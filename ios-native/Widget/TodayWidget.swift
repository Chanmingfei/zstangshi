//
//  TodayWidget.swift
//  TodayWidgetExtension
//
//  桌面小组件（WidgetKit）—— v4.0.0。
//
//  **独立 target 自带 @main**，与主 App 完全不共享源码：
//    · 不 import 主 App 的 Course / DayPlan / Theme 等模型，只读一份极简 JSON；
//    · 数据源 = App Group `group.com.tstc.zstangshi` 里的 `widget_today_courses`
//      （JSON 字符串，数组元素字段：name / time / place / colorIndex / done），
//      由主 App 的 WidgetBridge.publish() 写入；`widget_today_updated` 是时间戳；
//    · App 与小组件靠 Resources/App.entitlements 里的 App Group 授权互通。
//
//  不使用任何 iOS 26 专有 API（widget 本身不画玻璃，用系统语义色保证深浅色可读）。
//  最低 iOS 17（与主 App 一致）。
//

import Foundation
import UIKit
import SwiftUI
import WidgetKit

// MARK: - 数据模型（自包含）
//
// 字段名 / CodingKeys 必须与主 App `Sources/Bus/BusStore.swift` 的 WidgetCourse 一致，
// 否则 JSONDecoder 解不出数据（该结构用默认 coding keys，即属性名本身）。

private struct CourseItem: Codable, Identifiable, Equatable {
    var name: String
    var time: String
    var place: String
    var colorIndex: Int
    var done: Bool

    var id: String { "\(name)|\(time)|\(place)|\(colorIndex)|\(done)" }
}

private enum WidgetData {
    static let groupId = "group.com.tstc.zstangshi"
    static let coursesKey = "widget_today_courses"
    static let updatedKey = "widget_today_updated"

    struct Snapshot {
        let items: [CourseItem]
        let updated: Date?
    }

    static func load() -> Snapshot {
        let defaults = UserDefaults(suiteName: groupId) ?? .standard
        var items: [CourseItem] = []
        if let json = defaults.string(forKey: coursesKey),
           let data = json.data(using: .utf8),
           let decoded = try? JSONDecoder().decode([CourseItem].self, from: data) {
            items = decoded
        }
        let t = defaults.double(forKey: updatedKey)
        let updated: Date? = t > 0 ? Date(timeIntervalSince1970: t) : nil
        return Snapshot(items: items, updated: updated)
    }

    /// 小组件库（Add Widget）占位用，避免空白卡片。
    static let sample: [CourseItem] = [
        CourseItem(name: "高等数学", time: "08:00-09:40", place: "东校区 3-518", colorIndex: 0, done: false),
        CourseItem(name: "大学英语", time: "10:00-11:40", place: "西校区 A-203", colorIndex: 2, done: false),
        CourseItem(name: "程序设计", time: "14:00-15:40", place: "信息楼 502", colorIndex: 4, done: true)
    ]
}

// MARK: - 颜色（自包含）
//
// 镜像主 App T.course 的 8 色板（浅色 / 深色各一套），保证深浅色背景上都可读。
// 文字用系统语义色，跟随深浅色自动反相。

private enum Ink {
    static let primary = Color(uiColor: .label)
    static let secondary = Color(uiColor: .secondaryLabel)
    static let background = Color(uiColor: .systemBackground)
}

private enum Palette {
    static let light: [UInt32] = [
        0x3B7BE8, 0xE8734A, 0x3FA37A, 0xB06BDA,
        0xD9A21B, 0x4FA8C7, 0xC9527A, 0x6E7FA8
    ]
    static let dark: [UInt32] = [
        0x5B96F5, 0xF08C68, 0x55C092, 0xC48CE8,
        0xE8B743, 0x6FC0DA, 0xDB7196, 0x8C9BC0
    ]

    static func course(_ index: Int) -> Color {
        let i = abs(index) % light.count
        return Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(rgb: dark[i])
                : UIColor(rgb: light[i])
        })
    }
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}

// MARK: - Timeline

private struct TodayEntry: TimelineEntry {
    let date: Date
    let items: [CourseItem]
    let updated: Date?
}

private struct Provider: TimelineProvider {
    typealias Entry = TodayEntry

    func placeholder(in context: TimelineProviderContext) -> TodayEntry {
        TodayEntry(date: Date(), items: WidgetData.sample, updated: nil)
    }

    func getSnapshot(in context: TimelineProviderContext,
                     completion: @escaping (TodayEntry) -> Void) {
        completion(Self.currentEntry())
    }

    func getTimeline(in context: TimelineProviderContext,
                     completion: @escaping (Timeline<TodayEntry>) -> Void) {
        let entry = Self.currentEntry()
        // 30 分钟后再取一次：即使主 App 没有显式 reloadAllTimelines()，
        // 小组件也能定期同步到最新课表（主 App 写入后主动 reload 会立即刷新）。
        let refresh = Calendar.current.date(byAdding: .minute, value: 30, to: Date())
            ?? Date().addingTimeInterval(30 * 60)
        completion(Timeline(entries: [entry], policy: .after(refresh)))
    }

