import SwiftUI
import UIKit
import WidgetKit

/// 全局状态：一份课表、一个登录态、一个当前周次、一个主题模式。
///
/// 对应 Android 的「Store + 各 Page 自己持有一份数据」；这里收成一个
/// `ObservableObject`，SwiftUI 靠 `@Published` 自动刷新所有相关视图。
///
/// ⚠️ 上一版 iOS 的致命 bug（本次已修）：
///   `bootstrap()` 里用 `guard !didBootstrap else { return }` 做一次性初始化，
///   导致「在「我的」里登录并同步课表后，切到首页仍然是『还没有课表』」，
///   从后台切回来也不刷新。这里拆成 `reloadFromDisk()`（可重复调用）。
@MainActor
final class AppState: ObservableObject {

    let store: Store
    private(set) var client: JwClient
    private(set) var api: JwApi
    let busStore = BusStore()

    // MARK: - 主页面选择
    //
    // 四个主页面（与安卓 CapsuleDock.LABELS 完全一致）：
    //   0 首页 / 1 课表 / 2 成绩 / 3 我的
    // **实时公交不是一级 tab** —— 它是首页宫格里的功能页。
    // 上一版把公交提升成第 5 个 tab，这是「功能板块位置不对」的直接原因。
    @Published var tab: Int = 0
    /// 页面带的连续位置（小数），驱动整条带平移，并与 Dock 选中块联动
    @Published var deckPosition: CGFloat = 0

    // MARK: - 课表数据
    @Published var timetable: Timetable?
    @Published var schedule: ScheduleSettings = ScheduleSettings()
    @Published var week: Int = 1

    // MARK: - 登录态
    @Published var loggedIn = false
    @Published var studentName = ""
    @Published var userNo = ""

    // MARK: - 外观
    @Published var themeMode: Store.ThemeMode = .system

    // MARK: - 交互反馈
    @Published var toast: String?
    @Published var showLogin = false

    // MARK: - 同步中
    @Published var syncing = false

    init() {
        let s = Store()
        // 注意：client/api 依赖 store，必须先建 store
        store = s
        let c = JwClient(store: s)
        client = c
        api = JwApi(client: c, store: s)
        schedule = s.schedule()
        reloadFromDisk()
    }

    // MARK: - 启动 / 刷新
    //
    // 可重复调用：从后台回前台、登录完、同步完都走这里。
    // 不再有「只初始化一次」的 guard。

    func reloadFromDisk() {
        themeMode = store.themeMode()
        schedule = store.schedule()
        timetable = store.loadTimetable()
        userNo = store.user()
        loggedIn = !userNo.isEmpty && !store.pass.isEmpty
        studentName = store.studentName(owner: userNo)
        week = currentWeek()
        publishWidget()
    }

    func bootstrap() {
        reloadFromDisk()
    }

    // MARK: - 主题

    var preferredScheme: ColorScheme? {
        switch themeMode {
        case .light: return .light
        case .dark: return .dark
        case .system: return nil
        }
    }

    func setTheme(_ mode: Store.ThemeMode) {
        themeMode = mode
        store.setThemeMode(mode)
    }

    // MARK: - 主导航

    /// 切 tab。iOS 侧不自己补间 —— 由根视图的页面带负责动画，
    /// 这里只改数据，避免「两处都在动画」造成双倍位移。
    func switchTab(_ index: Int) {
        setTab(index, animated: true)
    }

    /// 切到指定 tab（同时更新页面带的连续位置，驱动整条带平移与 Dock 选中块联动）
    func setTab(_ index: Int, animated: Bool) {
        let target = max(0, min(MainTab.count - 1, index))
        guard target != tab else { return }
        tab = target
        deckPosition = CGFloat(target)
    }

    // MARK: - 周次

    func currentWeek() -> Int {
        guard let t = timetable, !t.semester.startDate.isEmpty else {
            return max(1, week)
        }
        return WeekMath.currentWeek(startDate: t.semester.startDate,
                                    totalWeeks: t.semester.totalWeeks)
    }

    // MARK: - 登录

