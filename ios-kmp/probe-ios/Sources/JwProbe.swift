import Foundation

/// 探针的一行输出。
struct ProbeLine: Identifiable {
    enum Mark { case info, ok, fail, warn }
    let id = UUID()
    let text: String
    let mark: Mark
}

/// 教务登录六步的 iOS 实现，目标是**把每一步的真实结果打出来**，
/// 而不是「跑通就算」。失败时要能直接看出是哪一步、什么错误码。
///
/// 与安卓 `jw/JwClient.kt` 逐条对齐：
///   1. GET  /xtgl/login_slogin.html      取隐藏域 csrftoken
///   2. GET  /xtgl/login_getPublicKey.html 取 modulus / exponent（base64 大整数）
///   3. RSA/ECB/PKCS1Padding 加密密码
///   4. POST /xtgl/login_slogin.html      yhm / mm / hidMm / csrftoken / language / mmsfjm
///   5. GET  /xtgl/index_cxMenuList.html  必须含 gnmkdm，否则视为没登上
///   6. GET  /kbcx/xskbcx_cxXskbcxIndex.html → 读 xnm/xqm → POST 课表接口
final class JwProbe {

    private static let host = "jwxt.tstc.edu.cn"
    private static let ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
        + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    private let session: URLSession
    private var origin = ""

