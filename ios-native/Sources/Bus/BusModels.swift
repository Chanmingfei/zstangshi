import Foundation
import SwiftUI

// MARK: - 实时公交 · 共享数据契约
//
// 这一份是 iOS 侧所有公交模块（网络层 / ViewModel / 页面）唯一依赖的模型定义，
// 字段口径逐条对齐 Android v4 的 `bus/BusModels.kt` + `bus/BusText.kt`。
// 网络层负责「填」，页面层负责「读」，任何一方都不得另造一份平行模型。

// MARK: 城市 / 站点

struct BusCity: Identifiable, Equatable, Codable {
    var id: String = ""
    var name: String = ""
}

/// 一条线路在某个站的到站预测。
struct BusArrival: Identifiable, Equatable, Codable {
    var id: String = UUID().uuidString
    var lineId: String = ""
    var lineName: String = ""
    var direction: Int = 0
    var startName: String = ""
    var endName: String = ""
    /// 该站在线路中的站序；虚拟站组共用 999，认站靠 stopId
    var orderInLine: Int = 0
    var stopId: String = ""

    /// 还有几分钟（向上取整）；-1 = 无预测
    var minutes: Int = -1
    /// 还有几站；-1 = 未知
    var stopsAway: Int = -1
    /// 距离本站还有多少米；-1 = 未知
    var distanceMeters: Int = -1

    /// 第二辆车（首页「下辆」）。与大字必须来自同一份预测；上游只给一辆时全为 -1
    var nextMinutes: Int = -1
    var nextStopsAway: Int = -1
    var nextBusId: String = ""
    /// 大字是这辆车算出来的，小字也只准读它
    var busId: String = ""

    var arrived: Bool = false
    /// 上游状态文案（等待发车 / 末班已过…），原样透传
    var status: String = ""
    var realtime: Bool = false
    /// 正在补预测
    var pending: Bool = false
    var refreshed: Bool = false

    // MARK: 统一文案口径（与 BusText 一致）

    var label: String {
        BusText.rowEtaText(minutes: minutes, stopsAway: stopsAway, arrived: arrived,
                           status: status, realtime: realtime, pending: pending)
    }
    var etaCompact: String { BusText.rowPrimaryText(minutes: minutes, stopsAway: stopsAway,
                                                     arrived: arrived, status: status, realtime: realtime) }
    var detailText: String { BusText.rowDetailText(minutes: minutes, stopsAway: stopsAway,
                                                    distanceMeters: distanceMeters, arrived: arrived) }
    var nextLabel: String { BusText.rowNextText(nextMinutes: nextMinutes, nextStopsAway: nextStopsAway) }
    var destination: String { endName.isEmpty ? startName : endName }
}

struct BusPlatform: Identifiable, Equatable, Codable {
    var id: String = ""
    var name: String = ""
}

struct BusStation: Identifiable, Equatable, Codable {
    var id: String = ""
    var physicalId: String = ""
    var namesakeId: String = ""
    var name: String = ""
    var distanceMeters: Int = -1
    var lat: Double = 0
    var lng: Double = 0
    var arrivals: [BusArrival] = []
    var platforms: [BusPlatform] = []

    var distanceLabel: String { BusText.distanceText(distanceMeters) }
    var hasRealtime: Bool { arrivals.contains { $0.realtime } }
}

struct BusLine: Identifiable, Equatable, Codable {
    var id: String = UUID().uuidString
    var lineId: String = ""
    var name: String = ""
    /// 上游短编号（r93552 之类），时刻表接口要它
    var lineNo: String = ""
    var direction: Int = 0
    var startName: String = ""
    var endName: String = ""
    var firstTime: String = ""
    var lastTime: String = ""
    var price: String = ""
    var stationsNum: Int = 0
    var arrivals: [BusArrival] = []

    var terminusLabel: String {
        startName.isEmpty && endName.isEmpty ? name : "\(startName) → \(endName)"
    }
}

struct BusSearchHit: Equatable, Codable {
    var stations: [BusStation] = []
    var lines: [BusLine] = []
    var lineCount: Int = 0
    var stationCount: Int = 0

    var isEmpty: Bool { stations.isEmpty && lines.isEmpty }
    var stationsTruncated: Bool { stationCount > stations.count }
    var linesTruncated: Bool { lineCount > lines.count }
}

// MARK: 线路详情

struct BusStop: Identifiable, Equatable, Codable {
    var id: String = ""
    var physicalId: String = ""
    var name: String = ""
    var order: Int = 0
    var orderLabel: String = ""
    var lat: Double = 0
    var lng: Double = 0
    var busHere: Bool = false
    /// 车在两站之间时的比例；-1 = 没有车
    var busRatio: Double = -1
    var nearest: Bool = false
    var picked: Bool = false
}

