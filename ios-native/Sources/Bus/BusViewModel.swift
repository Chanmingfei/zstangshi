import Foundation
import SwiftUI

// MARK: - 实时公交 · 页面状态机
//
// Android `BusActivity` 那个几千行的状态机（PANEL_STATION / PANEL_BUS_LIST /
// PANEL_BUS_FOLLOW + 结构键换站不跳动 + 20s 轮询）在 SwiftUI 里收成一个
// `ObservableObject`：页面只负责渲染，不持有任何业务状态。
//
// 数据真实性约定（与 Android v4 修补 4~9 完全一致）：
//   · 大字 / 小字 /「下辆」必须来自同一次预测，缺哪个字段就空着，绝不编数；
//   · 换站只重填列表，不重建整页视图树；
//   · 返回层级：单车跟随 → 全部车辆 → 站点卡，逐层退。

@MainActor
final class BusViewModel: ObservableObject {

    // MARK: 依赖

    let prefs: BusPreferences
    private let gateway: BusGateway
    let location = BusLocationService()

    // MARK: 首页

    @Published private(set) var stations: [BusStation] = []
    @Published private(set) var cities: [BusCity] = []
    @Published var city: BusCity
    @Published var source: BusSource
    @Published private(set) var favorites: [BusLine] = []
    @Published private(set) var loading = false
    @Published private(set) var refreshing = false
    @Published var errorText: String?
    @Published private(set) var lastRefresh: Date?

    // MARK: 搜索

    @Published var searchText = ""
    @Published private(set) var searchHit = BusSearchHit()
    @Published private(set) var searching = false
    @Published var showSearch = false

    // MARK: 站点 / 线路

    /// 当前站点（站点到站页面）
    @Published private(set) var station: BusStation?
    /// 当前线路详情（车辆列表 / 单车跟随共用同一份）
    @Published private(set) var detail: BusLineDetail?
    @Published private(set) var timetable: BusTimetable?
    @Published var panel: BusPanel = .station
    @Published var followedBusId = ""
    /// 行程流内嵌窗口的滚动位置：20s 重建时保留，对应 Android flowScrollY
    @Published var flowScrollY: CGFloat = 0
    /// 结构键：lineId # direction # stops.count。换站时只重填、不整页重建
    @Published private(set) var structureKey = ""
    @Published private(set) var selectedStopId = ""

    private var currentLineId = ""
    private var currentDirection = 0
    private var currentTargetOrder = 0
    private var refreshTask: Task<Void, Never>?

    // MARK: 乘车码

    @Published var transitChoice: TransitCodeChoice
    @Published var showTransitSheet = false

    // MARK: 初始化

    init(gateway: BusGateway? = nil, prefs: BusPreferences? = nil) {
        let p = prefs ?? BusStore()
        self.prefs = p
        self.gateway = gateway ?? BusApi(prefs: p)
        self.city = p.city()
        self.source = p.source()
        self.transitChoice = p.transitChoice()
        self.favorites = p.favorites()
    }

    private var coord: BusCoordinate { location.coord }

    // MARK: - 启动 / 刷新

    func start() {
        location.request()
        Task { await loadNearby() }
        Task { await loadCities() }
    }

    /// 下拉刷新 / 20s 轮询：只补预测，不整页 reload，避免列表跳动。
    func refresh() async {
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        do {
            if let s = station, !s.arrivals.isEmpty {
                let updated = try await gateway.refreshEta(cityId: city.id,
                                                           arrivals: s.arrivals,
                                                           at: coord)
                var next = s
                next.arrivals = updated
                station = next
            } else {
                stations = try await gateway.nearby(cityId: city.id, at: coord, limit: 12)
            }
            if !currentLineId.isEmpty {
                detail = try await gateway.lineDetail(cityId: city.id,
                                                     lineId: currentLineId,
                                                     targetOrder: currentTargetOrder,
                                                     at: coord)
                applyStructure()
            }
            lastRefresh = Date()
            errorText = nil
        } catch {
            errorText = (error as? BusError)?.errorDescription ?? "公交数据暂时取不到"
        }
    }

    private func loadNearby() async {
        loading = true
        defer { loading = false }
        do {
            stations = try await gateway.nearby(cityId: city.id, at: coord, limit: 12)
            lastRefresh = Date()
            errorText = nil
        } catch {
            errorText = (error as? BusError)?.errorDescription ?? "附近站点暂时取不到"
        }
    }

    private func loadCities() async {
        do { cities = try await gateway.cities() } catch { cities = [city] }
    }

    func reloadNearby() async { await loadNearby() }

    // MARK: - 站点

    func openStation(_ s: BusStation) async {
        panel = .station
        followedBusId = ""
        detail = nil
        timetable = nil
        currentLineId = ""
        station = s
        do {
            let full = try await gateway.stopDetail(cityId: city.id, station: s, at: coord)
            station = full
        } catch {
            errorText = (error as? BusError)?.errorDescription ?? "站点信息暂时取不到"
        }
    }

    // MARK: - 线路

