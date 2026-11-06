import Foundation
import Security

/**
 * CredentialStore —— Keychain 版 SecurePrefs（对应 Android SecurePrefs.java）。
 *
 * §8 共享知识第 10 条：密码只在内存里，落盘的凭据必须加密。
 * Android 用 AndroidKeyStore 的 AES-256/GCM 自行加密后写 SharedPreferences；
 * iOS 不该自己造加密层 —— 系统 Keychain 本身就是加密存储，直接用它更安全也更省事：
 *   · kSecClassGenericPassword + 独立 service，与其它 App 隔离；
 *   · kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly：设备首次解锁后可读（后台可用），
 *     且**不随 iCloud 备份迁移到新设备** —— 换机后读不到，与 Android 换机后
 *     Keystore key 失效、decrypt 失败返回 null 的行为一致，JS 侧自然会要求重新登录。
 *
 * 所有方法都是同步的，失败一律返回 false / nil，绝不抛异常把 WebView 弄崩。
 */
enum CredentialStore {

    private static let service = "com.tstc.zstangshi.credential"
    private static let account = "credential"

    private static var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    @discardableResult
    static func save(_ value: String) -> Bool {
        guard !value.isEmpty else { return false }
        var query = baseQuery
        SecItemDelete(query as CFDictionary)

        query[kSecValueData as String] = Data(value.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        if status != errSecSuccess {
            // 写失败只影响「下次自动登录」，不让调用方感知异常
            return false
        }
        return true
    }

    static func load() -> String? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func clear() {
        SecItemDelete(baseQuery as CFDictionary)
    }
}
