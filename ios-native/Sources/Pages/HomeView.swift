import SwiftUI

/// 首页：今天还有什么课。
///
/// 内容与 Android `HomePage` 一一对应：
///   1. 顶部一句「早上好 / 今天周二 · 11 月 10 日 · 第 10 周」；
///   2. 一张「正在上 / 下一节」的大卡（课名、时间、教室、还有多久）；
///   3. 今日全部课程，上完的置灰；
///   4. 常用功能宫格 + 学工入口 + 页脚校园线稿。
///
/// 数据全部来自本地课表，断网也能用。下拉刷新只核对登录态（会话被踢就自动重登），
/// **不动课表** —— 课表以「我的 → 同步课表」为准，避免把用户手改的内容冲掉。
struct HomeView: View {
    @EnvironmentObject private var app: AppState

    /// 需要 WebView 的入口仍走 sheet（`WebScreen` 自带 NavigationStack，
    /// 套进外层 NavigationStack 会形成嵌套导航栈，这是 iOS 平台的正确切分）。
    @State private var web: WebTarget?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                todaySection
                shortcuts
            }
            .padding(.horizontal, T.pagePadding)
            .padding(.top, 14)
            .padding(.bottom, 24)
        }
        .background(T.bgPage)
        .refreshable { await app.refreshHome() }
        .sheet(item: $web) { target in
            WebScreen(url: target.url, title: target.title)
                .environmentObject(app)
        }
    }

    // MARK: - 顶部

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(greeting())
                .font(F.title)
                .foregroundStyle(T.textPrimary)
            Text(subtitle())
                .font(.system(size: 13))
                .foregroundStyle(T.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
        .padding(.bottom, 12)
    }

    private func greeting() -> String {
        let hour = Calendar.current.component(.hour, from: Date())
        if hour < 6 { return "夜深了" }
        if hour < 11 { return "早上好" }
        if hour < 14 { return "中午好" }
        if hour < 18 { return "下午好" }
        return "晚上好"
    }

    private func subtitle() -> String {
        let today = WeekMath.today()
        let dayNames = ["一", "二", "三", "四", "五", "六", "日"]
        let idx = max(0, min(6, WeekMath.weekday(of: today) - 1))
        var text = "今天周\(dayNames[idx]) · \(shortDate(today))"
        if let t = app.timetable, !t.semester.startDate.isEmpty {
            let w = WeekMath.currentWeek(start: t.semester.startDate, totalWeeks: t.semester.totalWeeks)
            text += " · 第 \(w) 周"
        }
        return text
    }

    private func shortDate(_ ymd: String) -> String {
        let parts = ymd.split(separator: "-")
        guard parts.count == 3, let m = Int(parts[1]), let d = Int(parts[2]) else { return ymd }
        return "\(m) 月 \(d) 日"
    }

    // MARK: - 今日课程

    @ViewBuilder
    private var todaySection: some View {
        let items = app.todayItems()
        let now = DayPlan.nowMinute()

        if app.timetable == nil || app.timetable?.isEmpty == true {
            EmptyCard(title: "还没有课表", subtitle: "到「我的」里登录教务同步一次，之后没网也能看")
                .padding(.bottom, 10)
        } else if items.isEmpty {
            EmptyCard(title: "今天没课", subtitle: "好好休息，或者预习一下明天的")
                .padding(.bottom, 10)
        } else {
            let current = DayPlan.current(items, nowMin: now)
            let next = DayPlan.next(items, nowMin: now)
            if let focus = current ?? next {
                focusCard(focus, ongoing: current != nil, nowMin: now)
                    .padding(.bottom, 14)
            }

            Text("今天的课 · \(items.count) 门")
                .font(F.sectionTitle)
                .foregroundStyle(T.textTertiary)
                .padding(.horizontal, 4)
                .padding(.top, 4)
                .padding(.bottom, 8)

            VStack(spacing: 8) {
                ForEach(items) { item in
                    courseRow(item, nowMin: now)
                }
            }
        }
    }

    private func focusCard(_ item: DayPlan.Item, ongoing: Bool, nowMin: Int) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(ongoing ? "正在上" : "下一节")
                .font(.system(size: 11.5, weight: .bold))
                .foregroundStyle(T.brand)

            Text(item.name)
                .font(F.cardTitle)
                .foregroundStyle(T.textPrimary)
                .padding(.top, 8)

            Text(item.timeLabel + (item.placeLabel.isEmpty ? "" : " · \(item.placeLabel)"))
                .font(F.sub)
                .foregroundStyle(T.textSecondary)
                .padding(.top, 8)

            Text(ongoing
                 ? "还剩 \(max(0, item.endMin - nowMin)) 分钟下课"
                 : "\(max(0, item.startMin - nowMin)) 分钟后上课")
                .font(F.sub)
                .foregroundStyle(T.brand)
                .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .stroke(T.border, lineWidth: 0.8)
        )
    }

    private func courseRow(_ item: DayPlan.Item, nowMin: Int) -> some View {
        let done = item.endMin <= nowMin
        return HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                .fill(T.courseColor(item.colorIndex))
                .frame(width: 3, height: 30)

            VStack(alignment: .leading, spacing: 3) {
                Text(item.name)
                    .font(done ? F.body : F.bodyBold)
                    .foregroundStyle(done ? T.textTertiary : T.textPrimary)
                    .lineLimit(2)
                Text(item.timeLabel + (item.placeLabel.isEmpty ? "" : "  \(item.placeLabel)"))
                    .font(F.caption)
                    .foregroundStyle(T.textTertiary)
            }
            Spacer(minLength: 6)
            Text(done ? "已上完" : (item.startMin <= nowMin ? "进行中" : ""))
                .font(.system(size: 11))
                .foregroundStyle(T.textTertiary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .stroke(T.border, lineWidth: 0.8)
        )
    }

    // MARK: - 宫格 / 侧门 / 页脚

    private var shortcuts: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("常用功能")
                .font(F.sectionTitle)
                .foregroundStyle(T.textTertiary)
                .padding(.horizontal, 4)
                .padding(.top, 18)
                .padding(.bottom, 10)

            Card(padding: 0) {
                VStack(spacing: 6) {
                    HStack(spacing: 0) {
                        actionCell("calendar", "我的课表") { app.switchTab(1) }
                        actionCell("bus.fill", "实时公交") { app.switchTab(2) }
                        linkCell("chart.bar.fill", "成绩") {
                            GradesContent(kind: .grades)
                                .navigationTitle("成绩")
                                .navigationBarTitleDisplayMode(.inline)
                        }
                        linkCell("chart.line.uptrend.xyaxis", "学分绩点") {
                            GradesContent(kind: .gpa)
                                .navigationTitle("学分绩点")
                                .navigationBarTitleDisplayMode(.inline)
                        }
                    }
                    HStack(spacing: 0) {
                        linkCell("calendar.badge.clock", "校历") { CalendarPageView() }
                        linkCell("list.bullet.rectangle", "教务服务") { JwServicesView() }
                        actionCell("ellipsis.circle", "更多功能") { openWeb(JwClient.baseURL, "教务系统") }
                        actionCell("building.columns", "学工系统") { openWeb(JwClient.affairsURL, "学工系统") }
                    }
                }
                .padding(.horizontal, 7)
                .padding(.top, 14)
                .padding(.bottom, 8)
            }

            affairsCard
                .padding(.top, 12)

            // 页脚校园线稿：不着卡片、直接压在页面背景上，用品牌色染色（深浅色共用一张图）
            Image("CampusArt")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .foregroundStyle(T.brand.opacity(0.9))
                .padding(.top, 20)
                .accessibilityLabel("唐山师范学院校园")
        }
    }

    /// 宫格统一外观。三种入口（push 二级页 / 切标签 / 弹 Web 页）共用，
    /// 保证图标尺寸、点击目标、Dynamic Type 行为完全一致。
    private func cellBody(_ icon: String, _ label: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 19))
                .foregroundStyle(T.brand)
                .frame(width: T.minTap, height: T.minTap)
                .background(T.brandSoft)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            Text(label)
                .font(F.caption2)
                .foregroundStyle(T.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }

    private func actionCell(_ icon: String, _ label: String,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) { cellBody(icon, label) }
            .buttonStyle(.plain)
    }

    private func linkCell<D: View>(_ icon: String, _ label: String,
                                   @ViewBuilder destination: () -> D) -> some View {
        NavigationLink(destination: destination) { cellBody(icon, label) }
            .buttonStyle(.plain)
    }

    private var affairsCard: some View {
        Button {
            openWeb(JwClient.affairsURL, "学工系统")
        } label: {
            HStack(spacing: 12) {
                Text("↗")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(T.brand)
                    .frame(width: 36, height: 36)
                    .background(T.brandSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text("登录学工系统")
                        .font(F.bodyBold)
                        .foregroundStyle(T.textPrimary)
                    Text("奖助贷、请假、综合素质 · 外部网页")
                        .font(.system(size: 11))
                        .foregroundStyle(T.textTertiary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(T.textTertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(T.bgSurface)
            .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                    .stroke(T.border, lineWidth: 0.8)
            )
        }
        .buttonStyle(.plain)
    }

    private func openWeb(_ url: String, _ title: String) {
        web = WebTarget(url: url, title: title)
    }
}

/// 应用内浏览器的目标（对应 Android `WebViewActivity.open(host, url, title)`）
struct WebTarget: Identifiable {
    let id = UUID()
    let url: String
    let title: String
}
