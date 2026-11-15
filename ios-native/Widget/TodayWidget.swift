import WidgetKit
import SwiftUI

// MARK: - 「今日课程」桌面小组件
//
// 对齐 Android `widget/TodayWidget.kt` + `WidgetData.kt` 的口径：
//   · 只显示「正在上」「下一节 14:00」这类在区间内恒成立的文案 ——
//     **没有会过期的倒计时**，所以闹钟晚一两分钟也不会显示错；
//   · 主 App 通过 App Group 写一份极简 JSON，小组件只读这份，不联网、不读 Keychain；
//   · 30 分钟兜底刷新。
//
// 这个 target 是**独立可执行体**，自带 @main，绝不能混进主 App 的 Compile Sources。

// MARK: - 共享模型（与主 App 的 WidgetCourse 字段一致，但各自定义、不共享源码）

struct WidgetCourse: Codable, Identifiable {
    var id: String { name + time + place }
    var name: String
    var time: String
    var place: String
    var colorIndex: Int
    var done: Bool
    var startMin: Int
    var endMin: Int
}

/// 小组件的 5 种状态（对齐 Android `WidgetDay.kt:9-14`）
enum WidgetState {
    /// 没有任何本地课表
    case noTimetable
    /// 有课表但没设开学日期
    case noStart
    /// 开学前
    case before(Int)          // 距开学天数
    /// 学期已结束
    case after
    /// 正常学期内
    case ok
}

// MARK: - 读取

enum WidgetDataStore {
    static let appGroup = "group.com.tstc.zstangshi"

    static var defaults: UserDefaults {
        UserDefaults(suiteName: appGroup) ?? .standard
    }

    static func courses() -> [WidgetCourse] {
        guard let json = defaults.string(forKey: "widget_today_courses"),
              let data = json.data(using: .utf8) else { return [] }
        return (try? JSONDecoder().decode([WidgetCourse].self, from: data)) ?? []
    }

    /// 主 App 写入的状态信息（开学日期、学期名、总周数）
    static func startDate() -> String {
        defaults.string(forKey: "widget_start_date") ?? ""
    }

    static func semesterName() -> String {
        defaults.string(forKey: "widget_semester_name") ?? ""
    }

    static func totalWeeks() -> Int {
        let n = defaults.integer(forKey: "widget_total_weeks")
        return n > 0 ? n : 20
    }

    static func hasTimetable() -> Bool {
        defaults.bool(forKey: "widget_has_timetable")
    }

    static var updatedAt: Date? {
        let t = defaults.double(forKey: "widget_today_updated")
        return t > 0 ? Date(timeIntervalSince1970: t) : nil
    }
}

// MARK: - 时间与文案

enum WidgetText {
    static func hm(_ minutes: Int) -> String {
        String(format: "%02d:%02d", minutes / 60, minutes % 60)
    }

    static func nowMinute() -> Int {
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }

    /// 当前状态：正在上 / 下一节 / 今天的课都上完了 / 今天没课
    static func status(_ courses: [WidgetCourse]) -> (title: String, item: WidgetCourse?) {
        let now = nowMinute()
        if courses.isEmpty { return ("今天没课", nil) }
        if let cur = courses.first(where: { $0.startMin <= now && now < $0.endMin }) {
            return ("正在上", cur)
        }
        if let next = courses.first(where: { $0.startMin > now }) {
            return ("下一节", next)
        }
        return ("今天的课都上完了", nil)
    }

    /// 把日期字符串（yyyy-MM-dd）算成纪元日
    static func epochDay(_ ymd: String) -> Int {
        let parts = ymd.split(separator: "-")
        guard parts.count == 3,
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]) else {
            return Int.min
        }
        var c = DateComponents()
        c.year = y; c.month = m; c.day = d
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        guard let date = cal.date(from: c) else { return Int.min }
        return Int(floor(date.timeIntervalSince1970 / 86_400.0))
    }

    static func todayYmd() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date())
    }

    /// 判定当前处于 5 种状态的哪一种
    static func resolveState() -> WidgetState {
        guard WidgetDataStore.hasTimetable() else { return .noTimetable }
        let start = WidgetDataStore.startDate()
        guard epochDay(start) != Int.min else { return .noStart }

        let today = epochDay(todayYmd())
        let s = epochDay(start)
        guard today != Int.min, s != Int.min else { return .noStart }

        let days = today - s
        if days < 0 { return .before(-days / 7 + 1) }
        let week = days / 7 + 1
        if week > WidgetDataStore.totalWeeks() { return .after }
        return .ok
    }

    /// 第几周
    static func currentWeek() -> Int {
        let s = epochDay(WidgetDataStore.startDate())
        let t = epochDay(todayYmd())
        guard s != Int.min, t != Int.min else { return 1 }
        let days = t - s
        if days < 0 { return 1 }
        return max(1, min(WidgetDataStore.totalWeeks(), days / 7 + 1))
    }
}

// MARK: - 颜色（8 色板，浅/深各一套，与主 App 一致）

