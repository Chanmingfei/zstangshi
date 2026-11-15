import Foundation

// MARK: - 公交数据模型
//
// 严格对齐安卓 `bus/BusModels.kt` 的字段语义。两个数据源（车来了 / 掌上公交）
// 统一成这套模型，页面层只认这里。

/// 数据源
enum BusSource: String, CaseIterable, Codable {
    /// 车来了（默认，覆盖城市多、到站预测准）
    case chelaile = "chelaile"
    /// 掌上公交（备选）
    case mygolbs = "mygolbs"

    var label: String {
        switch self {
        case .chelaile: return "车来了"
        case .mygolbs: return "掌上公交"
        }
    }

    var defaultCityId: String {
        switch self {
        case .chelaile: return "056"        // 唐山
        case .mygolbs: return "唐山市"
        }
    }

    var defaultCityName: String {
        switch self {
        case .chelaile: return "唐山"
        case .mygolbs: return "唐山"
        }
    }
}

/// 城市
struct BusCity: Identifiable, Equatable {
    var id: String
    var name: String
}

/// 到站信息（一条线路在某站的实时状态）
struct BusArrival: Identifiable, Equatable {
    var id: String { lineId.isEmpty ? lineName : lineId }

    var lineId: String = ""
    var lineName: String = ""
    var direction: Int = 0
    var startName: String = ""
    var endName: String = ""
    /// 上游的状态文案（如「等待发车」「已收班」）
    var status: String = ""

    /// 候车站在本线路上的站序（1 基）
    var orderInLine: Int = 0
    var stopId: String = ""

    /// 最快那辆：还有几分钟（-1 = 未知）
    var minutes: Int = -1
    /// 最快那辆：还有多少秒（-1 = 未知）。上游直给 travelTime，保留原始值便于二次换算。
    var seconds: Int = -1
    /// 还有几站（-1 = 未知）
    var stopsAway: Int = -1
    /// 距本站多少米（-1 = 未知）
    var distanceMeters: Int = -1
    /// 官方口径「已到站」
    var arrived: Bool = false
    /// 有没有实时数据
    var realtime: Bool = false
    /// 正在等补预测（界面显示「…」）
    var pending: Bool = false

    /// 紧随其后的第二辆
    var nextMinutes: Int = -1
    var nextStopsAway: Int = -1

    /// 主文案：「已到站」「3分钟」「…」「等待发车」
    var primaryText: String {
        if arrived { return "已到站" }
        if pending { return "…" }
        if minutes >= 0 {
            if minutes == 0 { return "即将到站" }
            if minutes > 60 { return "\(minutes / 60)小时\(minutes % 60)分" }
            return "\(minutes)分钟"
        }
        if !status.isEmpty { return status }
        return "暂无车"
    }

    /// 副文案：「还有 3 站」「下辆 12分钟」
    var secondaryText: String {
        var parts: [String] = []
        if stopsAway > 0 { parts.append("还有 \(stopsAway) 站") }
        if nextMinutes >= 0 {
            var s = "下辆 \(nextMinutes)分钟"
            if nextStopsAway > 0 { s += " \(nextStopsAway)站" }
            parts.append(s)
        }
        return parts.joined(separator: " · ")
    }

    var directionText: String {
        let dest = endName.isEmpty ? startName : endName
        return dest.isEmpty ? "" : "开往 " + dest
    }
}

/// 站点（附近站点 / 搜索结果 / 站点详情共用）
struct BusStation: Identifiable, Equatable {
    var id: String { sId.isEmpty ? name : sId }

    var sId: String = ""
    var name: String = ""
    var physicalStId: String = ""
    var namesakeStId: String = ""
    var lat: Double = 0
    var lng: Double = 0
    /// 距用户多少米（-1 = 未知）
    var distanceMeters: Int = -1
    var cityId: String = ""
    /// 经过的线路
    var arrivals: [BusArrival] = []
    /// 同名的其他站台（可切换）
    var platforms: [BusPlatform] = []

    var distanceText: String {
        guard distanceMeters >= 0 else { return "" }
        if distanceMeters < 1000 { return "\(distanceMeters)m" }
        let tenths = (distanceMeters + 50) / 100
        return "\(tenths / 10).\(tenths % 10)km"
    }
}

/// 物理站台
struct BusPlatform: Identifiable, Equatable {
    var id: String
    var name: String
}

/// 线路
struct BusLine: Identifiable, Equatable {
    var id: String { lineId.isEmpty ? name : lineId }

    var lineId: String = ""
    var name: String = ""
    var lineNo: String = ""
    /// 0 = 正方向，1 = 反方向（上游把正反当两条记录）
    var direction: Int = 0
    var startName: String = ""
    var endName: String = ""
    var firstTime: String = ""
    var lastTime: String = ""
    var cityId: String = ""

    var serviceTime: String {
        if firstTime.isEmpty && lastTime.isEmpty { return "" }
        return "\(firstTime)-\(lastTime)"
    }

    var routeText: String {
        if startName.isEmpty && endName.isEmpty { return "" }
        return "\(startName) → \(endName)"
    }

    /// 收藏主键
    var favoriteKey: String { "\(cityId.isEmpty ? "056" : cityId)|\(lineId)" }
}

/// 线路上的一个途经站
struct BusStop: Identifiable, Equatable {
    var id: String { "\(order)_\(name)" }
    var name: String = ""
    var order: Int = 0
    var sId: String = ""
    var lat: Double = 0
    var lng: Double = 0
}

