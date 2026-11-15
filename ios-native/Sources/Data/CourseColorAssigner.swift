import Foundation

/// 按规范化课程名统一分配色板索引（对齐 Android `data/CourseColorAssigner.kt`）。
///
/// 同名课程（不同时段/不同周次）拿到同一颜色；不同课程名在同一学期内
/// 绝不撞色（色板 24 色，超过 24 门不同课才不得不复用）。手动课程和用户
/// 改过颜色的课程保持原色，并且算作「已占用」，自动分配会绕开它们。
///
/// 注意：色板有 24 个索引，但 UI 只用前 8 个主色（course_0..7）。
/// 这与安卓一致 —— 索引 8..23 会按 `% 8` 落到同一个色块上，
/// 但 CourseColorAssigner 的 24 格分配保证了「8 门以内绝不撞色」，
/// 其余靠色块内的文字与位置区分。
enum CourseColorAssigner {

    /// 与 Android PALETTE_SIZE 一致；数据层不依赖 UI 类
    static let paletteSize = 24

    // MARK: - 公开入口

    /// 对课表内全部课程重新分配 colorIndex。
    /// - Returns: 映射（课程名 → 颜色索引），供调用方把同一配色同步到其他学期。
    @discardableResult
    static func assign(_ timetable: inout Timetable) -> [String: Int] {
        var out: [String: Int] = [:]
        var used = [Bool](repeating: false, count: paletteSize)
        let fixed = fixedColors(timetable, used: &used)

        // 和手动设过颜色的同名课保持一致
        for (name, color) in fixed {
            for c in timetable.courses where isAuto(c) && normalizeName(c.name) == name {
                out[name] = color
            }
        }

        // 第一轮：已有颜色、而且这个颜色在本学期还没被别的课名占用 → 保持原色不动。
        // 这样同步回来的课程顺序变了、增删了一门课，其余课的颜色都不会跟着挪。
        for course in timetable.courses where isAuto(course) {
            let name = normalizeName(course.name)
            if out[name] != nil { continue }
            let idx = abs(course.colorIndex) % paletteSize
            if !used[idx] {
                used[idx] = true
                out[name] = idx
            }
        }

        // 第二轮：撞色的课名换到第一个空闲颜色
        for i in timetable.courses.indices where isAuto(timetable.courses[i]) {
            let name = normalizeName(timetable.courses[i].name)
            var idx = out[name]
            if idx == nil {
                idx = firstFree(used, name)
                used[idx!] = true
                out[name] = idx
            }
            timetable.courses[i].colorIndex = idx!
        }
        return out
    }

    /// 新建手动课程时的默认颜色：本学期还没人用的第一个颜色。
    static func firstFree(_ timetable: Timetable?) -> Int {
        var used = [Bool](repeating: false, count: paletteSize)
        if let courses = timetable?.courses {
            for c in courses { used[abs(c.colorIndex) % paletteSize] = true }
        }
        let n = timetable?.courses.count ?? 0
        return firstFree(used, "#\(n)")
    }

    /// 同一学期里不同课程名撞色时，把后出现的自动课程挪到空闲颜色上。
    static func dedupe(_ timetable: inout Timetable) {
        var used = [Bool](repeating: false, count: paletteSize)
        var owner: [Int: String] = [:]
        let fixed = fixedColors(timetable, used: &used)
        for (name, color) in fixed where owner[color] == nil { owner[color] = name }

        var decided: [String: Int] = fixed
        for i in timetable.courses.indices where isAuto(timetable.courses[i]) {
            let name = normalizeName(timetable.courses[i].name)
            var idx = decided[name]
            if idx == nil {
                let want = abs(timetable.courses[i].colorIndex) % paletteSize
                let who = owner[want]
                let chosen: Int
                if who == nil || who == name {
                    chosen = want
                } else {
                    chosen = firstFree(used, name)
                }
                used[chosen] = true
                if owner[chosen] == nil { owner[chosen] = name }
                decided[name] = chosen
                idx = chosen
            }
            timetable.courses[i].colorIndex = idx!
        }
    }

    /// 把已知的课程名配色应用到一个学期，未命中的名字保持原样。
    static func applyKnown(_ timetable: inout Timetable, knownColors: [String: Int]?) {
        guard let knownColors else { return }
        for i in timetable.courses.indices {
            guard timetable.courses[i].source == Timetable.sourceJwglxt else { continue }
            guard !timetable.courses[i].colorTouched else { continue }
            let key = normalizeName(timetable.courses[i].name)
            if let idx = knownColors[key] { timetable.courses[i].colorIndex = idx }
        }
        dedupe(&timetable)
    }

    /// 规范化课程名：去 NBSP、全角空格、所有空白，再 trim。
    static func normalizeName(_ name: String?) -> String {
        guard let name else { return "" }
        var s = name.replacingOccurrences(of: "\u{00A0}", with: " ")
                    .replacingOccurrences(of: "\u{3000}", with: " ")
        s = s.replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespaces)
    }

    // MARK: - 私有

    /// 是否「可自动分配」：教务来源、且用户没手动改过颜色。
    private static func isAuto(_ c: Course) -> Bool {
        c.source == Timetable.sourceJwglxt && !c.colorTouched
    }

    /// 手动课程 / 用户改过色的课程：颜色不动，标记为已占用。
    /// - Returns: 课程名 → 颜色
    private static func fixedColors(_ t: Timetable, used: inout [Bool]) -> [String: Int] {
        var fixed: [String: Int] = [:]
        for c in t.courses where !isAuto(c) {
            let idx = abs(c.colorIndex) % paletteSize
            used[idx] = true
            let name = normalizeName(c.name)
            if fixed[name] == nil { fixed[name] = idx }
        }
        return fixed
    }

    private static func firstFree(_ used: [Bool], _ name: String) -> Int {
        for i in used.indices where !used[i] { return i }
        // 24 色全占满：用课程名哈希兜底，保证同一门课永远同色
        return abs(name.unicodeScalars.reduce(0) { $0 &+ Int($1.value) }) % used.count
    }
}