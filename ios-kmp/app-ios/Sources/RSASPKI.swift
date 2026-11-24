import Foundation
import Security

/// 正方教务的密码加密：`RSA/ECB/PKCS1Padding`。
///
/// 安卓一行 `Cipher.getInstance("RSA/ECB/PKCS1Padding")` 就完事，因为 Java 的
/// KeyFactory 认 `RSAPublicKeySpec(modulus, exponent)`；而 iOS 的 Security 框架
/// 只接受 **DER 编码的公钥**，所以必须自己把教务给的两个 base64 大整数拼成
/// SubjectPublicKeyInfo。这一步是整个 iOS 迁移最容易翻车的地方。
///
/// v4.0.9 修复：真机日志出现「密码加密失败」，但同一个公钥在 Python/openssl
/// 里拼出的 SPKI 完全合法、能加密。差异只可能在 `SecKeyCreateWithData` 的属性：
/// 旧代码传了 `kSecAttrKeySizeInBits`（由 normalize 后的字节数 *8 推得），
/// 而教务 modulus 带前导 0x00 符号位，normalize 后是 128 字节 → 1024，
/// 但 SPKI 里的 INTEGER 是 129 字节。部分 iOS 版本会用这个不一致校验失败。
/// 修复：不传 keySize（让系统从 SPKI 自行推导），并依次尝试多种 SPKI/属性组合，
/// 每一步用 Diag 记录，便于下一次真机日志直接确诊。
enum RSASPKI {

    /// 返回 PKCS#1 v1.5 加密后的密文；失败返回 nil。
    static func encrypt(password: String, modulusB64: String, exponentB64: String) -> Data? {
        guard let modulus = base64(modulusB64), let exponent = base64(exponentB64) else {
            Diag.log("rsa", "base64 decode failed mod=\(modulusB64.count) exp=\(exponentB64.count)")
            return nil
        }
        guard !modulus.isEmpty, !exponent.isEmpty else {
            Diag.log("rsa", "empty modulus/exponent")
            return nil
        }
        let m = normalize(modulus)
        Diag.log("rsa", "modulus raw=\(modulus.count) norm=\(m.count) exp=\(exponent.count)")

        // 依次尝试：不同 modulus 编码（是否剥符号位）× 是否带前导零 × 是否传 keySize。
        // 真机上只要有一种成功即可；全部失败时日志会列出每个组合的错误码。
        let modVariants: [Data] = dedup([
            modulus,                  // 原始（含前导 0x00 符号位）
            m,                        // 剥掉前导 0x00
            m.first == 0 ? m : Data([0]) + m,  // 强制带 0x00 符号位
        ])
        let expVariants: [Data] = dedup([exponent, normalize(exponent)])

        for modV in modVariants {
            for expV in expVariants {
                let spki = subjectPublicKeyInfo(modulus: modV, exponent: expV)
                // 组合 A：只给类型/类别（推荐，让系统从 SPKI 推导位数）
                if let key = makeKey(spki, keySize: nil),
                   let cipher = encryptData(key, password) {
                    Diag.log("rsa", "OK variant mod=\(modV.count) exp=\(expV.count) noKeySize")
                    return cipher
                }
                // 组合 B：传 keySize（兼容老系统）
                let bits = m.count * 8
                if let key = makeKey(spki, keySize: bits),
                   let cipher = encryptData(key, password) {
                    Diag.log("rsa", "OK variant mod=\(modV.count) exp=\(expV.count) keySize=\(bits)")
                    return cipher
                }
            }
        }
        Diag.log("rsa", "ALL variants failed")
        return nil
    }

    private static func dedup(_ xs: [Data]) -> [Data] {
        var out: [Data] = []
        for x in xs where !out.contains(x) { out.append(x) }
        return out
    }

    private static func makeKey(_ spki: Data, keySize: Int?) -> SecKey? {
        var attrs: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass: kSecAttrKeyClassPublic,
        ]
        if let bits = keySize { attrs[kSecAttrKeySizeInBits] = bits }
        var err: Unmanaged<CFError>?
        let key = SecKeyCreateWithData(spki as CFData, attrs as CFDictionary, &err)
        if key == nil {
            let code = err.map { CFErrorGetCode($0.takeRetainedValue()) } ?? -1
            Diag.log("rsa", "makeKey fail keySize=\(keySize.map(String.init) ?? "nil") code=\(code)")
        }
        return key
    }

    private static func encryptData(_ key: SecKey, _ password: String) -> Data? {
        var err: Unmanaged<CFError>?
        guard let data = SecKeyCreateEncryptedData(
            key, .rsaEncryptionPKCS1, Data(password.utf8) as CFData, &err
        ) else {
            let code = err.map { CFErrorGetCode($0.takeRetainedValue()) } ?? -1
            Diag.log("rsa", "encryptData fail code=\(code)")
            return nil
        }
        return data as Data
    }

    /// base64 容错：去掉换行/空格/JSON 转义的反斜杠，补 `=` 到 4 的倍数。
    static func base64(_ s: String) -> Data? {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        t = t.replacingOccurrences(of: "\n", with: "")
             .replacingOccurrences(of: "\r", with: "")
             .replacingOccurrences(of: " ", with: "")
             .replacingOccurrences(of: "\\", with: "")   // 教务 JSON 里 \/ 转义
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
