import Foundation

// MARK: - 课表数据模型
//
// 与 Android `data/Timetable.kt` 的 JSON 结构**逐字段一致**（schemaVersion 1），
// 这样两端的本地数据理论上可以互相导入，也方便对照排查。
//
// 关键设计（照抄安卓注释里的决策）：
//   · 一门课可有多个时段（地球化学周一/周三/周五），时段各自有教室
//   · Course.key 是聚合主键：教务课程用 jxb_id，手动添加用 custom_xxx
//   · source 区分来源，同步时 custom 课程永不删除
//   · adjusts（停课/调课/放假/补课）跟着学期走，同步教务时保留

struct Timetable: Codable, Equatable {
    var schemaVersion: Int = Timetable.schemaVersion
    var semester: Semester = Semester()
    var courses: [Course] = []
    var adjusts: [Adjust] = []

    static let schemaVersion = 1

    static let sourceJwglxt = "jwglxt"
    static let sourceCustom = "custom"
    static let sourceImport = "import"

    init() {}

    init(semester: Semester = Semester(), courses: [Course] = [], adjusts: [Adjust] = []) {
        self.semester = semester
        self.courses = courses
        self.adjusts = adjusts
    }

    // MARK: 便捷查询

    var isEmpty: Bool { courses.allSatisfy { $0.sessions.isEmpty } }

    func findByName(_ name: String) -> Course? {
        courses.first { $0.name == name }
    }

    /// 指定周、指定星期的所有课程
    func coursesOn(dayOfWeek: Int, week: Int) -> [Course] {
        courses.filter { c in
            c.sessions.contains { $0.dayOfWeek == dayOfWeek && $0.isActive(week: week) }
        }
    }

    /// 课表里用到的最大节次（没有课返回 0）。
    var maxSectionUsed: Int {
        var maxV = 0
        for c in courses {
            for s in c.sessions {
                maxV = max(maxV, max(s.startSection, s.endSection))
            }
        }
        return min(ScheduleSettings.maxSections, maxV)
    }

    /// 按安卓口径重新分配颜色（CourseColorAssigner.assign 的等价实现，见 ColorAssigner.swift）
    mutating func normalizeColors() {
        CourseColorAssigner.assign(&self)
    }
}

// MARK: - 学期

struct Semester: Codable, Equatable {
    /// 2026-2027-1
    var id: String = ""
    /// 2026-2027 学年第一学期
    var name: String = ""
    /// 2026-09-01（第 1 周周一），可为空
    var startDate: String = ""
    var totalWeeks: Int = 20
    /// 用户手动改过开学日期（有值时不再被校历覆盖）
    var startDateTouched: Bool = false
    /// 归档内显示顺序（用户拖动后写入）
    var sortOrder: Int = 0

    init() {}

    init(id: String, name: String, startDate: String, totalWeeks: Int) {
        self.id = id
        self.name = name
        self.startDate = startDate
        self.totalWeeks = totalWeeks
    }

    // 两端 JSON 可能缺字段（老数据），全部给默认值，解码时不失败
    enum CodingKeys: String, CodingKey {
        case id, name, startDate, totalWeeks, startDateTouched, sortOrder
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? ""
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        startDate = (try? c.decodeIfPresent(String.self, forKey: .startDate)) ?? ""
        totalWeeks = (try? c.decodeIfPresent(Int.self, forKey: .totalWeeks)) ?? 20
        startDateTouched = (try? c.decodeIfPresent(Bool.self, forKey: .startDateTouched)) ?? false
        sortOrder = (try? c.decodeIfPresent(Int.self, forKey: .sortOrder)) ?? 0
    }
}

// MARK: - 课程

struct Course: Codable, Equatable {
    /// 聚合主键（jxb_id 或 custom_xxx）
    var key: String = ""
    var name: String = ""
    var teacher: String = ""
    var credit: Double = 0
    /// 主修 / 辅修 …
    var category: String = ""
    /// 正常考试 / 考查 …
    var examType: String = ""
    /// 0-7，色板索引
    var colorIndex: Int = 0
    /// 用户在编辑器里手动选过颜色
    var colorTouched: Bool = false
    var source: String = Timetable.sourceJwglxt
    /// 用户备注
    var note: String = ""
    var sessions: [Session] = []

    init() {}

    enum CodingKeys: String, CodingKey {
        case key, name, teacher, credit, category, examType,
             colorIndex, colorTouched, source, note, sessions
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = (try? c.decodeIfPresent(String.self, forKey: .key)) ?? ""
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        teacher = (try? c.decodeIfPresent(String.self, forKey: .teacher)) ?? ""
        credit = (try? c.decodeIfPresent(Double.self, forKey: .credit)) ?? 0
        category = (try? c.decodeIfPresent(String.self, forKey: .category)) ?? ""
        examType = (try? c.decodeIfPresent(String.self, forKey: .examType)) ?? ""
        colorIndex = (try? c.decodeIfPresent(Int.self, forKey: .colorIndex)) ?? 0
        colorTouched = (try? c.decodeIfPresent(Bool.self, forKey: .colorTouched)) ?? false
        source = (try? c.decodeIfPresent(String.self, forKey: .source)) ?? Timetable.sourceJwglxt
        note = (try? c.decodeIfPresent(String.self, forKey: .note)) ?? ""
        sessions = (try? c.decodeIfPresent([Session].self, forKey: .sessions)) ?? []
    }

