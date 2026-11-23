import SwiftUI
import Shared

/// 线路详情：站表 + 在途车辆。
struct BusLineView: View {
    let hit: BusLineHit
    @State private var detail: BusLineDetail?
    @State private var status = "加载中…"

    var body: some View {
        List {
            Section { Text(status).font(.caption).foregroundColor(.secondary) }

            if let d = detail {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(d.line.name).font(.title3).bold()
                        Text("\(d.line.startName) → \(d.line.endName)")
                            .font(.caption).foregroundColor(.secondary)
                        HStack(spacing: 12) {
                            if !d.line.firstTime.isEmpty {
                                Text("首班 \(d.line.firstTime)").font(.caption2)
                            }
                            if !d.line.lastTime.isEmpty {
                                Text("末班 \(d.line.lastTime)").font(.caption2)
                            }
                            if !d.line.price.isEmpty {
                                Text("票价 \(d.line.price)").font(.caption2)
                            }
                        }
                        if !d.status.isEmpty {
                            Text(d.status).font(.caption).foregroundColor(.orange)
                        }
                    }
                }

                if !d.vehicles.isEmpty {
                    Section("在途 \(d.vehicles.count) 辆") {
                        ForEach(d.vehicles, id: \.id) { v in
                            HStack {
                                Text(v.plate.isEmpty ? v.id : v.plate).font(.subheadline)
                                Spacer()
                                Text("第 \(v.order) 站").font(.caption)
                                if v.speedKmh > 0 {
                                    Text("\(Int(v.speedKmh)) km/h")
                                        .font(.caption2).foregroundColor(.secondary)
                                }
                            }
                        }
                    }
                }

                Section("站表 \(d.stops.count) 站") {
                    ForEach(d.stops, id: \.id) { s in
                        HStack {
                            Text("\(s.order)").font(.caption).foregroundColor(.secondary)
                                .frame(width: 28, alignment: .leading)
                            Text(s.name).font(.subheadline)
                            Spacer()
                            // 车辆停在/开向哪一站：直接标在站表上
                            if d.vehicles.contains(where: { Int($0.order) == Int(s.order) }) {
                                Text("🚌").font(.caption)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(hit.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        let url = ChelaileApi.shared.lineDetailUrl(cityId: "056", lineId: hit.lineId,
                                                  lat: 39.63, lng: 118.18)
        do {
            let body = try await BusFetch.text(url)
            let d = ChelaileParseKt.parseLineDetail(body: body, lineId: hit.lineId)
            detail = d
            status = "HTTP 200 · \(d.stops.count) 站 · \(d.vehicles.count) 辆在途"
        } catch {
            status = "失败：\(error.localizedDescription)"
        }
    }
}
