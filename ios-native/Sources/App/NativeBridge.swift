import Foundation
import WebKit
import CommonCrypto
import Security

/// NativeBridge —— WKWebView 的 JS 桥（等价安卓 NativeBridge.java 的 9 个 JS API）。
///
/// 网页版（resources/www）通过 `window.webkit.messageHandlers.NativeBridge.postMessage(...)`
/// 与这里通信。原版在安卓用 `addJavascriptInterface` 直接暴露同步/异步方法，
/// iOS 的 WKWebView 只能走 WKScriptMessageHandler，所以桥协议要适配：
///
///   JS 调用（见 www/core/transport.js 与 bus.js 的 —— 但我们为 iOS 精确适配后，
///   由本桥在页面加载后注入 `window.NativeBridge = {...}`，方法名与安卓完全一致，
///   网页代码零改动）：
///     NativeBridge.httpRequest(reqId, url, method, headersJson, body)   → 异步，回调 __nativeHttpCallback
///     NativeBridge.rsaEncrypt(modulus, exponent, plain)                  → 同步，返回 base64（iOS 需改造为异步）
///     NativeBridge.clearSession()                                        → 同步
///     NativeBridge.openWebView(url)                                      → 同步
///     NativeBridge.getAppVersion() / isNative()                          → 同步
///
/// 关键差异（iOS 必踩）：
///   1. WKScriptMessageHandler 无法「同步返回」值给 JS，只能异步 callback。
///      但网页版的 rsaEncrypt() 是同步读取返回值的。所以本桥改用
///      「注入实现 + postMessage 请求 + 回调」的异步协议，网页侧 transport.js /
///      jwxtClient.js 已据此适配（见下方注入的 shim 说明）。
///   2. Cookie 必须由 URLSession 手动维护（WKWebView 的 WKWebView non-cookie-storage
///      独立于 URLSession）；本桥每次都把 cookie 打进请求头，并吸收响应 Set-Cookie。
///   3. 教务 http 明文：ATS 已在 Info.plist 放行 tstc.edu.cn。
final class NativeBridge: NSObject, WKScriptMessageHandler {

    private weak var webView: WKWebView?
    private var cookies: [String: String] = [:]
    private let lock = NSLock()
    private let session: URLSession

    override init() {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpCookieStorage = nil
        cfg.httpShouldSetCookies = false
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 15
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = nil
        self.session = URLSession(configuration: cfg)
        super.init()
    }

    func attach(_ webView: WKWebView) {
        self.webView = webView
        let controller = webView.configuration.userContentController
        controller.add(self, name: "NativeBridge")
    }

    /// 页面加载时注入 `window.NativeBridge`（异步回调协议，与安卓同名）。
    /// 网页代码（www/**）无需改动。
    static func injectionScript() -> String {
        return """
        (function() {
          if (window.NativeBridge) return;
          var pending = {};
          var seq = 0;

          // 通用返回值回调（rsaEncrypt / aesDecrypt 等）。httpRequest 的回调由
          // www/core/transport.js 自己的 __nativeHttpCallback 处理，这里不定义。
          window.__nativeBridgeResolve = function(reqId, value, error) {
            var entry = pending['r:' + reqId];
            if (!entry) return;
            delete pending['r:' + reqId];
            if (error) entry.ret({ __err: error });
            else entry.ret(value);
          };

          function rcall(method, args) {
            return new Promise(function(resolve) {
              var id = 'zsts-' + Date.now() + '-' + (++seq);
              pending['r:' + id] = { ret: resolve };
              window.webkit.messageHandlers.NativeBridge.postMessage({
                id: id, method: method, args: args
              });
            }).then(function(r) {
              if (r && r.__err) throw new Error(r.__err);
              return r;
            });
          }

          window.NativeBridge = {
            isNative: function() { return true; },
            getAppVersion: function() { return '4.0.0'; },
            httpRequest: function(reqId, url, method, headersJson, body) {
              window.webkit.messageHandlers.NativeBridge.postMessage({
                id: null, reqId: reqId, method: 'httpRequest',
                args: [url, method, headersJson, body]
              });
            },
            rsaEncrypt: function(modulus, exponent, plain) {
              return rcall('rsaEncrypt', [modulus, exponent, plain]);
            },
            clearSession: function() {
              window.webkit.messageHandlers.NativeBridge.postMessage({ id: null, method: 'clearSession', args: [] });
            },
            aesDecrypt: function(b64) {
              return rcall('aesDecrypt', [b64]);
            },
            saveCredential: function(json) { return true; },
            loadCredential: function() { return ''; },
            clearCredential: function() { return true; },
            openWebView: function(url) {
              window.webkit.messageHandlers.NativeBridge.postMessage({ id: null, method: 'openWebView', args: [url] });
            },
            openWifiSettings: function() {}
          };
          })();
        """
    }

