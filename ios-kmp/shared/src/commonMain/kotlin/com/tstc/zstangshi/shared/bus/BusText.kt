package com.tstc.zstangshi.shared.bus

/**
 * 到站文案（从 Android `bus/BusText.kt` 移植核心部分）。
 *
 * 放在共享层的目的：这些文案规则是「业务口径」，不是排版——
 * 比如「车来了把不到 1 分钟给成 0，官方口径叫『即将到站』，不是『到站了』」，
 * 这种细节两端各写一遍必然漂移。
 */
object BusText {

    /** 秒 → 分钟。0 秒表示已到站；不足 60 秒算 0（即将到站）。 */
    fun minutesOfSecs(secs: Int): Int = when {
        secs < 0 -> -1
        secs == 0 -> 0
        secs < 60 -> 0
        else -> (secs + 59) / 60
    }

    /**
     * 是否「已到站」。
     * 官方口径：travelTime == 0 就是到站；个别条目只给 state=1（车在本站）也算。
     * 但带了秒数的条目一律按秒数说话 —— state=1 的远车停靠别站不能算到站。
     */
    fun arrivedOf(minutes: Int, secs: Int, state: Int, stops: Int): Boolean {
        if (secs == 0) return true
        if (secs > 0) return false
        return state == 1 || stops == 0
    }

    /** 列表一行的「大字」：到站了 / N 分钟 / 即将到站 / N 站 / 状态 / 暂无实时 / 暂无车。 */
    fun rowEtaText(
        minutes: Int,
        stopsAway: Int,
        arrived: Boolean,
        status: String,
        realtime: Boolean,
        pending: Boolean
    ): String {
        if (pending) return "…"
        if (arrived) return "到站了"
        if (minutes > 0) return "$minutes 分钟"
        if (minutes == 0) return "即将到站"
        if (stopsAway > 0) return "$stopsAway 站"
        if (status.isNotEmpty()) return status
        if (!realtime) return "暂无实时"
        return "暂无车"
    }

    /** 列表一行的「小字」：「还有 2 站 · 下一辆 12 分钟」。 */
    fun rowDetailText(
        stopsAway: Int,
        minutes: Int,
        arrived: Boolean,
        nextMinutes: Int,
        nextStopsAway: Int
    ): String {
        val bits = ArrayList<String>()
        if (stopsAway > 0) bits.add("还有 $stopsAway 站")
        else if (!arrived && minutes <= 0) bits.add("暂无到站时间")
        when {
            nextMinutes > 0 -> {
                val tail = if (nextStopsAway > 0) " · $nextStopsAway 站" else ""
                bits.add("下一辆 $nextMinutes 分钟$tail")
            }
            nextStopsAway > 0 -> bits.add("下一辆还有 $nextStopsAway 站")
        }
        return bits.joinToString(" · ")
    }

    /** 右端「蓝色大字」：把同一辆车的分钟与站数并成一句，如「12分钟 · 7站」。 */
    fun rowPrimaryText(
        minutes: Int,
        stopsAway: Int,
        arrived: Boolean,
        status: String,
        realtime: Boolean,
        pending: Boolean
    ): String {
        if (pending) return "…"
        if (arrived) return "到站了"
        val tail = if (stopsAway > 0) " · ${stopsAway}站" else ""
        if (minutes > 0) return "${minutes}分钟$tail"
        if (minutes == 0) return "即将到站$tail"
        if (stopsAway > 0) return "${stopsAway}站"
        if (status.isNotEmpty()) return status
        if (!realtime) return "暂无实时"
        return "暂无车"
    }

    /** 车牌清洗：上游有时给「冀B·12345(司机)」这类脏数据，只留中英文数字。 */
    fun sanitizePlate(raw: String?): String {
        if (raw == null) return ""
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val sb = StringBuilder()
        for (c in t) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }
}
