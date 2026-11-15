import Foundation

/// 某一天实际要上的课（已算上放假、停课、调课、补课），按开始时间排好。
/// 桌面小组件和课前提醒共用。（对齐 Android `data/DayPlan.kt`）
enum DayPlan {

    struct Item: Identifiable, Equatable {
        var id: String { "\(name)|\(startMin)|\(endMin)|\(sections)|\(tag)" }

        var name: String = ""
        /// 校区（教务里有时只有校区没有教室，反之亦然）
        var campus: String = ""
        var location: String = ""
        var startMin: Int = 0
        var endMin: Int = 0
        /// 「3-4节」或空（自定义时间的安排）
        var sections: String = ""
        /// 「补课」「调课」或空
        var tag: String = ""
        /// 课程色板下标（和课表同色）
        var colorIndex: Int = 0

        var timeLabel: String {
            Session.formatHm(startMin) + "-" + Session.formatHm(endMin)
        }

        /// 「东校区 3-518」这种完整地点；两段都没有就是空串。
        var placeLabel: String {
            let c = campus.trimmingCharacters(in: .whitespaces)
            let l = location.trimmingCharacters(in: .whitespaces)
            if c.isEmpty { return l }
            if l.isEmpty { return c }
            if l.contains(c) { return l }
            return c + " " + l
        }

        var isOngoing: Bool {
            let now = Dates.nowMinute()
            return startMin <= now && now < endMin
        }
    }

    /// - Parameters:
    ///   - start: 第 1 周周一（yyyy-MM-dd）；无效时返回空
    ///   - secStart/secEnd: 下标 = 节次，值为当天分钟数
    static func of(_ t: Timetable?, start: String?, ymd: String?,
                   secStart: [Int], secEnd: [Int]) -> [Item] {
        guard let t, Dates.valid(start), Dates.valid(ymd) else { return [] }
        let week = Dates.weekOf(start: start, ymd: ymd)
        guard week >= 1 else { return [] }
        let day = Dates.dayOfWeek(ymd)
        let o = WeekOverlay.build(t, start: start, week: week)

        var out: [Item] = []
        for c in t.courses {
            for s in c.sessions where s.dayOfWeek == day && s.isActive(week: week) {
                if o.cancelled[WeekOverlay.fingerprint(s)] != nil { continue }
                guard let it = item(c, s, secStart, secEnd) else { continue }
                out.append(it)
            }
        }
        for e in o.extras where e.session.dayOfWeek == day {
            guard var it = item(e.course, e.session, secStart, secEnd) else { continue }
            it.tag = e.label
            out.append(it)
        }
        out.sort { a, b in
            a.startMin != b.startMin ? a.startMin < b.startMin : a.name < b.name
        }
        return out
    }

    private static func item(_ c: Course, _ s: Session,
                             _ secStart: [Int], _ secEnd: [Int]) -> Item? {
        var it = Item()
        it.name = c.name
        it.campus = s.campus.trimmingCharacters(in: .whitespaces)
        it.location = s.location.trimmingCharacters(in: .whitespaces)
        it.colorIndex = c.colorIndex
        if s.hasCustomTime {
            it.startMin = Session.minutesOf(s.timeStart)
            it.endMin = Session.minutesOf(s.timeEnd)
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

    /// 现在正在上的课，没有返回 nil。
    static func current(_ items: [Item], nowMin: Int) -> Item? {
        items.first { $0.startMin <= nowMin && nowMin < $0.endMin }
    }

    /// 还没开始的第一节课，没有返回 nil。
    static func next(_ items: [Item], nowMin: Int) -> Item? {
        items.first { $0.startMin > nowMin }
    }

    /// 今天的课是不是都上完了（今天没课也算）。
    static func doneFor(_ items: [Item], nowMin: Int) -> Bool {
        items.allSatisfy { $0.endMin <= nowMin }
    }
}