    private static func currentEntry() -> TodayEntry {
        let snap = WidgetData.load()
        return TodayEntry(date: Date(), items: snap.items, updated: snap.updated)
    }
}

// MARK: - 「下一节 / 正在上」判定
//
// time 常见格式 "08:00-09:40"（HH:MM-HH:MM）；若是 "第3-4节" 之类无法解析，
// 统一按「下一节」处理，绝不因解析失败而崩溃。

private enum CourseStatus {
    static func label(for item: CourseItem, now: Date) -> String {
        let parts = item.time.split(separator: "-")
        guard parts.count == 2,
              let start = minutes(parts[0]),
              let end = minutes(parts[1]),
              end > start else { return "下一节" }
        let c = Calendar.current.dateComponents([.hour, .minute], from: now)
        let nowMin = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        return (start <= nowMin && nowMin < end) ? "正在上" : "下一节"
    }

    private static func minutes(_ token: Substring) -> Int? {
        let p = token.split(separator: ":")
        guard p.count == 2,
              let h = Int(p[0]), let m = Int(p[1]),
              (0..<24).contains(h), (0..<60).contains(m) else { return nil }
        return h * 60 + m
    }
}

// MARK: - 根视图

private struct TodayWidgetRootView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TodayEntry

    var body: some View {
        Group {
            if entry.items.isEmpty {
                EmptyCoursesView()
            } else {
                switch family {
                case .systemMedium:
                    MediumView(entry: entry)
                default:
                    SmallView(entry: entry)
                }
            }
        }
        .containerBackground(Ink.background, for: .widget)
    }
}

/// 无数据占位：还没有课表 / 到 App 里同步一次
private struct EmptyCoursesView: View {
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "calendar.badge.exclamationmark")
                .font(.title2)
                .foregroundStyle(Ink.secondary)
            Text("还没有课表")
                .font(.headline)
                .foregroundStyle(Ink.primary)
            Text("到 App 里同步一次")
                .font(.caption)
                .foregroundStyle(Ink.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .multilineTextAlignment(.center)
    }
}

/// systemSmall：下一节 / 正在上的那一门课 + 时间 + 地点
private struct SmallView: View {
    let entry: TodayEntry

    /// 主 App 按开始时间升序写入；done=false 的第一节即「当前 / 接下来」那门。
    private var highlight: CourseItem? { entry.items.first { !$0.done } }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("今日课程", systemImage: "calendar")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(Ink.secondary)

            Spacer(minLength: 2)

            if let item = highlight {
                HStack(alignment: .top, spacing: 8) {
                    RoundedRectangle(cornerRadius: 2, style: .continuous)
                        .fill(Palette.course(item.colorIndex))
                        .frame(width: 4, height: 72)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(CourseStatus.label(for: item, now: entry.date))
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(Palette.course(item.colorIndex))
                        Text(item.name)
                            .font(.headline)
                            .foregroundStyle(Ink.primary)
                            .lineLimit(2)
                            .minimumScaleFactor(0.75)
                        Text(item.time)
                            .font(.caption)
                            .foregroundStyle(Ink.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        if !item.place.isEmpty {
                            Label(item.place, systemImage: "mappin")
                                .font(.caption2)
                                .foregroundStyle(Ink.secondary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                        }
                    }
                }
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(Ink.secondary)
                    Text("今日课程已全部结束")
                        .font(.subheadline)
                        .foregroundStyle(Ink.secondary)
                }
            }

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// systemMedium：今日课程列表，最多 3 条
private struct MediumView: View {
    let entry: TodayEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("今日课程", systemImage: "calendar")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Ink.primary)
                Spacer()
                if let updated = entry.updated {
                    (Text("更新于 ") + Text(updated, style: .time))
                        .font(.caption2)
                        .foregroundStyle(Ink.secondary)
                }
            }

            VStack(spacing: 7) {
                ForEach(Array(entry.items.prefix(3))) { item in
                    HStack(spacing: 9) {
                        RoundedRectangle(cornerRadius: 2, style: .continuous)
                            .fill(Palette.course(item.colorIndex))
                            .frame(width: 3, height: 30)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(item.done ? Ink.secondary : Ink.primary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                            HStack(spacing: 10) {
                                Label(item.time, systemImage: "clock")
                                if !item.place.isEmpty {
                                    Label(item.place, systemImage: "mappin")
                                }
                            }
                            .font(.caption2)
                            .foregroundStyle(Ink.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                        }
                        Spacer(minLength: 4)
                        if item.done {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.caption)
                                .foregroundStyle(Ink.secondary)
                        }
                    }
                    .opacity(item.done ? 0.65 : 1)
                }
            }

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

// MARK: - 入口
//
// 独立 @main。不要再套一层 WidgetBundle 的 @main —— 两个 @main 会冲突。

@main
struct TodayWidget: Widget {
    let kind: String = "TodayWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: Provider()) { entry in
            TodayWidgetRootView(entry: entry)
        }
        .configurationDisplayName("今日课程")
        .description("一眼看到下一节课，以及今天要上的课。")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
