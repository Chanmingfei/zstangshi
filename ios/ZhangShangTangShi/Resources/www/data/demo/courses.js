/**
 * demo/courses.js —— 演示数据 · 2026-2027-1（大四上）课表条目（纯数据，零逻辑）
 *
 * 29 条课表格（29 门课），按周次分六组：
 *   A 1-17 周（5 门）  语文课程与教学论、教育哲学、中国现当代文学（三）、
 *                      古代文学专题、古代汉语（二）重修班 —— 贯穿全学期的长线课
 *   B 1-9 周（8 门）   前半程教师教育 / 学科基础模块
 *   C 1-8 周（1 门）   教师职业道德（专题）
 *   D 1-6 / 2-6 周（2 门）  形势与政策（三）、马克思主义文艺理论 —— 短程公共课
 *   F 10-17 周（9 门） 后半程专业选修 / 实践模块
 *   G 11-17 周（2 门） 中国传统美学、中国现代文学思潮 —— 第 11 周起新开的选修专题
 *   另有单周 1 门（教师书写技能训练，1/3/…/15 周）与双周 1 门（普通话水平测试训练，2/4/…/16 周）
 *
 * 周课程数曲线（1-19 周，复习考试周 18-19 无课，与校历 examWeeks [18,19] 一致）：
 *   周:  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16 17 18 19
 *   门: 16 17 17 17 17 17 15 15 14 15 17 17 17 17 17 17 16  0  0
 *   —— 相邻两周最大波动 2 门，无断崖；第 10 周 15 门是正常教学周。
 *
 * 第 10 周（2026-11-02 ~ 11-08）15 门，按天分布：周一 2 / 周二 3 / 周三 4 / 周四 4 / 周五 2。
 * 周四 11-05 共 4 门，覆盖上午与下午：
 *   1-2 节 语文课程与教学论（张文娟·敏行楼C305）
 *   3-4 节 教育哲学（吴桂芳·明德楼B302）
 *   5-6 节 说课与评课训练（周雅静·敏行楼C201）
 *   7-8 节 汉语史专题（赵敏·博学楼A201）
 *
 * 刻意保留的真实数据特征：
 *   1. 时间冲突：「教育学原理（专题）」周三 5-6 节（1-9 周）与「普通话水平测试训练」
 *      周三 5-6 节（双周 2/4/6/8 周）撞课 —— 语测训练插空造成，课表页必须能标红。全表仅此 1 处。
 *   2. 单双周：教师书写技能训练只在单周，普通话水平测试训练只在双周。
 *   3. 古代汉语（二）是重修班（1-17 周），对应 grades.js 里 2025-2026-2 的 59 分与
 *      2026-2027-1 的 71 分重修记录。
 *
 * `weeks` 是唯一真源；`weekRange` / `weekParity` 是派生字段，由 weeks 现算，**不写进数据**。
 * 作息取真实 12 节：1-2 节 08:00-09:35，3-4 节 09:55-11:30，5-6 节 14:00-15:35，
 * 7-8 节 15:55-17:30，10-11 节 19:00-20:35。
 *
 * 本文件只导出常量，不含函数、不含颜色、不含外链。
 */

/**
 * 本学期全部课表条目。
 * @type {import('../types.js').Course[]}
 */
