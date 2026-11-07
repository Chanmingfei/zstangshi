import UIKit
import WebKit

/**
 * InAppBrowserViewController —— 应用内浏览器（对应 Android WebActivity.java）。
 *
 * 为什么要有它：学工系统是独立站点，App 不解析、不缓存它的数据，只负责把人送过去。
 * Android 侧曾经把 openWebView 降级成系统浏览器，导致每次跳转都离开 App、丢失返回栈；
 * 后来用一个带「返回 / 刷新 / 关闭」工具条的 Activity 在应用内承载，关闭即回主页。
 * iOS 这里保持一致：模态呈现、共享同一个 WKWebsiteDataStore（Cookie 与主 WebView 通用）、
 * 关闭即回主页。**不跳 SFSafariViewController**，因为它用 Safari 的 Cookie 存储，
 * 会与 App 内会话割裂，且用户会有「离开了 App」的错觉。
 *
 * 工具条：左「返回」（可后退时显示）+「关闭」，右「刷新」，标题随页面变化。
 */
final class InAppBrowserViewController: UIViewController {

    private let initialURL: URL
    private let webView: WKWebView
    private let progressBar = UIProgressView(progressViewStyle: .bar)
    private var progressObservation: NSKeyValueObservation?

    /// 协议兜底只换一次，防止两边都失败时来回跳（对应 WebActivity.AppWebViewClient.swapped）
    private var swappedScheme = false

    init(url: URL) {
        self.initialURL = url

        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        self.webView = WKWebView(frame: .zero, configuration: configuration)

        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = initialURL.host ?? "加载中"

        setUpNavigationItems()
        setUpWebView()
        setUpProgressBar()

        if #available(iOS 16.4, *) {
            webView.isInspectable = true
        }
        webView.load(URLRequest(url: initialURL))
    }

    deinit {
        progressObservation?.invalidate()
    }

    // MARK: - 布局

    private func setUpWebView() {
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)

        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    private func setUpProgressBar() {
        progressBar.translatesAutoresizingMaskIntoConstraints = false
        progressBar.progressTintColor = UIColor(named: "BrandPrimary") ?? .systemBlue
        progressBar.trackTintColor = .clear
        view.addSubview(progressBar)

        NSLayoutConstraint.activate([
            progressBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            progressBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            progressBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            progressBar.heightAnchor.constraint(equalToConstant: 2),
        ])

        progressObservation = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] webView, _ in
            guard let self else { return }
            let progress = Float(webView.estimatedProgress)
            self.progressBar.setProgress(progress, animated: true)
            self.progressBar.isHidden = progress >= 1
        }
    }

    private func setUpNavigationItems() {
        navigationItem.leftBarButtonItems = [
            UIBarButtonItem(title: "返回", style: .plain, target: self, action: #selector(goBack)),
            UIBarButtonItem(title: "关闭", style: .plain, target: self, action: #selector(close)),
        ]
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "刷新", style: .plain, target: self, action: #selector(reload))
    }

    // MARK: - 动作

    @objc private func goBack() {
        if webView.canGoBack {
            webView.goBack()
        } else {
            close()
        }
    }

    @objc private func reload() {
        webView.reload()
    }

    @objc private func close() {
        dismiss(animated: true)
    }
}

// MARK: - WKNavigationDelegate

extension InAppBrowserViewController: WKNavigationDelegate {

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if let pageTitle = webView.title, !pageTitle.isEmpty {
            title = pageTitle
        }
        // 本次导航已成功 → 重置兜底开关。Android 侧是整个 Activity 生命周期只兜一次，
        // 这里按「每次导航」兜一次更合理：用户换了网络后重新点开的链接仍能被救。
        swappedScheme = false
    }

    /// 主框架加载失败 → 换一次 http/https 重试（对应 Android WebActivity.onReceivedError）
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!,
                 withError error: Error) {
        retryWithSwappedScheme(error: error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        retryWithSwappedScheme(error: error)
    }

    // MARK: - 协议兜底

    /**
     * 教务对外只开 80 / 443，可达性随网络环境变：校园网 443 通，移动数据下 443 的 SYN
     * 直接被丢弃、80 正常（2026-11-07 实测）。H5 的 jwxtOrigin 会探测并选协议，但
     * 「探测那一刻」与「用户真正点开那一刻」可能已不在同一个网络，所以这里再兜一次，
     * 避免「浏览器能开、应用内一片空白」。
     */
    private func retryWithSwappedScheme(error: Error) {
        guard !swappedScheme else { return }
        let nsError = error as NSError
        guard nsError.domain == NSURLErrorDomain else { return }

        // 只对「连不上」类错误换协议：用户取消、证书问题、策略拦截换过去也一样失败
        let retryable = [
            NSURLErrorCannotConnectToHost,
            NSURLErrorTimedOut,
            NSURLErrorNetworkConnectionLost,
            NSURLErrorCannotFindHost,
            NSURLErrorNotConnectedToInternet,
            NSURLErrorSecureConnectionFailed,
        ]
        guard retryable.contains(nsError.code) else { return }

        let failing = (nsError.userInfo[NSURLErrorFailingURLErrorKey] as? URL) ?? webView.url
        guard let source = failing,
              var components = URLComponents(url: source, resolvingAgainstBaseURL: false) else {
            return
        }

        switch components.scheme?.lowercased() {
        case "https": components.scheme = "http"
        case "http": components.scheme = "https"
        default: return
        }
        guard let target = components.url,
              target.absoluteString != source.absoluteString else { return }

        swappedScheme = true
        webView.load(URLRequest(url: target))
    }

    /// 站内导航全部留在应用内；tel / mailto 交给系统
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url, let scheme = url.scheme?.lowercased() else {
            decisionHandler(.allow)
            return
        }
        if ["tel", "mailto", "sms"].contains(scheme) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }
}

// MARK: - WKUIDelegate

extension InAppBrowserViewController: WKUIDelegate {

    /// target="_blank"：Android 侧同样在同一个 WebView 里打开，不弹新窗口
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil {
            webView.load(navigationAction.request)
        }
        return nil
    }
}
