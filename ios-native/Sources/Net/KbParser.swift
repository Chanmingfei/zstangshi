import Foundation

/// 正方教务课表数据解析（对齐 Android `data/JwglxtFetcher.kt`）。
///
/// 输入是 `/kbcx/xskbcx_cxXsKb.html` 返回的 JSON 字符串，输出是 `Timetable`。
///
/// 真机确认（2026-10-23）：
///   · 课表在 kbList 数组，一条记录 = 一个时段（不是一门课）
///   · 同一门课的多条记录共享 jxb_id（教学班 ID）→ 用它聚合
///   · 同一门课不同时段的教室可能不同 → location 挂在 Session 上
///   · jcor 形如 "3-4"，zcd 形如 "5-12周"
enum KbParser {

    struct Result {
        var timetable: Timetable?
        var rawRecords = 0
        var courseCount = 0
        var failedWeekExpr = 0
        var mergedDuplicates = 0
        var warnings: [String] = []

        var ok: Bool { courseCount > 0 }
    }

    /// 解析课表接口返回的 JSON。json 无法解析时返回 courseCount=0 的结果，不抛异常。
    static func parse(_ json: String?) -> Result {
        var r = Result()
        guard let json, !json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            r.warnings.append("响应为空")
            return r
        }
        guard let data = json.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            r.warnings.append("JSON 解析失败：响应不是合法 JSON")
            return r
        }

        var timetable = Timetable()

        // 1. 学期信息
        if let xsxx = root["xsxx"] as? [String: Any] {
            let xnm = str(xsxx["XNM"])       // 2026
            let xqm = str(xsxx["XQM"])       // 3 / 12
            let xnmc = str(xsxx["XNMC"])     // 2026-2027
            let xqmmc = str(xsxx["XQMMC"])   // 1 / 2
            timetable.semester.id = buildSemesterId(xnm: xnm, xqm: xqm)
            timetable.semester.name = xnmc + " 学年第" + xqmmc + "学期"
            timetable.semester.totalWeeks = 20
        }

        // 2. 课表记录（兼容 kbList 在顶层 / 在 data 里两种包装）
        var kbList: [[String: Any]]?
        if let direct = root["kbList"] as? [[String: Any]] {
            kbList = direct
        } else if let dataObj = root["data"] as? [String: Any],
                  let nested = dataObj["kbList"] as? [[String: Any]] {
            kbList = nested
        }
        guard let kbList else {
            r.warnings.append("响应中没有 kbList（教务可能改版）")
            return r
        }
        r.rawRecords = kbList.count

        // 3. 按 jxb_id 聚合。没有 jxb_id 时退化为「课程名+教师」
        var grouped: [String: Course] = [:]
        var order: [String] = []

        for rec in kbList {
            let name = clean(str(rec["kcmc"]))
            if name.isEmpty { continue }

            let teacher = clean(str(rec["xm"]))
            let jxbId = clean(str(rec["jxb_id"]))
            let key = jxbId.isEmpty ? "jwglxt_\(name)_\(teacher)" : "jwglxt_\(jxbId)"

            var course: Course
            if let existing = grouped[key] {
                course = existing
            } else {
                var c = Course()
                c.key = key
                c.name = name
                c.teacher = teacher
                c.credit = str(rec["xf"]).doubleValue
                c.category = clean(str(rec["kcbj"]))
                c.examType = clean(str(rec["khfsmc"]))
                c.source = Timetable.sourceJwglxt
                c.colorIndex = abs(stableHash(key)) % 8
                grouped[key] = c
                order.append(key)
                course = c
            }

            // 4. 构造时段
            var session = Session()
            session.dayOfWeek = str(rec["xqj"]).intValueOr(1)

            var jcor = clean(str(rec["jcor"]))
            if jcor.isEmpty { jcor = clean(str(rec["jc"])) }
            let sections = parseSections(jcor)
            session.startSection = sections.0
            session.endSection = sections.1

            var zcd = clean(str(rec["zcd"]))
            if zcd.isEmpty { zcd = clean(str(rec["zcmc"])) }
            session.weeksRaw = zcd
            let wr = WeekRange.parse(zcd)
            session.weeks = wr.weeks
            if !wr.ok {
                r.failedWeekExpr += 1
                r.warnings.append("周次解析失败: '\(zcd)' (\(name))")
            }

            session.location = clean(str(rec["cdmc"]))
            session.campus = clean(str(rec["xqmc"]))
            session.roomType = clean(str(rec["kclbmc"]))
            session.teacher = teacher

            // 去重：同一课程同一时段可能被重复下发
            if !course.sessions.contains(where: {
                $0.dayOfWeek == session.dayOfWeek
                    && $0.startSection == session.startSection
                    && $0.endSection == session.endSection
                    && $0.weeksRaw == session.weeksRaw
            }) {
                course.sessions.append(session)
            }
            grouped[key] = course
        }

        var courses = order.compactMap { grouped[$0] }
        let merged = mergeOverlaps(&courses)
        r.mergedDuplicates = merged
        if merged > 0 {
            r.warnings.append("合并了 \(merged) 个同名同时段的重复时段")
        }
        timetable.courses = courses
        CourseColorAssigner.assign(&timetable)

        r.timetable = timetable
        r.courseCount = timetable.courses.count
        return r
    }

    // MARK: - 字段清洗与转换

    /// 清洗字段。正方返回的文本偶尔带 HTML 转义与全角空格。
    /// 注意：不能对中文做 URL 解码（会导致乱码），只处理明确的转义实体。
    static func clean(_ s: String?) -> String {
        guard let s else { return "" }
        return s
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "\u{00A0}", with: " ")   // NBSP
            .replacingOccurrences(of: "\u{3000}", with: " ")   // 全角空格
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 解析节次。真实格式 `1-2` / `3-4`，兜底格式 `1-2节`。
    static func parseSections(_ jcor: String?) -> (Int, Int) {
        guard let jcor, !jcor.isEmpty else { return (1, 1) }
        let digits = jcor.replacingOccurrences(of: "[^0-9\\-]", with: "", options: .regularExpression)
        guard !digits.isEmpty else { return (1, 1) }
        if let dash = digits.firstIndex(of: "-"), dash != digits.startIndex,
           digits.index(after: dash) != digits.endIndex {
            var a = Int(digits[digits.startIndex..<dash]) ?? 1
            var b = Int(digits[digits.index(after: dash)...]) ?? a
            if a > b { swap(&a, &b) }
            return (a, b)
        }
        let a = Int(digits.replacingOccurrences(of: "-", with: "")) ?? 1
        return (a, a)
    }

    static func buildSemesterId(xnm: String, xqm: String) -> String {
        var year = xnm
        if year.count == 4, let y = Int(year) {
            year = "\(y)-\(y + 1)"
        }
        let term: Int
        switch xqm {
        case "3": term = 1
        case "12": term = 2
        case "16": term = 3
        default: term = 1
        }
        return "\(year)-\(term)"
    }

    /// 教务偶尔把同一门课在同一时段下发成两条（不同 jxb_id、或同一教学班周次写法不同），
    /// 例如「形势与政策」按专题拆成多个教学班。课表上它们是同一节课，不应排成两列冲突。
    ///
    /// 规则：课程名相同 + 星期相同 + 节次相同 + 周次有交集 → 并成一个时段
    /// （周次取并集，教师/教室不同时拼在一起）。不同名的课真冲突时照常并排显示。
    /// - Returns: 被合并掉的时段数
    @discardableResult
    static func mergeOverlaps(_ courses: inout [Course]) -> Int {
        var merged = 0
        guard courses.count >= 1 else { return 0 }

        // 逐对课程处理。注意必须**每次都从数组里重新读** x，
        // 因为上一轮合并可能已经改写了 courses[i].sessions[si]（周次并集）。
        for i in courses.indices {
            for j in i..<courses.count {
                guard courses[i].name == courses[j].name else { continue }

                var si = 0
                while si < courses[i].sessions.count {
                    let start = (i == j) ? si + 1 : 0
                    var sj = start
                    while sj < courses[j].sessions.count {
                        // 关键：每一轮都重新读 x（可能已被前一轮合并更新）
                        let x = courses[i].sessions[si]
                        let y = courses[j].sessions[sj]
                        // 同名 + 同星期 + 同节次 → 就是「同一节课的不同周次片段」。
                        // 不要要求周次有交集：「形势与政策」常被拆成 第11周/第12周 两个教学班
                        // 分别下发，交集为空但它们在同一格上课，必须合成一格（周次取并集）。
                        // （安卓 JwglxtFetcher.mergeOverlaps 的原始实现要求 intersect，
                        //   但那只对「同一教学班重复下发」有效，漏掉了按专题拆分的情形；
                        //   这里放宽为「同格即合并」，与用户在课表上看到的一格一课时一致。）
                        guard x.dayOfWeek == y.dayOfWeek,
                              x.startSection == y.startSection,
                              x.endSection == y.endSection else {
                            sj += 1
                            continue
                        }

                        var newX = x
                        mergeTeachers(&newX, y,
                                      courseTeacherX: courses[i].teacher,
                                      courseTeacherY: courses[j].teacher)
                        var added = false
                        for w in y.weeks where !newX.weeks.contains(w) {
                            newX.weeks.append(w)
                            added = true
                        }
                        newX.weeks.sort()
                        if added && !y.weeksRaw.isEmpty && newX.weeksRaw != y.weeksRaw {
                            newX.weeksRaw = newX.weeksRaw.isEmpty ? y.weeksRaw : newX.weeksRaw + "," + y.weeksRaw
                        }
                        newX.location = joinDistinct(newX.location, y.location, " / ")
                        courses[i].sessions[si] = newX
                        courses[j].sessions.remove(at: sj)
                        merged += 1
                        // sj 不递增：原 sj 位置被后一条顶上来了
                    }
                    si += 1
                }
            }
        }
        courses.removeAll { $0.sessions.isEmpty }
        return merged
    }

    /// 合并两条记录的教师：周次范围更窄的那条更具体
    /// （如「第11周 刘锦春」比「11-14周 夏菲」具体），它覆盖的周记到 weekTeachers 里；
    /// 宽的那条作为本时段默认教师。
    private static func mergeTeachers(_ x: inout Session, _ y: Session,
                                      courseTeacherX: String, courseTeacherY: String) {
        let tx = x.teacher.isEmpty ? courseTeacherX : x.teacher
        let ty = y.teacher.isEmpty ? courseTeacherY : y.teacher
        if ty.isEmpty || ty == tx {
            for (k, v) in y.weekTeachers where x.weekTeachers[k] == nil {
                x.weekTeachers[k] = v
            }
            return
        }
        if y.weeks.count <= x.weeks.count || x.weeks.isEmpty {
            for w in y.weeks { x.weekTeachers[String(w)] = ty }
        } else {
            for w in x.weeks where x.weekTeachers[String(w)] == nil {
                x.weekTeachers[String(w)] = tx
            }
            x.teacher = ty
        }
        for (k, v) in y.weekTeachers { x.weekTeachers[k] = v }
    }

    private static func intersects(_ a: [Int], _ b: [Int]) -> Bool {
        a.contains { b.contains($0) }
    }

    static func joinDistinct(_ a: String?, _ b: String?, _ sep: String) -> String {
        let aa = a ?? ""
        guard let b, !b.isEmpty else { return aa }
        if aa.isEmpty { return b }
        if aa.components(separatedBy: sep).contains(b) { return aa }
        return aa + sep + b
    }

    // MARK: - 工具

    /// 稳定哈希：Swift 的 `String.hashValue` 每次启动都会变（随机种子），
    /// 用它当颜色种子会导致「同一次导入，重启后课程颜色全变了」。
    /// 这里用 FNV-1a，跨进程稳定。
    static func stableHash(_ s: String) -> Int {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in s.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x100000001b3
        }
        return Int(bitPattern: UInt(hash & 0x7FFF_FFFF))
    }

    /// JSONSerialization 会把数字读成 NSNumber，字符串字段可能混着数字。
    private static func str(_ v: Any?) -> String {
        switch v {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        case let b as Bool: return b ? "true" : "false"
        default: return ""
        }
    }
}

// MARK: - 字符串 → 数字的小工具

private extension String {
    var doubleValue: Double { Double(trimmingCharacters(in: .whitespaces)) ?? 0 }

    func intValueOr(_ def: Int) -> Int { Int(trimmingCharacters(in: .whitespaces)) ?? def }
}