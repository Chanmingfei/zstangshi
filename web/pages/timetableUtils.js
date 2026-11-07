/**
 * timetableUtils.js —— 课表页的常量与纯函数（不含 DOM，便于直接单测/推理）
 *
 * 从 `pages/timetable.js` 拆出来只为守住单文件 ≤400 行；这里没有任何状态，
 * 所有函数都是「输入 → 输出」，页面负责把它们拼成 DOM。
 */

import { WEEKDAY_LABELS, getSemesters, weekRangeOf } from '../data/calendar.js';

/** 12 小节的起止时间（一节 45 分钟，课间 5 分钟） */
export const PERIOD_TIMES = [
  ['08:00', '08:45'], ['08:50', '09:35'], ['09:55', '10:40'], ['10:45', '11:30'],
  ['14:00', '14:45'], ['14:50', '15:35'], ['15:55', '16:40'], ['16:45', '17:30'], ['17:35', '18:20'],
  ['19:00', '19:45'], ['19:50', '20:35'], ['20:40', '21:25'],
];

/**
 * 节次总数 = 12。
 * ⚠️ 三段不是平分：上午 1-4、下午 5-9（**5 节**）、晚间 10-12。
 * 按「上下午各 4 节」排会让下午的课整体错位一节。
 */
export const TOTAL_SECTIONS = PERIOD_TIMES.length;

/** 三段的分界：第 1 / 5 / 10 节上方画更粗的分隔线 */
export const SEGMENT_STARTS = new Set([1, 5, 10]);

/**
 * 周次文案：「1-17 周」「1-15 周（单周）」「2、4、6 周」。
 * `weeks` 是唯一真源，这里现算，不回写数据。
 * @param {number[]} weeks
 * @returns {string}
 */
export function weeksText(weeks) {
  const list = (Array.isArray(weeks) ? weeks : []).map(Number).filter(Number.isFinite).sort((a, b) => a - b);
  if (list.length === 0) return '—';
  if (list.length === 1) return `第 ${list[0]} 周`;
  const allOdd = list.every((n) => n % 2 === 1);
  const allEven = list.every((n) => n % 2 === 0);
  const parity = allOdd ? '（单周）' : allEven ? '（双周）' : '';
  if (parity && list.length > 2) return `${list[0]}-${list[list.length - 1]} 周${parity}`;

  const ranges = [];
  let from = list[0];
  let prev = list[0];
  for (let i = 1; i < list.length; i += 1) {
    if (list[i] === prev + 1) {
      prev = list[i];
      continue;
    }
    ranges.push(from === prev ? `${from}` : `${from}-${prev}`);
    from = list[i];
    prev = list[i];
  }
  ranges.push(from === prev ? `${from}` : `${from}-${prev}`);
  return `${ranges.join('、')} 周${parity}`;
}

/**
 * 节次文案 + 起止时间。
 * @param {number[]} sections
 * @returns {{sections: string, time: string}}
 */
export function periodInfo(sections) {
  const list = (Array.isArray(sections) ? sections : []).map(Number).filter(Number.isFinite).sort((a, b) => a - b);
  if (list.length === 0) return { sections: '—', time: '--:--' };
  const first = PERIOD_TIMES[list[0] - 1];
  const last = PERIOD_TIMES[list[list.length - 1] - 1];
  const isRange = list.every((n, i) => i === 0 || n === list[i - 1] + 1);
  return {
    sections: isRange ? `第 ${list[0]}-${list[list.length - 1]} 节` : `第 ${list.join(',')} 节`,
    time: first && last ? `${first[0]}-${last[1]}` : '--:--',
  };
}

