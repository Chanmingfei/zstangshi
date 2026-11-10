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
/// 完成加密 —— 与 Java 的 PKCS#1 v1.5 输出完全一致（教务服务端只认这个填充）。
enum RSA {

    static func encrypt(password: String, modulusB64: String, exponentB64: String) -> String? {
        guard let modulus = Data(base64Encoded: modulusB64, options: .ignoreUnknownCharacters),
              let exponent = Data(base64Encoded: exponentB64, options: .ignoreUnknownCharacters),
              !modulus.isEmpty, !exponent.isEmpty,
              let spki = subjectPublicKeyInfo(modulus: modulus, exponent: exponent)
        else { return nil }

        var error: Unmanaged<CFError>?
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits as String: modulus.count * 8
        ]
        guard let key = SecKeyCreateWithData(spki as CFData, attrs as CFDictionary, &error),
              let plain = password.data(using: .utf8),
              let cipher = SecKeyCreateEncryptedData(key, .rsaEncryptionPKCS1,
                                                     plain as CFData, &error)
        else { return nil }

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

    private static func derInteger(_ value: Data) -> Data {
        var bytes = value
        // 去掉无意义的前导 0（教务给的 BigInteger 可能带符号位 0）
        while bytes.count > 1 && bytes.first == 0x00 {
            bytes.removeFirst()
        }
        // DER 的 INTEGER 是带符号的：最高位是 1 时必须补一个 0，否则会被当负数
        if let first = bytes.first, first & 0x80 != 0 {
            bytes.insert(0x00, at: 0)
        }
        return Data([0x02]) + derLength(bytes.count) + bytes
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
