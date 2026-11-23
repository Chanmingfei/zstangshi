import Foundation

/// 公交的本地偏好：当前城市 + 收藏线路。
///
/// iOS 上没有 SharedPreferences，用 UserDefaults 就够（都是非敏感数据）。
/// 密码那种才必须进 Keychain —— 这里一个都不能放敏感信息。
enum BusPrefs {

    private static let kCityId = "bus.cityId"
    private static let kCityName = "bus.cityName"
    private static let kFavorites = "bus.favorites"

    static var cityId: String {
        get { UserDefaults.standard.string(forKey: kCityId) ?? "056" }
        set { UserDefaults.standard.set(newValue, forKey: kCityId) }
    }

    static var cityName: String {
        get { UserDefaults.standard.string(forKey: kCityName) ?? "唐山" }
        set { UserDefaults.standard.set(newValue, forKey: kCityName) }
    }

    /// 收藏的 lineId → 显示名（存 JSON，避免两个数组对不上）
    private static var favMap: [String: String] {
        get {
            guard let d = UserDefaults.standard.data(forKey: kFavorites),
                  let m = try? JSONDecoder().decode([String: String].self, from: d) else { return [:] }
            return m
        }
        set {
            if let d = try? JSONEncoder().encode(newValue) {
                UserDefaults.standard.set(d, forKey: kFavorites)
            }
        }
    }

    static var favorites: [(id: String, name: String)] {
        favMap.map { (id: $0.key, name: $0.value) }.sorted { $0.name < $1.name }
    }

    static func isFavorite(_ lineId: String) -> Bool {
        favMap[lineId] != nil
    }

    static func toggleFavorite(lineId: String, name: String) {
        var m = favMap
        if m[lineId] != nil { m.removeValue(forKey: lineId) } else { m[lineId] = name }
        favMap = m
    }
}
