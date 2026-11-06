/**
 * demo/selection.js —— 演示数据 · 2026-2027-1 选课（纯数据，零逻辑）
 *
 * 两个轮次：第一轮（专业选修）已结束 closed，第二轮（通识选修补选）正在进行 open。
 * 课程池 22 门，余量（capacity − enrolled）刻意分成三档，页面上的「已满 / 紧张 / 充足」
 * 标签直接据此判定，不在数据里重复存一份：
 *   · 已满   余量 === 0        3 门：诗经研究、唐宋八大家散文研究、中学语文名篇解读
 *   · 紧张   1 ≤ 余量 < 5      6 门：汉语修辞学、中国文学批评史、语文教育名著选读、
 *                                   儿童文学研究、秘书实务、新闻采访与写作
 *   · 充足   余量 ≥ 5         13 门：其余
 *
 * 关于时间冲突：大四上课表已有 15-17 门/周（见 courses.js），补选时可用的空档只剩少数几个
 * （周一 3-4 / 5-6 / 10-11、周四 10-11、周五 1-2 / 5-6）。因此课程池里多数课程与已选课表
 * 时间重叠 —— 这正是选课页「冲突」角标要表达的信息，不是脏数据。
 * 教务「推荐课表打印」是教务自己算好并返回的另一张周课表，与本池无关。
 *
 * 本文件只导出常量，不含函数、不含颜色、不含外链。
 */

/**
 * 选课轮次（按时间升序，最后一个即当前轮次）。
 * @type {Array<import('../types.js').SelectionRound & {id: string}>}
 */
export const SELECTION_ROUNDS = [
  {
    id: 'round-1',
    label: '第一轮选课（专业选修）',
    openAt: '2026-10-19T08:00:00+08:00',
    closeAt: '2026-10-25T22:00:00+08:00',
    status: 'closed',
    // 本轮可选门数上限（演示数据自用，不在 SelectionRound 契约内）
    maxCourses: 4,
  },
  {
    id: 'round-2',
    label: '第二轮选课（通识选修补选）',
    openAt: '2026-11-02T08:00:00+08:00',
    closeAt: '2026-11-08T22:00:00+08:00',
    status: 'open',
    maxCourses: 3,
  },
];

/**
 * 选课页数据：当前轮次 + 可选课程池。
 * @type {import('../types.js').CourseSelection}
 */
