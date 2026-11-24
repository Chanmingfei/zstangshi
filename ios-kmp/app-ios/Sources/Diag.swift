import Foundation

/// 真机诊断日志：同时写到 syslog（idevicesyslog / pymobiledevice3 可抓）和沙盒文件。
/// 只记录状态与数量，不记录密码、Cookie 值等敏感内容。
enum Diag {
    private static let fileURL: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("diag.log")
    }()

    private static let lock = NSLock()
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    static func log(_ tag: String, _ message: String = "") {
        let line = "[\(formatter.string(from: Date()))][ZSTS][\(tag)] \(message)"
        print(line)
        lock.lock()
        defer { lock.unlock() }
        var old = (try? String(contentsOf: fileURL, encoding: .utf8)) ?? ""
        old += line + "\n"
        if old.utf8.count > 64 * 1024 {
            old = String(old.suffix(32 * 1024))
        }
        try? old.write(to: fileURL, atomically: true, encoding: .utf8)
    }

    static func recent() -> String {
        (try? String(contentsOf: fileURL, encoding: .utf8)) ?? "（暂无日志）"
    }
}
