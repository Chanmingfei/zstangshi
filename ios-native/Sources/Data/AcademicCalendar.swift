import Foundation

/// 唐山师范学院校历（内置数据，来源：教务处校历 jwc.tstc.edu.cn，2026-05-28 发布）。
/// 日期一律 yyyy-MM-dd 字符串。
/// 校历备注：8/29-8/30 报到，8/31 正式上课；本学期共 19 周（上课 17 + 考试 2）。
/// 每年学校发布新校历后，在 terms 里追加一个学年即可。
enum AcademicCalendar {

    static let sourceListURL = "https://jwc.tstc.edu.cn/xlcx.htm"

    struct Term {
        let id: String            // 2026-2027-1
        let name: String          // 2026-2027 学年第一学期
        let classStart: String    // 第 1 周周一
        let teachingWeeks: Int
        let examStart: String
        let examEnd: String
        let breakName: String
        let breakStart: String
        let breakEnd: String
    }

    struct Event {
        let start: String         // 含首尾
        let end: String
        let label: String
        /// 放假/节日（true）还是校内安排（false）
        let holiday: Bool

        func covers(_ ymd: String?) -> Bool {
            guard let ymd, !ymd.isEmpty else { return false }
            return ymd >= start && ymd <= end
        }
    }

    static let terms: [Term] = [
        // ===== 2026-2027 学年 =====
        // 8/29-8/30 报到，8/31 正式上课；本学期共 19 周（上课 17 + 考试 2）。
        Term(id: "2026-2027-1", name: "2026-2027 学年第一学期",
             classStart: "2026-08-31", teachingWeeks: 19,
             examStart: "2026-12-28", examEnd: "2027-01-10",
             breakName: "寒假", breakStart: "2027-01-11", breakEnd: "2027-02-26"),
        Term(id: "2026-2027-2", name: "2026-2027 学年第二学期",
             classStart: "2027-03-01", teachingWeeks: 17,
             examStart: "2027-06-26", examEnd: "2027-07-02",
             breakName: "暑假", breakStart: "2027-07-03", breakEnd: "2027-08-31")
    ]

    static let events: [Event] = [
        Event(start: "2026-09-10", end: "2026-09-10", label: "教师节", holiday: true),
        Event(start: "2026-09-22", end: "2026-09-24", label: "秋季运动会", holiday: false),
        Event(start: "2026-09-25", end: "2026-09-25", label: "中秋节", holiday: true),
        Event(start: "2026-10-01", end: "2026-10-07", label: "国庆节", holiday: true),
        Event(start: "2027-01-01", end: "2027-01-01", label: "元旦", holiday: true),
        Event(start: "2027-01-11", end: "2027-02-26", label: "寒假", holiday: true),
        Event(start: "2027-04-05", end: "2027-04-05", label: "清明节", holiday: true),
        Event(start: "2027-05-01", end: "2027-05-01", label: "劳动节", holiday: true),
        Event(start: "2027-06-09", end: "2027-06-09", label: "端午节", holiday: true),
        Event(start: "2027-07-03", end: "2027-08-31", label: "暑假", holiday: true)
    ]

    /// 按学期 id（如 2026-2027-1）找内置校历；没有返回 nil。
    static func term(byId id: String?) -> Term? {
        guard let id, !id.isEmpty else { return nil }
        return terms.first { $0.id == id }
    }

    /// 兼容旧调用名。
    static func termFor(semesterId: String?) -> Term? {
        term(byId: semesterId)
    }

    /// 今天所在（或即将开始）的学期：考试结束 + 假期内仍算这个学期；都过了返回 nil。
    static func termFor(today ymd: String?) -> Term? {
        guard let ymd, !ymd.isEmpty else { return nil }
        for t in terms where ymd <= t.breakEnd { return t }
        return nil
    }

    /// 当天是第几教学周（开学前为 0 或负数）。
    static func week(_ t: Term, _ ymd: String?) -> Int {
        let d = daysBetween(t.classStart, ymd)
        return Int(Dates.floorDiv(Int64(d), 7)) + 1
    }

    /// 当天正在进行的事件（节假日/考试周等）。
    static func eventsOn(_ ymd: String?) -> [Event] {
        events.filter { $0.covers(ymd) }
    }

    /// 从今天起（含正在进行的）最多 n 个事件。
    static func upcoming(_ ymd: String?, _ n: Int) -> [Event] {
        guard let ymd else { return [] }
        var out: [Event] = []
        for e in events where e.end >= ymd {
            out.append(e)
            if out.count >= n { break }
        }
        return out
    }

    /// 一句话状态：「第一学期 · 第 8 周 · 距寒假 90 天」。
    static func statusLine(_ ymd: String?) -> String {
        guard let ymd, let t = termFor(today: ymd) else { return "校历暂未更新到这一学年" }
        let termShort = t.id.hasSuffix("-1") ? "第一学期" : "第二学期"

        if ymd < t.classStart {
            return "\(termShort) · 距开学 \(daysBetween(ymd, t.classStart)) 天"
        }
        if ymd >= t.breakStart {
            let target = t.breakName == "寒假" ? "开学" : "新学年"
            let d = max(0, daysBetween(ymd, t.breakEnd) + 1)
            return t.breakName + "中 · 距" + target + " \(d) 天"
        }
        if ymd >= t.examStart {
            return "\(termShort) · 期末考试周 · 距\(t.breakName) \(daysBetween(ymd, t.breakStart)) 天"
        }
        let w = week(t, ymd)
        return "\(termShort) · 第 \(w) 周 · 距\(t.breakName) \(daysBetween(ymd, t.breakStart)) 天"
    }

    static func daysBetween(_ fromYmd: String?, _ toYmd: String?) -> Int {
        let a = Dates.epochDay(fromYmd)
        let b = Dates.epochDay(toYmd)
        guard a != Int.min, b != Int.min else { return 0 }
        return b - a
    }

    /// 09.07 这种短日期
    static func shortDate(_ ymd: String?) -> String {
        guard let ymd, ymd.count >= 10 else { return ymd ?? "" }
        let p = ymd.split(separator: "-")
        guard p.count == 3, let m = Int(p[1]), let d = Int(p[2]) else { return ymd }
        return String(format: "%02d.%02d", m, d)
    }
}