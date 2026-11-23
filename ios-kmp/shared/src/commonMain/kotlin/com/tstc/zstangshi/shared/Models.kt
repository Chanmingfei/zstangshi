package com.tstc.zstangshi.shared

/**
 * 课表模型（探针用的最小集）。
 *
 * 完整模型在 Android `data/Timetable.kt`（458 行），其中约一半是 toJson/fromJson 的
 * 持久化代码。探针阶段只需要「解析器要往里填的字段」，所以这里只保留这些；
 * 等 P1 落地时再把持久化部分补齐（JSON 兼容层已经就位，补的时候不用改调用方）。
 */
class Timetable {
    val semester: Semester = Semester()
    val courses: MutableList<Course> = ArrayList()

    /** Swift 只读视图（ForEach / first 都要它）。 */
    fun courseList(): List<Course> = courses

    /**
     * 本地隐藏掉的课程 key。
     *
     * 用途：教务调课/停课但系统没更新时，用户把整门课从自己的课表里去掉。
     * 单独存一份而不是删 course，是因为下次同步教务会把这门课又抓回来 ——
     * 安卓那边对应的是 Adjust(停课/调课) 体系，先覆盖「整门课不再显示」这类最常见诉求。
     */
    val hiddenKeys: MutableList<String> = ArrayList()

    fun hiddenList(): List<String> = hiddenKeys

    /** 手动调整（停课/调课/放假/补课），纯本地数据，同步时保留。 */
    val adjusts: MutableList<Adjust> = ArrayList()

    fun adjustList(): List<Adjust> = adjusts
}

class Semester {
    var id: String = ""
    var name: String = ""
    var startDate: String = ""
    var totalWeeks: Int = 20
}

class Course {
    var key: String = ""
    var name: String = ""
    var teacher: String = ""
    var credit: Double = 0.0
    var category: String = ""
    var examType: String = ""

    /** 来源：教务同步 / 用户手动。手动课程在任何同步中都不会被删。 */
    var source: String = SOURCE_JWGLXT

    /** 色板索引；手动或用户改过色时不参与自动分配。 */
    var colorIndex: Int = 0
    var colorTouched: Boolean = false

    val sessions: MutableList<Session> = ArrayList()

    /** Swift 只读视图：MutableList 会导成 NSMutableArray，取元素全是 Any。 */
    fun sessionList(): List<Session> = sessions
}

class Session {
    var dayOfWeek: Int = 1        // 1=周一 … 7=周日
    var startSection: Int = 1
    var endSection: Int = 1
    val weeks: MutableList<Int> = ArrayList()
    var weeksRaw: String = ""
    var location: String = ""
    var campus: String = ""
    var roomType: String = ""
    var teacher: String = ""

    /** 按周换老师（如形势与政策每周一个主讲）：周次 → 教师。 */
    val weekTeachers: MutableMap<Int, String> = LinkedHashMap()

    /** Swift 只读视图。 */
    fun weekList(): List<Int> = weeks
}

const val SOURCE_JWGLXT = "jwglxt"
const val SOURCE_CUSTOM = "custom"
