/**
 * demo/grades.js —— 演示数据 · 三个学期的成绩单（纯数据，零逻辑）
 *
 * 覆盖 2025-2026-1（15 条）、2025-2026-2（13 条）、2026-2027-1（8 条，在读未录），共 36 条。
 *
 * 成绩 → 绩点换算（本项目统一口径，4.0 制；与 docs/PRD.md 一致）：
 *   90-100 → 4.0 ｜ 85-89 → 3.7 ｜ 82-84 → 3.3 ｜ 78-81 → 3.0 ｜ 75-77 → 2.7
 *   72-74  → 2.3 ｜ 68-71 → 2.0 ｜ 64-67 → 1.5 ｜ 60-63 → 1.0 ｜ < 60 → 0
 *
 * 必须存在的边界样本（页面渲染与 GPA 计算都要能扛）：
 *   · 未录入：`score: null` + `gradePoint: null`（2026-2027-1 全部 8 条中的 7 条）
 *   · 缓考：`score: null` + `gradePoint: null` + `remark: '缓考'`（中国古典文献学）
 *   · 不及格：`score: 59` + `gradePoint: 0`（古代汉语（二），次年重修通过）
 *   · 及格线：`score: 60` + `gradePoint: 1.0`（军事理论，恰好压线）
 *   · 重修：`isRetake: true` + `examType: 'retake'`（2026-2027-1 古代汉语（二）71 分）
 *   · 文字成绩：`scoreText: '优秀'`（中国传统文化概论 95、教师职业道德 93）
 *   · 成绩构成：`components`（平时/期末分项，2025-2026-1 放了 7 条样本）——
 *     其中「马克思主义基本原理」期末 48 不及格但总评 61 通过，是「必须看分项」的典型；
 *     「大学体育（三）」只有 `期末(100%)` 一项，用来走详情页的单列布局。
 *
 * 学分口径：同一 courseId 只计一次。古代汉语（二）在 2025-2026-2 不及格（0 学分），
 * 2026-2027-1 重修通过后的 4 学分才计入已获学分 —— 与 plan.js 的 earnedCredits 对齐。
 *
 * ⚠️ 这里的 gradePoint 只用于「单科成绩行」展示，**不再参与任何总 GPA 计算**。
 * 总 GPA 一律取 OFFICIAL_GPA（教务权威值）。历史教训：本地加权得 3.07、教务 3.16，
 * 差 0.09，两处数字并存会直接摧毁 App 的可信度。
 *
 * 本文件只导出常量，不含函数、不含颜色、不含外链。
 */

/**
 * 全部成绩记录（按学期、按教务系统导出顺序）。
 * @type {import('../types.js').Grade[]}
 */
