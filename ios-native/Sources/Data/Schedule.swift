import Foundation

/// 全局作息配置：第 1 节开始时间、每节时长、每节课后休息。
/// 对应 Android `ScheduleSettings`（唐山师范学院标准作息：上午 4 节、下午 4 节、晚上 4 节）。
struct Schedule {

    static let maxSections = 16
    static let minSections = 8
    static let defaultSectionCount = 12
    static let defaultFirstStart = "08:00"

    /// 每节 45 分钟
    private static let durations = [Int](repeating: 45, count: maxSections)
    /// 每节后休息（分钟），下标 = 节次 - 1
    private static let breaks = [5, 20, 5, 150, 5, 20, 5, 5, 40, 5, 5, 0, 10, 10, 10, 0]

    var firstClassStart: String = Schedule.defaultFirstStart
    var sectionCount: Int = Schedule.defaultSectionCount

    static func load(from store: Store) -> Schedule {
        var s = Schedule()
        s.firstClassStart = store.firstStart()
        s.sectionCount = store.sectionCount()
        return s
    }

    private var firstMinutes: Int {
        let m = Session.minutes(firstClassStart)
        return m >= 0 ? m : 8 * 60
    }

    func startMinutes(_ section: Int) -> Int {
        let n = max(1, min(Schedule.maxSections, section))
        var minutes = firstMinutes
        guard n > 1 else { return minutes }
        for i in 1..<n {
            minutes += max(1, Schedule.durations[i - 1])
            minutes += max(0, Schedule.breaks[i - 1])
        }
        return minutes
    }

    func endMinutes(_ section: Int) -> Int {
        let n = max(1, min(Schedule.maxSections, section))
        return startMinutes(n) + max(1, Schedule.durations[n - 1])
    }

    func startTime(_ section: Int) -> String { Session.format(startMinutes(section)) }

    func endTime(_ section: Int) -> String { Session.format(endMinutes(section)) }

    /// 第 1 节到第 n 节的起止分钟数组（下标 = 节次，第 0 项空着），
    /// 首页 DayPlan 与课表网格共用同一套，保证「首页显示 10:45」和格子上一致。
    var sectionRanges: (start: [Int], end: [Int]) {
        var s = [Int](repeating: 0, count: Schedule.maxSections + 1)
        var e = [Int](repeating: 0, count: Schedule.maxSections + 1)
        for i in 1...Schedule.maxSections {
            s[i] = startMinutes(i)
            e[i] = endMinutes(i)
        }
        return (s, e)
    }

    /// 课表里用到的最大节次（用来决定网格画几行）
    static func maxSectionUsed(_ timetable: Timetable?) -> Int {
        guard let t = timetable else { return 0 }
        var m = 0
        for c in t.courses {
            for s in c.sessions {
                m = max(m, s.endSection)
            }
        }
        return m
    }

    /// 网格行数：最多画到用到的节次，但不少于 10 行，收在 sectionCount 之内
    func rowCount(for timetable: Timetable?) -> Int {
        let used = Schedule.maxSectionUsed(timetable)
        return max(10, min(sectionCount, max(used, min(sectionCount, 12))))
    }
}
