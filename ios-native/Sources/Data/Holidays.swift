import Foundation

/// 内置法定节假日与调休（国务院办公厅公布，对齐 Android `data/Holidays.kt`）。
/// 补课日上哪天的课按国务院调休对应关系推定；学校另有通知时用用户安排覆盖。
/// 2027 年安排公布后再补。
enum Holidays {

    struct Day {
        let date: String
        let label: String
        let off: Bool
        /// 仅补课日有值
        let fromDate: String
    }

    static let days: [Day] = build()

    private static func build() -> [Day] {
        var out: [Day] = []
        out += range("2026-09-25", "2026-09-27", "中秋")
        out += range("2026-10-01", "2026-10-07", "国庆")
        out.append(Day(date: "2026-09-20", label: "国庆调休", off: false, fromDate: "2026-10-06"))
        out.append(Day(date: "2026-10-10", label: "国庆调休", off: false, fromDate: "2026-10-07"))
        out += range("2027-01-01", "2027-01-01", "元旦")
        return out
    }

    private static func range(_ from: String, _ to: String, _ label: String) -> [Day] {
        var out: [Day] = []
        var d = from
        while d <= to {
            out.append(Day(date: d, label: label, off: true, fromDate: ""))
            let next = Dates.addDays(d, 1)
            if next.isEmpty { break }
            d = next
        }
        return out
    }

    /// 内置 + 课表里的用户安排（用户的优先）。返回 nil 表示普通日子。
    static func lookup(_ t: Timetable?, _ ymd: String?) -> Day? {
        guard let ymd, !ymd.isEmpty else { return nil }
        if let t {
            for a in t.adjusts.reversed() where ymd == a.date {
                if a.type == Adjust.off {
                    return Day(date: ymd, label: a.note.isEmpty ? "放假" : a.note, off: true, fromDate: "")
                }
                if a.type == Adjust.makeup {
                    return Day(date: ymd, label: a.note.isEmpty ? "补课" : a.note, off: false, fromDate: a.fromDate)
                }
            }
        }
        return days.first { $0.date == ymd }
    }
}