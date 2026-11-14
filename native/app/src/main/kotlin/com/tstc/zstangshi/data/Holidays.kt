package com.tstc.zstangshi.data

/**
 * 内置法定节假日与调休（国务院办公厅公布）。
 * 补课日上哪天的课按国务院调休对应关系推定；学校另有通知时用「烫梨指令」覆盖。
 * 2027 年安排公布后再补。
 */
object Holidays {

    class Day(
        @JvmField val date: String,
        @JvmField val label: String,
        @JvmField val off: Boolean,
        /** 仅补课日有值 */
        @JvmField val fromDate: String
    )

    @JvmField
    val DAYS: MutableList<Day> = ArrayList()

    init {
        off("2026-09-25", "2026-09-27", "中秋")
        off("2026-10-01", "2026-10-07", "国庆")
        DAYS.add(Day("2026-09-20", "国庆调休", false, "2026-10-06"))
        DAYS.add(Day("2026-10-10", "国庆调休", false, "2026-10-07"))
        off("2027-01-01", "2027-01-01", "元旦")
    }

    private fun off(from: String, to: String, label: String) {
        var d = from
        while (d.compareTo(to) <= 0) {
            DAYS.add(Day(d, label, true, ""))
            d = Dates.addDays(d, 1)
        }
    }

    /** 内置 + 课表里的用户安排（用户的优先）。返回 null 表示普通日子。 */
    @JvmStatic
    fun lookup(t: Timetable?, ymd: String?): Day? {
        if (ymd == null || ymd.isEmpty()) return null
        if (t != null) {
            for (i in t.adjusts.indices.reversed()) {
                val a = t.adjusts[i]
                if (ymd != a.date) continue
                if (Timetable.Adjust.OFF == a.type) {
                    return Day(ymd, if (a.note.isEmpty()) "放假" else a.note, true, "")
                }
                if (Timetable.Adjust.MAKEUP == a.type) {
                    return Day(ymd, if (a.note.isEmpty()) "补课" else a.note, false, a.fromDate)
                }
            }
        }
        for (d in DAYS) if (d.date == ymd) return d
        return null
    }
}
