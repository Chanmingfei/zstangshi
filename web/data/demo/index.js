/**
 * demo/index.js —— 演示数据集的唯一汇总出口（纯 re-export，零逻辑）
 *
 * 约定：`web/data/demoProvider.js` 只允许 `import { DEMO } from './demo/index.js'`，
 * 不得直接深入 user.js / courses.js / grades.js 等具体文件 —— 保持「一处出口、一处替换」，
 * 将来接真实教务时只换 provider，页面代码零改动。
 *
 * 注意：本文件不含任何计算。像「成绩按学期分组成 bySemester」「余量 = capacity − enrolled」
 * 这类派生动作，一律由 demoProvider 或 selectors 现算，数据层只存原始事实。
 * 例外是 GPA：它是数据层直接存下来的**教务原样快照**（OFFICIAL_GPA），不许在这里重算。
 *
 * 数据规模速查：
 *   · SEMESTERS          3 个学期（当前 2026-2027-1）
 *   · COURSES           29 条课表条目，第 10 周 15 门、其中周四 4 门（全学期/前半/后半配平后的实测值）
 *   · GRADES            36 条成绩（含未录入 / 缓考 / 不及格 / 重修 / 文字成绩）
 *   · SEMESTER_CREDITS   3 个学期的已获学分（43 + 28 + 4 = 75）
 *   · OFFICIAL_GPA        教务权威快照：GPA 3.16 / 计划 99 门 通过 82 门
 *   · COURSE_SELECTION  22 门选课池（已满 3 / 紧张 6 / 充足 13），2 个轮次
 *   · RECOMMENDED_TIMETABLE 教务「推荐课表打印」的一张周课表（10 门，与个人课表同构）
 */

export { DEMO_TODAY, STUDENT, CURRENT_SEMESTER_ID, SEMESTERS } from './user.js';
export { COURSES } from './courses.js';
export { GRADES, SEMESTER_CREDITS, OFFICIAL_GPA } from './grades.js';
export { SELECTION_ROUNDS, COURSE_SELECTION } from './selection.js';
export { RECOMMENDED_TIMETABLE } from './recommended.js';

import { DEMO_TODAY, STUDENT, CURRENT_SEMESTER_ID, SEMESTERS } from './user.js';
import { COURSES } from './courses.js';
import { GRADES, SEMESTER_CREDITS, OFFICIAL_GPA } from './grades.js';
import { SELECTION_ROUNDS, COURSE_SELECTION } from './selection.js';
import { RECOMMENDED_TIMETABLE } from './recommended.js';

/**
 * 演示数据全集（demoProvider 用它一次性填充 8 个数据槽）。
 * `grades` 是平铺数组，按学期分组由 provider 负责。
 * `gpa` 是教务「学生学业情况查询」的真快照，**provider 直接原样返回，不做二次计算**。
 * @type {{
 *   today: string,
 *   profile: import('../typesApp.js').StudentProfile,
 *   semesters: import('../types.js').Semester[],
 *   currentSemesterId: string,
 *   timetable: import('../types.js').Course[],
 *   grades: import('../types.js').Grade[],
 *   semesterCredits: Array<{semesterId: string, earnedCredits: number}>,
 *   gpa: import('../types.js').GPA,
 *   selection: import('../types.js').CourseSelection,
 *   selectionRounds: Array<import('../types.js').SelectionRound & {id: string}>,
 *   recommend: import('../types.js').RecommendedTimetable
 * }}
 */
export const DEMO = {
  today: DEMO_TODAY,
  profile: STUDENT,
  semesters: SEMESTERS,
  currentSemesterId: CURRENT_SEMESTER_ID,
  timetable: COURSES,
  grades: GRADES,
  semesterCredits: SEMESTER_CREDITS,
  gpa: OFFICIAL_GPA,
  selection: COURSE_SELECTION,
  selectionRounds: SELECTION_ROUNDS,
  recommend: RECOMMENDED_TIMETABLE,
};
