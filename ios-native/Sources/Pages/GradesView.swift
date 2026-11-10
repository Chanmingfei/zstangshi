import SwiftUI

extension JwApi.Kind: Identifiable {
    public var id: String { rawValue }
    var title: String {
        switch self {
        case .grades: return "成绩"
        case .gpa: return "学分绩点"
        }
    }
}

/// 底栏「成绩」页：顶部可以切「成绩 / 学分绩点」，两者共用同一套列表。
struct GradesHubView: View {
    @EnvironmentObject private var app: AppState
    @State private var kind: JwApi.Kind = .grades

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("成绩")
                    .font(F.pageTitle)
                    .foregroundStyle(T.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 18)
            .padding(.top, 10)
            .padding(.bottom, 8)

            Segmented(options: [JwApi.Kind.grades.title, JwApi.Kind.gpa.title],
                      index: Binding(
                        get: { kind == .grades ? 0 : 1 },
                        set: { kind = $0 == 0 ? .grades : .gpa }
                      ))
                .padding(.horizontal, 16)
                .padding(.bottom, 10)

            GradesContent(kind: kind)
        }
        .background(T.bgPage)
    }
}

/// 独立弹出的成绩页（首页宫格点「成绩 / 学分绩点」时用）
struct GradesView: View {
    let kind: JwApi.Kind
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            GradesContent(kind: kind)
                .background(T.bgPage)
                .navigationTitle(kind.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("关闭") { dismiss() }
                    }
                }
        }
    }
}

/// 列表本体：先显示本地快照，下拉才联网（「单次登录只加载一次」）。
struct GradesContent: View {
    let kind: JwApi.Kind

    @EnvironmentObject private var app: AppState
    @State private var data: FeatureData?
    @State private var loading = false
    @State private var failure: String?

    var body: some View {
        Group {
            if let data, data.ok {
                list(data)
            } else if loading {
                VStack(spacing: 10) {
                    ProgressView()
                    Text("正在读取教务…")
                        .font(F.sub)
                        .foregroundStyle(T.textTertiary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let message = failure ?? data?.message {
                ScrollView {
                    VStack(spacing: 12) {
                        EmptyCard(title: "没能读到数据", subtitle: message)
                        Button {
                            Task { await load(force: true) }
                        } label: {
                            Text("重试")
                                .font(F.bodyBold)
                                .foregroundStyle(.white)
                                .padding(.horizontal, 22)
                                .frame(height: 42)
                                .background(T.brand)
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(16)
                }
            } else {
                Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: kind) { await load(force: false) }
        .refreshable { await load(force: true) }
    }

    private func list(_ d: FeatureData) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !d.headline.isEmpty {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(d.headline)
                            .font(.system(size: 30, weight: .bold))
                            .foregroundStyle(T.brand)
                        Text(kind == .grades ? "已出成绩" : "平均学分绩点")
                            .font(F.sub)
                            .foregroundStyle(T.textTertiary)
                        Spacer()
                    }
                    .padding(.horizontal, 4)
                    .padding(.bottom, 12)
                }

                VStack(spacing: 0) {
                    ForEach(Array(d.rows.enumerated()), id: \.element.id) { index, row in
                        rowView(row)
                        if index < d.rows.count - 1 {
                            Rectangle().fill(T.border).frame(height: 0.6).padding(.leading, 16)
                        }
                    }
                }
                .background(T.bgSurface)
                .clipShape(RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: T.cardRadius, style: .continuous)
                        .stroke(T.border, lineWidth: 0.8)
                )

                if !d.note.isEmpty {
                    Text(d.note)
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                        .padding(.horizontal, 4)
                        .padding(.top, 12)
                }
            }
            .padding(.horizontal, T.pagePadding)
            .padding(.bottom, 24)
        }
    }

    private func rowView(_ row: FeatureRow) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(row.title)
                    .font(F.bodyBold)
                    .foregroundStyle(T.textPrimary)
                if !row.sub.isEmpty {
                    Text(row.sub)
                        .font(F.caption)
                        .foregroundStyle(T.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(row.value)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(scoreColor(row.value))
                if !row.extra.isEmpty {
                    Text("绩点 \(row.extra)")
                        .font(.system(size: 10))
                        .foregroundStyle(T.textTertiary)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }

    /// 60 分以下用红色提示（国内习惯：不及格标红）
    private func scoreColor(_ value: String) -> Color {
        guard let score = Double(value) else { return T.textPrimary }
        return score < 60 ? Color(hex: 0xD93A3A) : T.textPrimary
    }

    private func load(force: Bool) async {
        guard !loading else { return }
        guard app.loggedIn || !app.store.user().isEmpty else {
            failure = "先在「我的」里登录教务，再来看\(kind.title)"
            return
        }
        loading = true
        failure = nil
        let result: FeatureData
        if kind == .grades {
            result = await app.api.grades(force: force)
        } else {
            result = await app.api.gpa(force: force)
        }
        data = result
        if !result.ok { failure = result.message }
        loading = false
    }
}
