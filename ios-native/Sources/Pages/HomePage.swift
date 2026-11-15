import SwiftUI
import UIKit

/// 首页：今天还有什么课。
///
/// 与安卓 `pages/HomePage.kt` 的三块内容完全一致：
///   1. 顶部问候 + 「今天周二 · 11 月 10 日 · 第 10 周」；
///   2. 一张「正在上 / 下一节」的大卡（课名、时间、教室、还有多久）；
///   3. 今日全部课程，已上完的置灰；
///   4. 「常用功能」两行宫格（8 格）+ 学工系统横条 + 校园线稿。
///
/// 宫格 8 格顺序照抄 HomePage.kt:237-256：
///   第一行：我的课表 / 成绩 / 学分绩点 / 校历
///   第二行：选课 / 推荐课表 / **实时公交** / 更多功能
///
/// ⚠️ 关键：实时公交在这里是**宫格里的功能**，不是一级 tab。
/// 上一版 iOS 把它提升成了第 5 个 tab，既改变了首页布局，也破坏了 4 项底栏。
struct HomePage: View {
    @EnvironmentObject private var app: AppState
    let width: CGFloat

    @State private var route: HomeRoute?
    @State private var refreshing = false

    /// 首页宫格/横条能打开的二级页
    enum HomeRoute: Hashable, Identifiable {
        case bus
        case calendar
        case grades
        case gpa
        case selection
        case recommend
        case web(WebTarget)

        var id: String {
            switch self {
            case .bus: return "bus"
            case .calendar: return "calendar"
            case .grades: return "grades"
            case .gpa: return "gpa"
            case .selection: return "selection"
            case .recommend: return "recommend"
            case .web(let t): return "web-\(t.url)"
            }
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header

                    if let t = app.timetable, !t.courses.isEmpty {
                        todaySection(t)
                    } else {
                        EmptyCard(title: "还没有课表",
                                  subtitle: "到「我的」里登录教务同步一次")
                            .padding(.top, 2)
                    }

                    // 不管今天有没有课，宫格和学工入口都得在
                    shortcuts
                }
                .padding(.horizontal, T.pagePadding)
                .padding(.top, 8)
                // 内容能从胶囊底下穿过去：padding 留在底部，最后一项也不会被挡
                .padding(.bottom, T.dockBand + 24)
            }
            .scrollIndicators(.hidden)
            .background(T.bgPage.ignoresSafeArea())
            .zstRefreshable { await app.refreshHome() }
            .navigationDestination(for: HomeRoute.self) { r in
                destination(r)
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .onAppear { app.reloadFromDisk() }
        .onReceive(NotificationCenter.default.publisher(for: .zstOpenBus)) { _ in
            route = .bus
        }
    }

    // MARK: - 顶部

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(greeting)
                .font(F.hero)
                .foregroundStyle(T.textPrimary)

