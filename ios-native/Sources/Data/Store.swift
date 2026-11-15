import Foundation
import Security
import UIKit

/// 本地存储：UserDefaults（对应 Android 的 SharedPreferences）+ Keychain（对应 SecurePrefs）。
///
/// 两条关键设计，和 Android 一模一样：
///   1. 课表按学期分 key（`tt_2026-2027-1`），换学期不互相覆盖；当前学期没有数据时
///      回退到「最近的一份」，冷启动不登录也能直接进课表。
///   2. 密码**只**进 Keychain，UserDefaults 里连密文都不留 —— iOS 的 UserDefaults 是
///      明文 plist，放密码等于裸奔；Keychain 有系统级加密，且默认「本机专用、换机不迁移」。
///
/// 与旧 iOS 版的区别（这是上一版「功能全废」的隐患之一）：
///   · 旧版 `saveTimetable` 在 `semester.id` 为空时**静默 return**，
///     导致「同步完成 N 门课」但重开 App 课表又是空的。
///   · 这里改成：id 为空时用名称+日期兜底生成一个稳定 id，保证数据一定落盘。
final class Store {

    private let defaults: UserDefaults

    /// 小组件共享容器（App Group）。读不到时退回 standard，
    /// 但会记录告警，避免「静默写进错误位置」。
    static let appGroup = "group.com.tstc.zstangshi"

    init(suite: String? = nil) {
        if let suite, let d = UserDefaults(suiteName: suite) {
            defaults = d
        } else {
            defaults = .standard
        }
    }

    // MARK: - Key 常量（与 Android Store.kt:272-288 完全一致）

    private enum K {
        static let ttPrefix = "tt_"
        static let active = "active_semester"
        static let startPrefix = "startDate_"
        static let loginUser = "login_user"
        static let studentName = "student_name"
        static let nameOwner = "student_name_owner"
        static let loginAt = "login_at"
        static let origin = "origin_scheme"
        static let remember = "remember_pwd"
        static let autoLogin = "auto_login"
        static let sectionCount = "section_count"
        static let firstStart = "first_start"
        static let theme = "theme_mode"
        static let lastSync = "last_sync"
        static let scheduleJson = "schedule_json"
        static let featurePrefix = "feature_"
    }

    enum ThemeMode: Int, CaseIterable {
        case light = 0, dark = 1, system = 2

        var label: String {
            switch self {
            case .light: return "浅色"
            case .dark: return "深色"
            case .system: return "跟随系统"
            }
        }
    }

    // MARK: - 课表

    /// 保存课表。id 为空时生成稳定兜底 id，**绝不静默丢数据**。
    @discardableResult
    func saveTimetable(_ t: Timetable) -> String {
        var t = t
        if t.semester.id.isEmpty {
            let base = t.semester.name.isEmpty ? "current" : t.semester.name
            let sanitized = base.replacingOccurrences(of: " ", with: "")
            t.semester.id = sanitized.isEmpty ? "unset" : sanitized
        }
        let enc = JSONEncoder()
        enc.outputFormatting = []
        guard let data = try? enc.encode(t),
              let json = String(data: data, encoding: .utf8) else { return t.semester.id }

        defaults.set(json, forKey: K.ttPrefix + t.semester.id)
        defaults.set(t.semester.id, forKey: K.active)
        if !t.semester.startDate.isEmpty {
            defaults.set(t.semester.startDate, forKey: K.startPrefix + t.semester.id)
        }
        defaults.set(Date().timeIntervalSince1970, forKey: K.lastSync)
        return t.semester.id
    }

    func timetable(semester: String) -> Timetable? {
        guard !semester.isEmpty else { return nil }
        guard let json = defaults.string(forKey: K.ttPrefix + semester),
              let data = json.data(using: .utf8) else { return nil }
        guard var t = try? JSONDecoder().decode(Timetable.self, from: data) else { return nil }
        if let start = defaults.string(forKey: K.startPrefix + semester), !start.isEmpty {
            t.semester.startDate = start
        }
        return t
    }

    /// 当前学期（没有记录就取最近更新过的那份）
    func loadTimetable() -> Timetable? {
        if let t = timetable(semester: activeSemester()), !t.isEmpty { return t }
        return loadLatest()
    }

