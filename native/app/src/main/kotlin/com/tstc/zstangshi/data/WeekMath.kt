package com.tstc.zstangshi.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 教学周与日期计算，避免 UI 层各写一套。 */
object WeekMath {

    /** 不设学期总周数：周次从第 1 周一直往后排，最多到这里（足够覆盖整学期 + 假期）。 */
    const val OPEN_WEEKS_MAX = 99

    /** 可翻页的最少周数，保证刚开学也能往后翻看。 */
    const val OPEN_WEEKS_MIN = 30

    @JvmStatic
    fun todayDayOfWeek(): Int {
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    /** 开放式周跨度：至少 OPEN_WEEKS_MIN，今天所在周之后再留 4 周，最多 OPEN_WEEKS_MAX。 */
    @JvmStatic
    fun openWeekSpan(startDate: String?): Int {
        var span = OPEN_WEEKS_MIN
        val ymd = todayDateLabel()
        val today = if (startDate.isNullOrEmpty()) -1 else weekOfDate(startDate, ymd)
        if (today > 0) span = Math.max(span, today + 4)
        return Math.min(OPEN_WEEKS_MAX, span)
    }

    @JvmStatic
    fun currentWeek(startDate: String?, totalWeeks: Int): Int {
        val total = Math.max(1, Math.min(OPEN_WEEKS_MAX, totalWeeks))
        val start = parseDate(normalizeToMonday(startDate)) ?: return 1

        val today = Calendar.getInstance()
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)
        start.set(Calendar.HOUR_OF_DAY, 0)
        start.set(Calendar.MINUTE, 0)
        start.set(Calendar.SECOND, 0)
        start.set(Calendar.MILLISECOND, 0)

        val diff = today.timeInMillis - start.timeInMillis
        val days = Math.round(diff / (24.0 * 60 * 60 * 1000)) // 抵消夏令时等 ±1h
        if (days < 0) return 1
        val week = Math.floorDiv(days, 7L).toInt() + 1
        return Math.max(1, Math.min(total, week))
    }

    /**
     * 实际用于算周次和日期的第 1 周周一。
     * 用户没手动设开学日期时（新用户同步完就是这样），按内置校历兜底：
     * 先按学期 id 找，学期 id 未知时取今天所在学期。都找不到返回空串。
     *
     * @param builtinCalendar 能否用东华理工内置校历兜底；其他学校传 false，
     *                        没设开学日期时返回空串，界面提示用户去设置。
     */
    @JvmStatic
    @JvmOverloads
    fun effectiveStart(
        startDate: String?,
        semesterId: String?,
        builtinCalendar: Boolean = true
    ): String {
        val s = normalizeToMonday(startDate)
        if (s.isNotEmpty()) return s
        if (!builtinCalendar) return ""
        var t = AcademicCalendar.termById(semesterId)
        if (t == null && semesterId.isNullOrEmpty()) {
            t = AcademicCalendar.termFor(todayDateLabel())
        }
        return t?.classStart ?: ""
    }

    /** 任意日期落在第几周（周一为一周起点；解析失败返回 -1，可能越界由调用方收敛）。 */
    @JvmStatic
    fun weekOfDate(startDate: String?, ymd: String?): Int {
        val start = parseDate(normalizeToMonday(startDate)) ?: return -1
        val day = parseDate(normalizeToMonday(ymd)) ?: return -1
        val diff = day.timeInMillis - start.timeInMillis
        val days = Math.round(diff / (24.0 * 60 * 60 * 1000))
        // floorDiv：开学前的日期得到 0、-1…，而不是被截断成第 1 周
        return Math.floorDiv(days, 7L).toInt() + 1
    }

    /** 距开学还有几天；已开学或未设置返回 0。 */
    @JvmStatic
    fun daysUntilStart(startDate: String?): Int {
        val start = parseDate(normalizeToMonday(startDate)) ?: return 0
        val today = parseDate(todayDateLabel()) ?: return 0
        val d = Math.round((start.timeInMillis - today.timeInMillis) / (24.0 * 60 * 60 * 1000))
        return if (d > 0) d.toInt() else 0
    }

