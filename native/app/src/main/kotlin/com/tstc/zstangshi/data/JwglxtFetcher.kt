package com.tstc.zstangshi.data

import org.json.JSONObject
import java.util.Collections
import java.util.regex.Pattern

/**
 * 正方教务课表数据解析。
 *
 * 输入是 `/jwglxt/kbcx/xskbcx_cxXsKb.html` 返回的 JSON 字符串。
 * 输出是 [Timetable]。
 *
 * 真机确认（2026-10-23）：
 *   - 课表在 kbList 数组，一条记录 = 一个时段（不是一门课）
 *   - 同一门课的多条记录共享 jxb_id（教学班 ID）→ 用它聚合
 *   - 同一门课不同时段的教室可能不同 → location 挂在 Session 上
 *   - jcor 形如 "3-4"，zcd 形如 "5-12周"
 */
object JwglxtFetcher {

    /** 解析结果，带统计信息便于排查 */
    class Result {
        @JvmField var timetable: Timetable = Timetable()
        @JvmField var rawRecords: Int = 0          // kbList 原始条数
        @JvmField var courseCount: Int = 0         // 聚合后的课程数
        @JvmField var failedWeekExpr: Int = 0      // 周次解析失败的条数
        @JvmField var mergedDuplicates: Int = 0    // 同名同时段被合并的重复时段数
        @JvmField var warnings: MutableList<String> = ArrayList()

        fun ok(): Boolean = courseCount > 0
    }

