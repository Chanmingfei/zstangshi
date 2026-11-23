import SwiftUI
import Shared

/// 搜索结果页 —— 按安卓版对齐：站点组（只列最近 3 个 + 提示）+ 线路组（带线路号徽标）。
struct BusSearchView: View {
    let keyword: String

    @State private var result: BusSearchResult?
    @State private var status = "搜索中…"

    var body: some View {
        List {
            Section { Text(status).font(.caption).foregroundColor(.secondary) }

            if let r = result {
                if !r.stations.isEmpty {
                    Section("站点") {
                        ForEach(Array(r.stations.prefix(3)), id: \.id) { st in
                            NavigationLink {
                                BusStopDetailView(station: st)
                            } label: {
                                HStack(spacing: 10) {
                                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                                        .fill(Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.12))
                                        .frame(width: 34, height: 34)
                                        .overlay(Image(systemName: "bus.fill")
                                            .font(.system(size: 14))
                                            .foregroundColor(Color(red: 0.0, green: 0.48, blue: 1.0)))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(st.name).font(.headline)
                                        Text(stationSubtitle(st))
                                            .font(.caption2).foregroundColor(.secondary)
                                    }
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }
                    if r.stationCount > r.stations.count {
                        Text("共 \(r.stationCount) 个站点命中，这里只列最接近的 3 个 · 站名写全一些能找得更准")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                }

                if !r.lines.isEmpty {
                    Section("线路 · 共 \(r.lineCount) 条") {
                        ForEach(r.lines, id: \.lineId) { hit in
                            NavigationLink {
                                BusLineView(hit: hit)
                            } label: {
                                HStack(spacing: 10) {
                                    Text(badge(hit))
                                        .font(.system(size: 11, weight: .semibold))
                                        .foregroundColor(Color(red: 0.0, green: 0.48, blue: 1.0))
                                        .frame(width: 44, height: 30)
                                        .background(Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.10))
                                        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(hit.name).font(.headline)
                                        Text("\(hit.startSn) → \(hit.endSn)")
                                            .font(.caption2).foregroundColor(.secondary)
                                    }
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("实时公交")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func stationSubtitle(_ st: BusStation) -> String {
        if st.distanceMeters > 0 {
            return String(format: "公交站点 · 距你 %.1f km", Double(st.distanceMeters) / 1000.0)
        }
        return "公交站点"
    }

    private func badge(_ hit: BusLineHit) -> String {
        if !hit.no.isEmpty { return hit.no }
        return hit.name.replacingOccurrences(of: "路", with: "")
    }

    private func load() async {
        guard !keyword.isEmpty else {
            status = "输入线路或站点名再搜"
            return
        }
        if BusSourceStore.current == .mygolbs {
            await loadMygolbs()
            return
        }
        let url = ChelaileApi.shared.searchUrl(cityId: BusPrefs.cityId, keyword: keyword)
        do {
            let body = try await BusFetch.text(url)
            let r = BusSearchKt.parseSearchResult(body: body)
            result = r
            status = "线路 \(r.lineCount) 条 · 站点 \(r.stationCount) 个"
        } catch {
            status = "失败：\(error.localizedDescription)"
        }
    }

    /// 掌上公交：114 搜线路 + 110 搜站点，合并成一个结果
    private func loadMygolbs() async {
        let city = Mygolbs.shared.cityName(cityId: BusPrefs.cityId)
        let headers = ["User-Agent": Mygolbs.shared.UA,
                       "Origin": Mygolbs.shared.ORIGIN,
                       "Referer": Mygolbs.shared.REFERER]
        do {
            let linesBody = try await BusFetch.postForm(
                Mygolbs.shared.API,
                form: MygolbsSearchKt.mygolbsSearchLinesForm(cityName: city, keyword: keyword),
                headers: headers)
            let stopsBody = (try? await BusFetch.postForm(
                Mygolbs.shared.API,
                form: MygolbsSearchKt.mygolbsSearchStopsForm(cityName: city, keyword: keyword),
                headers: headers)) ?? "{}"
            let r = MygolbsSearchKt.composeMygolbsSearchResult(
                cityName: city, linesBody: linesBody, stopsBody: stopsBody)
            result = r
            status = "线路 \(r.lineCount) 条 · 站点 \(r.stationCount) 个"
        } catch {
            status = "失败：\(error.localizedDescription)"
        }
    }
}
