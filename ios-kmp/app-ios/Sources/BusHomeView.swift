import SwiftUI
import CoreLocation
import Shared

/// 实时公交首页 —— 按安卓 `BusActivity` 首页复刻：
/// 搜索卡片 → 城市行 → 附近站点卡片（站名/站台数/距离 + 线路行 + 展开全部）→ 乘车码悬浮按钮。
@MainActor
final class BusHomeModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var stations: [BusStation] = []
    @Published var status = "正在定位…"
    @Published var loading = false
    @Published var expanded: Set<String> = []
    @Published var source: BusSource = BusSourceStore.current

    private let manager = CLLocationManager()
    private(set) var coord: CLLocationCoordinate2D?
    private var loaded = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func start() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            status = "定位被拒绝，用默认位置"
            Task { await load(39.63, 118.18) }
        default:
            manager.requestLocation()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        Task { @MainActor in
            if m.authorizationStatus == .authorizedWhenInUse || m.authorizationStatus == .authorizedAlways {
                m.requestLocation()
            }
        }
    }

    nonisolated func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        guard let c = locs.last?.coordinate else { return }
        Task { @MainActor in
            coord = c
            await load(c.latitude, c.longitude)
        }
    }

    nonisolated func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            if coord == nil {
                status = "定位失败，用默认位置"
                await load(39.63, 118.18)
            }
        }
    }

    func switchSource(_ s: BusSource) {
        guard s != source else { return }
        source = s
        BusSourceStore.current = s
        // 两个源的城市标识不同，切换时重置
        BusPrefs.cityId = s.defaultCityId
        BusPrefs.cityName = s.defaultCityName
        stations = []
        expanded = []
        let c = coord ?? CLLocationCoordinate2D(latitude: 39.63, longitude: 118.18)
        Task { await load(c.latitude, c.longitude) }
    }

    func load(_ lat: Double, _ lng: Double) async {
        loading = true
        defer { loading = false }
        if source == .chelaile {
            await loadChelaile(lat, lng)
        } else {
            await loadMygolbs(lat, lng)
        }
    }

    private func loadChelaile(_ lat: Double, _ lng: Double) async {
        let url = ChelaileApi.shared.nearbyUrl(cityId: BusPrefs.cityId, lat: lat, lng: lng)
        do {
            let body = try await BusFetch.text(url)
            stations = ChelaileParseKt.parseNearby(body: body, limit: 15, userLat: lat, userLng: lng)
            status = "\(stations.count) 个站点"
        } catch {
            status = "失败：\(error.localizedDescription)"
        }
    }

    /// 掌上公交：先 106 探站，再对最近的几站补 105（安卓是 4 并发全量补）。
    private func loadMygolbs(_ lat: Double, _ lng: Double) async {
        let city = BusPrefs.cityId
        do {
            let body = try await BusFetch.postForm(
                Mygolbs.shared.API,
                form: Mygolbs.shared.nearbyForm(cityName: Mygolbs.shared.cityName(cityId: city),
                                                wgsLat: lat, wgsLng: lng),
                headers: mygolbsHeaders())
            let base = Mygolbs.shared.parseStops(
                body: body, cityName: Mygolbs.shared.cityName(cityId: city),
                userLat: lat, userLng: lng, limit: 15)
            stations = base
            status = "\(base.count) 个站点"
            // 只补最近 5 站，其余点进详情时再查（避免一次打十几个请求被掐）
            let head = Array(base.prefix(5))
            var filled: [BusStation] = []
            for st in head {
                filled.append(await stopLines(st))
            }
            stations = filled + base.dropFirst(head.count)
        } catch {
            status = "失败：\(error.localizedDescription)"
        }
    }

    func stopLines(_ st: BusStation) async -> BusStation {
        let city = Mygolbs.shared.cityName(cityId: BusPrefs.cityId)
        do {
            let body = try await BusFetch.postForm(
                Mygolbs.shared.API,
                form: Mygolbs.shared.stopLinesForm(cityName: city, stationName: st.name),
                headers: mygolbsHeaders())
            return Mygolbs.shared.parseStopLines(body: body, cityName: city, stationName: st.name)
        } catch {
            return st
        }
    }

    private func mygolbsHeaders() -> [String: String] {
        ["User-Agent": Mygolbs.shared.UA,
         "Origin": Mygolbs.shared.ORIGIN,
         "Referer": Mygolbs.shared.REFERER]
    }

    func toggleExpand(_ id: String) {
        if expanded.contains(id) { expanded.remove(id) } else { expanded.insert(id) }
    }
}