export const COURSE_SELECTION = {
  semesterId: '2026-2027-1',
  round: SELECTION_ROUNDS[1],
  courses: [
    // ── 已满（余量 0）──────────────────────────────────────────────────────
    {
      id: '2026-1-0833001-01', code: '0833001', name: '诗经研究', teacher: '李慧敏',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '明德楼B203',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 2, sections: [5, 6],
      startTime: '14:00', endTime: '15:35', capacity: 40, enrolled: 40, isSelected: false,
    },
    {
      id: '2026-1-0833002-01', code: '0833002', name: '唐宋八大家散文研究', teacher: '秦淑芬',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '博学楼A303',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 1, sections: [5, 6],
      startTime: '14:00', endTime: '15:35', capacity: 40, enrolled: 40, isSelected: false,
    },
    {
      id: '2026-1-0833022-01', code: '0833022', name: '中学语文名篇解读', teacher: '张文娟',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '敏行楼C305',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 5, sections: [1, 2],
      startTime: '08:00', endTime: '09:35', capacity: 48, enrolled: 48, isSelected: false,
    },

    // ── 紧张（余量 1-4）────────────────────────────────────────────────────
    {
      id: '2026-1-0833003-01', code: '0833003', name: '汉语修辞学', teacher: '赵敏',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '博学楼A201',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 3, sections: [3, 4],
      startTime: '09:55', endTime: '11:30', capacity: 45, enrolled: 43, isSelected: false,
    },
    {
      id: '2026-1-0833004-01', code: '0833004', name: '中国文学批评史', teacher: '孙丽华',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '博学楼A405',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 3, sections: [5, 6],
      startTime: '14:00', endTime: '15:35', capacity: 45, enrolled: 42, isSelected: false,
    },
    {
      // 与已选「说课与评课训练」（周四 5-6 节，10-17 周）冲突 —— 选课页冲突角标样本
      id: '2026-1-0833005-01', code: '0833005', name: '语文教育名著选读', teacher: '张文娟',
      credits: 1.5, courseType: 'practice', assessment: 'check', campus: '主校区', location: '敏行楼C305',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 4, sections: [5, 6],
      startTime: '14:00', endTime: '15:35', capacity: 40, enrolled: 37, isSelected: false,
    },
    {
      id: '2026-1-0833006-01', code: '0833006', name: '儿童文学研究', teacher: '周雅静',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '敏行楼C201',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 4, sections: [7, 8],
      startTime: '15:55', endTime: '17:30', capacity: 50, enrolled: 47, isSelected: false,
    },
    {
      id: '2026-1-0833007-01', code: '0833007', name: '秘书实务', teacher: '郑文博',
      credits: 2, courseType: 'practice', assessment: 'check', campus: '主校区', location: '明德楼B208',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 5, sections: [1, 2],
      startTime: '08:00', endTime: '09:35', capacity: 50, enrolled: 46, isSelected: false,
    },
    {
      id: '2026-1-0833008-01', code: '0833008', name: '新闻采访与写作', teacher: '罗建军',
      credits: 2, courseType: 'practice', assessment: 'check', campus: '主校区', location: '博学楼A207',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 5, sections: [3, 4],
      startTime: '09:55', endTime: '11:30', capacity: 45, enrolled: 41, isSelected: false,
    },

    // ── 充足（余量 ≥ 5）────────────────────────────────────────────────────
    {
      id: '2026-1-0000701-01', code: '0000701', name: '影视鉴赏', teacher: '徐立群',
      credits: 1.5, courseType: 'public', assessment: 'check', campus: '主校区', location: '敏行楼C410',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 1, sections: [5, 6],
      startTime: '14:00', endTime: '15:35', capacity: 60, enrolled: 52, isSelected: false,
    },
    {
      id: '2026-1-0000702-01', code: '0000702', name: '中国书法史', teacher: '马世昌',
      credits: 2, courseType: 'public', assessment: 'check', campus: '主校区', location: '敏行楼C102',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 1, sections: [7, 8],
      startTime: '15:55', endTime: '17:30', capacity: 50, enrolled: 38, isSelected: false,
    },
    {
      id: '2026-1-0000703-01', code: '0000703', name: '应用文写作', teacher: '田玉梅',
      credits: 1.5, courseType: 'public', assessment: 'check', campus: '主校区', location: '明德楼B310',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 5, sections: [5, 6],
      startTime: '14:00', endTime: '15:35', capacity: 60, enrolled: 55, isSelected: false,
    },
    {
      id: '2026-1-0000704-01', code: '0000704', name: '国学经典导读', teacher: '陈立新',
      credits: 2, courseType: 'public', assessment: 'check', campus: '主校区', location: '博学楼A108',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 2, sections: [3, 4],
      startTime: '09:55', endTime: '11:30', capacity: 55, enrolled: 44, isSelected: false,
    },
    {
      id: '2026-1-0000705-01', code: '0000705', name: '中国文化概论', teacher: '高志远',
      credits: 2, courseType: 'public', assessment: 'check', campus: '主校区', location: '明德楼B401',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 3, sections: [1, 2],
      startTime: '08:00', endTime: '09:35', capacity: 80, enrolled: 71, isSelected: false,
    },
    {
      // 补选空档：周一 5-6 节（10-17 周空档）
      id: '2026-1-0833009-01', code: '0833009', name: '教育测量与评价', teacher: '吴桂芳',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '明德楼B302',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 1, sections: [5, 6],
      startTime: '14:00', endTime: '15:35', capacity: 50, enrolled: 40, isSelected: false,
    },
    {
      // 补选空档：周五 5-6 节（10-17 周空档）
      id: '2026-1-0000706-01', code: '0000706', name: '心理与健康', teacher: '刘海涛',
      credits: 1.5, courseType: 'public', assessment: 'check', campus: '主校区', location: '明德楼B105',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 5, sections: [5, 6],
      startTime: '14:00', endTime: '15:35', capacity: 70, enrolled: 60, isSelected: false,
    },
    {
      id: '2026-1-0833010-01', code: '0833010', name: '现代汉语词汇学', teacher: '赵敏',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '博学楼A201',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 4, sections: [3, 4],
      startTime: '09:55', endTime: '11:30', capacity: 45, enrolled: 33, isSelected: false,
    },
    {
      id: '2026-1-0000707-01', code: '0000707', name: '演讲与口才', teacher: '田玉梅',
      credits: 1.5, courseType: 'public', assessment: 'check', campus: '主校区', location: '敏行楼C102',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 4, sections: [10, 11],
      startTime: '19:00', endTime: '20:35', capacity: 60, enrolled: 51, isSelected: false,
    },
    {
      id: '2026-1-0833011-01', code: '0833011', name: '中国古代小说史', teacher: '秦淑芬',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '博学楼A303',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 5, sections: [7, 8],
      startTime: '15:55', endTime: '17:30', capacity: 50, enrolled: 36, isSelected: false,
    },
    {
      // 补选空档：周四 10-11 节（晚间空档）
      id: '2026-1-0833012-01', code: '0833012', name: '文学与图像', teacher: '孙丽华',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '博学楼A405',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 4, sections: [10, 11],
      startTime: '19:00', endTime: '20:35', capacity: 45, enrolled: 30, isSelected: false,
    },
    {
      // 补选空档：周一 3-4 节（10-17 周空档）；演示中标记为「我已选上」
      id: '2026-1-0833013-01', code: '0833013', name: '汉语方言学', teacher: '陈立新',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '博学楼A108',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 1, sections: [3, 4],
      startTime: '09:55', endTime: '11:30', capacity: 40, enrolled: 28, isSelected: true,
    },
    {
      // 补选空档：周一 10-11 节（晚间空档）
      id: '2026-1-0833014-01', code: '0833014', name: '教育社会学', teacher: '郑文博',
      credits: 2, courseType: 'elective', assessment: 'check', campus: '主校区', location: '明德楼B208',
      weeks: [11, 12, 13, 14, 15, 16, 17, 18, 19], dayOfWeek: 1, sections: [10, 11],
      startTime: '19:00', endTime: '20:35', capacity: 55, enrolled: 45, isSelected: false,
    },
  ],
};
