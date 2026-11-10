import Foundation

/// 带 Cookie 的 HTTP 会话（Android `JwClient` 里 `HttpURLConnection` + `CookieManager` 的等价物）。
///
/// 为什么要自己管 Cookie、不让 URLSession 自动管：
///   正方教务登录是「POST → 302 → 302 → 菜单页」，JSESSIONID 在中间那跳的响应头里。
///   让 URLSession 自动跟随重定向时，每一跳都会沿用**上一跳**的 Cookie 头，
///   第二次跳转就被静默踢回登录页（页面没有任何错误文案，看起来像密码错）。
///   所以这里：关掉自动重定向与自动 Cookie，手动最多跟 5 跳、每跳重新取 Cookie。
final class Http {

    enum Failure: Error {
        case badURL(String)
        case transport(String)
        case redirectLoop
        case noResponse
    }

    struct Outcome {
        let status: Int
        let body: String
        let header: [String: String]
    }

    static let timeout: TimeInterval = 20
    private static let maxHops = 5

    /// Cookie：只记 name → value，够用且不会因为 domain/path 规则写错而丢会话
    private var cookies: [String: String] = [:]

    private static let session: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpCookieStorage = nil          // 关键：Cookie 只由本类管理
        cfg.httpShouldSetCookies = false
        cfg.timeoutIntervalForRequest = timeout
        cfg.timeoutIntervalForResource = timeout
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = nil
        return URLSession(configuration: cfg)
    }()

    private static let userAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
        + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    var cookieHeader: String {
        cookies.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
    }

    func clearCookies() { cookies.removeAll() }

    /// 把会话交给 WKWebView（「更多功能 / 选课」打开原站点时不用再登一次）
    func cookiesForWebView() -> [String: String] { cookies }

    // MARK: - 对外

    func get(_ urlString: String) async -> String? {
        (try? await request(method: "GET", url: urlString, form: nil))?.body
    }

    func post(_ urlString: String, form: [String: String]) async -> String? {
        (try? await request(method: "POST", url: urlString, form: form))?.body
    }

    // MARK: - 核心

    func request(method: String, url: String, form: [String: String]?) async throws -> Outcome {
        var current = url
        var verb = method
        var payload = form.map(Self.encodeForm) ?? ""

        for hop in 0...Self.maxHops {
            guard let target = URL(string: current) else { throw Failure.badURL(current) }
            var req = URLRequest(url: target)
            req.httpMethod = verb
            req.timeoutInterval = Self.timeout
            req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
            req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")
            if !cookies.isEmpty {
                req.setValue(cookieHeader, forHTTPHeaderField: "Cookie")
            }
            if verb == "POST" {
                req.setValue("application/x-www-form-urlencoded; charset=UTF-8",
                             forHTTPHeaderField: "Content-Type")
                req.httpBody = payload.data(using: .utf8)
            }

            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await Self.session.data(for: req)
            } catch let e as URLError {
                throw Failure.transport(Self.describe(e))
            } catch {
                throw Failure.transport(String(describing: error))
            }
            guard let http = response as? HTTPURLResponse else { throw Failure.noResponse }

            absorbSetCookie(http)
            let body = Self.decode(data, contentType: http.value(forHTTPHeaderField: "Content-Type"))

            if [301, 302, 303, 307, 308].contains(http.statusCode) {
                guard let loc = http.value(forHTTPHeaderField: "Location"),
                      let next = URL(string: loc, relativeTo: target)?.absoluteURL else {
                    throw Failure.noResponse
                }
                current = next.absoluteString
                // 浏览器语义：301/302 的 POST 与 303 一律降级成 GET 并丢 body
                if http.statusCode == 303 || ([301, 302].contains(http.statusCode) && verb == "POST") {
                    verb = "GET"
                    payload = ""
                }
                if hop == Self.maxHops { throw Failure.redirectLoop }
                continue
            }

            var header: [String: String] = [:]
            for (k, v) in http.allHeaderFields {
                if let k = k as? String { header[k.lowercased()] = String(describing: v) }
            }
            return Outcome(status: http.statusCode, body: body, header: header)
        }
        throw Failure.redirectLoop
    }

    // MARK: - Cookie

    private func absorbSetCookie(_ response: HTTPURLResponse) {
        let raws: [String]
        if let single = response.value(forHTTPHeaderField: "Set-Cookie") {
            raws = [single]
        } else {
            raws = response.allHeaderFields.compactMap { key, value -> String? in
                guard let name = key as? String, name.lowercased() == "set-cookie" else { return nil }
                return value as? String
            }
        }
        for raw in raws {
            for pair in Self.splitSetCookie(raw) {
                let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                guard parts.count == 2 else { continue }
                let name = parts[0].trimmingCharacters(in: .whitespaces)
                let value = parts[1].trimmingCharacters(in: .whitespaces)
                guard !name.isEmpty else { continue }
                if value.isEmpty {
                    cookies.removeValue(forKey: name)
                } else {
                    cookies[name] = value
                }
            }
        }
    }

    /// 一个 Set-Cookie 头里可能塞了多条（逗号分隔），但 Expires 里也有逗号。
    /// 只在「逗号后面紧跟 name=」的位置切，避免把日期切断。
    static func splitSetCookie(_ raw: String) -> [String] {
        var out: [String] = []
        var buffer = ""
        let chars = Array(raw)
        var i = 0
        while i < chars.count {
            let ch = chars[i]
            if ch == "," {
                var j = i + 1
                while j < chars.count, chars[j] == " " { j += 1 }
                var k = j
                while k < chars.count, chars[k] != "=", chars[k] != ";", chars[k] != "," { k += 1 }
                let looksLikeCookie = k < chars.count && chars[k] == "=" && k > j
                if looksLikeCookie {
                    out.append(buffer)
                    buffer = ""
                    i = j
                    continue
                }
            }
            buffer.append(ch)
            i += 1
        }
        if !buffer.isEmpty { out.append(buffer) }
        return out.map { part -> String in
            // 去掉属性段，只留 name=value
            let first = part.split(separator: ";").first.map(String.init) ?? part
            return first.trimmingCharacters(in: .whitespaces)
        }.filter { !$0.isEmpty }
    }

    // MARK: - 工具

    static func encodeForm(_ form: [String: String]) -> String {
        form.map { key, value in
            "\(escape(key))=\(escape(value))"
        }.joined(separator: "&")
    }

    private static func escape(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }

    /// 按 Content-Type 的 charset 解码；没写 charset 先 UTF-8，再退回 GB18030
    static func decode(_ data: Data, contentType: String?) -> String {
        if let name = charsetName(contentType) {
            let cf = CFStringConvertIANACharSetNameToEncoding(name as CFString)
            if cf != kCFStringEncodingInvalidId {
                let enc = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cf))
                if let text = String(data: data, encoding: enc) { return text }
            }
        }
        if let text = String(data: data, encoding: .utf8) { return text }
        let gb = CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue))
        if let text = String(data: data, encoding: String.Encoding(rawValue: gb)) { return text }
        return String(decoding: data, as: UTF8.self)
    }

    private static func charsetName(_ contentType: String?) -> String? {
        guard let ct = contentType?.lowercased(), let r = ct.range(of: "charset=") else { return nil }
        var v = String(ct[r.upperBound...])
        if let sep = v.firstIndex(where: { $0 == ";" || $0 == " " }) { v = String(v[..<sep]) }
        v = v.trimmingCharacters(in: CharacterSet(charactersIn: "\"' "))
        return v.isEmpty ? nil : v
    }

    private static func describe(_ e: URLError) -> String {
        switch e.code {
        case .timedOut: return "TIMEOUT"
        case .cannotConnectToHost, .cannotFindHost, .notConnectedToInternet,
             .networkConnectionLost, .dnsLookupFailed:
            return "NETWORK"
        case .secureConnectionFailed, .serverCertificateUntrusted:
            return "SSL"
        default:
            return "IO(\(e.code.rawValue))"
        }
    }
}
