import SwiftUI
import Shared

/// 站点详情：同名站台切换 + 20 秒轮询。
///
/// 轮询用 `.task` + `Task.sleep` 而不是 Timer：页面消失时任务自动取消，
/// 不会出现「退出页面还在打接口」这种耗电又耗流量的问题。
struct BusStopDetailView: View {
    let station: BusStation

    @State private var platforms: [BusStation] = []
    @State private var index: Int = 0
    @State private var status = "加载中…"
    @State private var lastUpdate = Date()

    var body: some View {
        List {
            if platforms.count > 1 {
                Section {
                    Picker("站台", selection: $index) {
                        ForEach(Array(platforms.enumerated()), id: \.offset) { i, st in
                            Text("站台 \(i + 1)").tag(i)
                        }
                    }
                    .pickerStyle(.segmented)
                }
            }

            Section {
                HStack {
                    Text(status).font(.caption).foregroundColor(.secondary)
                    Spacer()
                    Text("20 秒自动刷新").font(.caption2).foregroundColor(.secondary)
                }
            }

            if let cur = platforms.indices.contains(index) ? platforms[index] : nil {
                Section("\(cur.name) · \(cur.arrivals.count) 条线路") {
                    ForEach(cur.arrivals, id: \.lineId) { a in
                        row(a)
                    }
                }
            }
        }
        .navigationTitle(station.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await poll() }
    }

    private func row(_ a: BusArrival) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(a.lineName).font(.subheadline).bold()
                Text("\(a.startName) → \(a.endName)")
                    .font(.caption2).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(BusText.shared.rowPrimaryText(
                    minutes: a.minutes, stopsAway: a.stopsAway, arrived: a.arrived,
                    status: a.status, realtime: a.realtime, pending: a.pending))
                    .font(.subheadline).bold()
                    .foregroundColor(Color(red: 0.46, green: 0.08, blue: 0.10))
                Text(BusText.shared.rowDetailText(
                    stopsAway: a.stopsAway, minutes: a.minutes, arrived: a.arrived,
                    nextMinutes: a.nextMinutes, nextStopsAway: a.nextStopsAway))
                    .font(.caption2).foregroundColor(.secondary)
            }
        }
    }

    private func poll() async {
        while !Task.isCancelled {
            await load()
            try? await Task.sleep(nanoseconds: 20 * 1_000_000_000)
        }
    }

    private func load() async {
        // 掌上公交的站点 id 带 `mg|` 前缀，且只有 105 一个接口（站点名即主键）
        if station.id.hasPrefix("mg|") {
            let city = Mygolbs.shared.cityName(cityId: BusPrefs.cityId)
            do {
                let body = try await BusFetch.postForm(
                    Mygolbs.shared.API,
                    form: Mygolbs.shared.stopLinesForm(cityName: city, stationName: station.name),
                    headers: ["User-Agent": Mygolbs.shared.UA,
                              "Origin": Mygolbs.shared.ORIGIN,
                              "Referer": Mygolbs.shared.REFERER])
                let st = Mygolbs.shared.parseStopLines(body: body, cityName: city, stationName: station.name)
                platforms = [st]
                if index >= platforms.count { index = 0 }
                let f = DateFormatter()
                f.dateFormat = "HH:mm:ss"
                status = "\(st.arrivals.count) 条线路 · 更新于 \(f.string(from: Date()))"
            } catch {
                status = "刷新失败：\(error.localizedDescription)"
            }
            return
        }

        let url = ChelaileApi.shared.stopDetailUrl(
            cityId: BusPrefs.cityId,
            physicalId: station.physicalId,
            namesakeId: station.namesakeId,
            stationId: station.id,
            lat: 0, lng: 0)
        do {
            let body = try await BusFetch.text(url)
            let list = ChelaileParseKt.parseStopDetail(body: body, userLat: 0, userLng: 0)
            platforms = list
            if index >= list.count { index = 0 }
            let f = DateFormatter()
            f.dateFormat = "HH:mm:ss"
            status = "\(list.count) 个站台 · 更新于 \(f.string(from: Date()))"
            lastUpdate = Date()
        } catch {
            status = "刷新失败：\(error.localizedDescription)"
        }
    }
}
