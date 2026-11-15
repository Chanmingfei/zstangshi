/**
 * jwxtParse.js —— 正方教务页面 → App 数据模型的纯解析器
 *
 * 为什么要独立成文件：remoteProvider 负责「何时抓、抓哪个地址」，本文件只负责
 * 「抓回来的这堆东西怎么变成 Course / GPA」。纯函数、零 DOM 副作用依赖（htmlToText
 * 除外）、可在 Node 里直接跑，便于把真实抓回的原文固化成 fixture 做回归。
 *
 * 字段口径来自 docs/remote-api-map.md：
 *   · §4.1 课表 kbList：kcmc / cdmc / xqj / zcd / jc / xm / xqjmc / jcs
 *   · §1.6 学业情况页：服务端渲染纯文本，GPA 与学分树直接读原文，**不本地加权**
 *   · §4.2 真实作息 12 节（上午 1-4 / 下午 5-9 / 晚间 10-12）
 *
 * 【Course.weeks 是唯一真源】weekRange / weekParity 一律由 weeks 现算，
 * 绝不从「1-16周(单)」这种文案反推——教务的周次是任意集合，不是范围 + 单双周。
 */

/** 12 节作息（不分冬夏），来自用户提供的《最新作息时间表》 */
export const PERIOD_TIMES = Object.freeze([
  { section: 1, startTime: '08:00', endTime: '08:45' },
  { section: 2, startTime: '08:50', endTime: '09:35' },
  { section: 3, startTime: '09:55', endTime: '10:40' },
  { section: 4, startTime: '10:45', endTime: '11:30' },
  { section: 5, startTime: '14:00', endTime: '14:45' },
  { section: 6, startTime: '14:50', endTime: '15:35' },
  { section: 7, startTime: '15:55', endTime: '16:40' },
  { section: 8, startTime: '16:45', endTime: '17:30' },
  { section: 9, startTime: '17:35', endTime: '18:20' },
  { section: 10, startTime: '19:00', endTime: '19:45' },
  { section: 11, startTime: '19:50', endTime: '20:35' },
  { section: 12, startTime: '20:40', endTime: '21:25' },
]);

/** 课程类型：教务的「课程性质」文案 → App 枚举（选课链路 jwxtSelection.js 也要用） */
export const TYPE_MAP = Object.freeze([
  [/必修/, 'required'], [/选修|限选|任选/, 'elective'],
  [/公共|通识/, 'public'], [/实践|实验|实习|训练/, 'practice'],
]);

/**
 * 解析周次文案。支持教务常见的几种写法：
 *   '1-16周' / '1-9周(单)' / '2-8周(双)' / '1-9周,11-19周' / '第1-16周'
 * 返回升序去重数组；解析不出返回空数组（**不猜**，页面就不渲染这门课）。
 *
 * @param {string} raw
 * @returns {number[]}
 */
export function parseWeeks(raw) {
  const src = String(raw || '')
    // 先把 (单)/(双) 收成紧贴「周」后的单字，别让括号把标记与范围拆到两段去
    .replace(/[（(]\s*([单双全])\s*[)）]/g, '$1')
    .replace(/[(){}（）|第]/g, '')
    .replace(/到/g, '-');
  if (!src.trim()) return [];
  const out = new Set();
  /** 匹配 '1-9周单' / '2-8周双' / '11-19周' / '3周'，奇偶标记属于它前面那段范围 */
  const re = /(\d+)\s*(?:-\s*(\d+))?\s*周\s*([单双])?/g;
  let match;
  let matched = false;
  while ((match = re.exec(src)) !== null) {
    matched = true;
    const lo = Math.max(1, Math.min(Number(match[1]), Number(match[2] || match[1])));
    const hi = Math.min(30, Math.max(Number(match[1]), Number(match[2] || match[1])));
    const parity = match[3] === '单' ? 'odd' : match[3] === '双' ? 'even' : 'all';
    for (let w = lo; w <= hi; w += 1) {
      if (parity === 'odd' && w % 2 === 0) continue;
      if (parity === 'even' && w % 2 !== 0) continue;
      out.add(w);
    }
  }
  // 少数页面只给 '1-16' 不带「周」字：按全周兜底，别整门课消失
  if (!matched) {
    const bare = /^(\d+)\s*-\s*(\d+)$/.exec(src.trim());
    if (bare) {
      const lo = Math.max(1, Number(bare[1]));
      const hi = Math.min(30, Number(bare[2]));
      for (let w = lo; w <= hi; w += 1) out.add(w);
    }
  }
  return [...out].sort((a, b) => a - b);
}

/**
 * 解析节次文案 '1-2节,5-6节' → [1,2,5,6]，夹到 1..12。
 * @param {string} raw
 * @returns {number[]}
 */
export function parseSections(raw) {
  const out = new Set();
  for (const part of String(raw || '').replace(/节/g, '').split(/[,，]/)) {
    const match = /^\s*(\d+)\s*(?:-\s*(\d+))?\s*$/.exec(part);
    if (!match) continue;
    const lo = Number(match[1]);
    const hi = match[2] ? Number(match[2]) : lo;
    for (let s = Math.max(1, Math.min(lo, hi)); s <= Math.min(12, Math.max(lo, hi)); s += 1) out.add(s);
  }
  return [...out].sort((a, b) => a - b);
}

