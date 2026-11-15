import Foundation
import WebKit

/// AssetSchemeHandler —— 从 bundle 的 www/ 供给虚拟源 app:// 的全部静态资源。
///
/// 等价安卓 AssetLoader.java：拦截自定义 scheme `app`，把请求路径映射到 bundle 里的
/// Resources/www/ 文件。这是 iOS 上让 `<script type="module">`（ESM）能正常 import 的
/// 关键——file:// 下 ESM 会被 CORS 拒绝（与安卓 file:// 白屏同一根因），
/// 而自定义 scheme 的 origin 是 `app://app.local`，浏览器把它当独立源，
/// 配合 handler 返回的 CORS 头即可正常解析 import。
///
/// 约定：主文档用loadHTMLString 灌入（base 指向 app://app.local/），子资源
/// （CSS/JS/PNG/JSON）由本 handler 从 www/ 读。
final class AssetSchemeHandler: NSObject, WKURLSchemeHandler {

    static let scheme = "app"
    static let host = "app.local"

    private static let mimeMap: [String: String] = [
        "html": "text/html; charset=utf-8",
        "htm": "text/html; charset=utf-8",
        "js": "text/javascript; charset=utf-8",
        "mjs": "text/javascript; charset=utf-8",
        "css": "text/css; charset=utf-8",
        "json": "application/json; charset=utf-8",
        "svg": "image/svg+xml",
        "png": "image/png",
        "jpg": "image/jpeg",
        "jpeg": "image/jpeg",
        "webp": "image/webp",
        "gif": "image/gif",
        "ico": "image/x-icon",
        "txt": "text/plain; charset=utf-8",
    ]

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url else {
            respond(urlSchemeTask, data: Data(), mime: "text/plain; charset=utf-8", status: 404)
            return
        }
        // 目录穿越防护
        let path = url.path
        if path.contains("..") || path.contains("%2e") || path.contains("%2E") {
            respond(urlSchemeTask, data: Data(), mime: "text/plain; charset=utf-8", status: 403)
            return
        }

        let rel: String
        if path.isEmpty || path == "/" {
            rel = "index.html"
        } else {
            rel = String(path.dropFirst())
        }

        // 直接从 bundle 根拼 www/<rel> 读文件（不依赖 forResource 的 subdirectory 语义，
        // 避免 XcodeGen 组织 bundle 时目录层级变化导致读不到）。
        let data: Data
        if let rootURL = Bundle.main.resourceURL {
            let fileURL = rootURL.appendingPathComponent("www").appendingPathComponent(rel)
            if let d = try? Data(contentsOf: fileURL) {
                data = d
            } else if let d = try? Data(contentsOf: rootURL.appendingPathComponent(rel)) {
                data = d
            } else {
                respond(urlSchemeTask, data: Data(), mime: "text/plain; charset=utf-8", status: 404)
                return
            }
        } else if let d = try? Data(contentsOf: Bundle.main.url(forResource: rel, withExtension: nil, subdirectory: "www")!) {
            data = d
        } else {
            respond(urlSchemeTask, data: Data(), mime: "text/plain; charset=utf-8", status: 404)
            return
        }

        let ext = (rel as NSString).pathExtension.lowercased()
        let mime = Self.mimeMap[ext] ?? "application/octet-stream"
        respond(urlSchemeTask, data: data, mime: mime, status: 200)
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
        // 无长连接/流式资源，无需处理
    }

    private func respond(_ task: WKURLSchemeTask, data: Data, mime: String, status: Int) {
        let resp = HTTPURLResponse(
            url: task.request.url!,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: [
                "Content-Type": mime,
                "Cache-Control": "no-cache, no-store, must-revalidate",
                "Access-Control-Allow-Origin": "*",
            ]
        )!
        task.didReceive(resp)
        task.didReceive(data)
        task.didFinish()
    }
}