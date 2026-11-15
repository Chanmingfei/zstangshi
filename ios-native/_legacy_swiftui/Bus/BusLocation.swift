import Foundation
import CoreLocation
import UIKit

/// 定位封装 —— 专门为「查找附近公交站」这个场景优化。
///
/// ⚠️ 这一块是上一版 iOS 公交「无法定位」的核心。
/// 旧实现有三个问题：
///   1. `requestLocation()` 在 `notDetermined` 时**不会**先弹授权就调用，请求直接失败；
///   2. 授权刚下来时没有重新发起定位，用户点了「允许」也拿不到坐标；
///   3. `CheckedContinuation` 可能被 delegate 回调多次 resume → 崩溃。
///
/// 这里按安卓 `LocationManager` 的策略重写：
///   · 只收第一帧就 `stopUpdatingLocation()`（省电，与安卓一致）；
///   · 7 秒超时兜底，有上一次的坐标就用旧的（安卓同款）；
///   · 权限 denied 时明确返回 `.locationDenied`，界面引导去设置；
///   · 所有 finish 路径幂等，绝不会重复 resume。
@MainActor
final class BusLocation: NSObject, ObservableObject {

    @Published private(set) var authorization: CLAuthorizationStatus
    @Published private(set) var coordinate: CLLocationCoordinate2D?
    @Published private(set) var lastError: String?

    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<CLLocationCoordinate2D, Error>?
    private var timeoutTask: Task<Void, Never>?
    private var lastGood: CLLocation?
    /// 授权请求是否正在进行（用于在授权回调后续上定位）
    private var waitingForAuth = false

    override init() {
        authorization = CLLocationManager().authorizationStatus
        super.init()
        manager.delegate = self
        // 公交站场景不需要高精度，百米级足够，且更快出结果、更省电
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = 50
        authorization = manager.authorizationStatus
    }

    /// 是否已经有可用的近期坐标（30 秒内）
    var hasFreshCoordinate: Bool {
        guard let c = lastGood else { return false }
        return abs(c.timestamp.timeIntervalSinceNow) < 30
    }

    /// 当前是否被拒绝
    var denied: Bool {
        authorization == .denied || authorization == .restricted
    }

    /// 请求一次坐标。
    /// - Parameter timeout: 超时秒数，超时后有旧坐标就用旧的
    func requestOnce(timeout: TimeInterval = 7) async throws -> CLLocationCoordinate2D {
        // 先看缓存
        if let c = lastGood, abs(c.timestamp.timeIntervalSinceNow) < 30 {
            coordinate = c.coordinate
            return c.coordinate
        }

        switch manager.authorizationStatus {
        case .denied, .restricted:
            throw BusError.locationDenied
        case .notDetermined:
            // 关键：必须先请求授权，等 delegate 回调再发起定位。
            // 直接调用 requestLocation() 会立即失败。
            waitingForAuth = true
            manager.requestWhenInUseAuthorization()
        default:
            break
        }

        // 已有挂起请求：等它，而不是再建一个 continuation
        if continuation != nil {
            return try await withCheckedThrowingContinuation { cont in
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 700_000_000)
                    if let c = self.lastGood {
                        cont.resume(returning: c.coordinate)
                    } else {
                        cont.resume(throwing: BusError.locationTimeout)
                    }
                }
            }
        }

        return try await withCheckedThrowingContinuation { cont in
            self.continuation = cont

            // 未决定授权时先不定位，等 didChangeAuthorization
            if self.manager.authorizationStatus != .notDetermined {
                self.manager.requestLocation()
            }

            self.timeoutTask?.cancel()
            self.timeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                guard !Task.isCancelled else { return }
                await MainActor.run {
                    guard let self else { return }
                    if let old = self.lastGood {
                        // 安卓同款：超时后用旧坐标兜底，不让用户卡在转圈
                        self.coordinate = old.coordinate
                        self.finish(.success(old.coordinate))
                    } else {
                        self.finish(.failure(BusError.locationTimeout))
                    }
                }
            }
        }
    }

    /// 打开系统设置里的本 App 权限页
    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    // MARK: - 内部

    private func finish(_ result: Result<CLLocationCoordinate2D, Error>) {
        timeoutTask?.cancel()
        timeoutTask = nil
        guard let cont = continuation else { return }   // 幂等：已 finish 过就直接返回
        continuation = nil
        switch result {
        case .success(let c):
            coordinate = c
            cont.resume(returning: c)
        case .failure(let e):
            lastError = (e as? LocalizedError)?.errorDescription ?? "定位失败"
            cont.resume(throwing: e)
        }
    }
}

// MARK: - CLLocationManagerDelegate

extension BusLocation: CLLocationManagerDelegate {

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorization = status
            switch status {
            case .denied, .restricted:
                self.waitingForAuth = false
                self.finish(.failure(BusError.locationDenied))
            case .authorizedWhenInUse, .authorizedAlways:
                let wasWaiting = self.waitingForAuth
                self.waitingForAuth = false
                // 授权刚下来（或已有挂起请求）→ 立刻发起定位
                if wasWaiting || self.continuation != nil {
                    self.manager.requestLocation()
                }
            default:
                break
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in
            // 只收第一帧就停（与安卓一致，省电）
            manager.stopUpdatingLocation()
            self.lastGood = loc
            self.finish(.success(loc.coordinate))
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            let cle = error as? CLError
            switch cle?.code {
            case .denied:
                self.finish(.failure(BusError.locationDenied))
            case .locationUnknown:
                // 暂时定位不到：不立刻失败，交给超时兜底（系统常会自动重试）
                if let old = self.lastGood {
                    self.finish(.success(old.coordinate))
                }
            default:
                self.finish(.failure(
                    BusError.upstream("定位失败：\(error.localizedDescription)")))
            }
        }
    }
}