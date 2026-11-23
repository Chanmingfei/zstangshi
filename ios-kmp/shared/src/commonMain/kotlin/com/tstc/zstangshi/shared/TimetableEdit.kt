package com.tstc.zstangshi.shared

/**
 * 课表的节次时间、持久化与编辑操作。
 *
 * 三件事放在一起的原因：安卓端它们就是紧耦合的（`data/Timetable.kt` 458 行里
 * 一半是 toJson/fromJson，一半是编辑），拆开反而要在 Swift 里重写一遍字符串转义。
 */

/** 作息时间。与安卓课表左侧显示的完全一致（12 节）。 */
object Sections {
    private val STARTS = arrayOf(
        "08:00", "08:50", "09:55", "10:45", "14:00", "14:50",
        "15:55", "16:45", "17:35", "19:00", "19:50", "20:40"
    )
    private val ENDS = arrayOf(
        "08:45", "09:35", "10:40", "11:30", "14:45", "15:35",
        "16:40", "17:30", "18:20", "19:45", "20:35", "21:25"
    )

    fun count(): Int = STARTS.size
    fun start(section: Int): String = STARTS.getOrElse(section - 1) { "" }
    fun end(section: Int): String = ENDS.getOrElse(section - 1) { "" }
}

/** 课表 JSON 编解码（自用格式，只求能完整往返）。 */
object TimetableCodec {

    fun toJson(t: Timetable): String {
        val sb = StringBuilder()
        sb.append("{\"semester\":{")
        sb.append("\"id\":").append(q(t.semester.id)).append(',')
        sb.append("\"name\":").append(q(t.semester.name)).append(',')
        sb.append("\"startDate\":").append(q(t.semester.startDate)).append(',')
        sb.append("\"totalWeeks\":").append(t.semester.totalWeeks)
        sb.append("},\"courses\":[")
        for ((i, c) in t.courses.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"key\":").append(q(c.key))
            sb.append(",\"name\":").append(q(c.name))
            sb.append(",\"teacher\":").append(q(c.teacher))
            sb.append(",\"credit\":").append(c.credit)
            sb.append(",\"category\":").append(q(c.category))
            sb.append(",\"examType\":").append(q(c.examType))
            sb.append(",\"source\":").append(q(c.source))
            sb.append(",\"colorIndex\":").append(c.colorIndex)
            sb.append(",\"colorTouched\":").append(c.colorTouched)
            sb.append(",\"sessions\":[")
            for ((j, s) in c.sessions.withIndex()) {
                if (j > 0) sb.append(',')
                sb.append("{\"dayOfWeek\":").append(s.dayOfWeek)
                sb.append(",\"startSection\":").append(s.startSection)
                sb.append(",\"endSection\":").append(s.endSection)
                sb.append(",\"weeks\":[").append(s.weeks.joinToString(",")).append(']')
                sb.append(",\"weeksRaw\":").append(q(s.weeksRaw))
                sb.append(",\"location\":").append(q(s.location))
                sb.append(",\"campus\":").append(q(s.campus))
                sb.append(",\"roomType\":").append(q(s.roomType))
                sb.append(",\"teacher\":").append(q(s.teacher))
                sb.append('}')
            }
            sb.append("]}")
        }
        sb.append("],\"hidden\":[")
        for ((i, k) in t.hiddenKeys.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(q(k))
        }
        sb.append("]}")
        return sb.toString()
    }

    fun fromJson(json: String): Timetable? {
        val root = Js.parse(json) as? Js.Obj ?: return null
        val t = Timetable()
        val sem = root.optObj("semester")
        if (sem != null) {
            t.semester.id = sem.optString("id")
            t.semester.name = sem.optString("name")
            t.semester.startDate = sem.optString("startDate")
            t.semester.totalWeeks = sem.optInt("totalWeeks", 20)
        }
        val hid = root.optArr("hidden")
        if (hid != null) {
            for (h in hid.items) {
                val k = (h as? Js.Str)?.value ?: ""
                if (k.isNotEmpty()) t.hiddenKeys.add(k)
            }
        }
        val arr = root.optArr("courses") ?: return t
        for (item in arr.items) {
            val o = item as? Js.Obj ?: continue
            val c = Course()
            c.key = o.optString("key")
            c.name = o.optString("name")
            c.teacher = o.optString("teacher")
            c.credit = o.optDouble("credit", 0.0)
            c.category = o.optString("category")
            c.examType = o.optString("examType")
            c.source = o.optString("source").ifEmpty { SOURCE_JWGLXT }
            c.colorIndex = o.optInt("colorIndex", 0)
            c.colorTouched = o.optBoolean("colorTouched", false)
            val ss = o.optArr("sessions")
            if (ss != null) {
                for (x in ss.items) {
                    val so = x as? Js.Obj ?: continue
                    val s = Session()
                    s.dayOfWeek = so.optInt("dayOfWeek", 1)
                    s.startSection = so.optInt("startSection", 1)
                    s.endSection = so.optInt("endSection", s.startSection)
                    val ws = so.optArr("weeks")
                    if (ws != null) {
                        for (w in ws.items) {
                            val n = (w as? Js.Num)?.toIntOrNull() ?: 0
                            if (n > 0) s.weeks.add(n)
                        }
                    }
                    s.weeksRaw = so.optString("weeksRaw")
                    s.location = so.optString("location")
                    s.campus = so.optString("campus")
                    s.roomType = so.optString("roomType")
                    s.teacher = so.optString("teacher")
                    c.sessions.add(s)
                }
            }
            t.courses.add(c)
        }
        return t
    }

    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}

