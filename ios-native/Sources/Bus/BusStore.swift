import Foundation

// MARK: - 公交设置 / 收藏 / 小组件共享
//
// 与 Android `BusStore` 一一对应：数据源、当前城市、收藏、乘车码偏好。
// iOS 侧额外把「今日课程」也写进同一个 App Group，桌面小组件直接读，
// 不要求主 App 与小组件共享一整套模型文件（小组件只认一份极简 JSON）。

enum BusGroup {
    static let id = "group.com.tstc.zstangshi"
    static let todayCourses = "widget_today_courses"
    static let todayUpdated = "widget_today_updated"

    static var defaults: UserDefaults {
        UserDefaults(suiteName: id) ?? .standard
    }
}

final class BusStore: BusPreferences {

    private let d: UserDefaults

    private enum Key {
        static let source = "bus_source"
        static let cityId = "bus_city_id"
        static let cityName = "bus_city_name"
        static let favorites = "bus_favorites_v4"
        static let transit = "bus_transit_choice"
    }

    /// `useGroup: true` 时读写与主 App / Widget 共享的 suite。
    init(useGroup: Bool = true) {
        d = useGroup ? BusGroup.defaults : .standard
    }

    // MARK: BusPreferences

    func source() -> BusSource {
        BusSource(rawValue: d.integer(forKey: Key.source)) ?? .chelaile
    }

    func setSource(_ s: BusSource) {
        d.set(s.rawValue, forKey: Key.source)
    }

    func city() -> BusCity {
        let id = d.string(forKey: Key.cityId) ?? ""
        let name = d.string(forKey: Key.cityName) ?? ""
        if id.isEmpty { return BusCity(id: defaultCityId(for: source()), name: "唐山") }
        return BusCity(id: id, name: name.isEmpty ? "唐山" : name)
    }

    func setCity(_ c: BusCity) {
        d.set(c.id, forKey: Key.cityId)
        d.set(c.name, forKey: Key.cityName)
    }

    /// 两套数据源的 cityId 编号体系互不相通，切源后必须换回该源的默认城市。
    func defaultCityId(for s: BusSource) -> String {
        switch s {
        case .chelaile: return "056"
        case .mygolbs: return "唐山市"
        }
    }

    func favorites() -> [BusLine] {
        guard let raw = d.string(forKey: Key.favorites),
              let data = raw.data(using: .utf8),
              let list = try? JSONDecoder().decode([BusLine].self, from: data) else { return [] }
        // 清理早期注入的演示线路，避免测试数据混进正式收藏
        return list.filter { !$0.lineId.hasPrefix("demo-") }
    }

    private func save(_ list: [BusLine]) {
        guard let data = try? JSONEncoder().encode(list),
              let json = String(data: data, encoding: .utf8) else { return }
        d.set(json, forKey: Key.favorites)
    }

    func addFavorite(_ l: BusLine) {
        var list = favorites().filter { $0.lineId != l.lineId }
        list.insert(l, at: 0)
        save(list)
    }

    func removeFavorite(lineId: String) {
        save(favorites().filter { $0.lineId != lineId })
    }

    func isFavorite(lineId: String) -> Bool {
        favorites().contains { $0.lineId == lineId }
    }

    func transitChoice() -> TransitCodeChoice {
        let raw = d.string(forKey: Key.transit) ?? TransitCodeChoice.ask.rawValue
        // 微信乘车码已下线，旧值回落到「每次询问」
        guard let c = TransitCodeChoice(rawValue: raw) else { return .ask }
        return c
    }

    func setTransitChoice(_ c: TransitCodeChoice) {
        d.set(c.rawValue, forKey: Key.transit)
    }
}

// MARK: - 小组件共享：今日课程

/// 小组件只读这一份极简结构，不 import 主 App 的课表模型，避免 target 间耦合。
struct WidgetCourse: Codable, Equatable {
    var name: String
    var time: String
    var place: String
    var colorIndex: Int
    var done: Bool
}

enum WidgetBridge {
    /// 主 App 在课表变化 / 回到前台时调用。
    static func publish(_ items: [WidgetCourse]) {
        guard let data = try? JSONEncoder().encode(items),
              let json = String(data: data, encoding: .utf8) else { return }
        let d = BusGroup.defaults
        d.set(json, forKey: BusGroup.todayCourses)
        d.set(Date().timeIntervalSince1970, forKey: BusGroup.todayUpdated)
    }

    static func read() -> [WidgetCourse] {
        let d = BusGroup.defaults
        guard let raw = d.string(forKey: BusGroup.todayCourses),
              let data = raw.data(using: .utf8),
              let list = try? JSONDecoder().decode([WidgetCourse].self, from: data) else { return [] }
        return list
    }

    static func updatedAt() -> Date? {
        let t = BusGroup.defaults.double(forKey: BusGroup.todayUpdated)
        return t > 0 ? Date(timeIntervalSince1970: t) : nil
    }
}