/// 一辆在途车。字段可缺省 —— 两个数据源能给的字段不一样，界面按缺省降级。
struct BusVehicle: Identifiable, Equatable, Codable {
    var id: String = ""
    /// 车牌或自编号（清洗过、给人看的）
    var plate: String = ""
    /// 正开往（或已停靠）第几站，1-based
    var order: Int = 0
    var isArrived: Bool = false
    /// **到候车站**的剩余秒；-1 = 无。不是到终点站的秒数
    var etaSeconds: Int = -1
    /// 到候车站的直线距离（米）；-1 = 无
    var distanceMeters: Int = -1
    var speedKmh: Double = 0
    var nextStopName: String = ""
}

struct BusLineDetail: Equatable, Codable {
    var line: BusLine = BusLine()
    var reverseId: String = ""
    var reverseStart: String = ""
    var reverseEnd: String = ""
    var stops: [BusStop] = []
    /// 按 order 升序；空 = 上游没给车，不要伪造
    var vehicles: [BusVehicle] = []
    var busCount: Int = 0
    var status: String = ""
    var nextDeparture: String = ""
    var waitingStopName: String = ""
    var waitingOrder: Int = 0
    var waitingStopId: String = ""
    var etaMinutes: Int = -1
    var etaStops: Int = -1
    var etaDistanceMeters: Int = -1
    var etaArrived: Bool = false
    var etaBusId: String = ""
    var etaNextBusId: String = ""
    var estArrival: String = ""

    var reversible: Bool { !reverseId.isEmpty }

    /// 大字和小字必须是同一辆车：聚合预测（谁最快到）指的那辆最准，
    /// 拿不到才按站序兜底。点名的车不在列表里就返回 nil，绝不拿后车顶替。
    func displayVehicle() -> BusVehicle? {
        if !etaBusId.isEmpty {
            return vehicles.first { $0.id == etaBusId }
        }
        guard !vehicles.isEmpty else { return nil }
        return vehicles
            .filter { $0.order <= max(1, waitingOrder) }
            .min { a, b in
                let sa = a.etaSeconds >= 0 ? a.etaSeconds : Int.max
                let sb = b.etaSeconds >= 0 ? b.etaSeconds : Int.max
                return sa < sb
            } ?? vehicles.first
    }

    /// 「下一辆」：同一次预测里秒数次小的那辆，排除大字那辆
    func followingVehicle() -> BusVehicle? {
        if !etaNextBusId.isEmpty {
            return vehicles.first { $0.id == etaNextBusId && $0.id != etaBusId }
        }
        let main = displayVehicle()?.id ?? ""
        return vehicles
            .filter { $0.id != main }
            .min { a, b in
                let sa = a.etaSeconds >= 0 ? a.etaSeconds : Int.max
                let sb = b.etaSeconds >= 0 ? b.etaSeconds : Int.max
                return sa < sb
            }
    }

    /// 「小于1分钟 · 17站 / 8.8千米」，上游没给距离就只给站数
    var etaDetail: String {
        var bits: [String] = []
        if etaMinutes == 0 && !etaArrived { bits.append("小于1分钟") }
        if etaStops > 0 { bits.append("\(etaStops)站") }
        if etaDistanceMeters > 0 {
            bits.append(BusText.distanceText(etaDistanceMeters))
        }
        return bits.joined(separator: bits.count > 1 ? " · " : "")
    }
}

// MARK: 首站发车时刻表

struct BusTtSegment: Identifiable, Equatable, Codable {
    var id: String = UUID().uuidString
    var title: String = ""
    var times: [String] = []
}

struct BusTimetable: Equatable, Codable {
    var lineName: String = ""
    var startName: String = ""
    var endName: String = ""
    var depTitle: String = ""
    var tip: String = ""
    /// 今日发车预测（只给还没发的班次）
    var today: [BusTtSegment] = []
    /// 昨日 / 前日实际班次
    var history: [BusTtSegment] = []
    /// 真实口径标题：「今日预测」/「昨日实际」/「前日实际」
    var historyTitle: String = ""
    var nextDep: String = ""
    var nextFrom: String = ""

    var isEmpty: Bool { today.isEmpty && history.isEmpty }
}

// MARK: - 文案统一口径

enum BusText {
    /// 到站徽标：「已到站」「即将到站」「约 N 分钟」「暂无预测」
    static func etaText(_ secs: Int, arrived: Bool) -> String {
        if arrived { return "已到站" }
        if secs <= 0 { return "暂无预测" }
        if secs <= 60 { return "即将到站" }
        return "约 \(minutesOfSecs(secs)) 分钟"
    }

