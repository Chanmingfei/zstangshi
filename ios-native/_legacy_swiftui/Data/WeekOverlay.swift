import Foundation

/// 某一周的「临时变化」：哪些课这周不上（放假/停课/已调走），
/// 哪些课是额外加进来的（补课/调来）。
/// 课程本身不动，课表网格按这个叠加绘制。（对齐 Android `data/WeekOverlay.kt`）
struct WeekOverlay {

    struct Mark {
        /// 放假 / 停课 / 已调走
        let label: String
        /// 可撤销的用户安排；内置节假日为空
        let adjustId: String
    }

    struct Extra {
        let course: Course
        /// 新造的时段，只在本周显示
        let session: Session
        /// 补课 / 调课
        let label: String
        let adjustId: String
    }

    let week: Int
    /// 被取消的时段。用「时段指纹」做 key —— Session 是 struct，
    /// 而 Session 内又含字典（不是 Hashable 的稳定相等语义），
    /// 用指纹能保证同一时段在多处构造时稳定命中。
    var cancelled: [String: Mark] = [:]
    var extras: [Extra] = []
    /// 星期 → 当天标签（「国庆」「补 10/7 周三」），表头用。下标 1...7
    var dayLabels: [Int: String] = [:]
    var dayOff: Set<Int> = []

    var isEmpty: Bool {
        cancelled.isEmpty && extras.isEmpty && dayLabels.isEmpty
    }

    // MARK: - 构建

    static func empty(week: Int) -> WeekOverlay {
        WeekOverlay(week: week)
    }

    static func build(_ t: Timetable?, start: String?, week: Int) -> WeekOverlay {
        var o = WeekOverlay(week: week)
        guard let t else { return o }
        let hasStart = Dates.valid(start)

        // 1. 放假 / 补课日
        if hasStart {
            for d in 1...7 {
                let ymd = Dates.dateOf(start: start, week: week, day: d)
                guard let h = Holidays.lookup(t, ymd) else { continue }
                if h.off {
                    o.dayOff.insert(d)
                    o.dayLabels[d] = h.label
                    let offId = userAdjustId(t, ymd)
                    for c in t.courses {
                        for s in c.sessions where s.dayOfWeek == d && s.isActive(week: week) {
                            o.cancelled[fingerprint(s)] = Mark(label: "放假", adjustId: offId)
                        }
                    }
                } else if Dates.valid(h.fromDate) {
                    let fw = Dates.weekOf(start: start, ymd: h.fromDate)
                    let fd = Dates.dayOfWeek(h.fromDate)
                    o.dayLabels[d] = "补" + Dates.cnDay(fd)
                    let aid = userAdjustId(t, ymd)
                    for c in t.courses {
                        for s in c.sessions where s.dayOfWeek == fd && s.isActive(week: fw) {
                            if findCancel(t, c, s, week: fw) != nil { continue }
                            var n = s
                            n.dayOfWeek = d
                            n.weeks = [week]
                            n.weeksRaw = "\(week)周"
                            let wt = s.weekTeachers[String(fw)]
                            n.weekTeachers = [:]
                            if let wt { n.weekTeachers[String(week)] = wt }
                            o.extras.append(Extra(course: c, session: n, label: "补课", adjustId: aid))
                        }
                    }
                }
            }
        }

        // 2. 停课 / 调课
        for a in t.adjusts {
            let isCancel = a.type == Adjust.cancel
            let isMove = a.type == Adjust.move
            if !isCancel && !isMove { continue }

            if a.week == week {
                for c in t.courses where c.name == a.name {
                    for s in c.sessions where matches(a, s, week: week) {
                        o.cancelled[fingerprint(s)] = Mark(label: isCancel ? "停课" : "已调走",
                                                           adjustId: a.id)
                    }
                }
            }

            if isMove && targetWeek(a) == week {
                for c in t.courses where c.name == a.name {
                    var moved = false
                    for s in c.sessions {
                        if moved { break }
                        if !matches(a, s, week: a.week) { continue }
                        var n = s
                        n.dayOfWeek = a.toDay > 0 ? a.toDay : s.dayOfWeek
                        if a.hasToTime {
                            n.timeStart = a.toTimeStart
                            n.timeEnd = a.toTimeEnd
                        } else if a.toStart > 0 {
                            let len = s.endSection - s.startSection
                            n.startSection = a.toStart
                            n.endSection = a.toEnd >= a.toStart ? a.toEnd : a.toStart + len
                            n.timeStart = ""
                            n.timeEnd = ""
                        }
                        if !a.toLocation.isEmpty { n.location = a.toLocation }
                        n.weeks = [week]
                        n.weeksRaw = "\(week)周"
                        let wt = s.weekTeachers[String(a.week)]
                        n.weekTeachers = [:]
                        if let wt { n.weekTeachers[String(week)] = wt }
                        o.extras.append(Extra(course: c, session: n, label: "调课", adjustId: a.id))
                        moved = true
                    }
                }
            }
        }
        return o
    }

    // MARK: - 判定

    static func targetWeek(_ a: Adjust) -> Int {
        a.toWeek > 0 ? a.toWeek : a.week
    }

    /// 调整是否命中这个时段（这周确实有这节课，且星期/节次对得上）。
    static func matches(_ a: Adjust, _ s: Session, week: Int) -> Bool {
        guard s.isActive(week: week) else { return false }
        if a.day > 0 && s.dayOfWeek != a.day { return false }
        if a.start > 0 {
            let end = a.end >= a.start ? a.end : a.start
            if s.endSection < a.start || s.startSection > end { return false }
        }
        return true
    }

    // MARK: - 私有

    private static func findCancel(_ t: Timetable, _ c: Course, _ s: Session, week: Int) -> Adjust? {
        for a in t.adjusts {
            guard a.type == Adjust.cancel || a.type == Adjust.move else { continue }
            guard a.week == week, c.name == a.name, matches(a, s, week: week) else { continue }
            return a
        }
        return nil
    }

    private static func userAdjustId(_ t: Timetable, _ ymd: String) -> String {
        t.adjusts.first { $0.date == ymd }?.id ?? ""
    }

    /// 时段指纹：星期 + 节次 + 自定义时间。用于稳定识别「同一个时段」。
    static func fingerprint(_ s: Session) -> String {
        "\(s.dayOfWeek)|\(s.startSection)-\(s.endSection)|\(s.timeStart)-\(s.timeEnd)|\(s.weeksRaw)"
    }
}