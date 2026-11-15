import Foundation
import CoreLocation

// MARK: - 定位
//
// 只取「使用时」一次定位：附近站点不需要持续追踪，常开定位只会耗电。
// 拿不到授权时保留 `valid == false`，网关会自己回落到默认城市坐标，
// 页面按「未授权」提示，绝不伪造一个假坐标去请求。

@MainActor
final class BusLocationService: NSObject, ObservableObject, CLLocationManagerDelegate {

    @Published private(set) var coordinate = BusCoordinate()
    @Published private(set) var denied = false
    @Published private(set) var busy = false

    private let manager = CLLocationManager()
    private var asked = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = 200
    }

    var coord: BusCoordinate { coordinate }

    func request() {
        guard !asked else { return }
        asked = true
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            busy = true
            manager.requestLocation()
        case .denied, .restricted:
            denied = true
        @unknown default:
            denied = true
        }
    }

    func refresh() {
        guard manager.authorizationStatus == .authorizedWhenInUse
                || manager.authorizationStatus == .authorizedAlways else {
            request()
            return
        }
        busy = true
        manager.requestLocation()
    }

    /// 手动指定城市时用：不伪造精确坐标，只把「有坐标」这件事置为否，
    /// 网关按城市中心自行处理。
    func clear() { coordinate = BusCoordinate() }

    // MARK: CLLocationManagerDelegate

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            switch manager.authorizationStatus {
            case .authorizedWhenInUse, .authorizedAlways:
                denied = false
                busy = true
                manager.requestLocation()
            case .denied, .restricted:
                denied = true
            default:
                break
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager,
                                     didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in
            coordinate = BusCoordinate(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
            busy = false
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in busy = false }
    }
}
