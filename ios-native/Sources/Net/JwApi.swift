import Foundation

/// 教务功能页数据（成绩 / 学分绩点）。
///
/// 与 Android `JwApi` 同一套接口与同口径解析：成绩走两个 POST（主表 + 分项），
/// 绩点走学业情况页的 HTML。返回结构统一成「标题 + 副标题 + 数值 + 备注」的行列表，
/// 界面层只用一套列表视图就能把两类页面都画出来。
struct FeatureRow: Codable, Identifiable, Hashable {
    var id = UUID().uuidString
    var title = ""
    var sub = ""
    var value = ""
    var extra = ""
}

struct FeatureData: Codable {
    var ok = false
    var message = ""
    var headline = ""
    var note = ""
    var rows: [FeatureRow] = []
    var at: TimeInterval = 0
}

@MainActor
final class JwApi {

    enum Kind: String {
        case grades
        case gpa
    }

    private enum Path {
        static let gradesData = "/cjcx/cjcx_cxXsgrcj.html?doType=query"
        static let gradesDetail = "/cjcx/cjcx_cxXsKccjList.html?gnmkdm=N305007"
        static let gradesPage = "/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005&layout=default"
        static let gpaPage = "/xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=N105515&layout=default"
    }

    private let client: JwClient
    private let store: Store
    private var cachedUser = ""
    private var cachedPass = ""

    init(client: JwClient, store: Store) {
        self.client = client
        self.store = store
    }

    /// 会话过期时兜底重登
    private func relogin() async -> Bool {
        guard !cachedUser.isEmpty, !cachedPass.isEmpty else { return false }
        client.clearSession()
        return await client.login(user: cachedUser, password: cachedPass).ok
    }

    // MARK: - 成绩

    func grades(force: Bool) async -> FeatureData {
        cachedUser = store.user()
        cachedPass = store.pass

        // 进页先读本地快照；只有下拉刷新（force）或没快照才联网 —— 
        // 「单次登录只加载一次」，切页、返回都不会重复打接口。
        if !force, let snapshot = snapshot(.grades), snapshot.ok {
            return snapshot
        }

        var main: String?
        var detail: String?
        let form = ["xnm": "", "xqm": "", "zd_fzdm": "N305005-xs", "page": "1", "rows": "200",
                    "limit": "200", "queryModel.showCount": "200", "queryModel.pageSize": "200",
                    "sidx": "", "sord": "asc"]
        let dform = ["xnm": "", "xqm": "", "page": "1", "rows": "1000", "limit": "1000",
                     "queryModel.showCount": "1000", "queryModel.pageSize": "1000",
                     "sidx": "", "sord": "asc"]

        async let a = client.post(Path.gradesData, form: form)
        async let b = client.post(Path.gradesDetail, form: dform)
        main = await a
        detail = await b

        // 少数情况教务要求先访问 gnmkdm 页建立会话语境；只在主表异常时补这一步
        if badPayload(main) {
            _ = await client.get(Path.gradesPage)
            main = await client.post(Path.gradesData, form: form)
        }
        if badPayload(main), await relogin() {
            main = await client.post(Path.gradesData, form: form)
        }
        guard let json = main, !json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            var d = FeatureData()
            d.message = "成绩接口没有响应，请检查网络"
            return d
        }

