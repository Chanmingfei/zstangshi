package com.tstc.zstangshi.shared

/**
 * 日期工具：以「epoch day」（1970-01-01 起的天数）为唯一表示。
 *
 * 为什么不用 java.util.Calendar / SimpleDateFormat：
 *   这两个类在 KMP 的 commonMain 里都不存在，iOS 侧也没有。而周次计算本质只做
 *   「两个日期差几天」「这天是周几」，用整数运算就够了，还能顺带消掉时区、夏令时
 *   引起的 ±1 小时误差（Android 那边要靠 `Math.round(diff / 86400000.0)` 打补丁）。
 *
 * 算法：Howard Hinnant 的 days_from_civil / civil_from_days，纯整数、无查表。
 */
data class Ymd(val year: Int, val month: Int, val day: Int)

object Dates {

    fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    fun daysInMonth(y: Int, m: Int): Int = when (m) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeap(y)) 29 else 28
        else -> 0
    }

    /** 'yyyy-MM-dd' → epoch day；格式或范围不合法返回 null（对应 Android 的 isLenient=false）。 */
    fun parseYmd(s: String?): Int? {
        if (s == null) return null
        val t = s.trim()
        if (t.length != 10 || t[4] != '-' || t[7] != '-') return null
        val y = t.substring(0, 4).toIntOrNull() ?: return null
        val m = t.substring(5, 7).toIntOrNull() ?: return null
        val d = t.substring(8, 10).toIntOrNull() ?: return null
        if (m < 1 || m > 12) return null
        if (d < 1 || d > daysInMonth(y, m)) return null
        return daysFromCivil(y, m, d)
    }

    fun daysFromCivil(y0: Int, m: Int, d: Int): Int {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    fun civilFromDays(epochDay: Int): Ymd {
        val z = epochDay + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = mp + if (mp < 10) 3 else -9
        return Ymd(if (m <= 2) y + 1 else y, m, d)
    }

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

    fun formatYmd(epochDay: Int): String {
        val y = civilFromDays(epochDay)
        return "${y.year}-${pad2(y.month)}-${pad2(y.day)}"
    }

    /** 课表表头的 'M/d'（不带前导零，和 Android 的 SimpleDateFormat("M/d") 一致）。 */
    fun formatMd(epochDay: Int): String {
        val y = civilFromDays(epochDay)
        return "${y.month}/${y.day}"
    }

    /** 1=周一 … 7=周日。1970-01-01 是周四。 */
    fun dayOfWeek(epochDay: Int): Int {
        val m = (epochDay + 3) % 7
        return (if (m < 0) m + 7 else m) + 1
    }

    /** 向下取整的整除（Kotlin 的 `/` 和 `%` 都是向零截断，开学前的日期会算错）。 */
    fun floorDiv(a: Int, b: Int): Int {
        var q = a / b
        if ((a % b != 0) && ((a xor b) < 0)) q--
        return q
    }
}
