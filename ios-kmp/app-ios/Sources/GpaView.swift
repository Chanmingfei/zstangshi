import SwiftUI
import Shared

/// 学分绩点：总览 + 按学期分组。数据复用成绩接口，不额外请求。
struct GpaView: View {
    @State private var result: GradeResult?
    @State private var status = "正在计算…"

    var body: some View {
        List {
            if let r = result, r.ok {
                Section {
                    VStack(spacing: 10) {
                        Text(String(format: "%.2f", r.weightedPoint()))
                            .font(.system(size: 40, weight: .semibold))
                            .foregroundColor(Color(red: 0.46, green: 0.08, blue: 0.10))
                        Text("平均学分绩点").font(.caption).foregroundColor(.secondary)
                        HStack(spacing: 0) {
                            stat(String(format: "%.1f", r.totalCredits()), "总学分")
                            stat("\(r.rows.count)", "门数")
                            stat(String(format: "%.1f", r.weightedAverage()), "加权平均分")
                        }
                        .padding(.top, 4)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                }

                ForEach(Array(terms(r).enumerated()), id: \.offset) { pair in
                    Section(pair.element.term.isEmpty ? "未分学期" : pair.element.term) {
                        HStack {
                            Text("\(pair.element.count) 门 · \(String(format: "%.1f", pair.element.credits)) 学分")
                                .font(.caption).foregroundColor(.secondary)
                            Spacer()
                            Text(String(format: "绩点 %.2f", pair.element.point))
                                .font(.caption).foregroundColor(.secondary)
                        }
                        ForEach(Array(pair.element.rows.enumerated()), id: \.offset) { p in
                            HStack {
                                Text(p.element.name).font(.subheadline)
                                Spacer()
                                Text(p.element.score.isEmpty ? "—" : p.element.score)
                                    .font(.subheadline)
                                Text(p.element.point.isEmpty ? "" : "· \(p.element.point)")
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                        }
                    }
                }
            } else {
                Section { Text(status).font(.footnote).foregroundColor(.secondary) }
            }
        }
        .navigationTitle("学分绩点")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private struct TermGroup {
        var term: String
        var rows: [GradeRow]
        var credits: Double
        var point: Double
        var count: Int { rows.count }
    }

    private func terms(_ r: GradeResult) -> [TermGroup] {
        var order: [String] = []
        var map: [String: [GradeRow]] = [:]
        for row in r.rows {
            let k = row.term
            if map[k] == nil { order.append(k); map[k] = [] }
            map[k]?.append(row)
        }
        return order.map { k in
            let rows = map[k] ?? []
            var cred = 0.0, sum = 0.0, w = 0.0
            for x in rows {
                let c = Double(x.credit) ?? 0
                cred += c
                if let p = Double(x.point) { sum += p * max(c, 1); w += max(c, 1) }
            }
            return TermGroup(term: k, rows: rows, credits: cred, point: w > 0 ? sum / w : 0)
        }
    }

    private func stat(_ v: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(v).font(.system(size: 16, weight: .semibold))
            Text(label).font(.caption2).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
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
