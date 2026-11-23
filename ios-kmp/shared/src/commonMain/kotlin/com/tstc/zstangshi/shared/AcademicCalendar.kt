package com.tstc.zstangshi.shared

/**
 * 唐山师范学院校历（内置数据）—— 从 Android `data/AcademicCalendar.kt` 移植。
 *
 * 全部是纯逻辑：日期一律 yyyy-MM-dd 字符串比较（字典序 == 时间序），
 * 天数差走 [Dates] 的 epoch day 内核（不受时区/夏令时影响）。
 * 校历页按安卓口径只画「教学周小点 + 今日高亮」，不编节假日色点 ——
 * 安卓注释明确写了「放假、调休这类细项没有可靠数据源，就不编」。
 * EVENTS 仍然完整迁移，供关键节点列表/将来扩展使用。
 */
object AcademicCalendar {

    const val SOURCE_LIST_URL = "https://jwc.tstc.edu.cn/xlcx.htm"

    class Term(
        val id: String,
        val name: String,
        val classStart: String,
        val teachingWeeks: Int,
        val examStart: String,
        val examEnd: String,
        val breakName: String,
        val breakStart: String,
        val breakEnd: String
    )

    class Event(
        val start: String,
        val end: String,
        val label: String,
        /** true = 放假/节日；false = 校内安排 */
        val holiday: Boolean
    ) {
        fun covers(ymd: String?): Boolean =
            ymd != null && ymd >= start && ymd <= end
    }

    /** 月历单格（供 SwiftUI 直接 ForEach，ymd 唯一）。 */
    class Cell(
        val dayNo: Int,
        val ymd: String,
        val week: Int,
        val isToday: Boolean,
        val isSelected: Boolean,
        val inTeachingWeek: Boolean
    )

    // 对外一律只读 List：K/N 会把 MutableList 导成 NSMutableArray，Swift 侧没法 ForEach。
    private val _terms = ArrayList<Term>()
    private val _events = ArrayList<Event>()
    val terms: List<Term> get() = _terms
    val events: List<Event> get() = _events

    init {
        _terms.add(
            Term(
                "2026-2027-1", "2026-2027 学年第一学期", "2026-08-31", 19,
                "2026-12-28", "2027-01-10", "寒假", "2027-01-11", "2027-02-26"
            )
        )
        _terms.add(
            Term(
                "2026-2027-2", "2026-2027 学年第二学期", "2027-03-01", 17,
                "2027-06-26", "2027-07-02", "暑假", "2027-07-03", "2027-08-31"
            )
        )
        ev("2026-09-10", "2026-09-10", "教师节", true)
        ev("2026-09-22", "2026-09-24", "秋季运动会", false)
        ev("2026-09-25", "2026-09-25", "中秋节", true)
        ev("2026-10-01", "2026-10-07", "国庆节", true)
        ev("2027-01-01", "2027-01-01", "元旦", true)
        ev("2027-01-11", "2027-02-26", "寒假", true)
        ev("2027-04-05", "2027-04-05", "清明节", true)
        ev("2027-05-01", "2027-05-01", "劳动节", true)
        ev("2027-06-09", "2027-06-09", "端午节", true)
        ev("2027-07-03", "2027-08-31", "暑假", true)
    }

    private fun ev(s: String, e: String, label: String, holiday: Boolean) {
        _events.add(Event(s, e, label, holiday))
    }

    fun termById(id: String?): Term? {
        if (id.isNullOrEmpty()) return null
        for (t in _terms) if (t.id == id) return t
        return null
    }

    /** 今天所在（或即将开始）的学期；假期内仍算这个学期。 */
    fun termFor(ymd: String?): Term? {
        if (ymd == null) return null
        for (t in _terms) if (ymd <= t.breakEnd) return t
        return null
    }

    /** 从 classStart 起算第几教学周（开学前为 0 或负数）。 */
    fun weekOf(classStart: String?, ymd: String?): Int {
        val a = Dates.parseYmd(classStart) ?: return -1
        val b = Dates.parseYmd(ymd) ?: return -1
        return Dates.floorDiv(b - a, 7) + 1
    }

    fun eventsOn(ymd: String?): List<Event> {
        val out = ArrayList<Event>()
        for (e in _events) if (e.covers(ymd)) out.add(e)
        return out
    }

    /** 从今天起（含正在进行的）最多 n 个事件。 */
    fun upcoming(ymd: String?, n: Int): List<Event> {
        val out = ArrayList<Event>()
        for (e in _events) {
            if (ymd == null || e.end >= ymd) out.add(e)
            if (out.size >= n) break
        }
        return out
    }