    init() {
        let cfg = URLSessionConfiguration.default
        // 关键：用共享 Cookie 存储，且一律接受 Set-Cookie。
        // 登录 POST 会先 302 再跳菜单页，JSESSIONID 就在那个 302 的响应头里；
        // 只留最后一次响应就会丢会话，表现却像「密码错误」。
        cfg.httpCookieStorage = HTTPCookieStorage.shared
        cfg.httpCookieAcceptPolicy = .always
        cfg.httpShouldSetCookies = true
        cfg.timeoutIntervalForRequest = 12
        cfg.timeoutIntervalForResource = 30
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: cfg)
    }

    // MARK: - 主流程

    func run(studentNo: String, password: String) async -> [ProbeLine] {
        var out: [ProbeLine] = []
        let no = studentNo.trimmingCharacters(in: .whitespacesAndNewlines)
        let pwd = password

        guard !no.isEmpty, !pwd.isEmpty else {
            out.append(.init(text: "请先填学号和密码", mark: .warn))
            return out
        }

        // ① ATS 明文放行 —— 这一条单独测，因为它是「校外能不能登」的前提
        out.append(.init(text: "① ATS 明文放行：GET http://\(Self.host)/…", mark: .info))
        var httpOK = false
        do {
            let (data, code) = try await send("GET", "http://\(Self.host)/xtgl/login_slogin.html", nil, 8)
            httpOK = (code == 200)
            out.append(.init(text: "   http 直连 → \(code)，\(data.count) 字节", mark: httpOK ? .ok : .fail))
        } catch {
            out.append(.init(text: "   http 直连失败：\(describe(error))", mark: .fail))
        }

        // ② https 登录页 + csrftoken
        out.append(.init(text: "② https 登录页 / 取 csrftoken", mark: .info))
        var page = ""
        do {
            let (data, code) = try await send("GET", "https://\(Self.host)/xtgl/login_slogin.html", nil, 12)
            page = decode(data)
            origin = "https"
            out.append(.init(text: "   登录页 → \(code)，\(data.count) 字节", mark: code == 200 ? .ok : .fail))
        } catch {
            out.append(.init(text: "   https 失败：\(describe(error))，改走 http", mark: .warn))
            if let r = try? await send("GET", "http://\(Self.host)/xtgl/login_slogin.html", nil, 10) {
                page = decode(r.0)
                origin = "http"
                out.append(.init(text: "   http 登录页 → \(r.1)，\(r.0.count) 字节", mark: r.1 == 200 ? .ok : .fail))
            }
        }
        let csrf = HTML.hiddenValue(page, id: "csrftoken")
        out.append(.init(text: "   csrftoken：\(csrf.isEmpty ? "未找到（页面结构变了？）" : String(csrf.prefix(12)) + "…")",
                         mark: csrf.isEmpty ? .fail : .ok))
        if csrf.isEmpty {
            out.append(.init(text: "中止：拿不到 csrftoken，后面的步骤没有意义。原始页面前 200 字：\(String(page.prefix(200)).replacingOccurrences(of: "\n", with: " "))", mark: .fail))
            return out
        }

        let base = "\(origin)://\(Self.host)"

        // ③ 公钥 + RSA
        out.append(.init(text: "③ 取公钥并 RSA 加密密码", mark: .info))
        var cipher = ""
        do {
            let (data, code) = try await send("GET", "\(base)/xtgl/login_getPublicKey.html?time=\(Int(Date().timeIntervalSince1970 * 1000))", nil, 10)
            let json = decode(data)
            let modulus = HTML.jsonField(json, "modulus")
            let exponent = HTML.jsonField(json, "exponent")
            out.append(.init(text: "   公钥 → \(code)，modulus \(modulus.count) 字符 / exponent \(exponent.isEmpty ? "空" : exponent)",
                             mark: modulus.isEmpty ? .fail : .ok))
            guard let mData = RSASPKI.base64(modulus), !mData.isEmpty else {
                out.append(.init(text: "   modulus 不是合法 base64，中止", mark: .fail))
                return out
            }
            let bits = RSASPKI.normalize(mData).count * 8
            out.append(.init(text: "   modulus 去符号位后 \(RSASPKI.normalize(mData).count) 字节 → \(bits) 位",
                             mark: bits >= 1024 ? .ok : .fail))
            guard let enc = RSASPKI.encrypt(password: pwd, modulusB64: modulus, exponentB64: exponent) else {
                out.append(.init(text: "   SecKeyCreateWithData / 加密失败（DER 拼装有问题）", mark: .fail))
                return out
            }
            cipher = enc.base64EncodedString()
            out.append(.init(text: "   密文 \(enc.count) 字节（应等于模长 \(bits / 8)），base64 \(cipher.count) 字符",
                             mark: enc.count == bits / 8 ? .ok : .fail))
        } catch {
            out.append(.init(text: "   取公钥失败：\(describe(error))", mark: .fail))
            return out
        }

        // ④ POST 登录
        out.append(.init(text: "④ POST 登录表单", mark: .info))
        let form = [
            "csrftoken": csrf,
            "yhm": no,
            "mm": cipher,
            "hidMm": cipher,
            "language": "zh_CN",
            "mmsfjm": "1",
        ]
        var loginText = ""
        do {
            let (data, code) = try await send("POST", "\(base)/xtgl/login_slogin.html", form, 15)
            loginText = decode(data)
            out.append(.init(text: "   响应 → \(code)，\(data.count) 字节", mark: .info))
            out.append(.init(text: "   页内含「用户名或密码不正确」：\(loginText.contains("用户名或密码不正确"))",
                             mark: loginText.contains("用户名或密码不正确") ? .fail : .ok))
            out.append(.init(text: "   Cookie：\(cookieNames("\(base)/"))", mark: .info))
        } catch {
            out.append(.init(text: "   登录 POST 失败：\(describe(error))", mark: .fail))
            return out
        }

        // ⑤ 菜单页核验会话
        out.append(.init(text: "⑤ 取菜单页核验会话（关键：302 上的 Cookie 有没有留住）", mark: .info))
        do {
            let (data, code) = try await send("GET", "\(base)/xtgl/index_cxMenuList.html", nil, 12)
            let menu = decode(data)
            let hasGnmkdm = menu.contains("gnmkdm")
            let kicked = menu.contains("login_slogin")
            out.append(.init(text: "   菜单页 → \(code)，\(data.count) 字节，含 gnmkdm=\(hasGnmkdm)，被踢回登录=\(kicked)",
                             mark: (hasGnmkdm && !kicked) ? .ok : .fail))
            if !hasGnmkdm || kicked {
                out.append(.init(text: "中止：会话没建立起来。若上面已出现「密码不正确」就属实，否则是 Cookie 丢失。", mark: .fail))
                return out
            }
        } catch {
            out.append(.init(text: "   菜单页失败：\(describe(error))", mark: .fail))
            return out
        }

        // ⑥ 课表
        out.append(.init(text: "⑥ 取课表（读学期码 → POST 课表接口）", mark: .info))
        var xnm = ""
        var xqm = ""
        for path in ["/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default",
                     "/kbcx/xskbcx_cxXskbIndex.html?gnmkdm=N2151&layout=default"] {
            guard let resp = try? await send("GET", base + path, nil, 12) else {
                out.append(.init(text: "   课表页 \(path) 无响应", mark: .warn))
                continue
            }
            let code = resp.1
            let html = decode(resp.0)
            let a = HTML.selectedOption(html, name: "xnm")
            let b = HTML.selectedOption(html, name: "xqm")
            out.append(.init(text: "   \(path.prefix(34))… → \(code)，xnm=\(a)，xqm=\(b)",
                             mark: (a.isEmpty || b.isEmpty) ? .warn : .ok))
            if !a.isEmpty && !b.isEmpty { xnm = a; xqm = b; break }
        }
        if xnm.isEmpty || xqm.isEmpty {
            out.append(.init(text: "   读不到学期码，跳过课表接口", mark: .fail))
            return out
        }

        do {
            let (data, code) = try await send("POST", "\(base)/kbcx/xskbcx_cxXsKb.html", ["xnm": xnm, "xqm": xqm], 15)
            let json = decode(data)
            let records = HTML.countOccurrences(json, "\"kcmc\"")
            let names = HTML.courseNames(json, limit: 3)
            out.append(.init(text: "   课表接口 → \(code)，\(data.count) 字节", mark: code == 200 ? .ok : .fail))
            out.append(.init(text: "   kbList 记录数 ≈ \(records)；前几门：\(names.isEmpty ? "（空）" : names.joined(separator: "、"))",
                             mark: records > 0 ? .ok : .fail))
            out.append(.init(text: "   响应开头：\(String(json.prefix(160)).replacingOccurrences(of: "\n", with: " "))", mark: .info))
        } catch {
            out.append(.init(text: "   课表接口失败：\(describe(error))", mark: .fail))
        }

        out.append(.init(text: "完成。把这段日志截图或复制回传即可判定 iOS 侧是否可行。", mark: .info))
        _ = httpOK
        return out
    }

    // MARK: - HTTP

    private func send(_ method: String, _ urlString: String, _ form: [String: String]?,
                      _ timeout: TimeInterval) async throws -> (Data, Int) {
        guard let url = URL(string: urlString) else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = timeout
        req.setValue(Self.ua, forHTTPHeaderField: "User-Agent")
        req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")
        if method == "POST", let form = form {
            req.setValue("application/x-www-form-urlencoded; charset=UTF-8",
                         forHTTPHeaderField: "Content-Type")
            req.httpBody = Data(HTML.encodeForm(form).utf8)
        }
        let (data, resp) = try await session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
        return (data, code)
    }

    private func cookieNames(_ urlString: String) -> String {
        guard let u = URL(string: urlString) else { return "（无）" }
        let list = HTTPCookieStorage.shared.cookies(for: u) ?? []
        if list.isEmpty { return "（空）" }
        return list.map { $0.name }.sorted().joined(separator: ", ")
    }

    private func decode(_ data: Data) -> String {
        if let s = String(data: data, encoding: .utf8) { return s }
        let gb = CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue))
        return String(data: data, encoding: String.Encoding(rawValue: gb)) ?? ""
    }

    /// 把错误翻译成人能看懂的一句话。ATS 被拦时的 -1022 必须显式点出来，
    /// 否则会跟「网络不通」混在一起，白查半天。
    private func describe(_ error: Error) -> String {
        let e = error as NSError
        if e.domain == NSURLErrorDomain {
            switch e.code {
            case -1022:
                // NSURLErrorAppTransportSecurityRequiresSecureConnection
                return "ATS 拒绝了明文 HTTP（-1022）。Info.plist 的 NSExceptionDomains 没生效"
            case NSURLErrorTimedOut:
                return "超时（-1001），校内也可能不通，换网络再试"
            case NSURLErrorCannotFindHost:
                return "域名解析失败（-1003）"
            case NSURLErrorSecureConnectionFailed:
                return "TLS 握手失败（-1200），该站点可能只支持很老的协议"
            case NSURLErrorCancelled:
                return "已取消（-999）"
            default:
                return "网络错误 \(e.code)（\(e.localizedDescription)）"
            }
        }
        return e.localizedDescription
    }
}

