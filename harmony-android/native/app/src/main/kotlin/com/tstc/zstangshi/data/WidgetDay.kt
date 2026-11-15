package com.tstc.zstangshi.data

/**
 * 桌面小组件「今日课程」要显示什么：标题、日期行、一排课程条（正在上 / 快上课的带倒计时）、兜底一句话。
 * 同一个小组件拉成 2×2 时按 [NARROW] 收紧。
 */
object WidgetDay {

    /** 课表状态 */
    const val OK = 0
    const val NO_TIMETABLE = 1
    const val NO_START = 2
    const val BEFORE = 3
    const val AFTER = 4

    class Chip {
        @JvmField var name: String = ""

        /** 「10:00 - 11:40  3-518」（没教室就只有时间） */
        @JvmField var line: String = ""

        /** 右上角小字：「进行中」「明天」「+2」或空 */
        @JvmField var tag: String = ""
        @JvmField var ongoing: Boolean = false
        @JvmField var colorIndex: Int = 0

        /** 雨级：0=不下雨，1=小雨，2=中雨，3=大雨 */
        @JvmField var rainLevel: Int = 0

        /** 倒计时：无 / 「还剩 mm:ss」（正在上，到下课）/ 「mm:ss 后上课」（下一门，开始前 60 分钟内） */
        @JvmField var cdKind: Int = CD_NONE

        /** 倒计时到今天第几分钟（下课或上课）；没有为 -1 */
        @JvmField var cdMin: Int = -1

        companion object {
            const val CD_NONE = 0
            const val CD_LEFT = 1
            const val CD_SOON = 2
        }
    }

    class Card {
        @JvmField var title: String = ""

        /** 标题里要标朱砂色的那段 [numStart, numEnd)；没有为 -1 */
        @JvmField var numStart: Int = -1
        @JvmField var numEnd: Int = -1
        @JvmField var sub: String = ""
        @JvmField val chips: MutableList<Chip> = ArrayList()

        /** 没有课程条时的一句话；有课程条时为空 */
        @JvmField var note: String = ""

        /** 放不下、没显示出来的课数 */
        @JvmField var more: Int = 0
    }

    /** 今天还没下课的课（正在上 + 还没开始），保持原顺序。 */
    @JvmStatic
    fun remaining(items: List<DayPlan.Item>?, nowMin: Int): MutableList<DayPlan.Item> {
        val out = ArrayList<DayPlan.Item>()
        if (items == null) return out
        for (it in items) if (it.endMin > nowMin) out.add(it)
        return out
    }

    /** 下一个要刷新的时刻（今天的分钟数）：最近的上课或下课；今天没有了返回 -1。 */
    @JvmStatic
    fun nextBoundary(items: List<DayPlan.Item>?, nowMin: Int): Int {
        var best = -1
        if (items == null) return best
        for (it in items) {
            if (it.startMin > nowMin && (best < 0 || it.startMin < best)) best = it.startMin
            if (it.endMin > nowMin && (best < 0 || it.endMin < best)) best = it.endMin
        }
        return best
    }

    /** 「10.27」 */
    @JvmStatic
    fun dot(ymd: String?): String {
        val p = ymd?.split("-") ?: emptyList()
        if (p.size != 3) return ymd ?: ""
        return try {
            p[1].toInt().toString() + "." + p[2]
        } catch (e: NumberFormatException) {
            ymd ?: ""
        }
    }

    /** 「10:00 - 11:40  3-518」 */
    @JvmStatic
    fun line(it: DayPlan.Item): String {
        val t = Timetable.Session.formatHm(it.startMin) + " - " + Timetable.Session.formatHm(it.endMin)
        return if (it.location.isEmpty()) t else "$t  " + it.location
    }

    /** 「10:00 - 11:40」 */
    @JvmStatic
    fun timeRange(it: DayPlan.Item): String =
        Timetable.Session.formatHm(it.startMin) + " - " + Timetable.Session.formatHm(it.endMin)

