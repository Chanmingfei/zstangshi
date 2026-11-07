/**
 * jwxtGradeParse.js —— 成绩相关解析（从 jwxtParse.js 拆出，只为守住单文件 400 行上限）
 *
 * 两块内容，来源不同但都归「成绩」：
 *   · 学生成绩查询（N305005 / `cjcx_cxXsgrcj.html`）：一门课一行，有学分/绩点/性质/考核方式；
 *   · 学生成绩明细查询（N305007 / `cjcx_cxXsKccjList.html`）：一门课一行**分项**（平时/期末）。
 * 两张表靠 `jxb_id` 对齐，合并口径见 mergeGradeComponents。
 *
 * 规矩与 jwxtParse.js 一致：只读原文，不换算绩点、不加权、不补造字段。
 */

import { TYPE_MAP, normalizeTerm } from './jwxtParse.js';

/**
 * 教务成绩行（`cjcx_cxXsgrcj.html?doType=query` 的 items）→ App Grade。
 * 只读原文，不做绩点换算、不加权、不补造缺失字段。
 * @param {object} row
 * @returns {object|null}
 */
export function gradeRowToGrade(row) {
  if (!row || typeof row !== 'object') return null;
  const low = {};
  for (const [key, value] of Object.entries(row)) low[key.toLowerCase()] = value;
  const pick = (...keys) => {
    for (const key of keys) {
      const value = low[key.toLowerCase()];
      if (value !== undefined && value !== null && value !== '') return String(value).trim();
    }
    return '';
  };

  const courseName = pick('kcmc', 'courseName');
  if (!courseName) return null;
  const rawScore = pick('cj');
  const numericScore = /^-?\d+(?:\.\d+)?$/.test(rawScore);
  const rawPoint = pick('jd', 'xfjd');
  const nature = `${pick('kcxzmc')} ${pick('kclbmc')}`;
  const typeMatch = TYPE_MAP.find(([re]) => re.test(nature));
  const xnm = pick('xnmmc', 'xnm');
  const xqm = pick('xqm');
  const semesterId = `${xnm}-${normalizeTerm(xqm, pick('xqmmc'))}`;
  const examNature = pick('ksxz');
  const isRetake = pick('cjsfzf') === '是' || /重修/.test(examNature);
  const grade = {
    id: pick('key') || `${semesterId}-${pick('kch')}-${pick('jxb_id')}`,
    courseId: pick('jxb_id', 'kch_id', 'kch'),
    courseCode: pick('kch'),
    courseName,
    credits: Number(pick('xf')) || 0,
    score: numericScore ? Number(rawScore) : null,
    gradePoint: /^-?\d+(?:\.\d+)?$/.test(rawPoint) ? Number(rawPoint) : null,
    courseType: typeMatch ? typeMatch[1] : 'required',
    assessment: /考查/.test(pick('khfsmc')) ? 'check' : 'exam',
    semesterId,
    examType: isRetake ? 'retake' : /补考/.test(examNature) ? 'makeup' : 'normal',
    isRetake,
  };
  // 文字成绩（优秀/合格/缓考/缺考）保留原文，不假装成数字
  if (!numericScore && rawScore) grade.scoreText = rawScore;
  if (/缓考|缺考/.test(rawScore)) grade.remark = rawScore;
  return grade;
}

/** 归一化「行数组」：{items} / {rows} / {data} / 裸数组 都认（正方各接口封套不一致） */
function rowsOf(payload) {
  if (Array.isArray(payload)) return payload;
  if (!payload || typeof payload !== 'object') return [];
  const rows = payload.items || payload.rows || payload.data;
  return Array.isArray(rows) ? rows : [];
}

/** 成绩接口 JSON → Grade[]（兼容 { items } / { rows } / 裸数组） */
export function parseGradeList(payload) {
  return rowsOf(payload).map(gradeRowToGrade).filter(Boolean);
}

/**
 * 「学生成绩明细查询」（N305007 / `cjcx_cxXsKccjList.html`）→ 按教学班归组的成绩构成。
 *
 * 该校接口一行 = 一门课的一个分项：`xmblmc` 是分项名（`平时(40%)` / `期末(60%)` /
 * `总评`），`xmcj` 是该项得分（可能是数字，也可能是「良好」）。同一 `jxb_id` 的多行
 * 就是这门课的构成明细 —— 成绩查询接口只给总评，平时/期末只有这里才有。
 *
 * 两条实测结论（2026-11-07 真机，全量 216 行）：
 *   · `总评` 行与成绩查询的 `cj` 逐条一致（76/76），所以**丢掉总评**，避免同一数字两处来源；
 *   · 明细比成绩查询少 6 门课（全是「网络学习」类纯总分课，本来就没有分项），
 *     因此明细只能**附加**在成绩查询之上，不能替代它。
 *
 * @param {object} payload 接口 JSON
 * @returns {Map<string, Array<{label: string, weight: number|null, score: string}>>}
 *   key = 教学班 jxb_id（缺失时退化为 `课号|学年|学期`）
 */
export function parseGradeComponents(payload) {
  const map = new Map();
  for (const row of rowsOf(payload)) {
    if (!row || typeof row !== 'object') continue;
    const low = {};
    for (const [key, value] of Object.entries(row)) low[key.toLowerCase()] = value;
    const pick = (...keys) => {
      for (const key of keys) {
        const value = low[key];
        if (value !== undefined && value !== null && value !== '') return String(value).trim();
      }
      return '';
    };

    const rawLabel = pick('xmblmc');
    const score = pick('xmcj');
    if (!rawLabel || !score) continue;
    const label = rawLabel.replace(/[(（][^)）]*[)）]/g, '').trim();
    // 总评是汇总行，成绩行的主分数已经展示它了，不再当分项
    if (label === '总评') continue;
    const weightMatch = /[(（]\s*(\d+(?:\.\d+)?)\s*[%％]\s*[)）]/.exec(rawLabel);

    const key = pick('jxb_id') || `${pick('kch')}|${pick('xnm')}|${pick('xqm')}`;
    if (!map.has(key)) map.set(key, []);
    const list = map.get(key);
    if (list.some((item) => item.label === label)) continue; // 同一分项重复出现只留第一条
    list.push({
      label,
      weight: weightMatch ? Number(weightMatch[1]) : null,
      score,
    });
  }
  return map;
}

/**
 * 把构成明细合进成绩列表（原地写 `components`）。
 * 只做「有就补上」，明细里没有这门课就保持原样 —— 少 6 门课的课表不该因此变空。
 * @param {object[]} grades
 * @param {Map<string, Array<object>>} components
 * @returns {object[]} 同一个数组引用
 */
export function mergeGradeComponents(grades, components) {
  if (!components || typeof components.get !== 'function') return grades;
  for (const grade of grades) {
    // 两张表都带 jxb_id，Grade.courseId 也是优先取它，直接对齐即可
    const list = components.get(String(grade.courseId || ''));
    if (list && list.length) grade.components = list;
  }
  return grades;
}
