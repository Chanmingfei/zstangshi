import SwiftUI

/// 推荐课表页：四级级联（年级 / 院系 / 专业 / 班级）→ 查询 → 课表。
///
/// 严格按安卓 `FeatureActivity.kt:441-655` + `JwApi.kt:430-821` 的交互：
///   · 年级 / 院系 用页面全量字典，点了立刻弹（无网络）；
///   · 专业 / 班级 需要先选上级，点了去服务端拉班级清单再提取；
///   · 查询命中 1 个班 → 直接出课表；多个班 → 列卡片（最多 80 个）；
///   · 课表只读，**不导入本地课表**（安卓也没有这个功能）。
struct RecommendPage: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var ctx: JwApi.RecommendContext?
    @State private var picked: [Int] = [0, 0, 0, 0]
    @State private var groups: [JwApi.RecommendGroup] = []
    @State private var loading = true
    @State private var errorMessage: String?

    @State private var queryResult: JwApi.RecommendQueryResult?
    @State private var querying = false
    @State private var kbJson: String?
    @State private var kbTitle = ""
    @State private var kbNote = ""

    @State private var optionSheet: OptionSheetState?
    @State private var optionLoading = false

    struct OptionSheetState: Identifiable {
        let id = UUID()
        let groupIndex: Int
        let title: String
        let options: JwApi.RecommendGroup
        let pickedIndex: Int
    }

    var body: some View {
        Group {
            if let kbJson {
                RecommendKbView(kbJson: kbJson, title: kbTitle, note: kbNote,
                                onBack: { self.kbJson = nil })
                    .environmentObject(app)
            } else {
                filterForm
            }
        }
        .background(T.bgPage.ignoresSafeArea())
        .navigationTitle("推荐课表")
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadContext() }
        .sheet(item: $optionSheet) { s in
            OptionPickerSheet(title: s.title,
                              group: s.options,
                              pickedIndex: s.pickedIndex) { idx in
                pick(s.groupIndex, idx)
            }
        }
    }

    // MARK: - 筛选表单

    private var filterForm: some View {
        ScrollView {
            VStack(spacing: 12) {
                if loading {
                    StateBlock(message: "正在读取筛选条件…", loading: true)
                } else if let errorMessage {
                    StateBlock(message: errorMessage,
                               retry: { Task { await loadContext() } })
                } else if let ctx {
                    headerCard(ctx)
                    cascadeGrid
                    queryButton
                    if let q = queryResult {
                        resultSection(q)
                    }
                }
            }
            .padding(.horizontal, T.pagePadding)
            .padding(.top, 10)
            .padding(.bottom, 28)
        }
        .scrollIndicators(.hidden)
    }

    private func headerCard(_ ctx: JwApi.RecommendContext) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(ctx.headline)
                .font(F.headline)
                .foregroundStyle(T.textPrimary)
            Text(ctx.note)
                .font(F.caption)
                .foregroundStyle(T.textTertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    /// 2×2 筛选格
    private var cascadeGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10),
                            GridItem(.flexible(), spacing: 10)], spacing: 10) {
            ForEach(0..<4, id: \.self) { i in
                Button {
                    Task { await openPicker(i) }
                } label: {
                    HStack(spacing: 6) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(groupLabel(i))
                                .font(F.micro)
                                .foregroundStyle(T.textTertiary)
                            Text(currentValueName(i))
                                .font(F.callout)
                                .foregroundStyle(picked[i] == 0 ? T.textSecondary : T.brand)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(T.textTertiary)
                    }
                    .padding(.horizontal, 12)
                    .frame(height: 52)
                    .background(T.bgSurface)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(T.borderSubtle, lineWidth: 0.8)
                    )
                }
                .buttonStyle(.pressable(0.97))
            }
        }
    }

    private func groupLabel(_ i: Int) -> String {
        ["年级", "院系", "专业", "班级"][i]
    }

    private func currentValueName(_ i: Int) -> String {
        guard i < groups.count, picked[i] < groups[i].names.count else { return "全部" }
        return groups[i].names[picked[i]]
    }

    private var queryButton: some View {
        Button {
            Task { await runQuery() }
        } label: {
            if querying {
                HStack(spacing: 6) {
                    LoadingRing(size: 14)
                    Text("查询中…")
                }
            } else {
                Text("查询课表")
            }
        }
        .buttonStyle(PrimaryButtonStyle(enabled: !querying))
        .disabled(querying)
        .padding(.top, 4)
    }

    // MARK: - 结果

    @ViewBuilder
    private func resultSection(_ q: JwApi.RecommendQueryResult) -> some View {
        if q.rows.isEmpty {
            EmptyCard(title: "没有结果", subtitle: q.message.isEmpty ? q.note : q.message)
        } else {
            VStack(alignment: .leading, spacing: 0) {
                Text(q.headline.isEmpty ? "匹配班级" : q.headline)
                    .font(F.captionBold)
                    .foregroundStyle(T.textSecondary)
                    .padding(.bottom, 6)

                ForEach(q.rows.indices, id: \.self) { i in
                    let r = q.rows[i]
                    Button {
                        Task { await loadClassKb(index: i) }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(r.title)
                                    .font(F.body)
                                    .foregroundStyle(T.textPrimary)
                                if !r.sub.isEmpty {
                                    Text(r.sub)
                                        .font(F.micro)
                                        .foregroundStyle(T.textTertiary)
                                }
                            }
                            Spacer()
                            if !r.value.isEmpty {
                                Text(r.value)
                                    .font(F.micro)
                                    .foregroundStyle(T.textTertiary)
                            }
                            Image(systemName: "chevron.right")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(T.textTertiary)
                        }
                        .padding(.vertical, 10)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if i < q.rows.count - 1 {
                        Divider().overlay(T.borderSubtle)
                    }
                }

                if !q.note.isEmpty {
                    Text(q.note)
                        .font(F.micro)
                        .foregroundStyle(T.textTertiary)
                        .padding(.top, 8)
                }
            }
            .padding(16)
            .background(T.bgSurface)
            .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                    .strokeBorder(T.borderSubtle, lineWidth: 0.7)
            )
        }
    }

    // MARK: - 逻辑

    private func loadContext() async {
        loading = true
        errorMessage = nil
        guard !app.store.user().isEmpty, !app.store.pass.isEmpty else {
            loading = false
            errorMessage = "还没有登录教务，请先到「我的」登录"
            return
        }
        let ok = await app.api.login(app.store.user(), app.store.pass)
        guard ok else {
            loading = false
            let msg = await app.api.loginMessage
            errorMessage = msg.isEmpty ? "登录教务失败" : msg
            return
        }
        let c = await app.api.recommendContext()
        loading = false
        if c.ok {
            ctx = c
            groups = c.groups
        } else {
            errorMessage = c.message
        }
    }

    private func openPicker(_ i: Int) async {
        // 级联前置校验（与安卓一致）
        if i == 2, picked[0] == 0 && picked[1] == 0 {
            app.showToast("请先选择年级或院系")
            return
        }
        if i == 3, picked[2] == 0 {
            app.showToast("请先选择专业")
            return
        }

        optionLoading = true
        let g = await app.api.recommendOptions(picked: picked, group: i)
        optionLoading = false

        // 只有一条占位 → 不弹层，直接 Toast 占位文案
        if g.ids.count <= 1 {
            app.showToast(g.names.first ?? "暂无可选项")
            return
        }

        // 同步回本地候选（专业/班级的 ids 是服务端 UUID，必须用返回的这份）
        while groups.count <= i {
            groups.append(JwApi.RecommendGroup())
        }
        groups[i] = g

        optionSheet = OptionSheetState(groupIndex: i, title: g.label,
                                       options: g, pickedIndex: picked[i])
    }

    private func pick(_ groupIndex: Int, _ index: Int) {
        guard groupIndex < picked.count else { return }
        picked[groupIndex] = index
        // 清空所有下级（与安卓一致）
        for j in (groupIndex + 1)..<picked.count {
            picked[j] = 0
            if j < groups.count {
                groups[j].ids = [""]
                groups[j].names = ["全部"]
            }
        }
        queryResult = nil
        Haptics.tick()
    }

    private func runQuery() async {
        guard picked.contains(where: { $0 > 0 }) else {
            app.showToast("请先选择至少一个筛选条件，再点查询")
            return
        }
        querying = true
        defer { querying = false }
        let r = await app.api.recommendQuery(picked: picked)
        if r.ok, !r.kbJson.isEmpty {
            // 单班命中：直接进课表
            kbJson = r.kbJson
            kbTitle = r.headline
            kbNote = r.note
        } else if r.ok {
            queryResult = r
        } else {
            app.showToast(r.message.isEmpty ? "查询失败" : r.message)
            queryResult = nil
        }
    }

    private func loadClassKb(index: Int) async {
        querying = true
        defer { querying = false }
        let r = await app.api.recommendKb(index: index)
        if r.ok {
            kbJson = r.kbJson
            kbTitle = r.headline
            kbNote = r.note
        } else {
            app.showToast(r.message.isEmpty ? "课表加载失败" : r.message)
        }
    }
}