/** 编辑操作：新增手动课、改颜色、删除。手动课在同步时不会被清掉（source=custom）。 */
object TimetableEdit {

    /** 新增一门手动课。返回新建的课程。 */
    fun addCourse(
        t: Timetable,
        name: String,
        teacher: String,
        room: String,
        campus: String,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
        weeks: List<Int>,
        colorIndex: Int = -1
    ): Course {
        val c = Course()
        c.name = name.trim()
        c.teacher = teacher.trim()
        c.source = SOURCE_CUSTOM
        c.key = "custom-" + kotlin.random.Random.nextInt(100000, 999999)
        // 传了颜色就用传的（用户在弹窗里选过），否则挑一个没用过的
        c.colorIndex = if (colorIndex >= 0) colorIndex else nextColor(t)
        c.colorTouched = colorIndex >= 0
        val s = Session()
        s.dayOfWeek = dayOfWeek
        s.startSection = startSection
        s.endSection = endSection
        s.location = room.trim()
        s.campus = campus.trim()
        s.teacher = teacher.trim()
        s.weeksRaw = weeks.sorted().joinToString(",")
        s.weeks.addAll(weeks.sorted())
        c.sessions.add(s)
        t.courses.add(c)
        return c
    }

    /** 整门课从课表里隐藏（教务课也能隐藏，同步后依然隐藏）。 */
    fun hideCourse(t: Timetable, key: String) {
        if (key.isEmpty() || t.hiddenKeys.contains(key)) return
        t.hiddenKeys.add(key)
    }

    fun unhideCourse(t: Timetable, key: String) {
        t.hiddenKeys.remove(key)
    }

    fun isHidden(t: Timetable, key: String): Boolean = t.hiddenKeys.contains(key)

    /** 改颜色：标记 colorTouched，之后自动配色不会再动它。 */
    fun setColor(c: Course, index: Int) {
        c.colorIndex = index
        c.colorTouched = true
    }

    /** 按 key 删除。只允许删手动课（教务同步的删了下次同步又会回来，没意义）。 */
    fun removeCourse(t: Timetable, key: String): Boolean {
        val target = t.courses.firstOrNull { it.key == key } ?: return false
        if (target.source != SOURCE_CUSTOM) return false
        t.courses.remove(target)
        return true
    }

    /** 改一门课的基本信息（名称/教师/教室/时间/周次）。 */
    fun updateCourse(
        c: Course,
        name: String,
        teacher: String,
        room: String,
        campus: String,
        dayOfWeek: Int,
        startSection: Int,
        endSection: Int,
        weeks: List<Int>
    ) {
        c.name = name.trim()
        c.teacher = teacher.trim()
        // 手动课只有一个 session；教务课不在这里改
        val s = c.sessions.firstOrNull() ?: Session().also { c.sessions.add(it) }
        s.dayOfWeek = dayOfWeek
        s.startSection = startSection
        s.endSection = endSection
        s.location = room.trim()
        s.campus = campus.trim()
        s.teacher = teacher.trim()
        s.weeksRaw = weeks.sorted().joinToString(",")
        s.weeks.clear()
        s.weeks.addAll(weeks.sorted())
    }

    /** 把周次压缩成区间文案：1-5,8,10-16（安卓弹窗里「1-16周」那种摘要）。 */
    fun compressWeeks(weeks: List<Int>): String {
        if (weeks.isEmpty()) return "未选"
        val s = weeks.sorted().distinct()
        val parts = ArrayList<String>()
        var start = s[0]
        var prev = s[0]
        for (i in 1 until s.size) {
            val v = s[i]
            if (v == prev + 1) { prev = v; continue }
            parts.add(if (start == prev) "$start" else "$start-$prev")
            start = v
            prev = v
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        return parts.joinToString(",")
    }

    private fun nextColor(t: Timetable): Int {
        val used = t.courses.map { ((it.colorIndex % 24) + 24) % 24 }.toSet()
        for (i in 0 until 24) if (!used.contains(i)) return i
        return t.courses.size % 24
    }
}
