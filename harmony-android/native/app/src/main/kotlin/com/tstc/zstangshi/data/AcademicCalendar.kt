package com.tstc.zstangshi.data

import java.util.Calendar
import java.util.Locale

/**
 * 唐山师范学院校历（内置数据，来源：教务处校历 jwc.tstc.edu.cn，2026-05-28 发布）。
 * 日期一律 yyyy-MM-dd 字符串。
 * 校历备注：8/29-8/30 报到，8/31 正式上课；本学期共 19 周（上课 17 + 考试 2）。
 * 每年学校发布新校历后，在 TERMS 里追加一个学年即可。
 */
object AcademicCalendar {

    const val SOURCE_LIST_URL = "https://jwc.tstc.edu.cn/xlcx.htm"

    class Term(
        @JvmField val id: String,          // 2026-2027-1
        @JvmField val name: String,        // 2026-2027 学年第一学期
        @JvmField val classStart: String,  // 第 1 周周一
        @JvmField val teachingWeeks: Int,
        @JvmField val examStart: String,
        @JvmField val examEnd: String,
        @JvmField val breakName: String,
        @JvmField val breakStart: String,
        @JvmField val breakEnd: String
    )

    class Event(
        @JvmField val start: String,       // 含首尾
        @JvmField val end: String,
        @JvmField val label: String,
        /** 放假/节日（true）还是校内安排（false） */
        @JvmField val holiday: Boolean
    ) {
        fun covers(ymd: String?): Boolean {
            if (ymd == null) return false
            return ymd >= start && ymd <= end
        }
    }

    @JvmField val TERMS: MutableList<Term> = ArrayList()
    @JvmField val EVENTS: MutableList<Event> = ArrayList()

    init {
        // ===== 2026-2027 学年（唐山师范学院，来源：教务处校历 jwc.tstc.edu.cn）=====
        // 校历备注：8/29-8/30 报到，8/31 正式上课；本学期共 19 周（上课 17 + 考试 2）。
        TERMS.add(
            Term(
                "2026-2027-1", "2026-2027 学年第一学期", "2026-08-31", 19,
                "2026-12-28", "2027-01-10", "寒假", "2027-01-11", "2027-02-26"
            )
        )
        TERMS.add(
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
        EVENTS.add(Event(s, e, label, holiday))
    }

    /** 按学期 id（如 2026-2027-1）找内置校历；没有返回 null。 */
    @JvmStatic
    fun termById(id: String?): Term? {
        if (id.isNullOrEmpty()) return null
        for (t in TERMS) if (t.id == id) return t
        return null
    }

    /** 今天所在（或即将开始）的学期：考试结束 + 假期内仍算这个学期；都过了返回 null。 */
    @JvmStatic
    fun termFor(ymd: String?): Term? {
        if (ymd == null) return null
        for (t in TERMS) {
            if (ymd <= t.breakEnd) return t
        }
        return null
    }

    /** 当天是第几教学周（开学前为 0 或负数）。 */
    @JvmStatic
    fun weekOf(t: Term, ymd: String?): Int {
        val d = daysBetween(t.classStart, ymd)
        return Math.floorDiv(d, 7L).toInt() + 1
    }

    /** 当天正在进行的事件（节假日/考试周等）。 */
    @JvmStatic
    fun eventsOn(ymd: String?): MutableList<Event> {
        val out = ArrayList<Event>()
        for (e in EVENTS) if (e.covers(ymd)) out.add(e)
        return out
    }

    /** 从今天起（含正在进行的）最多 n 个事件。 */
    @JvmStatic
    fun upcoming(ymd: String?, n: Int): MutableList<Event> {
        val out = ArrayList<Event>()
        for (e in EVENTS) {
            if (ymd == null || e.end >= ymd) out.add(e)
            if (out.size >= n) break
        }
        return out
    }

    /** 一句话状态：「第一学期 · 第 8 周 · 距寒假 90 天」。 */
    @JvmStatic
    fun statusLine(ymd: String?): String {
        if (ymd == null) return "校历暂未更新到这一学年"
        val t = termFor(ymd) ?: return "校历暂未更新到这一学年"
        val termShort = if (t.id.endsWith("-1")) "第一学期" else "第二学期"
        if (ymd < t.classStart) {
            return "$termShort · 距开学 ${daysBetween(ymd, t.classStart)} 天"
        }
        if (ymd >= t.breakStart) {
            return t.breakName + "中 · 距" + (if (t.breakName == "寒假") "开学" else "新学年") +
                " " + Math.max(0L, daysBetween(ymd, t.breakEnd) + 1) + " 天"
        }
        if (ymd >= t.examStart) {
            return "$termShort · 期末考试周 · 距${t.breakName} ${daysBetween(ymd, t.breakStart)} 天"
        }
        val w = weekOf(t, ymd)
        return "$termShort · 第 $w 周 · 距${t.breakName} ${daysBetween(ymd, t.breakStart)} 天"
    }

    @JvmStatic
    fun daysBetween(fromYmd: String?, toYmd: String?): Long {
        val a = parse(fromYmd) ?: return 0
        val b = parse(toYmd) ?: return 0
        return Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0)
    }

    @JvmStatic
    fun shortDate(ymd: String?): String {
        val c = parse(ymd) ?: return ymd ?: ""
        return String.format(Locale.US, "%02d.%02d", c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun parse(ymd: String?): Calendar? {
        if (ymd == null || ymd.length < 10) return null
        return try {
            val c = Calendar.getInstance()
            c.clear()
            c.set(
                ymd.substring(0, 4).toInt(), ymd.substring(5, 7).toInt() - 1,
                ymd.substring(8, 10).toInt(), 12, 0, 0
            )
            c
        } catch (e: Exception) {
            null
        }
    }
}
