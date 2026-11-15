/**
 * remoteProvider.js —— 正方教务抓取数据源（DataProvider 的 remote 实现）
 *
 * 【这一层要回答的问题】
 * 用户登录后点进任意功能模块 → 后台抓取教务系统对应页面/接口 → 解析成 App 模型 →
 * 用 App 自己的 UI 渲染。App **不内置业务结论**：GPA 读教务原文、课表读 kbList、
 * 推荐课表读教务「推荐课表打印」，演示数据集只在抓取失败时作为降级副本。
 *
 * 分工：HTTP / 端点 / 加密 / 登录在 jwxtClient.js；页面文本 → 模型在 jwxtParse.js；
 * 本文件只做「哪个页面调哪个接口，抓回来怎么组装成 DataProvider 契约」。
 *
 * 【诚实边界】docs/remote-api-map.md §3.3「不允许猜地址」。2026-11-06 已用本地代理
 * 实测补齐首页菜单里全部 gnmkdm（§6）：成绩 N305005、自主选课 N253512、个人信息
 * N100801、推荐课表打印 N214505。注意「页面」不等于「数据接口」，成绩/课表都要在
 * 页面之后再 POST 一次才拿得到 JSON。推荐课表整条链（字典/清单/可看检查/课表本体）
 * 已实测打通（见 jwxtRecommend.js）；仍未实测的只有「自主选课提交协议」，保守实现
 * 于 jwxtSelection.js。**不编造路径，也不伪造业务数据**。
 *
 * 本文件不 import demoProvider —— 降级交给 provider.js 的 withFallback 统一处理。
 */

import { ok, fail, makeError } from './provider.js';
import { parseKbList, parseGpaPage, htmlToText, readSelectValue, parseGradeList } from './jwxtParse.js';
import {
  SOURCE, request, loginOnce,
  configureRemote, missingEndpoints, getEndpoints, getSemesterCode,
  fetchStudentName,
} from './jwxtClient.js';
import { fetchCoursePool, submitSelection } from './jwxtSelection.js';
import {
  fetchRecommendContext, fetchRecommendClasses, fetchClassTimetable,
  classRowToItem, semesterIdOf,
} from './jwxtRecommend.js';

/** @type {object|null} 最近一次成功登录的会话摘要 */
let session = null;
/** @type {object[]|null} 成绩列表内存缓存（详情按 id 查，不再打一次教务） */
let gradeCache = null;
/** 选课四步链的上文（hidden 表单 / kklxdm / 学期码），getCourseSelection 成功后缓存，提交写操作要用 */
let selectionChain = null;
/** @type {Map<string, {id: string, code: string}>} 课池 id → kch_id，提交 body 的 kch_id 来源 */
let selectionPool = new Map();
/**
 * 推荐课表链路上文（页面壳学期码 + 级联字典 + 按年级缓存的班级行）。
 * 换身份（login/logout）必须清空 —— 不能把 A 账号的默认班级喂给 B 账号。
 */
let recommendCtx = null;

/** 未实测端点的统一返回：明确说缺什么，不假装是网络问题 */
function notMeasured(moduleName, detail) {
  return fail(makeError('NOT_IMPLEMENTED', {
    message: `教务「${moduleName}」尚未打通，暂用演示数据`,
    detail: detail || '拿到 gnmkdm 后调用 configureRemote({ endpoints }) 补齐',
  }), { source: SOURCE });
}

/**
 * 抓一张课表：GET 页面（建立 gnmkdm 会话 + 读当前学期 xnm/xqm）→ POST kbList。
 * 个人课表与推荐课表共用这条管线，因为教务里这两个页面本来就是同一形态。
 *
 * 2026-11-06 修复：页面上的 xnm/xqm 是 <select><option selected>，不是带 value
 * 的 <input>。旧正则永远读不到，POST 空参后教务只回 `null`，课表必然降级。
 * 正方学期码 3/12/16 对应第 1/2/3 学期，默认是当前学年 + 3。
 */
async function fetchKb(pagePath, dataPath) {
  const page = await request(pagePath, { method: 'GET' });
  if (!page.ok) return page;
  const manual = getSemesterCode();
  const semester = {
    xnm: manual.xnm || readSelectValue(page.text, 'xnm'),
    xqm: manual.xqm || readSelectValue(page.text, 'xqm'),
  };
  if (!semester.xnm || !semester.xqm) {
    return fail(makeError('PARSE', {
      detail: '课表页找不到当前学年/学期（xnm/xqm），请调用 configureRemote({ semester }) 指定',
    }), { source: SOURCE });
  }
  const data = await request(dataPath, { method: 'POST', body: semester });
  if (!data.ok) return data;
  try { return ok(JSON.parse(data.text), { source: SOURCE }); } catch {
    return fail(makeError('PARSE', { detail: `${dataPath} 返回的不是 JSON` }), { source: SOURCE });
  }
}