export const GRADES = [
  // ── 2025-2026-1 大三上（15 条，学分合计 43，全部通过）──────────────────────
  {
    id: 'g-2526-1-01', courseId: '2025-1-0831002-01', courseCode: '0831002', courseName: '中国古代文学（二）',
    credits: 5, score: 88, gradePoint: 3.7, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
    components: [{ label: '平时', weight: 40, score: '90' }, { label: '期末', weight: 60, score: '87' }],
  },
  {
    id: 'g-2526-1-02', courseId: '2025-1-0831003-01', courseCode: '0831003', courseName: '中国现当代文学（一）',
    credits: 4, score: 91, gradePoint: 4.0, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-1-03', courseId: '2025-1-0831012-01', courseCode: '0831012', courseName: '古代汉语（一）',
    credits: 4, score: 79, gradePoint: 3.0, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
    components: [{ label: '平时', weight: 40, score: '85' }, { label: '期末', weight: 60, score: '75' }],
  },
  {
    id: 'g-2526-1-04', courseId: '2025-1-0831020-01', courseCode: '0831020', courseName: '现代汉语（一）',
    credits: 4, score: 86, gradePoint: 3.7, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-1-05', courseId: '2025-1-0831010-01', courseCode: '0831010', courseName: '文学概论',
    credits: 3, score: 92, gradePoint: 4.0, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
    components: [{ label: '平时', weight: 20, score: '88' }, { label: '期末', weight: 80, score: '93' }],
  },
  {
    id: 'g-2526-1-06', courseId: '2025-1-0831024-01', courseCode: '0831024', courseName: '语言学概论',
    credits: 3, score: 74, gradePoint: 2.3, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
    components: [{ label: '平时', weight: 40, score: '78' }, { label: '期末', weight: 60, score: '72' }],
  },
  {
    id: 'g-2526-1-07', courseId: '2025-1-0831025-01', courseCode: '0831025', courseName: '基础写作',
    credits: 2, score: 85, gradePoint: 3.7, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-1-08', courseId: '2025-1-0000101-01', courseCode: '0000101', courseName: '大学英语（三）',
    credits: 3, score: 68, gradePoint: 2.0, courseType: 'public', assessment: 'exam',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
    components: [{ label: '平时', weight: 40, score: '70' }, { label: '期末', weight: 60, score: '67' }],
  },
  {
    id: 'g-2526-1-09', courseId: '2025-1-0000203-01', courseCode: '0000203', courseName: '大学体育（三）',
    credits: 1, score: 90, gradePoint: 4.0, courseType: 'public', assessment: 'check',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
    components: [{ label: '期末', weight: 100, score: '90' }],
  },
  {
    id: 'g-2526-1-10', courseId: '2025-1-0000301-01', courseCode: '0000301', courseName: '计算机应用基础',
    credits: 3, score: 82, gradePoint: 3.3, courseType: 'public', assessment: 'exam',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-1-11', courseId: '2025-1-0000401-01', courseCode: '0000401', courseName: '思想道德与法治',
    credits: 3, score: 87, gradePoint: 3.7, courseType: 'public', assessment: 'exam',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-1-12', courseId: '2025-1-0000501-01', courseCode: '0000501', courseName: '中国传统文化概论',
    credits: 2, score: 95, scoreText: '优秀', gradePoint: 4.0, courseType: 'public', assessment: 'check',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-1-13', courseId: '2025-1-0832057-01', courseCode: '0832057', courseName: '三笔字与书法',
    credits: 1, score: 78, gradePoint: 3.0, courseType: 'practice', assessment: 'check',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-1-14', courseId: '2025-1-0000402-01', courseCode: '0000402', courseName: '马克思主义基本原理',
    credits: 3, score: 61, gradePoint: 1.0, courseType: 'public', assessment: 'exam',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
    components: [{ label: '平时', weight: 40, score: '80' }, { label: '期末', weight: 60, score: '48' }],
  },
  {
    id: 'g-2526-1-15', courseId: '2025-1-0000601-01', courseCode: '0000601', courseName: '军事理论',
    credits: 2, score: 60, gradePoint: 1.0, courseType: 'public', assessment: 'check',
    semesterId: '2025-2026-1', examType: 'normal', isRetake: false,
  },

  // ── 2025-2026-2 大三下（13 条，通过学分合计 28）────────────────────────────
  {
    id: 'g-2526-2-01', courseId: '2025-2-0831008-01', courseCode: '0831008', courseName: '中国古代文学（三）',
    credits: 5, score: 83, gradePoint: 3.3, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-2-02', courseId: '2025-2-0831009-01', courseCode: '0831009', courseName: '中国现当代文学（二）',
    credits: 4, score: 77, gradePoint: 2.7, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-2-03', courseId: '2025-2-0831040-01', courseCode: '0831040', courseName: '外国文学（一）',
    credits: 3, score: 89, gradePoint: 3.7, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false,
  },
  {
    // 不及格样本：59 分，绩点 0，次年重修通过
    id: 'g-2526-2-04', courseId: '2025-2-0831013-01', courseCode: '0831013', courseName: '古代汉语（二）',
    credits: 4, score: 59, gradePoint: 0, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false, remark: '不及格',
  },
  {
    id: 'g-2526-2-05', courseId: '2025-2-0832030-01', courseCode: '0832030', courseName: '教育学原理',
    credits: 3, score: 84, gradePoint: 3.3, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-2-06', courseId: '2025-2-0832032-01', courseCode: '0832032', courseName: '教育心理学',
    credits: 3, score: 80, gradePoint: 3.0, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-2-07', courseId: '2025-2-0000102-01', courseCode: '0000102', courseName: '大学英语（四）',
    credits: 3, score: 72, gradePoint: 2.3, courseType: 'public', assessment: 'exam',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-2-08', courseId: '2025-2-0000204-01', courseCode: '0000204', courseName: '大学体育（四）',
    credits: 1, score: 88, gradePoint: 3.7, courseType: 'public', assessment: 'check',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-2-09', courseId: '2025-2-0000403-01', courseCode: '0000403', courseName: '毛泽东思想和中国特色社会主义理论体系概论',
    credits: 3, score: 76, gradePoint: 2.7, courseType: 'public', assessment: 'exam',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-2-10', courseId: '2025-2-0832058-01', courseCode: '0832058', courseName: '书法基础',
    credits: 1, score: 90, gradePoint: 4.0, courseType: 'practice', assessment: 'check',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-2-11', courseId: '2025-2-0832059-01', courseCode: '0832059', courseName: '普通话训练',
    credits: 1, score: 87, gradePoint: 3.7, courseType: 'practice', assessment: 'check',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2526-2-12', courseId: '2025-2-0832060-01', courseCode: '0832060', courseName: '教师职业道德',
    credits: 1, score: 93, scoreText: '优秀', gradePoint: 4.0, courseType: 'required', assessment: 'check',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false,
  },
  {
    // 缓考样本：成绩与绩点均为 null，备注「缓考」，不计入 GPA，待补考
    id: 'g-2526-2-13', courseId: '2025-2-0831014-01', courseCode: '0831014', courseName: '中国古典文献学',
    credits: 2, score: null, gradePoint: null, courseType: 'required', assessment: 'exam',
    semesterId: '2025-2026-2', examType: 'normal', isRetake: false, remark: '缓考',
  },

  // ── 2026-2027-1 大四上（8 条，在读：1 条重修已出分 + 7 条未录入）──────────────
  {
    // 重修样本：4 学分计入学分，替代大三下的 59 分记录
    id: 'g-2627-1-01', courseId: '2025-2-0831013-01', courseCode: '0831013', courseName: '古代汉语（二）',
    credits: 4, score: 71, gradePoint: 2.0, courseType: 'required', assessment: 'exam',
    semesterId: '2026-2027-1', examType: 'retake', isRetake: true, remark: '重修',
  },
  {
    id: 'g-2627-1-02', courseId: '2026-1-0831004-01', courseCode: '0831004', courseName: '中国现当代文学（三）',
    credits: 3, score: null, gradePoint: null, courseType: 'required', assessment: 'exam',
    semesterId: '2026-2027-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2627-1-03', courseId: '2026-1-0831005-01', courseCode: '0831005', courseName: '古代文学专题',
    credits: 2, score: null, gradePoint: null, courseType: 'elective', assessment: 'check',
    semesterId: '2026-2027-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2627-1-04', courseId: '2026-1-0831011-01', courseCode: '0831011', courseName: '文学概论（二）',
    credits: 2, score: null, gradePoint: null, courseType: 'required', assessment: 'exam',
    semesterId: '2026-2027-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2627-1-05', courseId: '2026-1-0832011-01', courseCode: '0832011', courseName: '语文课程与教学论',
    credits: 2.5, score: null, gradePoint: null, courseType: 'required', assessment: 'exam',
    semesterId: '2026-2027-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2627-1-06', courseId: '2026-1-0832052-01', courseCode: '0832052', courseName: '现代教育技术',
    credits: 2, score: null, gradePoint: null, courseType: 'public', assessment: 'check',
    semesterId: '2026-2027-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2627-1-07', courseId: '2026-1-0832022-01', courseCode: '0832022', courseName: '教育哲学',
    credits: 2, score: null, gradePoint: null, courseType: 'elective', assessment: 'check',
    semesterId: '2026-2027-1', examType: 'normal', isRetake: false,
  },
  {
    id: 'g-2627-1-08', courseId: '2026-1-0000901-01', courseCode: '0000901', courseName: '形势与政策（三）',
    credits: 0.5, score: null, gradePoint: null, courseType: 'public', assessment: 'check',
    semesterId: '2026-2027-1', examType: 'normal', isRetake: false,
  },
];

