import Foundation

/// 正方教务周次表达式解析（Android `WeekRange.java` 的等价实现）。
///
/// 真实样本：`5-12周`、`1-5周,7-12周`、`1-16周(单)`、`1,3,5周`、`3周`、`1-16`
///
/// 解析失败**不抛错**、返回空列表：上层按「全周都上」保底显示，
/// 宁可多显示也不让课程凭空消失（这条和 Android 侧策略一致）。
enum WeekRange {

    struct Parsed {
        var weeks: [Int] = []
        var hasParity = false
        var oddOnly: Bool?
        var raw = ""

        var ok: Bool { !weeks.isEmpty }
        func contains(_ week: Int) -> Bool { weeks.contains(week) }
    }

    static func parse(_ expr: String) -> Parsed {
        var out = Parsed()
        out.raw = expr.trimmingCharacters(in: .whitespaces)
        guard !out.raw.isEmpty else { return out }

        // 1. 先判单双周（必须在剥掉中文字符之前）
        if out.raw.contains("单") {
            out.hasParity = true
            out.oddOnly = true
        } else if out.raw.contains("双") {
            out.hasParity = true
            out.oddOnly = false
        }

        // 2. 只留数字、'-'、','（全角逗号与顿号统一成半角）
        var cleaned = ""
        for ch in out.raw {
            if ch.isNumber, ch.isASCII {
                cleaned.append(ch)
            } else if ch == "-" {
                cleaned.append(ch)
            } else if ch == "," || ch == "，" || ch == "、" {
                cleaned.append(",")
            }
        }
        guard !cleaned.isEmpty else { return out }

        // 3. 按逗号切段：a-b 或 a
        var result: [Int] = []
        for part in cleaned.split(separator: ",", omittingEmptySubsequences: true) {
            let text = String(part)
            let pieces = text.split(separator: "-", omittingEmptySubsequences: false)
            if pieces.count == 2,
               let a = Int(pieces[0]), let b = Int(pieces[1]) {
                let lo = min(a, b), hi = max(a, b)
                if hi - lo > 60 { continue }        // 异常区间丢弃
                for w in lo...hi { append(&result, w) }
            } else if let a = Int(text.replacingOccurrences(of: "-", with: "")), a > 0, a <= 60 {
                append(&result, a)
            }
        }

        // 4. 单双周过滤
        if let odd = out.oddOnly {
            result = result.filter { odd ? ($0 % 2 == 1) : ($0 % 2 == 0) }
        }

        out.weeks = Array(Set(result)).sorted()
        return out
    }

    static func isActive(_ expr: String, week: Int) -> Bool {
        let p = parse(expr)
        if !p.ok { return true }
        return p.contains(week)
    }

    /// 把周次列表压回区间表达式（导入导出用）
    static func compress(_ weeks: [Int]) -> String {
        let s = Array(Set(weeks)).sorted()
        guard !s.isEmpty else { return "" }
        var parts: [String] = []
        var runStart = s[0]
        var prev = s[0]
        for i in 1...s.count {
            let cur = i < s.count ? s[i] : Int.min
            if cur == prev + 1 {
                prev = cur
                continue
            }
            parts.append(runStart == prev ? "\(runStart)" : "\(runStart)-\(prev)")
            if cur != Int.min {
                runStart = cur
                prev = cur
            }
        }
        return parts.joined(separator: ",")
    }

    private static func append(_ list: inout [Int], _ v: Int) {
        guard v > 0, v <= 60, !list.contains(v) else { return }
        list.append(v)
    }
}
