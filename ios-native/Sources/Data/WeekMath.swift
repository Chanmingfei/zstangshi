import Foundation

/// 教学周与日期计算（Android `WeekMath` 的等价实现）。
///
/// 与 Android 的唯一区别：那边用 `Calendar`（可变对象 + SimpleDateFormat），
/// 这边用 `Calendar`（值类型）+ 缓存好的 `DateFormatter`。
/// 所有「周」都以**周一**为起点，开学日期传进来先归一到那一周的周一。
enum WeekMath {

    /// 不设学期总周数时，周次最多排到这里（覆盖整学期 + 假期）
    static let openWeeksMax = 99
    /// 可翻页的最少周数，保证刚开学也能往后翻
    static let openWeeksMin = 30

    static var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.locale = Locale(identifier: "zh_CN")
        c.timeZone = .current
        c.firstWeekday = 2           // 周一
        return c
    }()

    private static let ymdFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private static let mdFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        f.dateFormat = "M/d"
        return f
    }()

    static func today() -> String { ymdFormatter.string(from: Date()) }

    static func ymd(_ date: Date) -> String { ymdFormatter.string(from: date) }

    static func parse(_ text: String) -> Date? {
        let s = text.trimmingCharacters(in: .whitespaces)
        guard !s.isEmpty else { return nil }
        return ymdFormatter.date(from: s)
    }

    static func isValid(_ text: String) -> Bool { parse(text) != nil }

    /// 归一到所在周的周一；解析失败返回空串
    static func monday(of date: String) -> String {
        guard let d = parse(date) else { return "" }
        let weekday = calendar.component(.weekday, from: d)   // 1=周日 … 7=周六
        let offset = weekday == 1 ? -6 : 2 - weekday          // 到周一的偏移
        guard let m = calendar.date(byAdding: .day, value: offset, to: d) else { return "" }
        return ymd(m)
    }

    static func isMonday(_ date: String) -> Bool {
        guard let d = parse(date) else { return false }
        return calendar.component(.weekday, from: d) == 2
    }

    /// 两个日期相差多少天（只用日期部分，避免时区/夏令时误差）
    static func daysBetween(_ from: String, _ to: String) -> Int {
        guard let a = parse(from), let b = parse(to) else { return 0 }
        let start = calendar.startOfDay(for: a)
        let end = calendar.startOfDay(for: b)
        return calendar.dateComponents([.day], from: start, to: end).day ?? 0
    }

    /// 今天周几：1=周一 … 7=周日（与课表 dayOfWeek 同口径）
    static func todayWeekday() -> Int {
        let w = calendar.component(.weekday, from: Date())
        return w == 1 ? 7 : w - 1
    }

    static func weekday(of date: String) -> Int {
        guard let d = parse(date) else { return 1 }
        let w = calendar.component(.weekday, from: d)
        return w == 1 ? 7 : w - 1
    }

    /// 某日期落在第几周（开学前返回 0/-1，由调用方收敛）
    static func weekOfDate(start: String, date: String) -> Int {
        let s = monday(of: start)
        guard !s.isEmpty else { return -1 }
        let days = daysBetween(s, date)
        return Int(floor(Double(days) / 7.0)) + 1
    }

    /// 当前教学周，夹在 1…totalWeeks
    static func currentWeek(start: String, totalWeeks: Int) -> Int {
        let total = max(1, min(openWeeksMax, totalWeeks))
        let s = monday(of: start)
        guard !s.isEmpty else { return 1 }
        let days = daysBetween(s, today())
        if days < 0 { return 1 }
        return max(1, min(total, days / 7 + 1))
    }

    /// 开放式周跨度：至少 30 周，今天所在周之后再留 4 周，上限 99
    static func openWeekSpan(start: String) -> Int {
        var span = openWeeksMin
        if !start.isEmpty {
            let w = weekOfDate(start: start, date: today())
            if w > 0 { span = max(span, w + 4) }
        }
        return min(openWeeksMax, span)
    }

    static func daysUntilStart(_ start: String) -> Int {
        let s = monday(of: start)
        guard !s.isEmpty else { return 0 }
        let d = daysBetween(today(), s)
        return max(0, d)
    }

    /// 第 week 周周一的 yyyy-MM-dd
    static func weekStart(_ start: String, week: Int) -> String {
        guard let s = parse(monday(of: start)) else { return "" }
        guard let d = calendar.date(byAdding: .day, value: (max(1, week) - 1) * 7, to: s) else { return "" }
        return ymd(d)
    }

    /// 某一周 7 天的日期（"9/7" 形式），用于表头
    static func weekDayLabels(_ start: String, week: Int) -> [String] {
        let base = weekStart(start, week: week)
        guard let d0 = parse(base) else { return Array(repeating: "", count: 7) }
        return (0..<7).map { i in
            let d = calendar.date(byAdding: .day, value: i, to: d0) ?? d0
            return mdFormatter.string(from: d)
        }
    }

    static func weekRangeLabel(_ start: String, week: Int) -> String {
        let labels = weekDayLabels(start, week: week)
        guard let first = labels.first, let last = labels.last else { return "" }
        return "\(first) - \(last)"
    }

    static func shortDate(_ date: String) -> String {
        guard let d = parse(date) else { return date }
        return mdFormatter.string(from: d)
    }

    /// 首节课日期：最早周次 + 最早星期反推
    static func firstCourseDate(start: String, timetable: Timetable?) -> String {
        let s = monday(of: start)
        guard !s.isEmpty, let t = timetable else { return "" }
        var bestWeek = Int.max, bestDay = 0
        for c in t.courses {
            for sess in c.sessions where !sess.weeks.isEmpty {
                let day = max(1, min(7, sess.dayOfWeek))
                for w in sess.weeks where w >= 1 {
                    if w < bestWeek || (w == bestWeek && day < bestDay) {
                        bestWeek = w
                        bestDay = day
                    }
                }
            }
        }
        guard bestWeek != Int.max, let base = parse(s) else { return "" }
        let offset = (bestWeek - 1) * 7 + max(1, bestDay) - 1
        guard let d = calendar.date(byAdding: .day, value: offset, to: base) else { return "" }
        return ymd(d)
    }
}

/// 唐山师范学院校历（内置，来源 jwc.tstc.edu.cn）。
/// 学校每年发布新校历后在这里追加一条；没有内置数据时 App 会提示用户手动设开学日期。
enum AcademicCalendar {

    struct Term {
        let id: String
        let name: String
        let classStart: String     // 第 1 周周一
        let teachingWeeks: Int
    }

    static let terms: [Term] = [
        Term(id: "2026-2027-1", name: "2026-2027 学年第一学期", classStart: "2026-08-31", teachingWeeks: 19),
        Term(id: "2026-2027-2", name: "2026-2027 学年第二学期", classStart: "2027-03-01", teachingWeeks: 17)
    ]

    static func term(id: String) -> Term? {
        guard !id.isEmpty else { return nil }
        return terms.first { $0.id == id }
    }

    /// 今天所在（或即将开始）的学期：学期结束（开学 + 教学周数）之前的日期都算这个学期
    static func term(for ymd: String) -> Term? {
        for t in terms where ymd <= endDate(t) {
            return t
        }
        return nil
    }

    /// 学期最后一天：第 1 周周一 + 教学周数 × 7 天
    static func endDate(_ t: Term) -> String {
        guard let start = WeekMath.parse(t.classStart) else { return t.classStart }
        let end = WeekMath.calendar.date(byAdding: .day, value: t.teachingWeeks * 7, to: start) ?? start
        return WeekMath.ymd(end)
    }
}