    /// 该课程在某周是否上课
    func isActive(week: Int) -> Bool {
        sessions.contains { $0.isActive(week: week) }
    }

    var isCustom: Bool { source == Timetable.sourceCustom }
}

// MARK: - 上课时段

struct Session: Codable, Equatable, Hashable {
    /// 1=周一 … 7=周日
    var dayOfWeek: Int = 1
    var startSection: Int = 1
    /// 结束节次（含）
    var endSection: Int = 1
    var weeks: [Int] = []
    /// 原始表达式，保留以便排查
    var weeksRaw: String = ""
    /// 教室（每个时段可能不同）
    var location: String = ""
    var campus: String = ""
    var roomType: String = ""
    /// 本时段教师；空串表示沿用课程教师
    var teacher: String = ""
    /// 按周换老师：周次 → 教师，优先于 teacher
    var weekTeachers: [String: String] = [:]
    /// 自定义起止时间 HH:mm（如考试 14:30–16:30）
    var timeStart: String = ""
    var timeEnd: String = ""

    init() {}

    enum CodingKeys: String, CodingKey {
        case dayOfWeek, startSection, endSection, weeks, weeksRaw,
             location, campus, roomType, teacher, weekTeachers, timeStart, timeEnd
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        dayOfWeek = (try? c.decodeIfPresent(Int.self, forKey: .dayOfWeek)) ?? 1
        startSection = (try? c.decodeIfPresent(Int.self, forKey: .startSection)) ?? 1
        endSection = (try? c.decodeIfPresent(Int.self, forKey: .endSection)) ?? startSection
        weeksRaw = (try? c.decodeIfPresent(String.self, forKey: .weeksRaw)) ?? ""
        let direct = (try? c.decodeIfPresent([Int].self, forKey: .weeks)) ?? []
        if !direct.isEmpty {
            weeks = direct
        } else if !weeksRaw.isEmpty {
            weeks = WeekRange.parse(weeksRaw).weeks
        }
        location = (try? c.decodeIfPresent(String.self, forKey: .location)) ?? ""
        campus = (try? c.decodeIfPresent(String.self, forKey: .campus)) ?? ""
        roomType = (try? c.decodeIfPresent(String.self, forKey: .roomType)) ?? ""
        teacher = (try? c.decodeIfPresent(String.self, forKey: .teacher)) ?? ""
        weekTeachers = (try? c.decodeIfPresent([String: String].self, forKey: .weekTeachers)) ?? [:]
        timeStart = (try? c.decodeIfPresent(String.self, forKey: .timeStart)) ?? ""
        timeEnd = (try? c.decodeIfPresent(String.self, forKey: .timeEnd)) ?? ""
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(dayOfWeek, forKey: .dayOfWeek)
        try c.encode(startSection, forKey: .startSection)
        try c.encode(endSection, forKey: .endSection)
        try c.encode(weeks, forKey: .weeks)
        try c.encode(weeksRaw, forKey: .weeksRaw)
        try c.encode(location, forKey: .location)
        try c.encode(campus, forKey: .campus)
        try c.encode(roomType, forKey: .roomType)
        if !teacher.isEmpty { try c.encode(teacher, forKey: .teacher) }
        if !weekTeachers.isEmpty { try c.encode(weekTeachers, forKey: .weekTeachers) }
        if hasCustomTime {
            try c.encode(timeStart, forKey: .timeStart)
            try c.encode(timeEnd, forKey: .timeEnd)
        }
    }

    /// 有合法的自定义时间（开始早于结束）。
    var hasCustomTime: Bool {
        let a = Session.minutesOf(timeStart)
        let b = Session.minutesOf(timeEnd)
        return a >= 0 && b > a
    }

    /// 这一周显示哪位老师：按周 > 本时段 > 课程。
    func teacherFor(week: Int, courseTeacher: String) -> String {
        if let w = weekTeachers[String(week)], !w.isEmpty { return w }
        if !teacher.isEmpty { return teacher }
        return courseTeacher
    }

    /// 该时段是否在某周上课
    func isActive(week: Int) -> Bool {
        if weeks.isEmpty { return true }   // 周次未解析出来时保底显示
        return weeks.contains(week)
    }

    // MARK: 时间工具

    /// "HH:mm" → 当天分钟数；格式不对返回 -1。
    static func minutesOf(_ hm: String?) -> Int {
        guard let hm else { return -1 }
        let s = hm.trimmingCharacters(in: .whitespaces)
        let parts = s.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, let h = Int(parts[0]), let m = Int(parts[1]) else { return -1 }
        guard h >= 0, h <= 23, m >= 0, m <= 59 else { return -1 }
        return h * 60 + m
    }