    static func minutesText(_ secs: Int) -> String? {
        guard secs > 0 else { return nil }
        return "\(minutesOfSecs(secs)) 分钟"
    }

    /// 0 秒=车在站上；1..59 秒=0（写「即将到站」，不能算成 1 分钟）；其余向上取整
    static func minutesOfSecs(_ secs: Int) -> Int {
        if secs < 0 { return -1 }
        if secs == 0 || secs < 60 { return 0 }
        return (secs + 59) / 60
    }

    /// 一行的「大字」：到站了 · N 分钟 · 即将到站 · N 站 · 状态文案 · 暂无实时 · 暂无车
    static func rowEtaText(minutes: Int, stopsAway: Int, arrived: Bool,
                           status: String, realtime: Bool, pending: Bool) -> String {
        if pending { return "…" }
        if arrived { return "到站了" }
        if minutes == 0 && realtime { return "即将到站" }
        if minutes > 0 { return "\(minutes) 分钟" }
        if stopsAway > 0 { return "\(stopsAway) 站" }
        if !status.isEmpty { return status }
        return "暂无实时"
    }

    /// 首页 / 详情页第一行蓝色大字
    static func rowPrimaryText(minutes: Int, stopsAway: Int, arrived: Bool,
                               status: String, realtime: Bool) -> String {
        if arrived { return "到站了" }
        if minutes == 0 && realtime { return "即将到站" }
        if minutes > 0 && stopsAway > 0 { return "\(minutes)分钟 · \(stopsAway)站" }
        if minutes > 0 { return "\(minutes)分钟" }
        if stopsAway > 0 { return "\(stopsAway)站" }
        if !status.isEmpty { return status }
        return "暂无实时"
    }

    static func rowDetailText(minutes: Int, stopsAway: Int, distanceMeters: Int,
                              arrived: Bool) -> String {
        var bits: [String] = []
        if minutes == 0 && !arrived { bits.append("小于1分钟") }
        if stopsAway > 0 { bits.append("\(stopsAway)站") }
        if distanceMeters > 0 { bits.append(distanceText(distanceMeters)) }
        return bits.joined(separator: " · ")
    }

    /// 形如「下辆 37分钟 22站」。两个数都来自同一辆车的预测，缺哪个就不写哪个
    static func rowNextText(nextMinutes: Int, nextStopsAway: Int) -> String {
        guard nextMinutes > 0 || nextStopsAway > 0 else { return "" }
        var bits: [String] = []
        if nextMinutes > 0 { bits.append("\(nextMinutes)分钟") }
        if nextStopsAway > 0 { bits.append("\(nextStopsAway)站") }
        return "下辆 " + bits.joined(separator: " ")
    }

    static func distanceText(_ meters: Int) -> String {
        guard meters > 0 else { return "" }
        if meters < 1000 { return "\(meters)米" }
        return String(format: "%.1f千米", Double(meters) / 1000.0)
    }

    static func clockText(_ epochMillis: Double) -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: Date(timeIntervalSince1970: epochMillis / 1000))
    }

    static func clockAfter(_ seconds: Int, now: Date = Date()) -> String {
        guard seconds > 0 else { return "" }
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: now.addingTimeInterval(Double(seconds)))
    }

    static func sanitizePlate(_ raw: String?) -> String {
        guard let raw = raw else { return "" }
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? "" : t
    }
}

// MARK: - 乘车码

enum TransitCodeChoice: String, CaseIterable, Identifiable {
    case ask = "ask"
    case alipay = "alipay"
    case unionpay = "unionpay"

    var id: String { rawValue }
    var label: String {
        switch self {
        case .ask: return "每次询问"
        case .alipay: return "支付宝乘车码"
        case .unionpay: return "云闪付乘车码"
        }
    }
    var detail: String {
        switch self {
        case .ask: return "每次点击都打开选择面板"
        case .alipay: return "跳转到支付宝公交乘车码"
        case .unionpay: return "跳转到云闪付公交乘车码"
        }
    }
    /// 微信小程序不开放明文 scheme / 票据调用，实测跳转被拒，已下线。
    /// 这里保留说明文案，不摆一个点不动的按钮。
    static let wechatNotice = "由于微信限制，暂不提供微信乘车码跳转"
}

// MARK: - 页面状态

enum BusPanel: Int, Equatable {
    case station = 0    // 站点到站
    case busList = 1    // 全部车辆
    case follow = 2     // 单车跟随
}

struct BusCoordinate: Equatable {
    var lat: Double = 0
    var lng: Double = 0
    var valid: Bool { lat != 0 || lng != 0 }
}
