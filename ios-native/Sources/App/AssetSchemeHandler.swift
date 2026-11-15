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

        guard let fileURL = Bundle.main.url(forResource: rel, withExtension: nil, subdirectory: "www") ?? Bundle.main.url(forResource: rel, withExtension: nil),
              let data = try? Data(contentsOf: fileURL) else {
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