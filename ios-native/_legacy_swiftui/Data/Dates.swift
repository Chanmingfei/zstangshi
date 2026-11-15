import Foundation

/// 纯日期小工具（yyyy-MM-dd），内部按 UTC 整天计算，避免时区/夏令时误差。
///
/// 严格对齐 Android `data/Dates.kt`：所有函数名、语义、返回值都与之一一对应，
/// 包括「无效日期返回 Long.MIN_VALUE」这种哨兵值（Swift 侧用 `Int.min`）。
enum Dates {

    private static let utc: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        c.locale = Locale(identifier: "en_US_POSIX")
        c.firstWeekday = 2          // 周一
        c.minimumDaysInFirstWeek = 4
        return c
    }()

    private static let cnChars = ["", "一", "二", "三", "四", "五", "六", "日"]

    // MARK: - 纪元日（epoch day）

    /// yyyy-MM-dd → 自 1970-01-01 的天数；格式不对返回 Int.min（对应 Kotlin 的 Long.MIN_VALUE）。
    ///
    /// 手工解析而不用 DateFormatter：DateFormatter 在大量调用时很慢，
    /// 且它对 "2026-13-45" 这类非法值会宽松地滚动到别的月份，
    /// 这里需要严格的 `m ∈ 1...12 && d ∈ 1...31` 校验（与安卓一致）。
    static func epochDay(_ ymd: String?) -> Int {
        guard let ymd else { return Int.min }
        let parts = ymd.trimmingCharacters(in: .whitespaces).split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              m >= 1, m <= 12, d >= 1, d <= 31
        else { return Int.min }

        var comps = DateComponents()
        comps.year = y
        comps.month = m
        comps.day = d
        guard let date = utc.date(from: comps) else { return Int.min }
        // 严格校验：GregorianCalendar 会把 2 月 30 日滚到 3 月 2 日，这里必须拒绝
        let back = utc.dateComponents([.year, .month, .day], from: date)
        guard back.year == y, back.month == m, back.day == d else { return Int.min }

        return Int(floor(date.timeIntervalSince1970 / 86_400.0))
    }

    /// 纪元日 → yyyy-MM-dd
    static func ofEpochDay(_ day: Int) -> String {
        let date = Date(timeIntervalSince1970: Double(day) * 86_400.0)
        let c = utc.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 1970, c.month ?? 1, c.day ?? 1)
    }

    static func valid(_ ymd: String?) -> Bool {
        epochDay(ymd) != Int.min
    }

    // MARK: - 星期

    /// 1=周一 … 7=周日。无效返回 0。
    static func dayOfWeek(_ ymd: String?) -> Int {
        let d = epochDay(ymd)
        if d == Int.min { return 0 }
        // 1970-01-01 是周四 ⇒ floorMod(d + 3, 7) + 1
        return Int(floorMod(Int64(d) + 3, 7)) + 1
    }

    /// 第 week 周星期 day 的日期；开学日期无效返回空串。
    static func dateOf(start: String?, week: Int, day: Int) -> String {
        let s = epochDay(start)
        if s == Int.min || week == 0 || day < 1 || day > 7 { return "" }
        let monday = s - (dayOfWeek(start) - 1)
        return ofEpochDay(monday + (week - 1) * 7 + (day - 1))
    }

    /// 日期是第几周（开学前 ≤0）；无效返回 0。
    static func weekOf(start: String?, ymd: String?) -> Int {
        let s = epochDay(start)
        let d = epochDay(ymd)
        if s == Int.min || d == Int.min { return 0 }
        let monday = s - (dayOfWeek(start) - 1)
        return Int(floorDiv(Int64(d - monday), 7)) + 1
    }

    static func addDays(_ ymd: String?, _ n: Int) -> String {
        let d = epochDay(ymd)
        return d == Int.min ? "" : ofEpochDay(d + n)
    }

    // MARK: - 展示

    /// 1 → "周一"
    static func cnDay(_ day: Int) -> String {
        (day >= 1 && day <= 7) ? "周" + cnChars[day] : ""
    }

    /// 2026-10-10 → 10月10日
    static func md(_ ymd: String?) -> String {
        guard let ymd else { return "" }
        let p = ymd.split(separator: "-", omittingEmptySubsequences: false)
        guard p.count == 3, let m = Int(p[1]), let d = Int(p[2]) else { return ymd }
        return "\(m)月\(d)日"
    }

    /// 2026-10-10 → 10 月 10 日（带空格，安卓 HomePage.shortDate 的写法）
    static func shortDate(_ ymd: String?) -> String {
        guard let ymd else { return "" }
        let p = ymd.split(separator: "-", omittingEmptySubsequences: false)
        guard p.count == 3, let m = Int(p[1]), let d = Int(p[2]) else { return ymd }
        return "\(m) 月 \(d) 日"
    }

    // MARK: - 工具

    /// Kotlin 的 Math.floorDiv / floorMod 语义（向下取整，不是向零截断）。
    /// Swift 的 `/` 对负数是向零截断，直接用会算错开学前的日期。
    static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q
    }

    static func floorMod(_ a: Int64, _ b: Int64) -> Int64 {
        let r = a % b
        return (r != 0 && ((r < 0) != (b < 0))) ? r + b : r
    }

    /// 本地时区的今天（yyyy-MM-dd）
    static func todayLocal() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        f.timeZone = .current
        return f.string(from: Date())
    }

    /// 当前时刻的当天分钟数（0…1439），用于「正在上 / 下一节」判断。
    static func nowMinute() -> Int {
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }
}