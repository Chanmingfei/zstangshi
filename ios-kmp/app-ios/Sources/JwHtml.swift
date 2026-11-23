import Foundation

/// 教务页面的 HTML 抽取（正方那套表单）。
///
/// 这些是"脏活"：正方的页面结构十几年没变但属性顺序会变，
/// 所以一律用正则容忍顺序，不用字符串 indexOf 硬切。
enum JwHtml {

    /// 取隐藏域的值：`<input ... id="csrftoken" ... value="xxx">`
    static func hiddenValue(_ html: String, id: String) -> String {
        let pattern = "<input[^>]*id=[\"']" + NSRegularExpression.escapedPattern(for: id) + "[\"'][^>]*>"
        guard let tag = firstMatch(html, pattern) else { return "" }
        guard let g = groups(tag, "value=[\"']([^\"']*)[\"']") else { return "" }
        return g.count > 1 ? g[1] : ""
    }

    /// 取下拉框选中项（没有 selected 就取第一个 option）。
    static func selectedOption(_ html: String, name: String) -> String {
        let patterns = [
            "<select[^>]*id=[\"']" + NSRegularExpression.escapedPattern(for: name) + "[\"'][^>]*>([\\s\\S]*?)</select>",
            "<select[^>]*name=[\"']" + NSRegularExpression.escapedPattern(for: name) + "[\"'][^>]*>([\\s\\S]*?)</select>",
        ]
        var body: String?
        for p in patterns {
            if let g = groups(html, p), g.count > 1 { body = g[1]; break }
        }
        guard let inner = body else { return "" }
        var first = ""
        for opt in allMatches(inner, "<option[^>]*>") {
            guard let g = groups(opt, "value=[\"']([^\"']*)[\"']"), g.count > 1 else { continue }
            let v = g[1]
            if v.isEmpty { continue }
            if first.isEmpty { first = v }
            if opt.lowercased().contains("selected") { return v }
        }
        return first
    }

    /// 登录失败时教务会在页面里写这些文案。
    static func loginError(_ html: String) -> String {
        for key in ["用户名或密码不正确", "用户不存在", "密码错误", "账号已被锁定", "验证码错误"] {
            if html.contains(key) { return key }
        }
        return ""
    }

    // MARK: - 正则小工具

    private static func groups(_ s: String, _ pattern: String) -> [String]? {
        guard let re = try? NSRegularExpression(pattern: pattern,
                                                options: [.caseInsensitive, .dotMatchesLineSeparators]) else {
            return nil
        }
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, options: [], range: NSRange(location: 0, length: ns.length)) else {
            return nil
        }
        return (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            return r.location == NSNotFound ? "" : ns.substring(with: r)
        }
    }

    private static func firstMatch(_ s: String, _ pattern: String) -> String? {
        guard let g = groups(s, pattern) else { return nil }
        return g.first
    }

    private static func allMatches(_ s: String, _ pattern: String) -> [String] {
        guard let re = try? NSRegularExpression(pattern: pattern,
                                                options: [.caseInsensitive, .dotMatchesLineSeparators]) else {
            return []
        }
        let ns = s as NSString
        return re.matches(in: s, options: [], range: NSRange(location: 0, length: ns.length)).map {
            ns.substring(with: $0.range)
        }
    }
}