        var out = FeatureData()
        let comps = detail.map(gradeComponents) ?? [:]
        guard let arr = firstArray(in: json) else {
            out.message = "成绩数据解析失败"
            return out
        }
        for item in arr {
            guard let o = item as? [String: Any] else { continue }
            let name = pick(o, ["kcmc", "kcmcmc", "courseName"])
            if name.isEmpty { continue }
            let score = pick(o, ["cj", "score"])
            let credit = pick(o, ["xf", "credits"])
            let point = pick(o, ["jd", "xfjd"])
            let sem = pick(o, ["xnmmc", "xnm"]) + " · " + pick(o, ["xqmmc", "xqm"])
            let kind = pick(o, ["kcxzmc", "kclbmc"])
            let parts = comps["j:" + pick(o, ["jxb_id", "key"])] ?? comps["n:" + name]

            var sub = sem
            if !credit.isEmpty { sub += " · \(credit) 学分" }
            if !kind.isEmpty { sub += " · \(kind)" }
            if let parts, !parts.isEmpty { sub += "\n" + parts }

            out.rows.append(FeatureRow(title: name, sub: sub,
                                       value: score.isEmpty ? "—" : score, extra: point))
        }
        guard !out.rows.isEmpty else {
            out.message = "教务暂未返回成绩（可能还没出分）"
            return out
        }
        out.ok = true
        out.headline = "\(out.rows.count) 门"
        out.note = "数据来自教务「信息查询 → 学生成绩查询」；五级制/缓考等文字成绩保留原文"
        out.at = Date().timeIntervalSince1970
        save(.grades, out)
        return out
    }

    // MARK: - 学分绩点

    func gpa(force: Bool) async -> FeatureData {
        cachedUser = store.user()
        cachedPass = store.pass

        if !force, let snapshot = snapshot(.gpa), snapshot.ok {
            return snapshot
        }

        var out = FeatureData()
        guard var html = await client.get(Path.gpaPage) else {
            out.message = "学业情况页打不开，请检查网络"
            return out
        }
        if html.contains("login_slogin"), await relogin() {
            html = await client.get(Path.gpaPage) ?? html
        }

        // 这一页服务端渲染能到 1MB，整页去标签纯属浪费：只切关键字前后一段
        let text: String
        if let r = html.range(of: "平均学分绩点") {
            let lower = html.index(r.lowerBound, offsetBy: -200, limitedBy: html.startIndex) ?? html.startIndex
            let upper = html.index(r.upperBound, offsetBy: 600, limitedBy: html.endIndex) ?? html.endIndex
            text = stripTags(String(html[lower..<upper]))
        } else {
            text = stripTags(String(html.prefix(4000)))
        }

        // 服务端把标签、全半角括号混着渲染，别把括号写死：
        // 从「平均学分绩点」往后 40 个非数字字符内抓第一个数字
        guard let value = JwClient.firstGroup(text, pattern: "平均学分绩点[^0-9]{0,40}([0-9]+(?:\\.[0-9]+)?)") else {
            out.message = "学业情况页找不到平均学分绩点，教务可能改版或会话已过期"
            return out
        }

        out.ok = true
        out.headline = value
        var note = "数据来自教务「信息查询 → 学生学业情况查询」"
        if let r = html.range(of: "统计时间") {
            let slice = stripTags(String(html[r.lowerBound...].prefix(200)))
            if let t = JwClient.firstGroup(slice, pattern: "统计时间\\s*([0-9-]{10}\\s+[0-9:]{8})\\s*之前有效") {
                note = "统计截至 \(t) · 教务权威值，App 不做二次计算"
            }
        }
        out.note = note
        out.rows = [
            FeatureRow(title: "关于这个数字",
                       sub: "公选课是否计入、重修取哪次成绩、五级制如何折算，均由教务口径决定",
                       value: "", extra: ""),
            FeatureRow(title: "提示", sub: "此页面信息仅做学业修读情况参考", value: "", extra: "")
        ]
        out.at = Date().timeIntervalSince1970
        save(.gpa, out)
        return out
    }

    // MARK: - 快照

    private func snapshot(_ kind: Kind) -> FeatureData? {
        let json = store.feature(kind.rawValue)
        guard !json.isEmpty, let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(FeatureData.self, from: data)
    }

    private func save(_ kind: Kind, _ data: FeatureData) {
        guard let encoded = try? JSONEncoder().encode(data),
              let json = String(data: encoded, encoding: .utf8) else { return }
        store.saveFeature(kind.rawValue, json)
    }

    // MARK: - 解析工具

    private func badPayload(_ s: String?) -> Bool {
        guard let s else { return true }
        return s.contains("login_slogin") || !s.trimmingCharacters(in: .whitespaces).hasPrefix("{")
    }

    /// 正方查询接口把数据包在 { items: [...] } 或 { rows: [...] } 里，两种都试
    private func firstArray(in json: String) -> [Any]? {
        guard let data = json.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        for key in ["items", "rows", "data"] {
            if let arr = root[key] as? [Any] { return arr }
        }
        return nil
    }

    private func pick(_ o: [String: Any], _ keys: [String]) -> String {
        for k in keys {
            if let v = o[k] {
                if let s = v as? String, !s.isEmpty { return s }
                if let n = v as? NSNumber { return n.stringValue }
            }
        }
        return ""
    }

    /// 分项成绩：按教学班 / 课名归组「平时 90 · 期末 85」，总评行丢掉避免重复
    private func gradeComponents(_ json: String) -> [String: String] {
        var out: [String: String] = [:]
        guard let arr = firstArray(in: json) else { return out }
        for item in arr {
            guard let o = item as? [String: Any] else { continue }
            let label = pick(o, ["xmblmc"])
            let score = pick(o, ["xmcj"])
            if label.isEmpty || score.isEmpty || label.contains("总评") { continue }
            let text = "\(label) \(score)"
            for key in ["j:" + pick(o, ["jxb_id", "jxbmc"]), "n:" + pick(o, ["kcmc", "kcmcmc"])]
            where key.count >= 3 {
                if let old = out[key] {
                    out[key] = old + " · " + text
                } else {
                    out[key] = text
                }
            }
        }
        return out
    }

    private func stripTags(_ html: String) -> String {
        html.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }
}
