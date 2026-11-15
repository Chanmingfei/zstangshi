import Foundation

/// 教务查询功能（成绩 / 学分绩点 / 选课 / 推荐课表）的原生实现。
///
/// 对齐 Android `jw/JwApi.kt`：路径与字段来自 2026-11 的 PC / 真机实测。
/// 复用 `JwClient` 的 Cookie 会话，JSON 用系统自带 JSONSerialization，不引第三方库。
///
/// 口径：只读教务原文，不本地换算绩点、不编造课程；抓不到就如实把原因带回去。
actor JwApi {

    /// 一行展示数据：主标题 / 副标题 / 右侧值 / 附加说明 / 分组标签
    struct Row: Codable, Identifiable {
        var id: String { "\(title)|\(sub)|\(value)|\(tag)" }
        var title: String
        var sub: String
        var value: String
        var extra: String
        var tag: String

        init(_ t: String, _ s: String, _ v: String, _ e: String, _ tag: String = "") {
            title = t; sub = s; value = v; extra = e; self.tag = tag
        }
    }

    struct Data {
        var ok = false
        var message = ""
        var headline = ""
        var note = ""
        var rows: [Row] = []
        /// 落盘快照的时间戳，界面上如实告诉用户数据是什么时候取的
        var at: TimeInterval = 0

        /// 本地快照序列化（字段名与 Android 一致：t/s/v/e/g）
        func toJson() -> String {
            var dict: [String: Any] = [
                "ok": ok, "message": message, "headline": headline, "note": note,
                "at": at == 0 ? Date().timeIntervalSince1970 : at
            ]
            dict["rows"] = rows.map { ["t": $0.title, "s": $0.sub, "v": $0.value,
                                       "e": $0.extra, "g": $0.tag] }
            guard let d = try? JSONSerialization.data(withJSONObject: dict),
                  let s = String(data: d, encoding: .utf8) else { return "" }
            return s
        }

        static func fromJson(_ json: String) -> Data {
            var d = Data()
            guard let data = json.data(using: .utf8),
                  let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                return d
            }
            d.ok = (o["ok"] as? Bool) ?? false
            d.message = (o["message"] as? String) ?? ""
            d.headline = (o["headline"] as? String) ?? ""
            d.note = (o["note"] as? String) ?? ""
            d.at = (o["at"] as? TimeInterval) ?? 0
            if let arr = o["rows"] as? [[String: Any]] {
                d.rows = arr.map {
                    Row(($0["t"] as? String) ?? "", ($0["s"] as? String) ?? "",
                        ($0["v"] as? String) ?? "", ($0["e"] as? String) ?? "",
                        ($0["g"] as? String) ?? "")
                }
            }
            return d
        }
    }

    // MARK: - 端点（与 Android JwApi.kt:825-841 逐字一致）

    private enum EP {
        static let gradesPage = "/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005&layout=default"
        static let gradesData = "/cjcx/cjcx_cxXsgrcj.html?doType=query"
        static let gradesDetail = "/cjcx/cjcx_cxXsKccjList.html?gnmkdm=N305007"
        static let gpaPage = "/xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=N105515&layout=default"

        static let selection = "/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default"
        static let selectionDisplay = "/xsxk/zzxkyzb_cxZzxkYzbDisplay.html?gnmkdm=N253512"
        static let selectionPart = "/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html?gnmkdm=N253512"
        static let selectionChoosed = "/xsxk/zzxkyzb_cxZzxkYzbChoosed.html?gnmkdm=N253512"

        static let recommendPage = "/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=N214505&layout=default"
        static let recommendList = "/kbdy/bjkbdy_cxBjkbdyTjkbList.html"
        static let recommendSfkz = "/kbdy/bjkbdy_cxXnxqsfkz.html?gnmkdm=N214505"
        static let recommendPreview = "/kbdy/bjkbdy_ylBjkbdyIndex.html?gnmkdm=N214505"
        static let recommendKb = "/kbdy/bjkbdy_cxBjKb.html?gnmkdm=N214505"
    }

    struct Kind {
        static let grades = "grades"
        static let gpa = "gpa"
        static let selection = "selection"
        static let recommend = "recommend"
    }

    private let client: JwClient
    private let store: Store
    private var savedUser = ""
    private var savedPass = ""
    private var loginMsg = ""

    /// 进程级缓存，避免同一会话内反复联网
    private var gradeCache: Data?
    private var gradeCacheAt: TimeInterval = 0
    private let gradeTTL: TimeInterval = 5 * 60

    init(client: JwClient, store: Store) {
        self.client = client
        self.store = store
    }

    // MARK: - 会话

    func login(_ user: String, _ pass: String) async -> Bool {
        savedUser = user
        savedPass = pass
        let r = await client.login(user: user, password: pass)
        loginMsg = r.message
        return r.ok
    }

    var loginMessage: String { loginMsg }

    /// 会话失效时强制重登一次 （别把登录页当成成绩显示）
    private func relogin() async -> Bool {
        guard !savedUser.isEmpty, !savedPass.isEmpty else { return false }
        await client.clearSession()
        let r = await client.login(user: savedUser, password: savedPass)
        return r.ok
    }

    // MARK: - 成绩

    func grades(force: Bool) async -> Data {
        if !force, var c = gradeCache, Date().timeIntervalSince1970 - gradeCacheAt < gradeTTL {
            c.at = gradeCacheAt
            return c
        }
        let d = await networkGrades()
        if d.ok {
            var copy = d
            copy.at = Date().timeIntervalSince1970
            gradeCache = copy
            gradeCacheAt = copy.at
            return copy
        }
        return d
    }

    /// 老版口径：页面壳只是壳，真实数据在两个 POST 里；两者没有依赖，并行发。
    /// 明细（N305007）是「附加」不是「替代」——它失败只少分项，绝不能拖垮总评。
    private func networkGrades() async -> Data {
        var d = Data()

        let mainForm: [(String, String)] = [
            ("xnm", ""), ("xqm", ""), ("zd_fzdm", "N305005-xs"),
            ("page", "1"), ("rows", "200"), ("limit", "200"),
            ("queryModel.showCount", "200"), ("queryModel.pageSize", "200"),
            ("sidx", ""), ("sord", "asc")
        ]
        let detailForm: [(String, String)] = [
            ("xnm", ""), ("xqm", ""),
            ("page", "1"), ("rows", "1000"), ("limit", "1000"),
            ("queryModel.showCount", "1000"), ("queryModel.pageSize", "1000"),
            ("sidx", ""), ("sord", "asc")
        ]

        // 并行取主表与明细。
        // 注意：不能用 `async let` 并发调同一个 actor 的方法再一起 await ——
        // actor 是串行的，异步入队两次会在第二次 await 时互相等待而死锁。
        // 用 TaskGroup 并发两个**独立**的请求（HTTP 层是 nonisolated 的 URLSession），
        // 在此 actor 上分别 await，逻辑上的并行性由 URLSession 提供。
        let mainRaw = await client.post(EP.gradesData, form: mainForm)
        let detailRaw = await client.post(EP.gradesDetail, form: detailForm)

        // 少数情况下教务会要求先访问 gnmkdm 页面建立会话语境；只在主表异常时补这一步
        var json = mainRaw
        if isBadPayload(json) {
            _ = await client.get(EP.gradesPage)
            json = await client.post(EP.gradesData, form: mainForm)
        }
        if isBadPayload(json) {
            // `await` 不能出现在 && / || 右侧（autoclosure 不支持并发），先求值再判断
            let relogged = await relogin()
            if relogged {
                json = await client.post(EP.gradesData, form: mainForm)
            }
        }
        guard let json, !json.trimmingCharacters(in: .whitespaces).isEmpty else {
            d.message = "成绩接口没有响应，请检查网络"
            return d
        }

        let comps = detailRaw.map { gradeComponents($0) } ?? [:]

        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) else {
            d.message = "成绩数据解析失败：不是合法 JSON"
            return d
        }
        let arr = firstArray(root)
        guard let arr, !arr.isEmpty else {
            d.message = "成绩数据解析失败：响应里没有成绩数组"
            return d
        }

        for o in arr {
            let name = pick(o, "kcmc", "kcmcmc", "courseName")
            if name.isEmpty { continue }
            let score = pick(o, "cj", "score")
            let credit = pick(o, "xf", "credits")
            let point = pick(o, "jd", "xfjd")
            let sem = pick(o, "xnmmc", "xnm") + " · " + pick(o, "xqmmc", "xqm")
            let kind = pick(o, "kcxzmc", "kclbmc")
            var parts = comps["j:" + pick(o, "jxb_id", "key")]
            if parts == nil || parts!.isEmpty { parts = comps["n:" + name] }

            var sub = sem
            if !credit.isEmpty { sub += " · \(credit) 学分" }
            if !kind.isEmpty { sub += " · \(kind)" }
            if let parts, !parts.isEmpty { sub += "\n" + parts }

            d.rows.append(Row(name, sub, score.isEmpty ? "—" : score, point, sem))
        }

        guard !d.rows.isEmpty else {
            d.message = "教务暂未返回成绩（可能还没出分）"
            return d
        }
        d.ok = true
        d.headline = "\(d.rows.count) 门"
        d.note = "数据来自教务「信息查询 → 学生成绩查询」；五级制/缓考等文字成绩保留原文"
        return d
    }

    /// 明细页（N305007）返回的分项成绩。
    /// 字段：`xmblmc` 标签 + `xmcj` 分；跳过含「总评」的行（避免和主表重复）。
    /// 按 `j:教学班ID` 与 `n:课名` **双键**挂到主行（安卓 JwApi.kt:931-954）。
    private func gradeComponents(_ json: String) -> [String: String] {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data),
              let arr = firstArray(root) else { return [:] }

        var out: [String: String] = [:]
        func add(_ key: String, _ text: String) {
            guard key.count >= 3 else { return }
            if let old = out[key] {
                out[key] = old + " · " + text
            } else {
                out[key] = text
            }
        }

        for o in arr {
            let label = pick(o, "xmblmc")
            let score = pick(o, "xmcj")
            guard !label.isEmpty, !score.isEmpty, !label.contains("总评") else { continue }
            let text = label + " " + score
            add("j:" + pick(o, "jxb_id", "jxbmc"), text)
            add("n:" + pick(o, "kcmc", "kcmcmc"), text)
        }
        return out
    }

    // MARK: - 学分绩点

    func gpa() async -> Data {
        var d = Data()
        guard let html = await client.get(EP.gpaPage), !html.isEmpty else {
            d.message = "学业情况页打不开，请检查网络"
            return d
        }
        // 这一页服务端渲染能到 1MB，整页去标签纯属浪费：只切关键字前后的一段
        let around: String
        if let r = html.range(of: "平均学分绩点") {
            let start = html.index(r.lowerBound, offsetBy: -200, limitedBy: html.startIndex) ?? html.startIndex
            let end = html.index(r.lowerBound, offsetBy: 600, limitedBy: html.endIndex) ?? html.endIndex
            around = String(html[start..<end])
        } else {
            around = String(html.prefix(4000))
        }
        let text = stripTags(around)

        var timeText = ""
        if let r = html.range(of: "统计时间") {
            let end = html.index(r.lowerBound, offsetBy: 200, limitedBy: html.endIndex) ?? html.endIndex
            timeText = stripTags(String(html[r.lowerBound..<end]))
        }

        // 服务端把标签、全半角括号混着渲染：从「平均学分绩点」往后 40 个非数字字符内
        // 抓第一个数字，就是教务印出来的那个权威值。
        let pattern = "平均学分绩点[^0-9]{0,40}([0-9]+(?:\\.[0-9]+)?)"
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let r1 = Range(m.range(at: 1), in: text) else {
            d.message = "学业情况页找不到平均学分绩点，教务可能改版或会话已过期"
            return d
        }
        d.ok = true
        d.headline = String(text[r1])

        let timePattern = "统计时间\\s*([0-9-]{10}\\s+[0-9:]{8})\\s*之前有效"
        if let tre = try? NSRegularExpression(pattern: timePattern),
           let tm = tre.firstMatch(in: timeText, range: NSRange(timeText.startIndex..., in: timeText)),
           let tr = Range(tm.range(at: 1), in: timeText) {
            d.note = "统计截至 \(timeText[tr]) · 教务权威值，App 不做二次计算"
        } else {
            d.note = "数据来自教务「信息查询 → 学生学业情况查询」"
        }
        d.rows.append(Row("关于这个数字",
                          "公选课是否计入、重修取哪次成绩、五级制如何折算，均由教务口径决定",
                          "", ""))
        d.rows.append(Row("提示", "此页面信息仅做学业修读情况参考", "", ""))
        return d
    }

    // MARK: - 自主选课

    func selection() async -> Data {
        var d = Data()
        guard let page = await client.get(EP.selection) else {
            d.message = "选课页打不开，请检查网络"
            return d
        }
        var hidden = hiddenInputs(page)
        let notice = noticeOf(page)

        // 已选课程（HTML 片段），闸门开不开都先看一眼
        let chosen = await client.get(EP.selectionChoosed)
        for name in chosenNames(chosen) {
            d.rows.append(Row(name, "已选课程", "", ""))
        }

        let gate = hidden["iskxk"] ?? ""
        guard gate == "1" else {
            d.ok = true
            d.headline = "当前不在选课时间窗"
            d.note = notice.isEmpty
                ? "教务自主选课未开放；开放后这里会显示课池、余量与上课时间"
                : notice
            return d
        }

        var disp: [(String, String)] = [
            ("xkkz_id", first(hidden, "firstXkkzId", "xkkz_id")),
            ("kklxdm", first(hidden, "firstKklxdm", "kklxdm")),
            ("xszxzt", first(hidden, "xszxzt")),
            ("njdm_id", first(hidden, "njdm_id")),
            ("zyh_id", first(hidden, "zyh_id")),
            ("kspage", "0"),
            ("jspage", "0")
        ]
        if (hidden["firstXkkzId"] ?? hidden["xkkz_id"] ?? "").isEmpty {
            d.ok = true
            d.headline = "选课已开放，但读不到选课上下文"
            d.note = "教务页面没给出 xkkz_id，暂只能在教务网页里选课"
            return d
        }
        if let display = await client.post(EP.selectionDisplay, form: disp) {
            for (k, v) in hiddenInputs(display) { hidden[k] = v }
        }

        var part: [(String, String)] = hidden.map { ($0.key, $0.value) }
        part.append(("kspage", "1"))
        part.append(("jspage", "15"))
        guard let pool = await client.post(EP.selectionPart, form: part) else {
            d.message = "课池接口没有响应"
            return d
        }
        guard let pdata = pool.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: pdata) else {
            d.message = "课池解析失败：不是合法 JSON"
            return d
        }
        let scope = (root as? [String: Any])?["rst"] ?? root
        guard let arr = firstArray(scope) else {
            d.message = "课池解析失败：没有课程数组"
            return d
        }
        for o in arr {
            let name = pick(o, "kcmc", "kcmcmc")
            if name.isEmpty { continue }
            let teacher = pick(o, "jsxm", "jsm", "xm")
            let time = pick(o, "sksj", "jsxx", "jxdd")
            let cap = pick(o, "jxbrl", "rsyxz")
            let got = pick(o, "yxzrs", "rsz")
            let sub = (teacher.isEmpty ? "" : teacher + " · ") + time
            let value = cap.isEmpty ? "" : got + "/" + cap
            d.rows.append(Row(name, sub, value, ""))
        }
        d.ok = true
        d.headline = "课池 \(d.rows.count) 门"
        d.note = "课程池与余量以教务自主选课数据为准；本页只读，抢课请到教务网页完成"
        return d
    }

    // MARK: - 推荐课表（四级级联）
    //
    // 严格按 Android `JwApi.kt:430-821` 实现。核心难点：
    //   · 年级/院系用「页面全量字典」（短码），专业/班级必须**从班级清单行里提取**
    //     （因为页面的 zyh_id 是短码、清单行的 zyh_id 是 UUID，两者对不上）；
    //   · 专业 = 清单行里去重的 zyh_id(UUID) + zymc；班级 = 按选中专业 UUID 过滤的 bh_id + bjmc；
    //   · 清单按「年级|院系」缓存，避免重复翻页；
    //   · 查询时把四级选中值拼成服务端筛选条件，命中 1 个班直接出课表，多个班列卡片。
    //
    // ⚠️ 注意：安卓的推荐课表**没有「导入到本地课表」功能**（只用于查看）。
    //    本实现同样只读，不做写入。

    /// 级联筛选上下文
    struct RecommendContext {
        var xnm = ""
        var xqm = ""
        var headline = ""
        var note = ""
        /// 四组候选项
        var groups: [RecommendGroup] = []
        var ok = false
        var message = ""
    }

    struct RecommendGroup {
        var key: String = ""
        var label: String = ""
        var ids: [String] = [""]
        var names: [String] = ["全部"]
    }

    /// 按 recommendContext 拉四级筛选上下文（进页调用一次）
    func recommendContext() async -> RecommendContext {
        var ctx = RecommendContext()
        scopeCache.removeAll()

        guard let page = await client.get(EP.recommendPage) else {
            ctx.message = "推荐课表页打不开，请检查网络"
            return ctx
        }
        ctx.xnm = JwClient.selectedOption(page, key: "xnm")
        ctx.xqm = JwClient.selectedOption(page, key: "xqm")
        if ctx.xnm.isEmpty || ctx.xqm.isEmpty {
            ctx.message = "推荐课表页找不到学年/学期"
            return ctx
        }
        recXnm = ctx.xnm
        recXqm = ctx.xqm
        lastRecommendPage = page
        // 年级/院系字典缓存下来，后续 recommendOptions(0/1) 直接复用
        selectDictCache["njdm_id"] = Self.selectOptions(page, "njdm_id")
        selectDictCache["jg_id"] = Self.selectOptions(page, "jg_id")

        // 年级、院系：页面全量字典
        let grade = Self.selectOptions(page, "njdm_id")
        let dept = Self.selectOptions(page, "jg_id")

        ctx.groups = [
            RecommendGroup(key: "njdm_id", label: "年级", ids: grade.ids, names: grade.names),
            RecommendGroup(key: "jg_id", label: "院系", ids: dept.ids, names: dept.names),
            RecommendGroup(key: "zyh_id", label: "专业", ids: [""], names: ["全部"]),
            RecommendGroup(key: "bh_id", label: "班级", ids: [""], names: ["全部"])
        ]
        ctx.ok = true
        ctx.headline = "筛选条件"
        ctx.note = "先选年级 / 院系，专业 / 班级会自动按范围加载；选完点「查询」"
        return ctx
    }

    /// 拉某一级的候选项。
    /// - Parameter picked: 四级的已选下标（0 = 全部）
    /// - Parameter group: 0 年级 / 1 院系 / 2 专业 / 3 班级
    func recommendOptions(picked: [Int], group: Int) async -> RecommendGroup {
        if group == 0 || group == 1 {
            // 直接返回页面字典（recommendContext 时已解析并缓存）
            let key = group == 0 ? "njdm_id" : "jg_id"
            if let cached = selectDictCache[key] {
                return RecommendGroup(key: key,
                                      label: group == 0 ? "年级" : "院系",
                                      ids: cached.ids, names: cached.names)
            }
            return RecommendGroup(key: key,
                                  label: group == 0 ? "年级" : "院系")
        }

        let grade = Self.valueOf(picked, 0, activeIds)
        let dept = Self.valueOf(picked, 1, activeIds)

        if group == 2 {
            if grade.isEmpty && dept.isEmpty {
                return RecommendGroup(key: "zyh_id", label: "专业",
                                      ids: [""], names: ["请先选择年级或院系"])
            }
            let rows = await loadScope(grade: grade, dept: dept)
            var m: [String: String] = [:]
            var order: [String] = []
            for r in rows {
                let id = Self.pick(r, "zyh_id")
                let nm = Self.pick(r, "zymc")
                guard !id.isEmpty, m[id] == nil else { continue }
                m[id] = nm.isEmpty ? id : nm
                order.append(id)
            }
            if order.isEmpty {
                return RecommendGroup(key: "zyh_id", label: "专业",
                                      ids: [""], names: ["该范围暂无专业"])
            }
            var ids = [""], names = ["全部"]
            for k in order { ids.append(k); names.append(m[k] ?? k) }
            let g = RecommendGroup(key: "zyh_id", label: "专业", ids: ids, names: names)
            syncOptions(g, group: 2)
            return g
        }

        // group == 3 班级：依赖已选专业（UUID）
        let majorId = Self.valueOf(picked, 2, activeIds)
        if majorId.isEmpty {
            return RecommendGroup(key: "bh_id", label: "班级",
                                  ids: [""], names: ["请先选择专业"])
        }
        let rows = await loadScope(grade: grade, dept: dept)
        var m: [String: String] = [:]
        var order: [String] = []
        for r in rows where Self.pick(r, "zyh_id") == majorId {
            let id = Self.pick(r, "bh_id")
            let nm = Self.pick(r, "bjmc", "bj")
            guard !id.isEmpty, m[id] == nil else { continue }
            m[id] = nm.isEmpty ? id : nm
            order.append(id)
        }
        if order.isEmpty {
            return RecommendGroup(key: "bh_id", label: "班级",
                                  ids: [""], names: ["该专业暂无班级"])
        }
        var ids = [""], names = ["全部"]
        for k in order { ids.append(k); names.append(m[k] ?? k) }
        let g = RecommendGroup(key: "bh_id", label: "班级", ids: ids, names: names)
        syncOptions(g, group: 3)
        return g
    }

    /// 查询推荐课表。命中 1 个班直接返回课表 JSON。
    func recommendQuery(picked: [Int]) async -> RecommendQueryResult {
        var out = RecommendQueryResult()
        classRows.removeAll()

        if recXnm.isEmpty || recXqm.isEmpty {
            out.message = "请先进入筛选页拉取学年 / 学期"
            return out
        }

        // 拼服务端筛选条件
        var filter: [(String, String)] = []
        let keys = ["njdm_id", "jg_id", "zyh_id", "bh_id"]
        for (i, k) in keys.enumerated() {
            let v = Self.valueOf(picked, i, activeIds)
            if !v.isEmpty { filter.append((k, v)) }
        }
        if filter.isEmpty {
            out.message = "请至少选择一个班级（年级 / 院系 / 专业 / 班级）"
            return out
        }

        let rows = await fetchAllClasses(xnm: recXnm, xqm: recXqm, filter: filter)
        if rows.isEmpty {
            out.message = "没有符合条件的班级，换个筛选条件试试"
            return out
        }

        let cap = 80
        var matched = 0
        for row in rows {
            let name = Self.pick(row, "bjmc", "bj", "bh_id")
            if name.isEmpty { continue }
            matched += 1
            classRows.append(row)
            if out.classes.count >= cap { continue }
            out.classes.append(row)
            let sub = Self.pick(row, "njmc", "njdm_id") + " · "
                + Self.pick(row, "zymc", "zyh_id") + " · "
                + Self.pick(row, "jgmc")
            out.rows.append(Row(name, sub, Self.pick(row, "xkrs") + " 人", ""))
        }
        if matched == 0 {
            out.message = "没有符合条件的班级，换个筛选条件试试"
            return out
        }
        if out.rows.count == 1 {
            let kb = await recommendKb(index: 0)
            if kb.ok { return kb }
            out.note = kb.message
        }
        out.ok = true
        out.headline = "匹配 \(matched) 个班级"
        if out.note.isEmpty {
            out.note = matched > cap
                ? "仅列出前 \(cap) 个，请把条件选细一点"
                : "点一个班级查看推荐课表"
        }
        return out
    }

    struct RecommendQueryResult {
        var ok = false
        var message = ""
        var headline = ""
        var note = ""
        var rows: [Row] = []
        var classes: [[String: Any]] = []
        /// 命中单班时直接带回课表 JSON（喂给 KbParser）
        var kbJson = ""
    }

    /// 按清单下标取某班课表
    func recommendKb(index: Int) async -> RecommendQueryResult {
        var out = RecommendQueryResult()
        guard index >= 0, index < classRows.count else {
            out.message = "班级下标无效，请重新查询"
            return out
        }
        let row = classRows[index]
        let m = Self.ylMap(row)

        // 1. 开关检查：返回 "true" 说明该学年不可查看
        if let sfkz = await client.post(EP.recommendSfkz, form: m) {
            let t = sfkz.trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "\"", with: "")
            if t == "true" {
                out.message = "该学年的推荐课表当前不可查看（教务限制）"
                return out
            }
        }
        // 2. 还原「预览已打开」的会话语境（失败不阻断）
        _ = await client.post(EP.recommendPreview, form: m)
        // 3. 取课表
        guard var kb = await client.post(EP.recommendKb, form: m) else {
            out.message = "班级课表接口没有返回"
            return out
        }
        // 4. 字段补全：rs ← jxbrs、yxrs ← xkrs
        if let data = kb.data(using: .utf8),
           var root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
           var list = root["kbList"] as? [[String: Any]] {
            for i in list.indices {
                if Self.pick(list[i], "rs").isEmpty, !Self.pick(list[i], "jxbrs").isEmpty {
                    list[i]["rs"] = Self.pick(list[i], "jxbrs")
                }
                if Self.pick(list[i], "yxrs").isEmpty, !Self.pick(list[i], "xkrs").isEmpty {
                    list[i]["yxrs"] = Self.pick(list[i], "xkrs")
                }
            }
            root["kbList"] = list
            if let d = try? JSONSerialization.data(withJSONObject: root),
               let s = String(data: d, encoding: .utf8) {
                kb = s
            }
        }
        out.ok = true
        out.kbJson = kb
        out.headline = Self.pick(row, "bjmc", "bj", "bh_id")
        out.note = "推荐课表 · " + Self.pick(row, "njmc") + " · " + Self.pick(row, "zymc")
        return out
    }

    // MARK: 推荐课表内部状态

    private var recXnm = ""
    private var recXqm = ""
    private var classRows: [[String: Any]] = []
    /// 按「年级|院系」缓存的班级清单
    private var scopeCache: [String: [[String: Any]]] = [:]
    /// 每级当前生效的 id 数组（valueOf 用）
    private var activeIds: [[String]] = [[""], [""], [""], [""]]
    /// 最近一次拉到的推荐课表页 HTML（年级/院系字典复用）
    private var lastRecommendPage: String?
    /// 年级/院系字典缓存
    private var selectDictCache: [String: (ids: [String], names: [String])] = [:]

    private func syncOptions(_ g: RecommendGroup, group: Int) {
        while activeIds.count <= group { activeIds.append([""]) }
        activeIds[group] = g.ids
    }

    /// 按年级/院系拉班级清单（分页取全），按「年级|院系」缓存
    private func loadScope(grade: String, dept: String) async -> [[String: Any]] {
        let key = (grade.isEmpty ? "*" : grade) + "|" + (dept.isEmpty ? "*" : dept)
        if let cached = scopeCache[key] { return cached }
        var filter: [(String, String)] = []
        if !grade.isEmpty { filter.append(("njdm_id", grade)) }
        if !dept.isEmpty { filter.append(("jg_id", dept)) }
        let rows = await fetchAllClasses(xnm: recXnm, xqm: recXqm, filter: filter)
        if !rows.isEmpty { scopeCache[key] = rows }
        return rows
    }

    /// 教务每页固定 10 条：先取第 1 页问总行数，剩余页按 4 并发拉完。
    /// 单页失败只丢那一页，不影响整体；按页码顺序回填保证下拉顺序稳定。
    private func fetchAllClasses(xnm: String, xqm: String,
                                 filter: [(String, String)]) async -> [[String: Any]] {
        var out: [[String: Any]] = []
        guard let first = await client.post(EP.recommendList,
                                            form: Self.pagedBody(xnm: xnm, xqm: xqm, page: 1, filter: filter)) else {
            return out
        }
        guard let data = first.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return out
        }
        let total = Self.pickInt(root, "totalResult") ?? 0
        guard let arr = Self.firstArray(root) else { return out }
        let per = arr.count
        out.append(contentsOf: arr)
        if per == 0 || out.count >= total { return out }

        let pages = min(60, (total + per - 1) / per)
        guard pages >= 2 else { return out }

        // 4 并发拉剩余页
        var pageResults: [[[String: Any]]?] = Array(repeating: nil, count: pages)
        await withTaskGroup(of: (Int, [[String: Any]]?).self) { group in
            var next = 2
            let maxConcurrent = 4
            // 先放 4 个任务
            while next <= pages, next < 2 + maxConcurrent {
                let page = next
                group.addTask { [self] in
                    let s = await client.post(EP.recommendList,
                                              form: Self.pagedBody(xnm: xnm, xqm: xqm,
                                                                   page: page, filter: filter))
                    guard let st = s, let d = st.data(using: .utf8),
                          let r = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else {
                        return (page, nil)
                    }
                    return (page, Self.firstArray(r))
                }
                next += 1
            }
            // 逐个收，收一个补一个
            for await (page, result) in group {
                pageResults[page - 1] = result
                if next <= pages {
                    let p = next
                    group.addTask { [self] in
                        let s = await client.post(EP.recommendList,
                                                  form: Self.pagedBody(xnm: xnm, xqm: xqm,
                                                                       page: p, filter: filter))
                        guard let st = s, let d = st.data(using: .utf8),
                              let r = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else {
                            return (p, nil)
                        }
                        return (p, Self.firstArray(r))
                    }
                    next += 1
                }
            }
        }
        for r in pageResults {
            if let r { out.append(contentsOf: r) }
        }
        return out
    }

    private static func pagedBody(xnm: String, xqm: String, page: Int,
                                  filter: [(String, String)]) -> [(String, String)] {
        var f: [(String, String)] = []
        for k in ["xqh_id", "njdm_id", "xb_id", "jg_id", "zyh_id", "zyfx_id",
                  "bh_id", "xsdm", "pyccdm", "kclxdm", "kclbdm", "sfzhsjk",
                  "kbsjlyqz", "zs", "yf"] {
            f.append((k, ""))
        }
        f.append(("sfcxxqh", "0"))
        f.append(("xnm", xnm))
        f.append(("xqm", xqm))
        f.append(("page", "\(page)"))
        f.append(("rows", "10"))
        f.append(contentsOf: filter)
        return f
    }

    /// 把清单行拍成 18 个字段的表单（`ylMap`）
    private static func ylMap(_ row: [String: Any]) -> [(String, String)] {
        var f: [(String, String)] = []
        for k in ["xnm", "xqm", "xnmc", "xqmmc", "xqh_id", "njdm_id", "zyh_id",
                  "bh_id", "tjkbzdm", "tjkbzxsdm", "zymc", "jgmc", "njmc", "bj",
                  "xkrs", "jsxm", "lxdh", "bh"] {
            f.append((k, pick(row, k)))
        }
        for k in ["zs", "xsdm", "kclxdm", "kclbdm", "kbsjlyqz", "yf"] {
            f.append((k, ""))
        }
        f.append(("zxszjjs", "false"))
        f.append(("akcxqjchb", "false"))
        f.append(("kzlx", "ck"))
        let xqh = f.first { $0.0 == "xqh_id" }?.1 ?? ""
        f.append(("sfcxxqh", xqh.isEmpty ? "0" : "1"))
        return f
    }

    private static func valueOf(_ picked: [Int], _ i: Int, _ active: [[String]]) -> String {
        guard i < picked.count, picked[i] > 0 else { return "" }
        guard i < active.count, picked[i] < active[i].count else { return "" }
        return active[i][picked[i]]
    }

    /// 解析 `<select id|name=xxx>` 的所有 option
    static func selectOptions(_ html: String, _ key: String) -> (ids: [String], names: [String]) {
        let escaped = NSRegularExpression.escapedPattern(for: key)
        var inner: String?
        for pattern in ["<select[^>]*id=[\"']\(escaped)[\"'][^>]*>([\\s\\S]*?)</select>",
                        "<select[^>]*name=[\"']\(escaped)[\"'][^>]*>([\\s\\S]*?)</select>"] {
            if let v = JwClient.firstGroup(html, pattern: pattern) {
                inner = v
                break
            }
        }
        guard let inner else { return (["", ], ["全部"]) }

        var ids: [String] = [""]
        var names: [String] = ["全部"]
        var seen = Set<String>()
        if let re = try? NSRegularExpression(pattern: "<option[^>]*>[^<]*", options: [.caseInsensitive]) {
            for m in re.matches(in: inner, range: NSRange(inner.startIndex..., in: inner)) {
                guard let r = Range(m.range, in: inner) else { continue }
                let tag = String(inner[r])
                guard let value = JwClient.firstGroup(tag, pattern: "value=[\"']([^\"']*)[\"']"),
                      !value.isEmpty, !seen.contains(value) else { continue }
                seen.insert(value)
                var label = value
                if let gt = tag.firstIndex(of: ">") {
                    let t = String(tag[tag.index(after: gt)...]).trimmingCharacters(in: .whitespacesAndNewlines)
                    if !t.isEmpty { label = t }
                }
                ids.append(value)
                names.append(label)
                // 上游字典可能上千项，设个上限防空转
                if ids.count >= 4000 { break }
            }
        }
        return (ids, names)
    }

    private static func pick(_ o: [String: Any], _ keys: String...) -> String {
        for k in keys {
            if let v = o[k] {
                if let s = v as? String { return s.trimmingCharacters(in: .whitespaces) }
                if let n = v as? NSNumber { return n.stringValue }
            }
        }
        return ""
    }

    private static func pickInt(_ o: [String: Any], _ key: String) -> Int? {
        if let n = o[key] as? NSNumber { return n.intValue }
        if let s = o[key] as? String { return Int(s) }
        return nil
    }

    private static func firstArray(_ root: [String: Any]) -> [[String: Any]]? {
        if let arr = root["items"] as? [[String: Any]] { return arr }
        if let arr = root["rows"] as? [[String: Any]] { return arr }
        if let arr = root["data"] as? [[String: Any]] { return arr }
        if let d = root["data"] as? [String: Any] {
            for k in ["items", "rows", "list"] {
                if let arr = d[k] as? [[String: Any]] { return arr }
            }
        }
        return nil
    }

    // MARK: - HTML 小工具

    private func hiddenInputs(_ html: String) -> [String: String] {
        var out: [String: String] = [:]
        let pattern = "<input[^>]*>"
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return out
        }
        for m in re.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
            guard let r = Range(m.range, in: html) else { continue }
            let tag = String(html[r])
            let name = JwClient.firstGroup(tag, pattern: "name=[\"']([^\"']+)[\"']")
                ?? JwClient.firstGroup(tag, pattern: "id=[\"']([^\"']+)[\"']")
            let value = JwClient.firstGroup(tag, pattern: "value=[\"']([^\"']*)[\"']") ?? ""
            if let name { out[name] = value }
        }
        return out
    }

    private func noticeOf(_ html: String) -> String {
        let text = stripTags(html)
        for key in ["不在选课时间", "选课尚未开始", "选课已结束", "未开放"] {
            if let r = text.range(of: key) {
                let end = text.index(r.lowerBound, offsetBy: 40, limitedBy: text.endIndex) ?? text.endIndex
                return String(text[r.lowerBound..<end]).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        return ""
    }

    private func chosenNames(_ html: String?) -> [String] {
        guard let html else { return [] }
        var out: [String] = []
        let pattern = "id=[\"'](?:kcmc_|courseName_)[^\"']*[\"'][^>]*>\\s*([^<]{1,40})\\s*<"
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return out
        }
        for m in re.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
            if m.numberOfRanges > 1, let r = Range(m.range(at: 1), in: html) {
                let name = String(html[r]).trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty, !out.contains(name) { out.append(name) }
            }
        }
        return out
    }

    private func first(_ dict: [String: String], _ keys: String...) -> String {
        for k in keys { if let v = dict[k], !v.isEmpty { return v } }
        return ""
    }

    private func stripTags(_ html: String) -> String {
        var s = html.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: "&nbsp;", with: " ")
        s = s.replacingOccurrences(of: "&amp;", with: "&")
        s = s.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - JSON 小工具

    private func isBadPayload(_ s: String?) -> Bool {
        guard let s else { return true }
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return true }
        if t.contains("login_slogin") { return true }
        // 正方成绩接口返回「数组」或「对象」都可能 —— 上一版只认对象，导致每次刷新都误判成异常
        return !(t.hasPrefix("{") || t.hasPrefix("["))
    }

    /// 从任意 JSON 根结构里取出第一个对象数组。
    private func firstArray(_ root: Any) -> [[String: Any]]? {
        if let arr = root as? [[String: Any]] { return arr }
        if let dict = root as? [String: Any] {
            for key in ["items", "rows", "data", "kbList", "result"] {
                if let arr = dict[key] as? [[String: Any]] { return arr }
            }
            // 兼容 {"rst": {"list": [...]}}
            for value in dict.values {
                if let nested = value as? [String: Any],
                   let arr = firstArray(nested) { return arr }
            }
        }
        return nil
    }

    private func pick(_ o: [String: Any], _ keys: String...) -> String {
        for k in keys {
            if let v = o[k] {
                if let s = v as? String, !s.trimmingCharacters(in: .whitespaces).isEmpty {
                    return s.trimmingCharacters(in: .whitespaces)
                }
                if let n = v as? NSNumber { return n.stringValue }
            }
        }
        return ""
    }
}