// MARK: - 选项选择弹层

struct OptionPickerSheet: View {
    let title: String
    let group: JwApi.RecommendGroup
    let pickedIndex: Int
    var onPick: (Int) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var filter = ""

    var body: some View {
        NavigationStack {
            Group {
                if group.names.count > 60 {
                    List(filtered.indices, id: \.self) { i in
                        row(i)
                    }
                    .searchable(text: $filter, prompt: "搜索\(title)")
                } else {
                    List(filtered.indices, id: \.self) { i in
                        row(i)
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var filtered: [Int] {
        guard !filter.isEmpty else { return Array(group.names.indices) }
        return group.names.indices.filter { group.names[$0].contains(filter) }
    }

    private func row(_ i: Int) -> some View {
        Button {
            onPick(i)
            dismiss()
        } label: {
            HStack {
                Text(group.names[i])
                    .foregroundStyle(T.textPrimary)
                Spacer()
                if i == pickedIndex {
                    Image(systemName: "checkmark")
                        .foregroundStyle(T.brand)
                }
            }
        }
    }
}

// MARK: - 推荐课表只读展示

/// 用 KbParser 解析教务返回的班级课表 JSON，复用课表网格（只读）。
struct RecommendKbView: View {
    @EnvironmentObject private var app: AppState
    let kbJson: String
    let title: String
    let note: String
    var onBack: () -> Void

    @State private var timetable: Timetable?
    @State private var week = 1
    @State private var parseError: String?

    private let headH: CGFloat = 34
    private let slotW: CGFloat = 46

    var body: some View {
        VStack(spacing: 0) {
            topBar
            if let t = timetable, !t.courses.isEmpty {
                grid(t)
            } else if let parseError {
                StateBlock(message: parseError)
                    .padding(.horizontal, T.pagePadding)
                    .padding(.top, 20)
            } else {
                StateBlock(message: "正在解析课表…", loading: true)
                    .padding(.horizontal, T.pagePadding)
                    .padding(.top, 20)
            }
            Spacer(minLength: 0)
        }
        .onAppear { parse() }
    }

    private var topBar: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Button {
                    Haptics.light()
                    onBack()
                } label: {
                    Label("重新筛选", systemImage: "chevron.left")
                        .font(F.caption)
                        .foregroundStyle(T.brand)
                }
                .buttonStyle(.plain)
                Spacer()
                if let t = timetable, !t.courses.isEmpty {
                    Text("第 \(week) 周")
                        .font(F.caption)
                        .foregroundStyle(T.textSecondary)
                }
            }

            Text(title)
                .font(F.headline)
                .foregroundStyle(T.textPrimary)

            if !note.isEmpty {
                Text(note)
                    .font(F.micro)
                    .foregroundStyle(T.textTertiary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, T.pagePadding)
        .padding(.vertical, 10)
    }

    private func grid(_ t: Timetable) -> some View {
        GeometryReader { geo in
            let n = max(app.schedule.clampedSectionCount,
                        min(ScheduleSettings.maxSections, t.maxSectionUsed))
            let gridH = max(geo.size.height, headH + CGFloat(n) * 52)
            let overlay = WeekOverlay.build(t, start: t.semester.startDate, week: week)

            ScrollView(.vertical, showsIndicators: false) {
                TimetableGridView(
                    timetable: t,
                    week: week,
                    overlay: overlay,
                    schedule: app.schedule,
                    sectionCount: n,
                    headHeight: headH,
                    slotWidth: slotW,
                    onCourseTap: { _, _, _ in },
                    onEmptyTap: nil
                )
                .frame(width: geo.size.width, height: gridH)
            }
        }
    }

    private func parse() {
        let r = KbParser.parse(kbJson)
        guard r.ok, var t = r.timetable else {
            parseError = r.warnings.first ?? "课表解析为空"
            return
        }
        // 起始周一：解析结果的 startDate → 本地已同步课表的 startDate → 内置校历 → 今天所在周周一
        if !Dates.valid(t.semester.startDate) {
            if let local = app.timetable?.semester.startDate, Dates.valid(local) {
                t.semester.startDate = local
            } else if let term = AcademicCalendar.term(byId: t.semester.id),
                      !term.classStart.isEmpty {
                t.semester.startDate = WeekMath.normalizeToMonday(term.classStart)
            } else {
                t.semester.startDate = WeekMath.normalizeToMonday(Dates.todayLocal())
            }
        }
        // 周次解析失败的时段补成全周（安卓同款兜底）
        for ci in t.courses.indices {
            for si in t.courses[ci].sessions.indices where t.courses[ci].sessions[si].weeks.isEmpty {
                t.courses[ci].sessions[si].weeks = Array(1...t.semester.totalWeeks)
                t.courses[ci].sessions[si].weeksRaw = "全周"
            }
        }
        timetable = t
        week = WeekMath.currentWeek(startDate: t.semester.startDate,
                                    totalWeeks: t.semester.totalWeeks)
    }
}