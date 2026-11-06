import Foundation
import Network
import os.log

/**
 * LocalHTTPServer —— 内置在 App 内的极简静态文件服务，只监听 127.0.0.1。
 *
 * 为什么需要它：Android 侧用 `http://app.local` 作为虚拟源（见 MainActivity.loadApp），
 * 由 shouldInterceptRequest 从 assets/www 供给资源。iOS 的 WKWebView **不支持**同等做法：
 *   · file:// 的 origin 是 null，<script type="module"> 被同源策略拒绝，整套 ESM 白屏；
 *   · WKURLSchemeHandler 对 ES module 有已知缺陷，且自定义 scheme 不是 secure context，
 *     localStorage 行为不可靠。
 * 唯一可靠的等价物是让 WebView 从真实 http 源加载：
 *   http://127.0.0.1:PORT 属 "potentially trustworthy origin"，是 secure context，
 *   localStorage / sessionStorage / ES module / crypto 全部照常可用。
 *
 * ★ 端口必须**固定**，不能用 .any 让系统随机分配：
 *   localStorage 与 sessionStorage 按「scheme + host + port」隔离。端口一变，
 *   用户已存的主题选择、记住的学号、课表缓存全部读不到，等于每次冷启动都重来。
 *   所以优先绑 18765，被占用时依次尝试 18766…（此时源会变，宁可丢一次本地数据，
 *   也不能整个 App 打不开）。端口区间避开了 49152+ 的系统临时端口段，冲突概率很低。
 *
 * 协议实现刻意保持最小：只处理 GET / HEAD，读完请求头立刻响应并关闭连接
 * （Connection: close），不做 keep-alive —— WKWebView 能正常处理。
 */
final class LocalHTTPServer {

    /// 首选端口：固定值 = 稳定的 origin = 稳定的 localStorage
    static let preferredPort: UInt16 = 18765
    private static let portCandidates: [UInt16] = [18765, 18766, 18767, 18768, 18769]

    private let root: URL
    private let queue = DispatchQueue(label: "com.tstc.zstangshi.httpserver", qos: .userInitiated)
    private var listener: NWListener?
    /// 每次尝试自增，用来丢弃旧 listener 迟到的状态回调
    private var generation = 0
    private let log = Logger(subsystem: "com.tstc.zstangshi", category: "server")

    /// 请求头最多读到这么多字节还没结束，就按坏请求断开
    private let maxHeaderBytes = 16 * 1024

    init(root: URL) {
        self.root = root
    }

    /// 启动服务并在主线程回调实际端口（全部候选都被占用时回 nil）。
    func start(completion: @escaping (UInt16?) -> Void) {
        queue.async { [weak self] in
            self?.attempt(index: 0, completion: completion)
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.generation += 1
            self?.listener?.cancel()
            self?.listener = nil
        }
    }

    // MARK: - 端口尝试

