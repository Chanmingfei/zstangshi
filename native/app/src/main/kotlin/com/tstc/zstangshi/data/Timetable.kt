package com.tstc.zstangshi.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.TreeMap

/**
 * 课表数据模型。
 *
 * 结构对应 docs/DATA-SCHEMA.md 的 timetable.json：
 *   Course（课程）1 ── N Session（时段）
 *
 * 关键设计：
 *   - 一门课可有多个时段（地球化学周一/周三/周五），时段各自有教室
 *   - Course.key 是聚合主键：教务课程用 jxb_id，手动添加用 custom_xxx
 *   - source 区分来源，同步时 custom 课程永不删除
 */
class Timetable {

    /** 学期信息 */
    class Semester {
        @JvmField var id: String = ""          // 2026-2027-1
        @JvmField var name: String = ""        // 2026-2027 学年第一学期
        @JvmField var startDate: String = ""   // 2026-09-01（第 1 周周一），可为空
        @JvmField var totalWeeks: Int = 20
        @JvmField var startDateTouched: Boolean = false // 用户手动改过开学日期
        @JvmField var sortOrder: Int = 0       // 归档内显示顺序（用户拖动后写入）

        constructor()

        constructor(id: String, name: String, startDate: String, totalWeeks: Int) {
            this.id = id
            this.name = name
            this.startDate = startDate
            this.totalWeeks = totalWeeks
        }

