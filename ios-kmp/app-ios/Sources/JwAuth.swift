import Foundation
import Shared

/// 教务会话：登录、取课表、取成绩。
///
/// 职责划分和公交一致：Swift 负责 HTTP（URLSession 自动管 Cookie），
/// Kotlin 负责解析（`KbParser` / `parseGrades`）。
///
/// 两个必须踩准的点：
///   1. 校外 443 可能不通 → https 失败要自动降级到 http（ATS 已放行 tstc.edu.cn）；
///   2. 登录会先 302 再进首页，JSESSIONID 在 302 的 Set-Cookie 里 —— 必须用共享 Cookie 存储。
@MainActor
final class JwAuth: ObservableObject {
    static let shared = JwAuth()

    @Published var loggedIn = false
    @Published var studentNo = ""
    @Published var message = ""

    private let host = "jwxt.tstc.edu.cn"

    /// 校外移动数据下 443 常常不通、80 正常（安卓端实测同款问题），所以必须能走 http。
    /// 这里做成「自动降级 + 手动开关」双保险：自动降级兜住偶发，手动开关兜住一直不通。
    var forceHttp: Bool {
        get { UserDefaults.standard.bool(forKey: "jw.forceHttp") }
    }

    func setForceHttp(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: "jw.forceHttp")
        origin = on ? "http://\(host)" : "https://\(host)"
    }
    private var origin = UserDefaults.standard.bool(forKey: "jw.forceHttp")
        ? "http://jwxt.tstc.edu.cn" : "https://jwxt.tstc.edu.cn"

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.httpCookieStorage = HTTPCookieStorage.shared
        cfg.httpCookieAcceptPolicy = .always
        cfg.httpShouldSetCookies = true
        cfg.timeoutIntervalForRequest = 12
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: cfg)
    }()

    private let ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
        + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    // MARK: - 登录

    func login(no: String, password: String) async -> Bool {
        message = "正在登录…"
        let n = no.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty, !password.isEmpty else {
            message = "学号和密码不能为空"
            return false
        }

        do {
            // ① 登录页拿 csrftoken（https 优先，失败降级 http）
            var (_, page) = try await sendWithFallback("GET", "/xtgl/login_slogin.html", nil)
            var csrf = JwHtml.hiddenValue(page, id: "csrftoken")
            if csrf.isEmpty && origin.hasPrefix("https") {
                // https 通了但拿不到登录表单，多半是被网关/拦截页截了 → 换 http 再来一次
                try await switchToHttp()
                (_, page) = try await send("GET", "/xtgl/login_slogin.html", nil, origin)
                csrf = JwHtml.hiddenValue(page, id: "csrftoken")
            }
            guard !csrf.isEmpty else {
                message = "拿不到 csrftoken（页面结构变了？）"
                return false
            }

            // ② 公钥
            let ts = Int(Date().timeIntervalSince1970 * 1000)
            let (_, keyJson) = try await sendWithFallback(
                "GET", "/xtgl/login_getPublicKey.html?time=\(ts)", nil)
            let modulus = jsonField(keyJson, "modulus")
            let exponent = jsonField(keyJson, "exponent")
            guard !modulus.isEmpty, let cipher = RSASPKI.encrypt(
                password: password, modulusB64: modulus, exponentB64: exponent) else {
                message = "密码加密失败"
                return false
            }

            // ③ 登录
            let form = [
                "csrftoken": csrf,
                "yhm": n,
                "mm": cipher.base64EncodedString(),
                "hidMm": cipher.base64EncodedString(),
                "language": "zh_CN",
                "mmsfjm": "1",
            ]
            let (_, loginBody) = try await sendWithFallback("POST", "/xtgl/login_slogin.html", form)
            let err = JwHtml.loginError(loginBody)
            if !err.isEmpty {
                message = err
                return false
            }

            // ④ 用菜单页确认会话真的建立了（302 上的 Cookie 有没有留住）
            let (code, menu) = try await sendWithFallback("GET", "/xtgl/index_cxMenuList.html", nil)
            if code != 200 || !menu.contains("gnmkdm") {
                message = "登录未生效（会话没建立）"
                return false
            }

            studentNo = n
            loggedIn = true
            message = ""
            Keychain.saveNo(n)
            if UserDefaults.standard.bool(forKey: "jw.remember") {
                Keychain.savePassword(password)
            } else {
                Keychain.deletePassword()
            }
            return true
        } catch {
            message = "网络失败：\(error.localizedDescription)"
            return false
        }
    }

    func logout() {
        loggedIn = false
        HTTPCookieStorage.shared.removeCookies(since: Date(timeIntervalSince1970: 0))
    }

    // MARK: - 课表

    func fetchTimetable() async -> Timetable? {
        // 学年学期从课表页的 select 里取；取不到就留空让教务用默认值
        var xnm = ""
        var xqm = ""
        if let (_, page) = try? await sendWithFallback(
            "GET", "/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default", nil) {
            xnm = JwHtml.selectedOption(page, name: "xnm")
            xqm = JwHtml.selectedOption(page, name: "xqm")
        }
        var form: [String: String] = [:]
        if !xnm.isEmpty { form["xnm"] = xnm }
        if !xqm.isEmpty { form["xqm"] = xqm }
        guard let (code, body) = try? await sendWithFallback("POST", "/kbcx/xskbcx_cxXsKb.html", form),
              code == 200, !body.isEmpty else {
            message = "课表接口没有响应"
            return nil
        }
        let r = KbParser.shared.parse(json: body)
        if !r.ok() {
            message = r.warnings.count > 0 ? "课表解析失败（\(r.warnings.count) 条提示）" : "课表解析失败"
            return nil
        }
        return r.timetable
    }

    // MARK: - 成绩

    func fetchGrades() async -> GradeResult? {
        let form = ["xnm": "", "xqm": "", "kcbj": "", "sfzgcj": "", "zyfxgk": "0"]
        guard let (code, body) = try? await sendWithFallback(
            "POST", "/cjcx/cjcx_cxXsgrcj.html?doType=query", form), code == 200 else {
            message = "成绩接口没有响应"
            return nil
        }
        let r = JwParserKt.parseGrades(json: body)
        if !r.ok { message = r.message }
        return r
    }

    // MARK: - HTTP

    /// 换到 http 并记住（下一次启动直接走 http，省掉一次失败往返）。
    private func switchToHttp() async throws {
        origin = "http://\(host)"
        UserDefaults.standard.set(true, forKey: "jw.forceHttp")
    }

    /// 带协议降级的请求：当前协议失败（抛错 / 5xx / 空体）就换另一种协议再试一次，
    /// 成功则记住这次用的是什么。教务在校外移动数据下 443 不通是常态，不能只靠登录那一步兜。
    private func sendWithFallback(_ method: String, _ path: String,
                                  _ form: [String: String]?) async throws -> (Int, String) {
        let first = origin
        do {
            let r = try await send(method, path, form, first)
            if r.0 < 500 && !r.1.isEmpty { return r }
        } catch {
            // 落到下面换协议
        }
        let alt = first.hasPrefix("https") ? "http://\(host)" : "https://\(host)"
        let r = try await send(method, path, form, alt)
        origin = alt
        UserDefaults.standard.set(alt.hasPrefix("http://"), forKey: "jw.forceHttp")
        return r
    }

    private func send(_ method: String, _ path: String, _ form: [String: String]?,
                      _ base: String) async throws -> (Int, String) {
        guard let url = URL(string: base + path) else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")
        if method == "POST", let form = form {
            req.httpBody = Data(formString(form).utf8)
        }
        let (data, resp) = try await session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
        let text = String(data: data, encoding: .utf8) ?? gb18030(data)
        return (code, text)
    }

    private func formString(_ form: [String: String]) -> String {
        form.map { k, v in
            let ek = k.addingPercentEncoding(withAllowedCharacters: unreserved) ?? k
            let ev = v.addingPercentEncoding(withAllowedCharacters: unreserved) ?? v
            return "\(ek)=\(ev)"
        }.joined(separator: "&")
    }

    private var unreserved: CharacterSet {
        var s = CharacterSet()
        s.insert(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        s.insert(charactersIn: "abcdefghijklmnopqrstuvwxyz")
        s.insert(charactersIn: "0123456789-._~")
        return s
    }

    /// 教务有些页面是 GBK，UTF-8 解不出来时兜一下。
    private func gb18030(_ data: Data) -> String {
        let enc = CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue))
        return String(data: data, encoding: String.Encoding(rawValue: enc)) ?? ""
    }

    private func jsonField(_ json: String, _ name: String) -> String {
        guard let re = try? NSRegularExpression(pattern: "\"\(name)\"\\s*:\\s*\"([^\"]*)\"") else { return "" }
        let ns = json as NSString
        guard let m = re.firstMatch(in: json, options: [], range: NSRange(location: 0, length: ns.length)),
              m.numberOfRanges > 1 else { return "" }
        return ns.substring(with: m.range(at: 1))
    }
}
