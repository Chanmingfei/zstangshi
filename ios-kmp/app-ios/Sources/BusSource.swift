import Foundation

/// 数据源：车来了 / 掌上公交。
///
/// 两家的协议完全不同（前者数字 cityId + 签名 + AES，后者中文城市名 + GCJ 坐标 + 中文文案），
/// 所以从"用哪个源"到"怎么解析"都要分叉。界面层只认 `BusSource` 这一个开关。
enum BusSource: String, CaseIterable {
    case chelaile
    case mygolbs

    var label: String {
        switch self {
        case .chelaile: return "车来了"
        case .mygolbs: return "掌上公交"
        }
    }

    /// 该源使用的城市标识：车来了 056，掌上公交 唐山市。
    var defaultCityId: String {
        switch self {
        case .chelaile: return "056"
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

enum BusSourceStore {
    private static let key = "bus.source"

    static var current: BusSource {
        get { BusSource(rawValue: UserDefaults.standard.string(forKey: key) ?? "") ?? .chelaile }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: key) }
    }
}
