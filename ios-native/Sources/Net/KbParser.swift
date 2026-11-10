import Foundation

/// 正方教务课表解析（`/kbcx/xskbcx_cxXsKb.html` 返回的 JSON）。
///
/// 真机确认的结构（与 Android 端 `JwglxtFetcher` 同一份结论）：
///   - 课表在 `kbList`，**一条记录 = 一个时段**，不是一门课
///   - 同一门课的多条记录共享 `jxb_id`（教学班 ID）→ 用它聚合
///   - 同一门课不同时段的教室可能不同 → location 挂在 Session 上
///   - `jcor` 形如 "3-4"，`zcd` 形如 "5-12周"
enum KbParser {

    struct Result {
        var timetable = Timetable()
        var rawRecords = 0
        var warnings: [String] = []
        var ok: Bool { !timetable.courses.isEmpty }
    }

    static func parse(_ json: String) -> Result {
        var r = Result()
        guard let data = json.data(using: .utf8) else {
            r.warnings.append("响应无法解码")
            return r
        }
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            r.warnings.append("JSON 解析失败（可能被踢回登录页）")
            return r
        }

        // 1. 学期信息
        var semester = Semester()
        if let xsxx = root["xsxx"] as? [String: Any] {
            let xnm = str(xsxx["XNM"])
            let xqm = str(xsxx["XQM"])
            semester.id = semesterId(xnm: xnm, xqm: xqm)
            semester.name = "\(str(xsxx["XNMC"]))学年第\(str(xsxx["XQMMC"]))学期"
            semester.totalWeeks = 20
        }
        r.timetable.semester = semester

        // 2. 课表记录
        guard let list = root["kbList"] as? [[String: Any]] else {
            r.warnings.append("响应里没有 kbList")
            return r
        }
        r.rawRecords = list.count

        // 3. 按 jxb_id 聚合（没有就用「课名+教师」）
        var order: [String] = []
        var grouped: [String: Course] = [:]

        for rec in list {
            let name = clean(str(rec["kcmc"]))
            if name.isEmpty { continue }
            let teacher = clean(str(rec["xm"]))
            let jxb = clean(str(rec["jxb_id"]))
            let key = jxb.isEmpty ? "jwglxt_\(name)_\(teacher)" : "jwglxt_\(jxb)"

            if grouped[key] == nil {
                var course = Course()
                course.key = key
                course.name = name
                course.teacher = teacher
                course.credit = Double(clean(str(rec["xf"]))) ?? 0
                course.category = clean(str(rec["kcbj"]))
                course.examType = clean(str(rec["khfsmc"]))
                course.source = "jwglxt"
                course.colorIndex = abs(key.hashValue) % 8
                grouped[key] = course
                order.append(key)
            }

            var session = Session()
            session.dayOfWeek = max(1, min(7, Int(clean(str(rec["xqj"]))) ?? 1))
            var jcor = clean(str(rec["jcor"]))
            if jcor.isEmpty { jcor = clean(str(rec["jc"])) }
            let secs = sections(jcor)
            session.startSection = secs.0
            session.endSection = secs.1

            var zcd = clean(str(rec["zcd"]))
            if zcd.isEmpty { zcd = clean(str(rec["zcmc"])) }
            session.weeksRaw = zcd
            let parsed = WeekRange.parse(zcd)
            session.weeks = parsed.weeks
            if !parsed.ok { r.warnings.append("周次解析失败: \(zcd)（\(name)）") }

            session.location = clean(str(rec["cdmc"]))
            session.campus = clean(str(rec["xqmc"]))
            session.roomType = clean(str(rec["kclbmc"]))
            session.teacher = teacher

            if !(grouped[key]?.sessions.contains(where: { same($0, session) }) ?? false) {
                grouped[key]?.sessions.append(session)
            }
        }

        var courses = order.compactMap { grouped[$0] }
        mergeOverlaps(&courses)
        courses.removeAll { $0.sessions.isEmpty }
        r.timetable.courses = courses
        r.timetable.normalizeColors()
        return r
    }

    // MARK: - 字段清洗

    /// 正方返回的文本偶尔带 HTML 转义与全角空格。注意不能对中文做 URL 解码（会乱码）。
    static func clean(_ s: String) -> String {
        s.replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "\u{00A0}", with: " ")
            .replacingOccurrences(of: "\u{3000}", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func str(_ any: Any?) -> String {
        if let s = any as? String { return s }
        if let n = any as? NSNumber { return n.stringValue }
        return ""
    }

    /// "3-4" / "3-4节" / "3" → (3,4)
    static func sections(_ raw: String) -> (Int, Int) {
        let digits = raw.filter { $0.isNumber || $0 == "-" }
        guard !digits.isEmpty else { return (1, 1) }
        let parts = digits.split(separator: "-", omittingEmptySubsequences: true)
        if parts.count >= 2, let a = Int(parts[0]), let b = Int(parts[1]) {
            return (min(a, b), max(a, b))
        }
        if let a = Int(digits.replacingOccurrences(of: "-", with: "")) {
            return (a, a)
        }
        return (1, 1)
    }

    /// 2026 + 3 → "2026-2027-1"；xqm: 3=第一学期, 12=第二学期, 16=第三学期
    static func semesterId(xnm: String, xqm: String) -> String {
        var year = xnm
        if year.count == 4, let y = Int(year) {
            year = "\(y)-\(y + 1)"
        }
        let term: Int
        switch xqm {
        case "12": term = 2
        case "16": term = 3
        default: term = 1
        }
        return "\(year)-\(term)"
    }

    // MARK: - 合并同名同时段的重复下发

    /// 教务偶尔把同一门课在同一时段下发成两条（如「形势与政策」按专题拆教学班）。
    /// 规则：同名 + 同星期 + 同节次 + 周次有交集 → 并成一个时段。
    static func mergeOverlaps(_ courses: inout [Course]) {
        for i in courses.indices {
            for j in courses.indices where j >= i {
                guard courses[i].name == courses[j].name else { continue }
                var sj = 0
                while sj < courses[j].sessions.count {
                    let y = courses[j].sessions[sj]
                    var merged = false
                    var si = 0
                    while si < courses[i].sessions.count {
                        var x = courses[i].sessions[si]
                        let sameSlot = x.dayOfWeek == y.dayOfWeek
                            && x.startSection == y.startSection
                            && x.endSection == y.endSection
                        if sameSlot && i != j && !Set(x.weeks).isDisjoint(with: Set(y.weeks)) {
                            let union = Array(Set(x.weeks).union(y.weeks)).sorted()
                            if union.count > x.weeks.count {
                                x.weeks = union
                                if !y.weeksRaw.isEmpty, x.weeksRaw != y.weeksRaw {
                                    x.weeksRaw = x.weeksRaw.isEmpty ? y.weeksRaw : x.weeksRaw + "," + y.weeksRaw
                                }
                            }
                            if !y.location.isEmpty, !x.location.contains(y.location) {
                                x.location = x.location.isEmpty ? y.location : x.location + " / " + y.location
                            }
                            courses[i].sessions[si] = x
                            merged = true
                        }
                        si += 1
                    }
                    if merged {
                        courses[j].sessions.remove(at: sj)
                    } else {
                        sj += 1
                    }
                }
            }
        }
    }

    private static func same(_ a: Session, _ b: Session) -> Bool {
        a.dayOfWeek == b.dayOfWeek
            && a.startSection == b.startSection
            && a.endSection == b.endSection
            && a.weeksRaw == b.weeksRaw
    }
}
