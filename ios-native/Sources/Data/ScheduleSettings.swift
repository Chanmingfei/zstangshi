import Foundation

/// 全局作息配置：第 1 节开始时间、每节时长、每节课后休息。
///
/// 时间按顺序顺延计算：第 N 节开始 = 第 1 节开始 + 前面所有节的时长与课间休息。
/// 数值全部照抄 Android `data/ScheduleSettings.kt`（唐山师范学院标准作息表）。
struct ScheduleSettings: Codable, Equatable {

    static let schemaVersion = 1

    /// 每天最多几节：不同学校从 8 节到 16 节不等，作息表按最大值存，
    /// sectionCount 决定显示几节。
    static let maxSections = 16
    static let minSections = 8

    /// 唐山师范学院标准作息：上午 4 节、下午 4 节、晚上 4 节，共 12 节。
    static let defaultSectionCount = 12
    static let defaultFirstClassStart = "08:00"

    /// 每节统一 45 分钟。
    private static let defaultDurations: [Int] = Array(repeating: 45, count: maxSections)

    /// 每节课后休息（分钟），下标 = 节次-1。依据唐山师范学院标准作息时间表：
    /// 第1节 08:00-08:45(休5) → 第2节 08:50-09:35(休20) → 第3节 09:55-10:40(休5)
    /// → 第4节 10:45-11:30(午休150→14:00)
    /// 第5节 14:00-14:45(休5) → 第6节 14:50-15:35(休20) → 第7节 15:55-16:40(休5)
    /// → 第8节 16:45-17:30(休5) → 第9节 17:35-18:20(晚餐40→19:00)
    /// 第10节 19:00-19:45(休5) → 第11节 19:50-20:35(休5) → 第12节 20:40-21:25(休0)
    private static let defaultBreaks: [Int] = [5, 20, 5, 150, 5, 20, 5, 5, 40, 5, 5, 0, 10, 10, 10, 0]

    struct Section: Codable, Equatable {
        var section: Int = 0
        var durationMinutes: Int = 45
        var breakAfterMinutes: Int = 0

        init() {}

        init(_ section: Int, _ duration: Int, _ breakAfter: Int) {
            self.section = section
            self.durationMinutes = duration
            self.breakAfterMinutes = breakAfter
        }
    }

    var firstClassStart: String = ScheduleSettings.defaultFirstClassStart
    /// 每天显示几节（minSections...maxSections）
    var sectionCount: Int = ScheduleSettings.defaultSectionCount
    var sections: [Section] = []

    init() {
        resetToDefaults()
    }

    /// 用默认值构造一份完整的作息（对应 `ScheduleSettings.createDefault()`）。
    static func createDefault() -> ScheduleSettings { ScheduleSettings() }

    enum CodingKeys: String, CodingKey {
        case firstClassStart, sectionCount, sections
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init()
        let first = (try? c.decodeIfPresent(String.self, forKey: .firstClassStart)) ?? ScheduleSettings.defaultFirstClassStart
        if ScheduleSettings.parseHm(first) >= 0 { firstClassStart = first }
        let count = (try? c.decodeIfPresent(Int.self, forKey: .sectionCount)) ?? ScheduleSettings.defaultSectionCount
        sectionCount = ScheduleSettings.clamp(count, ScheduleSettings.minSections, ScheduleSettings.maxSections)
        if let arr = try? c.decodeIfPresent([Section].self, forKey: .sections) {
            for (i, so) in arr.enumerated() {
                let number = so.section == 0 ? i + 1 : so.section
                guard number >= 1, number <= ScheduleSettings.maxSections else { continue }
                let idx = number - 1
                guard idx < sections.count else { continue }
                sections[idx].durationMinutes = ScheduleSettings.clamp(so.durationMinutes, 1, 300)
                sections[idx].breakAfterMinutes = ScheduleSettings.clamp(so.breakAfterMinutes, 0, 600)
            }
        }
    }

    mutating func resetToDefaults() {
        firstClassStart = ScheduleSettings.defaultFirstClassStart
        sectionCount = ScheduleSettings.defaultSectionCount
        sections = (0..<ScheduleSettings.maxSections).map { i in
            Section(i + 1,
                    ScheduleSettings.defaultDurations[i],
                    ScheduleSettings.defaultBreaks[i])
        }
    }

    /// 保证 sections 数组长度正确（解码后可能不足）。
    private mutating func normalizeSections() {
        while sections.count < ScheduleSettings.maxSections {
            let i = sections.count
            sections.append(Section(i + 1,
                                    ScheduleSettings.defaultDurations[i],
                                    ScheduleSettings.defaultBreaks[i]))
        }
        if sections.count > ScheduleSettings.maxSections {
            sections = Array(sections.prefix(ScheduleSettings.maxSections))
        }
        for i in sections.indices {
            sections[i].section = i + 1
            sections[i].durationMinutes = ScheduleSettings.clamp(sections[i].durationMinutes, 1, 300)
            sections[i].breakAfterMinutes = ScheduleSettings.clamp(sections[i].breakAfterMinutes, 0, 600)
        }
    }

    func section(_ number: Int) -> Section {
        let idx = max(0, min(ScheduleSettings.maxSections - 1, number - 1))
        if idx < sections.count { return sections[idx] }
        return Section(number, 45, 10)
    }

    func startMinutes(_ sectionNumber: Int) -> Int {
        var first = ScheduleSettings.parseHm(firstClassStart)
        if first < 0 { first = ScheduleSettings.parseHm(ScheduleSettings.defaultFirstClassStart) }
        var minutes = first
        guard sectionNumber > 1 else { return minutes }
        for i in 1..<sectionNumber {
            let s = section(i)
            minutes += max(1, s.durationMinutes)
            minutes += max(0, s.breakAfterMinutes)
        }
        return minutes
    }

    func endMinutes(_ sectionNumber: Int) -> Int {
        startMinutes(sectionNumber) + max(1, section(sectionNumber).durationMinutes)
    }

    func startTime(_ sectionNumber: Int) -> String {
        Session.formatHm(startMinutes(sectionNumber))
    }

    func endTime(_ sectionNumber: Int) -> String {
        Session.formatHm(endMinutes(sectionNumber))
    }

    func rangeLabel(_ sectionNumber: Int) -> String {
        startTime(sectionNumber) + "-" + endTime(sectionNumber)
    }

    var clampedSectionCount: Int {
        ScheduleSettings.clamp(sectionCount, ScheduleSettings.minSections, ScheduleSettings.maxSections)
    }

    /// 生成「每节开始/结束分钟」数组（下标 = 节次），供 DayPlan 使用。
    /// 这是 iOS 侧对 `DayPlan.of(t, start, ymd, ss, se)` 的适配，
    /// 数组长度 = maxSections + 1，下标 0 不用。
    func minuteRanges() -> (start: [Int], end: [Int]) {
        let n = ScheduleSettings.maxSections
        var ss = [Int](repeating: 0, count: n + 1)
        var se = [Int](repeating: 0, count: n + 1)
        for i in 1...n {
            ss[i] = startMinutes(i)
            se[i] = endMinutes(i)
        }
        return (ss, se)
    }

    // MARK: - 静态工具

    static func parseHm(_ hm: String?) -> Int {
        Session.minutesOf(hm)
    }

    static func formatHm(_ minutes: Int) -> String {
        let day = 24 * 60
        var m = minutes % day
        if m < 0 { m += day }
        return String(format: "%02d:%02d", m / 60, m % 60)
    }

    static func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int {
        max(lo, min(hi, v))
    }
}