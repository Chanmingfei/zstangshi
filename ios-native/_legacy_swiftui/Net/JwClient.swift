import Foundation

/// 正方（强智）教务客户端：登录 + 取课表。
///
/// 登录协议（与 Android 端真机实测一致，2026-11 于 jwxt.tstc.edu.cn）：
///   1. GET  /xtgl/login_slogin.html        → 取隐藏域 csrftoken
///   2. GET  /xtgl/login_getPublicKey.html  → {modulus, exponent}（base64 的 BigInteger）
///   3. RSA/ECB/PKCS1Padding 加密密码
///   4. POST /xtgl/login_slogin.html        → yhm / mm / hidMm / csrftoken / language / mmsfjm
///   5. GET  /xtgl/index_cxMenuList.html    → 必须含 gnmkdm，否则视为没登上
///
/// 协议自适应：校外移动数据下 443 常常不通、80 正常，所以先试上次成功的协议，
/// 连不上换另一个再试一次（与 Android 的 `JwClient.request` 同一策略）。
///
/// 用 actor 包住可变会话状态，天然线程安全；内部 HTTP 是 async，不阻塞主线程。
actor JwClient {

    static let host = "jwxt.tstc.edu.cn"

    enum Path {
        static let login = "/xtgl/login_slogin.html"
        static let publicKey = "/xtgl/login_getPublicKey.html"
        static let menu = "/xtgl/index_cxMenuList.html"
        static let profile = "/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=N100801&layout=default"
        static let kbPage = "/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"
        static let kbPageAlt = "/kbcx/xskbcx_cxXskbIndex.html?gnmkdm=N2151&layout=default"
        static let kbData = "/kbcx/xskbcx_cxXsKb.html"
    }

    struct LoginResult {
        var ok = false
        var message = ""
        var studentName = ""
        var scheme = ""
    }

    struct TimetableResult {
        var ok = false
        var message = ""
        var timetable: Timetable?
        var semesterId = ""
        var warnings: [String] = []
    }

    private let http = Http()
    private let store: Store
    private var origin: String
    /// 当前会话对应的账号（用于判断能否复用）
    private var sessionUser = ""
    private var sessionName = ""
    private var sessionAt: TimeInterval = 0
    private var sessionProbeAt: TimeInterval = 0
    private let sessionTTL: TimeInterval = 10 * 60
    private let probeGap: TimeInterval = 30

    init(store: Store) {
        self.store = store
        origin = store.originScheme() == "http"
            ? "http://\(JwClient.host)"
            : "https://\(JwClient.host)"
    }

    var originPrefix: String { origin }

    func clearSession() {
        http.clearCookies()
        sessionUser = ""
        sessionName = ""
        sessionAt = 0
        sessionProbeAt = 0
    }

    func cookiesForWebView() -> [String: String] {
        http.cookiesForWebView()
    }

    // MARK: - 登录

    func login(user: String, password: String) async -> LoginResult {
        var r = LoginResult()

        // 同一账号、十分钟内：轻量探测确认会话还活着就直接复用，省掉整套 RSA 登录
        if user == sessionUser, Date().timeIntervalSince1970 - sessionAt < sessionTTL {
            let now = Date().timeIntervalSince1970
            if now - sessionProbeAt < probeGap {
                r.ok = true
                r.studentName = sessionName
                r.scheme = origin
                return r
            }
            if let probe = await get(Path.menu),
               probe.contains("gnmkdm"), !probe.contains("login_slogin") {
                r.ok = true
                r.studentName = sessionName
                r.scheme = origin
                sessionProbeAt = now
                return r
            }
        }
        clearSession()

        guard let page = await get(Path.login) else {
            r.message = "连不上教务系统，请检查网络（校外流量建议改用校园网）"
            return r
        }
        let csrf = Self.hiddenValue(page, id: "csrftoken")
        guard !csrf.isEmpty else {
            r.message = "登录页结构变了（找不到 csrftoken），请更新 App"
            return r
        }

        guard let keyJson = await get("\(Path.publicKey)?time=\(Int(Date().timeIntervalSince1970 * 1000))") else {
            r.message = "取加密公钥失败"
            return r
        }
        let modulus = Self.jsonField(keyJson, "modulus")
        let exponent = Self.jsonField(keyJson, "exponent")
        if modulus.isEmpty || exponent.isEmpty {
            r.message = "教务公钥为空，可能不是正方教务系统"
            return r
        }

        let encrypted: String
        do {
            encrypted = try RSA.encryptThrowing(password: password,
                                               modulusB64: modulus,
                                               exponentB64: exponent)
        } catch {
            r.message = (error as? LocalizedError)?.errorDescription ?? "密码加密失败"
            return r
        }

        let form: [(String, String)] = [
            ("csrftoken", csrf),
            ("yhm", user),
            ("mm", encrypted),
            ("hidMm", encrypted),
            ("language", "zh_CN"),
            ("mmsfjm", "1")
        ]
        guard let post = await post(Path.login, form: form) else {
            r.message = "登录请求没有响应"
            return r
        }
        if post.contains("用户名或密码不正确") {
            r.message = "学号或密码不正确"
            return r
        }

        // 空响应既可能是成功也可能是失败，必须再要一次实锤
        guard let menu = await get(Path.menu) else {
            r.message = "登录后核对会话失败，请检查网络"
            return r
        }
        if menu.contains("login_slogin") {
            r.message = "学号或密码不正确"
            return r
        }
        if !menu.contains("gnmkdm") {
            r.message = "无法确认登录状态，请重试"
            return r
        }

        r.ok = true
        store.setLoginAt()

        // 姓名按学号绑定：换账号后旧姓名视为不存在
        let cached = store.studentName(owner: user)
        r.studentName = cached.isEmpty ? await fetchStudentName() : cached
        store.setStudentName(r.studentName, owner: user)

        sessionUser = user
        sessionName = r.studentName
        sessionAt = Date().timeIntervalSince1970
        sessionProbeAt = sessionAt
        r.scheme = origin
        return r
    }

    /// 只确认会话还活着，不动课表（首页下拉刷新用）
    func loginOnly(user: String, password: String) async -> Bool {
        await login(user: user, password: password).ok
    }

    private func fetchStudentName() async -> String {
        guard let page = await get(Path.profile), !page.isEmpty else { return "" }
        let patterns = [
            "(?<!英)姓名\\s*[:：]?\\s*</label>[\\s\\S]{0,300}?form-control-static[^>]*>\\s*([^<]{1,24})\\s*<",
            "id=[\"']col_xm[\"'][\\s\\S]{0,300}?form-control-static[^>]*>\\s*([^<]{1,24})\\s*<",
            "name=[\"']xm[\"'][^>]*value=[\"']([^\"']{1,24})[\"']"
        ]
        for p in patterns {
            if let v = Self.firstGroup(page, pattern: p) {
                let name = v.trimmingCharacters(in: .whitespacesAndNewlines)
                    .replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
                if !name.isEmpty, !name.contains("姓名") { return name }
            }
        }
        return ""
    }

    // MARK: - 课表

    func fetchTimetable() async -> TimetableResult {
        var r = TimetableResult()
        var xnm = ""
        var xqm = ""
        var kicked = false
        var anyResponse = false

        // 判定标准不是「有没有响应」，而是「能不能读到 xnm/xqm」——
        // 404 页、登录跳转页都会返回 200 文本，只看响应会把人骗过去。
        for path in [Path.kbPage, Path.kbPageAlt] {
            guard let page = await get(path) else { continue }
            anyResponse = true
            if page.contains("login_slogin") {
                kicked = true
                continue
            }
            let a = Self.selectedOption(page, key: "xnm")
            let b = Self.selectedOption(page, key: "xqm")
            if !a.isEmpty, !b.isEmpty {
                xnm = a
                xqm = b
                break
            }
        }
        if xnm.isEmpty || xqm.isEmpty {
            if kicked {
                r.message = "登录已过期，请重新登录"
            } else if !anyResponse {
                r.message = "连不上教务，请检查网络（校外建议用校园网）"
            } else {
                r.message = "课表页里找不到学年/学期，教务系统可能改版了"
            }
            return r
        }

        guard let json = await post(Path.kbData, form: [("xnm", xnm), ("xqm", xqm)]) else {
            r.message = "课表接口没有返回"
            return r
        }
        let parsed = KbParser.parse(json)
        r.warnings = parsed.warnings
        guard parsed.ok else {
            r.message = parsed.warnings.first ?? "课表解析为空"
            return r
        }
        r.ok = true
        r.timetable = parsed.timetable
        r.semesterId = parsed.timetable?.semester.id ?? ""
        return r
    }

    // MARK: - HTTP（带协议自适应）

    func get(_ path: String) async -> String? {
        await request(method: "GET", path: path, form: nil)
    }

    func post(_ path: String, form: [(String, String)]) async -> String? {
        await request(method: "POST", path: path, form: form)
    }

    /// 供 JwApi（成绩 / 绩点 / 选课 / 推荐课表）复用同一条带 Cookie 的会话。
    ///
    /// 协议策略（**完全对齐安卓 JwClient.kt:292-309**）：
    ///   · 先按记忆/默认的协议试；失败再换协议重试一次。
    ///   · 换协议那一次用 4 秒探测超时（安卓同款），不白等完整超时。
    ///   · 「成功即记住」：只要这一轮有响应，就把当前协议写回，下次直接用。
    ///
    /// ⚠️ 上一版这里「并发探测双协议」是错的：探测用新建的 Http()（空 Cookie），
    ///   探测成功的那路 Cookie（JSESSIONID/route）被直接丢弃，会话对不上；
    ///   而且并发下两个协议各自发请求，会和服务端会话状态打架。
    ///   这里改成跟安卓一样：同一个 http 会话、串行、先主后副。
    private func request(method: String, path: String, form: [(String, String)]?) async -> String? {
        let isHTTPS = origin.hasPrefix("https")
        let other = isHTTPS ? "http://\(JwClient.host)" : "https://\(JwClient.host)"

        // 先按当前协议试（完整超时）
        if let outcome = try? await http.request(method: method, url: origin + path, form: form) {
            store.setOriginScheme(origin.hasPrefix("https") ? "https" : "http")
            return outcome.body
        }

        // 换协议：只探测，4 秒就够，别让用户再等一个完整超时
        if let outcome = try? await http.request(method: method, url: other + path,
                                                 form: form, timeout: Http.probeTimeout) {
            origin = other
            store.setOriginScheme(other.hasPrefix("https") ? "https" : "http")
            return outcome.body
        }
        return nil
    }

    // MARK: - HTML 小工具

    /// 取隐藏域值（属性顺序可能是 value 在前，两个方向都试）
    static func hiddenValue(_ html: String, id: String) -> String {
        let escaped = NSRegularExpression.escapedPattern(for: id)
        guard let tag = firstMatch(html, pattern: "<input[^>]*id=[\"']\(escaped)[\"'][^>]*>") else {
            return ""
        }
        return firstGroup(tag, pattern: "value=[\"']([^\"']*)[\"']") ?? ""
    }

    /// 取下拉框当前选中值。教务课表页写的是 name="xnm"（不是 id），
    /// 而且 selected 可能排在 value 前面。
    static func selectedOption(_ html: String, key: String) -> String {
        let escaped = NSRegularExpression.escapedPattern(for: key)
        var inner: String?
        for pattern in ["<select[^>]*id=[\"']\(escaped)[\"'][^>]*>([\\s\\S]*?)</select>",
                        "<select[^>]*name=[\"']\(escaped)[\"'][^>]*>([\\s\\S]*?)</select>"] {
            if let v = firstGroup(html, pattern: pattern) {
                inner = v
                break
            }
        }
        guard let inner else { return "" }
        var first = ""
        for tag in allMatches(inner, pattern: "<option[^>]*>") {
            guard let value = firstGroup(tag, pattern: "value=[\"']([^\"']*)[\"']"),
                  !value.isEmpty else { continue }
            if first.isEmpty { first = value }
            if tag.lowercased().contains("selected") { return value }
        }
        return first
    }

    static func jsonField(_ json: String, _ name: String) -> String {
        firstGroup(json, pattern: "\"\(NSRegularExpression.escapedPattern(for: name))\"\\s*:\\s*\"([^\"]*)\"") ?? ""
    }

    private static func regex(_ pattern: String) -> NSRegularExpression? {
        try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
    }

    static func firstMatch(_ text: String, pattern: String) -> String? {
        guard let re = regex(pattern) else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard let m = re.firstMatch(in: text, range: range),
              let r = Range(m.range, in: text) else { return nil }
        return String(text[r])
    }

    static func firstGroup(_ text: String, pattern: String) -> String? {
        guard let re = regex(pattern) else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard let m = re.firstMatch(in: text, range: range), m.numberOfRanges > 1,
              let r = Range(m.range(at: 1), in: text) else { return nil }
        return String(text[r])
    }

    static func allMatches(_ text: String, pattern: String) -> [String] {
        guard let re = regex(pattern) else { return [] }
        let range = NSRange(text.startIndex..., in: text)
        return re.matches(in: text, range: range).compactMap { m in
            Range(m.range, in: text).map { String(text[$0]) }
        }
    }
}