/**
 * 建 remote provider。返回对象满足 provider.js 的 17 个方法契约。
 * @param {{proxyBase?: string, endpoints?: Record<string,string>}} [options]
 */
export function createRemoteProvider(options = {}) {
  if (options.proxyBase !== undefined || options.endpoints) configureRemote(options);
  const ep = getEndpoints();

  return {
    mode: SOURCE,

    async probe() {
      const res = await request(ep.loginPage, { method: 'GET', timeout: 3000 });
      return ok({
        reachable: Boolean(res.ok),
        reason: res.ok ? '' : (res.error && res.error.message) || '',
        missing: missingEndpoints(),
      }, { source: SOURCE });
    },

    /**
     * 登录：保留 3 次重试，但归因已更正（§6）。
     * 2026-11-06 实测发现此前「同一密码前两次报密码错误」的主因不是多节点，
     * 而是本地代理跟随重定向时复用了 POST 之前的旧 Cookie，登录换发的
     * JSESSIONID 在第二次 302 丢失。修复后单次即成功。重试仍保留，
     * 用于兜底 §3.1b 的多节点 csrftoken 绑定；文案始终不甩锅给密码。
     */
    async login(studentNo, password) {
      gradeCache = null; // 换身份先清成绩缓存：没点「退出登录」直接重登时，不能把上个账号的成绩喂给这个账号
      selectionChain = null; // 选课链路上文跟着换身份重置，提交 body 不能复用上个账号的 njdm/zyh
      selectionPool = new Map();
      recommendCtx = null; // 推荐课表的默认班级/字典跟着换身份重置
      let last = null;
      for (let attempt = 1; attempt <= 3; attempt += 1) {
        last = await loginOnce(studentNo, password);
        if (last.ok) {
          // 顺手抓姓名（失败返回空串，不影响登录结果）
          const name = await fetchStudentName();
          session = { studentNo, name, loginAt: new Date().toISOString() };
          return ok({ studentNo, name }, { source: SOURCE });
        }
        if (last.error.code !== 'AUTH') break; // 网络/加密问题重试也是白等
        if (last.error.definitive) break; // 教务明确说密码不正确：重试只会再错两次
        if (attempt < 3) await new Promise((r) => setTimeout(r, 800));
      }
      return fail(makeError(last && last.error ? last.error.code : 'AUTH', {
        message: last && last.error && last.error.definitive
          ? last.error.message // 教务明确回复（如密码不正确）：原文透传，不说「不一定是密码错误」
          : last && last.error.code === 'AUTH'
            ? '教务系统连续三次未通过，请稍后再试（不一定是密码错误）'
            : (last && last.error.message) || '登录失败',
      }), { source: SOURCE });
    },

    async logout() {
      session = null;
      gradeCache = null;
      selectionChain = null;
      selectionPool = new Map();
      recommendCtx = null;
      await request(ep.logoutAccount, { method: 'POST', body: {} });
      // 双保险：无论教务侧登出是否成功，本机 Cookie 一律清空 —— 残留的 JSESSIONID
      // 会让下一次登录「看起来成功」（实际在用旧会话），是跨账号数据事故的源头。
      try {
        if (typeof window !== 'undefined' && window.NativeBridge
          && typeof window.NativeBridge.clearSession === 'function') {
          window.NativeBridge.clearSession();
        }
      } catch { /* web 预览无桥 */ }
      return ok(null, { source: SOURCE });
    },

    getSession: () => session,

    /**
     * 会话恢复（冷启动，2026-11-06）：进程被杀后 sessionStorage 全丢，但 WebView
     * CookieManager 里的 JSESSIONID 仍在磁盘上、教务服务器侧会话往往未过期 ——
     * 用「记住的学号」探测一次已登录页面，通过就把会话捡回来，重开 App 不必重输密码。
     * 探测不过（Cookie 过期 / 被踢回登录页）返回 AUTH，启动流程照常进登录页。
     */
    async resumeSession(studentNo) {
      const no = String(studentNo || '');
      if (!no) return fail(makeError('AUTH', { detail: 'resumeSession 缺少学号' }), { source: SOURCE });
      const res = await request(ep.menuList, { method: 'GET', timeout: 6000 });
      if (!res.ok) return res;
      const authed = Boolean(res.text) && /gnmkdm/i.test(res.text) && !/login_slogin/i.test(res.text);
      if (!authed) return fail(makeError('AUTH', { message: '会话已过期，请重新登录' }), { source: SOURCE });
      const name = await fetchStudentName();
      session = { studentNo: no, name, loginAt: new Date().toISOString(), resumed: true };
      return ok({ studentNo: no, name }, { source: SOURCE });
    },

    // 校历来自教务处独立站点 jwc.tstc.edu.cn/xlcx.htm，不是正方教务的业务数据。
    // 由 app.js 读本地 school-calendar.json 注入，这里不重复抓。
    async getTermContext() { return ok(null, { source: SOURCE }); },
    async getSemesters() { return ok([], { source: SOURCE }); },

    async getTimetable(semesterId) {
      const res = await fetchKb(ep.timetablePage, ep.timetableData);
      if (!res.ok) return res;
      const courses = parseKbList(res.data, semesterId || 'current', { isSelected: true });
      if (courses.length === 0) {
        return fail(makeError('PARSE', { detail: 'kbList 为空或字段与预期不符，需校准解析器' }), { source: SOURCE });
      }
      return ok({
        semesterId: semesterId || 'current',
        weeks: [...new Set(courses.flatMap((c) => c.weeks))].sort((a, b) => a - b),
        courses,
      }, { source: SOURCE });
    },

    /** 今日课程不另打教务：课表已一次取全，本地按周次 + 星期筛即可 */
    async getTodayCourses(dateIso, week) {
      const res = await this.getTimetable('current');
      if (!res.ok) return res;
      const weekday = new Date(`${dateIso}T12:00:00`).getDay() || 7;
      return ok(res.data.courses
        .filter((c) => c.dayOfWeek === weekday && c.weeks.includes(Number(week)))
        .sort((a, b) => a.sections[0] - b.sections[0]), { source: SOURCE });
    },

    /**
     * 推荐课表（P08）：「信息查询 → 推荐课表打印」，链路在 jwxtRecommend.js（铁证
     * docs/diagnostics/bjkbdy-*）。入口一个方法通吃两种用法（withFallback 只包契约
     * 方法，不另开方法名）：
     *   · loadSlot 调用（无参）：页面壳读学期码 + 全量级联字典（服务端渲染，30 年级 /
     *     161 专业）→ 清单默认第一行 = 学生自己班 → sfkz/壳/cxBjKb 拿 kbList；
     *   · 页面「查询」按钮调用（filter = {gradeId, departmentId, majorId, classId}）：按
     *     年级+院系翻页缓存班级行，选了班级就抓该班课表，只到中间级则只回级联清单不出课表。
     * 学期不可查看（sfkz="true"）原样 UNAVAILABLE，绝不拿别的班课表顶包。
     */
    async getRecommendedTimetable(filter) {
      const want = filter || {};
      if (!recommendCtx) {
        const ctx = await fetchRecommendContext();
        if (!ctx.ok) return ctx;
        recommendCtx = { ...ctx.data, rowsByGrade: new Map() };
      }
      const { semester, dictionaries } = recommendCtx;
      const semesterId = semesterIdOf(semester);

      // 轻量路径（2026-11-06 口径）：页面取消默认课表，进页只要级联字典，
      // 不翻页拉班级行 —— 用户主动查询才走下面的完整链路。
      if (want.contextOnly) {
        return ok({
          semesterId, dictionaries, classList: [], classId: '', className: '',
          courses: [], weeks: [],
          sourceNote: '选择年级 / 院系 / 专业 / 班级后点「查询」查看推荐课表',
          fetchedAt: new Date().toISOString(),
        }, { source: SOURCE });
      }

      const gradeKey = `${want.gradeId || ''}|${want.departmentId || ''}`;
      let cached = recommendCtx.rowsByGrade.get(gradeKey);
      if (!cached) {
        const list = await fetchRecommendClasses(semester, {
          gradeId: want.gradeId || '', departmentId: want.departmentId || '',
        });
        if (!list.ok) return list;
        cached = list.data.rows;
        recommendCtx.rowsByGrade.set(gradeKey, cached);
      }

      const midCascade = want.gradeId || want.majorId || want.departmentId;
      const picked = want.classId
        ? cached.find((row) => String(row.bh_id) === String(want.classId)) || null
        : midCascade ? null : cached[0] || null;
      if (want.classId && !picked) {
        return fail(makeError('PARSE', {
          detail: `所选班级 ${want.classId} 不在当前清单里（年级/专业筛选后无此班）`,
        }), { source: SOURCE });
      }
      const item = picked ? classRowToItem(picked) : null;
      const classList = cached.map(classRowToItem);

      if (!picked) {
        // 只到年级/专业级（级联中转）：回清单不出课表，页面据它填专业/班级下拉
        return ok({
          semesterId, dictionaries, classList, classId: '', className: '',
          courses: [], weeks: [],
          sourceNote: '请选择具体班级后点「查询」查看课表',
          fetchedAt: new Date().toISOString(),
        }, { source: SOURCE });
      }

      const kb = await fetchClassTimetable(picked, semesterId);
      if (!kb.ok) return kb;
      const courses = kb.data.courses;
      return ok({
        semesterId, dictionaries, classList,
        classId: item.classId, className: item.className,
        courses,
        weeks: [...new Set(courses.flatMap((c) => c.weeks))].sort((a, b) => a - b),
        sourceNote: `数据来自教务系统「信息查询 → 推荐课表打印」，班级「${item.className}」${item.college ? `（${item.college}）` : ''}；仅供参考，实际修读以自主选课结果为准`,
        fetchedAt: new Date().toISOString(),
      }, { source: SOURCE });
    },

    /** GPA：服务端渲染，直接读原文，客户端零计算（R11） */
    async getGPA() {
      const res = await request(ep.gpa, { method: 'GET' });
      if (!res.ok) return res;
      const gpa = parseGpaPage(htmlToText(res.text));
      if (gpa.gpa === null) {
        return fail(makeError('PARSE', { detail: '学业情况页找不到平均学分绩点' }), { source: SOURCE });
      }
      return ok(gpa, { source: SOURCE });
    },

    /**
     * 成绩：页面 N305005 只是壳，真实数据在 `/cjcx/cjcx_cxXsgrcj.html?doType=query`。
     * 空 xnm/xqm = 全部学期；queryModel.showCount / limit 用来一次取全（默认只给 10 条）。
     * 学年第几学期用 xnmmc + xqmmc 原文拼，不自己换算。
     */
    async getGrades() {
      if (!ep.gradesData) return notMeasured('学生成绩查询');
      const res = await request(ep.gradesData, {
        method: 'POST',
        body: {
          xnm: '', xqm: '', zd_fzdm: 'N305005-xs',
          page: '1', rows: '200', limit: '200',
          'queryModel.showCount': '200', 'queryModel.pageSize': '200',
          sidx: '', sord: 'asc',
        },
      });
      if (!res.ok) return res;
      let payload;
      try { payload = JSON.parse(res.text); } catch {
        return fail(makeError('PARSE', { detail: '学生成绩查询返回的不是 JSON' }), { source: SOURCE });
      }
      const grades = parseGradeList(payload);
      if (grades.length === 0) {
        return fail(makeError('PARSE', { detail: '成绩接口 items 为空或字段与预期不符，需校准解析器' }), { source: SOURCE });
      }
      gradeCache = grades;
      return ok(gradeCache, { source: SOURCE });
    },

    async getGradeById(id) {
      if (!gradeCache) {
        const res = await this.getGrades();
        if (!res.ok) return res;
      }
      return ok((gradeCache || []).find((g) => String(g.id) === String(id)) || null, { source: SOURCE });
    },

    /**
     * 自主选课课池（P09）：链路在 jwxtSelection.js，按唐师真实结构（铁证见
     * docs/diagnostics/）。iskxk=0（不在选课时间窗）时**返回 ok**：closed 轮次 +
     * 教务原文提示 + 已选课程，绝不降级演示数据。iskxk=1 时缓存 chain（xkkz_id 等
     * 提交上文）与 id→code 映射，toggleCourse 要用。
     */
    async getCourseSelection() {
      const res = await fetchCoursePool();
      if (!res.ok) return res;
      selectionChain = res.data.chain;
      selectionPool = new Map(res.data.courses.map((course) => [String(course.id), { id: course.id, code: course.code }]));
      const { chain, ...selection } = res.data; // chain 只留在内存里，不进 store
      return ok(selection, { source: SOURCE });
    },

    /**
     * 选课提交 / 退课（真实写操作，§8 共享知识第 10 条）。
     * 唐师页面当前只暴露「快速选课」（xkZzxkyzbQuickly，body 仅 xkkz_id），单门提交
     * 协议待窗口开放实测 —— 保守实现见 jwxtSelection.submitSelection：无正向证据一律
     * 失败，**绝不假成功**；appActions 的乐观更新靠回滚兜底。chain 为空 = 闸门关闭，
     * 由 submitSelection 返回「当前不在选课时间窗」。
     */
    async toggleCourse(courseId, selected) {
      if (!session) {
        return fail(makeError('AUTH', { message: '请先登录教务系统，再提交选课' }), { source: SOURCE });
      }
      const cached = selectionChain ? selectionPool.get(String(courseId)) || null : null;
      return submitSelection(selectionChain, cached, Boolean(selected));
    },

    async getProfile() {
      if (!ep.profile) return notMeasured('个人信息');
      const res = await request(ep.profile, { method: 'GET' });
      if (!res.ok) return res;
      return ok({ raw: htmlToText(res.text) }, { source: SOURCE });
    },

    async clearCache() {
      gradeCache = null;
      return ok({ freedBytes: 0 }, { source: SOURCE });
    },
  };
}

export default createRemoteProvider;
