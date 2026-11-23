package com.tstc.zstangshi.shared

/**
 * 正方教务的周次表达式解析（从 Android `data/WeekRange.kt` 逐字移植）。
 *
 * 真实样本：`5-12周`、`10-13周`、`第9周`、`1-6周,8周`、`10-16周(双)`、`11-15周(单)`
 * 必须兼容但样本里没出现的：`1,3,5,7周`、`1-16`、写反的区间、全角逗号。
 *
 * 移植改动只有一处：`ArrayList.sort()` 是 JVM 专属，换成 `sorted()`（语义相同）。
 */
object WeekRange {

    class Parsed(
        val weeks: List<Int>,
        val hasParity: Boolean,
        val oddOnly: Boolean?,
        val raw: String
    ) {
        fun ok(): Boolean = weeks.isNotEmpty()
        fun contains(week: Int): Boolean = weeks.contains(week)
        fun min(): Int = if (weeks.isEmpty()) 0 else weeks[0]
        fun max(): Int = if (weeks.isEmpty()) 0 else weeks[weeks.size - 1]
    }

    fun parse(expr: String?): Parsed {
        val raw = expr?.trim() ?: ""
        if (raw.isEmpty()) return Parsed(ArrayList(), false, null, raw)

        // 1. 检测单双周标记（必须在剥离中文数字前做）
        var oddOnly: Boolean? = null
        var hasParity = false
        if (raw.contains("单")) {
            hasParity = true
            oddOnly = true
        } else if (raw.contains("双")) {
            hasParity = true
            oddOnly = false
        }

        // 2. 只保留数字、'-'、','（全角逗号/顿号统一）
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            if (c in '0'..'9' || c == '-') {
                sb.append(c)
            } else if (c == ',' || c == '，' || c == '、') {
                sb.append(',')
            }
        }
        val cleaned = sb.toString()
        if (cleaned.isEmpty()) return Parsed(ArrayList(), hasParity, oddOnly, raw)

        // 3. 按逗号切段，每段可能是 "a-b" 或 "a"
        var result = ArrayList<Int>()
        for (part in cleaned.split(",")) {
            if (part.isEmpty()) continue
            val dash = part.indexOf('-')
            if (dash > 0 && dash < part.length - 1) {
                var a = part.substring(0, dash).trim().toIntOrNull()
                var b = part.substring(dash + 1).trim().toIntOrNull()
                if (a == null || b == null) continue
                if (a > b) { val t = a; a = b; b = t }
                if (b - a > 60) continue
                var w = a
                while (w <= b) { addDistinct(result, w); w++ }
            } else {
                val a = part.replace("-", "").trim().toIntOrNull()
                if (a != null) addDistinct(result, a)
            }
        }

        // 4. 单双周过滤
        if (oddOnly != null) {
            val filtered = ArrayList<Int>()
            for (w in result) {
                val isOdd = w % 2 == 1
                if (if (oddOnly) isOdd else !isOdd) filtered.add(w)
            }
            result = filtered
        }

        // 5. 排序去重
        return Parsed(sortedDistinct(result), hasParity, oddOnly, raw)
    }

    /** 解析失败时返回 true（保底显示，避免课程凭空消失）。 */
    fun isActive(expr: String?, week: Int): Boolean {
        val p = parse(expr)
        if (!p.ok()) return true
        return p.contains(week)
    }

    private fun addDistinct(list: MutableList<Int>, v: Int) {
        if (v <= 0 || v > 60) return
        if (!list.contains(v)) list.add(v)
    }

    private fun sortedDistinct(input: List<Int>): ArrayList<Int> {
        val out = ArrayList<Int>()
        for (v in input) if (!out.contains(v)) out.add(v)
        return ArrayList(out.sorted())
    }

    /** 把周次列表压回区间表达式。 */
    fun compress(weeks: List<Int>?): String {
        if (weeks.isNullOrEmpty()) return ""
        val s = sortedDistinct(weeks)
        val sb = StringBuilder()
        var runStart = s[0]
        var prev = runStart
        for (i in 1..s.size) {
            val cur = if (i < s.size) s[i] else Int.MIN_VALUE
            if (cur == prev + 1) { prev = cur; continue }
            if (sb.isNotEmpty()) sb.append(',')
            if (runStart == prev) sb.append(runStart) else sb.append(runStart).append('-').append(prev)
            if (cur != Int.MIN_VALUE) { runStart = cur; prev = cur }
        }
        return sb.toString()
    }
}
