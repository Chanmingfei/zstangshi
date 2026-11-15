import SwiftUI
import WebKit
import SafariServices
import CoreLocation

/// WebContainer —— 承载网页版（www/）的 WKWebView 壳。
///
/// 与安卓 MainActivity.java 对应：
///   · 用自定义 scheme `app://app.local/` 从 bundle 读 www/（ESM 模块需要非 file:// 源）
///   · 注入 window.NativeBridge（9 个 JS API，见 NativeBridge.swift）
///   · 定位授权（网页 navigator.geolocation 用）
///   · 学工系统等外链 → SFSafariViewController（等价安卓 WebActivity）
///
/// 页面风格完全由 www/ 里的 HTML/CSS/JS 决定（HIG 设计），iOS 只做容器。
struct WebContainer: UIViewControllerRepresentable {
    let bridge: NativeBridge
    let onOpenExternal: (URL) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIViewController(context: Context) -> WebViewController {
        let vc = WebViewController(bridge: bridge, onOpenExternal: onOpenExternal)
        return vc
    }

    func updateUIViewController(_ vc: WebViewController, context: Context) {}

    final class Coordinator: NSObject {
        let parent: WebContainer
        init(_ parent: WebContainer) { self.parent = parent }
    }
}

final class WebViewController: UIViewController, WKNavigationDelegate, WKUIDelegate {
    private let bridge: NativeBridge
    private let onOpenExternal: (URL) -> Void
    private var webView: WKWebView!
    private let locationManager = LocationDelegate()

    init(bridge: NativeBridge, onOpenExternal: @escaping (URL) -> Void) {
        self.bridge = bridge
        self.onOpenExternal = onOpenExternal
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()

        let config = WKWebViewConfiguration()
        // 注册自定义 scheme（必须在 configuration 创建时就设好）
        config.setURLSchemeHandler(AssetSchemeHandler(), forURLScheme: AssetSchemeHandler.scheme)
        // 允许 JS 弹窗/定位等
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.websiteDataStore = WKWebsiteDataStore.default()

        webView = WKWebView(frame: view.bounds, configuration: config)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = false
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.backgroundColor = .systemBackground
        view.addSubview(webView)

        bridge.attach(webView)

        // 在页面加载前注入 window.NativeBridge
        let userScript = WKUserScript(
            source: NativeBridge.injectionScript(),
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false
        )
        config.userContentController.addUserScript(userScript)

        loadApp()
    }

    /// 加载主文档：loadHTMLString 灌入 index.html，base 指向 app://app.local/。
    /// （与安卓 loadDataWithBaseURL 同理：主文档直接灌，子资源走 scheme handler）
    private func loadApp() {
        guard let url = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "www"),
              let html = try? String(contentsOf: url, encoding: .utf8) else {
            // 兜底：直接导航到 scheme 根（handler 会回 index.html）
            webView.load(URLRequest(url: URL(string: "\(AssetSchemeHandler.scheme)://\(AssetSchemeHandler.host)/index.html")!))
            return
        }
        webView.loadHTMLString(html, baseURL: URL(string: "\(AssetSchemeHandler.scheme)://\(AssetSchemeHandler.host)/"))
    }

    // MARK: - 外链 / 学工系统

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel); return
        }
        if url.scheme == AssetSchemeHandler.scheme {
            decisionHandler(.allow)
            return
        }
        // 非 app:// 的 http(s)/tel/mailto → Safari 外开
        if ["http", "https", "tel", "mailto"].contains(url.scheme?.lowercased() ?? "") {
            onOpenExternal(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    // MARK: - 定位授权

    func webView(_ webView: WKWebView,
                 requestGeolocationPermissionFor origin: WKWebFrame,
                 initiatedByFrame frame: WKWebFrame,
                 decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        locationManager.requestLocation { ok in
            decisionHandler(ok ? .grant : .deny)
        }
    }
}

/// 定位授权封装（对应 Info.plist 的 NSLocationWhenInUseUsageDescription）。
final class LocationDelegate: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var completion: ((Bool) -> Void)?

    func requestLocation(_ completion: @escaping (Bool) -> Void) {
        self.completion = completion
        manager.delegate = self
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            completion(true)
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        default:
            completion(false)
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let ok = manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways
        completion?(ok)
        completion = nil
    }
}