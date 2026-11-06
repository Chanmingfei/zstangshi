#!/usr/bin/env node
/**
 * test-week.mjs —— 教学周算法回归测试
 *
 * 覆盖：
 *   A. docs/teaching-week-testcases.md 的 15 条验收对表（逐条断言 phase 与文案）
 *   B. 40 个抽样日期（19 个周一 + 19 个周日 + 2 个特意挑的日子）：周次严格单调、无空洞
 *   C. 顶栏文案的字符级断言（波浪号 U+007E、中文数字、半角空格位置）
 *   D. 月历网格：42 格、今日高亮、教学周角标
 *   E. weekOfDate / weekRangeOf 边界
 *
 * 退出码即结论：0 = 全绿，1 = 有差异（并打印逐条差异）。
 *
 * 运行：node tools/test-week.mjs
 */

import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  buildTermHeader,
  dayNumber,
  getSemesters,
  getTeachingWeek,
  isoOfDayNumber,
  pickSemester,
  weekOfDate,
  weekRangeOf,
} from '../web/data/calendar.js';
import { buildMonthGrid } from '../web/data/calendarGrid.js';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

/** 收集失败信息 */
const failures = [];
/** 通过计数 */
let passed = 0;

/**
 * 断言辅助。
 * @param {boolean} ok 是否通过
 * @param {string} label 断言描述
 * @param {unknown} [actual] 实际值（失败时打印）
 * @param {unknown} [expected] 期望值（失败时打印）
 */
function check(ok, label, actual, expected) {
  if (ok) {
    passed += 1;
    return;
  }
  const a = typeof actual === 'string' ? actual : JSON.stringify(actual);
  const e = typeof expected === 'string' ? expected : JSON.stringify(expected);
  failures.push(`${label}\n      期望: ${e}\n      实际: ${a}`);
}

/**
 * 构造本地时间 12:00 的 Date —— 归一化到中午，避免任何时区/DST 边界把日期推前推后。
 * @param {string} iso 'YYYY-MM-DD'
 * @returns {Date}
 */
function atNoon(iso) {
  return new Date(`${iso}T12:00:00`);
}

/** 日序号 → 本地中午 Date */
function dateOfDayNumber(num) {
  const iso = isoOfDayNumber(num);
  return atNoon(iso);
}