    static func formatHm(_ minutes: Int) -> String {
        String(format: "%02d:%02d", minutes / 60, minutes % 60)
    }
}

// MARK: - 单次调整
//
//   cancel  某周某天某节停一次课（start=0 表示那天这门课全停）
//   move    把某次课调到另一周/天/节（或自定义时间），原位置显示「已调走」
//   off     某天放假（date），当天课程置灰
//   makeup  某天补课（date），上 fromDate 那天的课

struct Adjust: Codable, Equatable {
    var id: String = ""
    var type: String = Adjust.cancel
    var name: String = ""
    var week: Int = 0
    var day: Int = 0
    var start: Int = 0
    var end: Int = 0
    var toWeek: Int = 0
    var toDay: Int = 0
    var toStart: Int = 0
    var toEnd: Int = 0
    var toTimeStart: String = ""
    var toTimeEnd: String = ""
    var toLocation: String = ""
    /// off/makeup：yyyy-MM-dd
    var date: String = ""
    /// makeup：上哪天的课
    var fromDate: String = ""
    var note: String = ""

    static let cancel = "cancel"
    static let move = "move"
    static let off = "off"
    static let makeup = "makeup"

    init() {}

    enum CodingKeys: String, CodingKey {
        case id, type, name, week, day, start, end, toWeek, toDay, toStart, toEnd,
             toTimeStart, toTimeEnd, toLocation, date, fromDate, note
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decodeIfPresent(String.self, forKey: .id)) ?? ""
        type = (try? c.decodeIfPresent(String.self, forKey: .type)) ?? Adjust.cancel
        name = (try? c.decodeIfPresent(String.self, forKey: .name)) ?? ""
        week = (try? c.decodeIfPresent(Int.self, forKey: .week)) ?? 0
        day = (try? c.decodeIfPresent(Int.self, forKey: .day)) ?? 0
        start = (try? c.decodeIfPresent(Int.self, forKey: .start)) ?? 0
        end = (try? c.decodeIfPresent(Int.self, forKey: .end)) ?? 0
        toWeek = (try? c.decodeIfPresent(Int.self, forKey: .toWeek)) ?? 0
        toDay = (try? c.decodeIfPresent(Int.self, forKey: .toDay)) ?? 0
        toStart = (try? c.decodeIfPresent(Int.self, forKey: .toStart)) ?? 0
        toEnd = (try? c.decodeIfPresent(Int.self, forKey: .toEnd)) ?? 0
        toTimeStart = (try? c.decodeIfPresent(String.self, forKey: .toTimeStart)) ?? ""
        toTimeEnd = (try? c.decodeIfPresent(String.self, forKey: .toTimeEnd)) ?? ""
        toLocation = (try? c.decodeIfPresent(String.self, forKey: .toLocation)) ?? ""
        date = (try? c.decodeIfPresent(String.self, forKey: .date)) ?? ""
        fromDate = (try? c.decodeIfPresent(String.self, forKey: .fromDate)) ?? ""
        note = (try? c.decodeIfPresent(String.self, forKey: .note)) ?? ""
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(type, forKey: .type)
        if !name.isEmpty { try c.encode(name, forKey: .name) }
        if week != 0 { try c.encode(week, forKey: .week) }
        if day != 0 { try c.encode(day, forKey: .day) }
        if start != 0 { try c.encode(start, forKey: .start) }
        if end != 0 { try c.encode(end, forKey: .end) }
        if toWeek != 0 { try c.encode(toWeek, forKey: .toWeek) }
        if toDay != 0 { try c.encode(toDay, forKey: .toDay) }
        if toStart != 0 { try c.encode(toStart, forKey: .toStart) }
        if toEnd != 0 { try c.encode(toEnd, forKey: .toEnd) }
        if !toTimeStart.isEmpty { try c.encode(toTimeStart, forKey: .toTimeStart) }
        if !toTimeEnd.isEmpty { try c.encode(toTimeEnd, forKey: .toTimeEnd) }
        if !toLocation.isEmpty { try c.encode(toLocation, forKey: .toLocation) }
        if !date.isEmpty { try c.encode(date, forKey: .date) }
        if !fromDate.isEmpty { try c.encode(fromDate, forKey: .fromDate) }
        if !note.isEmpty { try c.encode(note, forKey: .note) }
    }

    var hasToTime: Bool {
        let a = Session.minutesOf(toTimeStart)
        let b = Session.minutesOf(toTimeEnd)
        return a >= 0 && b > a
    }

    /// 是否同一件事（去重用，忽略 id/note）。
    func sameAs(_ o: Adjust) -> Bool {
        type == o.type && name == o.name && week == o.week && day == o.day
            && start == o.start && end == o.end && toWeek == o.toWeek && toDay == o.toDay
            && toStart == o.toStart && toEnd == o.toEnd && toTimeStart == o.toTimeStart
            && toTimeEnd == o.toTimeEnd && date == o.date && fromDate == o.fromDate
    }
}