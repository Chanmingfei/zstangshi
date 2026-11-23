package com.tstc.zstangshi.shared

/**
 * 手动调整（移植自安卓 `Timetable.Adjust`）。
 *
 * 解决的是「教务课调了，但教务系统没更新」这类现实问题：
 *   - `cancel` 本周这次课不上了（只影响指定周次，不动整门课）
 *   - `move`   这次课换到别的时间/地点
 *   - `off`    某天整天放假（校运会、临时封楼）
 *   - `makeup` 补课（把某天的课补到另一天）
 *
 * 为什么不是「删课」：教务一同步，删掉的课又回来了；而调整是纯本地数据，
 * 同步时按 `TimetableAdjust` 的规则保留（见 `keepManual` 的注释）。
 */

const val ADJ_CANCEL = "cancel"
const val ADJ_MOVE = "move"
const val ADJ_OFF = "off"
const val ADJ_MAKEUP = "makeup"

class Adjust {
    var id: String = ""
    var type: String = ADJ_CANCEL

    /** 课程名（cancel/move 用来兜底匹配，key 缺失时也能对上）。 */
    var name: String = ""
    var week: Int = 0
    var day: Int = 0
    var start: Int = 0
    var end: Int = 0

    /** move 的目标位置。 */
    var toWeek: Int = 0
    var toDay: Int = 0
    var toStart: Int = 0
    var toEnd: Int = 0
    var toLocation: String = ""

    /** off/makeup 的日期（yyyy-MM-dd，仅用于展示）。 */
    var date: String = ""
    var fromDate: String = ""
    var note: String = ""

    fun label(): String = when (type) {
        ADJ_CANCEL -> "停课"
        ADJ_MOVE -> "调课"
        ADJ_OFF -> "放假"
        ADJ_MAKEUP -> "补课"
        else -> "调整"
    }
}

object TimetableAdjust {

    fun add(t: Timetable, a: Adjust): Adjust {
        if (a.id.isEmpty()) a.id = "adj-" + kotlin.random.Random.nextInt(100000, 999999)
        t.adjusts.add(a)
        return a
    }

    fun cancel(t: Timetable, name: String, week: Int, day: Int, start: Int, end: Int): Adjust {
        val a = Adjust()
        a.type = ADJ_CANCEL
        a.name = name
        a.week = week
        a.day = day
        a.start = start
        a.end = end
        return add(t, a)
    }

    fun move(
        t: Timetable, name: String, week: Int, day: Int, start: Int, end: Int,
        toWeek: Int, toDay: Int, toStart: Int, toEnd: Int, toLocation: String
    ): Adjust {
        val a = Adjust()
        a.type = ADJ_MOVE
        a.name = name
        a.week = week
        a.day = day
        a.start = start
        a.end = end
        a.toWeek = toWeek
        a.toDay = toDay
        a.toStart = toStart
        a.toEnd = toEnd
        a.toLocation = toLocation
        return add(t, a)
    }

    fun offDay(t: Timetable, week: Int, day: Int, date: String, note: String): Adjust {
        val a = Adjust()
        a.type = ADJ_OFF
        a.week = week
        a.day = day
        a.date = date
        a.note = note
        return add(t, a)
    }

    fun remove(t: Timetable, id: String) {
        val target = t.adjusts.firstOrNull { it.id == id } ?: return
        t.adjusts.remove(target)
    }

    /** 某次课被停掉了吗（按周次+星期+节次匹配，名字只作兜底）。 */
    fun isCancelled(t: Timetable, name: String, week: Int, day: Int, start: Int, end: Int): Boolean {
        for (a in t.adjusts) {
            if (a.type != ADJ_CANCEL) continue
            if (a.week != week) continue
            if (a.day != 0 && a.day != day) continue
            if (a.start != 0 && a.start != start) continue
            if (a.end != 0 && a.end != end) continue
            if (a.name.isNotEmpty() && a.name != name) continue
            return true
        }
        return false
    }

    /** 这一天整体放假吗。 */
    fun isDayOff(t: Timetable, week: Int, day: Int): Boolean {
        for (a in t.adjusts) {
            if (a.type != ADJ_OFF) continue
            if (a.week == week && a.day == day) return true
        }
        return false
    }

    /** 这一周的调课（要额外画到别的格子里）。 */
    fun movedOf(t: Timetable, week: Int): List<Adjust> =
        t.adjusts.filter { it.type == ADJ_MOVE && it.toWeek == week }
}
