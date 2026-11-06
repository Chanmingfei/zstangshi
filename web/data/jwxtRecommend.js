/**
 * jwxtRecommend.js —— 推荐课表打印（gnmkdm=N214505）真实数据链
 *
 * 【链路铁证】2026-11-06 PC 直连实测（样本 docs/diagnostics/bjkbdy-*，官方脚本
 * bjkbdy.js / ylBjkbdyIndex.js 为证）。功能入口在「信息查询 → 推荐课表打印」：
 *   ① GET  recommendPage（cxBjkbdyIndex）→ 服务端渲染出 xnm/xqm 学期下拉，以及
 *      年级 njdm_id（30 项）/ 专业 zyh_id（161 项）/ 班级 bh_id（2198 项）全量字典，
 *      级联数据源一次请求全拿到，**不需要另打接口编数据**。
 *   ② POST recommendList（cxBjkbdyTjkbList）→ body 是页面 paramMap() 口径的平铺参数，
 *      返回 {items:[班级行], totalResult}。**服务端每页固定 10 条**（rows=800 实测仍回
 *      10 行），按年级筛后翻页取全。
 *   ③ POST recommendSfkz（cxXnxqsfkz）→ 响应字符串 "true" = 该学期不可查看（软检查）。
 *   ④ POST recommend（ylBjkbdyIndex 预览壳）→ 对话框会注入 map，此步只为还原会话语境，
 *      失败不阻断。
 *   ⑤ POST recommendKb（cxBjKb，大写 K）→ body = 预览 map【平铺】（ylBjkbdyIndex.js
 *      182/184 行：url + data: map），返回 {kbList:[...], weekNum, kblx, ...}。
 *      kbList 行自带 zcd（'1-16周'）/ jcs / xqj / xm / jxbrs / xkrs，parseKbList 直接可吃。
 */

import { ok, fail, makeError } from './provider.js';
import { request, getEndpoints, getSemesterCode, SOURCE } from './jwxtClient.js';
import { readSelectValue, parseKbList, normalizeTerm } from './jwxtParse.js';

/** 班级清单接口的 paramMap 空筛选项（与页面 paramMap() 逐键对齐，实测缺键不报错但口径不变） */
const LIST_FILTERS = Object.freeze({
  xqh_id: '', njdm_id: '', xb_id: '', jg_id: '', zyh_id: '', zyfx_id: '',
  bh_id: '', xsdm: '', pyccdm: '', kclxdm: '', kclbdm: '', sfzhsjk: '',
  kbsjlyqz: '', zs: '', yf: '', sfcxxqh: '0',
});

/**
 * 翻页保险丝：全校区 467 个班，单年级最多百余；50 页（500 行）足够覆盖任一
 * 筛选组合，超过视为参数错误，不做无限翻页。
 */
const MAX_PAGES = 50;
/** 每页固定 10 条（rows 传多大都回 10，实测） */
const PAGE_SIZE = 10;

/**
 * 从 recommendPage HTML 里解析 select option → 级联字典。
 * option 形如 <option value="E2076…">电子商务(2316)</option>；value 空的「全部」项跳过。
 * @param {string} html
 * @returns {{xnm: string, xqm: string, grades: object[], majors: object[], classes: object[]}}
 */
export function parseFilterOptions(html) {
  const grab = (id) => {
    const block = new RegExp(`<select[^>]*id=["']${id}["'][^>]*>[\\s\\S]*?</select>`, 'i').exec(String(html || ''));
    if (!block) return [];
    return [...block[0].matchAll(/<option[^>]*value=["']([^"']*)["'][^>]*>([^<]*)</gi)]
      .filter((m) => m[1] !== '')
      .map((m) => ({ id: m[1].trim(), name: m[2].trim() }));
  };
  return {
    xnm: readSelectValue(html, 'xnm'),
    xqm: readSelectValue(html, 'xqm'),
    grades: grab('njdm_id'),
    departments: grab('jg_id'),
    majors: grab('zyh_id'),
    classes: grab('bh_id'),
  };
}

/** 班级行 → App 级联项（字段名固定为教务原文，别在页面层猜别名） */
export function classRowToItem(row) {
  return {
    gradeId: String(row.njdm_id || ''),
    gradeName: String(row.njmc || row.njdm_id || ''),
    majorId: String(row.zyh_id || ''),
    majorName: String(row.zymc || ''),
    classId: String(row.bh_id || ''),
    className: String(row.bjmc || row.bj || ''),
    college: String(row.jgmc || ''),
    population: Number(row.xkrs) || 0,
  };
}

/**
 * 班级清单请求体：paramMap 口径 + 筛选项 + 分页。
 * @param {{xnm: string, xqm: string}} semester
 * @param {{gradeId?: string, majorId?: string, classId?: string}} [filter]
 * @param {number} [page] 从 1 起
 */
export function buildListBody(semester, filter = {}, page = 1) {
  return {
    ...LIST_FILTERS,
    xnm: semester.xnm,
    xqm: semester.xqm,
    njdm_id: filter.gradeId || '',
    jg_id: filter.departmentId || '',
    zyh_id: filter.majorId || '',
    bh_id: filter.classId || '',
    page: String(page),
    rows: String(PAGE_SIZE),
  };
}

/**
 * 预览 map（ylMap）：字段清单来自实测（探测脚本 ④），cxBjKb / sfkz / 预览壳共用。
 * @param {object} row 班级清单原始行
 */