// MARK: - 轻量 HTML/表单工具

enum HTML {

    /// 取隐藏域的值：`<input ... id="csrftoken" ... value="xxx">`，属性顺序不限。
    static func hiddenValue(_ html: String, id: String) -> String {
        let pattern = "<input[^>]*id=[\"']" + escaped(id) + "[\"'][^>]*>"
        guard let tag = firstMatch(html, pattern) else { return "" }
        let valuePattern = "value=[\"']([^\"']*)[\"']"
        guard let groups = match(tag, valuePattern) else { return "" }
        return groups.count > 1 ? groups[1] : ""
    }

    /// 取下拉框选中项（没有 selected 就取第一个），对应安卓 selectedOption。
    static func selectedOption(_ html: String, name: String) -> String {
        let patterns = [
            "<select[^>]*id=[\"']" + escaped(name) + "[\"'][^>]*>([\\s\\S]*?)</select>",
            "<select[^>]*name=[\"']" + escaped(name) + "[\"'][^>]*>([\\s\\S]*?)</select>",
        ]
        var inner: String?
        for p in patterns {
            if let g = match(html, p), g.count > 1 { inner = g[1]; break }
        }
        guard let body = inner else { return "" }
        // allMatches 返回的是「每组捕获」，opt 是 [String]（第 0 项才是整段匹配）
        let options = allMatches(body, "<option[^>]*>")
        var firstValue = ""
        for opt in options {
            guard let tag = opt.first else { continue }
            guard let g = match(tag, "value=[\"']([^\"']*)[\"']"), g.count > 1 else { continue }
            let v = g[1]
            if v.isEmpty { continue }
            if firstValue.isEmpty { firstValue = v }
            if tag.lowercased().contains("selected") { return v }
        }
        return firstValue
    }

