/**
 * appActions.js —— 语义化 action 集合（ARCHITECTURE §4.3 的 action 表）
 * 页面只调 action 不直接 patch store；action 集中摸 store / provider / router /
 * storage，app.js 只剩「谁在什么时候调用」。依赖注入 `getRouter` 用 getter 打破
 * 「actions 先于 router 创建、guard 又要读 store」的环。
 */

import * as storage from './core/storage.js';
import { getSemesters, pickSemester, buildTermHeader } from './data/calendar.js';
import { readCustom, applyCustomTerm, xnmXqmOf } from './data/customTerm.js';
import { configureRemote } from './data/jwxtClient.js';
import { createSlotFetchers } from './data/slotFetchers.js';
import { absorbTimetable } from './data/timetableSync.js';
import { saveRestored } from './data/timetableBulk.js';
import { syncWidget } from './data/widget.js';
/** sessionStorage：有会话（演示态与正式登录都写；冷启动消失） */
export const SS_DEMO = 'zsts.demoSession';
/**
 * sessionStorage：**这次会话是不是演示态**。
 * 必须与 SS_DEMO 分开：正式登录刷新后若被恢复成 demo，provider 就会停发教务请求，
 * 用户会看到演示数据却以为是自己的成绩 —— 这是最容易出事的一处状态混淆。
 */
export const SS_IS_DEMO = 'zsts.isDemoSession';
/** sessionStorage：演示提示条已关闭（本次启动有效） */
export const SS_BANNER = 'zsts.bannerDismissed';

/**
 * 读 sessionStorage 标记。隐私模式/禁用存储时抛错，一律当「没有」。
 * @param {string} key
 * @returns {boolean}
 */
export function readFlag(key) {
  try {
    return window.sessionStorage.getItem(key) === '1';
  } catch {
    return false;
  }
}

/**
 * 写 sessionStorage 标记。
 * @param {string} key
 * @param {boolean} on
 */
export function writeFlag(key, on) {
  try {
    if (on) window.sessionStorage.setItem(key, '1');
    else window.sessionStorage.removeItem(key);
  } catch {
    /* 存不下就算了：这只影响「下次要不要再提示」 */
  }
}

/**
 * 建 action 集合。
 *
 * @param {{
 *   store: object,
 *   provider: object,
 *   getRouter: () => object|null
 * }} deps
 * @returns {object}
 */