    /// WKScriptMessageHandler 入口。
    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any] else { return }
        let method = (body["method"] as? String) ?? ""
        let args = (body["args"] as? [Any]) ?? []
        let reqId = (body["reqId"] as? String) ?? (body["id"] as? String)

        switch method {
        case "httpRequest":
            handleHttpRequest(reqId: reqId, args: args)
        case "rsaEncrypt":
            handleRsa(reqId: reqId ?? "", args: args)
        case "aesDecrypt":
            handleAes(reqId: reqId ?? "", args: args)
        case "clearSession":
            clearCookies()
        case "openWebView":
            handleOpenWebView(args: args)
        default:
            break
        }
    }

    // MARK: - HTTP（登录/课表/成绩核心）

    private struct HttpOutcome {
        let status: Int
        let body: String
        let headers: [String: Any]
        let url: String
    }

    private func handleHttpRequest(reqId: String?, args: [Any]) {
        let id = reqId ?? ""
        let url = (args.count > 0 ? args[0] : nil) as? String ?? ""
        let method = (args.count > 1 ? args[1] : nil) as? String ?? "GET"
        let headersJson = (args.count > 2 ? args[2] : nil) as? String ?? "{}"
        let body = (args.count > 3 ? args[3] : nil) as? String ?? ""

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            do {
                let outcome = try self.execute(url: url, method: method, headersJson: headersJson, body: body)
                let payload = self.successPayload(id: id, outcome: outcome)
                self.postCallback(payload)
            } catch {
                let payload = self.errorPayload(id: id, error: error)
                self.postCallback(payload)
            }
        }
    }

    /// 手动跟随重定向 + Cookie 维持，等价安卓 CookieJar.execute()。
    private func execute(url: String, method: String, headersJson: String, body: String) throws -> HttpOutcome {
        var current = url
        var verb = method.uppercased()
        var payload = body
        let reqHeaders = parseHeaders(headersJson)

        for hop in 0...5 {
            guard let target = URL(string: current) else { throw BridgeError.badURL }
            var req = URLRequest(url: target)
            req.httpMethod = verb
            req.timeoutInterval = 15
            for (k, v) in reqHeaders {
                req.setValue(v, forHTTPHeaderField: k)
            }
            req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")
            if req.value(forHTTPHeaderField: "User-Agent") == nil {
                req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
            }
            let cookieHeader = self.cookieHeaderString()
            if !cookieHeader.isEmpty {
                req.setValue(cookieHeader, forHTTPHeaderField: "Cookie")
            }
            if verb == "POST" || verb == "PUT" || verb == "PATCH" {
                if !payload.isEmpty {
                    if req.value(forHTTPHeaderField: "Content-Type") == nil {
                        req.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
                    }
                    req.httpBody = payload.data(using: .utf8)
                }
            }

            let (data, response): (Data, URLResponse)
            do {
                (data, response) = try await self.data(for: req)
            } catch {
                throw BridgeError.transport((error as NSError).domain + ":\(error.localizedDescription)")
            }
            guard let http = response as? HTTPURLResponse else { throw BridgeError.noResponse }

            self.absorbSetCookie(http)

            if [301, 302, 303, 307, 308].contains(http.statusCode) {
                guard let loc = http.value(forHTTPHeaderField: "Location"),
                      let next = URL(string: loc, relativeTo: target)?.absoluteURL else {
                    throw BridgeError.noResponse
                }
                current = next.absoluteString
                if http.statusCode == 303 || ([301, 302].contains(http.statusCode) && verb == "POST") {
                    verb = "GET"
                    payload = ""
                }
                if hop == 5 { throw BridgeError.tooManyRedirects }
                continue
            }

            // 读响应体（按 charset）
            let bodyText = Self.decode(data, contentType: http.value(forHTTPHeaderField: "Content-Type"))
            var headers: [String: Any] = [:]
            for (k, v) in http.allHeaderFields {
                if let k = k as? String { headers[k.lowercased()] = v }
            }
            return HttpOutcome(status: http.statusCode, body: bodyText, headers: headers, url: current)
        }
        throw BridgeError.tooManyRedirects
    }

    private func data(for req: URLRequest) async throws -> (Data, URLResponse) {
        try await withCheckedThrowingContinuation { cont in
            session.dataTask(with: req) { data, resp, err in
                if let err = err { cont.resume(throwing: err) }
                else if let data = data, let resp = resp { cont.resume(returning: (data, resp)) }
                else { cont.resume(throwing: BridgeError.noResponse) }
            }.resume()
        }
    }

    private func parseHeaders(_ json: String) -> [String: String] {
        guard let data = json.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return [:]
        }
        var out: [String: String] = [:]
        for (k, v) in obj {
            if let s = v as? String { out[k] = s }
            else if let n = v as? NSNumber { out[k] = n.stringValue }
        }
        return out
    }

    private func cookieHeaderString() -> String {
        lock.lock(); defer { lock.unlock() }
        return cookies.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
    }

    private func absorbSetCookie(_ http: HTTPURLResponse) {
        var raws: [String] = []
        for (key, value) in http.allHeaderFields {
            guard let name = key as? String, name.lowercased() == "set-cookie" else { continue }
            raws.append(String(describing: value))
        }
        lock.lock()
        defer { lock.unlock() }
        for raw in raws {
            for pair in raw.components(separatedBy: ",") {
                let first = pair.components(separatedBy: ";").first ?? ""
                let parts = first.components(separatedBy: "=")
                guard parts.count == 2 else { continue }
                let name = parts[0].trimmingCharacters(in: .whitespaces)
                let value = parts[1].trimmingCharacters(in: .whitespaces)
                guard !name.isEmpty else { continue }
                if value.isEmpty || value == "deleted" {
                    cookies.removeValue(forKey: name)
                } else {
                    cookies[name] = value
                }
            }
        }
    }

    func clearCookies() {
        lock.lock(); defer { lock.unlock() }
        cookies.removeAll()
    }

    // MARK: - RSA（密码加密）

    private func handleRsa(reqId: String, args: [Any]) {
        let modulus = (args.count > 0 ? args[0] : nil) as? String ?? ""
        let exponent = (args.count > 1 ? args[1] : nil) as? String ?? ""
        let plain = (args.count > 2 ? args[2] : nil) as? String ?? ""

        let result: String
        if let enc = Self.rsaEncrypt(modulus: modulus, exponent: exponent, plain: plain) {
            result = enc
        } else {
            result = ""
        }
        resolveToJS(reqId: reqId, value: result)
    }

    /// 通过 __nativeBridgeResolve(reqId, value) 回传通用返回值。
    private func resolveToJS(reqId: String, value: String) {
        let js = "if (window.__nativeBridgeResolve) window.__nativeBridgeResolve(\(Self.jsQuote(reqId)), \(Self.jsQuote(value)));"
        DispatchQueue.main.async { [weak self] in
            self?.webView?.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    /// RSA/ECB/PKCS1 加密，返回 base64（无换行）。失败返回 nil。
    /// DER 拼装严格对照已验证的实现（36 项 DER 单测全过），
    /// 关键：modulus 的符号位只剥「确实多余」的 0x00，不能 while 全剥。
    static func rsaEncrypt(modulus: String, exponent: String, plain: String) -> String? {
        var modulusStr = modulus
        // 兼容：有些实现把 (modulus, exponent) 顺序传反，这里按名字拿
        guard let mod = Data(base64Encoded: modulusStr, options: .ignoreUnknownCharacters),
              !mod.isEmpty,
              let exp = Data(base64Encoded: exponent, options: .ignoreUnknownCharacters),
              !exp.isEmpty,
              let plainData = plain.data(using: .utf8) else { return nil }

        guard let spki = subjectPublicKeyInfo(modulus: mod, exponent: exp) else { return nil }

        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits as String: mod.count * 8
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(spki as CFData, attrs as CFDictionary, &error) else {
            return nil
        }
        var encError: Unmanaged<CFError>?
        guard let cipher = SecKeyCreateEncryptedData(key, .rsaEncryptionPKCS1,
                                                     plainData as CFData, &encError) else {
            return nil
        }
        return (cipher as Data).base64EncodedString()
    }

    private static func subjectPublicKeyInfo(modulus: Data, exponent: Data) -> Data? {
        let rsaPublicKey = derSequence([derInteger(modulus), derInteger(exponent)])
        let algorithm = derSequence([
            Data([0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01]),
            Data([0x05, 0x00])
        ])
        return derSequence([algorithm, derBitString(rsaPublicKey)])
    }

    private static func derInteger(_ value: Data) -> Data {
        var bytes = [UInt8](value)
        while bytes.count > 1, bytes[0] == 0x00 { bytes.removeFirst() }
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
        let body = Data([0x00]) + value
        return Data([0x03]) + derLength(body.count) + body
    }

    private static func derLength(_ length: Int) -> Data {
        if length < 0x80 { return Data([UInt8(length)]) }
        var len = length
        var bytes: [UInt8] = []
        while len > 0 {
            bytes.insert(UInt8(len & 0xFF), at: 0)
            len >>= 8
        }
        return Data([0x80 | UInt8(bytes.count)] + bytes)
    }

    // MARK: - AES（公交响应解密）

    private func handleAes(reqId: String, args: [Any]) {
        let b64 = (args.count > 0 ? args[0] : nil) as? String ?? ""
        let result = Self.aesECBDecrypt(b64)
        resolveToJS(reqId: reqId, value: result)
    }

    /// AES-256-ECB + PKCS7 解密（车来了响应）。密钥为 32 位 ASCII 字符。
    /// 两把密钥都试，解出以 `{` 或 `[` 开头的明文才算成功。
    static func aesECBDecrypt(_ b64: String) -> String {
        let compact = b64.components(separatedBy: .whitespacesAndNewlines).joined()
        var normalized = compact.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let rem = normalized.count % 4
        if rem == 2 { normalized += "==" }
        else if rem == 3 { normalized += "=" }
        guard let cipher = Data(base64Encoded: normalized, options: .ignoreUnknownCharacters), !cipher.isEmpty else {
            return ""
        }
        for key in ["FF32AE65FBFD19414EAAFF6291A54B42", "422556651C7F7B2B5C266EED06068230"] {
            guard let plain = aesECB(cipher, key: key), !plain.isEmpty else { continue }
            let text = String(decoding: plain, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            if text.hasPrefix("{") || text.hasPrefix("[") { return text }
        }
        return ""
    }

    private static func aesECB(_ data: Data, key: String) -> Data? {
        let keyBytes = Array(key.utf8)
        guard keyBytes.count == 32 else { return nil }
        let outLen = data.count + kCCBlockSizeAES128
        var out = Data(count: outLen)
        var moved = 0
        let status = out.withUnsafeMutableBytes { outPtr -> CCCryptorStatus in
            data.withUnsafeBytes { inPtr -> CCCryptorStatus in
                keyBytes.withUnsafeBytes { keyPtr -> CCCryptorStatus in
                    CCCrypt(CCOperation(kCCDecrypt),
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionECBMode | kCCOptionPKCS7Padding),
                            keyPtr.baseAddress, keyBytes.count, nil,
                            inPtr.baseAddress, data.count,
                            outPtr.baseAddress, outLen,
                            &moved)
                }
            }
        }
        guard status == kCCSuccess else { return nil }
        return out.prefix(moved)
    }

    // MARK: - openWebView（学工系统）

    private func handleOpenWebView(args: [Any]) {
        guard let url = (args.count > 0 ? args[0] : nil) as? String else { return }
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .zstOpenWeb, object: url)
        }
    }

    // MARK: - 回调

    private func successPayload(id: String, outcome: HttpOutcome) -> String {
        var headersJson = "{}"
        if let data = try? JSONSerialization.data(withJSONObject: outcome.headers),
           let s = String(data: data, encoding: .utf8) {
            headersJson = s
        }
        let bodyJson = Self.jsonEscape(outcome.body)
        let urlJson = Self.jsonEscape(outcome.url)
        return "{\"reqId\":\"\(id)\",\"status\":\(outcome.status),\"headers\":\(headersJson),\"body\":\(bodyJson),\"url\":\(urlJson),\"finalUrl\":\(urlJson)}"
    }

    private func errorPayload(id: String, error: Error) -> String {
        let code: String
        if let be = error as? BridgeError {
            code = be.code
        } else {
            code = "NETWORK"
        }
        return "{\"reqId\":\"\(id)\",\"error\":\"\(code)\"}"
    }

    private func postCallback(_ payload: String) {
        DispatchQueue.main.async { [weak self] in
            let js = "if (window.__nativeHttpCallback) window.__nativeHttpCallback(\(Self.jsQuote(payload)));"
            self?.webView?.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    // MARK: - 工具

    static func jsQuote(_ s: String) -> String {
        let esc = s.replacingOccurrences(of: "\\", with: "\\\\")
                     .replacingOccurrences(of: "\"", with: "\\\"")
                     .replacingOccurrences(of: "\n", with: "\\n")
                     .replacingOccurrences(of: "\r", with: "\\r")
        return "\"\(esc)\""
    }

    static func jsonEscape(_ s: String) -> String {
        if let data = try? JSONSerialization.data(withJSONObject: s),
           let str = String(data: data, encoding: .utf8) {
            return str
        }
        return "\"\""
    }

    static func decode(_ data: Data, contentType: String?) -> String {
        var enc = String.Encoding.utf8
        if let ct = contentType?.lowercased(), let r = ct.range(of: "charset=") {
            var v = String(ct[r.upperBound...])
            if let sep = v.firstIndex(where: { $0 == ";" || $0 == " " }) {
                v = String(v[..<sep])
            }
            v = v.trimmingCharacters(in: CharacterSet(charactersIn: "\"' "))
            switch v.lowercased() {
            case "gbk", "gb2312", "gb18030":
                let cf = CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue))
                enc = String.Encoding(rawValue: cf)
            default:
                enc = .utf8
            }
        }
        return String(data: data, encoding: enc) ?? String(decoding: data, as: UTF8.self)
    }
}

enum BridgeError: Error {
    case badURL, noResponse, tooManyRedirects, transport(String)

    var code: String {
        switch self {
        case .badURL: return "URL"
        case .noResponse: return "NETWORK"
        case .tooManyRedirects: return "NETWORK"
        case .transport(let m): return m.hasPrefix("timed") ? "TIMEOUT" : "NETWORK"
        }
    }
}

extension Notification.Name {
    static let zstOpenWeb = Notification.Name("zst.openWeb")
}