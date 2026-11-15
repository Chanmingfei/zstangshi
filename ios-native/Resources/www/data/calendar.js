/**
 * calendar.js —— 校历 / 教学周 纯函数模块
 *
 * 约定（docs/ARCHITECTURE.md §4.5）：
 *   1. 零 DOM 依赖、零副作用、零 import —— 可在 node 直接单测（tools/test-week.mjs）
 *   2. 日期一律按「本地日」计算，内部折算成 UTC 日序号（1970-01-01 = 0），消除时区误差。
 *      绝不对 'YYYY-MM-DD' 直接 new Date()：那会被当成 UTC 零点，东八区会退回前一天。
 *   3. 教学周公式唯一：week = floor((today - semesterStart) / 7) + 1
 *      —— 必须 floor 不能 round，否则周日会多算一周。
 */

/** 一天的毫秒数 */
const MS_PER_DAY = 86400000;
/** 一周的天数 */
const WEEK_DAYS = 7;
/** 学期未提供 totalWeeks 时的兜底周数 */
const DEFAULT_TOTAL_WEEKS = 19;
/** 距开学超过这么多天就不显示倒计时，直接显示「假期中」 */
const COUNTDOWN_MAX_DAYS = 30;
/** 两个学期之间的假期：距下学期开学不超过这么多天时，顶栏切换成「距开学 X 天」 */
const GAP_LOOKAHEAD_DAYS = 30;

/** 星期表头（周一在前，符合国内课表习惯） */
export const WEEKDAY_LABELS = ['一', '二', '三', '四', '五', '六', '日'];

/**
 * 把 (年, 月, 日) 折算成 UTC 日序号。month 为 1-12（人类习惯，不是 Date 的 0-11）。
 * @param {number} year 四位年份
 * @param {number} month 1-12
 * @param {number} day 1-31
 * @returns {number} 日序号（可为负）
 */
export function dayNumber(year, month, day) {
  return Math.floor(Date.UTC(year, month - 1, day) / MS_PER_DAY);
}

/**
 * 解析 'YYYY-MM-DD' 为日序号。
 * @param {string} iso 形如 '2026-11-05'
 * @returns {number} 日序号；入参非法时返回 NaN
 */
export function dayNumberOfIso(iso) {
  if (typeof iso !== 'string') return NaN;
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso.trim());
  if (!m) return NaN;
  return dayNumber(Number(m[1]), Number(m[2]), Number(m[3]));
}

/**
 * 日序号 → 'YYYY-MM-DD'。
 * @param {number} num 日序号
 * @returns {string}
 */
