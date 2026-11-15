/**
 * selectionUtils.js —— 选课页（P09）用的纯函数（零 DOM、零 store）
 *
 * 三条口径，都是被真实选课场景逼出来的：
 *   1. **冲突只在「时间」一个维度判**：两门课 `dayOfWeek` 相同 + `sections` 有交集 +
 *      `weeks` 有交集。三者缺一不可 —— 只看星期与节次会把「11 周才开」的课判成跟
 *      「1-9 周」的课撞车，那是假冲突，会白白劝退用户。
 *      **不做**教室重复占用（单人上课不存在），**不做**连堂间隔（12 小节之间没有相邻约束）。
 *   2. **余量三档**：已满（0）/ 紧张（1-4）/ 充足（≥5）。真实选课里余量 1-4 就是抢不到的
 *      边缘，直接用「已满 / 未满」两档会让用户以为还有机会。
 *   3. **已选判定以 Course.isSelected 为准**，写操作一律走 `actions.toggleSelection()`
 *      （轮次上限 / 余量 / 未开放三道闸、乐观更新与失败回滚都在 action 里），页面不自己改 store。
 *      推荐课表页（P08）是教务「推荐课表打印」返回的一张周课表，与选课池无关，
 *      不存在推荐方案、匹配度与志愿顺序 —— 早期那套设计已作废。
 */

import { WEEKDAY_LABELS } from '../data/calendar.js';
import { periodInfo } from './timetableUtils.js';

/** 余量低于这个数算「紧张」 */
const TIGHT_LEFT = 5;

/**
 * 任意值 → 数字，取不到就是 0。
 * @param {unknown} value
 * @returns {number}
 */
function num(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

/**
 * 非数组一律当空数组（数据来自 provider，宁可少画也不要抛错）。
 * @param {unknown} value
 * @returns {any[]}
 */
function arr(value) {
  return Array.isArray(value) ? value : [];
}

/**
 * 两个数组有没有交集。
 * @param {unknown} a
 * @param {unknown} b
 * @returns {boolean}
 */
function overlaps(a, b) {
  const left = arr(a);
  const right = arr(b);
  if (left.length === 0 || right.length === 0) return false;
  const set = new Set(left.map(Number));
  return right.some((item) => set.has(Number(item)));
}

/**
 * 这门课是否与已选课表时间冲突。
 *
 * @param {object} course 待判定的课程
 * @param {object[]} selectedCourses 已选课程（本学期课表 + 本轮已选，可含自身，函数内会跳过）
 * @returns {boolean}
 */
export function conflictsWith(course, selectedCourses) {
  if (!course) return false;
  const day = num(course.dayOfWeek);
  if (!day) return false;
  for (const other of arr(selectedCourses)) {
    if (!other || other.id === course.id) continue;
    if (num(other.dayOfWeek) !== day) continue;
    if (!overlaps(course.sections, other.sections)) continue;
    // 同一周才叫冲突：11-19 周的课跟 1-9 周的课不碰面
    if (!overlaps(course.weeks, other.weeks)) continue;
    return true;
  }
  return false;
}

/**
 * 余量档位。
 * @param {object} course
 * @returns {'full'|'tight'|'enough'}
 */
export function stockLevel(course) {
  const left = num(course && course.capacity) - num(course && course.enrolled);
  if (left <= 0) return 'full';
  if (left < TIGHT_LEFT) return 'tight';
  return 'enough';
}

/**
 * 纯筛选。`filter` 形状与 `ui.selectionFilter` 一致，另支持四个可选项：
 * `q`（课名 / 教师 / 课号关键字）、`onlyNoConflict`、`conflicting`（冲突课程 id 列表）。
 *
 * @param {object[]} courses
 * @param {{type?: string, dept?: string, onlyAvailable?: boolean, onlyNoConflict?: boolean,
 *          conflicting?: string[], q?: string}} [filter]
 * @returns {object[]}
 */
export function filterCourses(courses, filter = {}) {
  const list = arr(courses);
  const type = filter.type || 'all';
  const dept = filter.dept || 'all';
  const keyword = String(filter.q || '').trim().toLowerCase();
  const conflictIds = new Set(arr(filter.conflicting).map(String));

  return list.filter((course) => {
    if (!course) return false;
    if (type !== 'all' && course.courseType !== type) return false;
    if (dept !== 'all' && String(course.dept || '') !== dept) return false;
    if (filter.onlyAvailable && stockLevel(course) === 'full') return false;
    if (filter.onlyNoConflict && conflictIds.has(String(course.id))) return false;
    if (keyword) {
      const haystack = `${course.name || ''}${course.teacher || ''}${course.code || ''}`.toLowerCase();
      if (!haystack.includes(keyword)) return false;
    }
    return true;
  });
}

/**
 * 顶部三项统计。`totalCredits` 是整池学分合计（轮次卡上告知总量）。
 * `intent` 只是 `ui.selectionIntent` 的兼容参数：P08 不再写入它，
 * `toggleSelection()` 仍会同步维护，取并集与 `isSelected` 口径一致。
 *
 * @param {object[]} courses
 * @param {string[]} [intent] ui.selectionIntent（兼容参数，通常为空）
 * @returns {{picked: number, credits: number, totalCredits: number, fullCount: number}}
 */
export function selectionStats(courses, intent = []) {
  const list = arr(courses);
  const order = arr(intent).map(String);
  const picked = list.filter((course) => course && (course.isSelected || order.includes(String(course.id))));
  const credits = picked.reduce((sum, course) => sum + num(course.credits), 0);
  const totalCredits = list.reduce((sum, course) => sum + num(course.credits), 0);
  return {
    picked: picked.length,
    // 学分有 .5，浮点连加会出 6.999999，保留一位
    credits: Math.round(credits * 10) / 10,
    totalCredits: Math.round(totalCredits * 10) / 10,
    fullCount: list.filter((course) => stockLevel(course) === 'full').length,
  };
}

/**
 * 时间文案：`周二 5-6 节`。
 * 节次折叠（`第 5-6 节` / `第 5,7 节`）复用课表页的 periodInfo，避免两处各写一套规则。
 *
 * @param {object} course
 * @returns {string}
 */
export function timeTextOf(course) {
  const day = num(course && course.dayOfWeek);
  const head = day >= 1 && day <= 7 ? `周${'一二三四五六日'[day - 1]}` : '周—';
  const sections = arr(course && course.sections).map(Number).filter(Number.isFinite);
  if (sections.length === 0) return `${head} ——`;
  const text = periodInfo(sections).sections;
  return `${head} ${text.replace(/^第\s*/, '').replace(/\s*节$/, '')} 节`;
}
