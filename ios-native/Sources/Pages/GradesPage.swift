import SwiftUI

/// 底栏「成绩」页（对齐 Android `pages/GradesPage.kt`）。
///
/// 数据用与 FeatureActivity 同一份本地快照（`store.feature("grades")`）：
/// 有快照就秒开、不联网；没有快照才后台取一次并落盘。
/// 顶栏「刷新」与下拉刷新都能强制更新；「更多 ›」进完整页（带学期筛选）。
struct GradesPage: View {
    @EnvironmentObject private var app: AppState
    let width: CGFloat

    @State private var data: JwApi.Data?
    @State private var loading = false
    @State private var errorMessage: String?
    @State private var semesterPicked = 0
    @State private var semesterOptions: [String] = ["全部学期"]
    @State private var showMore = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 10) {
                    topBar

                    if let d = data, d.ok {
                        overview(d)
                        semesterFilter(d)
                        ForEach(visibleRows(d)) { r in
                            courseCard(r)
                        }
                    } else {
                        StateBlock(message: errorMessage ?? (loading ? "正在从教务读取成绩…" : "还没有教务成绩"),
                                   loading: loading,
                                   retry: { Task { await load(force: true) } })
                            .padding(.top, 2)
                    }
                }
                .padding(.horizontal, T.pagePadding)
                .padding(.top, 6)
                .padding(.bottom, T.dockBand + 24)
            }
            .scrollIndicators(.hidden)
            .background(T.bgPage.ignoresSafeArea())
            .navigationDestination(isPresented: $showMore) {
                FeaturePage(kind: .grades)
            }
            .toolbar(.hidden, for: .navigationBar)
            .zstRefreshable(minDuration: 1.2) { [self] in
                await load(force: true)
            }
        }
        .onAppear {
            build()
            autoLoadIfNeeded()
        }
        .onChange(of: app.tab) { _, newValue in
            if newValue == MainTab.grades.rawValue { build() }
        }
    }

    // MARK: - 顶栏

    private var topBar: some View {
        HStack(spacing: 0) {
            Text("教务成绩")
                .font(F.title)
                .foregroundStyle(T.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)

            Button {
                showMore = true
            } label: {
                Text("更多 ›")
                    .font(F.footnote)
                    .foregroundStyle(T.brand)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.pressable(0.95))

            if loading {
                LoadingRing(size: 15)
                    .padding(.trailing, 2)
            }

            Button {
                Task { await load(force: true) }
            } label: {
                Text(loading ? "读取中" : "刷新")
                    .font(F.footnote)
                    .foregroundStyle(loading ? T.textTertiary : T.brand)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.pressable(0.95))
            .disabled(loading)
        }
        .padding(.horizontal, 2)
        .padding(.bottom, 2)
    }

    // MARK: - 概览

    private func overview(_ d: JwApi.Data) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(d.headline)
                .font(F.display)
                .foregroundStyle(T.brand)

            if !d.note.isEmpty {
                Text(d.note)
                    .font(F.tiny)
                    .foregroundStyle(T.textTertiary)
                    .padding(.top, 6)
            }

            Text("读取于 \(timeLabel(d.at)) · 点「刷新」更新，不自动重复加载")
                .font(F.micro)
                .foregroundStyle(T.textTertiary)
                .padding(.top, 8)
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

    // MARK: - 学期筛选

    /// 把行里出现的学期去重、排序（"YYYY-YYYY · N" 字典序即时间序）
    private func uniqSemesters(_ d: JwApi.Data) -> [String] {
        var uniq: [String] = []
        for r in d.rows where !r.tag.isEmpty && !uniq.contains(r.tag) {
            uniq.append(r.tag)
        }
        uniq.sort()
        return uniq
    }

    // ViewBuilder 里不能写 `for` / `var`，所以先算好再进视图
    private func semesterFilter(_ d: JwApi.Data) -> some View {
        let uniq = uniqSemesters(d)
        let multi = uniq.count > 1

        return HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                Text("学期筛选")
                    .font(F.callout.weight(.semibold))
                    .foregroundStyle(T.textPrimary)
                Text(multi ? "只看某一学期的成绩" : "当前仅一个学期")
                    .font(F.tiny)
                    .foregroundStyle(T.textTertiary)
            }
            Spacer(minLength: 8)

            if multi {
                Menu {
                    Button("全部学期") { semesterPicked = 0 }
                    ForEach(uniq.indices, id: \.self) { i in
                        Button(uniq[i]) { semesterPicked = i + 1 }
                    }
                } label: {
                    Text(semesterOptions[safe: semesterPicked] ?? "全部学期")
                        .font(.system(size: F.scaled(16), weight: .bold))
                        .foregroundStyle(T.brand)
                }
            } else {
                Text("全部学期")
                    .font(F.tiny)
                    .foregroundStyle(T.textTertiary)
            }
        }
        .padding(.horizontal, 15)
        .padding(.vertical, 13)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
        // 不在 ViewBuilder 里改 @State：用 onChange 同步选项数组
        .onChange(of: d.rows.count, initial: true) { _, _ in
            var opts = ["全部学期"]
            opts.append(contentsOf: uniq)
            if opts != semesterOptions { semesterOptions = opts }
        }
    }

    private func visibleRows(_ d: JwApi.Data) -> [JwApi.Row] {
        guard semesterPicked > 0, semesterPicked < semesterOptions.count else { return d.rows }
        let tag = semesterOptions[semesterPicked]
        return d.rows.filter { $0.tag == tag }
    }

    // MARK: - 课程行

    private func courseCard(_ r: JwApi.Row) -> some View {
        HStack(alignment: .center, spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                Text(r.title)
                    .font(F.bodyBold)
                    .foregroundStyle(T.textPrimary)
                    .multilineTextAlignment(.leading)
                if !r.sub.isEmpty {
                    Text(r.sub)
                        .font(F.tiny)
                        .foregroundStyle(T.textTertiary)
                        .multilineTextAlignment(.leading)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .center, spacing: 1) {
                Text(r.value.isEmpty ? "—" : r.value)
                    .font(.system(size: F.scaled(19), weight: .bold))
                    .foregroundStyle(T.brand)
                if !r.extra.isEmpty {
                    Text("绩点 \(r.extra)")
                        .font(F.micro)
                        .foregroundStyle(T.textTertiary)
                }
            }
            .padding(.leading, 10)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(T.bgSurface)
        .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                .strokeBorder(T.borderSubtle, lineWidth: 0.7)
        )
    }

    // MARK: - 数据

    private func build() {
        let cached = app.store.feature("grades")
        if !cached.isEmpty {
            let d = JwApi.Data.fromJson(cached)
            data = d.ok ? d : nil
            if !d.ok { data = nil }
        } else {
            data = nil
        }
        if data == nil && !loading && errorMessage == nil {
            errorMessage = "还没有教务成绩"
        }
    }

    /// 只在「首次没快照」或「本次登录会话还没抓过」时才自动联网
    private func autoLoadIfNeeded() {
        guard !loading else { return }
        if data == nil {
            Task { await load(force: false) }
            return
        }
        // 快照比登录时间早 = 这个会话还没抓过，补一次
        if app.store.featureAt("grades") < app.store.loginAt() {
            Task { await load(force: false) }
        }
    }

    private func load(force: Bool) async {
        guard !loading else { return }
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
            Haptics.error()
            return
        }

        let d = await app.api.grades(force: force)
        loading = false
        if d.ok {
            data = d
            let json = d.toJson()
            if !json.isEmpty { app.store.saveFeature("grades", json) }
        } else {
            errorMessage = d.message.isEmpty ? "成绩读取失败" : d.message
            if data == nil { Haptics.error() }
        }
    }

    private func timeLabel(_ at: TimeInterval) -> String {
        guard at > 0 else { return "刚刚" }
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "MM-dd HH:mm"
        return f.string(from: Date(timeIntervalSince1970: at))
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}