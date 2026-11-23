import SwiftUI
import Shared

/// 首站发车时刻表。数据来源可能是「今日预测 / 昨日实际 / 前日实际」，
/// 标题必须写清是哪一种 —— 三者混着看会误判班次。
struct BusTimetableView: View {
    let hit: BusLineHit

    @State private var tt: BusTimetable?
    @State private var status = "加载中…"

    var body: some View {
        List {
            Section { Text(status).font(.caption).foregroundColor(.secondary) }

            if let t = tt {
                Section {
                    HStack {
                        if !t.firstDep.isEmpty { Text("首班 \(t.firstDep)") }
                        Spacer()
                        if !t.lastDep.isEmpty { Text("末班 \(t.lastDep)") }
                    }
                    .font(.subheadline)
                    if t.interval > 0 {
                        Text("高峰约 \(t.interval) 分钟一班").font(.caption).foregroundColor(.secondary)
                    }
                    if !t.tip.isEmpty {
                        Text(t.tip).font(.caption2).foregroundColor(.secondary)
                    }
                }

                if !t.today.isEmpty {
                    Section("今日预测") {
                        ForEach(Array(t.today.enumerated()), id: \.offset) { _, seg in
                            segRow(seg)
                        }
                    }
                }

                if !t.history.isEmpty {
                    Section(t.historyTitle.isEmpty ? "历史班次" : t.historyTitle) {
                        ForEach(Array(t.history.enumerated()), id: \.offset) { _, seg in
                            segRow(seg)
                        }
                    }
                }
            }
        }
        .navigationTitle("\(hit.name) · 发车时刻")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func segRow(_ seg: BusTimetableSegment) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("\(seg.fromTime) - \(seg.toTime)").font(.caption).foregroundColor(.secondary)
                Spacer()
                Text("\(seg.times.count) 班").font(.caption2).foregroundColor(.secondary)
            }
            Text(seg.times.joined(separator: "  "))
                .font(.system(size: 12, design: .monospaced))
        }
    }

    private func load() async {
        let url = ChelaileApi.shared.timetableUrl(
            cityId: BusPrefs.cityId, lineId: hit.lineId,
            lineNo: hit.no, direction: Int32(directionOf(hit)))
        do {
            let body = try await BusFetch.text(url)
            let t = ChelaileParseKt.parseTimetable(body: body)
            tt = t
            status = "今日 \(t.today.count) 段 · 历史 \(t.history.count) 段"
        } catch {
            status = "失败：\(error.localizedDescription)"
        }
    }

    private func directionOf(_ hit: BusLineHit) -> Int {
        // 搜索结果里没有 direction 字段，先按 0（上行）；下行线路的 lineId 不同，
        // 时刻表按 lineId 查，direction 只影响上游的班次表选择。
        0
    }
}
