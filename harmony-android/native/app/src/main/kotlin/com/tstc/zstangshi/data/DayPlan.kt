package com.tstc.zstangshi.data

/**
 * 某一天实际要上的课（已算上放假、停课、调课、补课），按开始时间排好。
 * 桌面小组件和课前提醒共用。作息用「每节开始/结束分钟」数组传进来（下标 = 节次）。
 */
object DayPlan {

    class Item {
        @JvmField var name: String = ""

        /** 校区（教务里有时只有校区没有教室，反之亦然） */
        @JvmField var campus: String = ""
        @JvmField var location: String = ""
        @JvmField var startMin: Int = 0
        @JvmField var endMin: Int = 0

        /** 「3-4节」或空（自定义时间的安排） */
        @JvmField var sections: String = ""

        /** 起始/结束节次（1 起）；自定义时间或解析不到时为 0。焦点通知用它算课间。 */
        @JvmField var startSection: Int = 0
        @JvmField var endSection: Int = 0

        /** 「补课」「调课」或空 */
        @JvmField var tag: String = ""

        /** 课程色板下标（和课表同色） */
        @JvmField var colorIndex: Int = 0

        /** 雨级：0=不下雨，1=小雨，2=中雨，3=大雨 */
        @JvmField var rainLevel: Int = 0

        fun timeLabel(): String =
            Timetable.Session.formatHm(startMin) + "-" + Timetable.Session.formatHm(endMin)

        /** 「东校区 3-518」这种完整地点；两段都没有就是空串。 */
        fun placeLabel(): String {
            val c = campus.trim()
            val l = location.trim()
            if (c.isEmpty()) return l
            if (l.isEmpty()) return c
            if (l.contains(c)) return l
            return "$c $l"
        }
    }

    /**
     * @param start 第 1 周周一（yyyy-MM-dd）；无效时返回空
     * @param secStart secStart[n] = 第 n 节开始分钟
     * @param secEnd   secEnd[n] = 第 n 节结束分钟
     */
    @JvmStatic
    fun of(t: Timetable?, start: String?, ymd: String?, secStart: IntArray, secEnd: IntArray): MutableList<Item> {
        val out = ArrayList<Item>()
        if (t == null || !Dates.valid(start) || !Dates.valid(ymd)) return out
        val week = Dates.weekOf(start, ymd)
        if (week < 1) return out
        val day = Dates.dayOfWeek(ymd)
        val o = WeekOverlay.build(t, start, week)
        for (c in t.courses) {
            for (s in c.sessions) {
                if (s.dayOfWeek != day || !s.isActive(week) || o.cancelled.containsKey(s)) continue
                val it = item(c, s, secStart, secEnd) ?: continue
                out.add(it)
            }
        }
        for (e in o.extras) {
            if (e.session.dayOfWeek != day) continue
            val it = item(e.course, e.session, secStart, secEnd) ?: continue
            it.tag = e.label
            out.add(it)
        }
        out.sortWith(Comparator { a, b ->
            if (a.startMin != b.startMin) a.startMin.compareTo(b.startMin)
            else a.name.compareTo(b.name)
        })
        return out
    }

    private fun item(c: Timetable.Course, s: Timetable.Session, secStart: IntArray, secEnd: IntArray): Item? {
        val it = Item()
        it.name = c.name
        it.campus = s.campus.trim()
        it.location = s.location.trim()
        it.colorIndex = c.colorIndex
        if (s.hasCustomTime()) {
            it.startMin = Timetable.Session.minutesOf(s.timeStart)
            it.endMin = Timetable.Session.minutesOf(s.timeEnd)
        } else {
            val a = s.startSection
            val b = Math.max(s.startSection, s.endSection)
            if (a < 1 || b >= secStart.size || b >= secEnd.size) return null
            it.startMin = secStart[a]
            it.endMin = secEnd[b]
            it.startSection = a
            it.endSection = b
            it.sections = if (a == b) "第$a" + "节" else "$a-$b" + "节"
        }
        if (it.startMin < 0 || it.endMin <= it.startMin) return null
        return it
    }

    /** 现在正在上的课，没有返回 null。 */
    @JvmStatic
    fun current(items: List<Item>, nowMin: Int): Item? {
        for (it in items) if (it.startMin <= nowMin && nowMin < it.endMin) return it
        return null
    }

    /** 还没开始的第一节课，没有返回 null。 */
    @JvmStatic
    fun next(items: List<Item>, nowMin: Int): Item? {
        for (it in items) if (it.startMin > nowMin) return it
        return null
    }

    /** 今天的课是不是都上完了（今天没课也算）。 */
    @JvmStatic
    fun doneFor(items: List<Item>, nowMin: Int): Boolean {
        for (it in items) if (it.endMin > nowMin) return false
        return true
    }
}