export function createActions(deps) {
  const { store, provider, getRouter } = deps;

  /** 局部更新某个数据槽（其余槽保持引用不变，订阅者不会被误触发） */
  function patchSlot(name, partial) {
    const slots = store.getState().slots;
    store.patch({ slots: { ...slots, [name]: { ...slots[name], ...partial } } });
  }

  /** 当前学期对象（顶栏 context 指向的那个，取不到就退第一个） */
  function currentSemester() {
    const term = store.getState().term;
    if (!term.calendar) return null;
    const list = getSemesters(term.calendar);
    return list.find((item) => item.id === (term.context && term.context.semesterId)) || list[0] || null;
  }

  const FETCHERS = createSlotFetchers({ store, provider });

  const actions = {
    /** 合并 ui 子树的局部状态 */
    setUi(partial) {
      const ui = store.getState().ui;
      store.patch({ ui: { ...ui, ...partial } });
    },

    /**
     * 把全部数据槽复位为 idle（清空内存里的查询结果）。
     * 登录 / 演示模式切换时必须调用：WebView 里换账号页面**不会重载**，不清槽的话
     * A 的课表和成绩会原样留给 B —— 数据事故。logout 同样走这里。
     */
    resetSlots() {
      const slots = {};
      for (const key of Object.keys(store.getState().slots)) {
        slots[key] = { status: 'idle', data: null, error: null, updatedAt: null, fromCache: false, degraded: false };
      }
      store.patch({ slots });
    },

    /** 关掉演示提示条：本次启动内（含刷新）不再出现，冷启动重新出现 */
    dismissBanner() {
      const source = store.getState().source;
      store.patch({ source: { ...source, bannerDismissed: true } });
      writeFlag(SS_BANNER, true);
    },

    /** 跳转（委托 router） */
    navigate(path) {
      const router = getRouter();
      if (router) router.go(path);
    },

    /**
     * 重算学期上下文（顶栏 / 周数 / 学期码）：校历 pickSemester → 用户自定义（customTerm）
     * 覆盖 → buildTermHeader；自定义存在时同步 configureRemote 学期码，让全部教务抓取走该
     * 学期（fetchKb / 推荐课表 / 选课链路里 manual 优先于教务页面自选值）。boot 与我的页
     * 保存共用这里，保证「谁算的都是同一份」。
     * @param {object} calendar 校历 JSON
     * @param {string} anchorIso 锚点日期（demo 模式用演示日，真实模式用系统今天）
     */
    recomputeTerm(calendar, anchorIso) {
      if (!calendar) return;
      const anchorDate = new Date(`${anchorIso}T09:00:00`);
      const semester = applyCustomTerm(pickSemester(anchorDate, calendar), readCustom());
      const context = buildTermHeader(anchorDate, semester);
      const semesters = getSemesters(calendar).map((item) => ({
        id: item.id,
        label: `${item.academicYear} 学年${item.termLabel}`,
        isCurrent: Boolean(semester && item.id === semester.id),
      }));

      const term = store.getState().term;
      store.patch({ term: { ...term, calendar, context, todayIso: anchorIso, semesters } });

      const ui = store.getState().ui;
      store.patch({ ui: { ...ui, weekNo: context.week || ui.weekNo } });
      // 学期码只在「用户自定义」时下发：无自定义保持空串让教务页面自选优先 ——
      // 否则校历 JSON 过期时会把旧学期强灌给课表抓取，违背本功能的初衷。
      configureRemote({
        semester: readCustom() ? xnmXqmOf(context.semesterId) : { xnm: '', xqm: '' },
      });
    },

    /**
     * 我的页「学期与教学周设置」保存后调用：重算学期上下文，并把跟着学期走的
     * todayCourses / timetable 槽复位重取（成绩等与学生强相关的槽不动，避免多打请求）。
     */
    applyTermSettings() {
      const term = store.getState().term;
      if (!term.calendar) return;
      actions.recomputeTerm(term.calendar, term.todayIso);
      for (const name of ['todayCourses', 'timetable']) {
        patchSlot(name, { status: 'idle', data: null, error: null, fromCache: false });
      }
      actions.loadSlot('todayCourses');
      actions.loadSlot('timetable');
    },
    /**
     * 统一取数：成功 → ready；失败且有旧数据 → stale（展示缓存 + 上次同步）；否则 error。
     * @param {string} name
     * @param {{force?: boolean}} [options]
     * @returns {Promise<object|null>} ProviderResult
     */
    async loadSlot(name, options = {}) {
      const fetcher = FETCHERS[name];
      if (!fetcher) {
        console.warn('[zsts] 未知数据槽', name);
        return null;
      }
      const before = store.getState().slots[name];
      if (before.status === 'loading') return null;
      // 已有数据且没要求强制刷新：直接复用，避免每次进页面都闪一下骨架
      if (before.status === 'ready' && !options.force) return null;

      patchSlot(name, { status: 'loading', error: null });
      const result = await fetcher();

      if (result && result.ok) {
        patchSlot(name, {
          status: 'ready',
          data: result.data,
          error: null,
          updatedAt: result.meta ? result.meta.fetchedAt : new Date().toISOString(),
          fromCache: Boolean(result.meta && result.meta.fromCache),
          degraded: Boolean(result.meta && result.meta.degraded),
        });
        if (name === 'timetable') absorbTimetable(store.getState(), result);
      } else {
        const error = (result && result.error) || { code: 'UNKNOWN', message: '取数失败' };
        const hadData = before.data !== null && before.data !== undefined;
        patchSlot(name, { status: hadData ? 'stale' : 'error', error, data: hadData ? before.data : null });
        const app = store.getState().app;
        if (error.code === 'NETWORK' && !app.offline) store.patch({ app: { ...app, offline: true } });
      }
      return result || null;
    },

    /**
     * 以教务为准重写个人课表（改错了想恢复时用）。
     * 与 `loadSlot('timetable', { force: true })` 不同：那条路规则③会**保留**手改的课，
     * 这里要的正是丢掉手改，所以走 saveRestored。三道闸：教务没连上 / 降级到演示 /
     * 返回空课表，任一成立都不碰本地 —— 宁可不同步，也不清空。
     *
     * @returns {Promise<{ok: boolean, stats?: object, local?: object, error?: object}>}
     */
    async resyncTimetable() {
      const context = store.getState().term.context;
      const semesterId = context && context.semesterId;
      const result = await provider.getTimetable(semesterId);
      const fail = (message) => ({ ok: false, error: { message } });
      if (!result || !result.ok) return fail('没能连上教务系统，本地课表未改动');
      if (result.meta && result.meta.degraded) return fail('教务暂不可达，同步已取消');
      const courses = result.data && Array.isArray(result.data.courses) ? result.data.courses : [];
      if (courses.length === 0) return fail('教务返回空课表，已取消同步');

      const saved = saveRestored(semesterId, courses);
      patchSlot('timetable', {
        status: 'ready', data: result.data, error: null,
        updatedAt: (result.meta && result.meta.fetchedAt) || new Date().toISOString(),
        fromCache: Boolean(result.meta && result.meta.fromCache), degraded: false,
      });
      syncWidget(store.getState());
      return { ok: true, stats: saved.stats, local: saved.local };
    },

    /**
     * 加选 / 退选一门课。**全项目第二个写操作**（第一个是登录）。
     * 三道闸在这里拦（页面只负责弹 error.message，页面自己没有判断能力）：
     * ① 轮次门数上限 round.maxCourses；② 余量 enrolled>=capacity（本地先拒省一次往返）；
     * ③ 轮次未开放 round.status!=='open'。乐观更新 + 失败回滚（点一下等 600ms 用户会连点
     * 三次；回滚安全是因为 provider.toggleCourse 失败注入会先回滚自己那份内存拷贝）。
     * `ui.selectionIntent` 同步维护：加选追加、退选移除 —— 数组顺序即志愿顺序。
     *
     * @param {string} courseId
     * @returns {Promise<object|null>} ProviderResult（页面据此弹 toast）
     */
    async toggleSelection(courseId) {
      const key = String(courseId || '');
      const data = store.getState().slots.selection.data;
      if (!key || !data || !Array.isArray(data.courses)) return null;

      const at = data.courses.findIndex((course) => course.id === key);
      if (at < 0) return null;
      const course = data.courses[at];
      const want = !course.isSelected;

      const round = data.round || {};
      if (want && round.status !== 'open') {
        return { ok: false, error: { code: 'CLOSED', message: '本轮选课未开放', retryable: false } };
      }
      const max = Number(round.maxCourses) || 0;
      if (want && max > 0 && data.courses.filter((item) => item.isSelected).length >= max) {
        return { ok: false, error: { code: 'LIMIT', message: `本轮最多选 ${max} 门，先取消一门再选`, retryable: false } };
      }
      if (want && course.enrolled >= course.capacity) {
        return { ok: false, error: { code: 'FULL', message: '这门课已满，无法加选', retryable: false } };
      }

      const ui = store.getState().ui;
      const intent = Array.isArray(ui.selectionIntent) ? ui.selectionIntent.slice() : [];

      // 乐观更新：只换被动到的那一条，其余引用保持不变（列表不会整体重渲）
      const nextCourses = data.courses.slice();
      nextCourses[at] = {
        ...course,
        isSelected: want,
        enrolled: Math.max(0, Math.min(course.capacity, course.enrolled + (want ? 1 : -1))),
      };
      patchSlot('selection', { data: { ...data, courses: nextCourses } });
      store.patch({
        ui: {
          ...ui,
          selectionIntent: want ? [...intent.filter((id) => id !== key), key] : intent.filter((id) => id !== key),
        },
      });

      const result = await provider.toggleCourse(key, want);
      if (!result || !result.ok) {
        patchSlot('selection', { data }); // 退回闭包里抓的原数据，不做第二套回滚逻辑
        store.patch({ ui: { ...store.getState().ui, selectionIntent: intent } });
      }
      return result || null;
    },

    /**
     * 单条成绩详情。不复用 `loadSlot`（按槽名取数，这里目标 id 每次不同，且「查不到」
     * 是正常业务态 ok(null)）。并发保护：返回时槽里的 id 变了（用户已点别的成绩）直接
     * 丢弃结果，否则会短暂把 A 的分数画到 B 的页面上。
     *
     * @param {string} id
     * @returns {Promise<object|null>} ProviderResult
     */
    async loadGrade(id) {
      const key = String(id || '');
      if (!key) return null;
      const before = store.getState().slots.gradeDetail;
      if (before.id === key && (before.status === 'ready' || before.status === 'loading')) return null;

      patchSlot('gradeDetail', {
        id: key, status: 'loading', data: null, error: null,
        updatedAt: null, fromCache: false, degraded: false,
      });

      const result = await provider.getGradeById(key);
      if (store.getState().slots.gradeDetail.id !== key) return result; // 已经切走了

      if (!result || !result.ok) {
        patchSlot('gradeDetail', {
          status: 'error',
          error: (result && result.error) || { code: 'UNKNOWN', message: '这条成绩没能取回来' },
          data: null,
        });
        return result || null;
      }

      // ok(null) = 教务里已经没有这条记录了（比如学期归档后被清理），页面显示「记录已更新」
      patchSlot('gradeDetail', {
        status: result.data ? 'ready' : 'missing',
        data: result.data || null,
        error: null,
        updatedAt: (result.meta && result.meta.fetchedAt) || new Date().toISOString(),
        fromCache: Boolean(result.meta && result.meta.fromCache),
        degraded: Boolean(result.meta && result.meta.degraded),
      });
      return result;
    },

    /**
     * 登录：guest → pending → authed / failed。
     * @param {string} studentNo
     * @param {string} password
     * @param {{rememberId?: boolean}} [options]
     * @returns {Promise<object>} ProviderResult
     */
    async login(studentNo, password, options = {}) {
      const session = store.getState().session;
      store.patch({ session: { ...session, status: 'pending', error: null } });

      const result = await provider.login(studentNo, password);
      if (result && result.ok) {
        // 只持久化学号（CREDENTIAL_ALLOWLIST 只放行 rememberId），密码永远不落盘
        if (options.rememberId) storage.set('rememberId', studentNo);
        else storage.remove('rememberId');
        // 换身份 = 换数据：不点退出直接重登时，必须清掉上个账号留在内存里的 slots
        actions.resetSlots();
        const data = result.data || {};
        store.patch({
          session: {
            status: 'authed',
            studentNo: data.studentNo || studentNo,
            rememberId: Boolean(options.rememberId),
            name: data.name || '',
            error: null,
          },
        });
        writeFlag(SS_DEMO, true);
        writeFlag(SS_IS_DEMO, false); // 正式登录 → 教务抓取为主
      } else {
        const error = (result && result.error) || { message: '登录失败，请稍后重试' };
        store.patch({ session: { ...store.getState().session, status: 'failed', error } });
      }
      return result;
    },

    /**
     * 演示模式：不校验任何输入，一次点击直达首页。effective 置 demo 并复位提示条
     * （用户有权知道眼前是演示数据）；同时清空数据槽防残留上个账号的教务数据。
     */
    enterDemo() {
      const state = store.getState();
      actions.resetSlots();
      store.patch({
        session: { status: 'demo', studentNo: '', rememberId: state.session.rememberId, name: '', error: null },
        source: { ...state.source, effective: 'demo', bannerDismissed: false },
      });
      writeFlag(SS_DEMO, true);
      writeFlag(SS_IS_DEMO, true);
      writeFlag(SS_BANNER, false);
    },

    /** 退出登录：清会话与全部数据槽，回登录页 */
    async logout() {
      await provider.logout();
      actions.resetSlots();
      store.patch({
        session: { status: 'guest', studentNo: '', rememberId: false, name: '', error: null },
      });
      writeFlag(SS_DEMO, false);
      writeFlag(SS_IS_DEMO, false);
      const router = getRouter();
      if (router) router.go('/login');
    },
  };

  return actions;
}