    /**
     * @param state     OK / NO_TIMETABLE / NO_START / BEFORE / AFTER
     * @param ymd       今天 yyyy-MM-dd
     * @param week      今天是第几周（state=OK 时 ≥1）
     * @param start     第 1 周周一（BEFORE 时用来说哪天开学）
     * @param today     今天全部的课（DayPlan.of），按开始时间排好
     * @param tomorrow  明天全部的课
     * @param nowMin    现在是今天第几分钟
     * @param cap       最多显示几条（≥1）
     * @param level     [WIDE] / [MID] / [NARROW]
     */
    @JvmStatic
    @JvmOverloads
    fun build(
        state: Int,
        ymd: String,
        week: Int,
        start: String?,
        today: List<DayPlan.Item>?,
        tomorrow: List<DayPlan.Item>?,
        nowMin: Int,
        capSize: Int,
        level: Int = WIDE
    ): Card {
        val c = Card()
        var cap = capSize
        val wd = Dates.cnDay(Dates.dayOfWeek(ymd))
        c.sub = if (state == OK && week >= 1) dot(ymd) + " / 第" + week + "周"
        else dot(ymd) + (if (wd.isEmpty()) "" else " $wd")
        when (state) {
            NO_TIMETABLE -> {
                c.title = "还没有课表"
                c.note = "打开烫梨同步一下，这里就会列出今天的课"
                return c
            }
            NO_START -> {
                c.title = "还不知道哪天开学"
                c.note = "在烫梨里设一下开学日期就能显示"
                return c
            }
            BEFORE -> {
                c.title = "还没开学"
                c.note = if (Dates.valid(start)) "开学见，第 1 周从 " + dot(start) + " 开始" else "开学见"
                return c
            }
            AFTER -> {
                c.title = "这学期的课上完啦"
                c.note = "放假愉快，新学期课表同步后会显示在这"
                return c
            }
            else -> {
            }
        }
        cap = Math.max(1, cap)
        if (level == NARROW) cap = Math.min(cap, 2)
        val left = remaining(today, nowMin)
        if (left.isNotEmpty()) {
            val head = if (level == NARROW) (if (wd.isEmpty()) "" else "$wd · ") + "还有 "
            else "今日$wd" + "，还有 "
            val n = left.size.toString()
            c.title = head + n + if (level == NARROW) " 门" else " 门课要上"
            c.numStart = head.length
            c.numEnd = head.length + n.length
            val shown = Math.min(cap, left.size)
            c.more = left.size - shown
            var soonDone = false
            for (i in 0 until shown) {
                val it = left[i]
                val ch = chip(it, level)
                ch.ongoing = it.startMin <= nowMin
                if (ch.ongoing) {
                    ch.tag = if (level == NARROW) "" else "进行中"
                    ch.cdKind = Chip.CD_LEFT
                    ch.cdMin = it.endMin
                } else if (!soonDone) {
                    soonDone = true // 只给最近要上的那一门
                    if (it.startMin - nowMin <= SOON_MIN) {
                        ch.cdKind = Chip.CD_SOON
                        ch.cdMin = it.startMin
                    }
                }
                // 窄的时候倒计时单占第二行，时间让给它
                if (level == NARROW && ch.cdKind != Chip.CD_NONE) ch.line = ""
                c.chips.add(ch)
            }
            if (c.more > 0) {
                val last = c.chips[c.chips.size - 1]
                last.tag = if (last.tag.isEmpty()) "+" + c.more else last.tag + " · +" + c.more
            }
            return c
        }
        c.title = if (today.isNullOrEmpty()) "今天没课" else "今天的课上完了"
        if (tomorrow.isNullOrEmpty()) {
            c.note = "明天也没课，好好休息"
        } else {
            val ch = chip(tomorrow[0], level)
            ch.tag = "明天"
            c.chips.add(ch)
        }
        return c
    }

    private fun chip(it: DayPlan.Item, level: Int): Chip {
        val ch = Chip()
        ch.name = it.name
        ch.line = if (level == WIDE) line(it) else timeRange(it) // 先丢教室，时间留着
        ch.colorIndex = it.colorIndex
        ch.rainLevel = it.rainLevel
        return ch
    }

    // ===== 宽度档位：同一个「今日课程」拉宽拉窄 =====

    /**
     * 宽（4×2 及以上）：完整标题、「时间  教室」；中：去掉教室；窄（拉成 2×2）：标题缩成「周二 · 还有 N 门」、
     * 不要日期行、最多 2 条、倒计时单占第二行，正在上的不再写「进行中」（朱砂色的「还剩」已经说明了，课名放得下）。
     */
    const val WIDE = 0
    const val MID = 1
    const val NARROW = 2

    /** 按小组件竖屏宽度（dp）和字号定档。 */
    @JvmStatic
    fun widthLevel(wDp: Int, fontScale: Float): Int {
        val fs = Math.max(1f, fontScale)
        if (wDp <= 0) return WIDE
        if (wDp >= 230 * fs) return WIDE
        if (wDp >= 185 * fs) return MID
        return NARROW
    }

    // ===== 倒计时 =====

    /** 正在上的课离下课、或下一门离上课在这么多分钟以内才显示倒计时。 */
    const val SOON_MIN = 60

    /**
     * 下一个要刷新的时刻（今天的分钟数）：上课、下课，以及上课前 [SOON_MIN] 分钟（那时开始显示「mm:ss 后上课」）；
     * 今天没有了返回 -1。
     */
    @JvmStatic
    fun nextRefreshMin(items: List<DayPlan.Item>?, nowMin: Int): Int {
        var best = nextBoundary(items, nowMin)
        if (items == null) return best
        for (it in items) {
            val t = it.startMin - SOON_MIN
            if (t > nowMin && (best < 0 || t < best)) best = t
        }
        return best
    }

    /** 倒计时的静态兜底文字（系统不支持倒计时控件时用）：「还剩 34 分钟」「12 分钟后上课」。 */
    @JvmStatic
    fun countdownText(kind: Int, targetMin: Int, nowMin: Int): String {
        val m = Math.max(0, targetMin - nowMin)
        if (kind == Chip.CD_LEFT) return "还剩 $m 分钟"
        if (kind == Chip.CD_SOON) return "$m 分钟后上课"
        return ""
    }
}
