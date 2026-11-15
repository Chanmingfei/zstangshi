import SwiftUI
import CoreLocation

/// 实时公交的状态机。
///
/// 职责：定位 → 查附近站点 → 进站点/线路详情 → 轮询到站。
/// 数据源（车来了 / 掌上公交）与城市可切换，选择持久化。
@MainActor
final class BusViewModel: ObservableObject {

    // MARK: - 发布状态

    @Published private(set) var source: BusSource
    @Published private(set) var cityId: String
    @Published private(set) var cityName: String

    @Published private(set) var favorites: [FavoriteLine] = []
    @Published private(set) var nearby: [BusStation] = []
    @Published private(set) var nearbyError: String?
    @Published private(set) var loadingNearby = false
    @Published private(set) var hasMoreNearby = false

    /// 当前位置（用于各种接口的 lat/lng）
    @Published private(set) var coordinate: CLLocationCoordinate2D?
    @Published private(set) var locationDenied = false
    @Published private(set) var locating = false

    @Published var error: String?

    // MARK: - 详情状态

    @Published private(set) var lineDetail: BusLineDetail?
    @Published private(set) var stationDetail: BusStation?
    @Published private(set) var busDetail = BusDetailResult()
    @Published private(set) var loadingDetail = false
    @Published private(set) var detailError: String?

    // MARK: - 依赖

    private let store = BusStore()
    private let location = BusLocation()
    private var gateway: BusGateway
    private var ring = 0
    private var pollTask: Task<Void, Never>?

    /// 当前打开的是哪条线（轮询用）
    private var activeLineId: String?
    private var activeTargetOrder = 0
    private var activeStationId: String?

    init() {
        let s = BusStore().source
        source = s
        cityId = BusStore().cityId(for: s)
        cityName = BusStore().cityName(for: s)
        gateway = BusGatewayFactory.make(s, cityId: BusStore().cityId(for: s))
        favorites = BusStore().favorites(for: s)
    }

    // MARK: - 启动

    /// 进入公交页时调用：定位 + 拉附近站点 + 刷新收藏
    func bootstrap() async {
        reloadFavorites()
        await locateAndLoad()
    }

    /// 重新定位并加载附近站点
    func locateAndLoad() async {
        guard !loadingNearby else { return }
        locating = true
        defer { locating = false }

        do {
            let c = try await location.requestOnce()
            coordinate = c
            locationDenied = false
            nearbyError = nil
            // 拿到坐标后立刻查附近站点
            await loadNearby(reset: true)
        } catch {
            locationDenied = location.denied
            nearbyError = (error as? LocalizedError)?.errorDescription
                ?? "定位失败，请检查定位权限"
        }
    }

    /// 只重新拉附近站点（不重新定位）
    func loadNearby(reset: Bool) async {
        guard !loadingNearby else { return }
        guard let c = coordinate else {
            await locateAndLoad()
            return
        }
        loadingNearby = true
        nearbyError = nil
        defer { loadingNearby = false }

        if reset { ring = 0 }
        ring += 1

        do {
            // 车来了：ring 用「8 方位 × ring×700 米」的方式扩大范围。
            // 第一圈直接查当前位置，后面几圈靠偏移点扩大（见 ChelaileClient 注释）。
            var collected: [BusStation] = []
            if ring == 1 {
                collected = try await gateway.nearby(lat: c.latitude, lng: c.longitude, limit: 15)
            } else {
                collected = await probeRing(center: c, ring: ring)
            }

            if reset {
                nearby = collected
            } else {
                // 去重追加（同一个站可能被多个探针点到）
                let existing = Set(nearby.map { $0.id })
                nearby.append(contentsOf: collected.filter { !existing.contains($0.id) })
            }
            hasMoreNearby = !collected.isEmpty && ring < 6
            if nearby.isEmpty {
                nearbyError = "附近 700 米内没有找到公交站，试试扩大范围"
            }
        } catch {
            nearbyError = (error as? LocalizedError)?.errorDescription
                ?? "获取附近站点失败"
            hasMoreNearby = false
        }
    }

    /// 以用户位置为中心，按 8 个方位把查询点挪到 ring×700 米处各查一次，合并去重。
    /// 这是安卓 `fartherStations` 的做法（上游没有翻页参数）。
    private func probeRing(center: CLLocationCoordinate2D, ring: Int) async -> [BusStation] {
        let radius = 700.0 * Double(max(1, ring))
        let dirs = 8
        var points: [(Double, Double)] = []
        for i in 0..<dirs {
            let bearing = Double(i) * 360.0 / Double(dirs)
            points.append(Self.offset(center.latitude, center.longitude,
                                      meters: radius, bearing: bearing))
        }

        return await withTaskGroup(of: [BusStation].self) { group in
            for p in points {
                group.addTask { [gateway] in
                    (try? await gateway.nearby(lat: p.0, lng: p.1, limit: 15)) ?? []
                }
            }
            var all: [BusStation] = []
            for await list in group {
                all.append(contentsOf: list)
            }
            // 按距离排序去重
            var seen = Set<String>()
            return all
                .sorted { a, b in
                    let da = a.distanceMeters < 0 ? Int.max : a.distanceMeters
                    let db = b.distanceMeters < 0 ? Int.max : b.distanceMeters
                    return da < db
                }
                .filter { seen.insert($0.id).inserted }
        }
    }

    /// 经纬度按方位偏移（球面近似，与安卓 offsetPoint 一致）
    private static func offset(_ lat: Double, _ lng: Double,
                               meters: Double, bearing: Double) -> (Double, Double) {
        let r = 6_371_000.0
        let dLat = meters * cos(bearing * .pi / 180) / r
        let dLng = meters * sin(bearing * .pi / 180) / (r * cos(lat * .pi / 180))
        return (lat + dLat * 180 / .pi, lng + dLng * 180 / .pi)
    }

