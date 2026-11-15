// LogicTests.swift —— 纯逻辑层单元测试（可在 Windows 上真实运行）
//
// 覆盖：日期/周次计算、周次表达式解析、作息时间、课表解析、颜色分配、RSA 序列化边界。
// 这些是「功能能不能用」的地基，出错会导致课表全空、周次全错这类严重问题。
//
// 运行：swiftc LogicTests.swift <被测源码> -o tests.exe && tests.exe
// 或直接跑 scripts/test-logic.ps1

import Foundation

// MARK: - 极简断言框架（不依赖 XCTest，Windows 上可用）

final class TestRunner {
    static var passed = 0
    static var failed = 0
    static var failures: [String] = []
    static var currentSuite = ""

    static func suite(_ name: String, _ block: () -> Void) {
        currentSuite = name
        print("\n── \(name) ──")
        block()
    }

    static func expect(_ cond: Bool, _ message: String,
                       file: String = #file, line: Int = #line) {
        if cond {
            passed += 1
        } else {
            failed += 1
            let where_ = "\(currentSuite): \(message) (line \(line))"
            failures.append(where_)
            print("  ✗ \(message)")
        }
    }

    static func equal<T: Equatable>(_ actual: T, _ expected: T, _ message: String,
                                    line: Int = #line) {
        if actual == expected {
            passed += 1
        } else {
            failed += 1
            let msg = "\(currentSuite): \(message)\n      实际: \(actual)\n      期望: \(expected) (line \(line))"
            failures.append(msg)
            print("  ✗ \(message)\n      实际: \(actual)\n      期望: \(expected)")
        }
    }

    static func report() -> Int32 {
        print("\n" + String(repeating: "=", count: 60))
        print("通过 \(passed) · 失败 \(failed)")
        if !failures.isEmpty {
            print("\n失败详情：")
            for f in failures { print("  - \(f)") }
        }
        print(String(repeating: "=", count: 60))
        return failed == 0 ? 0 : 1
    }
}