/**
 * 各学期已获学分（不含未录入 / 不及格 / 缓考），供成绩页页脚与 GPA 页交叉核对。
 * 43 + 28 + 4 = 75
 * @type {Array<{semesterId: string, earnedCredits: number}>}
 */
export const SEMESTER_CREDITS = [
  { semesterId: '2025-2026-1', earnedCredits: 43 },
  { semesterId: '2025-2026-2', earnedCredits: 28 },
  { semesterId: '2026-2027-1', earnedCredits: 4 },
];

/**
 * 教务「学生学业情况查询」（信息查询 → 学生学业情况查询）的**真实快照**。
 *
 * 这是 2026-11-06 00:36 从 jwxt.tstc.edu.cn 真实教务系统抓下来的，逐字照抄，不是编的：
 *   · gpa 3.16 是教务页面自己印出来的数字，不是本地加权结果
 *     （本地按 grades.js 加权只有 3.07 —— 公选课不计入、重修取首次、五级制单独折算，
 *      三个口径差异叠出 0.09。学生一对比就会质疑 App 可信度，所以这里只许照抄。）
 *   · 该页面**没有**逐学期 GPA、**没有**总学分合计，因此不再提供「学期 GPA 趋势」/
 *     totalCredits / 四类学分拆分 / 专业排名 —— 一个都不许编。
 *   · modules 是页面上的学分树，扁平数组 + depth 表达缩进层级（每 2 空格一级）。
 *     主修 / 资格证书信息 / 其他课程 / 创新创业情况尚未数据信息 这四个节点页面上没有
 *     学分数字，一律填 0，不猜。
 *   · reachedRaw / unreachedRaw 保留教务的原始写法（'.60' → '0.60'、'.4' → '0.4'），
 *     它们是「门次达成度」而不是绩点，转成数字再格式化反而会和页面对不上。
 *
 * @type {import('../types.js').GPA}
 */