    /// 返回错误文案（nil = 成功）
    func login(user: String, password: String,
               remember: Bool, autoLogin: Bool) async -> String? {
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
        userNo = trimmed
        loggedIn = true
        studentName = store.studentName(owner: trimmed)
        return nil
    }

    func logout() {
        store.clearLogin()
        Task { await client.clearSession() }
        loggedIn = false
        studentName = ""
        userNo = ""
        toast = "已退出登录"
    }

    // MARK: - 同步课表

    /// 同步：先登录，再拉课表，成功后落盘并刷新界面。返回错误文案（nil = 成功）。
    func syncTimetable() async -> String? {
        let user = store.user()
        let pass = store.pass
        guard !user.isEmpty, !pass.isEmpty else { return "先在「我的」里登录教务" }

        syncing = true
        defer { syncing = false }

        let login = await client.login(user: user, password: pass)
        guard login.ok else { return login.message }
        if !login.studentName.isEmpty {
            store.setStudentName(login.studentName, owner: user)
            studentName = login.studentName
        }

        let fetched = await client.fetchTimetable()
        guard fetched.ok, var t = fetched.timetable else { return fetched.message }

        // 保留用户手改过的开学日期（同步回来的 semester 里没有 startDate）
        let previousStart = store.startDate(t.semester.id)
        if !previousStart.isEmpty {
            t.semester.startDate = previousStart
            t.semester.startDateTouched = true
        }
        if t.semester.startDate.isEmpty {
            t.semester.startDate = guessStartDate(t)
        }
        t.normalizeColors()

        let savedId = store.saveTimetable(t)
        store.setActiveSemester(savedId)
        store.setStartDate(savedId, t.semester.startDate)
        timetable = store.timetable(semester: savedId) ?? t
        week = currentWeek()
        publishWidget()
        Haptics.success()
        return nil
    }

    /// 开学日期：教务接口不给，优先用内置校历（唐山师范学院 2026-2027-1 为 2026-08-31），
    /// 校历没覆盖当前学期时退回「今天所在周周一」。
    private func guessStartDate(_ t: Timetable) -> String {
        if let term = AcademicCalendar.termFor(semesterId: t.semester.id),
           !term.classStart.isEmpty {
            return WeekMath.normalizeToMonday(term.classStart)
        }
        if let term = AcademicCalendar.termFor(today: Dates.todayLocal()),
           !term.classStart.isEmpty {
            return WeekMath.normalizeToMonday(term.classStart)
        }
        return WeekMath.normalizeToMonday(Dates.todayLocal())
    }

    /// 首页下拉刷新：只核对登录态（会话被踢就自动重登），**不动课表**。
    func refreshHome() async {
        reloadFromDisk()
        guard loggedIn else { return }
        _ = await client.loginOnly(user: store.user(), password: store.pass)
    }

    // MARK: - 课表操作

    /// 保存课表到本地并刷新所有相关界面 + 小组件。
    /// 课程编辑、调课停课等所有写操作都走这里。
    func saveTimetable(_ t: Timetable) {
        var copy = t
        copy.normalizeColors()
        let savedId = store.saveTimetable(copy)
        store.setActiveSemester(savedId)
        timetable = store.timetable(semester: savedId) ?? copy
        week = currentWeek()
        publishWidget()
    }

    // MARK: - 作息设置

    /// 保存作息（节次数量 / 第 1 节开始 / 每节时长与课间）。
    /// 安卓的 `Store.schedule()` 每次从默认重建，每节时长无法持久化；
    /// iOS 的 `Store.setSchedule` 整体存 JSON，改了就真存下来。
    func setSchedule(_ s: ScheduleSettings) {
        store.setSchedule(s)
        schedule = s
        publishWidget()
    }

    // MARK: - 学期设置

    /// 学期与教学周设置（学年 / 学期 / 第一周周一）
    func applySemester(semesterId: String, startDate: String) -> String? {
        let monday = WeekMath.normalizeToMonday(startDate)
        guard Dates.valid(monday) else { return "先选第一周周一的日期" }

        if let existing = store.timetable(semester: semesterId), !existing.isEmpty {
            var t = existing
            t.semester.startDate = monday
            t.semester.startDateTouched = true
            store.saveTimetable(t)
            store.setActiveSemester(semesterId)
            store.setStartDate(semesterId, monday)
            reloadFromDisk()
            return nil
        }

        // 该学期没有课表：只存开学日期与 active 指针，等下次同步
        if let current = timetable, current.semester.id == semesterId {
            var t = current
            t.semester.startDate = monday
            t.semester.startDateTouched = true
            store.saveTimetable(t)
            store.setStartDate(semesterId, monday)
            reloadFromDisk()
            return nil
        }
        return "这个学期还没有课表，先登录教务同步一次"
    }