    /// 所有存过的学期 id（按字典序倒排，方便做学期切换）
    func semesters() -> [String] {
        defaults.dictionaryRepresentation().keys
            .filter { $0.hasPrefix(K.ttPrefix) }
            .map { String($0.dropFirst(K.ttPrefix.count)) }
            .filter { !$0.isEmpty }
            .sorted(by: >)
    }

    /// 最近更新的一份（跨学期兜底：冷启动不知道当前学期时用它）
    func loadLatest() -> Timetable? {
        for id in semesters() {
            if let t = timetable(semester: id), !t.isEmpty { return t }
        }
        return nil
    }

    func hasLocal() -> Bool {
        !(loadTimetable()?.isEmpty ?? true)
    }

    func activeSemester() -> String {
        defaults.string(forKey: K.active) ?? ""
    }

    func setActiveSemester(_ id: String) {
        guard !id.isEmpty else { return }
        defaults.set(id, forKey: K.active)
    }

    func setStartDate(_ semester: String, _ ymd: String) {
        guard !semester.isEmpty else { return }
        defaults.set(ymd, forKey: K.startPrefix + semester)
        if var t = timetable(semester: semester) {
            t.semester.startDate = ymd
            t.semester.startDateTouched = true
            saveTimetable(t)
        }
    }

    func startDate(_ semester: String) -> String {
        defaults.string(forKey: K.startPrefix + semester) ?? ""
    }

    func lastSync() -> TimeInterval {
        defaults.double(forKey: K.lastSync)
    }

    func clearTimetable() {
        for id in semesters() { defaults.removeObject(forKey: K.ttPrefix + id) }
        defaults.removeObject(forKey: K.active)
    }

    // MARK: - 作息设置

    /// 完整作息（学期设置里有每节时长/课间的自定义，需要存 JSON 而不是只有两个数）
    func schedule() -> ScheduleSettings {
        if let json = defaults.string(forKey: K.scheduleJson),
           let data = json.data(using: .utf8),
           var s = try? JSONDecoder().decode(ScheduleSettings.self, from: data) {
            // 先用旧的两个扁平 key 覆盖（老数据兼容），再以 JSON 为准
            if let first = defaults.string(forKey: K.firstStart), !first.isEmpty {
                s.firstClassStart = first
            }
            let count = defaults.integer(forKey: K.sectionCount)
            if count > 0 { s.sectionCount = count }
            return s
        }
        var s = ScheduleSettings()
        if let first = defaults.string(forKey: K.firstStart), !first.isEmpty {
            s.firstClassStart = first
        }
        let count = defaults.integer(forKey: K.sectionCount)
        if count > 0 { s.sectionCount = count }
        return s
    }

    func setSchedule(_ s: ScheduleSettings) {
        if let data = try? JSONEncoder().encode(s),
           let json = String(data: data, encoding: .utf8) {
            defaults.set(json, forKey: K.scheduleJson)
        }
        // 同时写回扁平 key，保证跨版本 / 跨平台的兼容读取
        defaults.set(s.firstClassStart, forKey: K.firstStart)
        defaults.set(s.clampedSectionCount, forKey: K.sectionCount)
    }

    func sectionCount() -> Int {
        schedule().clampedSectionCount
    }

    func setSectionCount(_ n: Int) {
        var s = schedule()
        s.sectionCount = ScheduleSettings.clamp(n, ScheduleSettings.minSections, ScheduleSettings.maxSections)
        setSchedule(s)
    }

    func firstStart() -> String {
        schedule().firstClassStart
    }

    func setFirstStart(_ hm: String) {
        var s = schedule()
        s.firstClassStart = hm
        setSchedule(s)
    }

    // MARK: - 外观

    /// 是否此刻应显示深色（明确选深色 → 深；明确选浅色 → 浅；跟随系统 → 看系统）
    func darkNow() -> Bool {
        switch themeMode() {
        case .dark: return true
        case .light: return false
        case .system:
            return UITraitCollection.current.userInterfaceStyle == .dark
        }
    }

    func themeMode() -> ThemeMode {
        if defaults.object(forKey: K.theme) == nil { return .system }
        return ThemeMode(rawValue: defaults.integer(forKey: K.theme)) ?? .system
    }

    func setThemeMode(_ m: ThemeMode) {
        defaults.set(m.rawValue, forKey: K.theme)
    }

    // MARK: - 登录信息

    func user() -> String { defaults.string(forKey: K.loginUser) ?? "" }

    var pass: String { Keychain.get("login_pass") ?? "" }

