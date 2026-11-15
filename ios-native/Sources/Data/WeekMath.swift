import Foundation

/// 教学周与日期计算，避免 UI 层各写一套（对齐 Android `data/WeekMath.kt`）。
enum WeekMath {

    /// 不设学期总周数：周次从第 1 周一直往后排，最多到这里（足够覆盖整学期 + 假期）。
    static let openWeeksMax = 99
    /// 可翻页的最少周数，保证刚开学也能往后翻看。
    static let openWeeksMin = 30

    static func todayDayOfWeek() -> Int {
        let wd = Calendar.current.component(.weekday, from: Date())   // 1=周日
        return wd == 1 ? 7 : wd - 1
    }

    /// 开放式周跨度：至少 openWeeksMin，今天所在周之后再留 4 周，最多 openWeeksMax。
    static func openWeekSpan(_ startDate: String?) -> Int {
        var span = openWeeksMin
        let ymd = Dates.todayLocal()
        let today = startDate?.isEmpty == false ? Dates.weekOf(start: startDate, ymd: ymd) : -1
        if today > 0 { span = max(span, today + 4) }
        return min(openWeeksMax, span)
    }

    /// 当前是第几周（夹到 1...totalWeeks）。
    static func currentWeek(startDate: String?, totalWeeks: Int) -> Int {
        let total = max(1, min(openWeeksMax, totalWeeks))
        let normalized = normalizeToMonday(startDate)
        guard Dates.valid(normalized) else { return 1 }

        let start = Dates.epochDay(normalized)
        let today = Dates.epochDay(Dates.todayLocal())
        guard start != Int.min, today != Int.min else { return 1 }

        let days = today - start
        if days < 0 { return 1 }
        let week = days / 7 + 1
        return max(1, min(total, week))
    }

    /// 任意日期落在第几周（周一为一周起点；解析失败返回 -1，可能越界由调用方收敛）。
    static func weekOfDate(startDate: String?, ymd: String?) -> Int {
        let normalizedStart = normalizeToMonday(startDate)
        guard Dates.valid(normalizedStart) else { return -1 }
        let normalizedYmd = normalizeToMonday(ymd)
        guard Dates.valid(normalizedYmd) else { return -1 }
        let s = Dates.epochDay(normalizedStart)
        let d = Dates.epochDay(normalizedYmd)
        // floorDiv：开学前的日期得到 0、-1…，而不是被截断成第 1 周
        return Int(Dates.floorDiv(Int64(d - s), 7)) + 1
    }

    /// 距开学还有几天；已开学或未设置返回 0。
    static func daysUntilStart(_ startDate: String?) -> Int {
        let normalized = normalizeToMonday(startDate)
        guard Dates.valid(normalized) else { return 0 }
        let s = Dates.epochDay(normalized)
        let t = Dates.epochDay(Dates.todayLocal())
        guard s != Int.min, t != Int.min else { return 0 }
        let d = s - t
        return d > 0 ? d : 0
    }

    /// 第 week 周周一的 yyyy-MM-dd；开学日期无效返回空串。
    static func weekRangeStart(_ startDate: String?, week: Int) -> String {
        let normalized = normalizeToMonday(startDate)
        guard Dates.valid(normalized) else { return "" }
        return Dates.addDays(normalized, (week - 1) * 7)
    }

    /// 第 week 周周一到周日 → ["09/07", "09/08", …]（供课表表头显示）
    static func weekDates(_ startDate: String?, week: Int) -> [String] {
        let normalized = normalizeToMonday(startDate)
        var base: String
        if Dates.valid(normalized) {
            base = Dates.addDays(normalized, (max(1, week) - 1) * 7)
        } else {
            // 没有开学日期时用本周周一占位（表头不能空着）
            base = Dates.addDays(Dates.todayLocal(), -(todayDayOfWeek() - 1))
        }
        var out: [String] = []
        for i in 0..<7 {
            let ymd = Dates.addDays(base, i)
            let parts = ymd.split(separator: "-")
            if parts.count == 3 {
                out.append("\(Int(parts[1]) ?? 0)/\(Int(parts[2]) ?? 0)")
            } else {
                out.append("")
            }
        }
        return out
    }