enum WidgetPalette {
    static let colors: [Color] = [
        Color(red: 0x3B/255, green: 0x7B/255, blue: 0xE8/255),
        Color(red: 0xE8/255, green: 0x73/255, blue: 0x4A/255),
        Color(red: 0x3F/255, green: 0xA3/255, blue: 0x7A/255),
        Color(red: 0xB0/255, green: 0x6B/255, blue: 0xDA/255),
        Color(red: 0xD9/255, green: 0xA2/255, blue: 0x1B/255),
        Color(red: 0x4F/255, green: 0xA8/255, blue: 0xC7/255),
        Color(red: 0xC9/255, green: 0x52/255, blue: 0x7A/255),
        Color(red: 0x6E/255, green: 0x7F/255, blue: 0xA8/255)
    ]

    static func of(_ index: Int) -> Color {
        colors[abs(index) % colors.count]
    }
}

// MARK: - Timeline

struct ZSTEntry: TimelineEntry {
    let date: Date
    let courses: [WidgetCourse]
}

struct ZSTProvider: TimelineProvider {
    func placeholder(in context: Context) -> ZSTEntry {
        ZSTEntry(date: Date(), courses: [
            WidgetCourse(name: "高等数学", time: "08:00-09:35", place: "东校区 3-518",
                         colorIndex: 0, done: false, startMin: 480, endMin: 575),
            WidgetCourse(name: "大学英语", time: "10:45-11:30", place: "西区 2-201",
                         colorIndex: 2, done: false, startMin: 645, endMin: 690)
        ])
    }

    func getSnapshot(in context: Context, completion: @escaping (ZSTEntry) -> Void) {
        completion(ZSTEntry(date: Date(), courses: WidgetDataStore.courses()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ZSTEntry>) -> Void) {
        let entry = ZSTEntry(date: Date(), courses: WidgetDataStore.courses())
        // 30 分钟兜底；主 App 切课时会主动 reloadAllTimelines
        let next = Calendar.current.date(byAdding: .minute, value: 30, to: Date()) ?? Date().addingTimeInterval(1800)
        completion(Timeline(entries: [entry], policy: .after(next)))
    }
}

// MARK: - 视图

@main
struct TodayWidget: Widget {
    let kind = "TodayWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: ZSTProvider()) { entry in
            TodayWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("今日课程")
        .description("在桌面显示今天的课程与下一节课")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct TodayWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: ZSTEntry

    private var state: WidgetState { WidgetText.resolveState() }
    private var status: (title: String, item: WidgetCourse?) {
        WidgetText.status(entry.courses)
    }

    var body: some View {
        switch family {
        case .systemMedium:
            medium
        default:
            small
        }
    }

    // MARK: 状态兜底（非 OK 态时不显示课程）
    @ViewBuilder
    private var statePlaceholder: some View {
        switch state {
        case .noTimetable:
            placeholder(title: "还没有课表", sub: "打开掌上唐师同步一次")
        case .noStart:
            placeholder(title: "还没设置开学日期", sub: "到「我的 → 学期与教学周」设置")
        case .before(let weeks):
            placeholder(title: "还没开学", sub: "还有约 \(weeks) 周开课")
        case .after:
            placeholder(title: "本学期已结束", sub: "新学期同步后自动更新")
        case .ok:
            EmptyView()
        }
    }

    private func placeholder(title: String, sub: String) -> some View {
        VStack(spacing: 3) {
            Spacer()
            Text(title)
                .font(.caption.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(sub)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: 小尺寸：状态 + 一节课
    private var small: some View {
        VStack(alignment: .leading, spacing: 4) {
            if case .ok = state {
                Text(status.title)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(status.item == nil
                                     ? Color.secondary
                                     : WidgetPalette.of(status.item!.colorIndex))

                if let item = status.item {
                    Text(item.name)
                        .font(.subheadline.weight(.bold))
                        .lineLimit(2)
                        .minimumScaleFactor(0.8)

                    Text(item.time)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)

                    if !item.place.isEmpty {
                        Text(item.place)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                } else if entry.courses.isEmpty {
                    Text("今天没课")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text("共 \(entry.courses.count) 门课")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            } else {
                statePlaceholder
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    // MARK: 中尺寸：今日课程列表（最多 3 条）
    private var medium: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("今日课程")
                    .font(.caption.weight(.bold))
                Spacer()
                if case .ok = state {
                    Text("第 \(WidgetText.currentWeek()) 周 · \(entry.courses.count) 门")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            if case .ok = state {
                if entry.courses.isEmpty {
                    Spacer()
                    HStack {
                        Spacer()
                        VStack(spacing: 2) {
                            Text("今天没课")
                                .font(.caption.weight(.semibold))
                            Text("好好休息")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    Spacer()
                } else {
                    ForEach(entry.courses.prefix(3)) { c in
                        HStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: 1.5)
                                .fill(WidgetPalette.of(c.colorIndex))
                                .frame(width: 3, height: 20)

                            VStack(alignment: .leading, spacing: 0) {
                                Text(c.name)
                                    .font(.caption.weight(.semibold))
                                    .lineLimit(1)
                                    .foregroundStyle(c.done ? Color.secondary : Color.primary)
                                Text(c.time + (c.place.isEmpty ? "" : " · " + c.place))
                                    .font(.system(size: 9))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            Spacer(minLength: 0)
                        }
                    }
                    if entry.courses.count > 3 {
                        Text("还有 \(entry.courses.count - 3) 门")
                            .font(.system(size: 9))
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
            } else {
                statePlaceholder
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}