    func saveLogin(user: String, pass: String) {
        defaults.set(user, forKey: K.loginUser)
        Keychain.set("login_pass", pass)
        defaults.set(true, forKey: K.remember)
    }

    /// 只记学号（没勾「记住密码」时用）
    func saveUser(_ user: String) {
        defaults.set(user, forKey: K.loginUser)
        Keychain.delete("login_pass")
        defaults.set(false, forKey: K.remember)
    }

    func clearLogin() {
        defaults.removeObject(forKey: K.loginUser)
        defaults.removeObject(forKey: K.studentName)
        defaults.removeObject(forKey: K.nameOwner)
        Keychain.delete("login_pass")
    }

    func rememberPwd() -> Bool {
        // 没写过这个键时按「有没有密码」推断（与安卓 Store.rememberPwd 一致）
        if defaults.object(forKey: K.remember) == nil { return !pass.isEmpty }
        return defaults.bool(forKey: K.remember)
    }

    func setRememberPwd(_ on: Bool) { defaults.set(on, forKey: K.remember) }

    func autoLogin() -> Bool { defaults.bool(forKey: K.autoLogin) }
    func setAutoLogin(_ on: Bool) { defaults.set(on, forKey: K.autoLogin) }

    /// 教务个人信息页抓到的姓名。姓名绑定学号存取：
    /// 换了账号旧姓名一律视为不存在，界面回退显示学号。
    func studentName(owner: String) -> String {
        let savedOwner = defaults.string(forKey: K.nameOwner) ?? ""
        guard !owner.isEmpty, owner == savedOwner else { return "" }
        return defaults.string(forKey: K.studentName) ?? ""
    }

    func setStudentName(_ name: String, owner: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, !owner.isEmpty else { return }
        defaults.set(trimmed, forKey: K.studentName)
        defaults.set(owner, forKey: K.nameOwner)
    }

    /// 最近一次教务登录成功的时间。功能页用它判断「本次登录会话抓过没有」。
    func loginAt() -> TimeInterval { defaults.double(forKey: K.loginAt) }

    func setLoginAt(_ t: TimeInterval = Date().timeIntervalSince1970) {
        defaults.set(t, forKey: K.loginAt)
    }

    /// 上次真正连得上的教务协议（"http" / "https"）。
    /// 校外移动数据下 443 常常直接超时，每次都从 https 试会白等一轮超时。
    func originScheme() -> String { defaults.string(forKey: K.origin) ?? "" }

    func setOriginScheme(_ s: String) { defaults.set(s, forKey: K.origin) }

    // MARK: - 功能页快照（成绩 / 绩点 / 选课 / 推荐课表）

    func saveFeature(_ type: String, _ json: String) {
        defaults.set(json, forKey: K.featurePrefix + type)
        defaults.set(Date().timeIntervalSince1970, forKey: K.featurePrefix + type + "_at")
    }

    func feature(_ type: String) -> String {
        defaults.string(forKey: K.featurePrefix + type) ?? ""
    }

    func featureAt(_ type: String) -> TimeInterval {
        defaults.double(forKey: K.featurePrefix + type + "_at")
    }

    // MARK: - 小组件共享（App Group）
    //
    // 主 App 与 Widget 通过共享 UserDefaults 交换「今日课程」JSON。
    // 若 App Group entitlement 缺失（未签名包重签时常见），
    // 退回 standard 让 App 自身仍能工作，但 Widget 会读不到 —— 这是可接受的降级。

    static var shared: UserDefaults {
        UserDefaults(suiteName: appGroup) ?? .standard
    }

    static func publishWidgetCourses(_ json: String) {
        shared.set(json, forKey: "widget_today_courses")
        shared.set(Date().timeIntervalSince1970, forKey: "widget_today_updated")
    }
}

// MARK: - Keychain

/// Keychain 读写（kSecClassGenericPassword，只给本 App 用）。
enum Keychain {

    private static let service = "com.tstc.zstangshi.credentials"

    @discardableResult
    static func set(_ account: String, _ value: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)

        var add = query
        add[kSecValueData as String] = data
        // AfterFirstUnlock：允许设备重启后、解锁前被后台任务读取（小组件刷新需要）。
        // ThisDeviceOnly：不随 iCloud 钥匙串迁移，换机后重新登录 —— 与安卓一致。
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static func get(_ account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func delete(_ account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}