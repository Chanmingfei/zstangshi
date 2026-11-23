import Foundation
import Security

/// 正方教务的密码加密：`RSA/ECB/PKCS1Padding`。
///
/// 安卓一行 `Cipher.getInstance("RSA/ECB/PKCS1Padding")` 就完事，因为 Java 的
/// KeyFactory 认 `RSAPublicKeySpec(modulus, exponent)`；而 iOS 的 Security 框架
/// 只接受 **DER 编码的公钥**，所以必须自己把教务给的两个 base64 大整数拼成
/// SubjectPublicKeyInfo。这一步是整个 iOS 迁移最容易翻车的地方：
///
///   - 教务给的 modulus 经常**自带一个 0x00 符号位**，剥多剥少都会让
///     `SecKeyCreateWithData` 返回 nil（表现为「登录必失败」）；
///   - 长度字段要动态算，不能写死 2048 位（换学校 / 换密钥长度就崩）。
enum RSASPKI {

    /// 返回 PKCS#1 v1.5 加密后的密文；失败返回 nil。
    static func encrypt(password: String, modulusB64: String, exponentB64: String) -> Data? {
        guard let modulus = base64(modulusB64), let exponent = base64(exponentB64) else { return nil }
        guard !modulus.isEmpty, !exponent.isEmpty else { return nil }

        let spki = subjectPublicKeyInfo(modulus: modulus, exponent: exponent)
        let bits = normalize(modulus).count * 8
        let attrs: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass: kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits: bits,
        ]
        var err: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(spki as CFData, attrs as CFDictionary, &err) else {
            return nil
        }
        guard let data = SecKeyCreateEncryptedData(
            key, .rsaEncryptionPKCS1, Data(password.utf8) as CFData, &err
        ) else { return nil }
        return data as Data
    }

    /// base64 容错：去掉换行/空格，补 `=` 到 4 的倍数。
    static func base64(_ s: String) -> Data? {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        t = t.replacingOccurrences(of: "\n", with: "")
             .replacingOccurrences(of: "\r", with: "")
             .replacingOccurrences(of: " ", with: "")
        if t.isEmpty { return nil }
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t, options: [.ignoreUnknownCharacters])
    }

    /// 去掉大整数的高位 0x00（DER INTEGER 的符号位由编码器重新补）。
    static func normalize(_ v: Data) -> Data {
        var d = v
        while d.count > 1 && d[0] == 0 { d.removeFirst() }
        return d
    }

    // MARK: - DER

    private static func length(_ n: Int) -> Data {
        if n < 0x80 { return Data([UInt8(n)]) }
        var v = n
        var bytes: [UInt8] = []
        while v > 0 {
            bytes.insert(UInt8(v & 0xFF), at: 0)
            v >>= 8
        }
        return Data([0x80 | UInt8(bytes.count)] + bytes)
    }

    private static func tlv(_ tag: UInt8, _ body: Data) -> Data {
        var d = Data([tag])
        d.append(length(body.count))
        d.append(body)
        return d
    }

    /// DER INTEGER：正数且最高位为 1 时补 0x00，否则会被当成负数。
    private static func integer(_ raw: Data) -> Data {
        var v = normalize(raw)
        if let first = v.first, first & 0x80 != 0 { v.insert(0, at: 0) }
        return tlv(0x02, v)
    }

    /// SubjectPublicKeyInfo = SEQ{ AlgorithmIdentifier, BIT STRING{ RSAPublicKey } }
    static func subjectPublicKeyInfo(modulus: Data, exponent: Data) -> Data {
        let rsaPublicKey = tlv(0x30, integer(modulus) + integer(exponent))
        // AlgorithmIdentifier: OID 1.2.840.113549.1.1.1 (rsaEncryption) + NULL
        let oid: [UInt8] = [0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00]
        let algorithm = tlv(0x30, Data(oid))
        let bitString = tlv(0x03, Data([0x00]) + rsaPublicKey)
        return tlv(0x30, algorithm + bitString)
    }
}
