import SwiftUI
import WebKit

/// App 内嵌浏览器 —— 对齐安卓 WebViewActivity。
///
/// 为什么不跳 Safari：教务/学工要登录态，跳出去等于换会话，用户得再登一次；
/// 这里用 WKWebView 共用 App 的 Cookie，登录过就是登录态。仓库页/版本页也在应用内打开。
///
/// 与安卓一致的三件事：
///   1. 顶部返回能后退网页，退到栈底才关页面；
///   2. 主框架 https 加载失败静默换 http（唐师 443 在移动数据下常不通）；
///   3. 顶部 2px 进度条 + JS/DOM 存储 + UA 后缀。
struct WebViewPage: View {
    let url: String
    let title: String

    /// 所有应用内打开的网页入口（对齐安卓 HomePage / MinePage / UpdateSheet 常量）。
    enum Link {
        static let jwxt = "http://jwxt.tstc.edu.cn/xtgl/index_initMenu.html"
        static let xuegong = "http://sws.tstc.edu.cn/XGTSTC/Phone/#/login"
        static let githubHome = "https://github.com/Chanmingfei/"
        static let giteeHome = "https://gitee.com/mingfei123/"
        static let githubRepo = "https://github.com/Chanmingfei/zstangshi"
        static let giteeRepo = "https://gitee.com/mingfei123/zstangshi"
        static let releases = "https://gitee.com/mingfei123/zstangshi/releases"
    }

    /// 便捷构造：按预置链接打开。
    static func link(_ url: String, title: String) -> WebViewPage {
        WebViewPage(url: url, title: title)
    }

    @Environment(\.dismiss) private var dismiss
    @StateObject private var controller = WebController()

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button {
                    if controller.canGoBack { controller.goBack() } else { dismiss() }
                } label: {
                    HStack(spacing: 2) {
                        Image(systemName: "chevron.left")
                        Text("返回")
                    }
                }
                Spacer()
                Text(title).font(.system(size: 16, weight: .semibold))
                Spacer()
                Button("刷新") { controller.reload() }
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(Color(UIColor.systemBackground))

            if controller.showProgress {
                ProgressView(value: controller.progress)
                    .progressViewStyle(.linear)
                    .frame(height: 2)
            }

            WebViewBridge(url: url, controller: controller)
        }
        .navigationBarHidden(true)
    }
}

/// WebView 控制器：持有实例，让顶层按钮能调 goBack/reload（SwiftUI 值类型做不到）。
@MainActor
final class WebController: ObservableObject {
    @Published var progress: Double = 0
    @Published var canGoBack = false
    @Published var showProgress = false
    weak var webView: WKWebView?

    func goBack() { webView?.goBack() }
    func reload() { webView?.reload() }
}

// MARK: - UIViewRepresentable

private struct WebViewBridge: UIViewRepresentable {
    let url: String
    @ObservedObject var controller: WebController

    func makeCoordinator() -> Coordinator { Coordinator(controller: controller) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.allowsInlineMediaPlayback = true
        config.preferences.javaScriptEnabled = true
        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = true
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.customUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1 ZSTS/4.0"

        web.addObserver(context.coordinator, forKeyPath: "estimatedProgress", options: .new, context: nil)
        web.addObserver(context.coordinator, forKeyPath: "canGoBack", options: .new, context: nil)

        controller.webView = web

        // 把 JwAuth(URLSession) 的登录 Cookie 灌进 WKWebView，否则教务网页要重登。
        // iOS 上两者默认不共享（安卓 CookieManager 是全局的），必须手动桥接。
        let jar = WKWebsiteDataStore.default().httpCookieStore
        let cookies = HTTPCookieStorage.shared.cookies ?? []
        let group = DispatchGroup()
        for c in cookies { group.enter(); jar.setCookie(c) { group.leave() } }
        group.notify(queue: .main) {
            if let u = URL(string: url) {
                var req = URLRequest(url: u)
                req.setValue("zh-CN,zh;q=0.9", forHTTPHeaderField: "Accept-Language")
                web.load(req)
            }
        }
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {}

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        web.removeObserver(coordinator, forKeyPath: "estimatedProgress")
        web.removeObserver(coordinator, forKeyPath: "canGoBack")
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        let controller: WebController
        private var retriedHttp = false
        init(controller: WebController) { self.controller = controller }

        override func observeValue(forKeyPath keyPath: String?, of object: Any?,
                                   change: [NSKeyValueChangeKey: Any]?, context: UnsafeMutableRawPointer?) {
            guard let web = object as? WKWebView else { return }
            Task { @MainActor in
                if keyPath == "estimatedProgress" {
                    controller.progress = web.estimatedProgress
                    controller.showProgress = web.estimatedProgress < 1.0
                } else if keyPath == "canGoBack" {
                    controller.canGoBack = web.canGoBack
                }
            }
        }

        /// 主框架失败 → https 换 http 静默重试一次（对齐安卓 PlainClient）。
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            retryHttpIfNeeded(webView)
        }

        func webView(_ webView: WKWebView,
                     didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            retryHttpIfNeeded(webView)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // 网页里新产生的 Cookie 回写 URLSession 存储，保持两端会话一致。
            WKWebsiteDataStore.default().httpCookieStore.getAllCookies { cookies in
                for c in cookies { HTTPCookieStorage.shared.setCookie(c) }
            }
            Task { @MainActor in
                controller.showProgress = false
                controller.canGoBack = webView.canGoBack
            }
        }

        private func retryHttpIfNeeded(_ web: WKWebView) {
            guard !retriedHttp,
                  let u = web.url, u.scheme?.lowercased() == "https" else { return }
            retriedHttp = true
            var comps = URLComponents(url: u, resolvingAgainstBaseURL: false)
            comps?.scheme = "http"
            if let httpUrl = comps?.url {
                web.load(URLRequest(url: httpUrl))
            }
        }
    }
}