    /** 第 week 周周一的 yyyy-MM-dd；开学日期无效返回空串。 */
    @JvmStatic
    fun weekRangeStart(startDate: String?, week: Int): String {
        val cal = parseDate(normalizeToMonday(startDate)) ?: return ""
        cal.add(Calendar.DAY_OF_YEAR, (week - 1) * 7)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    @JvmStatic
    fun weekDates(startDate: String?, week: Int): Array<String> {
        val parsed = parseDate(normalizeToMonday(startDate))
        val cal = parsed ?: Calendar.getInstance().apply {
            val dow = get(Calendar.DAY_OF_WEEK)
            add(Calendar.DAY_OF_YEAR, if (dow == Calendar.SUNDAY) -6 else Calendar.MONDAY - dow)
        }
        cal.add(Calendar.DAY_OF_YEAR, (Math.max(1, week) - 1) * 7)

        val out = ArrayList<String>(7)
        val fmt = SimpleDateFormat("MM/dd", Locale.US)
        for (i in 0..6) {
            out.add(fmt.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out.toTypedArray()
    }

    /**
     * 将任意日期归一到所在周的周一。
     *
     * 学期第一周固定从周一开始；旧数据或日期选择器传入其他星期时，
     * 在这里统一收口，避免周日期范围从周三、周五开始。
     */
    @JvmStatic
    fun normalizeToMonday(date: String?): String {
        val cal = parseDate(date) ?: return ""
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val offsetToMonday = if (dow == Calendar.SUNDAY) -6 else Calendar.MONDAY - dow
        cal.add(Calendar.DAY_OF_YEAR, offsetToMonday)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    @JvmStatic
    fun isMonday(date: String?): Boolean {
        val cal = parseDate(date)
        return cal != null && cal.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY
    }

    @JvmStatic
    fun weekRangeLabel(startDate: String?, week: Int): String {
        val dates = weekDates(startDate, week)
        return dates[0] + " - " + dates[6]
    }

    @JvmStatic
    fun shortDate(date: String?): String {
        val cal = parseDate(date) ?: return ""
        return SimpleDateFormat("M/d", Locale.US).format(cal.time)
    }

    /**
     * 从课表里最早的周次与星期反推首节课日期。
     * 例：第一周 2026-09-07，最早课程在第 5 周周一，则返回 2026-10-05。
     */
    @JvmStatic
    fun firstCourseDate(startDate: String?, timetable: Timetable?): String {
        val start = parseDate(normalizeToMonday(startDate))
        if (start == null || timetable?.courses == null) return ""

        var bestWeek = Int.MAX_VALUE
        var bestDay = 0
        for (course in timetable.courses) {
            val sessions = course.sessions ?: continue
            for (session in sessions) {
                val day = Math.max(1, Math.min(7, session.dayOfWeek))
                val weeks = session.weeks
                if (weeks == null || weeks.isEmpty()) continue
                for (week in weeks) {
                    if (week < 1) continue
                    if (week < bestWeek || (week == bestWeek && day < bestDay)) {
                        bestWeek = week
                        bestDay = day
                    }
                }
            }
        }
        if (bestWeek == Int.MAX_VALUE) return ""

        val first = start.clone() as Calendar
        first.add(Calendar.DAY_OF_YEAR, (bestWeek - 1) * 7 + bestDay - 1)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(first.time)
    }

    @JvmStatic
    fun firstCourseLabel(startDate: String?, timetable: Timetable?): String {
        val date = firstCourseDate(startDate, timetable)
        if (date.isEmpty()) return "暂无可计算的首节课"
        val cal = parseDate(date)
        val dow = if (cal == null) 1 else cal.get(Calendar.DAY_OF_WEEK)
        val dayNames = arrayOf("日", "一", "二", "三", "四", "五", "六")
        return shortDate(date) + " 周" + dayNames[dow - 1]
    }

    @JvmStatic
    fun parseDate(date: String?): Calendar? {
        if (date == null || date.trim().isEmpty()) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.isLenient = false
        return try {
            val d: Date = fmt.parse(date.trim()) ?: return null
            val c = Calendar.getInstance()
            c.time = d
            c
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun todayDateLabel(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
