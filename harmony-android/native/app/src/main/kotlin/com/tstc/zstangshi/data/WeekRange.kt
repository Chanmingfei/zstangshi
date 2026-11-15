package com.tstc.zstangshi.data

/**
 * 正方教务的周次表达式解析。
 *
 * 真实样本（本机）：`5-12周`、`10-13周`、`9-16周`
 * 必须兼容但本机未出现：
 *   `1-5周,7-12周`   多区间
 *   `1-16周(单)` / `1-16周(双)`   单双周
 *   `1,3,5,7周`      离散周（[ODD]）
 *   `3周`            单周
 *   `1-16周(单周)` / `1-16周(双周)`
 *   `1-16`           无「周」字
 *
 * 解析失败不抛异常：返回全空表示「无法判断」，由上层决定降级策略
 * （保底按全周显示，避免课程凭空消失）。
 */
object WeekRange {

    /** 解析结果 */
    class Parsed(
        /** 周次列表，升序去重。解析失败时为空 */
        @JvmField val weeks: List<Int>,
        /** 原文是否含「单」/「双」标记 */
        @JvmField val hasParity: Boolean,
        /** 单周 true / 双周 false / 无标记 null */
        @JvmField val oddOnly: Boolean?,
        /** 原始表达式 */
        @JvmField val raw: String
    ) {
        fun ok(): Boolean = weeks.isNotEmpty()

        fun contains(week: Int): Boolean = weeks.contains(week)

        fun min(): Int = if (weeks.isEmpty()) 0 else weeks[0]

        fun max(): Int = if (weeks.isEmpty()) 0 else weeks[weeks.size - 1]
    }

    /**
     * 解析周次表达式。
     *
     * @param expr 如 `5-12周`、`1-5周,7-12周`、`1-16周(单)`
     * @return 永不为 null；解析失败时 [Parsed.ok] 为 false
     */
    @JvmStatic
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

        // 2. 剥离所有非数字/非分隔符字符
        //    保留：数字、'-'（区间连接）、','（多区间分隔）
        //    统一：全角逗号/顿号 → ','
        //    丢弃：周、()、（）、单、双、空格
        val sb = StringBuilder(raw.length)
        for (i in raw.indices) {
            val c = raw[i]
            if (c in '0'..'9' || c == '-') {
                sb.append(c)
            } else if (c == ',' || c == '，' || c == '、') {
                sb.append(',')
            }
            // 其余字符（周、括号、单、双、空格）全部丢弃
        }

        val cleaned = sb.toString()
        if (cleaned.isEmpty()) return Parsed(ArrayList(), hasParity, oddOnly, raw)

        // 3. 按逗号切段，每段可能是 "a-b" 或 "a"
        var result = ArrayList<Int>()
        for (part in cleaned.split(",")) {
            if (part.isEmpty()) continue
            val dash = part.indexOf('-')
            if (dash > 0 && dash < part.length - 1) {
                // 区间 a-b
                var a = toInt(part.substring(0, dash))
                var b = toInt(part.substring(dash + 1))
                if (a == null || b == null) continue
                if (a > b) { val t = a; a = b; b = t } // 容错：写反了也认
                if (b - a > 60) continue               // 异常区间，丢弃
                var w = a
                while (w <= b) { addDistinct(result, w); w++ }
            } else {
                // 单值 a，或形如 "5-" / "-5" 的残缺写法
                val a = toInt(part.replace("-", ""))
                if (a != null && a > 0 && a <= 60) addDistinct(result, a)
            }
        }

        // 4. 应用单双周过滤
        if (oddOnly != null) {
            val filtered = ArrayList<Int>()
            for (w in result) {
                val isOdd = w % 2 == 1
                if (if (oddOnly) isOdd else !isOdd) filtered.add(w)
            }
            result = filtered
        }

        // 5. 排序去重
        result = sortedDistinct(result)
        return Parsed(result, hasParity, oddOnly, raw)
    }

    /** 便捷方法：判断某周是否上课。解析失败时返回 true（保底显示，避免课程消失） */
    @JvmStatic
    fun isActive(expr: String?, week: Int): Boolean {
        val p = parse(expr)
        if (!p.ok()) return true
        return p.contains(week)
    }

    private fun toInt(s: String?): Int? {
        if (s.isNullOrEmpty()) return null
        return try {
            s.trim().toInt()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun addDistinct(list: MutableList<Int>, v: Int) {
        if (v <= 0 || v > 60) return
        if (!list.contains(v)) list.add(v)
    }

    private fun sortedDistinct(input: List<Int>): ArrayList<Int> {
        val out = ArrayList<Int>()
        for (v in input) if (!out.contains(v)) out.add(v)
        out.sort()
        return out
    }

    /** 把周次列表压回区间表达式，用于导入导出时的紧凑表示 */
    @JvmStatic
    fun compress(weeks: List<Int>?): String {
        if (weeks.isNullOrEmpty()) return ""
        val s = sortedDistinct(weeks)
        val sb = StringBuilder()
        var runStart = s[0]
        var prev = runStart
        for (i in 1..s.size) {
            val cur = if (i < s.size) s[i] else Int.MIN_VALUE
            if (cur == prev + 1) {
                prev = cur
                continue
            }
            // 收尾一个区间
            if (sb.isNotEmpty()) sb.append(',')
            if (runStart == prev) sb.append(runStart)
            else sb.append(runStart).append('-').append(prev)
            if (cur != Int.MIN_VALUE) {
                runStart = cur
                prev = cur
            }
        }
        return sb.toString()
    }
}
