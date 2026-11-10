import Foundation

/// 课表数据模型 —— 直接沿用 Android 端 `docs/DATA-SCHEMA.md` 的 JSON 结构
/// （Semester / Course / Session），字段名一模一样。
///
/// 这么做的好处：以后要做「双端数据互导」时，Android 导出的 timetable.json
/// 能被 iOS 直接 `JSONDecoder` 解出来，不用写第二套映射。
struct Semester: Codable, Equatable {
    var id: String = ""
    var name: String = ""
    var startDate: String = ""
    var totalWeeks: Int = 20
    var startDateTouched: Bool = false
    var sortOrder: Int = 0

    init() {}

    init(id: String, name: String, startDate: String, totalWeeks: Int) {
        self.id = id
        self.name = name
        self.startDate = startDate
        self.totalWeeks = totalWeeks
    }
}

struct Session: Codable, Equatable {
    var dayOfWeek: Int = 1          // 1=周一 … 7=周日
    var startSection: Int = 1
    var endSection: Int = 1
    var weeks: [Int] = []
    var weeksRaw: String = ""
    var location: String = ""
    var campus: String = ""
    var roomType: String = ""
    var teacher: String = ""
    /// 周次 → 教师（按周换老师的课）。Android 侧是 `Map<Integer,String>`，
    /// JSON 里键是字符串；Swift 若用 [Int:String] 会编码成数组，这里保持 [String:String]。
    var weekTeachers: [String: String] = [:]
    var timeStart: String = ""
    var timeEnd: String = ""

    init() {}

    /// 有合法的自定义时间（开始早于结束），如考试 14:30–16:30
    var hasCustomTime: Bool {
        let a = Session.minutes(timeStart), b = Session.minutes(timeEnd)
        return a >= 0 && b > a
    }

    /// 这一周显示哪位老师：按周 > 本时段 > 课程
    func teacher(for week: Int, courseTeacher: String) -> String {
        if let w = weekTeachers["\(week)"], !w.isEmpty { return w }
        if !teacher.isEmpty { return teacher }
        return courseTeacher
    }

    func isActive(_ week: Int) -> Bool {
        if weeks.isEmpty { return true }   // 周次没解析出来时保底显示
        return weeks.contains(week)
    }

    static func minutes(_ hm: String) -> Int {
        let s = hm.trimmingCharacters(in: .whitespaces)
        let parts = s.split(separator: ":")
        guard parts.count == 2,
              let h = Int(parts[0]), let m = Int(parts[1]),
              (0...23).contains(h), (0...59).contains(m) else { return -1 }
        return h * 60 + m
    }

    static func format(_ minutes: Int) -> String {
        String(format: "%02d:%02d", minutes / 60, minutes % 60)
    }
}

struct Course: Codable, Equatable, Identifiable {
    var key: String = ""
    var name: String = ""
    var teacher: String = ""
    var credit: Double = 0
    var category: String = ""
    var examType: String = ""
    var colorIndex: Int = 0
    var colorTouched: Bool = false
    var source: String = "jwglxt"
    var note: String = ""
    var sessions: [Session] = []

    var id: String { key }

    init() {}

    func isActive(_ week: Int) -> Bool {
        sessions.contains { $0.isActive(week) }
    }

    /// 「周一 3-4节」这种紧凑写法，课程详情与首页复用
    func scheduleSummary() -> String {
        let dayNames = ["一", "二", "三", "四", "五", "六", "日"]
        return sessions.map { s in
            let d = dayNames[max(0, min(6, s.dayOfWeek - 1))]
            let sec = s.startSection == s.endSection
                ? "第\(s.startSection)节"
                : "\(s.startSection)-\(s.endSection)节"
            return "周\(d) \(sec)"
        }.joined(separator: "、")
    }
}

struct Timetable: Codable, Equatable {
    var schemaVersion: Int = 1
    var semester: Semester = Semester()
    var courses: [Course] = []

    init() {}

    var isEmpty: Bool { courses.isEmpty }

    func courses(on dayOfWeek: Int, week: Int) -> [Course] {
        courses.filter { c in
            c.sessions.contains { $0.dayOfWeek == dayOfWeek && $0.isActive(week) }
        }
    }

    /// 色板统一：同一课程名拿同一个颜色，不同课名尽量不撞色。
    /// 对应 Android `CourseColorAssigner.assign`（这里只做「同色不撞」这一层，
    /// 同步顺序变化时不会整体换色）。
    mutating func normalizeColors() {
        var assigned: [String: Int] = [:]
        var used = [Bool](repeating: false, count: 8)
        let auto = courses.enumerated().filter { !$0.element.colorTouched }
        for (_, c) in auto {
            let name = c.name.trimmingCharacters(in: .whitespaces)
            if assigned[name] != nil { continue }
            let idx = abs(c.colorIndex) % 8
            if !used[idx] {
                used[idx] = true
                assigned[name] = idx
            }
        }
        for i in courses.indices {
            let name = courses[i].name.trimmingCharacters(in: .whitespaces)
            if courses[i].colorTouched { continue }
            if let idx = assigned[name] {
                courses[i].colorIndex = idx
            } else {
                let free = used.firstIndex(of: false) ?? (abs(courses[i].colorIndex) % 8)
                used[free] = true
                assigned[name] = free
                courses[i].colorIndex = free
            }
        }
    }
}