export const OFFICIAL_GPA = {
  gpa: 3.16,
  gpaOrigin: 'official',
  statsAt: '2026-11-06 00:36:27',
  fetchedAt: '2026-11-05T16:36:27.000Z',
  courseCount: {
    planned: 99,
    passed: 82,
    failed: 0,
    notTaken: 11,
    inProgress: 6,
    extraPassed: 0,
    extraFailed: 0,
  },
  modules: [
    { name: '主修', depth: 0, requireCredits: 0, earnedCredits: 0, missingCredits: 0 },
    { name: '必修课', depth: 1, requireCredits: 27.75, earnedCredits: 123.0, missingCredits: 0.0, logic: '并且' },
    {
      name: '必修课公共部分', depth: 2, requireCredits: 0.0, earnedCredits: 117.5, missingCredits: 0.0,
      requiredCount: 2, reachedRaw: '0.60', unreachedRaw: '0.0', courseTotal: 89, coursePassed: 80,
    },
    {
      name: '必修课方向部分', depth: 2, requireCredits: 0.0, earnedCredits: 5.5, missingCredits: 0.0,
      requiredCount: 2, reachedRaw: '0.4', unreachedRaw: '0.0', logic: '缺省',
    },
    {
      name: '公费师范生方向', depth: 3, requireCredits: 0.0, earnedCredits: 5.5, missingCredits: 0.0,
      requiredCount: 2, reachedRaw: '0.4', unreachedRaw: '0.0',
    },
    { name: '限选课', depth: 1, requireCredits: 0.0, earnedCredits: 5.5, missingCredits: 0.0, logic: '并且' },
    {
      name: '限选课公共部分', depth: 2, requireCredits: 0.0, earnedCredits: 5.5, missingCredits: 0.0,
      requiredCount: 2, reachedRaw: '0.3', unreachedRaw: '0.0', courseTotal: 4, coursePassed: 3,
    },
    {
      name: '限选课方向部分', depth: 2, requireCredits: 0.0, earnedCredits: 0.0, missingCredits: 0.0,
      requiredCount: 2, reachedRaw: '0.0', unreachedRaw: '0.2', logic: '或者',
    },
    {
      name: '公费师范生方向', depth: 3, requireCredits: 0.0, earnedCredits: 0.0, missingCredits: 0.0,
      requiredCount: 2, reachedRaw: '0.0', unreachedRaw: '2', courseTotal: 0, coursePassed: 0,
    },
    {
      name: '任选课', depth: 1, requireCredits: 21.0, earnedCredits: 10.5, missingCredits: 10.5,
      courseTotal: 14, coursePassed: 7,
    },
    {
      name: '人文社会与科学素养选修课', depth: 1, requireCredits: 11.0, earnedCredits: 16.0,
      missingCredits: 0.0, logic: '并且',
    },
    { name: '人文社科类限选课', depth: 2, requireCredits: 8.0, earnedCredits: 12.0, missingCredits: 0.0 },
    {
      name: '外语技能与信息技术类', depth: 3, requireCredits: 3.0, earnedCredits: 6.0, missingCredits: 0.0,
      courseTotal: 2, coursePassed: 2,
    },
    {
      name: '艺术修养类', depth: 3, requireCredits: 2.0, earnedCredits: 3.0, missingCredits: 0.0,
      courseTotal: 1, coursePassed: 1,
    },
    {
      name: '交叉融合类', depth: 3, requireCredits: 2.0, earnedCredits: 2.0, missingCredits: 0.0,
      courseTotal: 1, coursePassed: 1,
    },
    {
      name: '礼仪与沟通类', depth: 3, requireCredits: 1.0, earnedCredits: 1.0, missingCredits: 0.0,
      requiredCount: 1, reachedRaw: '1', unreachedRaw: '0', courseTotal: 1, coursePassed: 1,
    },
    {
      name: '人文社素养任选课', depth: 2, requireCredits: 3.0, earnedCredits: 4.0, missingCredits: 0.0,
      courseTotal: 2, coursePassed: 2,
    },
    {
      name: '教师教育非必修课', depth: 1, requireCredits: 1.0, earnedCredits: 2.0, missingCredits: 0.0,
      courseTotal: 1, coursePassed: 1,
    },
    { name: '资格证书信息', depth: 0, requireCredits: 0, earnedCredits: 0, missingCredits: 0 },
    { name: '其他课程', depth: 0, requireCredits: 0, earnedCredits: 0, missingCredits: 0, courseTotal: 0, coursePassed: 0 },
    { name: '创新创业情况尚未数据信息', depth: 0, requireCredits: 0, earnedCredits: 0, missingCredits: 0 },
  ],
  disclaimer: '此页面信息仅做学业修读情况参考。',
};
