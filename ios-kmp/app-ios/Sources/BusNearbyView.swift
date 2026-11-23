import SwiftUI
import CoreLocation
import Shared

/// 附近站点：定位 → 拉 nearby → 列出站点与经过线路的到站。
@MainActor
final class NearbyModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var stations: [BusStation] = []
    @Published var status: String = "正在定位…"
    @Published var loading = false

    private let manager = CLLocationManager()
    private var coord: CLLocationCoordinate2D?

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
            status = "定位权限被拒绝，请到系统设置里打开"
        default:
            manager.requestLocation()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        Task { @MainActor in
            if m.authorizationStatus == .authorizedWhenInUse || m.authorizationStatus == .authorizedAlways {
                m.requestLocation()
            } else if m.authorizationStatus == .denied {
                status = "定位权限被拒绝，请到系统设置里打开"
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
            // 拿不到定位就退回学校坐标，至少能用
            if coord == nil {
                await load(39.63, 118.18)
                status += "（用默认位置：唐山市中心）"
            }
        }
    }

    func load(_ lat: Double, _ lng: Double) async {
        loading = true
        defer { loading = false }
        let url = ChelaileApi.shared.nearbyUrl(cityId: "056", lat: lat, lng: lng)
        do {
            let body = try await BusFetch.text(url)
            // 顶层函数挂在 <文件名>Kt 上：ChelaileParse.kt → ChelaileParseKt
            let list = ChelaileParseKt.parseNearby(body: body, limit: 15, userLat: lat, userLng: lng)
            stations = list
            status = "\(list.count) 个站点 · \(body.count) 字节"
        } catch {
            status = "失败：\(error.localizedDescription)"
        }
    }
}

struct BusNearbyView: View {
    @StateObject private var model = NearbyModel()

    var body: some View {
        List {
            Section { Text(model.status).font(.caption).foregroundColor(.secondary) }

            ForEach(model.stations, id: \.id) { st in
                Section {
                    ForEach(st.arrivals, id: \.lineId) { a in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(a.lineName).font(.subheadline).bold()
                                Text("\(a.startName) → \(a.endName)")
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                Text(BusText.shared.rowPrimaryText(
                                    minutes: a.minutes, stopsAway: a.stopsAway,
                                    arrived: a.arrived, status: a.status,
                                    realtime: a.realtime, pending: a.pending))
                                    .font(.subheadline).bold()
                                    .foregroundColor(Color(red: 0.46, green: 0.08, blue: 0.10))
                                Text(BusText.shared.rowDetailText(
                                    stopsAway: a.stopsAway, minutes: a.minutes,
                                    arrived: a.arrived, nextMinutes: a.nextMinutes,
                                    nextStopsAway: a.nextStopsAway))
                                    .font(.caption2).foregroundColor(.secondary)
                            }
                        }
                    }
                } header: {
                    HStack {
                        Text(st.name)
                        Spacer()
                        if st.distanceMeters > 0 {
                            Text("\(st.distanceMeters) m").font(.caption2)
                        }
                    }
                }
            }
        }
        .navigationTitle("附近站点")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { model.start() }
        .onAppear { model.start() }
    }
}
