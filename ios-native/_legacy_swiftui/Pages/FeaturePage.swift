import SwiftUI

/// 教务功能页（成绩 / 学分绩点 / 选课 / 推荐课表）。
///
/// 对应安卓 `FeatureActivity` 的四种类型：四页共用一套「标题 + 概览 + 行列表」的结构，
/// 数据来自 `JwApi` 的对应方法，并落盘为本地快照。
struct FeaturePage: View {
    enum Kind: String, Hashable {
        case grades, gpa, selection, recommend

        var title: String {
            switch self {
            case .grades: return "成绩"
            case .gpa: return "学分绩点"
            case .selection: return "自主选课"
            case .recommend: return "推荐课表"
            }
        }

        var featureKey: String {
            switch self {
            case .grades: return "grades"
            case .gpa: return "gpa"
            case .selection: return "selection"
            case .recommend: return "recommend"
            }
        }
    }

    @EnvironmentObject private var app: AppState
    let kind: Kind

    @State private var data: JwApi.Data?
    @State private var loading = false
    @State private var errorMessage: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                if let d = data, d.ok {
                    headlineCard(d)
                    if d.rows.isEmpty {
                        EmptyCard(title: "没有数据", subtitle: d.note)
                    } else {
                        ForEach(d.rows) { r in
                            rowCard(r)
                        }
                    }
                } else {
                    StateBlock(message: errorMessage ?? (loading ? "正在读取…" : "还没有数据"),
                               loading: loading,
                               retry: { Task { await load(force: true) } })
                }
            }
            .padding(.horizontal, T.pagePadding)
            .padding(.top, 10)
            .padding(.bottom, 30)
        }
        .scrollIndicators(.hidden)
        .background(T.bgPage.ignoresSafeArea())
        .navigationTitle(kind.title)
        .navigationBarTitleDisplayMode(.inline)
        .zstRefreshable(minDuration: 1.0) { [self] in
            await load(force: true)
        }
        .task {
            build()
            if data == nil {
                await load(force: false)
            } else if app.store.featureAt(kind.featureKey) < app.store.loginAt() {
                await load(force: false)
            }
        }
    }

    // MARK: - 概览卡

    private func headlineCard(_ d: JwApi.Data) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(d.headline)
                .font(kind == .gpa ? F.display : F.title)
                .foregroundStyle(T.brand)

            if !d.note.isEmpty {
                Text(d.note)
                    .font(F.tiny)
                    .foregroundStyle(T.textTertiary)
                    .padding(.top, 6)
            }
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

    private func rowCard(_ r: JwApi.Row) -> some View {
        HStack(alignment: .top, spacing: 0) {
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

            if !r.value.isEmpty || !r.extra.isEmpty {
                VStack(alignment: .trailing, spacing: 1) {
                    Text(r.value.isEmpty ? "—" : r.value)
                        .font(.system(size: F.scaled(17), weight: .bold))
                        .foregroundStyle(T.brand)
                    if !r.extra.isEmpty {
                        Text(r.extra)
                            .font(F.micro)
                            .foregroundStyle(T.textTertiary)
                    }
                }
                .padding(.leading, 8)
            }
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
        let cached = app.store.feature(kind.featureKey)
        if !cached.isEmpty {
            let d = JwApi.Data.fromJson(cached)
            if d.ok { data = d }
        }
    }

    private func load(force: Bool) async {
        guard !loading else { return }
        guard !app.store.user().isEmpty, !app.store.pass.isEmpty else {
            errorMessage = "还没有登录教务，请先到「我的」登录"
            return
        }
        loading = true
        errorMessage = nil
        defer { loading = false }

        let ok = await app.api.login(app.store.user(), app.store.pass)
        guard ok else {
            let msg = await app.api.loginMessage
            errorMessage = msg.isEmpty ? "登录教务失败" : msg
            Haptics.error()
            return
        }

        let d: JwApi.Data
        switch kind {
        case .grades:
            d = await app.api.grades(force: force)
        case .gpa:
            d = await app.api.gpa()
        case .selection:
            d = await app.api.selection()
        case .recommend:
            // 推荐课表在安卓是「四级级联筛选 + 查询课表」，此处先复用成绩口径的占位实现，
            // 明确告诉用户去教务网页操作，而不是显示一个假的空列表。
            var placeholder = JwApi.Data()
            placeholder.ok = true
            placeholder.headline = "推荐课表"
            placeholder.note = "推荐课表需要「年级 / 院系 / 专业 / 班级」四级级联查询，当前版本请用「更多功能」在教务网页查看"
            d = placeholder
        }

        if d.ok {
            data = d
            if kind != .recommend {
                let json = d.toJson()
                if !json.isEmpty { app.store.saveFeature(kind.featureKey, json) }
            }
        } else {
            errorMessage = d.message.isEmpty ? "读取失败" : d.message
            Haptics.error()
        }
    }
}