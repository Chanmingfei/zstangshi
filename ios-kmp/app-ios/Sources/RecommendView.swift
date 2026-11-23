import SwiftUI
import Shared

/// 推荐课表 —— 对齐安卓 FeatureActivity 的 recommend 分支。
///
/// 版式（实机截图）：说明卡「筛选条件」+ 2×2 四级下拉 + 蓝色「查询课表」按钮；
/// 查询后列班级卡片，点一个班级拉课表，用与个人课表同一套周网格渲染（只读）。
/// 级联口径：年级/院系取页面字典；专业/班级按已拉班级行逐级过滤（Kotlin recommendCascade）。
struct RecommendView: View {
    @ObservedObject private var auth = JwAuth.shared

    @State private var note = "先选年级 / 院系，专业 / 班级会自动按范围加载；选完点「查询」；课表来自教务「推荐课表打印」"
    @State private var gradeOpts = OptionGroup()
    @State private var deptOpts = OptionGroup()
    @State private var majorOpts = OptionGroup()
    @State private var classOpts = OptionGroup()

    @State private var gradeIdx = 0
    @State private var deptIdx = 0
    @State private var majorIdx = 0
    @State private var classIdx = 0

    @State private var allRows: [String] = []       // 全量班级行（懒加载缓存）
    @State private var results: [String] = []       // 查询命中的班级行
    @State private var loading = true
    @State private var showTimetable = false
    @State private var kbTimetable: Timetable?
    @State private var kbWeek = 1
    @State private var kbTitle = ""
    @State private var kbNote = ""
    @State private var kbStatus = ""

    private let pathPage = "/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=N214505&layout=default"
    private let pathList = "/kbdy/bjkbdy_cxBjkbdyTjkbList.html"
    private let pathSfkz = "/kbdy/bjkbdy_cxXnxqsfkz.html?gnmkdm=N214505"
    private let pathPreview = "/kbdy/bjkbdy_ylBjkbdyIndex.html?gnmkdm=N214505"
    private let pathKb = "/kbdy/bjkbdy_cxBjKb.html?gnmkdm=N214505"

    @State private var xnm = ""
    @State private var xqm = ""

    var body: some View {
        Group {
            if showTimetable, let t = kbTimetable {
                WeekGridView(timetable: t, week: $kbWeek,
                             maxWeek: max(20, Int(t.semester.totalWeeks)),
                             sourceNote: kbNote)
                    .navigationTitle(kbTitle)
                    .navigationBarTitleDisplayMode(.inline)
            } else {
                filterView
            }
        }
        .task { await loadContext() }
    }

    // MARK: 筛选页

