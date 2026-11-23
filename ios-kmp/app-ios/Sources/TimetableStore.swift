import Foundation
import Shared

/// 课表本地存储。
///
/// 为什么存文件而不是 UserDefaults：一份课表几十 KB，而且会随课程数增长；
/// UserDefaults 适合小配置，塞大 JSON 会让每次读取都变慢。
/// 序列化用 Kotlin 的 `TimetableCodec`（和安卓同一套字段名），换端也能读。
enum TimetableStore {

    private static var fileURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("timetable.json")
    }

    static func load() -> Timetable? {
        guard let data = try? Data(contentsOf: fileURL),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return TimetableCodec.shared.fromJson(json: text)
    }

    static func save(_ t: Timetable) {
        let json = TimetableCodec.shared.toJson(t: t)
        try? json.data(using: .utf8)?.write(to: fileURL, options: .atomic)
    }

    static func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }
}
