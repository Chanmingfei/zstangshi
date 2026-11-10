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

    @State private var web: WebTarget?
    @State private var feature: JwApi.Kind?
    @State private var showCalendar = false

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
        .sheet(item: $feature) { kind in
            GradesView(kind: kind)
                .environmentObject(app)
        }
        .sheet(isPresented: $showCalendar) {
            CalendarSheet()
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
                        cell("calendar", "我的课表") { app.switchTab(1) }
                        cell("chart.bar.fill", "成绩") { feature = .grades }
                        cell("chart.line.uptrend.xyaxis", "学分绩点") { feature = .gpa }
                        cell("calendar.badge.clock", "校历") { showCalendar = true }
                    }
                    HStack(spacing: 0) {
                        cell("checkmark.circle", "选课") { openWeb(JwClient.baseURL, "自主选课") }
                        cell("sparkles", "推荐课表") { openWeb(JwClient.baseURL, "推荐课表") }
                        cell("ellipsis.circle", "更多功能") { openWeb(JwClient.baseURL, "教务系统") }
                        cell("building.columns", "学工系统") { openWeb(JwClient.affairsURL, "学工系统") }
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

    private func cell(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 19))
                    .foregroundStyle(T.brand)
                    .frame(width: 44, height: 44)
                    .background(T.brandSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                Text(label)
                    .font(.system(size: 11.5))
                    .foregroundStyle(T.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
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
