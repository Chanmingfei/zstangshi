import Foundation

/// 某一天实际要上的课，按开始时间排好（对应 Android `DayPlan`）。
///
/// v1 说明：Android 版还叠了「停课/调课/放假/补课」这层 overlay，
/// iOS 首版只做纯课表 → 今日课程，overlay 留到与「手动调课」一起做。
enum DayPlan {

    struct Item: Identifiable {
        var id: String { "\(name)-\(startMin)-\(location)" }
        var name = ""
        var campus = ""
        var location = ""
        var startMin = 0
        var endMin = 0
        /// 「3-4节」，自定义时间的安排留空
        var sections = ""
        /// 「补课」「调课」或空
        var tag = ""
        var colorIndex = 0

        var timeLabel: String {
            "\(Session.format(startMin))-\(Session.format(endMin))"
        }

        /// 「东校区 3-518」；两段都没有就是空串
        var placeLabel: String {
            let c = campus.trimmingCharacters(in: .whitespaces)
            let l = location.trimmingCharacters(in: .whitespaces)
            if c.isEmpty { return l }
            if l.isEmpty { return c }
            if l.contains(c) { return l }
            return "\(c) \(l)"
        }
    }

    static func of(_ t: Timetable?, start: String, ymd: String,
                   secStart: [Int], secEnd: [Int]) -> [Item] {
        guard let t, WeekMath.isValid(start), WeekMath.isValid(ymd) else { return [] }
        let week = WeekMath.weekOfDate(start: start, date: ymd)
        guard week >= 1 else { return [] }
        let day = WeekMath.weekday(of: ymd)

        var out: [Item] = []
        for c in t.courses {
            for s in c.sessions {
                guard s.dayOfWeek == day, s.isActive(week) else { continue }
                if let item = make(c, s, secStart, secEnd) { out.append(item) }
            }
        }
        out.sort { a, b in
            a.startMin == b.startMin ? a.name < b.name : a.startMin < b.startMin
        }
        return out
    }

    private static func make(_ c: Course, _ s: Session,
                             _ secStart: [Int], _ secEnd: [Int]) -> Item? {
        var it = Item()
        it.name = c.name
        it.campus = s.campus
        it.location = s.location
        it.colorIndex = c.colorIndex
        if s.hasCustomTime {
            it.startMin = Session.minutes(s.timeStart)
            it.endMin = Session.minutes(s.timeEnd)
        } else {
            let a = s.startSection
            let b = max(s.startSection, s.endSection)
            guard a >= 1, b < secStart.count, b < secEnd.count else { return nil }
            it.startMin = secStart[a]
            it.endMin = secEnd[b]
            it.sections = a == b ? "第\(a)节" : "\(a)-\(b)节"
        }
        guard it.startMin >= 0, it.endMin > it.startMin else { return nil }
        return it
    }

    /// 现在正在上的课
    static func current(_ items: [Item], nowMin: Int) -> Item? {
        items.first { $0.startMin <= nowMin && nowMin < $0.endMin }
    }

    /// 还没开始的第一节课
    static func next(_ items: [Item], nowMin: Int) -> Item? {
        items.first { $0.startMin > nowMin }
    }

    static func nowMinute() -> Int {
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }
}
