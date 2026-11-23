package com.tstc.zstangshi.shared

/**
 * 教学周与日期计算（从 Android `data/WeekMath.kt` 移植）。
 *
 * 语义逐条对齐原实现：
 *   - 第 1 周固定从周一开始，任意日期先归一到所在周的周一；
 *   - `weekOfDate` 用 floorDiv，开学前的日期得到 0、-1…（不被截断成第 1 周）；
 *   - 解析失败返回 -1 / 空串，由调用方给降级文案。
 *
 * 与原实现的差异只有一处：不再用 `Calendar` 的毫秒差 + Math.round 打补丁，
 * 直接对 epoch day 做整数差 —— 结果相同，但不受时区与夏令时影响。
 */
object WeekMath {

    /** 不设学期总周数：从第 1 周一直往后排，最多到这里。 */
    const val OPEN_WEEKS_MAX = 99

    /** 可翻页的最少周数，保证刚开学也能往后翻看。 */
    const val OPEN_WEEKS_MIN = 30

    /** 今天。由平台层注入（Android = java.time，iOS = NSDate），测试里可替换。 */
    var todayProvider: () -> Int = { 0 }

    fun todayEpochDay(): Int = todayProvider()

    fun dayOfWeek(epochDay: Int): Int = Dates.dayOfWeek(epochDay)

    /** 开放式周跨度：至少 30 周，今天所在周之后再留 4 周，最多 99。 */
    fun openWeekSpan(startDate: String?, today: Int = todayEpochDay()): Int {
        var span = OPEN_WEEKS_MIN
        val d = if (startDate.isNullOrEmpty()) -1
        else weekOfDate(startDate, Dates.formatYmd(today))
        if (d > 0) span = maxOf(span, d + 4)
        return minOf(OPEN_WEEKS_MAX, span)
    }

    fun currentWeek(startDate: String?, totalWeeks: Int, today: Int = todayEpochDay()): Int {
        val total = maxOf(1, minOf(OPEN_WEEKS_MAX, totalWeeks))
        val start = Dates.parseYmd(normalizeToMonday(startDate)) ?: return 1
        val days = today - start
        if (days < 0) return 1
        val week = Dates.floorDiv(days, 7) + 1
        return maxOf(1, minOf(total, week))
    }

    /** 任意日期落在第几周（周一为一周起点；解析失败返回 -1）。 */
    fun weekOfDate(startDate: String?, ymd: String?): Int {
        val start = Dates.parseYmd(normalizeToMonday(startDate)) ?: return -1
        val day = Dates.parseYmd(normalizeToMonday(ymd)) ?: return -1
        return Dates.floorDiv(day - start, 7) + 1
    }

    /** 距开学还有几天；已开学或未设置返回 0。 */
    fun daysUntilStart(startDate: String?, today: Int = todayEpochDay()): Int {
        val start = Dates.parseYmd(normalizeToMonday(startDate)) ?: return 0
        val d = start - today
        return if (d > 0) d else 0
    }

    /** 第 week 周周一的 yyyy-MM-dd；开学日期无效返回空串。 */
    fun weekRangeStart(startDate: String?, week: Int): String {
        val start = Dates.parseYmd(normalizeToMonday(startDate)) ?: return ""
        return Dates.formatYmd(start + (week - 1) * 7)
    }

    /** 第 week 周的 7 个 'MM/dd'。开学日期无效时退回本周。 */
    fun weekDates(startDate: String?, week: Int, today: Int = todayEpochDay()): Array<String> {
        val parsed = Dates.parseYmd(normalizeToMonday(startDate))
        val monday = parsed ?: (today - (Dates.dayOfWeek(today) - 1))
        val first = monday + (maxOf(1, week) - 1) * 7
        val out = ArrayList<String>(7)
        for (i in 0..6) out.add(Dates.formatMd(first + i))
        return out.toTypedArray()
    }

    /** 任意日期归一到所在周的周一；解析失败返回空串。 */
    fun normalizeToMonday(date: String?): String {
        val d = Dates.parseYmd(date) ?: return ""
        val offset = Dates.dayOfWeek(d) - 1
        return Dates.formatYmd(d - offset)
    }

    fun isMonday(date: String?): Boolean {
        val d = Dates.parseYmd(date) ?: return false
        return Dates.dayOfWeek(d) == 1
    }

    fun weekRangeLabel(startDate: String?, week: Int, today: Int = todayEpochDay()): String {
        val dates = weekDates(startDate, week, today)
        return dates[0] + " - " + dates[6]
    }

    fun shortDate(date: String?): String {
        val d = Dates.parseYmd(date) ?: return ""
        return Dates.formatMd(d)
    }

    /**
     * 从课表里最早的周次与星期反推首节课日期。
     * 例：第一周 2026-09-07，最早课程在第 5 周周一 → 2026-10-05。
     */
    fun firstCourseDate(startDate: String?, timetable: Timetable?): String {
        val start = Dates.parseYmd(normalizeToMonday(startDate)) ?: return ""
        if (timetable == null) return ""

        var bestWeek = Int.MAX_VALUE
        var bestDay = 0
        for (course in timetable.courses) {
            for (session in course.sessions) {
                val day = maxOf(1, minOf(7, session.dayOfWeek))
                if (session.weeks.isEmpty()) continue
                for (week in session.weeks) {
                    if (week < 1) continue
                    if (week < bestWeek || (week == bestWeek && day < bestDay)) {
                        bestWeek = week
                        bestDay = day
                    }
                }
            }
        }
        if (bestWeek == Int.MAX_VALUE) return ""
        return Dates.formatYmd(start + (bestWeek - 1) * 7 + bestDay - 1)
    }

    fun firstCourseLabel(startDate: String?, timetable: Timetable?): String {
        val date = firstCourseDate(startDate, timetable)
        if (date.isEmpty()) return "暂无可计算的首节课"
        val d = Dates.parseYmd(date)
        val dow = if (d == null) 1 else Dates.dayOfWeek(d)
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        return shortDate(date) + " 周" + names[dow - 1]
    }
}