export function buildYlMap(row) {
  return {
    xnm: row.xnm || '', xqm: row.xqm || '', xnmc: row.xnmc || '', xqmmc: row.xqmmc || '',
    xqh_id: row.xqh_id || '', njdm_id: row.njdm_id || '', zyh_id: row.zyh_id || '',
    bh_id: row.bh_id || '', tjkbzdm: row.tjkbzdm || '', tjkbzxsdm: row.tjkbzxsdm || '',
    zymc: row.zymc || '', jgmc: row.jgmc || '', njmc: row.njmc || '', bj: row.bj || '',
    xkrs: row.xkrs || '', jsxm: row.jsxm || '', lxdh: row.lxdh || '', bh: row.bh || '',
    zs: '', zxszjjs: 'false', akcxqjchb: 'false', xsdm: '', kclxdm: '', kclbdm: '',
    kbsjlyqz: '', yf: '', kzlx: 'ck', sfcxxqh: row.xqh_id ? '1' : '0',
  };
}

/**
 * ① 页面壳：读学期码 + 全量级联字典。manual（configureRemote 传的学期）优先。
 * @returns {Promise<object>} ProviderResult<{semester, dictionaries}>
 */
export async function fetchRecommendContext() {
  const ep = getEndpoints();
  const page = await request(ep.recommendPage, { method: 'GET' });
  if (!page.ok) return page;
  const dicts = parseFilterOptions(page.text);
  const manual = getSemesterCode();
  const semester = {
    xnm: manual.xnm || dicts.xnm,
    xqm: manual.xqm || dicts.xqm,
  };
  if (!semester.xnm || !semester.xqm) {
    return fail(makeError('PARSE', {
      detail: '推荐课表页找不到 xnm/xqm，请调用 configureRemote({ semester }) 指定',
    }), { source: SOURCE });
  }
  return ok({ semester, dictionaries: { grades: dicts.grades, departments: dicts.departments, majors: dicts.majors } }, { source: SOURCE });
}

/**
 * ② 班级清单：按筛选项翻页取全（服务端每页 10 条）。
 * @param {{xnm: string, xqm: string}} semester
 * @param {{gradeId?: string, majorId?: string, classId?: string}} [filter]
 * @returns {Promise<object>} ProviderResult<{rows: object[], total: number}>
 */
export async function fetchRecommendClasses(semester, filter = {}) {
  const ep = getEndpoints();
  const rows = [];
  let total = 0;
  for (let page = 1; page <= MAX_PAGES; page += 1) {
    const res = await request(ep.recommendList, {
      method: 'POST',
      body: buildListBody(semester, filter, page),
    });
    if (!res.ok) return res;
    let json;
    try { json = JSON.parse(res.text); } catch {
      return fail(makeError('PARSE', { detail: '班级清单返回的不是 JSON' }), { source: SOURCE });
    }
    const items = Array.isArray(json.items) ? json.items : [];
    total = Number(json.totalResult) || total;
    rows.push(...items);
    if (items.length === 0 || rows.length >= total) break;
  }
  return ok({ rows, total }, { source: SOURCE });
}

/**
 * ③④⑤ 单班课表：sfkz 软检查 → 预览壳（失败不阻断）→ cxBjKb 平铺 → Course[]。
 * @param {object} row 班级清单原始行（含 njdm_id/zyh_id/bh_id/xnm/xqm…）
 * @param {string} semesterId App 学期 id（如 '2026-1'）
 * @returns {Promise<object>} ProviderResult<{courses: object[], kbJson: object}>
 */
export async function fetchClassTimetable(row, semesterId) {
  const ep = getEndpoints();
  const ylMap = buildYlMap(row);

  const sfkz = await request(ep.recommendSfkz, { method: 'POST', body: ylMap });
  if (sfkz.ok) {
    // 实测响应是 JSON 字符串字面量，带引号（如 "true"）；两种形态都认
    const flag = String(sfkz.text || '').trim().replace(/^"(.*)"$/, '$1');
    if (flag === 'true') {
      return fail(makeError('UNAVAILABLE', {
        message: '该学年的推荐课表当前不可查看（教务限制）',
      }), { source: SOURCE });
    }
  }

  // 预览壳只负责还原「对话框已打开」的会话语境；失败不阻断数据本体
  await request(ep.recommend, { method: 'POST', body: ylMap }).catch(() => null);

  const kb = await request(ep.recommendKb, { method: 'POST', body: ylMap });
  if (!kb.ok) return kb;
  let kbJson;
  try { kbJson = JSON.parse(kb.text); } catch {
    return fail(makeError('PARSE', { detail: 'cxBjKb 返回的不是 JSON，需校准' }), { source: SOURCE });
  }
  const raw = Array.isArray(kbJson && kbJson.kbList) ? kbJson.kbList : [];
  // rs/yxrs 是 parseKbList 的容量口径；推荐课表行里叫 jxbrs/xkrs，平移不加工
  const rows = raw.map((r) => ({ ...r, rs: r.rs || r.jxbrs, yxrs: r.yxrs || r.xkrs }));
  const courses = parseKbList({ kbList: rows }, semesterId);
  if (courses.length === 0) {
    return fail(makeError('PARSE', {
      detail: `班级「${row.bjmc || row.bh_id}」kbList ${raw.length} 行解析不出课程，需按实测样本校准`,
    }), { source: SOURCE });
  }
  return ok({ courses, kbJson }, { source: SOURCE });
}

/** 教务学期码 → App semesterId（3→1 / 12→2 / 16→3） */
export function semesterIdOf(semester) {
  return `${semester.xnm}-${normalizeTerm(semester.xqm, '')}`;
}
