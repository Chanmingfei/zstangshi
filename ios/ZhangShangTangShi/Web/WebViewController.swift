import UIKit
import WebKit
import os.log

/**
 * WebViewController —— 手写 WKWebView 壳（对应 Android MainActivity.java）。
 *
 * 加载链路：
 *   LocalHTTPServer（只监听 127.0.0.1，固定端口）→ http://127.0.0.1:18765/index.html
 *   → bridge-shim.js 在 documentStart 注入，造出 window.NativeBridge
 *   → web/app.js 照原样启动（H5 零改动）
 *
 * 与 Android 逐条对齐的关键设置：
 *   · scrollView.contentInsetAdjustmentBehavior = .never
 *       —— H5 用 env(safe-area-inset-*) 自己处理安全区（web/styles/tokens.css:189-192），
 *          WebView 再自动内缩一次就会出现双倍留白。
 *   · scrollView.bounces = false
 *       —— Android 默认 overscroll 只发光不回弹；H5 有自己实现的下拉刷新
 *          （web/components/pullRefresh.js），原生回弹会与它抢手势。
 *   · 捏合缩放关闭 + viewport 已锁 maximum-scale=1
 *       —— 对齐 Android setSupportZoom(false)。
 *   · 边缘右滑 = 系统返回键：调 window.__appBack()（router.js:367），
 *       返回 true 表示被次级页/弹层消费，false 表示已在 Tab 根页 —— iOS 没有
 *       「退出 App」的交互范式，根页就不再响应。
 *   · 非本地源链接一律外开（对应 AssetLoader.shouldOverrideUrlLoading）。
 */
final class WebViewController: UIViewController {

    private var webView: WKWebView!
    private var server: LocalHTTPServer?
    private var bridge: NativeBridge?
    private var failureView: UIView?
    private var currentTheme = "light"

    private let log = Logger(subsystem: "com.tstc.zstangshi", category: "web")

    /// 品牌深蓝（web/index.html 的 theme-color-dark 与 android colors.xml 同值）：
    /// 冷启动到首帧之间的底色，避免白屏闪烁（对应 Android splash_bg）。
    private let splashColor = UIColor(
        red: CGFloat(0x1B) / 255.0,
        green: CGFloat(0x3A) / 255.0,
        blue: CGFloat(0x6B) / 255.0,
        alpha: 1
    )

    private var appVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "1.2.0"
    }

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = splashColor

        setUpWebView()
        startLocalServer()
    }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        currentTheme == "dark" ? .lightContent : .darkContent
    }

    // MARK: - 搭建 WebView

    private func setUpWebView() {
        let configuration = WKWebViewConfiguration()
        // 与主 WebView 共用默认存储：Cookie / localStorage 跨启动保留
        configuration.websiteDataStore = .default()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.allowsInlineMediaPlayback = true
        // 对齐 Android setMediaPlaybackRequiresUserGesture(true)
        configuration.mediaTypesRequiringUserActionForPlayback = .all

        let nativeBridge = NativeBridge(appVersion: appVersion)
        configuration.userContentController.add(nativeBridge, name: NativeBridge.handlerName)
        if let shim = makeShimSource(for: nativeBridge) {
            configuration.userContentController.addUserScript(
                WKUserScript(source: shim, injectionTime: .atDocumentStart, forMainFrameOnly: true)
            )
        } else {
            log.error("bridge-shim.js 缺失：H5 将退化为 web 通道，教务直连不可用")
        }
        self.bridge = nativeBridge

        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = splashColor
        webView.scrollView.backgroundColor = splashColor
        webView.allowsBackForwardNavigationGestures = false
        webView.allowsLinkPreview = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.bounces = false
        webView.scrollView.alwaysBounceVertical = false
        webView.scrollView.pinchGestureRecognizer?.isEnabled = false
        if #available(iOS 16.4, *) {
            // 与 Android WebView.setWebContentsDebuggingEnabled(true) 对齐，
            // 便于在 Mac/Windows 上用 Safari 远程调试验收项
            webView.isInspectable = true
        }

        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        nativeBridge.webView = webView
        nativeBridge.host = self

        let edgePan = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(handleEdgePan(_:)))
        edgePan.edges = .left
        edgePan.delegate = self
        webView.addGestureRecognizer(edgePan)
    }

    /// 把原生环境信息 + bridge-shim.js 拼成一段在 documentStart 执行的脚本
    private func makeShimSource(for bridge: NativeBridge) -> String? {
        guard let url = Bundle.main.url(forResource: "bridge-shim", withExtension: "js"),
              let shim = try? String(contentsOf: url, encoding: .utf8) else {
            return nil
        }
        let preamble = """
        (function () {
          window.__ZSTS_NATIVE = {
            appVersion: \(JSString.literal(bridge.appVersion)),
            credential: \(JSString.literal(bridge.credentialAtLaunch)),
            platform: 'ios'
          };
          window.__ZSTS_NATIVE_CREDENTIAL = window.__ZSTS_NATIVE.credential;
        })();

        """
        return preamble + shim
    }

    // MARK: - 启动本地资源服务

    private func startLocalServer() {
        guard let webRoot = Bundle.main.url(forResource: "www", withExtension: nil) else {
            log.error("资源目录 www 缺失")
            showFailure("安装包缺少 www 资源目录，请重新安装应用。")
            return
        }
        let server = LocalHTTPServer(root: webRoot)
        self.server = server
        server.start { [weak self] port in
            guard let self else { return }
            guard let port, let url = URL(string: "http://127.0.0.1:\(port)/index.html") else {
                self.log.error("本地资源服务启动失败")
                self.showFailure("本地资源服务启动失败，请重启应用。")
                return
            }
            self.hideFailure()
            self.log.info("loading \(url.absoluteString, privacy: .public)")
            self.webView.load(URLRequest(url: url))
        }
    }

    // MARK: - 主题

    /// 由 bridge-shim.js 在 data-theme 变化时上报；只改状态栏样式，不改 WebView 的配色
    func applyTheme(_ theme: String) {
        currentTheme = theme == "dark" ? "dark" : "light"
        setNeedsStatusBarAppearanceUpdate()
    }

    // MARK: - 应用内浏览器

    func presentInAppBrowser(_ url: URL) {
        let browser = InAppBrowserViewController(url: url)
        let navigation = UINavigationController(rootViewController: browser)
        navigation.modalPresentationStyle = .pageSheet
        present(navigation, animated: true)
    }

    // MARK: - 返回手势

    @objc private func handleEdgePan(_ gesture: UIScreenEdgePanGestureRecognizer) {
        guard gesture.state == .ended else { return }
        let translation = gesture.translation(in: webView)
        let velocity = gesture.velocity(in: webView)
        guard translation.x > 60 || velocity.x > 500 else { return }
        webView.evaluateJavaScript("window.__appBack ? window.__appBack() : false") { [weak self] result, _ in
            // true  = 已被次级页/弹层消费；
            // false = 已在 Tab 根页 —— iOS 不提供「退出 App」手势，保持不动。
            let consumed = (result as? Bool) ?? false
            if !consumed {
                self?.log.debug("edge pan ignored: already at tab root")
            }
        }
    }

    // MARK: - 失败兜底

    private func showFailure(_ message: String) {
        hideFailure()
        let container = UIView()
        container.backgroundColor = .systemBackground
        container.translatesAutoresizingMaskIntoConstraints = false

        let label = UILabel()
        label.text = message
        label.numberOfLines = 0
        label.textAlignment = .center
        label.textColor = .secondaryLabel
        label.font = .preferredFont(forTextStyle: .body)
        label.translatesAutoresizingMaskIntoConstraints = false

        let button = UIButton(type: .system)
        button.setTitle("重试", for: .normal)
        button.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        button.addTarget(self, action: #selector(retry), for: .touchUpInside)
        button.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(label)
        container.addSubview(button)
        view.addSubview(container)

        NSLayoutConstraint.activate([
            container.topAnchor.constraint(equalTo: view.topAnchor),
            container.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            container.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            container.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            label.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: container.centerYAnchor, constant: -24),
            label.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 32),
            label.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -32),
            button.topAnchor.constraint(equalTo: label.bottomAnchor, constant: 20),
            button.centerXAnchor.constraint(equalTo: container.centerXAnchor),
        ])
        failureView = container
    }

    private func hideFailure() {
        failureView?.removeFromSuperview()
        failureView = nil
    }

    @objc private func retry() {
        hideFailure()
        if server == nil { startLocalServer() } else { webView.reload() }
    }
}