/** weeks → 派生文案字段（只用于显示，不参与任何判定） */
export function deriveWeekFields(weeks) {
  const list = Array.isArray(weeks) ? weeks : [];
  if (list.length === 0) return { weekRange: [0, 0], weekParity: 'all' };
  const min = Math.min(...list);
  const max = Math.max(...list);
  const allOdd = list.every((w) => w % 2 === 1);
  const allEven = list.every((w) => w % 2 === 0);
  return { weekRange: [min, max], weekParity: allOdd ? 'odd' : allEven ? 'even' : 'all' };
}

/**
 * kbList 的一行 → Course。
 * 教务字段名不统一（kcmc / kcmcMc / KCMC），所以这里做一次小写归一再取值。
 *
 * @param {object} row
 * @param {string} semesterId
 * @param {{isSelected?: boolean}} [options]
 * @returns {object|null} Course；连课程名都没有就返回 null，不画幽灵卡
 */
export function kbRowToCourse(row, semesterId, options = {}) {
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

  const name = pick('kcmc', 'kcmcmc', 'courseName');
  if (!name) return null;
  const weeks = parseWeeks(pick('zcd', 'zcmc', 'weeks'));
  const sections = parseSections(pick('jc', 'jcs', 'sections'));
  const dayOfWeek = Number(pick('xqj', 'day')) || 0;
  if (weeks.length === 0 || sections.length === 0 || dayOfWeek < 1 || dayOfWeek > 7) return null;

  const first = PERIOD_TIMES[sections[0] - 1];
  const last = PERIOD_TIMES[sections[sections.length - 1] - 1];
  const nature = pick('kcxzmc', 'kcxz', 'nature');
  const typeMatch = TYPE_MAP.find(([re]) => re.test(nature));
  const derived = deriveWeekFields(weeks);

  return {
    id: pick('jxbmc', 'kch_id', 'id') || `${semesterId}-${pick('kch', 'code') || name}`,
    code: pick('kch', 'kcdm', 'code'),
    name,
    teacher: pick('xm', 'jsxm', 'teacher') || '未提供',
    credits: Number(pick('xf', 'credits')) || 0,
    hours: Number(pick('xs', 'hours')) || 0,
    courseType: typeMatch ? typeMatch[1] : 'required',
    assessment: /考查/.test(nature) ? 'check' : 'exam',
    campus: pick('xqmc', 'campus') || '',
    location: pick('cdmc', 'location') || '未安排',
    classNo: pick('jxbmc', 'classNo').slice(-2) || '',
    capacity: Number(pick('rs', 'capacity')) || 0,
    enrolled: Number(pick('yxrs', 'enrolled')) || 0,
    weeks,
    ...derived,
    dayOfWeek,
    sections,
    startTime: first ? first.startTime : '--:--',
    endTime: last ? last.endTime : '--:--',
    isSelected: Boolean(options.isSelected),
  };
}

/**
 * 把 kbList（或推荐课表打印页返回的同类数组）转成 Course[]。
 * @param {unknown} payload JSON 对象 / 数组 / 含 kbList 的包装
 * @param {string} semesterId
 * @param {{isSelected?: boolean}} [options]
 * @returns {object[]}
 */
export function parseKbList(payload, semesterId, options = {}) {
  let rows = payload;
  if (payload && !Array.isArray(payload) && typeof payload === 'object') {
    rows = payload.kbList || payload.items || payload.rows || payload.data || [];
  }
  if (!Array.isArray(rows)) return [];
  return rows.map((row) => kbRowToCourse(row, semesterId, options)).filter(Boolean);
}

/**
 * HTML → 纯文本，保留块级换行（学分树靠行首空格推 depth，换行不能丢）。
 * 浏览器走 DOMParser；Node 测试环境没有 DOMParser 时退化为标签剥离。
 * @param {string} html
 * @returns {string}
 */
