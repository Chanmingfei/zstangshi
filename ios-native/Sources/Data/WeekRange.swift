import Foundation

/// 正方教务的周次表达式解析（严格对齐 Android `data/WeekRange.kt`）。
///
/// 真实样本：`5-12周`、`10-13周`、`9-16周`
/// 必须兼容但本机未出现：
///   `1-5周,7-12周`   多区间
///   `1-16周(单)` / `1-16周(双)`   单双周
///   `1,3,5,7周`      离散周
///   `3周`            单周
///   `1-16周(单周)` / `1-16周(双周)`
///   `1-16`           无「周」字
///
/// 解析失败不抛异常：返回空 weeks 表示「无法判断」，由上层决定降级策略
/// （保底按全周显示，避免课程凭空消失）。
enum WeekRange {

    struct Parsed {
        /// 周次列表，升序去重。解析失败时为空
        let weeks: [Int]
        /// 原文是否含「单」/「双」标记
        let hasParity: Bool
        /// 单周 true / 双周 false / 无标记 nil
        let oddOnly: Bool?
        /// 原始表达式
        let raw: String

        var ok: Bool { !weeks.isEmpty }
        func contains(_ week: Int) -> Bool { weeks.contains(week) }
        var minWeek: Int { weeks.first ?? 0 }
        var maxWeek: Int { weeks.last ?? 0 }
    }

    static func parse(_ expr: String?) -> Parsed {
        let raw = (expr ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return Parsed(weeks: [], hasParity: false, oddOnly: nil, raw: raw) }

        // 1. 检测单双周标记（必须在剥离中文数字前做）
        var oddOnly: Bool?
        var hasParity = false
        if raw.contains("单") {
            hasParity = true
            oddOnly = true
        } else if raw.contains("双") {
            hasParity = true
            oddOnly = false
        }

        // 2. 剥离所有非数字/非分隔符字符
        //    保留：数字、'-'（区间连接）、','（多区间分隔）
        //    统一：全角逗号/顿号 → ','
        //    丢弃：周、()、（）、单、双、空格
        var cleaned = ""
        for ch in raw {
            if ch.isASCII && ch.isNumber {
                cleaned.append(ch)
            } else if ch == "-" {
                cleaned.append(ch)
            } else if ch == "," || ch == "，" || ch == "、" {
                cleaned.append(",")
            }
        }
        guard !cleaned.isEmpty else {
            return Parsed(weeks: [], hasParity: hasParity, oddOnly: oddOnly, raw: raw)
        }

        // 3. 按逗号切段，每段可能是 "a-b" 或 "a"
        var result: [Int] = []
        for part in cleaned.split(separator: ",", omittingEmptySubsequences: false) {
            let p = String(part)
            if p.isEmpty { continue }
            if let dashIdx = p.firstIndex(of: "-"),
               dashIdx != p.startIndex,
               p.index(after: dashIdx) != p.endIndex {
                // 区间 a-b
                guard var a = Int(p[p.startIndex..<dashIdx]),
                      var b = Int(p[p.index(after: dashIdx)...]) else { continue }
                if a > b { swap(&a, &b) }        // 容错：写反了也认
                if b - a > 60 { continue }        // 异常区间，丢弃
                var w = a
                while w <= b { addDistinct(&result, w); w += 1 }
            } else {
                // 单值 a，或形如 "5-" / "-5" 的残缺写法
                if let a = Int(p.replacingOccurrences(of: "-", with: "")), a > 0, a <= 60 {
                    addDistinct(&result, a)
                }
            }
        }

        // 4. 应用单双周过滤
        if let oddOnly {
            result = result.filter { w in
                let isOdd = w % 2 == 1
                return oddOnly ? isOdd : !isOdd
            }
        }

        // 5. 排序去重
        result = sortedDistinct(result)
        return Parsed(weeks: result, hasParity: hasParity, oddOnly: oddOnly, raw: raw)
    }

    /// 便捷方法：判断某周是否上课。解析失败时返回 true（保底显示，避免课程消失）
    static func isActive(_ expr: String?, week: Int) -> Bool {
        let p = parse(expr)
        return p.ok ? p.contains(week) : true
    }

    /// 把周次列表压回区间表达式，用于导入导出时的紧凑表示
    static func compress(_ weeks: [Int]?) -> String {
        guard let weeks, !weeks.isEmpty else { return "" }
        let s = sortedDistinct(weeks)
        var parts: [String] = []
        var runStart = s[0]
        var prev = runStart
        var i = 1
        while i <= s.count {
            let cur: Int? = i < s.count ? s[i] : nil
            if let cur, cur == prev + 1 {
                prev = cur
                i += 1
                continue
            }
            // 收尾一个区间
            if runStart == prev {
                parts.append("\(runStart)")
            } else {
                parts.append("\(runStart)-\(prev)")
            }
            if let cur {
                runStart = cur
                prev = cur
            }
            i += 1
        }
        return parts.joined(separator: ",")
    }

    // MARK: - 私有

    private static func addDistinct(_ list: inout [Int], _ v: Int) {
        guard v > 0, v <= 60 else { return }
        if !list.contains(v) { list.append(v) }
    }

    private static func sortedDistinct(_ input: [Int]) -> [Int] {
        var seen = Set<Int>()
        var out: [Int] = []
        for v in input where seen.insert(v).inserted { out.append(v) }
        out.sort()
        return out
    }
}