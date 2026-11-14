package com.tstc.zstangshi.data

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** 纯日期小工具（yyyy-MM-dd），按 UTC 算整天，避免时区/夏令时误差。 */
object Dates {

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    private val CN = arrayOf("", "一", "二", "三", "四", "五", "六", "日")

    /** yyyy-MM-dd → 自 1970-01-01 的天数；格式不对返回 Long.MIN_VALUE。 */
    @JvmStatic
    fun epochDay(ymd: String?): Long {
        if (ymd == null) return Long.MIN_VALUE
        val p = ymd.trim().split("-")
        if (p.size != 3) return Long.MIN_VALUE
        return try {
            val y = p[0].toInt()
            val m = p[1].toInt()
            val d = p[2].toInt()
            if (m < 1 || m > 12 || d < 1 || d > 31) return Long.MIN_VALUE
            val c = GregorianCalendar(UTC)
            c.clear()
            c.set(y, m - 1, d)
            if (c.get(Calendar.DAY_OF_MONTH) != d) return Long.MIN_VALUE
            Math.floorDiv(c.timeInMillis, 86_400_000L)
        } catch (e: NumberFormatException) {
            Long.MIN_VALUE
        }
    }

    @JvmStatic
    fun ofEpochDay(day: Long): String {
        val c = GregorianCalendar(UTC)
        c.timeInMillis = day * 86_400_000L
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    @JvmStatic
    fun valid(ymd: String?): Boolean = epochDay(ymd) != Long.MIN_VALUE

    /** 1=周一 … 7=周日 */
    @JvmStatic
    fun dayOfWeek(ymd: String?): Int {
        val d = epochDay(ymd)
        if (d == Long.MIN_VALUE) return 0
        // 1970-01-01 是周四
        return Math.floorMod(d + 3, 7L).toInt() + 1
    }

    /** 第 week 周星期 day 的日期；开学日期无效返回空串。 */
    @JvmStatic
    fun dateOf(start: String?, week: Int, day: Int): String {
        val s = epochDay(start)
        if (s == Long.MIN_VALUE || week == 0 || day < 1 || day > 7) return ""
        val monday = s - (dayOfWeek(start) - 1)
        return ofEpochDay(monday + (week - 1).toLong() * 7 + (day - 1))
    }

    /** 日期是第几周（开学前 ≤0）；无效返回 0。 */
    @JvmStatic
    fun weekOf(start: String?, ymd: String?): Int {
        val s = epochDay(start)
        val d = epochDay(ymd)
        if (s == Long.MIN_VALUE || d == Long.MIN_VALUE) return 0
        val monday = s - (dayOfWeek(start) - 1)
        return Math.floorDiv(d - monday, 7L).toInt() + 1
    }

    @JvmStatic
    fun addDays(ymd: String?, n: Int): String {
        val d = epochDay(ymd)
        return if (d == Long.MIN_VALUE) "" else ofEpochDay(d + n)
    }

    @JvmStatic
    fun cnDay(day: Int): String = if (day in 1..7) "周" + CN[day] else ""

    /** 2026-10-10 → 10月10日 */
    @JvmStatic
    fun md(ymd: String?): String {
        val p = ymd?.split("-") ?: emptyList()
        if (p.size != 3) return ymd ?: ""
        return p[1].toInt().toString() + "月" + p[2].toInt() + "日"
    }
}