export function htmlToText(html) {
  const src = String(html || '');
  if (typeof DOMParser !== 'undefined') {
    const doc = new DOMParser().parseFromString(src, 'text/html');
    return (doc.body && doc.body.textContent) || '';
  }
  return src
    .replace(/<(script|style)[\s\S]*?<\/\1>/gi, '')
    .replace(/<\/(tr|p|div|li|h[1-6]|table)>/gi, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .trim();
}

/** 从任意值里取第一个数字（教务常写 '.60' 这种缺前导零的小数） */
function num(re, text) {
  const match = re.exec(text);
  return match ? Number(match[1]) : undefined;
}

/**
 * 学业情况页 → GPA 权威快照。与 tools/test-gpa.mjs 的 fixture 解析口径一致，
 * 那份测试会拿 OFFICIAL_GPA 逐项比对，两处实现漂移时测试会红。
 *
 * @param {string} pageText 页面纯文本（含顶部摘要与缩进的学分树）
 * @param {string} [fetchedAt]
 * @returns {object} GPA
 */
export function parseGpaPage(pageText, fetchedAt = new Date().toISOString()) {
  const src = String(pageText || '');
  const gpa = num(/平均学分绩点（GPA）：\s*([\d.]+)/, src);
  const statsAt = /统计时间\s*([\d-]{10}\s+[\d:]{8})\s*之前有效/.exec(src);
  const bangAt = src.indexOf('！');
  const inside = bangAt === -1 ? src : src.slice(0, bangAt);
  const outside = bangAt === -1 ? '' : src.slice(bangAt + 1);
  const count = (text, label) => num(new RegExp(`${label}\\s*(\\d+)\\s*门`), text) ?? 0;
  const parenCount = (text, label) => {
    const match = new RegExp(`${label}\\s*[（(](\\d+)[）)]`).exec(text);
    return match ? Number(match[1]) : undefined;
  };

  /** @type {object[]} */
  const modules = [];
  for (const rawLine of src.split('\n')) {
    const line = rawLine.replace(/\s+$/, '');
    if (!line.trim()) continue;
    // 顶部摘要、页脚提示、图例不是树节点；其余行（含「主修」「资格证书信息」
    // 这类纯分组标题）都要保留 —— 它们是 depth=0 的树根，共 21 节点，
    // 与 tools/test-gpa.mjs 的 parseCreditTree 及 OFFICIAL_GPA 逐项对齐。
    if (/平均学分绩点|统计时间|提示：|^图例/.test(line)) continue;
    const indent = line.length - line.trimStart().length;
    const body = line.trim().split(/\s{2,}/);
    const rest = body.slice(1).join('  ');
    const node = {
      name: body[0],
      depth: Math.floor(indent / 2),
      requireCredits: num(/要求学分:([\d.]+)/, rest) ?? 0,
      earnedCredits: num(/获得学分:([\d.]+)/, rest) ?? 0,
      missingCredits: num(/未获得学分:([\d.]+)/, rest) ?? 0,
    };
    const logic = /(并且|或者|缺省)\s*$/.exec(rest);
    if (logic) node.logic = logic[1];
    const total = parenCount(rest, '共');
    const passed = parenCount(rest, '通过');
    if (total !== undefined) node.courseTotal = total;
    if (passed !== undefined) node.coursePassed = passed;
    modules.push(node);
  }

  return {
    gpa: gpa === undefined ? null : gpa,
    gpaOrigin: 'official',
    statsAt: statsAt ? statsAt[1].replace(/\s+/g, ' ') : '',
    fetchedAt,
    courseCount: {
      planned: num(/计划总课程\s*(\d+)\s*门/, src) ?? 0,
      passed: count(inside, '通过'),
      failed: count(inside, '未通过'),
      notTaken: count(inside, '未修'),
      inProgress: count(inside, '在读'),
      extraPassed: count(outside, '通过'),
      extraFailed: count(outside, '未通过'),
    },
    modules,
  };
}

/* ── 页面表单学期码 ────────────────────────────────────────────────────────
 * 2026-11-06 实测：课表页的 xnm/xqm 是 <select> + <option selected>，不是
 * <input value>。旧版 fetchKb() 用 name="xnm" ... value="..." 去读，永远读不到，
 * POST 空参后教务只回一个 null，课表必然降级。故改为按 select 读选中项。
 * 正方学期码：3=第一学期，12=第二学期，16=第三学期（不是 1/2）。
 */

/** 从 <select name="xnm"> 里读出选中 option 的 value；无 selected 时取第一个非空项 */
export function readSelectValue(html, name) {
  const src = String(html || '');
  const select = new RegExp(`<select[^>]*name=["']${name}["'][\\s\\S]*?<\\/select>`, 'i').exec(src);
  if (!select) return '';
  const block = select[0];
  const selected = /<option[^>]*value=["']([^"']+)["'][^>]*selected[^>]*>/i.exec(block)
    || /<option[^>]*selected[^>]*value=["']([^"']+)["'][^>]*>/i.exec(block);
  if (selected) return selected[1];
  const first = [...block.matchAll(/<option[^>]*value=["']([^"']*)["'][^>]*>/gi)]
    .find((m) => m[1] !== '');
  return first ? first[1] : '';
}

/** 教务学期码 → App 学期序号（3→1，12→2，16→3） */
export function normalizeTerm(code, termName) {
  const label = String(termName || '').trim();
  if (/^\d+$/.test(label)) return label;
  return code === '12' ? '2' : code === '16' ? '3' : '1';
}

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

/** 成绩接口 JSON → Grade[]（兼容 { items } / { rows } / 裸数组） */
export function parseGradeList(payload) {
  let rows = payload;
  if (payload && !Array.isArray(payload) && typeof payload === 'object') {
    rows = payload.items || payload.rows || payload.data || [];
  }
  if (!Array.isArray(rows)) return [];
  return rows.map(gradeRowToGrade).filter(Boolean);
}
