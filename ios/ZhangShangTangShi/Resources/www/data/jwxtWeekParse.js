/**
 * jwxtWeekParse.js —— 正方教务「周次」解析（从 jwxtParse.js 拆出，守住单文件 ≤400 行）
 *
 * 【为什么单双周不能只解析中文文案】
 * 教务同一个接口在不同页面给出的写法并不一致：推荐课表打印页的 `zcd` 是
 * '10-16周(双)' '11-15周(单)'，个人课表是 '1-8周'，还有一些页面写成
 * '单周1-16周' 或干脆只给 '1,3,5,7'。文案一旦没覆盖到，parity 就会丢，
 * 于是单周课与双周课被同时渲染进同一格 → 页面报「时间冲突」。
 *
 * 好在每行还带着两个**精确字段**：
 *   · zcds  = '10,12,14,16'          周次列表（推荐课表打印页实测）
 *   · oldzc = '64512'                周次位掩码，第 w 周 = 第 (w-1) 位（个人课表实测）
 * 2026-11-07 在真机抓了 10 个班 / 128 行逐一比对：zcd 解析结果与 zcds 完全一致。
 * 现在仍以精确字段为准，是为了让将来教务改写文案时不再退化。
 *
 * Course.weeks 是唯一真源，本文件只产出 weeks，不产出 weekRange / weekParity。
 */

/**
 * 解析周次文案。支持教务常见的几种写法：
 *   '1-16周' / '1-9周(单)' / '2-8周(双)' / '1-9周,11-19周' / '第1-16周'
 *   '单周1-16周'（前缀标记）/ '1,3,5,7' / '1-6,8'（裸列表）
 * 返回升序去重数组；解析不出返回空数组（**不猜**，页面就不渲染这门课）。
 * 注意：这只是兜底，见 weeksFromRow()。
 *
 * @param {string} raw
 * @returns {number[]}
 */
export function parseWeeks(raw) {
  const src = String(raw || '')
    // 先把 (单)/(双) 收成紧贴「周」后的单字，别让括号把标记与范围拆到两段去
    .replace(/[（(]\s*([单双全])\s*[)）]/g, '$1')
    .replace(/[(){}（）|第]/g, '')
    .replace(/到/g, '-')
    .trim();
  if (!src) return [];
  // 「单周1-16周」这类前缀标记段内正则吃不到，先记下来，最后统一过滤
  const prefix = /^[单双]/.exec(src);
  const globalParity = prefix ? (prefix[0] === '单' ? 'odd' : 'even') : null;
  const out = new Set();
  /** 匹配 '1-9周单' / '2-8周双' / '11-19周' / '3周'，奇偶标记属于它前面那段范围 */
  const re = /(\d+)\s*(?:-\s*(\d+))?\s*周\s*([单双])?/g;
  let match;
  let matched = false;
  let localParity = false;
  while ((match = re.exec(src)) !== null) {
    matched = true;
    if (match[3]) localParity = true;
    const lo = Math.max(1, Math.min(Number(match[1]), Number(match[2] || match[1])));
    const hi = Math.min(30, Math.max(Number(match[1]), Number(match[2] || match[1])));
    const parity = match[3] === '单' ? 'odd' : match[3] === '双' ? 'even' : 'all';
    for (let w = lo; w <= hi; w += 1) {
      if (parity === 'odd' && w % 2 === 0) continue;
      if (parity === 'even' && w % 2 !== 0) continue;
      out.add(w);
    }
  }
  // 少数页面只给 '1-16' / '1,3,5' / '1-6,8' 不带「周」字：按裸列表兜底，别整门课消失
  if (!matched) {
    const bare = src.replace(/周/g, '').trim();
    if (/^[\d,\s，、-]+$/.test(bare)) {
      for (const part of bare.split(/[,，]/)) {
        const m = /^\s*(\d+)\s*(?:-\s*(\d+))?\s*$/.exec(part);
        if (!m) continue;
        matched = true;
        const lo = Math.max(1, Math.min(Number(m[1]), Number(m[2] || m[1])));
        const hi = Math.min(30, Math.max(Number(m[1]), Number(m[2] || m[1])));
        for (let w = lo; w <= hi; w += 1) out.add(w);
      }
    }
  }
  if (!matched) return [];
  let list = [...out].sort((a, b) => a - b);
  // 前缀奇偶只在所有段落都没自带标记时生效，避免把 '1-9周(单),2-8周(双)' 二次过滤
  if (globalParity && !localParity) {
    list = list.filter((w) => (globalParity === 'odd' ? w % 2 === 1 : w % 2 === 0));
  }
  return list;
}

/** 教务 zcds：精确周次列表 '1,3,5' / '10,12,14,16' → 数字数组（不合法就返回空） */
export function parseWeekList(raw) {
  const text = String(raw || '').trim();
  if (!/^[\d,\s，]+$/.test(text)) return [];
  const out = new Set();
  for (const part of text.split(/[^\d]+/)) {
    if (!part) continue;
    const w = Number(part);
    if (Number.isInteger(w) && w >= 1 && w <= 30) out.add(w);
  }
  return [...out].sort((a, b) => a - b);
}

/** 教务 oldzc：周次位掩码，第 w 周 = 第 (w-1) 位（255 → 1-8 周，64512 → 11-16 周） */
export function parseWeekMask(raw) {
  const mask = Number(String(raw || '').trim());
  if (!Number.isSafeInteger(mask) || mask <= 0) return [];
  const out = [];
  for (let w = 1; w <= 30; w += 1) {
    if (Math.floor(mask / 2 ** (w - 1)) % 2 === 1) out.push(w);
  }
  return out;
}

/**
 * kbList 一行的周次：优先教务精确字段，文案只作最后兜底。
 * @param {object} row 原始行（字段名大小写不敏感）
 * @returns {number[]}
 */
export function weeksFromRow(row) {
  const get = (key) => {
    let value = row && row[key];
    if (value === undefined && row) {
      const hit = Object.keys(row).find((k) => k.toLowerCase() === key);
      value = hit ? row[hit] : undefined;
    }
    return value === undefined || value === null || value === '' ? '' : String(value).trim();
  };
  const list = parseWeekList(get('zcds'));
  if (list.length) return list;
  const mask = parseWeekMask(get('oldzc'));
  if (mask.length) return mask;
  return parseWeeks(get('zcd') || get('weeks'));
}
