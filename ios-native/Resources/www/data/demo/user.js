/**
 * demo/user.js —— 演示数据 · 学生档案与学期元数据（纯数据，零逻辑）
 *
 * 事实锚点（不可偏离，来自 docs/PRD.md 与 docs/school-calendar.json）：
 *   · 学生：陈铭霏 / 学号 231400000678 / 文学院 / 23中文本科4班（公费师范）/ 2023 级
 *   · 专业：汉语言文学（师范）
 *   · 当前学期 2026-2027-1，第 10 教学周 = 2026-11-02 ~ 2026-11-08
 *
 * 与校历的一致性：
 *   `2026-2027-1` 的 semesterStart / semesterEnd / totalWeeks / teachingWeeks / examWeeks
 *   与 `web/data/school-calendar.json` 逐字段相同（该校历是教务处原件副本，不得改写）。
 *   2025-2026-1 / 2025-2026-2 两个历史学期为演示数据自备，semesterStart 均取当周周一。
 *
 * 本文件只导出常量，不含任何函数、不含颜色、不含外链。
 */

/**
 * 演示数据的「今天」锚点。
 * 演示模式下所有「今日/本周/倒计时」都以它为基准，保证每次打开看到的是同一周。
 * 对应 2026-2027-1 第 10 教学周的周四。
 * @type {string}
 */
export const DEMO_TODAY = '2026-11-05';

/**
 * 学生档案。
 * @type {import('../typesApp.js').StudentProfile}
 */
export const STUDENT = {
  name: '陈铭霏',
  studentNo: '231400000678',
  college: '文学院',
  className: '23中文本科4班（公费师范）',
  major: '汉语言文学（师范）',
  grade: '2023',
  avatarText: '霏',
  // —— 以下为演示数据自用的补充字段（不在 StudentProfile 契约内，供「我的」页展示）——
  gender: '女',
  ethnicity: '汉族',
  politicalStatus: '共青团员',
  enrollDate: '2023-09-09',
  counselor: '田玉梅',
  phone: '0315-3863xxx',
};

/** 当前学期 ID（与 SEMESTERS 中的 id 对应）。 */
export const CURRENT_SEMESTER_ID = '2026-2027-1';

/**
 * 三个学期的元数据（按时间升序）。
 * @type {import('../types.js').Semester[]}
 */
export const SEMESTERS = [
  {
    id: '2025-2026-1',
    academicYear: '2025-2026',
    displayYear: '25~26',
    term: 1,
    termLabel: '第一学期',
    semesterStart: '2025-09-01',
    semesterEnd: '2026-01-11',
    totalWeeks: 19,
    teachingWeeks: 17,
    examWeeks: [18, 19],
    isCurrent: false,
  },
  {
    id: '2025-2026-2',
    academicYear: '2025-2026',
    displayYear: '25~26',
    term: 2,
    termLabel: '第二学期',
    semesterStart: '2026-02-23',
    semesterEnd: '2026-06-28',
    totalWeeks: 18,
    teachingWeeks: 16,
    examWeeks: [17, 18],
    isCurrent: false,
  },
  {
    id: '2026-2027-1',
    academicYear: '2026-2027',
    displayYear: '26~27',
    term: 1,
    termLabel: '第一学期',
    semesterStart: '2026-08-31',
    semesterEnd: '2027-01-10',
    totalWeeks: 19,
    teachingWeeks: 17,
    examWeeks: [18, 19],
    isCurrent: true,
  },
];