            Text(subline)
                .font(F.footnote)
                .foregroundStyle(T.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
        .padding(.bottom, 12)
    }

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        if h < 6 { return "夜深了" }
        if h < 11 { return "早上好" }
        if h < 14 { return "中午好" }
        if h < 18 { return "下午好" }
        return "晚上好"
    }

    private var subline: String {
        let today = Dates.todayLocal()
        let dow = Dates.dayOfWeek(today)
        let dayNames = ["一", "二", "三", "四", "五", "六", "日"]
        let weekPart = dayNames[max(0, min(6, dow - 1))]
        var s = "今天周\(weekPart) · \(Dates.shortDate(today))"
        if let t = app.timetable, Dates.valid(t.semester.startDate) {
            let w = WeekMath.currentWeek(startDate: t.semester.startDate,
                                         totalWeeks: t.semester.totalWeeks)
            if w >= 1 { s += " · 第 \(w) 周" }
        }
        return s
    }

    // MARK: - 今日课程

    @ViewBuilder
    private func todaySection(_ t: Timetable) -> some View {
        let items = app.todayItems()
        let now = Dates.nowMinute()

        if items.isEmpty {
            EmptyCard(title: "今天没课", subtitle: "好好休息，或者预习一下明天的")
        } else {
            let current = DayPlan.current(items, nowMin: now)
            let next = DayPlan.next(items, nowMin: now)
            if let focus = current ?? next {
                focusCard(focus, ongoing: current != nil, nowMin: now)
            }

            SectionTitle(text: "今天的课 · \(items.count) 门")
                .padding(.horizontal, 4)
                .padding(.top, 6)
                .padding(.bottom, 8)

            VStack(spacing: 8) {
                ForEach(items) { item in
                    courseRow(item, nowMin: now)
                }
            }
        }
    }

    /// 「正在上 / 下一节」大卡
    private func focusCard(_ item: DayPlan.Item, ongoing: Bool, nowMin: Int) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(ongoing ? "正在上" : "下一节")
                .font(F.tinyBold)
                .foregroundStyle(T.brand)

            Text(item.name)
                .font(F.title)
                .foregroundStyle(T.textPrimary)
                .padding(.top, 8)

            Text(item.timeLabel + (item.placeLabel.isEmpty ? "" : " · " + item.placeLabel))
                .font(F.caption)
                .foregroundStyle(T.textSecondary)
                .padding(.top, 8)

            let mins = ongoing ? item.endMin - nowMin : item.startMin - nowMin
            Text(ongoing ? "还剩 \(max(0, mins)) 分钟下课" : "\(max(0, mins)) 分钟后上课")
                .font(F.caption)
                .foregroundStyle(T.brand)
                .padding(.top, 8)

            if !item.tag.isEmpty {
                Text(item.tag)
                    .font(F.micro)
                    .foregroundStyle(T.warn)
                    .padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
        .padding(.bottom, 14)
    }

    /// 今日课程行：左侧课程色条 + 课名/时间 + 右侧状态
    private func courseRow(_ item: DayPlan.Item, nowMin: Int) -> some View {
        let done = item.endMin <= nowMin
        let ongoing = item.startMin <= nowMin && nowMin < item.endMin

        return HStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 1.5)
                .fill(T.courseColor(item.colorIndex))
                .frame(width: 3, height: 30)
                .padding(.trailing, 10)

            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(done ? F.body : F.bodyBold)
                    .foregroundStyle(done ? T.textTertiary : T.textPrimary)
                    .lineLimit(1)
                Text(item.timeLabel + (item.placeLabel.isEmpty ? "" : "  " + item.placeLabel))
                    .font(F.tiny)
                    .foregroundStyle(T.textTertiary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            Text(done ? "已上完" : (ongoing ? "进行中" : ""))
                .font(F.micro)
                .foregroundStyle(ongoing ? T.brand : T.textTertiary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    // MARK: - 常用功能

    private var shortcuts: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionTitle(text: "常用功能")
                .padding(.horizontal, 4)
                .padding(.top, 18)
                .padding(.bottom, 10)

            // 宫格：8 格铺满两行（顺序照抄安卓 HomePage.kt）
            VStack(spacing: 6) {
                // 顺序与图标严格对齐安卓 HomePage.kt:237-255
                //   行1：我的课表(ic_nav_timetable) 成绩(ic_nav_grades) 学分绩点(ic_quick_gpa) 校历(ic_nav_calendar)
                //   行2：选课(ic_quick_select) 推荐课表(ic_quick_recommend) 实时公交(ic_quick_bus) 更多功能(ic_quick_more)
                gridRow([
                    (.timetable, "我的课表", { app.setTab(1, animated: true) }),
                    (.grades, "成绩", { route = .grades }),
                    (.gpa, "学分绩点", { route = .gpa }),
                    (.calendar, "校历", { route = .calendar })
                ])
                gridRow([
                    (.select, "选课", { route = .selection }),
                    (.recommend, "推荐课表", { route = .recommend }),
                    (.bus, "实时公交", { route = .bus }),
                    (.more, "更多功能", { route = .web(.jwxt) })
                ])
            }
            .padding(.horizontal, 7)
            .padding(.top, 14)
            .padding(.bottom, 8)
            .background(T.bgSurface)
            .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                    .strokeBorder(T.borderSubtle, lineWidth: 0.7)
            )

            affairsCard
                .padding(.top, 12)

            campusArt
                .padding(.top, 20)
                .padding(.bottom, 4)
        }
    }

    private func gridRow(_ cells: [(QuickIcon.PathView.Key, String, () -> Void)]) -> some View {
        HStack(spacing: 0) {
            ForEach(cells.indices, id: \.self) { i in
                let (key, label, action) = cells[i]
                Button(action: { Haptics.light(); action() }) {
                    VStack(spacing: 8) {
                        // 用安卓原始矢量路径，而不是 SF Symbols：
                        // 形状/粗细/留白都一致，摆一排才不会显得「和安卓不一样」
                        ZStack {
                            if QuickIcon.PathView.isStroked(key) {
                                QuickIcon.PathView(key: key)
                                    .stroke(T.brand,
                                            style: StrokeStyle(lineWidth: 2 * (20.0 / 24.0),
                                                               lineCap: .round,
                                                               lineJoin: .round))
                                    .frame(width: 20, height: 20)
                            } else {
                                QuickIcon.PathView(key: key)
                                    .fill(T.brand)
                                    .frame(width: 20, height: 20)
                            }
                        }
                        .frame(width: 44, height: 44)
                        .background(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(T.brandSoft)
                        )
                        Text(label)
                            .font(.system(size: F.scaled(11.5)))
                            .foregroundStyle(T.textSecondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.pressable(0.94))
            }
        }
    }

    /// 学工系统横条
    private var affairsCard: some View {
        Button {
            Haptics.light()
            route = .web(.xuegong)
        } label: {
            HStack(spacing: 0) {
                Text("↗")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(T.brand)
                    .frame(width: 36, height: 36)
                    .background(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(T.brandSoft)
                    )

                VStack(alignment: .leading, spacing: 2) {
                    Text("登录学工系统")
                        .font(F.bodyBold)
                        .foregroundStyle(T.textPrimary)
                    Text("奖助贷、请假、综合素质 · 外部网页")
                        .font(F.micro)
                        .foregroundStyle(T.textTertiary)
                }
                .padding(.leading, 12)

                Spacer(minLength: 8)

                Image(systemName: "chevron.right")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(T.textTertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(T.bgSurface)
            .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                    .strokeBorder(T.borderSubtle, lineWidth: 0.7)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressable(0.98))
    }

    /// 页脚校园线稿（资源缺失时自动隐藏，不留空白）
    @ViewBuilder
    private var campusArt: some View {
        if UIImage(named: "CampusArt") != nil {
            // renderingMode 必须紧跟在 Image 之后、resizable 之前，
            // 否则 resizable 会返回 `some View`（Image 的具体类型丢失）
            Image("CampusArt")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .foregroundStyle(T.brand)
                .opacity(0.9)
                .frame(maxWidth: .infinity)
                .accessibilityLabel("唐山师范学院校园")
        }
    }

    // MARK: - 二级页路由

    @ViewBuilder
    private func destination(_ r: HomeRoute) -> some View {
        switch r {
        case .bus:
            BusRootPage()
        case .calendar:
            CalendarPage()
        case .grades:
            FeaturePage(kind: .grades)
        case .gpa:
            FeaturePage(kind: .gpa)
        case .selection:
            FeaturePage(kind: .selection)
        case .recommend:
            RecommendPage()
        case .web(let t):
            WebPage(target: t)
        }
    }
}

// MARK: - 外部网页目标

enum WebTarget: Hashable {
    /// 教务首页（「更多功能」）
    case jwxt
    /// 学工系统
    case xuegong
    case url(String, String)

    var url: String {
        switch self {
        case .jwxt: return "http://jwxt.tstc.edu.cn/xtgl/index_initMenu.html"
        case .xuegong: return "http://sws.tstc.edu.cn/XGTSTC/Phone/#/login"
        case .url(let u, _): return u
        }
    }

    var title: String {
        switch self {
        case .jwxt: return "教务系统"
        case .xuegong: return "学工系统"
        case .url(_, let t): return t
        }
    }
}