    private var filterView: some View {
        ScrollView {
            VStack(spacing: 12) {
                infoCard
                filterCard
                Button {
                    Task { await query() }
                } label: {
                    Text("查询课表").font(.system(size: 16, weight: .semibold))
                        .frame(maxWidth: .infinity).padding(.vertical, 14)
                        .background(Color(red: 0.0, green: 0.48, blue: 1.0))
                        .foregroundColor(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                if !results.isEmpty { resultList }
                if !kbStatus.isEmpty {
                    Text(kbStatus).font(.caption2).foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(14)
        }
        .background(Color(UIColor.systemGroupedBackground))
        .navigationTitle("推荐课表")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("刷新") { Task { await loadContext(force: true) } }
            }
        }
    }

    private var infoCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("筛选条件").font(.system(size: 20, weight: .bold))
            Text(note).font(.subheadline).foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var filterCard: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                picker("年级", selection: $gradeIdx, options: gradeOpts) { i in
                    gradeIdx = i; resetBelow(0)
                }
                picker("院系", selection: $deptIdx, options: deptOpts) { i in
                    deptIdx = i; resetBelow(1)
                }
            }
            HStack(spacing: 10) {
                picker("专业", selection: $majorIdx, options: majorOpts) { i in
                    majorIdx = i; resetBelow(2)
                }
                picker("班级", selection: $classIdx, options: classOpts) { i in
                    classIdx = i
                }
            }
        }
        .padding(14)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func picker(_ label: String, selection: Binding<Int>,
                        options: OptionGroup, onChange: @escaping (Int) -> Void) -> some View {
        HStack {
            Text("\(label)：").font(.subheadline).foregroundColor(.secondary)
            Menu {
                ForEach(0..<Int(options.size), id: \.self) { i in
                    Button(options.names[i]) { onChange(i) }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(currentName(options, selection.wrappedValue))
                        .font(.subheadline).foregroundColor(.primary)
                    Image(systemName: "chevron.down").font(.caption2).foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 10).padding(.vertical, 9)
                .background(Color(UIColor.tertiarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func currentName(_ g: OptionGroup, _ idx: Int) -> String {
        idx < g.size ? g.names[idx] : "全部"
    }

    private func currentId(_ g: OptionGroup, _ idx: Int) -> String {
        idx < g.size ? g.ids[idx] : ""
    }

    private func resetBelow(_ level: Int) {
        // level=0 改年级 → 清空 院系/专业/班级；level=1 改院系 → 清空 专业/班级
        if level <= 0 { deptIdx = 0 }
        majorIdx = 0
        classIdx = 0
        Task { await refreshCascade(after: level) }
    }

    private var resultList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("匹配 \(results.count) 个班级").font(.headline)
            ForEach(results, id: \.self) { row in
                Button {
                    Task { await openClass(row) }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(JwFeaturesKt.className(rowJson: row))
                                .font(.subheadline).bold().foregroundColor(.primary)
                            Text(JwFeaturesKt.classSubtitle(rowJson: row))
                                .font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        Text(JwFeaturesKt.classSize(rowJson: row) + " 人")
                            .font(.caption).foregroundColor(.secondary)
                        Image(systemName: "chevron.right").font(.caption2).foregroundColor(.secondary)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(UIColor.secondarySystemGroupedBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - 数据

    private func loadContext(force: Bool = false) async {
        loading = true
        defer { loading = false }
        guard let page = await auth.request("GET", pathPage) else {
            note = "推荐课表页打不开，请检查网络：\(auth.message)"
            return
        }
        let ctx = JwFeaturesKt.recommendContext(html: page)
        guard ctx.ok else {
            note = ctx.message
            return
        }
        xnm = ctx.xnm
        xqm = ctx.xqm
        gradeOpts = ctx.grade
        deptOpts = ctx.dept
        note = "先选年级 / 院系，专业 / 班级会自动按范围加载；选完点「查询」；课表来自教务「推荐课表打印」"
    }

    /// 懒加载全量班级行（分页拉全，最多 60 页，口径同安卓 fetchAllClasses）。
    private func ensureRows() async {
        if !allRows.isEmpty { return }
        var rows: [String] = []
        var page = 1
        var total = 0
        while page <= 60 {
            let form = JwFeaturesKt.classListForm(
                xnm: xnm, xqm: xqm, page: Int32(page), filter: [])
            guard let body = await auth.request("POST", pathList, form) else { break }
            let part = JwFeaturesKt.parseClassList(json: body)
            if page == 1 { total = Int(part.total) }
            rows.append(contentsOf: part.rows)
            if part.rows.isEmpty || rows.count >= total { break }
            page += 1
        }
        allRows = rows
        // 专业/班级选项从全量行里按已选上级过滤
        await refreshCascade(after: 0)
    }

    private func refreshCascade(after level: Int) async {
        guard !allRows.isEmpty else { return }
        let g = currentId(gradeOpts, gradeIdx)
        let d = currentId(deptOpts, deptIdx)
        if level <= 0 {
            majorOpts = JwFeaturesKt.recommendCascade(
                rows: allRows, gradeId: g, deptId: d, majorId: "", group: 2)
            majorIdx = 0
        }
        classOpts = JwFeaturesKt.recommendCascade(
            rows: allRows, gradeId: g, deptId: d, majorId: currentId(majorOpts, majorIdx), group: 3)
        classIdx = 0
    }

    private func query() async {
        kbStatus = ""
        await ensureRows()
        guard !allRows.isEmpty else {
            kbStatus = auth.message.isEmpty ? "没有拉到班级清单，请点刷新重试" : auth.message
            results = []
            return
        }
        let g = currentId(gradeOpts, gradeIdx)
        let d = currentId(deptOpts, deptIdx)
        let m = currentId(majorOpts, majorIdx)
        // 按已选条件过滤行（Kotlin 侧完成，Swift 不碰 Js 类型）
        let filtered = JwFeaturesKt.filterClassRows(
            rows: allRows, gradeId: g, deptId: d, majorId: m)
        results = filtered
        if filtered.isEmpty {
            kbStatus = "没有符合条件的班级，换个筛选条件试试"
        } else if filtered.count == 1 {
            await openClass(filtered[0])
        }
    }

    private func openClass(_ row: String) async {
        kbStatus = "正在读取推荐课表…"
        let form = JwFeaturesKt.recommendPreviewForm(rowJson: row)
        // ① 学年学期是否允许查看
        if let sfkz = await auth.request("POST", pathSfkz, form),
           sfkz.trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "\"", with: "") == "true" {
            kbStatus = "该学年的推荐课表当前不可查看（教务限制）"
            return
        }
        // ② 还原「预览已打开」会话语境（失败不阻断）
        _ = await auth.request("POST", pathPreview, form)
        // ③ 取课表
        guard let kb = await auth.request("POST", pathKb, form) else {
            kbStatus = "班级课表接口没有返回：\(auth.message)"
            return
        }
        let repaired = JwFeaturesKt.repairKbJson(json: kb)
        let parsed = KbParser.shared.parse(json: repaired)
        guard parsed.ok() else {
            kbStatus = "班级课表解析失败（\(parsed.warnings.count) 条提示）"
            return
        }
        let t = parsed.timetable
        kbTimetable = t
        kbWeek = 1
        kbTitle = JwFeaturesKt.className(rowJson: row)
        kbNote = "推荐课表 · " + JwFeaturesKt.classSubtitle(rowJson: row)
        kbStatus = ""
        showTimetable = true
    }
}