    func openLine(lineId: String, targetOrder: Int = 0) async {
        currentLineId = lineId
        currentTargetOrder = targetOrder
        panel = .busList
        followedBusId = ""
        flowScrollY = 0
        do {
            detail = try await gateway.lineDetail(cityId: city.id,
                                                 lineId: lineId,
                                                 targetOrder: targetOrder,
                                                 at: coord)
            applyStructure()
            await loadTimetable()
        } catch {
            errorText = (error as? BusError)?.errorDescription ?? "线路信息暂时取不到"
        }
    }

    /// 换候车站：**只换数据，不重建视图树**。结构键不变时页面连滚动位置都不动。
    func switchWaitingStop(_ stop: BusStop) async {
        guard let d = detail else { return }
        guard stop.id != selectedStopId else { return }
        selectedStopId = stop.id
        do {
            let next = try await gateway.lineDetail(cityId: city.id,
                                                   lineId: d.line.lineId.isEmpty ? currentLineId : d.line.lineId,
                                                   targetOrder: stop.order,
                                                   at: coord)
            currentTargetOrder = stop.order
            detail = next
            applyStructure()
        } catch {
            errorText = (error as? BusError)?.errorDescription ?? "换站失败"
        }
    }

    func switchDirection() async {
        guard let d = detail, d.reversible else { return }
        await openLine(lineId: d.reverseId, targetOrder: 0)
    }

    private func applyStructure() {
        guard let d = detail else { return }
        currentDirection = d.line.direction
        structureKey = "\(d.line.lineId)#\(d.line.direction)#\(d.stops.count)"
        if selectedStopId.isEmpty { selectedStopId = d.waitingStopId }
    }

    // MARK: - 三态层级

    func openFollow(busId: String) {
        guard let d = detail, d.vehicles.contains(where: { $0.id == busId }) else { return }
        followedBusId = busId
        panel = .follow
        flowScrollY = 0
    }

    /// 返回一层。返回 false 表示已经到顶，交给 NavigationStack 的系统返回。
    func backOneLevel() -> Bool {
        switch panel {
        case .follow:
            panel = .busList
            followedBusId = ""
            return true
        case .busList:
            panel = .station
            followedBusId = ""
            detail = nil
            currentLineId = ""
            return true
        case .station:
            return false
        }
    }

    // MARK: - 车辆取值（页面只读这里，不许自己另挑一辆）

    func displayVehicle() -> BusVehicle? { detail?.displayVehicle() }
    func followingVehicle() -> BusVehicle? { detail?.followingVehicle() }

    func followedVehicle() -> BusVehicle? {
        detail?.vehicles.first { $0.id == followedBusId }
    }

    // MARK: - 时刻表

    func loadTimetable() async {
        guard let d = detail else { return }
        do {
            timetable = try await gateway.timetable(cityId: city.id,
                                                    line: d.line,
                                                    direction: d.line.direction)
        } catch {
            timetable = nil
        }
    }

    // MARK: - 搜索

    func search(_ keyword: String) async {
        let q = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { searchHit = BusSearchHit(); return }
        searching = true
        defer { searching = false }
        do {
            searchHit = try await gateway.search(cityId: city.id, keyword: q, at: coord)
        } catch {
            searchHit = BusSearchHit()
            errorText = (error as? BusError)?.errorDescription ?? "搜索失败"
        }
    }

    func searchMore() async {
        guard searchHit.linesTruncated else { return }
        do {
            let more = try await gateway.searchMore(cityId: city.id,
                                                    keyword: searchText,
                                                    at: coord)
            var hit = searchHit
            var seen = Set(hit.lines.map(\.lineId))
            for l in more where !seen.contains(l.lineId) {
                hit.lines.append(l)
                seen.insert(l.lineId)
            }
            searchHit = hit
        } catch { /* 保持已有结果 */ }
    }

    // MARK: - 收藏 / 设置

    func toggleFavorite(_ line: BusLine) {
        if prefs.isFavorite(lineId: line.lineId) {
            prefs.removeFavorite(lineId: line.lineId)
        } else {
            prefs.addFavorite(line)
        }
        favorites = prefs.favorites()
        Haptics.selection()
    }

    func isFavorite(_ lineId: String) -> Bool { prefs.isFavorite(lineId: lineId) }

    func setSource(_ s: BusSource) {
        guard s != source else { return }
        prefs.setSource(s)
        source = s
        // 两套数据源的 cityId 编号体系互不相通，切源必须换回该源默认城市
        let c = BusCity(id: (prefs as? BusStore)?.defaultCityId(for: s) ?? "056", name: "唐山")
        prefs.setCity(c)
        city = c
        station = nil
        detail = nil
        panel = .station
        Task { await reloadNearby() }
    }

    func setCity(_ c: BusCity) {
        prefs.setCity(c)
        city = c
        station = nil
        detail = nil
        panel = .station
        Task { await reloadNearby() }
    }

    func setTransitChoice(_ c: TransitCodeChoice) {
        prefs.setTransitChoice(c)
        transitChoice = c
    }

    // MARK: - 轮询

    /// 进入线路页后每 20s 补一次预测；离开时停掉，不做后台空转。
    func startPolling() {
        stopPolling()
        refreshTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                guard !Task.isCancelled, let self else { return }
                await self.refresh()
            }
        }
    }

    func stopPolling() {
        refreshTask?.cancel()
        refreshTask = nil
    }
}
