import Foundation
import UIKit
import WebKit
import os.log

/**
 * NativeBridge —— window.NativeBridge 背后的原生实现（对应 Android NativeBridge.java）。
 *
 * 协议：H5 侧不再直接调用原生方法，而是由 bridge-shim.js 造一个同名同签名的
 * window.NativeBridge，把调用转成 postMessage({"op": ...})；这里按 op 分发。
 * 好处是 web/ 目录**一行都不用改**，detectEnv() 依然判定为原生通道。
 *
 * 与 Android 的逐条对应关系见 bridge-shim.js 顶部注释。这里补三处平台差异：
 *   1. rsaEncrypt 在 JS 侧同步完成（WKWebView 的 messageHandler 无法返回值），
 *      所以本类没有对应分支 —— 这不是漏实现，是故意为之。
 *   2. openWifiSettings：iOS 不允许 App 直达 Wi-Fi 设置页，只能打开本 App 的设置页
 *      （UIApplication.openSettingsURLString）。H5 侧的文案在 login.js 里，功能降级但不报错。
 *   3. theme：Android 由 values-night 主题自动跟随系统；iOS 的状态栏样式只能由原生改，
 *      所以 shim 在 data-theme 变化时上报，这里转给 WebViewController。
 */
final class NativeBridge: NSObject, WKScriptMessageHandler {

    static let handlerName = "zsts"

    weak var webView: WKWebView?
    weak var host: WebViewController?

    let appVersion: String
    /// 冷启动时从 Keychain 读到的凭据，随 UserScript 注入给 H5（loadCredential 是同步接口）
    let credentialAtLaunch: String

    private let log = Logger(subsystem: "com.tstc.zstangshi", category: "bridge")

    init(appVersion: String) {
        self.appVersion = appVersion
        self.credentialAtLaunch = CredentialStore.load() ?? ""
        super.init()
    }

    // MARK: - WKScriptMessageHandler

    func userContentController(_ userContentController: WKUserContentController,
                              didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let op = body["op"] as? String else { return }

        switch op {
        case "http":
            handleHTTP(body)
        case "clearSession":
            handleClearSession()
        case "saveCredential":
            if let value = body["value"] as? String {
                log.debug("saveCredential len=\(value.count, privacy: .public)")
                CredentialStore.save(value)
            }
        case "clearCredential":
            CredentialStore.clear()
        case "openWebView":
            if let raw = body["url"] as? String, let url = URL(string: raw) {
                host?.presentInAppBrowser(url)
            }
        case "openWifiSettings":
            openSystemSettings()
        case "theme":
            let value = (body["value"] as? String) == "dark" ? "dark" : "light"
            host?.applyTheme(value)
        default:
            log.debug("unknown op: \(op, privacy: .public)")
        }
    }

    // MARK: - 异步 HTTP

    private func handleHTTP(_ body: [String: Any]) {
        let reqId = body["reqId"] as? String ?? ""
        let url = body["url"] as? String ?? ""
        let method = body["method"] as? String ?? "GET"
        let headers = Self.parseHeaders(body["headers"] as? String)
        let payload = body["body"] as? String ?? ""

        Task { [weak self] in
            guard let self else { return }
            let result: [String: Any]
            do {
                let outcome = try await HTTPClient.execute(url: url, method: method,
                                                            headers: headers, body: payload)
                result = [
                    "reqId": reqId,
                    "status": outcome.status,
                    "headers": outcome.headers,
                    "body": outcome.body,
                    "url": outcome.url,
                    // Android 侧同时给 url 与 finalUrl（transport.js 读 url，文档写 finalUrl）
                    "finalUrl": outcome.url,
                ]
            } catch let failure as HTTPClient.Failure {
                result = Self.errorPayload(reqId: reqId, failure: failure)
            } catch {
                result = Self.errorPayload(reqId: reqId,
                                           failure: .transport(code: "IO",
                                                               detail: String(describing: error)))
            }
            await MainActor.run {
                self.deliver(result)
            }
        }
    }

    /// 回调载荷以「JSON 字符串」传给 H5（与 Android JSONObject.quote 的行为一致）
    private func deliver(_ payload: [String: Any]) {
        guard let webView else { return }
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: []),
              let json = String(data: data, encoding: .utf8) else { return }
        let literal = JSString.literal(json)
        let script = "if (window.__nativeHttpCallback) window.__nativeHttpCallback(\(literal));"
        webView.evaluateJavaScript(script, completionHandler: nil)
    }

    private static func errorPayload(reqId: String, failure: HTTPClient.Failure) -> [String: Any] {
        let code: String
        let detail: String
        switch failure {
        case .invalidURL(let url):
            code = "IO"
            detail = "invalidURL: \(url)"
        case .redirectWithoutLocation:
            code = "NETWORK"
            detail = "redirectWithoutLocation: 重定向缺少 Location 头"
        case .tooManyRedirects:
            code = "NETWORK"
            detail = "tooManyRedirects: 重定向超过 5 次"
        case .transport(let transportCode, let transportDetail):
            code = transportCode
            detail = transportDetail
        }
        return ["reqId": reqId, "error": "\(code)|\(detail)"]
    }

    // MARK: - 会话与系统设置

    /// 退出登录：清 WebView Cookie + 清 Keychain 凭据（§8 共享知识第 10 条）
    private func handleClearSession() {
        log.debug("clearSession() invoked from JS")
        CredentialStore.clear()
        Task {
            await CookieStore.clear()
        }
    }

    private func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
    }

    // MARK: - 工具

    /// headersJson 是 JSON 字符串（transport.js 用 JSON.stringify 传过来）
    private static func parseHeaders(_ json: String?) -> [String: String] {
        guard let json, let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        var out: [String: String] = [:]
        for (key, value) in object {
            if value is NSNull { continue }
            out[key] = (value as? String) ?? String(describing: value)
        }
        return out
    }

    deinit {
        webView?.configuration.userContentController
            .removeScriptMessageHandler(forName: Self.handlerName)
    }
}

/**
 * 把 Swift 字符串转成可安全嵌进 JS 源码的字符串字面量。
 *
 * 必须处理 U+2028/U+2029：它们在 JSON 里合法、在 JS 源码里却是行终止符，
 * 直接拼进去会让整段脚本语法错误（教务页面返回的正文里出现过）。
 */
enum JSString {
    static func literal(_ value: String) -> String {
        var escaped = ""
        escaped.reserveCapacity(value.count + 16)
        for character in value {
            switch character {
            case "\\": escaped += "\\\\"
            case "\"": escaped += "\\\""
            case "\n": escaped += "\\n"
            case "\r": escaped += "\\r"
            case "\t": escaped += "\\t"
            case "\u{2028}": escaped += "\\u2028"
            case "\u{2029}": escaped += "\\u2029"
            default: escaped.append(character)
            }
        }
        return "\"\(escaped)\""
    }
}
