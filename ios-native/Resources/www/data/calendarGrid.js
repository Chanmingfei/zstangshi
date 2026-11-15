/**
 * calendarGrid.js —— 月历网格（校历页 P10 的数据底稿）
 *
 * 与 calendar.js 的分工：
 *   calendar.js      教学周域（算第几周、顶栏文案）
 *   calendarGrid.js  月历网格域（算 42 格 + 每格的教学周角标与节点）
 *
 * 依赖方向单向：本文件 import calendar.js，calendar.js 不反过来 import 本文件。
 */

import {
  dayNumber,
  isoOfDayNumber,
  todayDayNumber,
  weekOfDate,
  weekdayOfDayNumber,
} from './calendar.js';

/** 一天的毫秒数（把 UTC 日序号还原成 Date 时用） */
const MS_PER_DAY = 86400000;
/** 月历固定格数：6 行 × 7 列 */
const GRID_SIZE = 42;

/**
 * 按日期索引学期里的关键节点，供月历格挂色点。
 * @param {object} semester 学期对象
 * @returns {Map<string, Array<{title: string, type: string}>>} key 为 'YYYY-MM-DD'
 */
export function indexMilestones(semester) {
  const map = new Map();
  const list = semester && Array.isArray(semester.milestones) ? semester.milestones : [];
  for (const item of list) {
    if (!item || !item.date) continue;
    const key = item.date;
    if (!map.has(key)) map.set(key, []);
    map.get(key).push({ title: item.title || '', type: item.type || 'event' });
  }
  return map;
}

/**
 * 生成月历网格：固定 42 格，周一为每周第一天（国内课表/校历惯例）。
 *
 * 之所以固定 42 格而不是按需 35/42：让翻月时容器高度恒定，避免整页跳动。
 *
 * @param {number} year 四位年份
 * @param {number} month 1-12
 * @param {object} semester 学期对象（用于算教学周角标与节点；可传 null，此时角标全为 null）
 * @param {string} [todayIso] 指定「今天」的 ISO 日期，主要给单测用；缺省取本地今天
 * @returns {Array<{
 *   date: string, day: number, inMonth: boolean, isToday: boolean,
 *   weekNo: number|null, events: Array<{title: string, type: string}>
 * }>}
 */
export function buildMonthGrid(year, month, semester, todayIso) {
  const referenceToday = todayIso || isoOfDayNumber(todayDayNumber(new Date()));
  const milestoneMap = indexMilestones(semester);

  const firstOfMonth = dayNumber(year, month, 1);
  const firstWeekday = weekdayOfDayNumber(firstOfMonth); // 1=周一 … 7=周日
  const gridStart = firstOfMonth - (firstWeekday - 1);

  const cells = [];
  for (let i = 0; i < GRID_SIZE; i += 1) {
    const num = gridStart + i;
    const date = isoOfDayNumber(num);
    const d = new Date(num * MS_PER_DAY);
    cells.push({
      date,
      day: d.getUTCDate(),
      inMonth: d.getUTCMonth() + 1 === month && d.getUTCFullYear() === year,
      isToday: date === referenceToday,
      weekNo: weekOfDate(date, semester),
      events: milestoneMap.get(date) || [],
    });
  }
  return cells;
}

/**
 * 取月历网格中某个日期的格子（找不到返回 null）。
 * @param {Array<object>} grid buildMonthGrid 的返回值
 * @param {string} dateIso 'YYYY-MM-DD'
 * @returns {object|null}
 */
export function findCellByDate(grid, dateIso) {
  if (!Array.isArray(grid)) return null;
  return grid.find((cell) => cell.date === dateIso) || null;
}