    /// 将任意日期归一到所在周的周一。
    ///
    /// 学期第一周固定从周一开始；旧数据或日期选择器传入其他星期时，
    /// 在这里统一收口，避免周日期范围从周三、周五开始。
    static func normalizeToMonday(_ date: String?) -> String {
        guard Dates.valid(date) else { return "" }
        let dow = Dates.dayOfWeek(date)          // 1=周一 … 7=周日
        return Dates.addDays(date, -(dow - 1))
    }

    static func isMonday(_ date: String?) -> Bool {
        Dates.dayOfWeek(date) == 1
    }

    static func weekRangeLabel(_ startDate: String?, week: Int) -> String {
        let d = weekDates(startDate, week: week)
        guard d.count == 7 else { return "" }
        return d[0] + " - " + d[6]
    }

    static func shortDate(_ date: String?) -> String {
        guard Dates.valid(date) else { return "" }
        let p = (date ?? "").split(separator: "-")
        guard p.count == 3, let m = Int(p[1]), let d = Int(p[2]) else { return "" }
        return "\(m)/\(d)"
    }

    /// 从课表里最早的周次与星期反推首节课日期。
    /// 例：第一周 2026-09-07，最早课程在第 5 周周一，则返回 2026-10-05。
    static func firstCourseDate(_ startDate: String?, _ timetable: Timetable?) -> String {
        let normalized = normalizeToMonday(startDate)
        guard Dates.valid(normalized), let timetable else { return "" }

        var bestWeek = Int.max
        var bestDay = 0
        for course in timetable.courses {
            for session in course.sessions {
                let day = max(1, min(7, session.dayOfWeek))
                for week in session.weeks where week >= 1 {
                    if week < bestWeek || (week == bestWeek && day < bestDay) {
                        bestWeek = week
                        bestDay = day
                    }
                }
            }
        }
        guard bestWeek != Int.max else { return "" }
        return Dates.addDays(normalized, (bestWeek - 1) * 7 + bestDay - 1)
    }

    /// 第一节课程的「M/d 周X」标签
    static func firstCourseLabel(_ startDate: String?, _ timetable: Timetable?) -> String {
        let date = firstCourseDate(startDate, timetable)
        guard !date.isEmpty else { return "暂无可计算的首节课" }
        let dayNames = ["日", "一", "二", "三", "四", "五", "六"]
        let dow = Calendar.current.component(.weekday, from: parseDate(date))
        return shortDate(date) + " 周" + dayNames[max(0, min(6, dow - 1))]
    }

    /// 解析成 Date（本地时区当天 00:00）。
    static func parseDate(_ date: String?) -> Date {
        let normalized = (date ?? "").trimmingCharacters(in: .whitespaces)
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        f.timeZone = .current
        return f.date(from: normalized) ?? Date()
    }

    /// 「2026-2027 学年第一学期」→「26-27 第一学期」：标题只剩一行的地方，年份不必写全
    static func shortTermName(_ name: String) -> String {
        // 先把「2026-2027」缩成「26-27」，其余空白原样保留
        var s = name
        // 匹配 20YY-20YY 这种四位数年份对，再各取后两位。
        // 必须用 [0-9] 而非 \d 前缀锚定，避免回溯成「26-2027」这种错切。
        if let re = try? NSRegularExpression(pattern: "([0-9]{4})-([0-9]{4})") {
            let range = NSRange(s.startIndex..., in: s)
            if let m = re.firstMatch(in: s, range: range), let r = Range(m.range, in: s) {
                let a = Range(m.range(at: 1), in: s).map { String(s[$0].suffix(2)) } ?? ""
                let b = Range(m.range(at: 2), in: s).map { String(s[$0].suffix(2)) } ?? ""
                s = s.replacingCharacters(in: r, with: a + "-" + b)
            }
        }
        return s.replacingOccurrences(of: "学年", with: "")
    }
}