// MARK: - WKNavigationDelegate

extension WebViewController: WKNavigationDelegate {

    /// 本地源放行；其它 http(s)/tel/mailto 交给系统（对应 AssetLoader.shouldOverrideUrlLoading）
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        if isLocal(url) || navigationAction.targetFrame == nil {
            decisionHandler(.allow)
            return
        }
        let scheme = url.scheme?.lowercased() ?? ""
        if ["http", "https", "tel", "mailto"].contains(scheme) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        hideFailure()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        handleNavigationFailure(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        handleNavigationFailure(error)
    }

    /// WebContent 进程被系统回收（内存压力）：直接重载，别留白屏
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        log.error("WebContent 进程已终止，重新加载")
        webView.reload()
    }

    private func handleNavigationFailure(_ error: Error) {
        let nsError = error as NSError
        // 主动取消（例如被 decidePolicy 拦下的外链）不是错误
        if nsError.domain == NSURLErrorDomain, nsError.code == NSURLErrorCancelled { return }
        log.error("导航失败: \(error.localizedDescription, privacy: .public)")
        showFailure("页面加载失败：\(error.localizedDescription)")
    }
}

// MARK: - WKUIDelegate

extension WebViewController: WKUIDelegate {

    /// target="_blank"：在同一个 WebView 里打开（Android 同样不弹新窗口）
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil {
            webView.load(navigationAction.request)
        }
        return nil
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) {
        presentAlert(title: nil, message: message, completionHandler: completionHandler)
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel) { _ in completionHandler(false) })
        alert.addAction(UIAlertAction(title: "确定", style: .default) { _ in completionHandler(true) })
        presentAlertController(alert)
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String,
                 defaultText: String?, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (String?) -> Void) {
        let alert = UIAlertController(title: nil, message: prompt, preferredStyle: .alert)
        alert.addTextField { $0.text = defaultText }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel) { _ in completionHandler(nil) })
        alert.addAction(UIAlertAction(title: "确定", style: .default) { [weak alert] _ in
            completionHandler(alert?.textFields?.first?.text)
        })
        presentAlertController(alert)
    }

    private func presentAlert(title: String?, message: String, completionHandler: @escaping () -> Void) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "好", style: .default) { _ in completionHandler() })
        presentAlertController(alert)
    }

    private func presentAlertController(_ alert: UIAlertController) {
        guard presentedViewController == nil else { return }
        present(alert, animated: true)
    }

    private func isLocal(_ url: URL) -> Bool {
        let host = url.host ?? ""
        return host == "127.0.0.1" || host == "localhost"
    }
}

// MARK: - UIGestureRecognizerDelegate

extension WebViewController: UIGestureRecognizerDelegate {

    /// 边缘返回手势与 WebView 内部滚动/横向滑动共存
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        true
    }
}
