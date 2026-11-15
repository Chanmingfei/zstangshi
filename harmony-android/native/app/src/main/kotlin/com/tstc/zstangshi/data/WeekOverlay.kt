package com.tstc.zstangshi.data

import java.util.IdentityHashMap

/**
 * 某一周的「临时变化」：哪些课这周不上（放假/停课/已调走），哪些课是额外加进来的（补课/调来）。
 * 课程本身不动，TimetableView 按这个叠加绘制。
 */
class WeekOverlay private constructor(@JvmField val week: Int) {

    class Mark(
        @JvmField val label: String,     // 放假 / 停课 / 已调走
        @JvmField val adjustId: String   // 可撤销的用户安排；内置节假日为空
    )

    class Extra(
        @JvmField val course: Timetable.Course,
        /** 新造的时段，只在本周显示 */
        @JvmField val session: Timetable.Session,
        @JvmField val label: String,     // 补课 / 调课
        @JvmField val adjustId: String
    )

    @JvmField val cancelled: MutableMap<Timetable.Session, Mark> = IdentityHashMap()
    @JvmField val extras: MutableList<Extra> = ArrayList()

    /** 星期 → 当天标签（「国庆」「补 10/7 周三」），表头用 */
    @JvmField val dayLabels: Array<String?> = arrayOfNulls(8)
    @JvmField val dayOff: BooleanArray = BooleanArray(8)

    fun isEmpty(): Boolean {
        if (cancelled.isNotEmpty() || extras.isNotEmpty()) return false
        for (d in 1..7) if (dayLabels[d] != null) return false
        return true
    }

    companion object {

        @JvmStatic
        fun empty(week: Int): WeekOverlay = WeekOverlay(week)

        @JvmStatic
        fun build(t: Timetable?, start: String?, week: Int): WeekOverlay {
            val o = WeekOverlay(week)
            if (t == null) return o
            val hasStart = Dates.valid(start)

            // 1. 放假 / 补课日
            if (hasStart) {
                for (d in 1..7) {
                    val ymd = Dates.dateOf(start, week, d)
                    val h = Holidays.lookup(t, ymd) ?: continue
                    if (h.off) {
                        o.dayOff[d] = true
                        o.dayLabels[d] = h.label
                        val offId = userAdjustId(t, ymd)
                        for (c in t.courses) {
                            for (s in c.sessions) {
                                if (s.dayOfWeek == d && s.isActive(week)) {
                                    o.cancelled[s] = Mark("放假", offId)
                                }
                            }
                        }
                    } else if (Dates.valid(h.fromDate)) {
                        val fw = Dates.weekOf(start, h.fromDate)
                        val fd = Dates.dayOfWeek(h.fromDate)
                        o.dayLabels[d] = "补" + Dates.cnDay(fd)
                        val aid = userAdjustId(t, ymd)
                        for (c in t.courses) {
                            for (s in c.sessions) {
                                if (s.dayOfWeek != fd || !s.isActive(fw)) continue
                                if (findCancel(t, c, s, fw) != null) continue
                                val n = s.copy()
                                n.dayOfWeek = d
                                n.weeks.clear()
                                n.weeks.add(week)
                                n.weeksRaw = "$week" + "周"
                                val wt = s.weekTeachers[fw]
                                n.weekTeachers.clear()
                                if (wt != null) n.weekTeachers[week] = wt
                                o.extras.add(Extra(c, n, "补课", aid))
                            }
                        }
                    }
                }
            }

            // 2. 停课 / 调课
            for (a in t.adjusts) {
                val cancel = Timetable.Adjust.CANCEL == a.type
                val move = Timetable.Adjust.MOVE == a.type
                if (!cancel && !move) continue
                if (a.week == week) {
                    for (c in t.courses) {
                        if (c.name != a.name) continue
                        for (s in c.sessions) {
                            if (matches(a, s, week)) {
                                o.cancelled[s] = Mark(if (cancel) "停课" else "已调走", a.id)
                            }
                        }
                    }
                }
                if (move && targetWeek(a) == week) {
                    for (c in t.courses) {
                        if (c.name != a.name) continue
                        for (s in c.sessions) {
                            if (!matches(a, s, a.week)) continue
                            val n = s.copy()
                            n.dayOfWeek = if (a.toDay > 0) a.toDay else s.dayOfWeek
                            if (a.hasToTime()) {
                                n.timeStart = a.toTimeStart
                                n.timeEnd = a.toTimeEnd
                            } else if (a.toStart > 0) {
                                val len = s.endSection - s.startSection
                                n.startSection = a.toStart
                                n.endSection = if (a.toEnd >= a.toStart) a.toEnd else a.toStart + len
                                n.timeStart = ""
                                n.timeEnd = ""
                            }
                            if (a.toLocation.isNotEmpty()) n.location = a.toLocation
                            n.weeks.clear()
                            n.weeks.add(week)
                            n.weeksRaw = "$week" + "周"
                            val wt = s.weekTeachers[a.week]
                            n.weekTeachers.clear()
                            if (wt != null) n.weekTeachers[week] = wt
                            o.extras.add(Extra(c, n, "调课", a.id))
                            break // 一条调课只搬一个时段
                        }
                    }
                }
            }
            return o
        }

        @JvmStatic
        fun targetWeek(a: Timetable.Adjust): Int = if (a.toWeek > 0) a.toWeek else a.week

        /** 调整是否命中这个时段（这周确实有这节课，且星期/节次对得上）。 */
        @JvmStatic
        fun matches(a: Timetable.Adjust, s: Timetable.Session, week: Int): Boolean {
            if (!s.isActive(week)) return false
            if (a.day > 0 && s.dayOfWeek != a.day) return false
            if (a.start > 0) {
                val end = if (a.end >= a.start) a.end else a.start
                if (s.endSection < a.start || s.startSection > end) return false
            }
            return true
        }

        private fun findCancel(
            t: Timetable,
            c: Timetable.Course,
            s: Timetable.Session,
            week: Int
        ): Timetable.Adjust? {
            for (a in t.adjusts) {
                if ((Timetable.Adjust.CANCEL == a.type || Timetable.Adjust.MOVE == a.type) &&
                    a.week == week && c.name == a.name && matches(a, s, week)
                ) return a
            }
            return null
        }

        private fun userAdjustId(t: Timetable, ymd: String): String {
            for (a in t.adjusts) if (ymd == a.date) return a.id
            return ""
        }
    }
}
