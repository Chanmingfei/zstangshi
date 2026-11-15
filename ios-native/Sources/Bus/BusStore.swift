import Foundation

/// 公交本地存储：收藏线路 + 数据源/城市选择 + 候车站记忆。
///
/// 对齐安卓 `bus/BusStore.kt`：收藏存独立偏好文件，主键 `cityId|lineId`，
/// 按当前数据源过滤显示，**不删除另一数据源的收藏**（用户切回来还能看到）。
final class BusStore {

    private let defaults: UserDefaults

    private enum K {
        static let favorites = "favorites"
        static let source = "source"
        static let cityIdPrefix = "cityId_"
        static let cityNamePrefix = "cityName_"
        static let targetPrefix = "targetOrder_"
        static let lastName = "last_line_name"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: - 数据源与城市

    var source: BusSource {
        get {
            guard let raw = defaults.string(forKey: K.source),
                  let s = BusSource(rawValue: raw) else { return .chelaile }
            return s
        }
        set { defaults.set(newValue.rawValue, forKey: K.source) }
    }

    func cityId(for source: BusSource) -> String {
        let saved = defaults.string(forKey: K.cityIdPrefix + source.rawValue)
        return (saved?.isEmpty == false) ? saved! : source.defaultCityId
    }

    func setCityId(_ id: String, for source: BusSource) {
        guard !id.isEmpty else { return }
        defaults.set(id, forKey: K.cityIdPrefix + source.rawValue)
    }

    func cityName(for source: BusSource) -> String {
        let saved = defaults.string(forKey: K.cityNamePrefix + source.rawValue)
        return (saved?.isEmpty == false) ? saved! : source.defaultCityName
    }

    func setCityName(_ name: String, for source: BusSource) {
        guard !name.isEmpty else { return }
        defaults.set(name, forKey: K.cityNamePrefix + source.rawValue)
    }

    // MARK: - 收藏

    /// 全部收藏（不过滤数据源）
    func allFavorites() -> [FavoriteLine] {
        guard let json = defaults.string(forKey: K.favorites),
              let data = json.data(using: .utf8),
              let arr = try? JSONDecoder().decode([FavoriteLine].self, from: data) else {
            return []
        }
        return arr
    }

    /// 当前数据源的收藏，最近添加的在前
    func favorites(for source: BusSource) -> [FavoriteLine] {
        allFavorites()
            .filter { $0.source == source.rawValue }
            .sorted { $0.addedAt > $1.addedAt }
    }

    func isFavorite(_ line: BusLine) -> Bool {
        let key = line.favoriteKey
        return allFavorites().contains { $0.favoriteKey == key }
    }

    /// 切换收藏，返回切换后是否已收藏
    @discardableResult
    func toggleFavorite(_ line: BusLine) -> Bool {
        var arr = allFavorites()
        let key = line.favoriteKey
        if let idx = arr.firstIndex(where: { $0.favoriteKey == key }) {
            arr.remove(at: idx)
            save(arr)
            return false
        }
        var f = FavoriteLine()
        f.favoriteKey = key
        f.lineId = line.lineId
        f.name = line.name
        f.startName = line.startName
        f.endName = line.endName
        f.cityId = line.cityId.isEmpty ? source.defaultCityId : line.cityId
        f.source = source.rawValue
        f.addedAt = Date().timeIntervalSince1970
        arr.append(f)
        save(arr)
        return true
    }

    private func save(_ arr: [FavoriteLine]) {
        guard let data = try? JSONEncoder().encode(arr),
              let json = String(data: data, encoding: .utf8) else { return }
        defaults.set(json, forKey: K.favorites)
    }

    // MARK: - 候车站记忆

    func targetOrder(for lineId: String) -> Int {
        defaults.integer(forKey: K.targetPrefix + lineId)
    }

    func setTargetOrder(_ order: Int, for lineId: String) {
        guard !lineId.isEmpty else { return }
        defaults.set(order, forKey: K.targetPrefix + lineId)
    }

    // MARK: - 上次查看

    var lastLineName: String {
        get { defaults.string(forKey: K.lastName) ?? "" }
        set { defaults.set(newValue, forKey: K.lastName) }
    }
}