    // MARK: - 今日课程

    func todayItems() -> [DayPlan.Item] {
        guard let t = timetable, !t.semester.startDate.isEmpty else { return [] }
        let ranges = schedule.minuteRanges()
        return DayPlan.of(t, start: t.semester.startDate, ymd: Dates.todayLocal(),
                          secStart: ranges.start, secEnd: ranges.end)
    }

    // MARK: - 小组件

    /// 把今日课程写进 App Group，桌面小组件直接读这份极简 JSON。
    func publishWidget() {
        let now = Dates.nowMinute()
        let items = todayItems().map { item in
            WidgetCourse(name: item.name,
                         time: item.timeLabel,
                         place: item.placeLabel,
                         colorIndex: item.colorIndex,
                         done: item.endMin <= now,
                         startMin: item.startMin,
                         endMin: item.endMin)
        }
        guard let data = try? JSONEncoder().encode(items),
              let json = String(data: data, encoding: .utf8) else { return }
        Store.publishWidgetCourses(json)

        // 同时发布「状态信息」：小组件据此判定 5 种状态
        // （没有课表 / 没设开学日期 / 开学前 / 已结束 / 正常学期内）
        let s = Store.shared
        s.set(timetable != nil && !(timetable?.courses.isEmpty ?? true),
              forKey: "widget_has_timetable")
        s.set(timetable?.semester.startDate ?? "", forKey: "widget_start_date")
        s.set(timetable?.semester.name ?? "", forKey: "widget_semester_name")
        s.set(timetable?.semester.totalWeeks ?? 20, forKey: "widget_total_weeks")

        WidgetCenterBridge.reload()
    }

    // MARK: - Toast

    func showToast(_ text: String) {
        toast = text
    }
}

// MARK: - 主页面定义

/// 四个主页面。顺序 = 安卓 CapsuleDock.LABELS 的顺序，**不可随意调整**。
enum MainTab: Int, CaseIterable {
    case home = 0
    case timetable = 1
    case grades = 2
    case mine = 3

    var title: String {
        switch self {
        case .home: return "首页"
        case .timetable: return "课表"
        case .grades: return "成绩"
        case .mine: return "我的"
        }
    }

    /// 底栏图标不再用 SF Symbols —— 形状与安卓差异明显（尤其「课表」）。
    /// 现在由 `DockIcon.PathView` 用安卓 `ic_nav_*.xml` 的原始 pathData 绘制。
    /// 这两个属性保留给「首页宫格」等其他地方用 SF Symbols 的场景。
    var icon: String {
        switch self {
        case .home: return "house"
        case .timetable: return "square.grid.3x1.below.line.grid.1x2"
        case .grades: return "chart.bar"
        case .mine: return "person"
        }
    }

    var iconFilled: String {
        switch self {
        case .home: return "house.fill"
        case .timetable: return "square.grid.3x1.below.line.grid.1x2"
        case .grades: return "chart.bar.fill"
        case .mine: return "person.fill"
        }
    }

    static var count: Int { allCases.count }
}

// MARK: - 小组件桥
//
// 主 App 触发 WidgetKit 重载。包一层是为了让 AppState 不直接依赖 WidgetKit
// 的具体调用点，也方便测试。

enum WidgetCenterBridge {
    static func reload() {
        WidgetCenter.shared.reloadAllTimelines()
    }
}

/// 写给小组件的极简课程结构（与 Widget target 里那份**字段完全一致**，
/// 但两边各自定义、不共享源码 —— Widget 不能 import 主 App 的模型）。
struct WidgetCourse: Codable {
    var name: String
    var time: String
    var place: String
    var colorIndex: Int
    var done: Bool
    var startMin: Int
    var endMin: Int
}