async function main() {
  const raw = await readFile(path.join(ROOT, 'web/data/school-calendar.json'), 'utf8');
  const calendar = JSON.parse(raw);
  const semesters = getSemesters(calendar);
  check(semesters.length === 1, '校历应包含 1 个学期', semesters.length, 1);

  const semester = semesters[0];
  check(semester.id === '2026-2027-1', '学期 id 应为 2026-2027-1', semester.id, '2026-2027-1');
  check(semester.totalWeeks === 19, '总周数应为 19', semester.totalWeeks, 19);

  // ── A. 15 条验收对表 ────────────────────────────────────────────────
  const table = [
    ['2026-08-28', 'beforeTerm', '距开学 3 天'],
    ['2026-08-30', 'beforeTerm', '距开学 1 天'],
    ['2026-08-31', 'inTerm', '第 1 教学周'],
    ['2026-09-06', 'inTerm', '第 1 教学周'],
    ['2026-09-07', 'inTerm', '第 2 教学周'],
    ['2026-10-01', 'inTerm', '第 5 教学周'],
    ['2026-11-02', 'inTerm', '第 10 教学周'],
    ['2026-11-05', 'inTerm', '第 10 教学周'],
    ['2026-11-08', 'inTerm', '第 10 教学周'],
    ['2026-11-09', 'inTerm', '第 11 教学周'],
    ['2026-12-31', 'inTerm', '第 18 教学周'],
    ['2027-01-01', 'inTerm', '第 18 教学周'],
    ['2027-01-10', 'inTerm', '第 19 教学周'],
    ['2027-01-11', 'vacation', '假期中'],
    ['2027-02-15', 'vacation', '假期中'],
  ];

  for (const [iso, expectPhase, expectText] of table) {
    const now = atNoon(iso);
    const sem = pickSemester(now, calendar);
    const header = buildTermHeader(now, sem);
    check(header.phase === expectPhase, `[A] ${iso} phase`, header.phase, expectPhase);
    check(header.text === expectText, `[A] ${iso} 右侧文案`, header.text, expectText);
    check(header.full === `26~27学年第一学期 ${expectText}`,
      `[A] ${iso} 完整文案`, header.full, `26~27学年第一学期 ${expectText}`);
  }

  // ── B. 40 个抽样日期 ───────────────────────────────────────────────
  const startNum = dayNumber(2026, 8, 31); // 第 1 教学周周一
  const sampled = [];
  const sampledExpected = [];
  for (let w = 0; w < 19; w += 1) {
    sampled.push(startNum + w * 7); // 每周一
    sampledExpected.push(w + 1);
  }
  for (let w = 0; w < 19; w += 1) {
    sampled.push(startNum + w * 7 + 6); // 每周日
    sampledExpected.push(w + 1);
  }
  sampled.push(dayNumber(2026, 11, 5)); // 主基准日（周四）
  sampledExpected.push(10);
  sampled.push(dayNumber(2026, 12, 31)); // 跨年日（周四）
  sampledExpected.push(18);
  check(sampled.length === 40, '[B] 抽样日期应为 40 个', sampled.length, 40);

  let previousWeek = 0;
  for (let i = 0; i < sampled.length; i += 1) {
    const num = sampled[i];
    const iso = isoOfDayNumber(num);
    const tw = getTeachingWeek(dateOfDayNumber(num), semester);
    const expected = sampledExpected[i];
    check(tw.phase === 'inTerm', `[B] ${iso} phase 应为 inTerm`, tw.phase, 'inTerm');
    check(tw.week === expected, `[B] ${iso} 周次（表驱动）`, tw.week, expected);
    if (i > 0 && i < 38 && i % 19 !== 0) {
      // 同一轮（周一序列 / 周日序列）内必须严格 +1，不得出现跳周或空洞
      check(tw.week === previousWeek + 1, `[B] ${iso} 周次应连续递增`, tw.week, previousWeek + 1);
    }
    previousWeek = tw.week;
  }

  // 单调性独立复核：周一的周次必须严格递增
  let lastMondayWeek = 0;
  for (let w = 0; w < 19; w += 1) {
    const tw = getTeachingWeek(dateOfDayNumber(startNum + w * 7), semester);
    check(tw.week === lastMondayWeek + 1, `[B] 第 ${w + 1} 个周一连续`, tw.week, lastMondayWeek + 1);
    lastMondayWeek = tw.week;
  }

  // ── C. 顶栏文案的字符级断言 ─────────────────────────────────────────
  const mainNow = atNoon('2026-11-05');
  const twMain = getTeachingWeek(mainNow, semester);
  check(twMain.week === 10, '[C] 2026-11-05 应为第 10 教学周', twMain.week, 10);
  check(twMain.dayOfWeek === 4, '[C] 2026-11-05 应为周四', twMain.dayOfWeek, 4);

  const header = buildTermHeader(mainNow, semester);
  const FULL = '26~27学年第一学期 第 10 教学周';
  check(header.text === '第 10 教学周', '[C] text 只含右侧段', header.text, '第 10 教学周');
  check(header.left === '26~27学年第一学期', '[C] left', header.left, '26~27学年第一学期');
  check(header.full === FULL, '[C] full 严格相等', header.full, FULL);
  check(header.full.includes('\u007E'), '[C] 必须含波浪号 U+007E', header.full, FULL);
  check(!header.full.includes('\uFF5E'), '[C] 不得含全角波浪号 U+FF5E', header.full, FULL);
  check(!header.full.includes('\u2013') && !header.full.includes('\u2014'),
    '[C] 不得含 en/em dash', header.full, FULL);
  check(header.full === ['26~27学年第一学期', '第', '10', '教学周'].join(' '),
    '[C] 空格位置（三段半角空格）', header.full, FULL);
  check(header.displayYear === '26~27', '[C] displayYear', header.displayYear, '26~27');
  check(header.termLabel === '第一学期', '[C] termLabel 用中文数字', header.termLabel, '第一学期');

  // ── D. 月历网格 ─────────────────────────────────────────────────────
  const grid = buildMonthGrid(2026, 11, semester, '2026-11-05');
  check(grid.length === 42, '[D] 网格应为 42 格', grid.length, 42);
  check(grid[0].date === '2026-10-26', '[D] 首格应为 2026-10-26（周一）', grid[0].date, '2026-10-26');
  check(grid[41].date === '2026-12-06', '[D] 末格应为 2026-12-06', grid[41].date, '2026-12-06');
  check(grid.filter((c) => c.inMonth).length === 30, '[D] 11 月应有 30 天在月内',
    grid.filter((c) => c.inMonth).length, 30);

  const cell1105 = grid.find((c) => c.date === '2026-11-05');
  check(Boolean(cell1105), '[D] 网格应包含 2026-11-05', Boolean(cell1105), true);
  if (cell1105) {
    check(cell1105.weekNo === 10, '[D] 11-05 角标应为第 10 周', cell1105.weekNo, 10);
    check(cell1105.isToday === true, '[D] 11-05 应为今日高亮', cell1105.isToday, true);
  }
  check(grid.filter((c) => c.isToday).length === 1, '[D] 今日高亮唯一',
    grid.filter((c) => c.isToday).length, 1);

  const cell1001 = grid.find((c) => c.date === '2026-10-01');
  check(cell1001 === undefined, '[D] 10-01 不在 11 月网格内', Boolean(cell1001), false);

  const octGrid = buildMonthGrid(2026, 10, semester, '2026-11-05');
  const oct1 = octGrid.find((c) => c.date === '2026-10-01');
  check(Boolean(oct1 && oct1.events.some((e) => e.title === '国庆节')),
    '[D] 10-01 应有「国庆节」节点', oct1 ? oct1.events : null, '国庆节');

  // ── E. weekOfDate / weekRangeOf 边界 ───────────────────────────────
  check(weekOfDate('2026-11-05', semester) === 10, '[E] weekOfDate 11-05', weekOfDate('2026-11-05', semester), 10);
  check(weekOfDate('2026-08-31', semester) === 1, '[E] weekOfDate 08-31', weekOfDate('2026-08-31', semester), 1);
  check(weekOfDate('2026-08-30', semester) === null, '[E] weekOfDate 开学前一天应为 null',
    weekOfDate('2026-08-30', semester), null);
  check(weekOfDate('2027-01-10', semester) === 19, '[E] weekOfDate 01-10', weekOfDate('2027-01-10', semester), 19);
  check(weekOfDate('2027-01-11', semester) === null, '[E] weekOfDate 假期中应为 null',
    weekOfDate('2027-01-11', semester), null);
  check(weekOfDate('2026-02-30', semester) === null, '[E] weekOfDate 非法日期应容错为 null',
    weekOfDate('2026-02-30', semester), null);

  const range10 = weekRangeOf(semester, 10);
  check(range10 && range10.start === '2026-11-02' && range10.end === '2026-11-08',
    '[E] 第 10 周应为 11-02 ~ 11-08', JSON.stringify(range10), '{"week":10,"start":"2026-11-02","end":"2026-11-08"}');
  check(weekRangeOf(semester, 0) === null, '[E] 第 0 周应为 null', weekRangeOf(semester, 0), null);
  check(weekRangeOf(semester, 20) === null, '[E] 第 20 周应为 null', weekRangeOf(semester, 20), null);

  // ── F. 汇总 ────────────────────────────────────────────────────────
  if (failures.length === 0) {
    process.stdout.write(`\n  [PASS] 教学周算法 ${passed} 项断言全部通过\n`);
    process.stdout.write('         A 15 条验收对表 / B 40 个抽样日期 / C 文案字符级 / D 42 格网格 / E 边界\n\n');
    process.exitCode = 0;
    return;
  }

  process.stderr.write(`\n  [FAIL] ${failures.length} 项断言失败（通过 ${passed} 项）\n\n`);
  for (const item of failures) {
    process.stderr.write(`    ✗ ${item}\n`);
  }
  process.stderr.write('\n');
  process.exitCode = 1;
}

main().catch((err) => {
  process.stderr.write(`\n  [ERROR] 测试脚本自身异常：${err && err.stack ? err.stack : err}\n\n`);
  process.exitCode = 1;
});