/**
 * 取「星期 × 起始节次」这一格与当前周有关的全部课程。
 *
 * 旧实现只认 `weeks.includes(weekNo)`，于是当教务把单周课、双周课拆成两行、
 * 又因文案写法变化被解析成全周时，两者会同时命中 → 页面报「时间冲突」，
 * 用户反而看不到真正该上的那门课。这里把结果分成两类：
 *   · active    本周确实要上的（网格只画这些，真实课排在板块占位行前面）
 *   · rotating  同一格、本周没课、且与某门 active 课周次完全互补的「另一半」
 *               （不占格子，但进详情层，让用户知道隔周还有一门）
 *
 * `relation` 里 `placeholder` 表示这格只有「真实课 + 板块占位行」，不是撞课；
 * 只有两门以上真实课同周重叠才叫 `conflict`。
 *
 * @param {object[]} courses 整张课表（不要预先按周过滤）
 * @param {number} day 1..7
 * @param {number} start 起始节次
 * @param {number} weekNo 当前周
 * @returns {{active: object[], rotating: object[],
 *            relation: 'single'|'conflict'|'alternating'|'placeholder'}}
 */
export function coursesInCell(courses, day, start, weekNo) {
  const list = (Array.isArray(courses) ? courses : []).filter(
    (c) => c && c.dayOfWeek === day && Array.isArray(c.sections) && c.sections.length
      && Math.min(...c.sections) === start
  );
  const active = list
    .filter((c) => Array.isArray(c.weeks) && c.weeks.includes(weekNo))
    .sort((a, b) => Number(Boolean(a.isPlaceholder)) - Number(Boolean(b.isPlaceholder)));
  const rotating = list
    .filter((c) => Array.isArray(c.weeks) && !c.weeks.includes(weekNo)
      && active.some((a) => !a.isPlaceholder && !a.weeks.some((w) => c.weeks.includes(w))))
    .map((c) => ({ ...c, offWeek: true }));
  const realActive = active.filter((c) => !c.isPlaceholder);
  let relation = 'single';
  if (realActive.length > 1) relation = 'conflict';
  else if (active.some((c) => c.isPlaceholder)) relation = 'placeholder';
  else if (rotating.length) relation = 'alternating';
  return { active, rotating, relation };
}

/**
 * 当前时刻落在第几小节（1-12），课间 / 午休返回 0。
 *
 * 判定用「左闭右闭」：08:45 这一分钟算第 1 节还没下课 ——
 * 学生看到「进行中」的时间点通常正是这一刻，提前 1 分钟熄灯反而像 bug。
 * 课间（09:35-09:50）不属于任何一节，宁可没有标识，也不要给错行。
 *
 * @param {Date} [now]
 * @returns {number} 0 表示不在一节课内
 */
export function currentSection(now = new Date()) {
  const minutes = now.getHours() * 60 + now.getMinutes();
  for (let i = 0; i < PERIOD_TIMES.length; i += 1) {
    const [from, to] = PERIOD_TIMES[i];
    const start = toMinutes(from);
    const end = toMinutes(to);
    if (minutes >= start && minutes <= end) return i + 1;
  }
  return 0;
}

/**
 * 'HH:MM' → 当天第几分钟。
 * @param {string} hhmm
 * @returns {number}
 */
function toMinutes(hhmm) {
  const [hh, mm] = String(hhmm).split(':').map(Number);
  if (!Number.isFinite(hh) || !Number.isFinite(mm)) return NaN;
  return hh * 60 + mm;
}

/**
 * 当前学期（顶栏 context 指向的那个，取不到退第一个）。
 * @param {object} state 应用 state
 * @returns {object|null}
 */
export function currentSemester(state) {
  const term = (state && state.term) || {};
  if (!term.calendar) return null;
  const list = getSemesters(term.calendar);
  const id = term.context && term.context.semesterId;
  return list.find((item) => item.id === id) || list[0] || null;
}

/**
 * 本周 7 天的日期文案（表头「11/2」用），取自校历的周起止日期。
 * @param {object|null} semester
 * @param {number} weekNo
 * @returns {string[]}
 */
export function weekDates(semester, weekNo) {
  const range = semester ? weekRangeOf(semester, weekNo) : null;
  if (!range) return [];
  const base = new Date(`${range.start}T12:00:00`);
  return WEEKDAY_LABELS.map((_, index) => {
    const d = new Date(base);
    d.setDate(d.getDate() + index);
    return `${d.getMonth() + 1}/${d.getDate()}`;
  });
}
