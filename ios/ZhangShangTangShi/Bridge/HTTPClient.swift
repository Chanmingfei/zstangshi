import Foundation

/**
 * HTTPClient —— 原生通道的 HTTP 执行器（Android 侧 CookieJar.execute 的 iOS 等价物）。
 *
 * 与 Android 实现逐条对齐的行为契约（改动前先看 CookieJar.java 的注释）：
 *   1. **手动跟随重定向**，最多 5 跳。正方教务登录是两级 302，POST 响应会换发新的
 *      JSESSIONID；若让 URLSession 自动跟随，每一跳都会沿用旧 Cookie 头，第二次跳转
 *      必被静默踢回登录页（页面无任何错误文案，看起来像密码错）。
 *   2. **每跳都重新取 Cookie**：从 WKHTTPCookieStore 现取现注入，不复用上一跳的头。
 *   3. **每跳都把 Set-Cookie 写回** WKHTTPCookieStore，保证 H5 侧后续的 resumeSession
 *      探测能拿到会话。
 *   4. 重定向语义按浏览器：301/302 的 POST 与 303 一律降级为 GET 且丢弃 body；
 *      307/308 保留原方法与 body。
 *   5. 4xx/5xx 也要把响应体带回去（支付宝/教务的错误页就是 4xx 带 HTML）。
 */
enum HTTPClient {

    struct Outcome {
        let status: Int
        let headers: [String: String]
        let body: String
        let url: String
    }

    enum Failure: Error {
        case invalidURL(String)
        case redirectWithoutLocation
        case tooManyRedirects
        case transport(code: String, detail: String)
    }

    /**
     * 与 transport.js 的 DEFAULT_TIMEOUT = 20000 对齐（Android 侧 CookieJar.TIMEOUT_MS 同值）。
     *
     * 这个数**必须 ≥ H5 侧的超时**，否则会把本该成功的慢请求掐死：
     * transport.js:178 自己也挂了 setTimeout 兜超时（到点先 resolve TIMEOUT），
     * 原生这层只是后备。2026-11-07 实测校外移动数据下教务单次请求要 15–16 s，
     * 原先写 15 s 正好卡在边界上 —— H5 给了 20 s 预算，原生却先在第 15 秒失败，
     * 结果就是「浏览器能开、App 登录失败」。两者必须同步，改一个就改另一个。
     */
    static let timeout: TimeInterval = 20
    private static let maxHops = 5

