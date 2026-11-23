package com.tstc.zstangshi.shared

/**
 * 课表网格：把「课程 + 分段 sessions + 周次」摊平成一个个格子。
 *
 * 为什么放在 Kotlin 而不是 Swift：
 *   周次展开、跨节次合并、同格多课这几个规则，安卓（`data/Timetable.kt`）
 *   已经有一套经过验证的实现；照搬到这里，Swift 只负责画格子，
 *   避免两端各写一遍周次逻辑然后对不上。
 */
class GridCell(
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    val name: String,
    val room: String,
    val teacher: String,
    val colorIndex: Int,
    val courseKey: String
) {
    override fun toString(): String = "$name@${day}w$startSection-$endSection"
}

object TimetableGrid {

    /** 该周要显示的格子，按天、节次排序。 */
    fun cells(t: Timetable, week: Int): List<GridCell> {
        val out = ArrayList<GridCell>()
        val hidden = t.hiddenKeys
        for (course in t.courses) {
            if (hidden.contains(course.key)) continue
            for (s in course.sessions) {
                if (s.weeks.isNotEmpty() && !s.weeks.contains(week)) continue
                out.add(
                    GridCell(
                        day = s.dayOfWeek,
                        startSection = s.startSection,
                        endSection = s.endSection,
                        name = course.name,
                        room = s.location,
                        teacher = s.teacher.ifEmpty { course.teacher },
                        colorIndex = course.colorIndex,
                        courseKey = course.key
                    )
                )
            }
        }
        out.sortWith(compareBy({ it.day }, { it.startSection }))
        return out
    }

    /** 某一天的课（首页「今日课程」用）。 */
    fun cellsOfDay(t: Timetable, week: Int, day: Int): List<GridCell> =
        cells(t, week).filter { it.day == day }

    /** 课表最多上到第几节（决定网格高度，默认 12）。 */
    fun maxSection(t: Timetable): Int {
        var m = 12
        for (course in t.courses) {
            for (s in course.sessions) {
                if (s.endSection > m) m = s.endSection
            }
        }
        return m
    }

    /** 当前生效的课程数（不算被隐藏的）。 */
    fun visibleCount(t: Timetable): Int = t.courses.count { !t.hiddenKeys.contains(it.key) }

    /** 全学期出现过的最大周次。 */
    fun maxWeek(t: Timetable, def: Int): Int {
        var m = def
        for (course in t.courses) {
            for (s in course.sessions) {
                for (w in s.weeks) if (w > m) m = w
            }
        }
        return m
    }
}
