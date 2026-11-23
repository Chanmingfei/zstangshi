import SwiftUI
import Shared

/// 自主选课（只读）—— 对齐安卓 FeatureActivity 的 selection 分支。
///
/// 安卓口径：不在选课时间窗就只显示空态卡；开放后展示课池/余量课程行；
/// 抢课必须跳教务网页，App 不做真实提交。网络与会话走 JwAuth（含 http 自动降级），
/// HTML/JSON 解析全部在 Kotlin 共享层。
struct SelectionView: View {
    @ObservedObject private var auth = JwAuth.shared

    @State private var headline = ""
    @State private var note = ""
    @State private var courses: [SelectionCourse] = []
    @State private var chosen: [String] = []
    @State private var loading = true
    @State private var loadedAt = ""
    @State private var showNotice = false

    private let pathIndex = "/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default"
    private let pathChosen = "/xsxk/zzxkyzb_cxZzxkYzbChoosed.html?gnmkdm=N253512"
    private let pathDisplay = "/xsxk/zzxkyzb_cxZzxkYzbDisplay.html?gnmkdm=N253512"
    private let pathPart = "/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html?gnmkdm=N253512"

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if loading {
                    Text("正在从教务读取…")
                        .font(.subheadline).foregroundColor(.secondary)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 120)
                } else {
                    infoCard
                    if !chosen.isEmpty { chosenCard }
                    if !courses.isEmpty { courseList }
                    refreshHint
                }
            }
            .padding(14)
        }
        .background(Color(UIColor.systemGroupedBackground))
        .navigationTitle("自主选课")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("刷新") { Task { await load(force: true) } }
            }
        }
        .task { await load(force: false) }
    }

    // MARK: 主信息卡

    private var infoCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(headline.isEmpty ? "当前不在选课时间窗" : headline)
                .font(.system(size: 20, weight: .bold))
            Text(note.isEmpty
                 ? "教务自主选课未开放；开放后这里会显示课池、余量与上课时间"
                 : note)
                .font(.subheadline).foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var chosenCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("已选课程").font(.headline)
            ForEach(chosen, id: \.self) { name in
                HStack {
                    Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
                    Text(name).font(.subheadline)
                    Spacer()
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private var courseList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("课程池 · 共 \(courses.count) 门").font(.headline)
            ForEach(Array(courses.enumerated()), id: \.offset) { pair in
                let c = pair.element
                HStack(alignment: .top, spacing: 10) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(c.name).font(.subheadline).bold()
                        Text(c.sub.isEmpty ? "—" : c.sub)
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Spacer()
                    if !c.value.isEmpty {
                        Text(c.value).font(.caption).foregroundColor(.secondary)
                    }
                }
                .padding(12)
                .background(Color(UIColor.secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            Text("本页只读，抢课请到教务网页完成")
                .font(.caption2).foregroundColor(.secondary)
        }
    }

    private var refreshHint: some View {
        Text("点右上角「刷新」更新 · 读取于 \(loadedAt)（本页只加载一次）")
            .font(.caption2).foregroundColor(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 4)
    }

    // MARK: - 数据

    private func load(force: Bool) async {
        loading = true
        defer { loading = false }
        // 入口页：拿闸门 + display 表单
        guard let page = await auth.request("GET", pathIndex) else {
            headline = "选课页打不开，请检查网络"
            note = auth.message
            stamp()
            return
        }
        let ctx = JwFeaturesKt.selectionContext(html: page)
        // 已选课程（无论闸门开不开都先看）
        if let chosenHtml = await auth.request("GET", pathChosen) {
            chosen = JwParseKt.chosenNames(html: chosenHtml)
        }
        guard ctx.gate == "1" else {
            headline = "当前不在选课时间窗"
            note = ctx.notice.isEmpty
                ? "教务自主选课未开放；开放后这里会显示课池、余量与上课时间"
                : ctx.notice
            courses = []
            stamp()
            return
        }
        let form = JwFeaturesKt.selectionDisplayForm(ctx: ctx)
        guard let display = await auth.request("POST", pathDisplay, form) else {
            headline = "选课页打不开，请检查网络"
            note = auth.message
            stamp()
            return
        }
        let displayHidden = JwParseKt.hiddenInputs(html: display)
        guard !displayHidden.isEmpty else {
            headline = "选课已开放，但读不到选课上下文"
            note = "教务页面没给出选课参数，暂只能在教务网页里选课"
            courses = []
            stamp()
            return
        }
        let part = JwFeaturesKt.selectionPartForm(displayHidden: displayHidden)
        guard let poolJson = await auth.request("POST", pathPart, part) else {
            headline = "课池接口没有响应"
            note = auth.message
            stamp()
            return
        }
        let pool = JwFeaturesKt.parseSelectionPool(json: poolJson)
        if pool.ok {
            headline = pool.headline
            note = pool.note
            courses = pool.courses
        } else {
            headline = "课池解析失败"
            note = pool.message
        }
        stamp()
    }

    private func stamp() {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "MM-dd HH:mm"
        loadedAt = f.string(from: Date())
    }
}
