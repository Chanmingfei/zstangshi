package com.tstc.zstangshi.shared

import kotlin.math.abs

/**
 * 正方教务课表解析（从 Android `data/JwglxtFetcher.kt` 移植）。
 *
 * 移植改动：
 *   - `org.json.JSONObject` → 自带的 [Js]（语义兼容层）
 *   - `Regex` 用 Kotlin 标准库（原本就是 stdlib，无需改）
 *   - `Collections.sort` → `sorted()`
 *   - `String.hashCode()` 保持原样：Kotlin 的 hashCode 在 JVM/Native 上都按同一个
 *     算法计算字符串，所以 colorIndex 初值两端一致（这点专门在探针里断言过一次）
 *
 * 业务语义一概不改：jxb_id 聚合、同名同时段合并、周次解析失败计数。
 */
object KbParser {

    class Result {
        var timetable: Timetable = Timetable()
        var rawRecords: Int = 0
        var courseCount: Int = 0
        var failedWeekExpr: Int = 0
        var mergedDuplicates: Int = 0
        val warnings: MutableList<String> = ArrayList()

        fun ok(): Boolean = courseCount > 0
    }

    fun parse(json: String?): Result {
        val r = Result()
        if (json == null || json.trim().isEmpty()) {
            r.warnings.add("响应为空")
            return r
        }
        val root = Js.parse(json) as? Js.Obj
        if (root == null) {
            r.warnings.add("JSON 解析失败")
            return r
        }

        // 1. 学期信息
        val xsxx = root.optObj("xsxx")
        if (xsxx != null) {
            val xnm = xsxx.optString("XNM", "")
            val xqm = xsxx.optString("XQM", "")
            val xnmc = xsxx.optString("XNMC", "")
            val xqmmc = xsxx.optString("XQMMC", "")
            r.timetable.semester.id = buildSemesterId(xnm, xqm)
            r.timetable.semester.name = xnmc + " 学年第" + xqmmc + "学期"
            r.timetable.semester.totalWeeks = 20
        }

        // 2. 课表记录
        val kbList = root.optArr("kbList")
        if (kbList == null) {
            r.warnings.add("响应中没有 kbList")
            return r
        }
        r.rawRecords = kbList.size

        // 3. 按 jxb_id 聚合；没有 jxb_id 时退化为「课程名+教师」
        val grouped = LinkedHashMap<String, Course>()

        for (i in 0 until kbList.size) {
            val rec = kbList.items[i] as? Js.Obj ?: continue

            val name = clean(rec.optString("kcmc", ""))
            if (name.isEmpty()) continue

            val teacher = clean(rec.optString("xm", ""))
            val jxbId = clean(rec.optString("jxb_id", ""))
            val key = if (jxbId.isNotEmpty()) "jwglxt_$jxbId" else "jwglxt_${name}_$teacher"

            var course = grouped[key]
            if (course == null) {
                course = Course()
                course.key = key
                course.name = name
                course.teacher = teacher
                course.credit = parseDouble(rec.optString("xf", "0"))
                course.category = clean(rec.optString("kcbj", ""))
                course.examType = clean(rec.optString("khfsmc", ""))
                course.source = SOURCE_JWGLXT
                course.colorIndex = abs(key.hashCode()) % 8
                grouped[key] = course
            }

            // 4. 构造时段
            val session = Session()
            session.dayOfWeek = parseInt(rec.optString("xqj", "0"), 1)

            var jcor = clean(rec.optString("jcor", ""))
            if (jcor.isEmpty()) jcor = clean(rec.optString("jc", ""))
            val sections = parseSections(jcor)
            session.startSection = sections[0]
            session.endSection = sections[1]

            var zcd = clean(rec.optString("zcd", ""))
            if (zcd.isEmpty()) zcd = clean(rec.optString("zcmc", ""))
            session.weeksRaw = zcd
            val wr = WeekRange.parse(zcd)
            session.weeks.addAll(wr.weeks)
            if (!wr.ok()) {
                r.failedWeekExpr++
                r.warnings.add("周次解析失败: '$zcd' ($name)")
            }

            session.location = clean(rec.optString("cdmc", ""))
            session.campus = clean(rec.optString("xqmc", ""))
            session.roomType = clean(rec.optString("kclbmc", ""))
            session.teacher = teacher

            if (!containsSession(course, session)) course.sessions.add(session)
        }

        val courses = ArrayList(grouped.values)
        r.mergedDuplicates = mergeOverlaps(courses)
        if (r.mergedDuplicates > 0) {
            r.warnings.add("合并了 " + r.mergedDuplicates + " 个同名同时段的重复时段")
        }
        r.timetable.courses.addAll(courses)
        CourseColorAssigner.assign(r.timetable)
        r.courseCount = r.timetable.courses.size
        return r
    }

    // ---------- 字段清洗 ----------

    /** 清洗 HTML 转义与全角空格。不能对中文做 URL 解码。 */
    fun clean(s: String?): String {
        if (s == null) return ""
        return s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .trim()
    }

