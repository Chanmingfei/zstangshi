import Foundation
import Security

/// 本地存储：UserDefaults（对应 Android 的 SharedPreferences）+ Keychain（对应 SecurePrefs）。
///
/// 两条关键设计，和 Android 一模一样：
///   1. 课表按学期分 key（`tt_2026-2027-1`），换学期不互相覆盖；当前学期没有数据时
///      回退到「最近的一份」，冷启动不登录也能直接进课表。
///   2. 密码**只**进 Keychain，UserDefaults 里连密文都不留 —— iOS 的 UserDefaults 是
///      明文 plist，放密码等于裸奔；Keychain 有系统级加密，且默认「本机专用、换机不迁移」。
final class Store {

    private let defaults = UserDefaults.standard

    private enum Key {
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
        static let featurePrefix = "feature_"
    }

    enum ThemeMode: Int {
        case light = 0, dark = 1, system = 2
    }

    // MARK: - 课表

    func saveTimetable(_ t: Timetable) {
        guard !t.semester.id.isEmpty else { return }
        let encoder = JSONEncoder()
        guard let data = try? encoder.encode(t), let json = String(data: data, encoding: .utf8) else { return }
        defaults.set(json, forKey: Key.ttPrefix + t.semester.id)
        defaults.set(t.semester.id, forKey: Key.active)
        if !t.semester.startDate.isEmpty {
            defaults.set(t.semester.startDate, forKey: Key.startPrefix + t.semester.id)
        }
        defaults.set(Date().timeIntervalSince1970, forKey: Key.lastSync)
    }

    func timetable(semester: String) -> Timetable? {
        guard !semester.isEmpty,
              let json = defaults.string(forKey: Key.ttPrefix + semester),
              let data = json.data(using: .utf8) else { return nil }
        var t = try? JSONDecoder().decode(Timetable.self, from: data)
        if let start = defaults.string(forKey: Key.startPrefix + semester), !start.isEmpty {
            t?.semester.startDate = start
        }
        return t
    }

    /// 当前学期（没有记录就取最近更新过的那份）
    func loadTimetable() -> Timetable? {
        if let t = timetable(semester: activeSemester()), !t.isEmpty { return t }
        return loadLatest()
    }

    func semesters() -> [String] {
        defaults.dictionaryRepresentation().keys
            .filter { $0.hasPrefix(Key.ttPrefix) }
            .map { String($0.dropFirst(Key.ttPrefix.count)) }
            .sorted(by: >)
    }

    func loadLatest() -> Timetable? {
        for id in semesters() {
            if let t = timetable(semester: id), !t.isEmpty { return t }
        }
        return nil
    }

    func hasLocal() -> Bool { !(loadTimetable()?.isEmpty ?? true) }

    func activeSemester() -> String { defaults.string(forKey: Key.active) ?? "" }

    func setActiveSemester(_ id: String) {
        guard !id.isEmpty else { return }
        defaults.set(id, forKey: Key.active)
    }

    func setStartDate(_ semester: String, _ ymd: String) {
        guard !semester.isEmpty else { return }
        defaults.set(ymd, forKey: Key.startPrefix + semester)
        if var t = timetable(semester: semester) {
            t.semester.startDate = ymd
            saveTimetable(t)
        }
    }

    func startDate(_ semester: String) -> String {
        defaults.string(forKey: Key.startPrefix + semester) ?? ""
    }

    func lastSync() -> TimeInterval { defaults.double(forKey: Key.lastSync) }

    func clearTimetable() {
        for id in semesters() { defaults.removeObject(forKey: Key.ttPrefix + id) }
        defaults.removeObject(forKey: Key.active)
    }

    // MARK: - 设置

    func sectionCount() -> Int {
        let v = defaults.integer(forKey: Key.sectionCount)
        return v == 0 ? Schedule.defaultSectionCount : max(Schedule.minSections, min(Schedule.maxSections, v))
    }

    func setSectionCount(_ n: Int) {
        defaults.set(max(Schedule.minSections, min(Schedule.maxSections, n)), forKey: Key.sectionCount)
    }

    func firstStart() -> String { defaults.string(forKey: Key.firstStart) ?? Schedule.defaultFirstStart }

    func setFirstStart(_ hm: String) { defaults.set(hm, forKey: Key.firstStart) }

    var schedule: Schedule {
        var s = Schedule()
        s.firstClassStart = firstStart()
        s.sectionCount = sectionCount()
        return s
    }

    func themeMode() -> ThemeMode { ThemeMode(rawValue: defaults.integer(forKey: Key.theme)) ?? .system }

    func setThemeMode(_ m: ThemeMode) { defaults.set(m.rawValue, forKey: Key.theme) }

    // MARK: - 登录

    func user() -> String { defaults.string(forKey: Key.loginUser) ?? "" }

    var pass: String { Keychain.get("login_pass") ?? "" }

    func saveLogin(user: String, pass: String) {
        defaults.set(user, forKey: Key.loginUser)
        Keychain.set("login_pass", pass)
    }

    /// 只记学号（没勾「记住密码」时用）
    func saveUser(_ user: String) {
        defaults.set(user, forKey: Key.loginUser)
        Keychain.delete("login_pass")
    }

    func clearLogin() {
        defaults.removeObject(forKey: Key.loginUser)
        defaults.removeObject(forKey: Key.studentName)
        defaults.removeObject(forKey: Key.nameOwner)
        Keychain.delete("login_pass")
    }

    func rememberPwd() -> Bool { defaults.bool(forKey: Key.remember) }
    func setRememberPwd(_ on: Bool) { defaults.set(on, forKey: Key.remember) }

    func autoLogin() -> Bool { defaults.bool(forKey: Key.autoLogin) }
    func setAutoLogin(_ on: Bool) { defaults.set(on, forKey: Key.autoLogin) }

    /// 姓名绑定学号：换账号后旧姓名一律视为不存在
    func studentName(owner: String) -> String {
        let savedOwner = defaults.string(forKey: Key.nameOwner) ?? ""
        guard owner == savedOwner else { return "" }
        return defaults.string(forKey: Key.studentName) ?? ""
    }

    func setStudentName(_ name: String, owner: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        defaults.set(trimmed, forKey: Key.studentName)
        defaults.set(owner, forKey: Key.nameOwner)
    }

    func loginAt() -> TimeInterval { defaults.double(forKey: Key.loginAt) }

    func setLoginAt(_ t: TimeInterval = Date().timeIntervalSince1970) {
        defaults.set(t, forKey: Key.loginAt)
    }

    /// 上次连得上的教务协议（http / https）
    func originScheme() -> String { defaults.string(forKey: Key.origin) ?? "" }

    func setOriginScheme(_ s: String) { defaults.set(s, forKey: Key.origin) }

    // MARK: - 功能页快照（成绩 / 绩点）

    func saveFeature(_ type: String, _ json: String) {
        defaults.set(json, forKey: Key.featurePrefix + type)
        defaults.set(Date().timeIntervalSince1970, forKey: Key.featurePrefix + type + "_at")
    }

    func feature(_ type: String) -> String {
        defaults.string(forKey: Key.featurePrefix + type) ?? ""
    }

    func featureAt(_ type: String) -> TimeInterval {
        defaults.double(forKey: Key.featurePrefix + type + "_at")
    }
}

/// Keychain 读写（kSecClassGenericPassword，只给本 App 用）。
enum Keychain {

    private static let service = "com.tstc.zstangshi.credentials"

    static func set(_ account: String, _ value: String) {
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
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