    /**
     * 解析课表接口返回的 JSON。
     *
     * @param json 接口原始响应
     * @return 解析结果；json 无法解析时返回 courseCount=0 的结果，不抛异常
     */
    @JvmStatic
    fun parse(json: String?): Result {
        val r = Result()
        if (json == null || json.trim().isEmpty()) {
            r.warnings.add("响应为空")
            return r
        }

        val root: JSONObject = try {
            JSONObject(json)
        } catch (e: Exception) {
            r.warnings.add("JSON 解析失败: " + e.message)
            return r
        }

        // 1. 学期信息
        val xsxx = root.optJSONObject("xsxx")
        if (xsxx != null) {
            val xnm = xsxx.optString("XNM", "")       // 2026
            val xqm = xsxx.optString("XQM", "")       // 3 / 12
            val xnmc = xsxx.optString("XNMC", "")     // 2026-2027
            val xqmmc = xsxx.optString("XQMMC", "")   // 1 / 2
            r.timetable.semester.id = buildSemesterId(xnm, xqm)
            r.timetable.semester.name = xnmc + " 学年第" + xqmmc + "学期"
            r.timetable.semester.totalWeeks = 20
        }

        // 2. 课表记录
        val kbList = root.optJSONArray("kbList")
        if (kbList == null) {
            r.warnings.add("响应中没有 kbList")
            return r
        }
        r.rawRecords = kbList.length()

        // 3. 按 jxb_id 聚合。没有 jxb_id 时退化为「课程名+教师」
        val grouped = LinkedHashMap<String, Timetable.Course>()

        for (i in 0 until kbList.length()) {
            val rec = kbList.optJSONObject(i) ?: continue

            val name = clean(rec.optString("kcmc", ""))
            if (name.isEmpty()) continue

            val teacher = clean(rec.optString("xm", ""))
            val jxbId = clean(rec.optString("jxb_id", ""))
            val key = if (jxbId.isNotEmpty()) "jwglxt_$jxbId" else "jwglxt_${name}_$teacher"

            var course = grouped[key]
            if (course == null) {
                course = Timetable.Course()
                course.key = key
                course.name = name
                course.teacher = teacher
                course.credit = parseDouble(rec.optString("xf", "0"))
                course.category = clean(rec.optString("kcbj", ""))
                course.examType = clean(rec.optString("khfsmc", ""))
                course.source = Timetable.SOURCE_JWGLXT
                course.colorIndex = Math.abs(key.hashCode()) % 8
                grouped[key] = course
            }

            // 4. 构造时段
            val session = Timetable.Session()
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
                r.warnings.add("周次解析失败: '" + zcd + "' (" + name + ")")
            }

            session.location = clean(rec.optString("cdmc", ""))
            session.campus = clean(rec.optString("xqmc", ""))
            session.roomType = clean(rec.optString("kclbmc", ""))
            session.teacher = teacher

            // 去重：同一课程同一时段可能被重复下发
            if (!containsSession(course, session)) {
                course.sessions.add(session)
            }
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

    // ---------- 字段清洗与转换 ----------

    /**
     * 清洗字段。正方返回的文本偶尔带 HTML 转义与全角空格。
     * 注意：不能对中文做 URL 解码（会导致乱码），只处理明确的转义实体。
     */
    @JvmStatic
    fun clean(s: String?): String {
        if (s == null) return ""
        return s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace('\u00A0', ' ')   // NBSP
            .replace('\u3000', ' ')   // 全角空格
            .trim()
    }

    @JvmStatic
    fun parseDouble(s: String?): Double {
        if (s == null) return 0.0
        return try {
            s.trim().toDouble()
        } catch (e: NumberFormatException) {
            0.0
        }
    }

    @JvmStatic
    fun parseInt(s: String?, def: Int): Int {
        if (s == null) return def
        return try {
            s.trim().toInt()
        } catch (e: NumberFormatException) {
            def
        }
    }

    /**
     * 解析节次。真实格式 `1-2` / `3-4`，兜底格式 `1-2节`。
     * @return [start, end]
     */
    @JvmStatic
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

    @JvmStatic
    fun buildSemesterId(xnm: String, xqm: String): String {
        var year = xnm
        if (year.length == 4) {
            try {
                val y = year.toInt()
                year = "$y-" + (y + 1)
            } catch (ignored: NumberFormatException) {
            }
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
     * 教务偶尔把同一门课在同一时段下发成两条（不同 jxb_id、或同一教学班周次写法不同），
     * 例如「形势与政策」按专题拆成多个教学班。课表上它们是同一节课，不应排成两列冲突。
     * 规则：课程名相同 + 星期相同 + 节次相同 + 周次有交集 → 并成一个时段（周次取并集，
     * 教师/教室不同时拼在一起）。不同名的课真冲突时照常并排显示。
     *
     * @return 被合并掉的时段数
     */
    @JvmStatic
    fun mergeOverlaps(courses: MutableList<Timetable.Course>): Int {
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
                            if (!x.weeks.contains(w)) {
                                x.weeks.add(w)
                                added = true
                            }
                        }
                        Collections.sort(x.weeks)
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

    /**
     * 合并两条记录的教师：周次范围更窄的那条更具体（如「第11周 刘锦春」比「11-14周 夏菲」具体），
     * 它覆盖的周记到 weekTeachers 里；宽的那条作为本时段默认教师。
     */
    private fun mergeTeachers(
        x: Timetable.Session,
        y: Timetable.Session,
        courseTeacherX: String,
        courseTeacherY: String
    ) {
        val tx = if (x.teacher.isEmpty()) courseTeacherX else x.teacher
        val ty = if (y.teacher.isEmpty()) courseTeacherY else y.teacher
        if (ty.isEmpty() || ty == tx) {
            for ((k, v) in y.weekTeachers) {
                x.weekTeachers.putIfAbsent(k, v)
            }
            return
        }
        if (y.weeks.size <= x.weeks.size || x.weeks.isEmpty()) {
            for (w in y.weeks) x.weekTeachers[w] = ty
        } else {
            for (w in x.weeks) x.weekTeachers.putIfAbsent(w, tx)
            x.teacher = ty
        }
        for ((k, v) in y.weekTeachers) {
            x.weekTeachers[k] = v
        }
    }

    private fun intersects(a: List<Int>, b: List<Int>): Boolean {
        for (w in a) if (b.contains(w)) return true
        return false
    }

    @JvmStatic
    fun joinDistinct(a: String?, b: String?, sep: String): String {
        if (b.isNullOrEmpty()) return a ?: ""
        if (a.isNullOrEmpty()) return b
        for (p in a.split(Pattern.quote(sep))) if (p == b) return a
        return a + sep + b
    }

    private fun containsSession(c: Timetable.Course, s: Timetable.Session): Boolean {
        for (x in c.sessions) {
            if (x.dayOfWeek == s.dayOfWeek &&
                x.startSection == s.startSection &&
                x.endSection == s.endSection &&
                x.weeksRaw == s.weeksRaw
            ) {
                return true
            }
        }
        return false
    }
}
