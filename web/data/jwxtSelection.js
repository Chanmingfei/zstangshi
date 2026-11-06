/**
 * jwxtSelection.js —— 自主选课（N253512）· 按唐师真实结构实现
 * 契约来源是本机铁证（docs/diagnostics/：Index 样本 + 官方 zzxkYzb.js + 真机 logcat），
 * 不再是 zfn_api 通用流程。要点：
 *   · 无 role=tab 板块页签；hidden input 同时有 id 与 name（sessionUserKey 等只有 id），
 *     zzxkYzb.js 全部按 #id 取值 → 收集时键同时挂 name 与 id
 *   · 总闸门 `if($("#iskxk").val()=="1")`（79 行）：iskxk=0 = 不在选课时间窗，页面连
 *     板块默认值（firstXkkzId/firstKklxdm/firstNjdmId/firstZyhId）都不渲染，没有课池
 *     —— 此时返回 ok + closed 轮次 + 已选课程，**绝不降级演示数据、绝不编造轮次**
 *   · ② Display {xkkz_id,kklxdm,xszxzt,njdm_id,zyh_id,kspage:0,jspage:0}；③ PartDisplay
 *     的 requestMap = 查询条件 + 全部 hidden 字段，rst.tmpList 即课池（每行一个教学班）；
 *     549-560 行 JxbWithKch 按课程补全教师/时间/容量
 * 响应字段名 / 已选表格结构 / 提交协议仍待窗口开放实测。
 */

import { request, getEndpoints, getSemesterCode, SOURCE } from './jwxtClient.js';
import { ok, fail, makeError } from './provider.js';
import { parseWeeks, parseSections, TYPE_MAP } from './jwxtParse.js';

/** 星期文案 → dayOfWeek（1=周一 … 7=周日） */
const WEEKDAY_MAP = Object.freeze({ 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 日: 7, 天: 7 });

/** PartDisplay / JxbWithKch 的 requestMap 字段全集（zzxkYzb.js 549-560 与 901-907 行并集），值全来自 hidden 表 */
const REQUEST_MAP_FIELDS = Object.freeze([
  'rwlx', 'xklc', 'xkly', 'bklx_id', 'sfkkjyxdxnxq', 'kzkcgs', 'xqh_id',
  'jg_id_1', 'njdm_id_1', 'zyh_id_1', 'gnjkxdnj', 'zyh_id', 'zyfx_id', 'njdm_id', 'bh_id',
  'bjgkczxbbjwcx', 'xbm', 'xslbdm', 'mzm', 'xz', 'ccdm', 'xsbj', 'sfkknj', 'sfkkzy',
  'kzybkxy', 'sfznkx', 'zdkxms', 'sfkxq', 'bhbcyxkjxb', 'sfkcfx', 'kkbk', 'kkbkdj',
  'bklbkcj', 'sfkgbcx', 'sfrxtgkcxd', 'xkkz_xh', 'tykczgxdcs', 'xkxnm', 'xkxqm',
  'kklxdm', 'bbhzxjxb', 'zxgbxkkg', 'xkkz_id', 'rlkz', 'xkzgbj', 'jxbzcxskg',
]);

/** 教务被打回登录页的负向证据（会话过期统一按 AUTH 归因） */
function looksLoggedOut(text) {
  return /login_slogin/i.test(String(text || ''));
}

function authFail() {
  return fail(makeError('AUTH', { message: '教务会话已过期，请重新登录' }), { source: SOURCE });
}

/** JSON.parse 失败返回 null，绝不抛 */
function tryJson(text) {
  try { return JSON.parse(String(text || '')); } catch { return null; }
}

/** 从 JSON 对象 / 嵌套包装里挑出数组（正方常见键 tmpList/items/rows/data） */
export function listOf(payload) {
  let rows = payload;
  if (payload && !Array.isArray(payload) && typeof payload === 'object') {
    rows = payload.tmpList || payload.items || payload.rows || payload.data || [];
  }
  return Array.isArray(rows) ? rows : [];
}

/** 行记录键小写归一后按候选名取第一个非空值 */
function makePicker(row) {
  const low = {};
  for (const [key, value] of Object.entries(row || {})) low[String(key).toLowerCase()] = value;
  return (...keys) => {
    for (const key of keys) {
      const value = low[String(key).toLowerCase()];
      if (value !== undefined && value !== null && value !== '') return String(value).trim();
    }
    return '';
  };
}