    private func attempt(index: Int, completion: @escaping (UInt16?) -> Void) {
        guard index < Self.portCandidates.count else {
            DispatchQueue.main.async { completion(nil) }
            return
        }
        let port = Self.portCandidates[index]
        generation += 1
        let currentGeneration = generation

        do {
            let parameters = NWParameters.tcp
            parameters.allowLocalEndpointReuse = true
            // 只绑 loopback：局域网看不到，也不需要「本地网络」权限弹窗
            parameters.requiredLocalEndpoint = NWEndpoint.hostPort(
                host: "127.0.0.1",
                port: NWEndpoint.Port(rawValue: port) ?? .any
            )

            let listener = try NWListener(using: parameters)
            self.listener = listener

            listener.stateUpdateHandler = { [weak self] state in
                guard let self, self.generation == currentGeneration else { return }
                switch state {
                case .ready:
                    self.log.info("listening on 127.0.0.1:\(port, privacy: .public)")
                    DispatchQueue.main.async { completion(port) }
                case .failed(let error):
                    self.log.error("port \(port, privacy: .public) failed: \(error.localizedDescription, privacy: .public)")
                    listener.cancel()
                    self.listener = nil
                    self.queue.async { self.attempt(index: index + 1, completion: completion) }
                default:
                    break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                self?.accept(connection)
            }
            listener.start(queue: queue)
        } catch {
            log.error("listener init failed on \(port, privacy: .public): \(error.localizedDescription, privacy: .public)")
            queue.async { self.attempt(index: index + 1, completion: completion) }
        }
    }

    // MARK: - 连接处理

    private func accept(_ connection: NWConnection) {
        connection.stateUpdateHandler = { state in
            switch state {
            case .failed, .cancelled:
                connection.cancel()
            default:
                break
            }
        }
        connection.start(queue: queue)
        receive(on: connection, buffer: Data())
    }

    private func receive(on connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] chunk, _, isComplete, error in
            guard let self else {
                connection.cancel()
                return
            }
            if error != nil {
                connection.cancel()
                return
            }
            var accumulated = buffer
            if let chunk { accumulated.append(chunk) }

            if let headerEnd = accumulated.range(of: Data("\r\n\r\n".utf8)) {
                let head = accumulated.subdata(in: accumulated.startIndex..<headerEnd.lowerBound)
                self.respond(to: head, on: connection)
                return
            }
            if accumulated.count > self.maxHeaderBytes || isComplete {
                self.respond(to: accumulated, on: connection)
                return
            }
            self.receive(on: connection, buffer: accumulated)
        }
    }

    private func respond(to head: Data, on connection: NWConnection) {
        let text = String(decoding: head, as: UTF8.self)
        guard let requestLine = text.components(separatedBy: "\r\n").first else {
            send(status: 400, reason: "Bad Request", mime: "text/plain; charset=utf-8",
                 body: Data(), on: connection)
            return
        }
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else {
            send(status: 400, reason: "Bad Request", mime: "text/plain; charset=utf-8",
                 body: Data(), on: connection)
            return
        }

        let method = String(parts[0]).uppercased()
        guard method == "GET" || method == "HEAD" else {
            send(status: 405, reason: "Method Not Allowed", mime: "text/plain; charset=utf-8",
                 body: Data(), on: connection)
            return
        }

        var path = String(parts[1])
        if let query = path.firstIndex(of: "?") { path = String(path[path.startIndex..<query]) }
        path = path.removingPercentEncoding ?? path

        // 目录穿越：字面 .. 与百分号编码形态都会被拦（解码已在上一步完成）
        if path.contains("..") {
            send(status: 403, reason: "Forbidden", mime: "text/plain; charset=utf-8",
                 body: Data(), on: connection)
            return
        }
        if path.isEmpty || path == "/" { path = "/index.html" }

        let relative = String(path.drop(while: { $0 == "/" }))
        let fileURL = root.appendingPathComponent(relative)
        guard let data = try? Data(contentsOf: fileURL, options: .mappedIfSafe) else {
            send(status: 404, reason: "Not Found", mime: "text/plain; charset=utf-8",
                 body: Data(), on: connection)
            return
        }

        send(status: 200, reason: "OK", mime: MIME.type(for: path),
             body: method == "HEAD" ? Data() : data, on: connection,
             extraHeaders: ["Content-Length": String(data.count)])
    }

    private func send(status: Int, reason: String, mime: String, body: Data,
                      on connection: NWConnection, extraHeaders: [String: String] = [:]) {
        var head = "HTTP/1.1 \(status) \(reason)\r\n"
        head += "Content-Type: \(mime)\r\n"
        head += "Cache-Control: no-cache, no-store, must-revalidate\r\n"
        head += "Access-Control-Allow-Origin: *\r\n"
        head += "Connection: close\r\n"
        for (key, value) in extraHeaders {
            head += "\(key): \(value)\r\n"
        }
        head += "\r\n"

        var payload = Data(head.utf8)
        payload.append(body)

        connection.send(content: payload, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }
}

/**
 * MIME 表 —— 唯一真源，与 Android AssetLoader.mimeOf 完全一致。
 *
 * .js/.mjs 必须是 `text/javascript`：写成 application/javascript 或 text/plain 时
 * Safari 会以严格 MIME 检查拒绝 <script type="module">，整套 ESM 白屏。
 */
enum MIME {
    static func type(for path: String) -> String {
        let ext = (path as NSString).pathExtension.lowercased()
        switch ext {
        case "html", "htm": return "text/html; charset=utf-8"
        case "js", "mjs": return "text/javascript; charset=utf-8"
        case "css": return "text/css; charset=utf-8"
        case "json": return "application/json; charset=utf-8"
        case "svg": return "image/svg+xml"
        case "webmanifest": return "application/manifest+json"
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "webp": return "image/webp"
        case "gif": return "image/gif"
        case "ico": return "image/x-icon"
        case "txt": return "text/plain; charset=utf-8"
        case "woff": return "font/woff"
        case "woff2": return "font/woff2"
        case "ttf": return "font/ttf"
        default: return "application/octet-stream"
        }
    }
}