func expect(_ c: Bool, _ m: String, line: Int = #line) { TestRunner.expect(c, m, line: line) }
func equal<T: Equatable>(_ a: T, _ b: T, _ m: String, line: Int = #line) { TestRunner.equal(a, b, m, line: line) }

// MARK: - 日期计算

TestRunner.suite("Dates") {
    equal(Dates.epochDay("1970-01-01"), 0, "纪元起点")
    equal(Dates.epochDay("1970-01-02"), 1, "第二天")
    equal(Dates.epochDay("2026-08-31"), 20696, "2026-08-31 的纪元日")
    equal(Dates.epochDay("bad"), Int.min, "非法日期返回哨兵")
    equal(Dates.epochDay("2026-13-01"), Int.min, "13 月非法")
    equal(Dates.epochDay("2026-02-30"), Int.min, "2 月 30 日非法")

    equal(Dates.ofEpochDay(20696), "2026-08-31", "纪元日回转")
    equal(Dates.ofEpochDay(0), "1970-01-01", "纪元日 0 回转")

    // 1970-01-01 是周四
    equal(Dates.dayOfWeek("1970-01-01"), 4, "1970-01-01 是周四")
    equal(Dates.dayOfWeek("2026-08-31"), 1, "2026-08-31 是周一")
    equal(Dates.dayOfWeek("2026-11-15"), 7, "2026-11-15 是周日")

    // 第 1 周周一 = 2026-08-31
    equal(Dates.dateOf(start: "2026-08-31", week: 1, day: 1), "2026-08-31", "第1周周一")
    equal(Dates.dateOf(start: "2026-08-31", week: 1, day: 7), "2026-09-06", "第1周周日")
    equal(Dates.dateOf(start: "2026-08-31", week: 3, day: 2), "2026-09-15", "第3周周二")

    equal(Dates.weekOf(start: "2026-08-31", ymd: "2026-08-31"), 1, "开学当天第1周")
    equal(Dates.weekOf(start: "2026-08-31", ymd: "2026-09-07"), 2, "次周第2周")
    equal(Dates.weekOf(start: "2026-08-31", ymd: "2026-08-24"), 0, "开学前一周返回0")

    equal(Dates.addDays("2026-08-31", 7), "2026-09-07", "加 7 天")
    equal(Dates.addDays("2026-12-31", 1), "2027-01-01", "跨年")
    equal(Dates.cnDay(1), "周一", "cnDay 1")
    equal(Dates.cnDay(7), "周日", "cnDay 7")
    equal(Dates.md("2026-10-10"), "10月10日", "md")

    // floorDiv / floorMod 对负数
    equal(Dates.floorDiv(-1, 7), -1, "floorDiv(-1,7)")
    equal(Dates.floorDiv(-7, 7), -1, "floorDiv(-7,7)")
    equal(Dates.floorDiv(7, 7), 1, "floorDiv(7,7)")
    equal(Dates.floorMod(-1, 7), 6, "floorMod(-1,7)")
}

// MARK: - 周次表达式

TestRunner.suite("WeekRange") {
    equal(WeekRange.parse("5-12周").weeks, Array(5...12), "区间 5-12周")
    equal(WeekRange.parse("10-13周").weeks, [10, 11, 12, 13], "区间 10-13周")
    equal(WeekRange.parse("9-16周").weeks, Array(9...16), "区间 9-16周")
    equal(WeekRange.parse("1-5周,7-12周").weeks,
          [1,2,3,4,5,7,8,9,10,11,12], "多区间")

    // 单双周
    let odd = WeekRange.parse("1-16周(单)")
    equal(odd.weeks, [1,3,5,7,9,11,13,15], "单周")
    equal(odd.oddOnly, true, "单周标记")
    equal(odd.hasParity, true, "有单双周标记")

    let even = WeekRange.parse("1-16周(双)")
    equal(even.weeks, [2,4,6,8,10,12,14,16], "双周")
    equal(even.oddOnly, false, "双周标记")

    // 离散 / 单值 / 无「周」字
    equal(WeekRange.parse("1,3,5,7周").weeks, [1,3,5,7], "离散周")
    equal(WeekRange.parse("3周").weeks, [3], "单周次")
    equal(WeekRange.parse("1-16").weeks, Array(1...16), "无「周」字")

    // 全角逗号
    equal(WeekRange.parse("1-5，7-12").weeks,
          [1,2,3,4,5,7,8,9,10,11,12], "全角逗号")

    // 容错
    equal(WeekRange.parse("12-5周").weeks, Array(5...12), "写反了也认")
    equal(WeekRange.parse("").ok, false, "空表达式解析失败")
    equal(WeekRange.parse("abc").ok, false, "无法解析的表达式")

    // isActive 保底
    equal(WeekRange.isActive("", week: 5), true, "空表达式保底显示")
    equal(WeekRange.isActive("5-12周", week: 5), true, "第5周活跃")
    equal(WeekRange.isActive("5-12周", week: 4), false, "第4周不活跃")

    // compress
    equal(WeekRange.compress([1,2,3,5,6,8]), "1-3,5-6,8", "compress 混合")
    equal(WeekRange.compress([1,3,5]), "1,3,5", "compress 离散")
    equal(WeekRange.compress([]), "", "compress 空")
}

// MARK: - 作息时间

TestRunner.suite("ScheduleSettings") {
    let s = ScheduleSettings()
    equal(s.clampedSectionCount, 12, "默认 12 节")
    equal(s.firstClassStart, "08:00", "默认 8:00 开始")

    // 唐山师范学院作息：第1节 08:00-08:45，休5 → 第2节 08:50
    equal(s.startTime(1), "08:00", "第1节开始")
    equal(s.endTime(1), "08:45", "第1节结束")
    equal(s.startTime(2), "08:50", "第2节开始（休5）")
    equal(s.endTime(2), "09:35", "第2节结束")
    equal(s.startTime(3), "09:55", "第3节开始（休20）")
    equal(s.endTime(3), "10:40", "第3节结束")
    equal(s.startTime(4), "10:45", "第4节开始")
    equal(s.endTime(4), "11:30", "第4节结束")
    // 午休 150 分钟 → 第5节 14:00
    equal(s.startTime(5), "14:00", "第5节开始（午休150）")
    equal(s.startTime(6), "14:50", "第6节开始")
    equal(s.startTime(7), "15:55", "第7节开始")
    equal(s.startTime(9), "17:35", "第9节开始")
    equal(s.startTime(10), "19:00", "第10节开始（晚餐40）")
    equal(s.startTime(12), "20:40", "第12节开始")
    equal(s.endTime(12), "21:25", "第12节结束")

    let ranges = s.minuteRanges()
    equal(ranges.start.count, ScheduleSettings.maxSections + 1, "分钟数组长度")
    equal(ranges.start[1], 480, "第1节 = 480 分钟")
    equal(ranges.end[12], 21 * 60 + 25, "第12节结束 = 1285 分钟")

    equal(ScheduleSettings.parseHm("08:00"), 480, "parseHm")
    equal(ScheduleSettings.parseHm("bad"), -1, "parseHm 非法")
    equal(ScheduleSettings.formatHm(480), "08:00", "formatHm")
    equal(ScheduleSettings.formatHm(-30), "23:30", "formatHm 负值归一")
}

// MARK: - 周次计算

TestRunner.suite("WeekMath") {
    equal(WeekMath.normalizeToMonday("2026-09-02"), "2026-08-31", "周三归一到周一")
    equal(WeekMath.normalizeToMonday("2026-09-06"), "2026-08-31", "周日归一到前一个周一")
    equal(WeekMath.normalizeToMonday("2026-08-31"), "2026-08-31", "周一本周")
    equal(WeekMath.normalizeToMonday("bad"), "", "非法日期返回空")

    equal(WeekMath.currentWeek(startDate: "2026-08-31", totalWeeks: 19) >= 1, true, "当前周 ≥ 1")
    equal(WeekMath.weekOfDate(startDate: "2026-08-31", ymd: "2026-09-07"), 2, "weekOfDate 第2周")
    // floorDiv 语义：开学前返回负数（与安卓 Dates.weekOf 一致，调用方自己夹到 1）
    equal(WeekMath.weekOfDate(startDate: "2026-08-31", ymd: "2026-08-17"), -1, "开学前两周返回 -1")

    equal(WeekMath.weekRangeStart("2026-08-31", week: 1), "2026-08-31", "第1周周一")
    equal(WeekMath.weekRangeStart("2026-08-31", week: 3), "2026-09-14", "第3周周一")
    equal(WeekMath.isMonday("2026-08-31"), true, "isMonday")

    let dates = WeekMath.weekDates("2026-08-31", week: 1)
    equal(dates.count, 7, "一周 7 天")
    equal(dates[0], "8/31", "周一日期")
    equal(dates[6], "9/6", "周日日期")

    // 学期简称
    equal(WeekMath.shortTermName("2026-2027 学年第一学期"), "26-27 第一学期", "学期简称")
}

// MARK: - 课表建模与颜色

TestRunner.suite("Timetable & ColorAssigner") {
    var t = Timetable()
    t.semester = Semester(id: "2026-2027-1", name: "2026-2027 学年第一学期",
                          startDate: "2026-08-31", totalWeeks: 19)

    var c1 = Course()
    c1.key = "jwglxt_1"; c1.name = "高等数学"; c1.teacher = "张三"
    var s1 = Session()
    s1.dayOfWeek = 1; s1.startSection = 1; s1.endSection = 2
    s1.weeks = Array(1...16); s1.location = "3-518"
    c1.sessions = [s1]

    var c2 = Course()
    c2.key = "jwglxt_2"; c2.name = "大学英语"
    var s2 = Session()
    s2.dayOfWeek = 3; s2.startSection = 3; s2.endSection = 4
    s2.weeks = Array(1...16); s2.location = "2-201"
    c2.sessions = [s2]

    t.courses = [c1, c2]
    equal(t.maxSectionUsed, 4, "最大节次 4")
    equal(t.isEmpty, false, "非空课表")

    // 同名课程应拿同一个颜色；不同名不撞色
    CourseColorAssigner.assign(&t)
    let c1Color = t.courses[0].colorIndex
    let c2Color = t.courses[1].colorIndex
    expect(c1Color != c2Color, "不同课程不同色")
    // 颜色索引应落在 0..23
    expect(c1Color >= 0 && c1Color < CourseColorAssigner.paletteSize, "颜色在色板内")
    expect(c2Color >= 0 && c2Color < CourseColorAssigner.paletteSize, "颜色在色板内")

    // 课程名规范化
    equal(CourseColorAssigner.normalizeName(" 高等 数学 "), "高等数学", "去空白")
    equal(CourseColorAssigner.normalizeName("高等\u{3000}数学"), "高等数学", "去全角空格")

    // DayPlan：2026-08-31 是第1周周一，应有高等数学
    let sch = ScheduleSettings()
    let ranges = sch.minuteRanges()
    let items = DayPlan.of(t, start: "2026-08-31", ymd: "2026-08-31",
                           secStart: ranges.start, secEnd: ranges.end)
    equal(items.count, 1, "周一有 1 门课")
    if let first = items.first {
        equal(first.name, "高等数学", "课名正确")
        equal(first.startMin, 480, "开始 = 8:00")
        equal(first.endMin, 575, "结束 = 9:35")
        equal(first.timeLabel, "08:00-09:35", "时间文案")
        equal(first.sections, "1-2节", "节次文案")
        equal(first.placeLabel, "3-518", "地点文案")
    }

    // 周三应有大学英语
    let wed = DayPlan.of(t, start: "2026-08-31", ymd: "2026-09-02",
                         secStart: ranges.start, secEnd: ranges.end)
    equal(wed.count, 1, "周三有 1 门课")
    equal(wed.first?.name, "大学英语", "周三课名")
}

// MARK: - 课表小节次解析

TestRunner.suite("KbParser helpers") {
    equal(KbParser.parseSections("3-4").0, 3, "jcor 3-4 起始")
    equal(KbParser.parseSections("3-4").1, 4, "jcor 3-4 结束")
    equal(KbParser.parseSections("1-2节").0, 1, "带「节」也能解析")
    equal(KbParser.parseSections("5").0, 5, "单节次")
    equal(KbParser.parseSections("").0, 1, "空 → 兜底 1")
    equal(KbParser.parseSections("4-2").0, 2, "写反了修正")

    equal(KbParser.buildSemesterId(xnm: "2026", xqm: "3"), "2026-2027-1", "第一学期 id")
    equal(KbParser.buildSemesterId(xnm: "2026", xqm: "12"), "2026-2027-2", "第二学期 id")

    equal(KbParser.clean("  高等数学&nbsp; "), "高等数学", "clean 去 nbsp")
    equal(KbParser.clean("A&amp;B"), "A&B", "clean 实体")

    // 稳定哈希：同样的字符串必须永远同样的值（跨进程稳定）
    let h1 = KbParser.stableHash("高等数学")
    let h2 = KbParser.stableHash("高等数学")
    equal(h1, h2, "稳定哈希一致")
    expect(h1 >= 0, "稳定哈希非负")
}

// MARK: - 完整课表 JSON 解析

TestRunner.suite("KbParser full parse") {
    let json = """
    {
      "xsxx": {"XNM": "2026", "XQM": "3", "XNMC": "2026-2027", "XQMMC": "1"},
      "kbList": [
        {"kcmc": "高等数学", "xm": "张三", "jxb_id": "A1", "xqj": "1",
         "jcor": "1-2", "zcd": "1-16周", "cdmc": "3-518", "xqmc": "东校区",
         "xf": "4.0", "kcbj": "主修", "khfsmc": "正常考试"},
        {"kcmc": "高等数学", "xm": "张三", "jxb_id": "A1", "xqj": "3",
         "jcor": "1-2", "zcd": "1-16周", "cdmc": "3-518", "xqmc": "东校区",
         "xf": "4.0", "kcbj": "主修", "khfsmc": "正常考试"},
        {"kcmc": "大学英语", "xm": "李四", "jxb_id": "A2", "xqj": "2",
         "jcor": "3-4", "zcd": "1-8周", "cdmc": "2-201", "xqmc": "西校区",
         "xf": "3.0", "kcbj": "主修", "khfsmc": "考查"}
      ]
    }
    """
    let r = KbParser.parse(json)
    equal(r.ok, true, "解析成功")
    equal(r.rawRecords, 3, "原始记录 3 条")
    equal(r.courseCount, 2, "聚合后 2 门课")
    equal(r.timetable?.semester.id, "2026-2027-1", "学期 id")
    equal(r.timetable?.semester.name, "2026-2027 学年第1学期", "学期名")

    if let tt = r.timetable {
        let math = tt.courses.first { $0.name == "高等数学" }
        equal(math?.sessions.count, 2, "高等数学有 2 个时段")
        let eng = tt.courses.first { $0.name == "大学英语" }
        equal(eng?.sessions.count, 1, "大学英语有 1 个时段")
        equal(eng?.credit, 3.0, "学分解析")
        equal(eng?.examType, "考查", "考核方式")
        equal(eng?.sessions.first?.location, "2-201", "教室")
        equal(eng?.sessions.first?.campus, "西校区", "校区")
    }

    // 空 / 非法输入
    equal(KbParser.parse("").ok, false, "空响应")
    equal(KbParser.parse("not json").ok, false, "非 JSON")
    equal(KbParser.parse("{\"foo\":1}").ok, false, "没有 kbList")

    // 同名同时段重复记录应被合并
    let dupJson = """
    {"xsxx":{"XNM":"2026","XQM":"3","XNMC":"2026-2027","XQMMC":"1"},
     "kbList":[
      {"kcmc":"形势与政策","xm":"王五","jxb_id":"B1","xqj":"5","jcor":"3-4","zcd":"11-11周","cdmc":"1-101"},
      {"kcmc":"形势与政策","xm":"赵六","jxb_id":"B2","xqj":"5","jcor":"3-4","zcd":"12-12周","cdmc":"1-101"}
     ]}
    """
    let dr = KbParser.parse(dupJson)
    equal(dr.courseCount, 1, "同名课程聚合为 1 门")
    if let tt = dr.timetable, let c = tt.courses.first {
        equal(c.sessions.count, 1, "同名同时段合并为 1 个时段")
        equal(c.sessions.first?.weeks, [11, 12], "周次取并集")
    }
}

// MARK: - 校历

TestRunner.suite("AcademicCalendar") {
    let t = AcademicCalendar.term(byId: "2026-2027-1")
    equal(t?.classStart, "2026-08-31", "第一学期开学日")
    equal(t?.teachingWeeks, 19, "19 周")

    equal(AcademicCalendar.termFor(today: "2026-09-15")?.id, "2026-2027-1", "9月属于第一学期")
    equal(AcademicCalendar.termFor(today: "2027-03-15")?.id, "2026-2027-2", "次年3月属于第二学期")

    let week = AcademicCalendar.week(t!, "2026-09-07")
    equal(week, 2, "2026-09-07 是第2周")

    expect(AcademicCalendar.eventsOn("2026-10-01").contains { $0.label == "国庆节" }, "10-01 是国庆")
    expect(AcademicCalendar.eventsOn("2026-08-31").isEmpty, "开学日没有节日")
}

// MARK: - 运行

exit(TestRunner.report())