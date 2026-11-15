import Foundation

/// 带 Cookie 的 HTTP 会话（Android `JwClient` 里 `HttpURLConnection` + `CookieManager` 的等价物）。
///
/// 为什么自己管 Cookie、不让 URLSession 自动管：
///   正方教务登录是「POST → 302 → 302 → 菜单页」，JSESSIONID 在中间那跳的响应头里。
///   让 URLSession 自动跟随重定向时，每一跳都会沿用**上一跳**的 Cookie 头，
///   第二次跳转就被静默踢回登录页（页面没有任何错误文案，看起来像密码错）。
///   所以这里：关掉自动重定向与自动 Cookie，手动最多跟 5 跳、每跳重新取 Cookie。
///
/// ⚠️ 上一版 iOS 版在这里有个致命 bug（本次已修）：
///   旧代码用 `response.value(forHTTPHeaderField: "Set-Cookie")` 取 Cookie，
///   **这个 API 只返回第一个 Set-Cookie**。正方登录的 302 会同时下发
///   `JSESSIONID` 和 `route`，丢一个就会被负载均衡打回未登录节点。
///   正确做法是遍历 `allHeaderFields` 里**所有** Set-Cookie 条目。
final class Http {

    enum Failure: Error, LocalizedError {
        case badURL(String)
        case transport(String)
        case redirectLoop
        case noResponse

        var errorDescription: String? {
            switch self {
            case .badURL(let u): return "地址无效：\(u)"
            case .transport(let m): return "网络错误：\(m)"
            case .redirectLoop: return "重定向次数过多"
            case .noResponse: return "没有收到响应"
            }
        }
    }

    struct Outcome {
        let status: Int
        let body: String
        let header: [String: String]
    }

    static let timeout: TimeInterval = 20
    private static let maxHops = 5

    /// Cookie：记 name → value。不按 domain/path 规则存，
    /// 因为教务会话只在单一 host 内流转，简化后反而不会因为规则写错而丢会话。
    private var cookies: [String: String] = [:]
    private let lock = NSLock()

    private static let session: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpCookieStorage = nil          // 关键：Cookie 只由本类管理
        cfg.httpShouldSetCookies = false
        cfg.timeoutIntervalForRequest = timeout
        cfg.timeoutIntervalForResource = timeout
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = nil
        // 教务在校外是 http 明文：不能让 ATS 之外的配置再拦一次
        cfg.httpAdditionalHeaders = [:]
        return URLSession(configuration: cfg)
    }()

    /// 伪装成移动 Safari。教务对非浏览器 UA 会直接返回桌面布局，解析会全错。
    private static let userAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
        + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    // MARK: - Cookie 视图

    var cookieHeader: String {
        lock.lock(); defer { lock.unlock() }
        return cookies.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
    }

    func clearCookies() {
        lock.lock(); defer { lock.unlock() }
        cookies.removeAll()
    }

    /// 把会话交给 WKWebView（「更多功能 / 选课」打开原站点时不用再登一次）
    func cookiesForWebView() -> [String: String] {
        lock.lock(); defer { lock.unlock() }
        return cookies
    }

    // MARK: - 对外

    func get(_ urlString: String) async -> String? {
        (try? await request(method: "GET", url: urlString, form: nil))?.body
    }

    func post(_ urlString: String, form: [(String, String)]) async -> String? {
        (try? await request(method: "POST", url: urlString, form: form))?.body
    }

    func post(_ urlString: String, form: [String: String]) async -> String? {
        await post(urlString, form: form.sorted { $0.key < $1.key })
    }

    // MARK: - 核心

    func request(method: String, url: String, form: [(String, String)]?) async throws -> Outcome {
        var current = url
        var verb = method
        var payload = form.map(Self.encodeForm) ?? ""

        var hop = 0
        while hop <= Self.maxHops {
            guard let target = URL(string: current) else { throw Failure.badURL(current) }
            var req = URLRequest(url: target)
            req.httpMethod = verb
            req.timeoutInterval = Self.timeout
            req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
            req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")
            req.setValue("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                         forHTTPHeaderField: "Accept")
            let ch = cookieHeader
            if !ch.isEmpty {
                req.setValue(ch, forHTTPHeaderField: "Cookie")
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
                hop += 1
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

    // MARK: - Cookie 吸收

    /// ⚠️ 关键修复点：必须遍历 allHeaderFields 取出**全部** Set-Cookie，
    /// 不能用 `value(forHTTPHeaderField:)`（它只返回第一个）。
    private func absorbSetCookie(_ response: HTTPURLResponse) {
        var raws: [String] = []
        for (key, value) in response.allHeaderFields {
            guard let name = key as? String,
                  name.lowercased() == "set-cookie" else { continue }
            if let s = value as? String {
                raws.append(s)
            } else {
                raws.append(String(describing: value))
            }
        }

        lock.lock()
        defer { lock.unlock() }
        for raw in raws {
            for pair in Self.splitSetCookie(raw) {
                let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                guard parts.count == 2 else { continue }
                let name = parts[0].trimmingCharacters(in: .whitespaces)
                let value = parts[1].trimmingCharacters(in: .whitespaces)
                guard !name.isEmpty else { continue }
                // 空值 = 服务端在删这个 Cookie
                if value.isEmpty || value == "deleted" {
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
        // 去掉属性段（Path / Domain / Expires…），只留 name=value
        return out.map { part -> String in
            let first = part.split(separator: ";", maxSplits: 1).first.map(String.init) ?? part
            return first.trimmingCharacters(in: .whitespaces)
        }.filter { !$0.isEmpty }
    }

    // MARK: - 表单编码

    /// application/x-www-form-urlencoded。
    ///
    /// Swift 的 `.urlQueryAllowed` **不转义 `&` `=` `+`**，直接用它会导致
    /// 密码里的特殊字符把整个表单拆坏 —— 这是上一版的隐性 bug。
    /// 这里用严格的 RFC 3986 unreserved 集合，空格编成 `+`（HTML 表单标准）。
    static func encodeForm(_ form: [(String, String)]) -> String {
        form.map { "\(escape($0.0))=\(escape($0.1))" }.joined(separator: "&")
    }

    private static func escape(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let encoded = s.addingPercentEncoding(withAllowedCharacters: allowed) else { return "" }
        return encoded.replacingOccurrences(of: "%20", with: "+")
    }

    // MARK: - 解码

    /// 按 Content-Type 的 charset 解码；没写 charset 先 UTF-8，再退回 GB18030。
    ///
    /// 正方教务很多页面是 GBK/GB2312，用 UTF-8 硬解会得到一片乱码，
    /// 进而导致「找不到 csrftoken」「找不到姓名」这类看似逻辑错误的故障。
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

    // MARK: - 错误可读化

    /// 把 URLError 翻成**用户能据此自救**的文案。
    /// 上一版把所有网络错误都归成 "NETWORK"，用户看到「请检查网络」时无从下手。
    static func describe(_ e: URLError) -> String {
        switch e.code {
        case .timedOut:
            return "连不上（超时）"
        case .cannotFindHost, .dnsLookupFailed:
            return "域名解析失败，可能不在校园网"
        case .cannotConnectToHost:
            return "无法建立连接"
        case .notConnectedToInternet:
            return "设备未联网"
        case .networkConnectionLost:
            return "连接已断开"
        case .secureConnectionFailed, .serverCertificateUntrusted:
            return "HTTPS 证书校验失败"
        case .appTransportSecurityRequiresSecureConnection:
            return "被 ATS 拦截（需要 Info.plist 放行 http）"
        default:
            return "网络错误 \(e.code.rawValue)"
        }
    }
}