    fun parseDouble(s: String?): Double {
        if (s == null) return 0.0
        return s.trim().toDoubleOrNull() ?: 0.0
    }

    fun parseInt(s: String?, def: Int): Int {
        if (s == null) return def
        return s.trim().toIntOrNull() ?: def
    }

    /** 解析节次：`1-2` / `3-4` / 兜底 `1-2节`。 */
    fun parseSections(jcor: String?): IntArray {
        if (jcor.isNullOrEmpty()) return intArrayOf(1, 1)
        val digits = jcor.replace(Regex("[^0-9\\-]"), "")
        if (digits.isEmpty()) return intArrayOf(1, 1)
        val dash = digits.indexOf('-')
        if (dash > 0 && dash < digits.length - 1) {
            var a = parseInt(digits.substring(0, dash), 1)
            var b = parseInt(digits.substring(dash + 1), a)
            if (a > b) { val t = a; a = b; b = t }
            return intArrayOf(a, b)
        }
        val a = parseInt(digits.replace("-", ""), 1)
        return intArrayOf(a, a)
    }

    /** 学期 id：`xnm=2026, xqm=3` → `2026-2027-1`（3/12/16 分别对应 1/2/3 学期）。 */
    fun buildSemesterId(xnm: String, xqm: String): String {
        var year = xnm
        if (year.length == 4) {
            val y = year.toIntOrNull()
            if (y != null) year = "$y-" + (y + 1)
        }
        val term = when (xqm) {
            "3" -> 1
            "12" -> 2
            "16" -> 3
            else -> 1
        }
        return "$year-$term"
    }

    /**
     * 同一门课在同一时段被下发成多条（不同 jxb_id，或周次写法不同）时并成一个时段。
     * 规则：课程名相同 + 星期相同 + 节次相同 + 周次有交集。
     */
    fun mergeOverlaps(courses: MutableList<Course>): Int {
        var merged = 0
        for (i in courses.indices) {
            val a = courses[i]
            for (j in i until courses.size) {
                val b = courses[j]
                if (a.name != b.name) continue
                var si = 0
                while (si < a.sessions.size) {
                    val x = a.sessions[si]
                    val start = if (a === b) si + 1 else 0
                    var sj = start
                    while (sj < b.sessions.size) {
                        val y = b.sessions[sj]
                        if (x.dayOfWeek != y.dayOfWeek || x.startSection != y.startSection ||
                            x.endSection != y.endSection || !intersects(x.weeks, y.weeks)
                        ) {
                            sj++
                            continue
                        }
                        mergeTeachers(x, y, a.teacher, b.teacher)
                        var added = false
                        for (w in y.weeks) {
                            if (!x.weeks.contains(w)) { x.weeks.add(w); added = true }
                        }
                        val sortedWeeks = x.weeks.toList().sorted()
                        x.weeks.clear()
                        x.weeks.addAll(sortedWeeks)
                        if (added && y.weeksRaw.isNotEmpty() && x.weeksRaw != y.weeksRaw) {
                            x.weeksRaw = if (x.weeksRaw.isEmpty()) y.weeksRaw else x.weeksRaw + "," + y.weeksRaw
                        }
                        x.location = joinDistinct(x.location, y.location, " / ")
                        b.sessions.removeAt(sj)
                        merged++
                    }
                    si++
                }
            }
        }
        for (i in courses.indices.reversed()) {
            if (courses[i].sessions.isEmpty()) courses.removeAt(i)
        }
        return merged
    }

    private fun mergeTeachers(x: Session, y: Session, courseTeacherX: String, courseTeacherY: String) {
        val tx = if (x.teacher.isEmpty()) courseTeacherX else x.teacher
        val ty = if (y.teacher.isEmpty()) courseTeacherY else y.teacher
        if (ty.isEmpty() || ty == tx) {
            for ((k, v) in y.weekTeachers) if (!x.weekTeachers.containsKey(k)) x.weekTeachers[k] = v
            return
        }
        if (y.weeks.size <= x.weeks.size || x.weeks.isEmpty()) {
            for (w in y.weeks) x.weekTeachers[w] = ty
        } else {
            for (w in x.weeks) if (!x.weekTeachers.containsKey(w)) x.weekTeachers[w] = tx
            x.teacher = ty
        }
        for ((k, v) in y.weekTeachers) x.weekTeachers[k] = v
    }

    private fun intersects(a: List<Int>, b: List<Int>): Boolean {
        for (w in a) if (b.contains(w)) return true
        return false
    }

    fun joinDistinct(a: String?, b: String?, sep: String): String {
        if (b.isNullOrEmpty()) return a ?: ""
        if (a.isNullOrEmpty()) return b
        for (p in a.split(sep)) if (p == b) return a
        return a + sep + b
    }

    private fun containsSession(c: Course, s: Session): Boolean {
        for (x in c.sessions) {
            if (x.dayOfWeek == s.dayOfWeek &&
                x.startSection == s.startSection &&
                x.endSection == s.endSection &&
                x.weeksRaw == s.weeksRaw
            ) return true
        }
        return false
    }
}