export function isoOfDayNumber(num) {
  const d = new Date(num * MS_PER_DAY);
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, '0');
  const day = String(d.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/**
 * 取本地「今天」的日序号。用本地年月日再折 UTC，确保与用户看到的日期一致。
 * @param {Date} [now] 缺省取当前时间
 * @returns {number}
 */
export function todayDayNumber(now = new Date()) {
  return dayNumber(now.getFullYear(), now.getMonth() + 1, now.getDate());
}

/**
 * 日序号 → 星期（1=周一 … 7=周日）。1970-01-01 是周四。
 * @param {number} num 日序号
 * @returns {number} 1-7
 */
export function weekdayOfDayNumber(num) {
  // 1970-01-01 是周四：日序号 mod 7 得到「距周四的天数」，+3 再 +1 折算成 1=周一…7=周日
  const offset = ((num % WEEK_DAYS) + WEEK_DAYS) % WEEK_DAYS;
  return ((offset + 3) % WEEK_DAYS) + 1;
}

/**
 * 取本地「今天」的星期。
 * @param {Date} [now]
 * @returns {number} 1=周一 … 7=周日
 */
export function todayWeekday(now = new Date()) {
  return weekdayOfDayNumber(todayDayNumber(now));
}

/**
 * 规范化单个学期：补出 `id` 字段（校历 JSON 原文不含 id，业务层需要 `2026-2027-1` 这种口径）。
 * 不改动入参，返回浅拷贝。
 * @param {object} raw 校历 JSON 里的 semester 对象
 * @returns {object} 规范化后的学期对象
 */
export function normalizeSemester(raw) {
  if (!raw || typeof raw !== 'object') {
    throw new TypeError('normalizeSemester 需要学期对象');
  }
  const academicYear = raw.academicYear || '';
  const term = raw.term === 2 ? 2 : 1;
  return {
    ...raw,
    id: raw.id || `${academicYear}-${term}`,
    term,
    totalWeeks: Number(raw.totalWeeks) || DEFAULT_TOTAL_WEEKS,
    examWeeks: Array.isArray(raw.examWeeks) ? raw.examWeeks.slice() : [],
    milestones: Array.isArray(raw.milestones) ? raw.milestones.slice() : [],
    weeks: Array.isArray(raw.weeks) ? raw.weeks.slice() : [],
  };
}

/**
 * 从校历 JSON（或已规范化对象）里取学期数组，按 semesterStart 升序。
 * @param {object} calendarJson 校历原始 JSON
 * @returns {object[]} 规范化后的学期数组
 */
export function getSemesters(calendarJson) {
  const list = calendarJson && Array.isArray(calendarJson.semesters) ? calendarJson.semesters : [];
  return list
    .map(normalizeSemester)
    .filter((s) => !Number.isNaN(dayNumberOfIso(s.semesterStart)))
    .sort((a, b) => dayNumberOfIso(a.semesterStart) - dayNumberOfIso(b.semesterStart));
}

/**
 * 学期首日（第 1 教学周周一）的日序号。
 * @param {object} semester
 * @returns {number}
 */
export function semesterStartNumber(semester) {
  return dayNumberOfIso(semester.semesterStart);
}

/**
 * 学期末日的日序号。优先用 semesterEnd，缺失时按 totalWeeks 推算（末日 = 起始 + 周数×7 − 1）。
 * @param {object} semester
 * @returns {number}
 */
export function semesterEndNumber(semester) {
  const explicit = dayNumberOfIso(semester.semesterEnd);
  if (!Number.isNaN(explicit)) return explicit;
  const totalWeeks = Number(semester.totalWeeks) || DEFAULT_TOTAL_WEEKS;
  return semesterStartNumber(semester) + totalWeeks * WEEK_DAYS - 1;
}

/**
 * 选出当前适用的学期。
 *
 * 规则：
 *   1. 今天落在某个学期区间内 → 该学期
 *   2. 今天早于所有学期 → 最早的那个（顶栏显示「距开学 X 天」）
 *   3. 今天晚于所有学期 → 最近的那个（顶栏显示「假期中」）
 *   4. 落在两个学期之间的假期 → 距下学期开学 ≤30 天则返回下学期，否则返回上学期
 *
 * @param {Date} [now] 缺省取当前时间
 * @param {object} calendarJson 校历 JSON
 * @returns {object|null} 规范化后的学期对象
 */
export function pickSemester(now = new Date(), calendarJson) {
  const list = getSemesters(calendarJson);
  if (list.length === 0) return null;

  const today = todayDayNumber(now);
  for (const semester of list) {
    const start = semesterStartNumber(semester);
    const end = semesterEndNumber(semester);
    if (today >= start && today <= end) return semester;
  }

  const first = list[0];
  const last = list[list.length - 1];
  if (today < semesterStartNumber(first)) return first;
  if (today > semesterEndNumber(last)) return last;

  // 学期之间的假期
  let previous = first;
  for (const semester of list) {
    const start = semesterStartNumber(semester);
    if (start > today) {
      return start - today <= GAP_LOOKAHEAD_DAYS ? semester : previous;
    }
    previous = semester;
  }
  return last;
}

/**
 * 计算教学周上下文。
 *
 * @param {Date} [now] 缺省取当前时间
 * @param {object} semester 学期对象（可传校历 JSON 里的原始对象）
 * @returns {{
 *   phase: 'inTerm'|'beforeTerm'|'vacation',
 *   week: number|null,
 *   semesterId: string,
 *   dayOfWeek: number|null,
 *   daysToStart: number,
 *   daysSinceEnd: number,
 *   todayIso: string
 * }}
 *   - inTerm：week 为 1..totalWeeks
 *   - beforeTerm：week 为 null，daysToStart 为距开学天数（≥1）
 *   - vacation：week 为 null，daysSinceEnd 为距学期结束天数（≥1）
 */
export function getTeachingWeek(now = new Date(), semester) {
  const sem = semester && semester.semesterStart ? semester : null;
  const today = todayDayNumber(now);
  const todayIso = isoOfDayNumber(today);

  if (!sem) {
    return {
      phase: 'vacation',
      week: null,
      semesterId: '',
      dayOfWeek: weekdayOfDayNumber(today),
      daysToStart: 0,
      daysSinceEnd: 0,
      todayIso,
    };
  }

  const totalWeeks = Number(sem.totalWeeks) || DEFAULT_TOTAL_WEEKS;
  const start = semesterStartNumber(sem);
  const end = semesterEndNumber(sem);
  const diff = today - start;
  const semesterId = sem.id || `${sem.academicYear}-${sem.term}`;

  if (diff < 0) {
    return {
      phase: 'beforeTerm',
      week: null,
      semesterId,
      dayOfWeek: weekdayOfDayNumber(today),
      daysToStart: -diff,
      daysSinceEnd: 0,
      todayIso,
    };
  }

  const week = Math.floor(diff / WEEK_DAYS) + 1;
  if (week > totalWeeks) {
    return {
      phase: 'vacation',
      week: null,
      semesterId,
      dayOfWeek: weekdayOfDayNumber(today),
      daysToStart: 0,
      daysSinceEnd: today - end,
      todayIso,
    };
  }

  return {
    phase: 'inTerm',
    week,
    semesterId,
    dayOfWeek: weekdayOfDayNumber(today),
    daysToStart: 0,
    daysSinceEnd: 0,
    todayIso,
  };
}

/**
 * 组装顶栏文案上下文。
 *
 * 关键格式（curl 断言过，改动前先看 docs/teaching-week-testcases.md）：
 *   left  = '26~27学年第一学期'          ← 波浪号是 U+007E，不是 '-'，也不是 '~' 全角
 *   text  = '第 10 教学周'               ← 中文与数字之间是半角空格
 *   full  = '26~27学年第一学期 第 10 教学周'（左右两段之间一个半角空格）
 *
 * 说明：`text` 只承载右侧那一段（学期外为「假期中」/「距开学 X 天」），
 * 完整串用 `full`。这样渲染层可以给左右两段单独的样式而不用切字符串。
 *
 * @param {Date} [now] 缺省取当前时间
 * @param {object} semester 学期对象
 * @returns {{
 *   displayYear: string, termLabel: string, week: number|null,
 *   phase: 'inTerm'|'beforeTerm'|'vacation',
 *   text: string, left: string, full: string,
 *   semesterId: string, dayOfWeek: number|null
 * }}
 */
export function buildTermHeader(now = new Date(), semester) {
  const tw = getTeachingWeek(now, semester);
  const displayYear = (semester && semester.displayYear) || '';
  const termLabel = (semester && semester.termLabel) || '';
  const left = displayYear && termLabel ? `${displayYear}学年${termLabel}` : '';

  let text;
  if (tw.phase === 'inTerm') {
    text = `第 ${tw.week} 教学周`;
  } else if (tw.phase === 'beforeTerm' && tw.daysToStart > 0 && tw.daysToStart <= COUNTDOWN_MAX_DAYS) {
    text = `距开学 ${tw.daysToStart} 天`;
  } else {
    text = '假期中';
  }

  return {
    displayYear,
    termLabel,
    week: tw.week,
    phase: tw.phase,
    text,
    left,
    full: left ? `${left} ${text}` : text,
    semesterId: tw.semesterId,
    dayOfWeek: tw.dayOfWeek,
  };
}

/**
 * 某个日期属于第几教学周。
 * @param {string} dateIso 'YYYY-MM-DD'
 * @param {object} semester 学期对象
 * @returns {number|null} 周次；不在学期内返回 null
 */
export function weekOfDate(dateIso, semester) {
  const num = dayNumberOfIso(dateIso);
  if (Number.isNaN(num) || !semester || !semester.semesterStart) return null;
  const totalWeeks = Number(semester.totalWeeks) || DEFAULT_TOTAL_WEEKS;
  const start = semesterStartNumber(semester);
  const diff = num - start;
  if (diff < 0) return null;
  const week = Math.floor(diff / WEEK_DAYS) + 1;
  return week > totalWeeks ? null : week;
}

/**
 * 取学期内「第 N 教学周」的起止日期。
 * @param {object} semester 学期对象
 * @param {number} weekNo 周次
 * @returns {{week: number, start: string, end: string}|null}
 */
export function weekRangeOf(semester, weekNo) {
  if (!semester || !semester.semesterStart) return null;
  const totalWeeks = Number(semester.totalWeeks) || DEFAULT_TOTAL_WEEKS;
  if (!Number.isInteger(weekNo) || weekNo < 1 || weekNo > totalWeeks) return null;
  const start = semesterStartNumber(semester) + (weekNo - 1) * WEEK_DAYS;
  return { week: weekNo, start: isoOfDayNumber(start), end: isoOfDayNumber(start + WEEK_DAYS - 1) };
}

/**
 * 学期内所有周次的起止日期表（课表周次选择器用）。
 * @param {object} semester 学期对象
 * @returns {Array<{week: number, start: string, end: string}>}
 */
export function listWeekRanges(semester) {
  const totalWeeks = (semester && Number(semester.totalWeeks)) || DEFAULT_TOTAL_WEEKS;
  const out = [];
  for (let w = 1; w <= totalWeeks; w += 1) {
    const r = weekRangeOf(semester, w);
    if (r) out.push(r);
  }
  return out;
}
