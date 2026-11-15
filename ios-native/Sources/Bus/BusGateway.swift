import Foundation

// MARK: - 实时公交 · 网关协议（网络层与页面层的唯一接缝）
//
// 页面层 / ViewModel 只依赖这个协议，不认识车来了、掌上公交任何一个具体类型。
// 具体实现见 BusApi.swift（双数据源 + 自动回落）。
// 所有方法都是 `async throws`，失败由 ViewModel 统一转成界面上的状态文案，
// 绝不返回伪造数据：上游没给就是 -1 / 空数组。

protocol BusGateway {

    /// 支持实时公交的城市列表
    func cities() async throws -> [BusCity]

    /// 按坐标由近及远取附近站点
    func nearby(cityId: String, at coord: BusCoordinate, limit: Int) async throws -> [BusStation]

    /// 站点详情：按物理站台分组，补齐线路与到站预测
    func stopDetail(cityId: String, station: BusStation,
                    at coord: BusCoordinate) async throws -> BusStation

    /// 线路详情：站表 + 在途车辆 + 反向线路 + 到候车站的预测
    /// - targetOrder: 候车站站序（1-based），传 0 表示由上游决定
    func lineDetail(cityId: String, lineId: String, targetOrder: Int,
                    at coord: BusCoordinate) async throws -> BusLineDetail

    /// 首站发车时刻表。按「今日预测 → 昨日实际 → 前日实际」优先级取数并写明真实来源
    func timetable(cityId: String, line: BusLine, direction: Int) async throws -> BusTimetable

    /// 搜索线路 / 站点
    func search(cityId: String, keyword: String, at coord: BusCoordinate) async throws -> BusSearchHit

    /// 搜索结果翻页（more=true 时）
    func searchMore(cityId: String, keyword: String,
                    at coord: BusCoordinate) async throws -> [BusLine]

    /// 批量补到站预测：只回填传进来的这些行，返回更新后的同一批对象
    func refreshEta(cityId: String, arrivals: [BusArrival],
                    at coord: BusCoordinate) async throws -> [BusArrival]

    /// 换向：返回对向线路 id，空串表示单程 / 环线
    func reverseLineId(cityId: String, line: BusLine) async throws -> String
}

// MARK: - 网关错误

enum BusError: LocalizedError {
    case noLocation
    case noCity
    case upstream(String)
    case decode(String)

    var errorDescription: String? {
        switch self {
        case .noLocation: return "还没拿到定位，可在顶部搜索线路"
        case .noCity: return "当前城市暂不支持实时公交"
        case .upstream(let s): return s.isEmpty ? "公交数据暂时取不到" : s
        case .decode(let s): return "公交数据解析失败：\(s)"
        }
    }
}

// MARK: - 数据源（与 Android BusClient.SRC_* 一致）

enum BusSource: Int, CaseIterable, Identifiable {
    case chelaile = 0
    case mygolbs = 1

    var id: Int { rawValue }
    var name: String {
        switch self {
        case .chelaile: return "车来了"
        case .mygolbs: return "掌上公交"
        }
    }
}

// MARK: - 收藏 / 设置（页面层通过它读写，不直接碰 UserDefaults）

protocol BusPreferences {
    func source() -> BusSource
    func setSource(_ s: BusSource)
    func city() -> BusCity
    func setCity(_ c: BusCity)
    func favorites() -> [BusLine]
    func addFavorite(_ l: BusLine)
    func removeFavorite(lineId: String)
    func isFavorite(lineId: String) -> Bool
    func transitChoice() -> TransitCodeChoice
    func setTransitChoice(_ c: TransitCodeChoice)
}