/// 在途车辆
struct BusVehicle: Identifiable, Equatable {
    var id: String { busId }
    var busId: String = ""
    var plate: String = ""
    /// 车辆当前所在站序
    var order: Int = 0
    /// 距终点站米数
    var distanceToTerminal: Int = -1
    /// ⚠️ 上游 `distanceToWaitStn` 实际是「到终点站的距离」，此字段仅供排查，不要用于展示
    var distanceToWaitStnRaw: Int = -1
    /// 距候车站米数（由 busDetail 回填）
    var distanceToTarget: Int = -1
    var speed: Int = -1
    var angle: Double = 0
    var lat: Double = 0
    var lng: Double = 0
}

/// 线路详情
struct BusLineDetail: Equatable {
    var cityId: String = ""
    var lineId: String = ""
    var line = BusLine()
    var stops: [BusStop] = []
    var buses: [BusVehicle] = []
    /// 候车站站序
    var targetOrder: Int = 0
    var at: Date = Date()
    /// 原始 JSON（调试 / 后续扩展用）
    var raw: [String: Any] = [:]

    static func == (lhs: BusLineDetail, rhs: BusLineDetail) -> Bool {
        lhs.lineId == rhs.lineId && lhs.line == rhs.line
            && lhs.stops.count == rhs.stops.count
            && lhs.buses == rhs.buses
            && lhs.targetOrder == rhs.targetOrder
    }

    /// 候车站
    var targetStop: BusStop? {
        stops.first { $0.order == targetOrder }
    }
}

/// 某辆车到候车站的明细
struct BusEta: Equatable {
    var busId: String = ""
    var seconds: Int = -1
    var distance: Int = -1
    var tip: String = ""
    var order: Int = 0

    var text: String {
        if seconds == 0 { return "已到站" }
        if seconds > 0 {
            let m = (seconds + 59) / 60
            return m == 0 ? "即将到站" : "\(m)分钟"
        }
        return tip.isEmpty ? "—" : tip
    }
}

/// 主预测（最快那辆）
struct BusPrediction: Equatable {
    var seconds: Int = -1
    var distance: Int = -1
    var busId: String = ""
    var tip: String = ""
    var arrived: Bool = false
    var nextSeconds: Int = -1
    var nextBusId: String = ""

    var primaryText: String {
        if arrived { return "已到站" }
        if seconds < 0 { return "—" }
        if seconds == 0 { return "已到站" }
        let m = (seconds + 59) / 60
        return m == 0 ? "即将到站" : "\(m)分钟"
    }
}

/// busDetail 的完整结果
struct BusDetailResult {
    var prediction = BusPrediction()
    var byBus: [String: BusEta] = [:]
}

/// 搜索结果
struct BusSearchHit {
    var stations: [BusStation] = []
    var lines: [BusLine] = []
    var stationCount: Int = 0
    var lineCount: Int = 0

    var isEmpty: Bool { stations.isEmpty && lines.isEmpty }
    /// 站点还能不能继续加载（上游没有翻页，只能告诉用户有几条）
    var hasMoreStations: Bool { stationCount > stations.count }
    var hasMoreLines: Bool { lineCount > lines.count }
}

/// 首站发车时刻
struct BusTimetable {
    var lineId: String = ""
    var times: [String] = []
    var note: String = ""
}

/// 收藏的线路
struct FavoriteLine: Codable, Identifiable, Equatable {
    var id: String { favoriteKey }
    var favoriteKey: String = ""
    var lineId: String = ""
    var name: String = ""
    var startName: String = ""
    var endName: String = ""
    var cityId: String = ""
    var source: String = BusSource.chelaile.rawValue
    var addedAt: TimeInterval = 0

    var routeText: String {
        if startName.isEmpty && endName.isEmpty { return "" }
        return "\(startName) → \(endName)"
    }

    var asLine: BusLine {
        BusLine(lineId: lineId, name: name, startName: startName,
                endName: endName, cityId: cityId)
    }
}

// MARK: - 文案换算（对齐 BusText.kt）

enum BusText {
    /// 秒 → 分钟。1–59 秒归为 0（即「即将到站」），其余向上取整。
    static func minutesOfSecs(_ secs: Int) -> Int {
        if secs < 0 { return -1 }
        if secs < 60 { return 0 }
        return (secs + 59) / 60
    }

    /// 米 → 「1.3公里」/「420米」。用整数运算，与安卓逐字一致。
    static func distance(_ meters: Int) -> String {
        guard meters >= 0 else { return "" }
        if meters < 1000 { return "\(meters)米" }
        let tenths = (meters + 50) / 100
        return "\(tenths / 10).\(tenths % 10)公里"
    }
}

// MARK: - 错误

enum BusError: Error, LocalizedError {
    case locationDenied
    case locationTimeout
    case network(String)
    case upstream(String)

    var errorDescription: String? {
        switch self {
        case .locationDenied:
            return "需要定位权限才能查找附近的公交站。请到「设置 → 掌上唐师 → 位置」里允许访问"
        case .locationTimeout:
            return "定位超时。请确认已开启定位服务后重试"
        case .network(let m):
            return m.isEmpty ? "网络连接失败，请检查网络后重试" : m
        case .upstream(let m):
            return m.isEmpty ? "公交数据源暂时不可用" : m
        }
    }
}