        @Throws(JSONException::class)
        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("id", id)
            o.put("name", name)
            o.put("startDate", startDate)
            o.put("totalWeeks", totalWeeks)
            o.put("startDateTouched", startDateTouched)
            o.put("sortOrder", sortOrder)
            return o
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): Semester {
                val s = Semester()
                s.id = o.optString("id", "")
                s.name = o.optString("name", "")
                s.startDate = o.optString("startDate", "")
                s.totalWeeks = o.optInt("totalWeeks", 20)
                s.startDateTouched = o.optBoolean("startDateTouched", false)
                s.sortOrder = o.optInt("sortOrder", 0)
                return s
            }
        }
    }

    /** 一个上课时段 */
    class Session {
        @JvmField var dayOfWeek: Int = 1        // 1=周一 … 7=周日
        @JvmField var startSection: Int = 1     // 起始节次
        @JvmField var endSection: Int = 1       // 结束节次（含）
        @JvmField var weeks: MutableList<Int> = ArrayList()
        @JvmField var weeksRaw: String = ""     // 原始表达式，保留以便排查
        @JvmField var location: String = ""     // 教室（每个时段可能不同）
        @JvmField var campus: String = ""       // 校区
        @JvmField var roomType: String = ""     // 场地类别

        /** 本时段教师；空串表示沿用课程教师。 */
        @JvmField var teacher: String = ""

        /** 按周换老师（如形势与政策每周一个主讲）：周次 → 教师，优先于 teacher。 */
        @JvmField var weekTeachers: MutableMap<Int, String> = TreeMap()

        /** 自定义起止时间 HH:mm（如考试 14:30–16:30）；都为空时按节次画。 */
        @JvmField var timeStart: String = ""
        @JvmField var timeEnd: String = ""

        constructor()

        /** 有合法的自定义时间（开始早于结束）。 */
        fun hasCustomTime(): Boolean {
            val a = minutesOf(timeStart)
            val b = minutesOf(timeEnd)
            return a >= 0 && b > a
        }

        /** 这一周显示哪位老师：按周 > 本时段 > 课程。 */
        fun teacherFor(week: Int, courseTeacher: String?): String {
            val w = weekTeachers[week]
            if (!w.isNullOrEmpty()) return w
            if (teacher.isNotEmpty()) return teacher
            return courseTeacher ?: ""
        }

        fun copy(): Session {
            val s = Session()
            s.dayOfWeek = dayOfWeek
            s.startSection = startSection
            s.endSection = endSection
            s.weeks.addAll(weeks)
            s.weeksRaw = weeksRaw
            s.location = location
            s.campus = campus
            s.roomType = roomType
            s.teacher = teacher
            s.weekTeachers.putAll(weekTeachers)
            s.timeStart = timeStart
            s.timeEnd = timeEnd
            return s
        }

        @Throws(JSONException::class)
        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("dayOfWeek", dayOfWeek)
            o.put("startSection", startSection)
            o.put("endSection", endSection)
            val wa = JSONArray()
            for (w in weeks) wa.put(w)
            o.put("weeks", wa)
            o.put("weeksRaw", weeksRaw)
            o.put("location", location)
            o.put("campus", campus)
            o.put("roomType", roomType)
            if (teacher.isNotEmpty()) o.put("teacher", teacher)
            if (weekTeachers.isNotEmpty()) {
                val wt = JSONObject()
                for ((k, v) in weekTeachers) wt.put(k.toString(), v)
                o.put("weekTeachers", wt)
            }
            if (hasCustomTime()) {
                o.put("timeStart", timeStart)
                o.put("timeEnd", timeEnd)
            }
            return o
        }

        /** 该时段是否在某周上课 */
        fun isActive(week: Int): Boolean {
            if (weeks.isEmpty()) return true // 周次未解析出来时保底显示
            return weeks.contains(week)
        }

        companion object {
            /** "HH:mm" → 当天分钟数；格式不对返回 -1。 */
            @JvmStatic
            fun minutesOf(hm: String?): Int {
                if (hm == null) return -1
                val s = hm.trim()
                val c = s.indexOf(':')
                if (c <= 0) return -1
                return try {
                    val h = s.substring(0, c).toInt()
                    val m = s.substring(c + 1).toInt()
                    if (h < 0 || h > 23 || m < 0 || m > 59) return -1
                    h * 60 + m
                } catch (e: NumberFormatException) {
                    -1
                }
            }

            @JvmStatic
            fun formatHm(minutes: Int): String =
                String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

            @JvmStatic
            fun fromJson(o: JSONObject): Session {
                val s = Session()
                s.dayOfWeek = o.optInt("dayOfWeek", 1)
                s.startSection = o.optInt("startSection", 1)
                s.endSection = o.optInt("endSection", s.startSection)
                s.weeksRaw = o.optString("weeksRaw", "")
                val wa = o.optJSONArray("weeks")
                if (wa != null) {
                    for (i in 0 until wa.length()) s.weeks.add(wa.optInt(i))
                }
                if (s.weeks.isEmpty() && s.weeksRaw.isNotEmpty()) {
                    s.weeks.addAll(WeekRange.parse(s.weeksRaw).weeks)
                }
                s.location = o.optString("location", "")
                s.campus = o.optString("campus", "")
                s.roomType = o.optString("roomType", "")
                s.teacher = o.optString("teacher", "")
                val wt = o.optJSONObject("weekTeachers")
                if (wt != null) {
                    val it = wt.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        try {
                            s.weekTeachers[k.toInt()] = wt.optString(k, "")
                        } catch (ignored: NumberFormatException) {
                        }
                    }
                }
                s.timeStart = o.optString("timeStart", "")
                s.timeEnd = o.optString("timeEnd", "")
                return s
            }
        }
    }

    /** 一门课程 */
    class Course {
        @JvmField var key: String = ""          // 聚合主键（jxb_id 或 custom_xxx）
        @JvmField var name: String = ""
        @JvmField var teacher: String = ""
        @JvmField var credit: Double = 0.0
        @JvmField var category: String = ""     // 主修 / 辅修 …
        @JvmField var examType: String = ""     // 正常考试 / 考查 …
        @JvmField var colorIndex: Int = 0       // 0-7，色板索引
        @JvmField var colorTouched: Boolean = false // 用户在编辑器里手动选过颜色
        @JvmField var source: String = SOURCE_JWGLXT
        @JvmField var note: String = ""         // 用户备注
        @JvmField var sessions: MutableList<Session> = ArrayList()

        constructor()

        @Throws(JSONException::class)
        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("key", key)
            o.put("name", name)
            o.put("teacher", teacher)
            o.put("credit", credit)
            o.put("category", category)
            o.put("examType", examType)
            o.put("colorIndex", colorIndex)
            o.put("colorTouched", colorTouched)
            o.put("source", source)
            o.put("note", note)
            val sa = JSONArray()
            for (s in sessions) sa.put(s.toJson())
            o.put("sessions", sa)
            return o
        }

        /** 该课程在某周是否上课 */
        fun isActive(week: Int): Boolean {
            for (s in sessions) {
                if (s.isActive(week)) return true
            }
            return false
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): Course {
                val c = Course()
                c.key = o.optString("key", "")
                c.name = o.optString("name", "")
                c.teacher = o.optString("teacher", "")
                c.credit = o.optDouble("credit", 0.0)
                c.category = o.optString("category", "")
                c.examType = o.optString("examType", "")
                c.colorIndex = o.optInt("colorIndex", 0)
                c.colorTouched = o.optBoolean("colorTouched", false)
                c.source = o.optString("source", SOURCE_JWGLXT)
                c.note = o.optString("note", "")
                val sa = o.optJSONArray("sessions")
                if (sa != null) {
                    for (i in 0 until sa.length()) {
                        val so = sa.optJSONObject(i)
                        if (so != null) c.sessions.add(Session.fromJson(so))
                    }
                }
                return c
            }
        }
    }

    /**
     * 单次调整（不改课程本身）：
     *   cancel  某周某天某节停一次课（start=0 表示那天这门课全停）
     *   move    把某次课调到另一周/天/节（或自定义时间），原位置显示「已调走」
     *   off     某天放假（date），当天课程置灰
     *   makeup  某天补课（date），上 fromDate 那天的课
     */
    class Adjust {
        @JvmField var id: String = ""
        @JvmField var type: String = CANCEL
        @JvmField var name: String = ""        // 课程名（cancel/move）
        @JvmField var week: Int = 0
        @JvmField var day: Int = 0
        @JvmField var start: Int = 0
        @JvmField var end: Int = 0
        @JvmField var toWeek: Int = 0
        @JvmField var toDay: Int = 0
        @JvmField var toStart: Int = 0
        @JvmField var toEnd: Int = 0
        @JvmField var toTimeStart: String = ""
        @JvmField var toTimeEnd: String = ""
        @JvmField var toLocation: String = ""
        @JvmField var date: String = ""        // off/makeup：yyyy-MM-dd
        @JvmField var fromDate: String = ""    // makeup：上哪天的课
        @JvmField var note: String = ""

        fun hasToTime(): Boolean {
            val a = Session.minutesOf(toTimeStart)
            val b = Session.minutesOf(toTimeEnd)
            return a >= 0 && b > a
        }

        /** 是否同一件事（去重用，忽略 id/note）。 */
        fun sameAs(o: Adjust): Boolean = type == o.type && name == o.name && week == o.week &&
            day == o.day && start == o.start && end == o.end && toWeek == o.toWeek &&
            toDay == o.toDay && toStart == o.toStart && toEnd == o.toEnd &&
            toTimeStart == o.toTimeStart && toTimeEnd == o.toTimeEnd && date == o.date &&
            fromDate == o.fromDate

        @Throws(JSONException::class)
        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("id", id)
            o.put("type", type)
            if (name.isNotEmpty()) o.put("name", name)
            if (week != 0) o.put("week", week)
            if (day != 0) o.put("day", day)
            if (start != 0) o.put("start", start)
            if (end != 0) o.put("end", end)
            if (toWeek != 0) o.put("toWeek", toWeek)
            if (toDay != 0) o.put("toDay", toDay)
            if (toStart != 0) o.put("toStart", toStart)
            if (toEnd != 0) o.put("toEnd", toEnd)
            if (toTimeStart.isNotEmpty()) o.put("toTimeStart", toTimeStart)
            if (toTimeEnd.isNotEmpty()) o.put("toTimeEnd", toTimeEnd)
            if (toLocation.isNotEmpty()) o.put("toLocation", toLocation)
            if (date.isNotEmpty()) o.put("date", date)
            if (fromDate.isNotEmpty()) o.put("fromDate", fromDate)
            if (note.isNotEmpty()) o.put("note", note)
            return o
        }

        companion object {
            const val CANCEL = "cancel"
            const val MOVE = "move"
            const val OFF = "off"
            const val MAKEUP = "makeup"

            @JvmStatic
            fun fromJson(o: JSONObject): Adjust {
                val a = Adjust()
                a.id = o.optString("id", "")
                a.type = o.optString("type", CANCEL)
                a.name = o.optString("name", "")
                a.week = o.optInt("week")
                a.day = o.optInt("day")
                a.start = o.optInt("start")
                a.end = o.optInt("end")
                a.toWeek = o.optInt("toWeek")
                a.toDay = o.optInt("toDay")
                a.toStart = o.optInt("toStart")
                a.toEnd = o.optInt("toEnd")
                a.toTimeStart = o.optString("toTimeStart", "")
                a.toTimeEnd = o.optString("toTimeEnd", "")
                a.toLocation = o.optString("toLocation", "")
                a.date = o.optString("date", "")
                a.fromDate = o.optString("fromDate", "")
                a.note = o.optString("note", "")
                return a
            }
        }
    }

    @JvmField var semester: Semester = Semester()
    @JvmField var courses: MutableList<Course> = ArrayList()

    /** 停课/调课/放假/补课，跟着学期走，同步教务时保留。 */
    @JvmField var adjusts: MutableList<Adjust> = ArrayList()

    constructor()

    @Throws(JSONException::class)
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("schemaVersion", SCHEMA_VERSION)
        o.put("semester", semester.toJson())
        val ca = JSONArray()
        for (c in courses) ca.put(c.toJson())
        o.put("courses", ca)
        if (adjusts.isNotEmpty()) {
            val aa = JSONArray()
            for (a in adjusts) aa.put(a.toJson())
            o.put("adjusts", aa)
        }
        return o
    }

    /** 按课程名查（调试用） */
    fun findByName(name: String): Course? {
        for (c in courses) {
            if (name == c.name) return c
        }
        return null
    }

    /** 指定周、指定星期的所有时段 */
    fun coursesOn(dayOfWeek: Int, week: Int): MutableList<Course> {
        val out = ArrayList<Course>()
        for (c in courses) {
            for (s in c.sessions) {
                if (s.dayOfWeek == dayOfWeek && s.isActive(week)) {
                    out.add(c)
                    break
                }
            }
        }
        return out
    }

    companion object {
        const val SCHEMA_VERSION = 1

        const val SOURCE_JWGLXT = "jwglxt"
        const val SOURCE_CUSTOM = "custom"
        const val SOURCE_IMPORT = "import"

        @JvmStatic
        @Throws(JSONException::class)
        fun fromJson(o: JSONObject): Timetable {
            val ver = o.optInt("schemaVersion", 0)
            if (ver > SCHEMA_VERSION) {
                throw JSONException("课表数据版本 $ver 高于当前支持版本 $SCHEMA_VERSION，请升级 App")
            }
            val t = Timetable()
            val so = o.optJSONObject("semester")
            if (so != null) t.semester = Semester.fromJson(so)
            val ca = o.optJSONArray("courses")
            if (ca != null) {
                for (i in 0 until ca.length()) {
                    val co = ca.optJSONObject(i)
                    if (co != null) t.courses.add(Course.fromJson(co))
                }
            }
            val aa = o.optJSONArray("adjusts")
            if (aa != null) {
                for (i in 0 until aa.length()) {
                    val ao = aa.optJSONObject(i)
                    if (ao != null) t.adjusts.add(Adjust.fromJson(ao))
                }
            }
            return t
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun fromJson(json: String): Timetable = fromJson(JSONObject(json))
    }
}
