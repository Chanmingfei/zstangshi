import SwiftUI

/// 全局状态：一份课表、一个登录态、一个当前周次、一个主题模式。
///
/// 对应 Android 的「Store + 各 Page 自己持有一份数据」；这里收成一个
/// `ObservableObject`，SwiftUI 靠 `@Published` 自动刷新所有相关视图，
/// 不需要像 Android 那样每页手写 `reload()`。
@MainActor
final class AppState: ObservableObject {

    let store = Store()
    private(set) lazy var client = JwClient(store: store)
    private(set) lazy var api = JwApi(client: client, store: store)

    /// 底栏当前页（0 首页 / 1 课表 / 2 成绩 / 3 我的）
    @Published var tab = 0
    /// 课表当前周次
    @Published var week = 1
    @Published var timetable: Timetable?
    @Published var loggedIn = false
    @Published var studentName = ""
    @Published var themeMode: Store.ThemeMode = .system
    /// 轻提示（对应 Android 的 Toast）
    @Published var toast: String?

    /// 登录页是否呈现
    @Published var showLogin = false
    /// 首次启动且没有任何本地课表时，直接弹登录
    @Published var didBootstrap = false

    var startDate: String { timetable?.semester.startDate ?? "" }

    var schedule: Schedule { store.schedule }

    var user: String { store.user() }

    // MARK: - 启动

    func bootstrap() {
        guard !didBootstrap else { return }
        didBootstrap = true
        themeMode = store.themeMode()
        timetable = store.loadTimetable()
        loggedIn = !store.user().isEmpty
        studentName = store.studentName(owner: store.user())
        week = currentWeek()
    }

    func currentWeek() -> Int {
        guard let t = timetable, !t.semester.startDate.isEmpty else {
            return max(1, week)
        }
        return WeekMath.currentWeek(start: t.semester.startDate, totalWeeks: t.semester.totalWeeks)
    }

    func setTheme(_ mode: Store.ThemeMode) {
        themeMode = mode
        store.setThemeMode(mode)
    }

    /// 切底栏页：整条页面带平移，时长按「离目标还有几格」算（与 Android v3.0.22 同一公式）
    func switchTab(_ index: Int, animated: Bool = true) {
        let target = max(0, min(3, index))
        guard target != tab else { return }
        let distance = CGFloat(abs(target - tab))
        let animation = PageDeck<EmptyView>.curve(PageDeck<EmptyView>.duration(for: distance))
        if animated {
            withAnimation(animation) { tab = target }
        } else {
            tab = target
        }
    }

    /// PageDeck / 页面用：是否要强制深色（跟随系统时返回 nil，交给系统）
    var preferredScheme: ColorScheme? {
        switch themeMode {
        case .light: return .light
        case .dark: return .dark
        case .system: return nil
        }
    }

    // MARK: - 登录

    func login(user: String, password: String, remember: Bool, autoLogin: Bool) async -> String? {
        let trimmed = user.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, !password.isEmpty else { return "请输入学号与密码" }

        let result = await client.login(user: trimmed, password: password)
        guard result.ok else { return result.message }

        if remember {
            store.saveLogin(user: trimmed, pass: password)
        } else {
            store.saveUser(trimmed)
        }
        store.setRememberPwd(remember)
        store.setAutoLogin(autoLogin)
        if !result.studentName.isEmpty {
            store.setStudentName(result.studentName, owner: trimmed)
        }
        loggedIn = true
        studentName = store.studentName(owner: trimmed)
        return nil
    }

    func logout() {
        store.clearLogin()
        client.clearSession()
        loggedIn = false
        studentName = ""
        toast = "已退出登录"
    }

    // MARK: - 同步课表

    /// 同步：先登录，再拉课表，成功后落盘并刷新界面。
    /// 返回错误文案（nil = 成功）。
    func syncTimetable() async -> String? {
        let user = store.user()
        let pass = store.pass
        guard !user.isEmpty, !pass.isEmpty else { return "先在「我的」里登录教务" }

        let login = await client.login(user: user, password: pass)
        guard login.ok else { return login.message }

        let fetched = await client.fetchTimetable()
        guard fetched.ok, var t = fetched.timetable else { return fetched.message }

        // 保留用户手改过的开学日期（同步回来的 semester 里没有 startDate）
        let previousStart = store.startDate(t.semester.id)
        if !previousStart.isEmpty { t.semester.startDate = previousStart }
        if t.semester.startDate.isEmpty {
            t.semester.startDate = WeekMath.monday(of: WeekMath.today())
        }
        t.normalizeColors()

        store.saveTimetable(t)
        store.setActiveSemester(t.semester.id)
        timetable = t
        week = currentWeek()
        return nil
    }

    /// 首页下拉刷新：只核对登录态（会话被踢就自动重登），**不动课表**。
    /// 保底转满 2 秒，避免「一刷新就结束」让人怀疑没刷新（与 Android v3.0.20 一致）。
    func refreshHome() async {
        let started = Date()
        let user = store.user()
        let pass = store.pass
        if !user.isEmpty, !pass.isEmpty {
            _ = await client.loginOnly(user: user, password: pass)
            loggedIn = true
            studentName = store.studentName(owner: user)
        }
        timetable = store.loadTimetable()
        let elapsed = Date().timeIntervalSince(started)
        if elapsed < 2.0 {
            try? await Task.sleep(nanoseconds: UInt64((2.0 - elapsed) * 1_000_000_000))
        }
    }

    // MARK: - 课表操作

    func saveTimetable(_ t: Timetable) {
        var copy = t
        copy.normalizeColors()
        store.saveTimetable(copy)
        timetable = copy
    }

    func setStartDate(_ startDate: String) {
        guard var t = timetable else { return }
        let monday = WeekMath.monday(of: startDate)
        t.semester.startDate = monday
        t.semester.startDateTouched = true
        store.setStartDate(t.semester.id, monday)
        store.saveTimetable(t)
        timetable = t
        week = currentWeek()
    }

    /// 学期与教学周设置（学号 / 学年 / 学期 / 第一周周一）
    func applySemester(id: String, name: String, startDate: String) {
        if var t = timetable, t.semester.id == id {
            t.semester.startDate = WeekMath.monday(of: startDate)
            t.semester.startDateTouched = true
            store.setStartDate(id, t.semester.startDate)
            store.saveTimetable(t)
            timetable = t
        } else if var t = store.timetable(semester: id) {
            t.semester.startDate = WeekMath.monday(of: startDate)
            store.setStartDate(id, t.semester.startDate)
            store.saveTimetable(t)
            store.setActiveSemester(id)
            timetable = t
        } else {
            store.setActiveSemester(id)
            store.setStartDate(id, WeekMath.monday(of: startDate))
        }
        week = currentWeek()
        toast = "已按第 1 周 \(WeekMath.monday(of: startDate)) 生效"
    }

    // MARK: - 今日

    func todayItems() -> [DayPlan.Item] {
        guard let t = timetable, !t.semester.startDate.isEmpty else { return [] }
        let ranges = schedule.sectionRanges
        return DayPlan.of(t, start: t.semester.startDate, ymd: WeekMath.today(),
                          secStart: ranges.start, secEnd: ranges.end)
    }

    func showToast(_ text: String) {
        toast = text
    }
}