    // MARK: - 搜索

    func search(_ keyword: String) async -> BusSearchHit {
        do {
            let c = coordinate
            return try await gateway.search(keyword,
                                            lat: c?.latitude ?? 0,
                                            lng: c?.longitude ?? 0)
        } catch {
            self.error = (error as? LocalizedError)?.errorDescription ?? "搜索失败"
            return BusSearchHit()
        }
    }

    // MARK: - 站点详情

    func openStation(_ station: BusStation) async {
        stationDetail = station
        detailError = nil
        loadingDetail = true
        defer { loadingDetail = false }
        do {
            let c = coordinate
            let full = try await gateway.stopDetail(station: station,
                                                    lat: c?.latitude ?? 0,
                                                    lng: c?.longitude ?? 0)
            if let d = stationDetail, d.id == station.id {
                stationDetail = full
            }
        } catch {
            detailError = (error as? LocalizedError)?.errorDescription
                ?? "站点详情加载失败"
        }
    }

    func closeStation() {
        stationDetail = nil
        detailError = nil
    }

    // MARK: - 线路详情 + 轮询

    func openLine(_ line: BusLine) async {
        activeLineId = line.lineId
        activeTargetOrder = store.targetOrder(for: line.lineId)
        activeStationId = nil
        lineDetail = nil
        busDetail = BusDetailResult()
        detailError = nil
        store.lastLineName = line.name
        await refreshLine()
        startPolling()
    }

    func closeLine() {
        pollTask?.cancel()
        pollTask = nil
        activeLineId = nil
        lineDetail = nil
        busDetail = BusDetailResult()
        detailError = nil
    }

    /// 切换候车站
    func setTargetOrder(_ order: Int) async {
        guard let lineId = activeLineId else { return }
        activeTargetOrder = order
        store.setTargetOrder(order, for: lineId)
        await refreshLine()
    }

    /// 拉线路详情 + 车辆预测
    func refreshLine() async {
        guard let lineId = activeLineId else { return }
        let c = coordinate
        let lat = c?.latitude ?? 0
        let lng = c?.longitude ?? 0

        do {
            // 1. 站表
            let d = try await gateway.lineDetail(lineId: lineId,
                                                 targetOrder: activeTargetOrder,
                                                 stationId: activeStationId,
                                                 lat: lat, lng: lng)

            // 候车站：优先用户记住的，其次上游给的 targetOrder，最后第一个站
            var target = activeTargetOrder
            if target == 0 { target = d.targetOrder }
            if target == 0 { target = d.stops.first?.order ?? 0 }
            if target == 0 && !d.stops.isEmpty { target = d.stops[0].order }
            activeTargetOrder = target

            var merged = d
            merged.targetOrder = target
            if activeStationId == nil {
                activeStationId = merged.targetStop?.sId
            }

            // 2. 车辆预测（回填「到候车站距离」与到站时间）
            if target > 0 {
                if let bd = try? await gateway.busDetail(lineId: lineId,
                                                         targetOrder: target,
                                                         stationId: merged.targetStop?.sId,
                                                         lat: lat, lng: lng) {
                    busDetail = bd
                    // 把每辆车的「到候车站距离」回填到车辆上
                    for i in merged.buses.indices {
                        if let eta = bd.byBus[merged.buses[i].busId] {
                            merged.buses[i].distanceToTarget = eta.distance
                        }
                    }
                }
            }

            lineDetail = merged
            detailError = nil
        } catch {
            if lineDetail == nil {
                detailError = (error as? LocalizedError)?.errorDescription
                    ?? "线路详情加载失败"
            }
        }
    }

    /// 20 秒轮询到站（与安卓一致）。离开页面必须调用 closeLine() 取消。
    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                guard !Task.isCancelled else { return }
                await self?.refreshLine()
            }
        }
    }

    /// 首站发车时刻表
    func loadTimetable() async -> [String] {
        guard let lineId = activeLineId, let d = lineDetail else { return [] }
        do {
            return try await gateway.timetable(lineId: lineId,
                                               lineNo: d.line.lineNo,
                                               direction: d.line.direction)
        } catch {
            return []
        }
    }

    // MARK: - 收藏

    func isFavorite(_ line: BusLine) -> Bool {
        store.isFavorite(line)
    }

    @discardableResult
    func toggleFavorite(_ line: BusLine) -> Bool {
        let result = store.toggleFavorite(line)
        reloadFavorites()
        Haptics.light()
        return result
    }

    private func reloadFavorites() {
        favorites = store.favorites(for: source)
    }

    // MARK: - 数据源 / 城市

    func changeSource(_ s: BusSource) async {
        guard s != source else { return }
        source = s
        store.source = s
        cityId = store.cityId(for: s)
        cityName = store.cityName(for: s)
        gateway = BusGatewayFactory.make(s, cityId: cityId)
        reloadFavorites()
        nearby = []
        ring = 0
        hasMoreNearby = false
        await locateAndLoad()
    }

    func changeCity(_ city: BusCity) async {
        cityId = city.id
        cityName = city.name
        store.setCityId(city.id, for: source)
        store.setCityName(city.name, for: source)
        gateway = BusGatewayFactory.make(source, cityId: city.id)
        nearby = []
        ring = 0
        await locateAndLoad()
    }

    func availableCities() async -> [BusCity] {
        (try? await gateway.cities()) ?? []
    }
}