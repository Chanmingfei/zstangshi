import Foundation

/// 公交网关：把两个数据源藏在统一接口后面。
///
/// 页面层只依赖这个协议 —— 这是上一版 iOS 唯一值得保留的架构设计。
protocol BusGateway: Sendable {
    var source: BusSource { get }

    func cities() async throws -> [BusCity]
    func nearby(lat: Double, lng: Double, limit: Int) async throws -> [BusStation]
    func stopDetail(station: BusStation, lat: Double, lng: Double) async throws -> BusStation
    func search(_ keyword: String, lat: Double, lng: Double) async throws -> BusSearchHit
    func lineDetail(lineId: String, targetOrder: Int,
                    stationId: String?, lat: Double, lng: Double) async throws -> BusLineDetail
    func busDetail(lineId: String, targetOrder: Int,
                   stationId: String?, lat: Double, lng: Double) async throws -> BusDetailResult
    func timetable(lineId: String, lineNo: String?, direction: Int) async throws -> [String]
}

// MARK: - 车来了

struct ChelaileGateway: BusGateway {
    let source: BusSource = .chelaile
    private let client: ChelaileClient

    init(cityId: String = BusSource.chelaile.defaultCityId) {
        client = ChelaileClient(cityId: cityId)
    }

    func cities() async throws -> [BusCity] { try await client.cities() }

    func nearby(lat: Double, lng: Double, limit: Int) async throws -> [BusStation] {
        try await client.nearby(lat: lat, lng: lng, limit: limit)
    }

    func stopDetail(station: BusStation, lat: Double, lng: Double) async throws -> BusStation {
        try await client.stopDetail(physicalId: station.physicalStId,
                                    namesakeId: station.namesakeStId,
                                    stationId: station.sId,
                                    lat: lat, lng: lng)
    }

    func search(_ keyword: String, lat: Double, lng: Double) async throws -> BusSearchHit {
        try await client.search(keyword: keyword, lat: lat, lng: lng)
    }

    func lineDetail(lineId: String, targetOrder: Int,
                    stationId: String?, lat: Double, lng: Double) async throws -> BusLineDetail {
        try await client.lineDetail(lineId: lineId, targetOrder: targetOrder,
                                    stationId: stationId, lat: lat, lng: lng)
    }

    func busDetail(lineId: String, targetOrder: Int,
                   stationId: String?, lat: Double, lng: Double) async throws -> BusDetailResult {
        try await client.busDetail(lineId: lineId, targetOrder: targetOrder,
                                   stationId: stationId, lat: lat, lng: lng)
    }

    func timetable(lineId: String, lineNo: String?, direction: Int) async throws -> [String] {
        try await client.preStartTimetable(lineId: lineId, lineNo: lineNo, direction: direction)
    }
}

// MARK: - 掌上公交

struct MygolbsGateway: BusGateway {
    let source: BusSource = .mygolbs
    private let client: MygolbsClient

    init(cityName: String = BusSource.mygolbs.defaultCityId) {
        client = MygolbsClient(cityName: cityName)
    }

    func cities() async throws -> [BusCity] {
        // 掌上公交没有城市列表接口，固定唐山
        [BusCity(id: BusSource.mygolbs.defaultCityId,
                 name: BusSource.mygolbs.defaultCityName)]
    }

    func nearby(lat: Double, lng: Double, limit: Int) async throws -> [BusStation] {
        try await client.nearbyStations(lat: lat, lng: lng, range: 500)
    }

    func stopDetail(station: BusStation, lat: Double, lng: Double) async throws -> BusStation {
        // 掌上公交的站点详情就是「该站经过的线路」，直接用搜索结果里的数据
        var st = station
        if st.arrivals.isEmpty {
            let hit = try await client.search(keyword: station.name)
            let match = hit.stations.first { $0.name == station.name }
            st.arrivals = match?.arrivals ?? []
        }
        return st
    }

    func search(_ keyword: String, lat: Double, lng: Double) async throws -> BusSearchHit {
        let r = try await client.search(keyword: keyword)
        var hit = BusSearchHit()
        hit.stations = r.stations
        hit.lines = r.lines
        hit.stationCount = r.stations.count
        hit.lineCount = r.lines.count
        return hit
    }

    func lineDetail(lineId: String, targetOrder: Int,
                    stationId: String?, lat: Double, lng: Double) async throws -> BusLineDetail {
        try await client.lineDetail(lineId: lineId, targetOrder: targetOrder)
    }

    func busDetail(lineId: String, targetOrder: Int,
                   stationId: String?, lat: Double, lng: Double) async throws -> BusDetailResult {
        let r = try await client.realtime(lineId: lineId,
                                          stationId: stationId ?? "",
                                          stationOrder: targetOrder)
        var out = BusDetailResult()
        // 掌上公交的 realtime 已经直接给了车辆与到站，这里按 busId 摊开。
        // 注意：BusArrival 没有 busId 字段（它描述的是「线路到站」而不是「某辆车」），
        // 车辆维度的信息在 vehicles 里，所以用 车辆(vehicles) 与 到站(arrivals) 对齐下标。
        for (i, a) in r.arrivals.enumerated() {
            let v = i < r.vehicles.count ? r.vehicles[i] : nil
            let busId = v?.busId ?? "mg_\(i)"
            out.byBus[busId] = BusEta(busId: busId,
                                      seconds: a.seconds,
                                      distance: a.distanceMeters,
                                      tip: a.status,
                                      order: v?.order ?? 0)
        }
        if let first = r.arrivals.first, let v = r.vehicles.first {
            out.prediction = BusPrediction(seconds: first.seconds,
                                           distance: first.distanceMeters,
                                           busId: v.busId,
                                           arrived: first.seconds == 0)
        } else if let first = r.arrivals.first {
            out.prediction = BusPrediction(seconds: first.seconds,
                                           distance: first.distanceMeters,
                                           arrived: first.seconds == 0)
        }
        return out
    }

    func timetable(lineId: String, lineNo: String?, direction: Int) async throws -> [String] {
        []
    }
}

// MARK: - 工厂

enum BusGatewayFactory {
    static func make(_ source: BusSource, cityId: String? = nil) -> BusGateway {
        switch source {
        case .chelaile:
            return ChelaileGateway(cityId: cityId ?? BusSource.chelaile.defaultCityId)
        case .mygolbs:
            return MygolbsGateway()
        }
    }
}