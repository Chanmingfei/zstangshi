import SwiftUI
import Shared

/// 成绩页：概要（门数 / 总学分 / 加权平均 / 平均绩点）+ 明细。
struct GradesView: View {
    @State private var result: GradeResult?
    @State private var status = "正在查询…"

    var body: some View {
        List {
            if let r = result, r.ok {
                Section {
                    HStack(spacing: 0) {
                        stat("\(r.rows.count)", "门")
                        stat(String(format: "%.1f", r.totalCredits()), "学分")
                        stat(String(format: "%.1f", r.weightedAverage()), "加权分")
                        stat(String(format: "%.2f", r.weightedPoint()), "绩点")
                    }
                    .padding(.vertical, 6)
                }
                Section("明细") {
                    ForEach(Array(r.rows.enumerated()), id: \.offset) { _, g in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(g.name).font(.subheadline)
                                Text(sub(g)).font(.caption2).foregroundColor(.secondary)
                            }
                            Spacer()
                            Text(g.score.isEmpty ? "—" : g.score)
                                .font(.headline)
                                .foregroundColor(scoreColor(g.score))
                        }
                        .padding(.vertical, 2)
                    }
                }
            } else {
                Section { Text(status).font(.footnote).foregroundColor(.secondary) }
            }
        }
        .navigationTitle("成绩")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.system(size: 18, weight: .semibold))
            Text(label).font(.caption2).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func sub(_ g: GradeRow) -> String {
        var bits: [String] = []
        if !g.term.isEmpty { bits.append(g.term) }
        if !g.credit.isEmpty { bits.append("\(g.credit) 学分") }
        if !g.kind.isEmpty { bits.append(g.kind) }
        return bits.joined(separator: " · ")
    }

    private func scoreColor(_ s: String) -> Color {
        guard let v = Double(s) else { return .primary }
        if v >= 90 { return Color(red: 0.75, green: 0.10, blue: 0.12) }
        if v >= 60 { return .primary }
        return Color(red: 0.10, green: 0.45, blue: 0.22)
    }

    private func load() async {
        guard let r = await JwAuth.shared.fetchGrades() else {
            status = JwAuth.shared.message.isEmpty ? "查询失败" : JwAuth.shared.message
            return
        }
        result = r
        if !r.ok { status = r.message }
    }
}
