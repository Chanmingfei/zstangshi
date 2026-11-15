import SwiftUI

/// 「我的」页（对齐 Android `pages/MinePage.kt`）。
///
/// 五张卡：账号 / 课表设置 / 外观 / 桌面小组件 / 关于。
/// 登录与同步放在这里而不是首屏，是为了兑现「没登录也能看课表」。
struct MinePage: View {
    @EnvironmentObject private var app: AppState
    let width: CGFloat

    @State private var termSheet = false
    @State private var loginSheet = false
    @State private var confirmLogout = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    accountCard
                    settingsCard
                    appearanceCard
                    widgetCard
                    aboutCard
                }
                .padding(.horizontal, T.pagePadding)
                .padding(.top, 12)
                .padding(.bottom, T.dockBand + 24)
            }
            .scrollIndicators(.hidden)
            .background(T.bgPage.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
        }
        .onAppear { app.reloadFromDisk() }
        .sheet(isPresented: $termSheet) {
            TermSheet()
                .environmentObject(app)
        }
        .fullScreenCover(isPresented: $app.showLogin) {
            LoginPage().environmentObject(app)
        }
        .confirmationDialog("确定退出登录？", isPresented: $confirmLogout) {
            Button("退出登录", role: .destructive) { app.logout() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("退出后需要重新输入学号密码，本地课表会保留")
        }
    }

    // MARK: - 账号卡

    private var accountCard: some View {
        ZSTCard {
            HStack(spacing: 12) {
                Text(avatarText)
                    .font(.system(size: F.scaled(20), weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 46, height: 46)
                    .background(Circle().fill(T.brand))

                VStack(alignment: .leading, spacing: 3) {
                    Text(titleText)
                        .font(F.headline)
                        .foregroundStyle(T.textPrimary)
                        .lineLimit(1)
                    Text(subText)
                        .font(F.small)
                        .foregroundStyle(T.textTertiary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Text(app.loggedIn ? "已登录" : "访客")
                    .font(F.micro)
                    .fontWeight(.bold)
                    .foregroundStyle(T.brand)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(
                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                            .fill(T.brandSoft)
                    )
            }

            Text(metaText)
                .font(F.tiny)
                .foregroundStyle(T.textTertiary)

            HStack(spacing: 10) {
                Button {
                    Haptics.light()
                    app.showLogin = true
                } label: {
                    Text(app.loggedIn ? "重新登录" : "登录教务")
                }
                .buttonStyle(OutlineButtonStyle())

                Button {
                    Task { await doSync() }
                } label: {
                    if app.syncing {
                        HStack(spacing: 6) {
                            LoadingRing(size: 14)
                            Text("同步中")
                        }
                    } else {
                        Text("同步课表")
                    }
                }
                .buttonStyle(PrimaryButtonStyle(enabled: !app.syncing))
                .disabled(app.syncing)
            }
            .padding(.top, 2)

            Text(app.loggedIn
                 ? "同步会以教务为准覆盖本地改动（手工加的课保留）"
                 : "密码只存在本机，用于自动登录教务")
                .font(F.micro)
                .foregroundStyle(T.textTertiary)

            Toggle(isOn: Binding(
                get: { app.store.autoLogin() },
                set: { on in
                    // 没有存密码就开不了，引导先登录一次
                    if on && app.store.pass.isEmpty {
                        app.showToast("先登录一次并记住密码，才能自动登录")
                        Haptics.warning()
                        return
                    }
                    app.store.setAutoLogin(on)
                    app.showToast(on ? "下次打开直接自动登录" : "已关闭自动登录，下次要先登录")
                }
            )) {
                Text("自动登录")
                    .font(F.body)
                    .foregroundStyle(T.textPrimary)
            }
            .tint(T.brand)

            if app.loggedIn {
                Button(role: .destructive) {
                    confirmLogout = true
                } label: {
                    Text("退出登录")
                        .font(F.footnote)
                        .foregroundStyle(T.danger)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private var avatarText: String {
        guard app.loggedIn else { return "唐" }
        let name = app.studentName
        return name.isEmpty ? "唐" : String(name.prefix(1))
    }

    private var titleText: String {
        guard app.loggedIn else { return "未登录" }
        let name = app.studentName
        if !name.isEmpty { return name }
        let masked = maskNo(app.store.user())
        return masked.isEmpty ? "已登录" : masked
    }

    private var subText: String {
        guard app.loggedIn else { return "登录教务后可同步课表与成绩" }
        let name = app.studentName
        if name.isEmpty { return "登录状态由教务会话维持" }
        return maskNo(app.store.user())
    }

    private var metaText: String {
        guard let t = app.timetable, !t.courses.isEmpty else {
            return "本地还没有课表，登录教务同步一次"
        }
        let last = app.store.lastSync()
        let when = last > 0 ? "上次同步 " + ago(last) : "从未同步"
        return "本地课表 \(t.courses.count) 门 · " + when
    }

    /// 学号只露前 4 后 4
    private func maskNo(_ no: String) -> String {
        guard no.count >= 8 else { return no }
        return String(no.prefix(4)) + "****" + String(no.suffix(4))
    }

    private func ago(_ ts: TimeInterval) -> String {
        let min = Int((Date().timeIntervalSince1970 - ts) / 60)
        if min < 1 { return "刚刚" }
        if min < 60 { return "\(min) 分钟前" }
        let h = min / 60
        if h < 24 { return "\(h) 小时前" }
        return "\(h / 24) 天前"
    }

    // MARK: - 课表设置

    private var settingsCard: some View {
        ZSTCard(padding: EdgeInsets(top: 16, leading: 16, bottom: 8, trailing: 16)) {
            SectionTitle(text: "课表设置")

            Button {
                Haptics.light()
                termSheet = true
            } label: {
                InfoRow(title: "学期与教学周",
                        value: semesterText,
                        valueColor: T.textTertiary,
                        showChevron: true)
            }
            .buttonStyle(.plain)
            .foregroundStyle(T.textPrimary)

            Divider().overlay(T.borderSubtle)

            NavigationLink {
                ScheduleSettingsPage().environmentObject(app)
            } label: {
                InfoRow(title: "作息时间",
                        value: scheduleSummary,
                        valueColor: T.textTertiary,
                        showChevron: true)
            }
            .buttonStyle(.plain)

            Divider().overlay(T.borderSubtle)

            NavigationLink {
                AdjustManagerPage().environmentObject(app)
            } label: {
                InfoRow(title: "调课与停课",
                        value: adjustSummary,
                        valueColor: T.textTertiary,
                        showChevron: true)
            }
            .buttonStyle(.plain)
        }
    }

    /// 「12 节 · 08:00 开始」
    private var scheduleSummary: String {
        let s = app.schedule
        return "\(s.clampedSectionCount) 节 · \(s.firstClassStart) 开始"
    }

    /// 「N 条」
    private var adjustSummary: String {
        let n = app.timetable?.adjusts.count ?? 0
        return n > 0 ? "\(n) 条调整" : "停课 / 调课 / 补课"
    }

    private var semesterText: String {
        guard let name = app.timetable?.semester.name, !name.isEmpty else {
            return "点这里设置"
        }
        return name
    }

    // MARK: - 外观

    private var appearanceCard: some View {
        ZSTCard {
            SectionTitle(text: "外观")
            Segmented(
                titles: Store.ThemeMode.allCases.map { $0.label },
                selected: Binding(
                    get: { app.themeMode.rawValue },
                    set: { raw in
                        guard let m = Store.ThemeMode(rawValue: raw) else { return }
                        // 等胶囊滑到位（260ms）再换主题，看到的是两个动作而不是硬切
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.26) {
                            withAnimation(M.fadeIn) { app.setTheme(m) }
                        }
                    }
                )
            )
        }
    }

    // MARK: - 小组件

    private var widgetCard: some View {
        ZSTCard(padding: EdgeInsets(top: 16, leading: 16, bottom: 8, trailing: 16)) {
            SectionTitle(text: "桌面小组件")

            Button {
                app.publishWidget()
                Haptics.success()
                app.showToast("已刷新小组件")
            } label: {
                InfoRow(title: "刷新小组件", value: "立即重画今日课程", showChevron: true)
            }
            .buttonStyle(.plain)

            Button {
                Haptics.light()
                app.showToast("在桌面长按 → 编辑 → 添加小组件 → 掌上唐师")
            } label: {
                InfoRow(title: "添加今日课程", value: "桌面长按添加", showChevron: true)
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - 关于

    private var aboutCard: some View {
        ZSTCard(padding: EdgeInsets(top: 16, leading: 16, bottom: 8, trailing: 16)) {
            SectionTitle(text: "关于")

            InfoRow(title: "版本", value: appVersion, showChevron: false)
            InfoRow(title: "数据来源", value: "正方教务系统 · 教务处校历")

            Divider().overlay(T.borderSubtle)

            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("开发者")
                        .font(F.body)
                        .foregroundStyle(T.textPrimary)
                    Text("陈铭霏")
                        .font(F.caption)
                        .foregroundStyle(T.textSecondary)
                    Spacer()
                }
                HStack(spacing: 12) {
                    linkChip("GitHub主页", url: "https://github.com/Chanmingfei/")
                    linkChip("Gitee主页", url: "https://gitee.com/mingfei123/")
                }
            }
            .padding(.vertical, 8)

            InfoRow(title: "荣誉出品", value: "百度唐山师范学院贴吧吧务组")

            Divider().overlay(T.borderSubtle)

            VStack(alignment: .leading, spacing: 8) {
                Text("项目地址")
                    .font(F.body)
                    .foregroundStyle(T.textPrimary)
                HStack(spacing: 12) {
                    linkChip("GitHub仓库", url: "https://github.com/Chanmingfei/zstangshi")
                    linkChip("Gitee仓库", url: "https://gitee.com/mingfei123/zstangshi")
                }
            }
            .padding(.vertical, 8)
        }
    }

    private func linkChip(_ title: String, url: String) -> some View {
        Button {
            Haptics.light()
            if let u = URL(string: url) {
                UIApplication.shared.open(u)
            }
        } label: {
            Text(title)
                .font(F.caption)
                .foregroundStyle(T.brand)
        }
        .buttonStyle(.pressable(0.95))
    }

    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let v = info?["CFBundleShortVersionString"] as? String ?? "—"
        let b = info?["CFBundleVersion"] as? String ?? ""
        return b.isEmpty ? v : "\(v) (\(b))"
    }

    // MARK: - 同步

    private func doSync() async {
        if !app.loggedIn {
            app.showToast("先登录教务")
            app.showLogin = true
            return
        }
        app.showToast("正在同步课表…")
        if let err = await app.syncTimetable() {
            Haptics.error()
            app.showToast(err)
        } else {
            let n = app.timetable?.courses.count ?? 0
            app.showToast("同步完成：\(n) 门课")
        }
    }
}

// MARK: - 学期与教学周

struct TermSheet: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var yearIndex = 0
    @State private var termIndex = 0
    @State private var startDate = Date()
    @State private var showPicker = false
    // 学年候选在 init 里算好，用 @State 存（普通 let 没有 `_years` 投影）
    @State private var years: [String] = []

    private let termOptions = ["第一学期", "第二学期"]

    init() {
        // 学年候选：8 月起算新学年，前后各留两年
        let cal = Calendar.current
        let now = Date()
        let month = cal.component(.month, from: now)
        let year = cal.component(.year, from: now)
        let base = month >= 8 ? year : year - 1
        let list: [String] = (0..<4).map { i in
            let a = base - 2 + i
            return "\(a)-\(a + 1)"
        }
        _years = State(initialValue: list)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("学期与教学周")
                .font(F.headline)
                .foregroundStyle(T.textPrimary)
                .padding(.bottom, 12)

            VStack(spacing: 0) {
                menuRow("学年", value: years[safe: yearIndex] ?? years[0]) {
                    ForEach(years.indices, id: \.self) { i in
                        Button(years[i] + " 学年") { yearIndex = i }
                    }
                }
                Divider().overlay(T.borderSubtle)
                menuRow("学期", value: termOptions[termIndex]) {
                    ForEach(termOptions.indices, id: \.self) { i in
                        Button(termOptions[i]) { termIndex = i }
                    }
                }
                Divider().overlay(T.borderSubtle)

                Button {
                    showPicker = true
                } label: {
                    HStack {
                        Text("第一周周一")
                            .font(F.body)
                            .foregroundStyle(T.textPrimary)
                        Spacer()
                        Text(ymd(startDate))
                            .font(F.caption)
                            .foregroundStyle(T.brand)
                    }
                    .padding(.vertical, 12)
                }
                .buttonStyle(.plain)
            }

            Text("保存后按这个学期与第一周算周数，重启仍生效；非周一的日期会自动归到那一周的周一。")
                .font(F.tiny)
                .foregroundStyle(T.textTertiary)
                .padding(.top, 8)
                .padding(.bottom, 14)

            HStack(spacing: 10) {
                Button("取消") { dismiss() }
                    .buttonStyle(OutlineButtonStyle())
                Button("保存并生效") { save() }
                    .buttonStyle(PrimaryButtonStyle())
            }
        }
        .padding(.horizontal, 18)
        .padding(.top, 12)
        .padding(.bottom, 20)
        .presentationDetents([.height(420)])
        .presentationDragIndicator(.visible)
        .presentationBackground(T.bgSurface)
        .sheet(isPresented: $showPicker) {
            DatePickerSheet(date: $startDate)
        }
        .onAppear { prefill() }
    }

    private func menuRow<Content: View>(_ title: String, value: String,
                                        @ViewBuilder menu: () -> Content) -> some View {
        HStack {
            Text(title)
                .font(F.body)
                .foregroundStyle(T.textPrimary)
            Spacer()
            Menu {
                menu()
            } label: {
                Text(value)
                    .font(F.caption)
                    .foregroundStyle(T.brand)
            }
        }
        .padding(.vertical, 12)
    }

    private func prefill() {
        guard let t = app.timetable else { return }
        let id = t.semester.id
        if id.count >= 9, let i = years.firstIndex(of: String(id.prefix(9))) {
            yearIndex = i
        }
        termIndex = id.hasSuffix("-2") ? 1 : 0
        if Dates.valid(t.semester.startDate) {
            startDate = WeekMath.parseDate(t.semester.startDate)
        }
    }

    private func save() {
        let year = years[safe: yearIndex] ?? years[0]
        let id = year + (termIndex == 1 ? "-2" : "-1")
        guard Dates.valid(ymd(startDate)) else {
            app.showToast("先选第一周周一的日期")
            Haptics.warning()
            return
        }
        let monday = WeekMath.normalizeToMonday(ymd(startDate))
        if let err = app.applySemester(semesterId: id, startDate: monday) {
            app.showToast(err)
            Haptics.warning()
            return
        }
        Haptics.success()
        app.showToast("已按第 1 周 \(monday) 生效")
        dismiss()
    }

    private func ymd(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: d)
    }
}

/// 日期选择弹层（iOS 无系统 DatePickerDialog，用 sheet + graphical 样式）
struct DatePickerSheet: View {
    @Binding var date: Date
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            DatePicker("第一周周一", selection: $date, displayedComponents: .date)
                .datePickerStyle(.graphical)
                .environment(\.locale, Locale(identifier: "zh_CN"))
                .padding()
                .navigationTitle("选择日期")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { dismiss() }
                    }
                }
        }
        .presentationDetents([.medium])
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}