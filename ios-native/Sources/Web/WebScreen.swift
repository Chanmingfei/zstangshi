import SwiftUI
import WebKit

/// 应用内浏览器（对应 Android `WebViewActivity`）。
///
/// 唯一的意义是**共享教务会话**：登录后打开「选课 / 更多功能 / 学工」不用再登一次。
/// 做法是把 `JwClient` 手动维护的 Cookie 灌进 WKWebView 的 cookie store
/// （Android 那边是 `CookieManager.setCookie`，同一思路），然后再 load。
///
/// 返回行为按 iOS 平台规范：网页可后退时先退网页，到栈底才关页面 ——
/// 不再自己画淡出动画（Android v3.0.21 修掉的黑屏就是自绘动画盖掉了系统转场，
/// iOS 这边从一开始就交给系统 sheet 的 dismiss 转场）。
struct WebScreen: View {
    let url: String
    let title: String

    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = WebModel()

    var body: some View {
        NavigationStack {
            ZStack(alignment: .top) {
                WebViewContainer(model: model)
                    .ignoresSafeArea(edges: .bottom)
                if model.loading {
                    ProgressView(value: max(0.05, model.progress))
                        .progressViewStyle(.linear)
                        .tint(T.brand)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        if model.canGoBack { model.goBack() } else { dismiss() }
                    } label: {
                        Image(systemName: "chevron.left")
                    }
                }
            }
        }
        .onAppear {
            model.load(url, cookies: app.client.cookiesForWebView())
        }
        .onDisappear { model.stop() }
    }
}

@MainActor
final class WebModel: NSObject, ObservableObject, WKNavigationDelegate {
    @Published var loading = false
    @Published var progress: Double = 0
    @Published var canGoBack = false

    let webView: WKWebView

    override init() {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        webView.navigationDelegate = self
        webView.allowsBackForwardNavigationGestures = true
    }

    /// 把教务会话灌进 WKWebView，再导航
    func load(_ urlString: String, cookies: [String: String]) {
        guard let url = URL(string: urlString) else { return }
        let host = url.host ?? JwClient.host
        let store = webView.configuration.websiteDataStore.httpCookieStore

        let group = DispatchGroup()
        for (name, value) in cookies {
            group.enter()
            let cookie = HTTPCookie(properties: [
                .domain: host,
                .path: "/",
                .name: name,
                .value: value,
                .secure: "FALSE"
            ])
            if let cookie {
                store.setCookie(cookie) { group.leave() }
            } else {
                group.leave()
            }
        }
        group.notify(queue: .main) { [weak self] in
            self?.webView.load(URLRequest(url: url))
        }
    }

    func goBack() { webView.goBack() }
    func stop() { webView.stopLoading() }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        loading = true
        canGoBack = webView.canGoBack
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        loading = false
        progress = 1
        canGoBack = webView.canGoBack
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        loading = false
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        loading = false
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        canGoBack = webView.canGoBack
    }
}

private struct WebViewContainer: UIViewRepresentable {
    @ObservedObject var model: WebModel

    func makeUIView(context: Context) -> WKWebView { model.webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