    /** 首页状态句：「第一学期 · 第 8 周 · 距寒假 90 天」。 */
    fun statusLine(ymd: String?): String {
        if (ymd == null) return "校历暂未更新到这一学年"
        val t = termFor(ymd) ?: return "校历暂未更新到这一学年"
        val termShort = if (t.id.endsWith("-1")) "第一学期" else "第二学期"
        if (ymd < t.classStart) {
            return "$termShort · 距开学 ${daysBetween(ymd, t.classStart)} 天"
        }
        if (ymd >= t.breakStart) {
            val to = if (t.breakName == "寒假") "开学" else "新学年"
            return t.breakName + "中 · 距" + to + " " +
                maxOf(0, daysBetween(ymd, t.breakEnd) + 1) + " 天"
        }
        if (ymd >= t.examStart) {
            return "$termShort · 期末考试周 · 距${t.breakName} ${daysBetween(ymd, t.breakStart)} 天"
        }
        val w = weekOf(t.classStart, ymd)
        return "$termShort · 第 $w 周 · 距${t.breakName} ${daysBetween(ymd, t.breakStart)} 天"
    }

    fun daysBetween(fromYmd: String?, toYmd: String?): Int {
        val a = Dates.parseYmd(fromYmd) ?: return 0
        val b = Dates.parseYmd(toYmd) ?: return 0
        return b - a
    }

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

    fun shortDate(ymd: String?): String {
        val d = Dates.parseYmd(ymd) ?: return ymd ?: ""
        val y = Dates.civilFromDays(d)
        return pad2(y.month) + "." + pad2(y.day)
    }

    /** 周一为 0 的首日偏移。 */
    fun monthOffset(year: Int, month: Int): Int {
        if (month < 1 || month > 12) return 0
        val first = Dates.daysFromCivil(year, month, 1)
        return Dates.dayOfWeek(first) - 1
    }

    /** 当月天数。 */
    fun monthDays(year: Int, month: Int): Int = Dates.daysInMonth(year, month)

    /** 当月占几行（4~6），Swift 据此撑高度。 */
    fun monthRows(year: Int, month: Int): Int {
        val n = monthOffset(year, month) + monthDays(year, month)
        return maxOf(4, (n + 6) / 7)
    }

    /**
     * 生成整月格子（含上下月留白处的 null 由 Swift 处理：dayNo=0 表示空格）。
     * todayEpochDay 由平台注入（今天），selectedYmd 是当前选中日。
     */
    fun monthCells(
        year: Int,
        month: Int,
        startDate: String?,
        totalWeeks: Int,
        todayEpochDay: Int,
        selectedYmd: String?
    ): List<Cell> {
        val offset = monthOffset(year, month)
        val days = monthDays(year, month)
        val todayYmd = Dates.formatYmd(todayEpochDay)
        val weeks = if (totalWeeks <= 0) 20 else totalWeeks
        val out = ArrayList<Cell>(42)
        for (i in 0 until 42) {
            val dayNo = i - offset + 1
            if (dayNo < 1 || dayNo > days) continue
            val ymd = "$year-${pad2(month)}-${pad2(dayNo)}"
            val w = weekOf(startDate, ymd)
            out.add(
                Cell(
                    dayNo = dayNo,
                    ymd = ymd,
                    week = w,
                    isToday = ymd == todayYmd,
                    isSelected = ymd == selectedYmd,
                    inTeachingWeek = w in 1..weeks
                )
            )
        }
        return out
    }

    /** 选中日说明（对齐安卓 CalendarPage.updateDayInfo）。 */
    fun dayInfo(selectedYmd: String?, startDate: String?, totalWeeks: Int): String {
        if (Dates.parseYmd(selectedYmd) == null || Dates.parseYmd(startDate) == null) {
            return "选一天看看是第几周"
        }
        val week = weekOf(startDate, selectedYmd)
        val d = Dates.parseYmd(selectedYmd)!!
        val dow = "一二三四五六日"[Dates.dayOfWeek(d) - 1]
        return when {
            week < 1 -> "$selectedYmd · 周$dow\n开学前，第 1 周从 $startDate 开始"
            week > totalWeeks -> "$selectedYmd · 周$dow\n本学期（共 $totalWeeks 周）已经结束"
            else -> "$selectedYmd · 周$dow · 第 $week 周"
        }
    }
}