    /// 关掉 URLSession 的自动重定向与自动 Cookie —— 这两件事都必须由我们手工接管
    private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession,
                        task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            completionHandler(nil)
        }
    }

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        // 关键：不接受、也不写入 HTTPCookieStorage，Cookie 只走 WKHTTPCookieStore
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration,
                          delegate: NoRedirectDelegate(),
                          delegateQueue: nil)
    }()

    // MARK: - 执行

    static func execute(url: String, method: String, headers: [String: String], body: String) async throws -> Outcome {
        var current = url
        var verb = normalizedMethod(method)
        var payload = body

        for hop in 0...maxHops {
            guard let target = URL(string: current) else {
                throw Failure.invalidURL(current)
            }

            var request = URLRequest(url: target)
            request.httpMethod = verb
            request.timeoutInterval = timeout
            for (key, value) in headers where !key.isEmpty {
                request.setValue(value, forHTTPHeaderField: key)
            }

            // ★ 方向一：WebView 存储 → 本次请求（每跳现取，绝不复用上一跳的头）
            let cookie = await CookieStore.cookieHeader(for: target)
            if !cookie.isEmpty {
                request.setValue(cookie, forHTTPHeaderField: "Cookie")
            }

            let sendsBody = !payload.isEmpty && ["POST", "PUT", "PATCH"].contains(verb)
            if sendsBody {
                request.httpBody = payload.data(using: .utf8)
            }

            let transferred: (Data, URLResponse)
            do {
                transferred = try await session.data(for: request)
            } catch {
                throw mapTransportError(error)
            }
            let data = transferred.0
            let response = transferred.1

            guard let http = response as? HTTPURLResponse else {
                throw Failure.transport(code: "IO", detail: "响应不是 HTTP 响应")
            }

            // ★ 方向二：Set-Cookie → WebView 存储（先落盘再决定是否跟随，避免丢会话）
            let rawSetCookies = http.allHeaderFields.compactMap { key, value -> String? in
                guard let name = key as? String, name.lowercased() == "set-cookie" else { return nil }
                return value as? String
            }
            var setCookieValues: [String] = []
            for raw in rawSetCookies {
                setCookieValues.append(contentsOf: CookieStore.splitSetCookie(raw))
            }
            if !setCookieValues.isEmpty {
                await CookieStore.store(setCookieValues: setCookieValues, for: target)
            }

            if isRedirect(http.statusCode) {
                guard let location = http.value(forHTTPHeaderField: "Location"), !location.isEmpty else {
                    throw Failure.redirectWithoutLocation
                }
                guard let next = URL(string: location, relativeTo: target)?.absoluteURL else {
                    throw Failure.invalidURL(location)
                }
                current = next.absoluteString
                if http.statusCode == 303 || ((http.statusCode == 301 || http.statusCode == 302) && verb == "POST") {
                    verb = "GET"
                    payload = ""
                }
                if hop == maxHops {
                    throw Failure.tooManyRedirects
                }
                continue
            }

            return Outcome(status: http.statusCode,
                           headers: lowercasedHeaders(http),
                           body: decodeBody(data, contentType: http.value(forHTTPHeaderField: "Content-Type")),
                           url: current)
        }

        throw Failure.tooManyRedirects
    }

    // MARK: - 工具

    private static func isRedirect(_ status: Int) -> Bool {
        [301, 302, 303, 307, 308].contains(status)
    }

    private static func normalizedMethod(_ method: String) -> String {
        let upper = method.uppercased()
        let allowed = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"]
        return allowed.contains(upper) ? upper : "GET"
    }

    /// 响应头归一成全小写键（transport.js 的 normalizeHeaders 会再转一层，这里做是为了对齐全端行为）
    private static func lowercasedHeaders(_ response: HTTPURLResponse) -> [String: String] {
        var out: [String: String] = [:]
        for (key, value) in response.allHeaderFields {
            guard let name = key as? String else { continue }
            out[name.lowercased()] = String(describing: value)
        }
        return out
    }

    /**
     * 按 Content-Type 的 charset 解码；没写 charset 先试 UTF-8，再退回 GB18030。
     * 教务系统新页面是 UTF-8，老页面偶有 GBK —— Android 侧只兜到 UTF-8，
     * 这里多一层 GB 兜底，避免老页面乱码。
     */
    static func decodeBody(_ data: Data, contentType: String?) -> String {
        if let name = charsetName(contentType) {
            let encoding = CFStringConvertIANACharSetNameToEncoding(name as CFString)
            if encoding != kCFStringEncodingInvalidId {
                let nsEncoding = CFStringConvertEncodingToNSStringEncoding(encoding)
                if let text = String(data: data, encoding: String.Encoding(rawValue: nsEncoding)) {
                    return text
                }
            }
        }
        if let text = String(data: data, encoding: .utf8) {
            return text
        }
        let gbEncoding = CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue))
        if let text = String(data: data, encoding: String.Encoding(rawValue: gbEncoding)) {
            return text
        }
        return String(decoding: data, as: UTF8.self)
    }

    private static func charsetName(_ contentType: String?) -> String? {
        guard let contentType else { return nil }
        let lower = contentType.lowercased()
        guard let range = lower.range(of: "charset=") else { return nil }
        var value = String(lower[range.upperBound...])
        if let separator = value.firstIndex(where: { $0 == ";" || $0 == " " }) {
            value = String(value[value.startIndex..<separator])
        }
        value = value.trimmingCharacters(in: CharacterSet(charactersIn: "\"' "))
        return value.isEmpty ? nil : value
    }

    /// 错误归一：把 URLError 映射成 Android 侧同一套错误码（TIMEOUT / SSL / NETWORK / IO）
    static func mapTransportError(_ error: Error) -> Failure {
        guard let urlError = error as? URLError else {
            return Failure.transport(code: "IO", detail: String(describing: error))
        }
        let detail = "URLError(\(urlError.code.rawValue)): \(urlError.localizedDescription)"
        switch urlError.code {
        case .timedOut:
            return Failure.transport(code: "TIMEOUT", detail: detail)
        case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate,
             .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid, .clientCertificateRejected:
            return Failure.transport(code: "SSL", detail: detail)
        case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost, .cannotConnectToHost,
             .dnsLookupFailed, .internationalRoamingOff, .dataNotAllowed:
            return Failure.transport(code: "NETWORK", detail: detail)
        default:
            return Failure.transport(code: "IO", detail: detail)
        }
    }
}