struct BusHomeView: View {
    @StateObject private var model = BusHomeModel()

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            List {
                searchCard
                cityRow
                if !BusPrefs.favorites.isEmpty { favoritesSection }

                Section {
                    ForEach(model.stations, id: \.id) { st in
                        StationCard(station: st,
                                    expanded: model.expanded.contains(st.id),
                                    onExpand: { model.toggleExpand(st.id) },
                                    onTapHeader: {})
                    }
                } header: {
                    Text("附近站点").font(.footnote).foregroundColor(.secondary)
                }
            }
            .listStyle(.plain)
            .navigationTitle("实时公交")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        ForEach(BusSource.allCases, id: \.rawValue) { s in
                            Button {
                                model.switchSource(s)
                            } label: {
                                if s == model.source { Label(s.label, systemImage: "checkmark") }
                                else { Text(s.label) }
                            }
                        }
                    } label: {
                        HStack(spacing: 2) {
                            Text(model.source.label)
                            Image(systemName: "chevron.down").font(.caption2)
                        }
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("刷新") {
                        let c = model.coord ?? CLLocationCoordinate2D(latitude: 39.63, longitude: 118.18)
                        Task { await model.load(c.latitude, c.longitude) }
                    }
                }
            }
            .refreshable {
                let c = model.coord ?? CLLocationCoordinate2D(latitude: 39.63, longitude: 118.18)
                await model.load(c.latitude, c.longitude)
            }
            .task {
                model.start()
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 20 * 1_000_000_000)
                    if let c = model.coord { await model.load(c.latitude, c.longitude) }
                }
            }

            NavigationLink {
                TransitCodeView()
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "qrcode")
                    Text("乘车码")
                }
                .font(.subheadline).bold()
                .padding(.horizontal, 18).padding(.vertical, 12)
                .background(Color(red: 0.0, green: 0.48, blue: 1.0))
                .foregroundColor(.white)
                .clipShape(Capsule())
                .shadow(radius: 6, y: 3)
            }
            .padding(.trailing, 18)
            .padding(.bottom, 24)
        }
    }

    private var searchCard: some View {
        HStack(spacing: 10) {
            NavigationLink {
                BusSearchView()
            } label: {
                HStack {
                    Text("搜索线路、站点").foregroundColor(.secondary)
                    Spacer()
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(Color(UIColor.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            NavigationLink {
                BusSearchView()
            } label: {
                Text("搜索")
                    .font(.subheadline).bold()
                    .padding(.horizontal, 18).padding(.vertical, 10)
                    .background(Color(red: 0.0, green: 0.48, blue: 1.0))
                    .foregroundColor(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
        }
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
    }

    private var cityRow: some View {
        HStack(spacing: 6) {
            Circle().fill(Color.green).frame(width: 8, height: 8)
            Text("\(BusPrefs.cityName) · 实时公交").font(.footnote)
            Spacer()
            NavigationLink("切换城市") { BusCityPickerView() }.font(.footnote)
        }
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 8, trailing: 16))
    }

    private var favoritesSection: some View {
        Section {
            ForEach(BusPrefs.favorites, id: \.id) { fav in
                NavigationLink {
                    BusLineView(hit: BusLineHit(lineId: fav.id, name: fav.name,
                                                no: "", startSn: "", endSn: ""))
                } label: {
                    HStack {
                        Image(systemName: "star.fill").font(.caption).foregroundColor(.orange)
                        Text(fav.name)
                    }
                }
            }
        } header: {
            Text("收藏线路").font(.footnote)
        }
    }
}

/// 一张站点卡片：头部（图标/站名/站台数/距离）+ 线路行 + 展开全部。
struct StationCard: View {
    let station: BusStation
    let expanded: Bool
    let onExpand: () -> Void
    let onTapHeader: () -> Void

    private var lines: [BusArrival] {
        expanded ? station.arrivals : Array(station.arrivals.prefix(3))
    }

    var body: some View {
        VStack(spacing: 0) {
            NavigationLink {
                BusStopDetailView(station: station)
            } label: {
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Color(red: 0.0, green: 0.48, blue: 1.0).opacity(0.12))
                        .frame(width: 34, height: 34)
                        .overlay(Image(systemName: "bus.fill")
                            .font(.system(size: 15))
                            .foregroundColor(Color(red: 0.0, green: 0.48, blue: 1.0)))
                    Text(station.name).font(.headline)
                    Text(subtitle).font(.caption).foregroundColor(.secondary)
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption2).foregroundColor(.secondary)
                }
                .padding(.vertical, 12)
            }
            .buttonStyle(.plain)

            ForEach(lines, id: \.lineId) { a in
                Divider()
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(a.lineName).font(.subheadline)
                        Text(a.endName.isEmpty ? "" : "开往 \(a.endName)")
                            .font(.caption2).foregroundColor(.secondary)
                    }
                    Spacer()
                    Text(BusText.shared.rowPrimaryText(
                        minutes: a.minutes, stopsAway: a.stopsAway, arrived: a.arrived,
                        status: a.status, realtime: a.realtime, pending: a.pending))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                .padding(.vertical, 10)
            }

            if station.arrivals.count > 3 {
                Divider()
                Button(action: onExpand) {
                    Text(expanded ? "收起" : "展开全部")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
    }

    private var subtitle: String {
        // 安卓：「4 个站台 127 m」——站台数来自 namesakeStSize，不是线路数
        var bits: [String] = []
        if station.platformCount > 0 { bits.append("\(station.platformCount) 个站台") }
        if station.distanceMeters > 0 { bits.append("\(station.distanceMeters) m") }
        return bits.joined(separator: " ")
    }
}
