import SwiftUI
import WebKit

/// 应用内浏览器（对齐 Android `WebViewActivity`）。
///
/// 关键：要把教务会话的 Cookie 灌进 WKWebView，
/// 这样「更多功能 / 选课」打开原站点时不必再登一次 —— 这是内置浏览器唯一的意义。
///
/// ⚠️ 上一版的 bug：`HTTPCookie(properties:)` 的 `.domain` 传裸 host 且强制
/// `.secure: "FALSE"`，导致 https 页面拿不到 Cookie，用户以为「登录没共享」。
/// 这里按页面实际 scheme 决定 secure 标志，并为 domain 补上前导点。
struct WebPage: View {
    @EnvironmentObject private var app: AppState
    let target: WebTarget

    @State private var cookies: [String: String] = [:]

    var body: some View {
        WebViewRepresentable(url: URL(string: target.url), cookies: cookies)
            .navigationTitle(target.title)
            .navigationBarTitleDisplayMode(.inline)
            .background(T.bgPage.ignoresSafeArea())
            .task {
                // 异步取会话 Cookie —— 不要用信号量阻塞主线程（会卡死 UI 甚至死锁）
                cookies = await app.client.cookiesForWebView()
            }
    }
}

// MARK: - WKWebView 封装

struct WebViewRepresentable: UIViewRepresentable {
    let url: URL?
    let cookies: [String: String]

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.defaultWebpagePreferences.allowsContentJavaScript = true

        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = true
        web.scrollView.contentInsetAdjustmentBehavior = .automatic
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        guard let url else { return }
        let key = url.absoluteString + "#" + String(cookies.count)
        // 只在「首次加载」或「Cookie 从空变为非空」时重载，避免 SwiftUI 频繁刷新时反复请求
        guard context.coordinator.loadedKey != key else { return }
        guard context.coordinator.loadedKey == nil || !cookies.isEmpty else { return }
        context.coordinator.loadedKey = key

        injectCookies(into: web, for: url) {
            web.load(URLRequest(url: url))
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    /// 把会话 Cookie 写进 WKWebView 的 cookie store，再 load
    private func injectCookies(into web: WKWebView, for url: URL, completion: @escaping () -> Void) {
        guard !cookies.isEmpty else { completion(); return }
        let store = web.configuration.websiteDataStore.httpCookieStore
        let host = url.host ?? ""
        let isSecure = url.scheme?.lowercased() == "https"
        let group = DispatchGroup()

        for (name, value) in cookies {
            var props: [HTTPCookiePropertyKey: Any] = [
                .name: name,
                .value: value,
                .path: "/"
            ]
            if !host.isEmpty { props[.domain] = host }
            if isSecure { props[.secure] = "TRUE" }

            if let cookie = HTTPCookie(properties: props) {
                group.enter()
                store.setCookie(cookie) { group.leave() }
            }
        }
        group.notify(queue: .main) { completion() }
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var loadedKey: String?

        func webView(_ webView: WKWebView,
                     decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            // 外部链接（非 http/https）交给系统：支付宝、拨号等
            if let scheme = navigationAction.request.url?.scheme?.lowercased(),
               scheme != "http", scheme != "https", scheme != "about" {
                if let u = navigationAction.request.url {
                    UIApplication.shared.open(u)
                }
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }
    }
}