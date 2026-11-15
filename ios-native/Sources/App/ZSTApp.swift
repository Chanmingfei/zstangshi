import SwiftUI
import SafariServices

/// 应用入口。
///
/// 这一版是「网页套壳」：整个界面由 bundle 里的 www/（HTML/CSS/JS，HIG 风格）渲染，
/// iOS 只提供 WKWebView 容器 + NativeBridge（教务 HTTP 代理 / RSA / Cookie）。
///
/// 与安卓 V1.2.0（网页壳版）完全同构：
///   · 界面/交互/功能逻辑都在 www/ 里（登录、课表、成绩、GPA、选课、推荐课表、校历、
///     实时公交、我的）
///   · iOS 原生只做：载入 www、注入 window.NativeBridge、定位授权、学工系统外链
@main
struct ZSTApp: App {
    @StateObject private var coordinator = AppCoordinator()

    var body: some Scene {
        WindowGroup {
            RootContainer()
                .environmentObject(coordinator)
        }
    }
}

/// 协调原生壳与网页桥、外链打开。
@MainActor
final class AppCoordinator: ObservableObject {
    let bridge = NativeBridge()
    @Published var externalURL: URL?

    func openExternal(_ url: URL) {
        externalURL = url
    }
}

struct RootContainer: View {
    @EnvironmentObject private var coordinator: AppCoordinator

    var body: some View {
        WebContainer(bridge: coordinator.bridge) { url in
            coordinator.openExternal(url)
        }
        .ignoresSafeArea()
        // 学工系统等外链 → SFSafariViewController（等价安卓 WebActivity）
        .fullScreenCover(item: $coordinator.externalURL) { url in
            SafariView(url: url)
        }
    }
}

/// 用 SFSafariViewController 承载外链（学工系统等），自带返回/刷新/关闭工具条。
struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ vc: SFSafariViewController, context: Context) {}
}

// 让 URL 可作为 fullScreenCover 的 item（Identifiable）
extension URL: @retroactive Identifiable {
    public var id: String { absoluteString }
}