export const COURSES = [
  // ── A 贯穿全学期（1-17 周，5 门）──────────────────────────────────────────
  {
    id: '2026-1-0832011-01', code: '0832011', name: '语文课程与教学论', teacher: '张文娟',
    credits: 2.5, hours: 40, courseType: 'required', assessment: 'exam',
    campus: '主校区', location: '敏行楼C305', classNo: '01', capacity: 48, enrolled: 48,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 4, sections: [1, 2], startTime: '08:00', endTime: '09:35', isSelected: true,
  },
  {
    id: '2026-1-0832022-01', code: '0832022', name: '教育哲学', teacher: '吴桂芳',
    credits: 2, hours: 32, courseType: 'elective', assessment: 'check',
    campus: '主校区', location: '明德楼B302', classNo: '01', capacity: 60, enrolled: 57,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 4, sections: [3, 4], startTime: '09:55', endTime: '11:30', isSelected: true,
  },
  {
    id: '2026-1-0831004-01', code: '0831004', name: '中国现当代文学（三）', teacher: '李慧敏',
    credits: 3, hours: 48, courseType: 'required', assessment: 'exam',
    campus: '主校区', location: '明德楼B201', classNo: '01', capacity: 62, enrolled: 61,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 1, sections: [1, 2], startTime: '08:00', endTime: '09:35', isSelected: true,
  },
  {
    id: '2026-1-0831005-01', code: '0831005', name: '古代文学专题', teacher: '秦淑芬',
    credits: 2, hours: 32, courseType: 'elective', assessment: 'check',
    campus: '主校区', location: '博学楼A303', classNo: '01', capacity: 55, enrolled: 52,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 2, sections: [5, 6], startTime: '14:00', endTime: '15:35', isSelected: true,
  },
  {
    // 重修班：对应 grades.js 2025-2026-2 的 59 分与 2026-2027-1 的 71 分重修记录
    id: '2026-1-0831013-01', code: '0831013', name: '古代汉语（二）', teacher: '王建国',
    credits: 4, hours: 64, courseType: 'required', assessment: 'exam',
    campus: '主校区', location: '博学楼A302', classNo: '02', capacity: 30, enrolled: 26,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 3, sections: [1, 2], startTime: '08:00', endTime: '09:35', isSelected: true,
  },

  // ── B 前半学期（1-9 周，8 门）──────────────────────────────────────────────
  {
    id: '2026-1-0831011-01', code: '0831011', name: '文学概论（二）', teacher: '孙丽华',
    credits: 2, hours: 32, courseType: 'required', assessment: 'exam',
    campus: '主校区', location: '博学楼A405', classNo: '01', capacity: 60, enrolled: 59,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9],
    dayOfWeek: 1, sections: [3, 4], startTime: '09:55', endTime: '11:30', isSelected: true,
  },
  {
    id: '2026-1-0831021-01', code: '0831021', name: '现代汉语（二）', teacher: '赵敏',
    credits: 3, hours: 48, courseType: 'required', assessment: 'exam',
    campus: '主校区', location: '博学楼A201', classNo: '01', capacity: 62, enrolled: 60,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9],
    dayOfWeek: 2, sections: [1, 2], startTime: '08:00', endTime: '09:35', isSelected: true,
  },
  {
    id: '2026-1-0831022-01', code: '0831022', name: '语言学概论（二）', teacher: '陈立新',
    credits: 2, hours: 32, courseType: 'required', assessment: 'exam',
    campus: '主校区', location: '博学楼A108', classNo: '01', capacity: 58, enrolled: 58,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9],
    dayOfWeek: 3, sections: [3, 4], startTime: '09:55', endTime: '11:30', isSelected: true,
  },
  {
    id: '2026-1-0831023-01', code: '0831023', name: '基础写作（二）', teacher: '周雅静',
    credits: 2, hours: 32, courseType: 'required', assessment: 'check',
    campus: '主校区', location: '敏行楼C201', classNo: '01', capacity: 50, enrolled: 48,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9],
    dayOfWeek: 1, sections: [5, 6], startTime: '14:00', endTime: '15:35', isSelected: true,
  },
  {
    // 时间冲突的另一半：与「普通话水平测试训练」周三 5-6 节在 2/4/6/8 周撞课
    id: '2026-1-0832031-01', code: '0832031', name: '教育学原理（专题）', teacher: '郑文博',
    credits: 2, hours: 32, courseType: 'required', assessment: 'check',
    campus: '主校区', location: '明德楼B208', classNo: '01', capacity: 60, enrolled: 56,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9],
    dayOfWeek: 3, sections: [5, 6], startTime: '14:00', endTime: '15:35', isSelected: true,
  },
  {
    id: '2026-1-0832041-01', code: '0832041', name: '语文教材研究', teacher: '徐立群',
    credits: 2, hours: 32, courseType: 'required', assessment: 'check',
    campus: '主校区', location: '敏行楼C410', classNo: '01', capacity: 48, enrolled: 45,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9],
    dayOfWeek: 5, sections: [1, 2], startTime: '08:00', endTime: '09:35', isSelected: true,
  },
  {
    id: '2026-1-0832042-01', code: '0832042', name: '班主任工作', teacher: '田玉梅',
    credits: 1, hours: 16, courseType: 'public', assessment: 'check',
    campus: '主校区', location: '明德楼B310', classNo: '01', capacity: 70, enrolled: 66,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9],
    dayOfWeek: 5, sections: [3, 4], startTime: '09:55', endTime: '11:30', isSelected: true,
  },
  {
    id: '2026-1-0832052-01', code: '0832052', name: '现代教育技术', teacher: '徐立群',
    credits: 2, hours: 32, courseType: 'public', assessment: 'check',
    campus: '主校区', location: '敏行楼C410', classNo: '01', capacity: 50, enrolled: 47,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9],
    dayOfWeek: 5, sections: [5, 6], startTime: '14:00', endTime: '15:35', isSelected: true,
  },

  // ── C 1-8 周（1 门）───────────────────────────────────────────────────────
  {
    id: '2026-1-0832043-01', code: '0832043', name: '教师职业道德（专题）', teacher: '田玉梅',
    credits: 1, hours: 16, courseType: 'required', assessment: 'check',
    campus: '主校区', location: '明德楼B310', classNo: '01', capacity: 70, enrolled: 68,
    weeks: [1, 2, 3, 4, 5, 6, 7, 8],
    dayOfWeek: 4, sections: [5, 6], startTime: '14:00', endTime: '15:35', isSelected: true,
  },

  // ── D 短程公共课（形势与政策 2-6 周 / 马文艺理论 1-6 周）──────────────────
  {
    id: '2026-1-0000901-01', code: '0000901', name: '形势与政策（三）', teacher: '高志远',
    credits: 0.5, hours: 8, courseType: 'public', assessment: 'check',
    campus: '主校区', location: '明德楼B401', classNo: '01', capacity: 120, enrolled: 118,
    weeks: [2, 3, 4, 5, 6],
    dayOfWeek: 3, sections: [7, 8], startTime: '15:55', endTime: '17:30', isSelected: true,
  },
  {
    id: '2026-1-0831031-01', code: '0831031', name: '马克思主义文艺理论', teacher: '罗建军',
    credits: 2, hours: 32, courseType: 'elective', assessment: 'check',
    campus: '主校区', location: '博学楼A207', classNo: '01', capacity: 55, enrolled: 50,
    weeks: [1, 2, 3, 4, 5, 6],
    dayOfWeek: 2, sections: [3, 4], startTime: '09:55', endTime: '11:30', isSelected: true,
  },

  // ── F 后半学期（10-17 周，9 门）───────────────────────────────────────────
  {
    id: '2026-1-0832053-01', code: '0832053', name: '说课与评课训练', teacher: '周雅静',
    credits: 1.5, hours: 24, courseType: 'practice', assessment: 'check',
    campus: '主校区', location: '敏行楼C201', classNo: '01', capacity: 48, enrolled: 43,
    weeks: [10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 4, sections: [5, 6], startTime: '14:00', endTime: '15:35', isSelected: true,
  },
  {
    id: '2026-1-0831034-01', code: '0831034', name: '汉语史专题', teacher: '赵敏',
    credits: 2, hours: 32, courseType: 'elective', assessment: 'check',
    campus: '主校区', location: '博学楼A201', classNo: '01', capacity: 50, enrolled: 44,
    weeks: [10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 4, sections: [7, 8], startTime: '15:55', endTime: '17:30', isSelected: true,
  },
  {
    id: '2026-1-0831006-01', code: '0831006', name: '中国古代文学（四）', teacher: '秦淑芬',
    credits: 3, hours: 48, courseType: 'required', assessment: 'exam',
    campus: '主校区', location: '博学楼A303', classNo: '01', capacity: 62, enrolled: 60,
    weeks: [10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 1, sections: [7, 8], startTime: '15:55', endTime: '17:30', isSelected: true,
  },
  {
    id: '2026-1-0831007-01', code: '0831007', name: '外国文学（三）', teacher: '李慧敏',
    credits: 3, hours: 48, courseType: 'required', assessment: 'exam',
    campus: '主校区', location: '明德楼B201', classNo: '01', capacity: 62, enrolled: 59,
    weeks: [10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 2, sections: [1, 2], startTime: '08:00', endTime: '09:35', isSelected: true,
  },
  {
    id: '2026-1-0831032-01', code: '0831032', name: '文字学（二）', teacher: '赵敏',
    credits: 2, hours: 32, courseType: 'elective', assessment: 'exam',
    campus: '主校区', location: '博学楼A201', classNo: '01', capacity: 55, enrolled: 53,
    weeks: [10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 3, sections: [3, 4], startTime: '09:55', endTime: '11:30', isSelected: true,
  },
  {
    id: '2026-1-0831033-01', code: '0831033', name: '中国古典文献学（二）', teacher: '秦淑芬',
    credits: 2, hours: 32, courseType: 'elective', assessment: 'check',
    campus: '主校区', location: '博学楼A303', classNo: '01', capacity: 50, enrolled: 46,
    weeks: [10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 3, sections: [7, 8], startTime: '15:55', endTime: '17:30', isSelected: true,
  },
  {
    id: '2026-1-0832044-01', code: '0832044', name: '语文名师课例研究', teacher: '张文娟',
    credits: 1.5, hours: 24, courseType: 'practice', assessment: 'check',
    campus: '主校区', location: '敏行楼C305', classNo: '01', capacity: 48, enrolled: 48,
    weeks: [10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 5, sections: [10, 11], startTime: '19:00', endTime: '20:35', isSelected: true,
  },
  {
    id: '2026-1-0832054-01', code: '0832054', name: '教育法律法规', teacher: '高志远',
    credits: 1, hours: 16, courseType: 'public', assessment: 'check',
    campus: '主校区', location: '明德楼B401', classNo: '01', capacity: 80, enrolled: 72,
    weeks: [10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 5, sections: [3, 4], startTime: '09:55', endTime: '11:30', isSelected: true,
  },
  {
    id: '2026-1-0831035-01', code: '0831035', name: '比较文学专题研究', teacher: '罗建军',
    credits: 2, hours: 32, courseType: 'elective', assessment: 'check',
    campus: '主校区', location: '博学楼A207', classNo: '01', capacity: 50, enrolled: 41,
    weeks: [10, 11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 2, sections: [3, 4], startTime: '09:55', endTime: '11:30', isSelected: true,
  },

  // ── G 第 11 周起新开的选修专题（11-17 周，2 门）────────────────────────────
  {
    id: '2026-1-0831036-01', code: '0831036', name: '中国传统美学', teacher: '郑文博',
    credits: 2, hours: 32, courseType: 'elective', assessment: 'check',
    campus: '主校区', location: '明德楼B208', classNo: '01', capacity: 50, enrolled: 38,
    weeks: [11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 3, sections: [10, 11], startTime: '19:00', endTime: '20:35', isSelected: true,
  },
  {
    id: '2026-1-0831037-01', code: '0831037', name: '中国现代文学思潮', teacher: '孙丽华',
    credits: 2, hours: 32, courseType: 'elective', assessment: 'check',
    campus: '主校区', location: '博学楼A405', classNo: '01', capacity: 50, enrolled: 39,
    weeks: [11, 12, 13, 14, 15, 16, 17],
    dayOfWeek: 2, sections: [10, 11], startTime: '19:00', endTime: '20:35', isSelected: true,
  },

  // ── 单双周（各 1 门）────────────────────────────────────────────────────
  {
    // 单周：1/3/5/7/9/11/13/15 周上课，双周不上
    id: '2026-1-0832055-01', code: '0832055', name: '教师书写技能训练', teacher: '马世昌',
    credits: 1, hours: 16, courseType: 'practice', assessment: 'check',
    campus: '主校区', location: '敏行楼C102', classNo: '01', capacity: 40, enrolled: 38,
    weeks: [1, 3, 5, 7, 9, 11, 13, 15],
    dayOfWeek: 5, sections: [7, 8], startTime: '15:55', endTime: '17:30', isSelected: true,
  },
  {
    // 双周：2/4/6/8/10/12/14/16 周；2/4/6/8 周与「教育学原理（专题）」周三 5-6 撞课
    id: '2026-1-0832056-01', code: '0832056', name: '普通话水平测试训练', teacher: '田玉梅',
    credits: 1, hours: 16, courseType: 'practice', assessment: 'check',
    campus: '主校区', location: '敏行楼C102', classNo: '01', capacity: 40, enrolled: 40,
    weeks: [2, 4, 6, 8, 10, 12, 14, 16],
    dayOfWeek: 3, sections: [5, 6], startTime: '14:00', endTime: '15:35', isSelected: true,
  },
];
