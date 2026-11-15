/**
 * demo/recommended.js —— 演示数据 · 推荐课表（纯数据，零逻辑）
 *
 * 【口径更正，勿再改回「推荐方案」】
 * 教务系统「我的应用 → 推荐课表打印」给出的**就是一张周课表**，页面形态与
 * 「信息查询 → 个人课表查询」几乎一样。它不是几套可选方案，也没有匹配度、
 * 没有推荐算法、没有「一键参照选课」。App 只是把它抓回来、映射成 Course[]、
 * 画成自己的网格。
 *
 * 因此本文件导出 `RECOMMENDED_TIMETABLE`，形状与 `demoProvider.getTimetable()`
 * 的返回值同构（semesterId / weeks / courses），可直接交给 `buildGrid`。
 *
 * 与 COURSES（个人课表）的区别：这里是教务按培养方案建议修读、学生**尚未选**的课。
 * 刻意排布：6 门贯穿全学期（第 10 周可见）、3 门 11-19 周（第 10 周不出现，
 * 用来验证「只渲染本周命中课程」这条规则）、1 门单周课（weeks 是任意集合，不是范围）。
 *
 * 本文件只导出常量，不含函数、不含颜色、不含外链。
 */

const FULL = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17];
const LATE = [11, 12, 13, 14, 15, 16, 17, 18, 19];
const ODD = [1, 3, 5, 7, 9, 11, 13, 15, 17];

/**
 * 教务推荐课表（演示快照）。
 * @type {import('../types.js').RecommendedTimetable}
 */
export const RECOMMENDED_TIMETABLE = {
  semesterId: '2026-2027-1',
  weeks: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19],
  sourceNote: '数据来自教务系统「我的应用 → 推荐课表打印」，仅供参考，实际修读以自主选课结果为准',
  fetchedAt: '2026-11-05T16:36:27.000Z',
  courses: [
    {
      id: '2026-1-0833009-01', code: '0833009', name: '教育测量与评价', teacher: '孙立平',
      credits: 2, hours: 32, courseType: 'elective', assessment: 'check',
      campus: '大学道北校区', location: '明德楼B207', classNo: '01', capacity: 60, enrolled: 41,
      weeks: FULL, dayOfWeek: 1, sections: [1, 2], startTime: '08:00', endTime: '09:35', isSelected: false,
    },
    {
      id: '2026-1-0000703-01', code: '0000703', name: '应用文写作', teacher: '周敏',
      credits: 1.5, hours: 24, courseType: 'public', assessment: 'check',
      campus: '大学道北校区', location: '博学楼A105', classNo: '01', capacity: 80, enrolled: 66,
      weeks: FULL, dayOfWeek: 1, sections: [5, 6], startTime: '14:00', endTime: '15:35', isSelected: false,
    },
    {
      id: '2026-1-0833013-01', code: '0833013', name: '汉语方言学', teacher: '赵敏',
      credits: 2, hours: 32, courseType: 'elective', assessment: 'exam',
      campus: '大学道北校区', location: '博学楼A201', classNo: '01', capacity: 45, enrolled: 30,
      weeks: FULL, dayOfWeek: 2, sections: [3, 4], startTime: '09:55', endTime: '11:30', isSelected: false,
    },
    {
      id: '2026-1-0832058-01', code: '0832058', name: '教师书写技能训练', teacher: '李慧敏',
      credits: 1, hours: 16, courseType: 'practice', assessment: 'check',
      campus: '大学道北校区', location: '敏行楼C108', classNo: '01', capacity: 40, enrolled: 28,
      weeks: ODD, dayOfWeek: 2, sections: [10, 11], startTime: '19:00', endTime: '20:35', isSelected: false,
    },
    {
      id: '2026-1-0832061-01', code: '0832061', name: '中学语文教材研究', teacher: '张文娟',
      credits: 2, hours: 32, courseType: 'elective', assessment: 'exam',
      campus: '大学道北校区', location: '敏行楼C305', classNo: '01', capacity: 50, enrolled: 33,
      weeks: LATE, dayOfWeek: 3, sections: [1, 2], startTime: '08:00', endTime: '09:35', isSelected: false,
    },
    {
      id: '2026-1-0833007-01', code: '0833007', name: '秘书实务', teacher: '秦淑芬',
      credits: 2, hours: 32, courseType: 'elective', assessment: 'check',
      campus: '大学道北校区', location: '明德楼B303', classNo: '01', capacity: 55, enrolled: 38,
      weeks: FULL, dayOfWeek: 3, sections: [7, 8], startTime: '15:55', endTime: '17:30', isSelected: false,
    },
    {
      id: '2026-1-0833005-01', code: '0833005', name: '语文教育名著选读', teacher: '吴桂芳',
      credits: 1.5, hours: 24, courseType: 'elective', assessment: 'check',
      campus: '大学道北校区', location: '明德楼B302', classNo: '01', capacity: 45, enrolled: 29,
      weeks: LATE, dayOfWeek: 4, sections: [3, 4], startTime: '09:55', endTime: '11:30', isSelected: false,
    },
    {
      id: '2026-1-0833014-01', code: '0833014', name: '教育社会学', teacher: '孙立平',
      credits: 2, hours: 32, courseType: 'elective', assessment: 'exam',
      campus: '大学道北校区', location: '明德楼B207', classNo: '01', capacity: 60, enrolled: 44,
      weeks: FULL, dayOfWeek: 4, sections: [10, 11], startTime: '19:00', endTime: '20:35', isSelected: false,
    },
    {
      id: '2026-1-0000706-01', code: '0000706', name: '心理与健康', teacher: '陈静',
      credits: 1.5, hours: 24, courseType: 'public', assessment: 'check',
      campus: '大学道北校区', location: '博学楼A105', classNo: '01', capacity: 90, enrolled: 72,
      weeks: FULL, dayOfWeek: 5, sections: [1, 2], startTime: '08:00', endTime: '09:35', isSelected: false,
    },
    {
      id: '2026-1-0833012-01', code: '0833012', name: '文学与图像', teacher: '周雅静',
      credits: 2, hours: 32, courseType: 'elective', assessment: 'check',
      campus: '大学道北校区', location: '敏行楼C201', classNo: '01', capacity: 50, enrolled: 31,
      weeks: LATE, dayOfWeek: 5, sections: [5, 6], startTime: '14:00', endTime: '15:35', isSelected: false,
    },
  ],
};