/**
 * 收集页面全部 hidden input。zzxk.js 按 #id 取值、Index 里部分字段只有 id 没有 name
 * （sessionUserKey / gnmkdmKey / csrftoken），所以 name 与 id 都作为键挂进同一张表。
 * @param {string} html
 * @returns {Record<string, string>} 键 = name 或 id（后者仅在无 name 时补充），值 = value
 */
export function collectHiddenInputs(html) {
  const hidden = {};
  const inputRe = /<input\b[^>]*type=["']hidden["'][^>]*>/gi;
  let input;
  while ((input = inputRe.exec(String(html || ''))) !== null) {
    const tag = input[0];
    const name = /name=["']([^"']+)["']/i.exec(tag);
    const id = /id=["']([^"']+)["']/i.exec(tag);
    const value = /value=["']([^"']*)["']/i.exec(tag);
    if (!name && !id) continue;
    const val = value ? value[1] : '';
    if (name) hidden[name[1]] = val;
    if (id) hidden[id[1]] = val; // name 与 id 双键：zzxk.js 一律按 #id 取值
  }
  return hidden;
}

/**
 * 解析选课 Index 页。
 * @param {string} html
 * @returns {{hidden: Record<string,string>, iskxk: string, notice: string,
 *            defaults: {xkkzId: string, kklxdm: string, njdmId: string, zyhId: string, xszxzt: string}}}
 */
export function parseSelectionIndex(html) {
  const hidden = collectHiddenInputs(html);
  const noticeMatch = /class=["']nodata["'][^>]*>\s*<span>([^<]+)<\/span>/.exec(String(html || ''));
  return {
    hidden,
    iskxk: String(hidden.iskxk || '0'),
    notice: noticeMatch ? noticeMatch[1].trim() : '',
    defaults: {
      xkkzId: hidden.firstXkkzId || '', kklxdm: hidden.firstKklxdm || '',
      njdmId: hidden.firstNjdmId || '', zyhId: hidden.firstZyhId || '', xszxzt: hidden.xszxzt || '',
    },
  };
}

/** 从 jsxx / sksj（「周X第N,M节{第A-B周};…」类文案）拆星期 / 节次 / 周次 / 尾段 */
export function parseTimeText(text) {
  const src = String(text || '');
  if (!src.trim()) return null;
  const parts = src.split(';').map((part) => part.trim()).filter(Boolean);
  const timePart = parts.find((part) => /[周星期]|节/.test(part)) || src;
  const tail = [...parts].reverse().find((part) => part !== timePart && part.length > 0
    && !/^[0-9\-,\s周第节单双{}]+$/.test(part)) || '';
  const dayMatch = /[周星期]\s*([一二三四五六日天])/.exec(timePart);
  const dayOfWeek = dayMatch ? WEEKDAY_MAP[dayMatch[1]] || 0 : 0;
  const sections = [];
  const sectionRe = /第?\s*(\d+)\s*(?:[-,，]\s*(\d+))?\s*节/g;
  let sec;
  while ((sec = sectionRe.exec(timePart)) !== null) {
    const lo = Number(sec[1]);
    const hi = sec[2] ? Number(sec[2]) : lo;
    for (let s = Math.min(lo, hi); s <= Math.max(lo, hi); s += 1) sections.push(s);
  }
  const weeks = parseWeeks(src);
  if (dayOfWeek < 1 || sections.length === 0 || weeks.length === 0) return null;
  return { dayOfWeek, sections: [...new Set(sections)].sort((a, b) => a - b), weeks, tail };
}

/**
 * PartDisplay tmpList 一行（或 JxbWithKch 一行）→ P09 的 Course。
 * id 用 jxb_id（页面行键），code 用 kch/kch_id；时间/教师来自 sksj/jsxx，字段缺失时
 * 返回不完整的 Course（容量/时间可由 JxbWithKch 二次补全），只在无课名时返回 null。
 */
export function jxbRowToCourse(row, kchId) {
  if (!row || typeof row !== 'object') return null;
  const pick = makePicker(row);
  const name = pick('kcmc', 'kcmcmc');
  if (!name) return null;
  const time = parseTimeText(pick('sksj', 'jsxx', 'skjsxx'));
  const nature = pick('kclxmc', 'kcxzmc', 'kcflmc');
  const typeMatch = TYPE_MAP.find(([re]) => re.test(nature));
  return {
    id: pick('jxb_id', 'do_jxb_id') || `${kchId || pick('kch_id', 'kch')}-${name}`,
    code: pick('kch', 'kch_id') || String(kchId || ''),
    name,
    teacher: pick('jsxm', 'jsm'),
    credits: Number(pick('xf', 'jxbxf')) || 0,
    courseType: typeMatch ? typeMatch[1] : '',
    assessment: /考查/.test(nature) ? 'check' : 'exam',
    campus: pick('xqmc') || '',
    location: pick('jxdd', 'cdmc') || (time ? time.tail : '') || '未安排',
    weeks: time ? time.weeks : parseWeeks(pick('jxbzc', 'zcd')),
    dayOfWeek: time ? time.dayOfWeek : 0,
    sections: time ? time.sections : parseSections(pick('jc', 'jcs')),
    startTime: '', endTime: '',
    capacity: Number(pick('jxbrl', 'rsyxz')) || 0,
    enrolled: Number(pick('yxzrs', 'rsz')) || 0,
    isSelected: false,
  };
}
/** tmpList / 教学班数组 → Course[] */
export function mapJxbList(payload, kchId) {
  let rows = payload;
  if (payload && !Array.isArray(payload) && typeof payload === 'object') {
    rows = payload.tmpList || payload.items || payload.rows || payload.data || [];
  }
  if (!Array.isArray(rows)) return [];
  return rows.map((row) => jxbRowToCourse(row, kchId)).filter(Boolean);
}

/**
 * 已选课程（cxZzxkYzbChoosed.html 是 .load 进 #choosedBox 的 HTML 表格）→ Course[]。
 * 表格内部结构**待真机验证**：按 <tr> 分行，行内 hidden input + 单元格文本兜底；
 * 解析不出行就返回空数组，由调用方保持沉默而不是编数据。
 */
export function parseChoosed(html) {
  const src = String(html || '');
  const courses = [];
  const trRe = /<tr\b[^>]*>([\s\S]*?)<\/tr>/gi;
  let tr;
  while ((tr = trRe.exec(src)) !== null) {
    const rowHtml = tr[1];
    const hidden = collectHiddenInputs(rowHtml);
    const pick = makePicker(hidden);
    const cells = [...rowHtml.matchAll(/<t[dh]\b[^>]*>([\s\S]*?)<\/t[dh]>/gi)]
      .map((m) => m[1].replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim())
      .filter(Boolean);
    const name = pick('kcmc') || cells.find((text) => /[\u4e00-\u9fa5]/.test(text) && !/^(已选|删除|退课)/.test(text)) || '';
    if (!name) continue;
    const time = parseTimeText(pick('sksj') || cells.find((text) => /节|周/.test(text)) || '');
    courses.push({
      id: pick('jxb_id', 'do_jxb_id', 'kch_id') || `${name}-${courses.length}`,
      code: pick('kch_id', 'kch') || '',
      name,
      teacher: pick('jsxm') || '',
      credits: Number(pick('xf', 'jxbxf')) || Number((/^[\d.]+$/.test(cells[1] || '') ? cells[1] : '')) || 0,
      courseType: '',
      assessment: 'exam',
      campus: '',
      location: pick('jxdd', 'cdmc') || '',
      weeks: time ? time.weeks : [],
      dayOfWeek: time ? time.dayOfWeek : 0,
      sections: time ? time.sections : [],
      startTime: '',
      endTime: '',
      capacity: Number(pick('jxbrl', 'rsyxz')) || 0,
      enrolled: 0,
      isSelected: true,
    });
  }
  return courses;
}

/* ═══ 链路 ═══════════════════════════════════════════════════════════════ */

/** requestMap：契约字段全集 ← hidden 表（name/id 双键），再并入 extra（kch_id/cxbj 等） */
function buildRequestMap(hidden, extra) {
  const map = {};
  for (const field of REQUEST_MAP_FIELDS) map[field] = hidden[field] !== undefined ? hidden[field] : '';
  return { ...map, ...extra };
}

/**
 * 抓 P09 选课数据。iskxk≠1 → **ok**：closed 轮次 + 教务原文提示 + 已选课程；
 * iskxk=1 → Display 建上下文 → PartDisplay tmpList 课池 → JxbWithKch 补全缺时间的课程。
 * @returns {Promise<object>} ProviderResult，data = {semesterId, round, courses, chain}
 */
export async function fetchCoursePool() {
  const ep = getEndpoints();

  // ① Index：iskxk 闸门 + hidden 表 + nodata 提示原文
  const page = await request(ep.selection, { method: 'GET' });
  if (!page.ok) return page;
  if (looksLoggedOut(page.text)) return authFail();
  const index = parseSelectionIndex(page.text);

  const manual = getSemesterCode();
  const semesterId = manual.xnm ? `${manual.xnm}-${manual.xqm === '12' ? '2' : manual.xqm === '16' ? '3' : '1'}` : 'current';

  if (index.iskxk !== '1') {
    // 闸门关闭：真实状态回给页面，不造轮次、不降级演示数据
    const selected = await request(ep.selectionChoosed, { method: 'GET' });
    const courses = selected.ok && !looksLoggedOut(selected.text) ? parseChoosed(selected.text) : [];
    return ok({
      semesterId,
      round: {
        label: '自主选课', status: 'closed',
        note: index.notice || '当前不在选课时间窗', openAt: '', closeAt: '', maxCourses: 0,
      },
      courses,
      chain: null,
    }, { source: SOURCE });
  }

  // 闸门开：板块默认值必须由服务端渲染出来，缺失 = 页面形态又变了
  if (!index.defaults.xkkzId) {
    return fail(makeError('PARSE', {
      detail: 'iskxk=1 但页面没有 firstXkkzId/firstKklxdm 默认值，需按真实开放态样本校准',
    }), { source: SOURCE });
  }

  // ② Display：建选课上下文，响应页带回 rwlx/xkxnm/xkxqm 等全部 hidden 字段
  const display = await request(ep.selectionDisplay, {
    method: 'POST',
    body: {
      xkkz_id: index.defaults.xkkzId, kklxdm: index.defaults.kklxdm, xszxzt: index.defaults.xszxzt,
      njdm_id: index.defaults.njdmId, zyh_id: index.defaults.zyhId, kspage: 0, jspage: 0,
    },
  });  if (!display.ok) return display;
  if (looksLoggedOut(display.text)) return authFail();
  const hidden = { ...index.hidden, ...collectHiddenInputs(display.text) };
  const xkxnm = hidden.xkxnm || manual.xnm || '';
  const xkxqm = hidden.xkxqm || manual.xqm || '';
  if (!xkxnm || !xkxqm) {
    return fail(makeError('PARSE', {
      detail: '选课上下文里找不到 xkxnm/xkxqm，请调用 configureRemote({ semester: { xnm, xqm } }) 指定',
    }), { source: SOURCE });
  }
  const chain = {
    xkkzId: index.defaults.xkkzId, kklxdm: hidden.kklxdm || index.defaults.kklxdm,
    hidden, xkxnm, xkxqm,
  };

  // ③ PartDisplay：rst.tmpList 即课池（每行一个教学班）
  const part = await request(ep.selectionPart, {
    method: 'POST',
    body: buildRequestMap(hidden, { kspage: '1', jspage: '15' }),
  });
  if (!part.ok) return part;
  if (looksLoggedOut(part.text)) return authFail();
  const rst = tryJson(part.text);
  if (!rst) {
    return fail(makeError('PARSE', { detail: 'PartDisplay 返回的不是 JSON，需按真实响应校准' }), { source: SOURCE });
  }
  if (rst.flag === '0') {
    return fail(makeError('UNAVAILABLE', { message: String(rst.msg || '教务拒绝了本次课池查询') }), { source: SOURCE });
  }
  const courses = mapJxbList(rst, '');
  if (courses.length === 0) {
    return fail(makeError('PARSE', { detail: 'PartDisplay tmpList 为空或字段与预期不符，需真机校准' }), { source: SOURCE });
  }

  // ④ 补全缺时间的课程（正方初始渲染不含教师/时间/容量，点击行才按课程拉教学班）
  const needEnrich = [...new Set(courses
    .filter((course) => course.dayOfWeek < 1 || course.sections.length === 0 || !course.capacity)
    .map((course) => course.code))].filter(Boolean);
  let enrichFailed = 0;
  for (const kchId of needEnrich) {
    const res = await request(ep.selectionJxb, {
      method: 'POST',
      body: buildRequestMap(hidden, {
        kch_id: kchId,
        cxbj: '',
        fxbj: '',
      }),
    });
    if (!res.ok) { enrichFailed += 1; continue; }
    const rows = tryJson(res.text);
    if (!Array.isArray(rows) || rows.length === 0) { enrichFailed += 1; continue; }
    for (const data of rows) {
      const target = courses.find((course) => course.id === String(data.jxb_id) && course.code === kchId);
      if (!target) continue;
      const time = parseTimeText(String(data.sksj || ''));
      if (time) {
        target.weeks = time.weeks;
        target.dayOfWeek = time.dayOfWeek;
        target.sections = time.sections;
        if (time.tail) target.location = time.tail;
      }
      if (data.jxbrl) target.capacity = Number(data.jxbrl) || target.capacity;
      if (data.yxzrs) target.enrolled = Number(data.yxzrs) || target.enrolled;
      if (data.xf) target.credits = Number(data.xf) || target.credits;
      if (data.jxdd) target.location = String(data.jxdd);
    }
  }

  return ok({
    semesterId: `${xkxnm}-${xkxqm === '12' ? '2' : xkxqm === '27' || xkxqm === '16' ? '3' : '1'}`,
    round: {
      label: '自主选课', status: 'open',
      note: enrichFailed > 0 ? `部分课程教学班详情补全失败（${enrichFailed}/${needEnrich.length}）` : '',
      openAt: '', closeAt: '', maxCourses: 0,
    },
    courses,
    chain,
  }, { source: SOURCE });
}

/**
 * 选课提交（写操作）。唐师页面目前只暴露「快速选课」：POST xkZzxkyzbQuickly，body 仅
 * {xkkz_id}（zzxkYzb.js 470 行 chooseCoursesQuickly，响应 {flag:'0'|'1', msg}）。它的
 * 语义更像「按推荐快速选完本轮」，与「加选单门」不等价 —— **待窗口开放后真机实测确认
 * 单门提交协议**（可能在内联 HTML 的行内 onclick 里，iskxk=0 的样本看不到）。退课同样
 * 未实测，返回 NOT_IMPLEMENTED。保守策略：轮次未开放（chain 为空）不放行；响应无
 * flag='1' 正向证据一律失败，教务原文带回 detail，绝不把无响应当成功。
 */
export async function submitSelection(chain, course, want) {
  if (!want) {
    return fail(makeError('NOT_IMPLEMENTED', {
      message: '退课接口尚未实测，暂不开放退课操作',
      detail: '待窗口开放后抓取退课协议再放开，避免误退',
    }), { source: SOURCE });
  }
  if (!chain || !chain.xkkzId) {
    return fail(makeError('NOT_IMPLEMENTED', {
      message: '当前不在选课时间窗，无法提交选课',
      detail: '提交需要选课上下文 xkkz_id，闸门关闭时页面不渲染该值',
    }), { source: SOURCE });
  }
  const ep = getEndpoints();
  const res = await request(ep.selectionSubmit, { method: 'POST', body: { xkkz_id: chain.xkkzId } });
  if (!res.ok) return res;
  if (looksLoggedOut(res.text)) return authFail();
  const json = tryJson(res.text);
  if (!json) {
    return fail(makeError('UNAVAILABLE', {
      message: '教务未确认选课结果，请到选课页刷新核对',
      detail: String(res.text || '').slice(0, 200),
    }), { source: SOURCE });
  }
  if (String(json.flag) !== '1') {
    return fail(makeError('UNAVAILABLE', {
      message: String(json.msg || '教务拒绝了本次选课请求'),
      detail: JSON.stringify(json).slice(0, 200),
    }), { source: SOURCE });
  }
  return ok({
    courseId: course ? course.id : '',
    isSelected: true,
    message: String(json.msg || ''),
    quickMode: true, // 提交协议未定，标记本次是「快速选课」语义，UI 不必区分但排障有用
  }, { source: SOURCE });
}