    static func jsonField(_ json: String, _ name: String) -> String {
        guard let g = match(json, "\"" + escaped(name) + "\"\\s*:\\s*\"([^\"]*)\""), g.count > 1 else { return "" }
        return g[1]
    }

    static func countOccurrences(_ s: String, _ needle: String) -> Int {
        if needle.isEmpty { return 0 }
        var count = 0
        var range = s.startIndex..<s.endIndex
        while let r = s.range(of: needle, range: range) {
            count += 1
            range = r.upperBound..<s.endIndex
        }
        return count
    }

    /// 粗略取前几门课名，只为证明数据真的下来了。
    static func courseNames(_ json: String, limit: Int) -> [String] {
        var out: [String] = []
        let pattern = "\"kcmc\"\\s*:\\s*\"([^\"]{1,40})\""
        for g in allMatches(json, pattern) where g.count > 1 {
            let name = g[1]
            if !name.isEmpty && !out.contains(name) { out.append(name) }
            if out.count >= limit { break }
        }
        return out
    }

    /// 只放行 RFC 3986 的 unreserved 字符，其余一律百分号编码。
    /// 不用 CharacterSet.alphanumerics —— 它会把中文当成「字母」原样放过，
    /// 拼进 urlencoded 表单里就是脏数据。
    private static let unreserved: CharacterSet = {
        var set = CharacterSet()
        set.insert(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        set.insert(charactersIn: "abcdefghijklmnopqrstuvwxyz")
        set.insert(charactersIn: "0123456789-._~")
        return set
    }()

    static func encodeForm(_ form: [String: String]) -> String {
        var parts: [String] = []
        for key in form.keys.sorted() {
            let k = key.addingPercentEncoding(withAllowedCharacters: unreserved) ?? key
            let v = (form[key] ?? "").addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
            parts.append("\(k)=\(v)")
        }
        return parts.joined(separator: "&")
    }

    // MARK: 正则小工具

    private static func escaped(_ s: String) -> String {
        return NSRegularExpression.escapedPattern(for: s)
    }

    private static func match(_ s: String, _ pattern: String) -> [String]? {
        guard let re = try? NSRegularExpression(pattern: pattern,
                                                options: [.caseInsensitive, .dotMatchesLineSeparators]) else {
            return nil
        }
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, options: [], range: NSRange(location: 0, length: ns.length)) else {
            return nil
        }
        var out: [String] = []
        for i in 0..<m.numberOfRanges {
            let r = m.range(at: i)
            out.append(r.location == NSNotFound ? "" : ns.substring(with: r))
        }
        return out
    }

    private static func firstMatch(_ s: String, _ pattern: String) -> String? {
        guard let m = match(s, pattern) else { return nil }
        return m.first
    }

    private static func allMatches(_ s: String, _ pattern: String) -> [[String]] {
        guard let re = try? NSRegularExpression(pattern: pattern,
                                                options: [.caseInsensitive, .dotMatchesLineSeparators]) else {
            return []
        }
        let ns = s as NSString
        let results = re.matches(in: s, options: [], range: NSRange(location: 0, length: ns.length))
        return results.map { m in
            (0..<m.numberOfRanges).map { i -> String in
                let r = m.range(at: i)
                return r.location == NSNotFound ? "" : ns.substring(with: r)
            }
        }
    }
}
