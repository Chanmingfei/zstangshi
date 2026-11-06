import Foundation
import WebKit

/**
 * CookieStore —— 打通 WKWebView 与 URLSession 的会话（Android 侧 CookieJar.java 的 iOS 等价物）。
 *
 * 背景：Android 的 CookieManager 只服务 WebView 网络栈，java.net.HttpURLConnection 走另一套
 * `java.net.CookieHandler`，两者默认互不相通 —— 登录成功但后续请求全 302 回登录页就是这么来的
 * （docs 里 R2 记的坑）。iOS 有一模一样的结构性问题：
 *   · WKWebView 的 Cookie 存在 WKHTTPCookieStore（独立于其他网络栈）；
 *   · URLSession 走 HTTPCookieStorage.shared。
 *   两边**不同步**。所以这里让 WKHTTPCookieStore 当唯一存储：
 *     请求侧 —— 每次 hop 前从 WKHTTPCookieStore 取出匹配 Cookie 注入 Cookie 头；
 *     响应侧 —— 每条 Set-Cookie 解析后写回 WKHTTPCookieStore。
 *   同时把 URLSessionConfiguration.httpCookieStorage 置 nil、httpShouldSetCookies 置 false，
 *   确保请求头完全由我们控制，不会出现重复 Cookie 头。
 *
 * 为什么这么绕也要走 WKHTTPCookieStore：H5 侧要靠 Cookie 做「重开 App 直接回到已登录态」
 * （web/app.js:242 的 resumeSession 探测），那份 Cookie 必须真的在 WebView 的存储里。
 */
enum CookieStore {

    private static var store: WKHTTPCookieStore {
        WKWebsiteDataStore.default().httpCookieStore
    }

    // MARK: - 读

    /// 生成请求用的 Cookie 头（无匹配返回空串）。
    static func cookieHeader(for url: URL) async -> String {
        let cookies = await allCookies()
        let matched = cookies
            .filter { matches($0, url) }
            // 浏览器语义：path 更具体的排在前面
            .sorted { $0.path.count > $1.path.count }
            .map { "\($0.name)=\($0.value)" }
        return matched.joined(separator: "; ")
    }

    static func allCookies() async -> [HTTPCookie] {
        await withCheckedContinuation { continuation in
            store.getAllCookies { cookies in
                continuation.resume(returning: cookies)
            }
        }
    }

    // MARK: - 写

    /// 把若干条原始 Set-Cookie 写回 WebView 存储。
    static func store(setCookieValues: [String], for url: URL) async {
        var parsed: [HTTPCookie] = []
        for raw in setCookieValues {
            let cookies = HTTPCookie.cookies(withResponseHeaderFields: ["Set-Cookie": raw], for: url)
            if cookies.isEmpty {
                // 解析失败（极少数畸形头）就跳过，绝不让一次响应把整条链路打断
                continue
            }
            parsed.append(contentsOf: cookies)
        }
        for cookie in parsed {
            await setCookie(cookie)
        }
    }

    static func setCookie(_ cookie: HTTPCookie) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            store.setCookie(cookie) {
                continuation.resume()
            }
        }
    }

    /// 退出登录：清空 WebView Cookie（对应 Android CookieJar.clear()）。
    ///
    /// 注意：WKHTTPCookieStore **没有** deleteAllCookies（那是 WKWebsiteDataStore 的 API），
    /// 只能先全量取出再逐条 delete。也刻意不去 removeData(localStorage)：
    /// Android 侧 clearSession 同样只清 Cookie + 凭据，主题与「记住学号」要留着。
    static func clear() async {
        let cookies = await allCookies()
        for cookie in cookies {
            await delete(cookie)
        }
    }

    static func delete(_ cookie: HTTPCookie) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            store.delete(cookie) {
                continuation.resume()
            }
        }
    }

    // MARK: - 匹配 / 解析

    private static func matches(_ cookie: HTTPCookie, _ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        if let expires = cookie.expiresDate, expires.timeIntervalSinceNow < 0 { return false }
        if cookie.isSecure && url.scheme?.lowercased() != "https" { return false }

        let domain = cookie.domain.lowercased()
        let domainOK: Bool
        if domain.hasPrefix(".") {
            // .tstc.edu.cn 既匹配 tstc.edu.cn 也匹配 jwxt.tstc.edu.cn
            let bare = String(domain.dropFirst())
            domainOK = host == bare || host.hasSuffix(domain)
        } else {
            domainOK = host == domain
        }
        guard domainOK else { return false }

        let path = url.path.isEmpty ? "/" : url.path
        return path.hasPrefix(cookie.path)
    }

    /**
     * 拆分被 URLSession 合并成一条的 Set-Cookie。
     *
     * Foundation 把多条 Set-Cookie 按 ", " 拼进同一个头，但 `Expires=Wed, 21 Oct 2015 ...`
     * 本身也含逗号，直接 split 会把过期时间切断。判据是：逗号后面紧跟
     * `name=` 这种「无空格的 token + 等号」才当作新 Cookie 的起点。
     */
    static func splitSetCookie(_ joined: String) -> [String] {
        let characters = Array(joined)
        var result: [String] = []
        var current = ""
        var index = 0

        while index < characters.count {
            let character = characters[index]
            guard character == "," else {
                current.append(character)
                index += 1
                continue
            }
            var cursor = index + 1
            while cursor < characters.count, characters[cursor] == " " { cursor += 1 }
            var token = ""
            var sawEquals = false
            var probe = cursor
            while probe < characters.count {
                let candidate = characters[probe]
                if candidate == "=" { sawEquals = true; break }
                if candidate == ";" || candidate == "," || candidate == " " { break }
                token.append(candidate)
                probe += 1
            }
            if sawEquals && !token.isEmpty {
                result.append(current)
                current = ""
            } else {
                current.append(character)
            }
            index += 1
        }
        if !current.trimmingCharacters(in: .whitespaces).isEmpty {
            result.append(current)
        }
        return result
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }
}
