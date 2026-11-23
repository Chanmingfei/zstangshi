import Foundation
import Security

/// 极简 Keychain 封装：只存学号和密码。
///
/// 为什么密码不能放 UserDefaults：iOS 的 UserDefaults 是明文 plist，
/// 备份/越狱/文件共享都可能把它读走。Keychain 至少受系统加密保护。
enum Keychain {
    private static let service = "com.tstc.zstangshi.jw"

    static func saveNo(_ value: String) { put("studentNo", value) }
    static func savePassword(_ value: String) { put("password", value) }

    static var studentNo: String { get("studentNo") ?? "" }
    static var password: String { get("password") ?? "" }

    static func deletePassword() { remove("password") }

    private static func put(_ key: String, _ value: String) {
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        // 只在本机可用、解锁后可读，且不随 iCloud 同步
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    private static func get(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func remove(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
