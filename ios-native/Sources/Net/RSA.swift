import Foundation
import Security

/// 正方教务登录的密码加密：`RSA/ECB/PKCS1Padding`。
///
/// Android 侧一行 `Cipher.getInstance("RSA/ECB/PKCS1Padding")` 就完事，因为 Java 的
/// `RSAPublicKeySpec` 直接吃 (modulus, exponent) 两个大整数。iOS 没有这条捷径：
/// Security 框架只接受 **DER 编码的公钥**（SPKI），所以这里自己把教务给的
/// base64(modulus) / base64(exponent) 拼成 `SubjectPublicKeyInfo`：
///
///     SEQUENCE {                       -- SubjectPublicKeyInfo
///       SEQUENCE {                     -- AlgorithmIdentifier
///         OID 1.2.840.113549.1.1.1     -- rsaEncryption
///         NULL
///       }
///       BIT STRING {                   -- 真正被包裹的 RSAPublicKey
///         SEQUENCE { INTEGER n, INTEGER e }
///       }
///     }
///
/// 拼好之后 `SecKeyCreateWithData` 建公钥，`SecKeyCreateEncryptedData(.rsaEncryptionPKCS1)`
/// 完成加密 —— 与 Java 的 PKCS#1 v1.5 输出完全一致。
///
/// ⚠️ 上一版的 bug（本次已修）：
///   `derInteger` 用 `while bytes.first == 0x00 { removeFirst() }` 剥前导零。
///   教务返回的 modulus 是 `BigInteger.toByteArray()` 的产物，最高位为 1 时
///   **必然多一个 0x00 符号位**；全剥掉后模数变了，`SecKeyCreateWithData` 返回 nil，
///   用户看到的是「密码加密失败」—— 且与网络无关，必现。
///   正确做法：只在「确实有符号位」时剥掉那一个 0x00。
enum RSA {

    struct EncryptError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    static func encrypt(password: String, modulusB64: String, exponentB64: String) -> String? {
        (try? encryptThrowing(password: password,
                              modulusB64: modulusB64,
                              exponentB64: exponentB64))
    }

    /// 带具体错误原因的版本。上层据此给出「公钥格式不支持」这类可行动的提示，
    /// 而不是笼统的「密码加密失败」。
    static func encryptThrowing(password: String, modulusB64: String,
                                exponentB64: String) throws -> String {
        guard let modulus = Data(base64Encoded: modulusB64, options: .ignoreUnknownCharacters),
              !modulus.isEmpty else {
            throw EncryptError(message: "教务公钥 modulus 解析失败")
        }
        guard let exponent = Data(base64Encoded: exponentB64, options: .ignoreUnknownCharacters),
              !exponent.isEmpty else {
            throw EncryptError(message: "教务公钥 exponent 解析失败")
        }
        guard let spki = subjectPublicKeyInfo(modulus: modulus, exponent: exponent) else {
            throw EncryptError(message: "教务公钥格式不支持")
        }

        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits as String: modulus.count * 8
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(spki as CFData, attrs as CFDictionary, &error) else {
            let desc = error.map { String(describing: $0.takeRetainedValue()) } ?? "未知错误"
            throw EncryptError(message: "公钥装载失败：\(desc)")
        }
        guard let plain = password.data(using: .utf8) else {
            throw EncryptError(message: "密码编码失败")
        }
        var encError: Unmanaged<CFError>?
        guard let cipher = SecKeyCreateEncryptedData(key, .rsaEncryptionPKCS1,
                                                     plain as CFData, &encError) else {
            let desc = encError.map { String(describing: $0.takeRetainedValue()) } ?? "未知错误"
            throw EncryptError(message: "RSA 加密失败：\(desc)")
        }
        return (cipher as Data).base64EncodedString()
    }

    // MARK: - DER 拼装

    private static func subjectPublicKeyInfo(modulus: Data, exponent: Data) -> Data? {
        let rsaPublicKey = derSequence([derInteger(modulus), derInteger(exponent)])
        let algorithm = derSequence([
            // OID 1.2.840.113549.1.1.1（rsaEncryption）
            Data([0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01]),
            Data([0x05, 0x00])                       // NULL
        ])
        return derSequence([algorithm, derBitString(rsaPublicKey)])
    }

    /// DER INTEGER（带符号大端）。
    ///
    /// 处理规则与 Java 的 `BigInteger.toByteArray()` 输出保持一致：
    ///   · 去掉「多余的」前导 0（保留恰好一个符号位 0x00，且只有下一位高位为 1 时才需要）；
    ///   · 若最高位仍为 1（说明没符号位），补一个 0x00，避免被解析成负数。
    ///
    /// 注意不能简单 while 剥零：modulus 本身的字节序列首字节可能真的是 0x00 表示符号，
    /// 后面紧跟 0x80…，剥掉后模数就少一个字节，位长全错。
    private static func derInteger(_ value: Data) -> Data {
        var bytes = [UInt8](value)
        // 1) 去掉多余的前导零，但至少留 1 个字节
        while bytes.count > 1, bytes[0] == 0x00 {
            bytes.removeFirst()
        }
        // 2) 最高位为 1 → 需要补符号位 0x00
        if let first = bytes.first, (first & 0x80) != 0 {
            bytes.insert(0x00, at: 0)
        }
        return Data([0x02]) + derLength(bytes.count) + Data(bytes)
    }

    private static func derSequence(_ items: [Data]) -> Data {
        let body = items.reduce(into: Data()) { $0.append($1) }
        return Data([0x30]) + derLength(body.count) + body
    }

    private static func derBitString(_ value: Data) -> Data {
        // 前置 0x00 表示「后面没有未使用的比特位」
        let body = Data([0x00]) + value
        return Data([0x03]) + derLength(body.count) + body
    }

    private static func derLength(_ length: Int) -> Data {
        if length < 0x80 {
            return Data([UInt8(length)])
        }
        var len = length
        var bytes: [UInt8] = []
        while len > 0 {
            bytes.insert(UInt8(len & 0xFF), at: 0)
            len >>= 8
        }
        return Data([0x80 | UInt8(bytes.count)] + bytes)
    }
}