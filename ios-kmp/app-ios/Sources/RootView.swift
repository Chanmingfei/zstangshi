import SwiftUI
import Shared

/// 首页 —— 对齐安卓：问候 + 日期周次 + 今日课程 + 常用功能宫格 + 登录卡。
struct RootView: View {
    @EnvironmentObject private var router: TabRouter
    @ObservedObject private var auth = JwAuth.shared
    @State private var timetable: Timetable?
    @State private var todayCells: [GridCell] = []
    @State private var week = 1
    @State private var loading = false
    @State private var autoState = ""

    private let weekNames = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                header
                todayCard
                gridSection
                affairsCard
                loginCard
            }
            .padding(16)
        }
        .background(Color(UIColor.systemGroupedBackground))
        .navigationTitle("")
        .navigationBarHidden(true)
        .task { await bootstrap() }
    }

    // MARK: 顶部

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(greeting).font(.title2).bold()
            Text("\(dateText) · 第 \(week) 周")
                .font(.footnote).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        if h < 6 { return "夜深了" }
        if h < 12 { return "早上好" }
        if h < 14 { return "中午好" }
        if h < 18 { return "下午好" }
        return "晚上好"
    }

    private var dateText: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "M 月 d 日"
        let weekday = weekNames[max(0, min(6, Calendar.current.component(.weekday, from: Date()) - 2))]
        return "今天\(weekday) · \(f.string(from: Date()))"
    }

    // MARK: 今日课程

    private var todayCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            if loading {
                Text("正在同步课表…").font(.footnote).foregroundColor(.secondary)
            } else if !auth.loggedIn {
                Text("登录后可查看今日课程").font(.subheadline)
                Text("点下方「登录学工系统」").font(.caption2).foregroundColor(.secondary)
            } else if todayCells.isEmpty {
                Text("今天没课").font(.title3).bold()
                Text("好好休息，或者预习一下明天的").font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(todayCells, id: \.courseKey) { c in
                    HStack(spacing: 10) {
                        VStack(spacing: 0) {
                            Text("\(c.startSection)").font(.system(size: 13, weight: .semibold))
                            Text("节").font(.system(size: 9)).foregroundColor(.secondary)
                        }
                        .frame(width: 30)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(c.name).font(.subheadline).bold()
                            Text("\(c.room)\(c.teacher.isEmpty ? "" : " · \(c.teacher)")")
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        Spacer()
                    }
                    .padding(.vertical, 6)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    // MARK: 宫格

    private var gridSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("常用功能").font(.footnote).foregroundColor(.secondary)

            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 4), spacing: 10) {
                // 安卓首页宫格点「我的课表 / 成绩」是切到对应底栏 tab，不是 push 新页。
                tabTile("我的课表", "calendar", tab: 1)
                tabTile("成绩", "chart.bar.doc.horizontal", tab: 2)
                tile("学分绩点", "graduationcap") { GpaView() }
                tile("校历", "calendar.badge.clock") { CalendarView() }
                tile("选课", "checkmark.rectangle.stack") { SelectionView() }
                tile("推荐课表", "book.closed") { RecommendView() }
                tile("实时公交", "bus.fill") { BusHomeView() }
                tile("更多功能", "square.grid.2x2") {
                    WebViewPage.link(WebViewPage.Link.jwxt, title: "教务系统")
                }
            }
        }
    }

    /// 登录学工系统横条（对齐安卓首页 affairsCard：应用内打开，不跳浏览器）。
    private var affairsCard: some View {
        NavigationLink {
            WebViewPage.link(WebViewPage.Link.xuegong, title: "学工系统")
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "arrow.up.right.square")
                    .font(.title3)
                    .foregroundColor(Color(red: 0.46, green: 0.08, blue: 0.10))
                VStack(alignment: .leading, spacing: 2) {
                    Text("登录学工系统").font(.subheadline).bold()
                    Text("奖助贷、请假、综合素质 · 应用内打开")
                        .font(.caption2).foregroundColor(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption2).foregroundColor(.secondary)
            }
            .padding(14)
            .background(Color(UIColor.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    /// 切底栏 tab 的宫格（对齐安卓首页点课表/成绩直接切页）。
    private func tabTile(_ title: String, _ icon: String, tab: Int) -> some View {
        Button { router.selection = tab } label: {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                    .foregroundColor(Color(red: 0.46, green: 0.08, blue: 0.10))
                Text(title).font(.system(size: 11)).foregroundColor(.primary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color(UIColor.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private func tile<D: View>(_ title: String, _ icon: String, @ViewBuilder dest: @escaping () -> D) -> some View {
        NavigationLink {
            dest()
        } label: {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                    .foregroundColor(Color(red: 0.46, green: 0.08, blue: 0.10))
                Text(title).font(.system(size: 11)).foregroundColor(.primary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color(UIColor.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // MARK: 登录

    private var loginCard: some View {
        Group {
            if auth.loggedIn {
                HStack(spacing: 12) {
                    Image(systemName: "person.crop.circle.badge.checkmark")
                        .font(.title3)
                        .foregroundColor(Color(red: 0.10, green: 0.45, blue: 0.22))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("已登录 · \(auth.studentNo)").font(.subheadline)
                        Text("课表与成绩已接通教务").font(.caption2).foregroundColor(.secondary)
                    }
                    Spacer()
                    Button("退出") {
                        auth.logout()
                        timetable = nil
                        todayCells = []
                    }
                    .font(.footnote)
                }
                .padding(16)
                .background(Color(UIColor.secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            } else {
                NavigationLink {
                    LoginView().environmentObject(auth)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "person.crop.circle")
                            .font(.title3)
                            .foregroundColor(Color(red: 0.46, green: 0.08, blue: 0.10))
                        VStack(alignment: .leading, spacing: 2) {
                            Text("登录学工系统").font(.subheadline)
                            Text(autoState.isEmpty
                                 ? "课表、成绩、绩点、校历（需要教务账号）"
                                 : autoState)
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(16)
                    .background(Color(UIColor.secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: 数据

    /// 启动顺序：先渲染本地缓存（秒开），再后台静默登录并同步。
    /// 安卓就是这个体验 —— 首页立刻可用，登录/同步在后台跑，绝不挡着点「实时公交」。
    private func bootstrap() async {
        if let cached = TimetableStore.load() {
            apply(cached)
        }
        await autoLoginThenSync()
    }

    private func autoLoginThenSync() async {
        if auth.loggedIn {
            await sync()
            return
        }
        let remember = UserDefaults.standard.bool(forKey: "jw.remember")
        let no = Keychain.studentNo
        let pwd = Keychain.password
        guard remember, !no.isEmpty, !pwd.isEmpty else { return }

        autoState = "正在自动登录…"
        let ok = await auth.login(no: no, password: pwd)
        if ok {
            autoState = ""
            await sync()
        } else {
            autoState = "自动登录失败：\(auth.message)"
        }
    }

    /// 首次登录（或换账号）才自动同步；之后只用缓存，刷新交给「我的」页。
    private func sync() async {
        let synced = UserDefaults.standard.string(forKey: "jw.syncedNo") ?? ""
        let needFirst = !auth.studentNo.isEmpty && synced != auth.studentNo
        guard needFirst || timetable == nil else { return }
        loading = true
        defer { loading = false }
        guard let fresh = await JwAuth.shared.fetchTimetable() else { return }
        if let local = timetable {
            for k in local.hiddenList() {
                TimetableEdit.shared.hideCourse(t: fresh, key: k)
            }
        }
        TimetableStore.save(fresh)
        UserDefaults.standard.set(auth.studentNo, forKey: "jw.syncedNo")
        NotificationCenter.default.post(name: .timetableChanged, object: nil)
        apply(fresh)
    }

    private func apply(_ t: Timetable) {
        timetable = t
        week = currentWeek(t)
        todayCells = TimetableGrid.shared.cellsOfDay(
            t: t, week: Int32(week), day: Int32(todayWeekday()))
    }

    /// 当前教学周：优先用学期开始日推算，缺就退回第 1 周。
    private func currentWeek(_ t: Timetable) -> Int {
        guard !t.semester.startDate.isEmpty else { return 1 }
        let comps = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        let today = Dates.shared.daysFromCivil(
            y0: Int32(comps.year ?? 2026), m: Int32(comps.month ?? 1), d: Int32(comps.day ?? 1))
        return Int(WeekMath.shared.currentWeek(
            startDate: t.semester.startDate,
            totalWeeks: Int32(max(1, t.semester.totalWeeks)),
            today: today))
    }

    private func todayWeekday() -> Int {
        // Calendar 的 weekday：1=周日…7=周六 → 转成 1=周一…7=周日
        let w = Calendar.current.component(.weekday, from: Date())
        return w == 1 ? 7 : w - 1
    }
}
