import UIKit

/**
 * AppDelegate —— 应用入口。
 *
 * 刻意**不用** UIScene 生命周期（Info.plist 里没有 UIApplicationSceneManifest）：
 * 本项目只有一个窗口、一个根控制器，Scene 机制（SceneDelegate + scene(_:willConnectTo:)）
 * 只会多一层间接、多一个可能出错的地方。iOS 15 起仍然完整支持纯 AppDelegate 生命周期。
 *
 * 冷启动底色由 UILaunchScreen（Info.plist）给到品牌深蓝，与 Android 的 splash_bg 一致，
 * 首帧由 WebViewController 的 splashColor 接续，全程不出现白屏。
 */
@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = WebViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
