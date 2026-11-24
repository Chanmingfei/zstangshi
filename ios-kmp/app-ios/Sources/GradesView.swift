import SwiftUI
import Shared

/// 成绩页：概要（门数 / 总学分 / 加权平均 / 平均绩点）+ 明细。
struct GradesView: View {
    @State private var result: GradeResult?
    @State private var status = "正在查询…"
    @State private var picked = 0

    /// 从成绩行抽学期去重排序，第一项是「全部学期」（对齐安卓 semesterFilter）。
    private var terms: [String] {
        guard let r = result, r.ok else { return [] }
        var uniq: [String] = []
        for g in r.rows where !g.term.isEmpty && !uniq.contains(g.term) { uniq.append(g.term) }
        uniq.sort()
        return ["全部学期"] + uniq
    }

    private var shownRows: [GradeRow] {
        guard let r = result, r.ok else { return [] }
        if picked == 0 || picked >= terms.count { return Array(r.rows) }
        let t = terms[picked]
        return r.rows.filter { $0.term == t }
    }

    var body: some View {
        List {
            if let r = result, r.ok {
                Section {
                    HStack(spacing: 0) {
                        stat("\(shownRows.count)", "门")
                        stat(String(format: "%.1f", shownRows.reduce(0.0) { $0 + (Double($1.credit) ?? 0) }), "学分")
                        stat(picked == 0 ? String(format: "%.1f", r.weightedAverage()) : "—", "加权分")
                        stat(picked == 0 ? String(format: "%.2f", r.weightedPoint()) : "—", "绩点")
                    }
                    .padding(.vertical, 6)
                }
                if terms.count > 1 {
                    Section {
                        Menu {
                            ForEach(0..<terms.count, id: \.self) { i in
                                Button(terms[i]) { picked = i }
                            }
                        } label: {
                            HStack {
                                Text("学期筛选").foregroundColor(.primary)
                                Spacer()
                                Text(terms[min(picked, terms.count - 1)])
                                    .foregroundColor(.secondary)
                                Image(systemName: "chevron.up.chevron.down")
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                        }
                    } footer: {
                        Text("只看某一学期的成绩；默认显示全部")
                    }
                }
                Section("明细") {
                    ForEach(Array(shownRows.enumerated()), id: \.offset) { _, g in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(g.name).font(.subheadline)
                                Text(sub(g)).font(.caption2).foregroundColor(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
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
        // 安卓在副标题末尾换行追加「平时 x · 期末 y」分项，这里一致处理。
        let detail = g.detail
        if !detail.isEmpty {
            return bits.joined(separator: " · ") + "\n" + detail
        }
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
