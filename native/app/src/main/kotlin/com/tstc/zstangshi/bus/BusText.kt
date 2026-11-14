package com.tstc.zstangshi.bus

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 公交文案的统一口径，逐字对齐 Trackr（github.com/5151561/Trackr，MIT）。
 * 线路详情、站点详情、首页都用它，保证同一个数字到哪儿都是同一句话。
 */
object BusText {

    /** 到站徽标：「已到站」「即将到站」「约 24 分钟」「暂无预测」 */
    @JvmStatic
    fun etaText(etaSeconds: Int, arrived: Boolean): String {
        if (arrived) return "已到站"
        if (etaSeconds <= 0) return "暂无预测"
        if (etaSeconds <= 60) return "即将到站"
        val mins = Math.round(etaSeconds / 60.0).toInt().coerceAtLeast(1)
        return "约 $mins 分钟"
    }

    /** 「24 分钟」；没有 ETA 返回 null */
    @JvmStatic
    fun minutesText(etaSeconds: Int): String? {
        if (etaSeconds <= 0) return null
        val mins = Math.round(etaSeconds / 60.0).toInt().coerceAtLeast(1)
        return "$mins 分钟"
    }

    /**
     * 上游秒数 → 界面上的分钟位，首页、站点页、线路详情必须同一步换算：
     *   · 0 秒 = 车在站上（由 arrived 说了算）
     *   · 1..59 秒 = 0（写「即将到站」，不能算成 1 分钟）
     *   · 其余向上取整；-1 = 上游没给
     */
    @JvmStatic
    fun minutesOfSecs(secs: Int): Int = when {
        secs < 0 -> -1
        secs == 0 -> 0
        secs < 60 -> 0
        else -> (secs + 59) / 60
    }

    /**
     * 首页 / 站点详情里一行的「大字」，和线路详情主卡同一个口径：
     * 到站了 · N 分钟 · 即将到站 · N 站 · 状态文案 · 暂无实时 · 暂无车。
     * 对应 Trackr `ArrivalText.format` 的 value+unit 部分。
     */
    @JvmStatic
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
        // 车来了把「不到 1 分钟」给成 0；官方口径是「即将到站」，不是「已到站」
        if (minutes == 0) return "即将到站"
        if (stopsAway > 0) return "$stopsAway 站"
        if (status.isNotEmpty()) return status
        if (!realtime) return "暂无实时"
        return "暂无车"
    }

    /**
     * 首页 / 站点详情里一行的「小字」，和线路详情主卡的小字、底栏同一口径：
     * 「还有 2 站 · 下一辆 12 分钟」「还有 2 站 · 下一辆 12 分钟 · 3 站」。
     * 对应 Trackr `ArrivalText.format` 的 detail，以及 `nextBusMinutes/nextBusStops`。
     */
    @JvmStatic
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

    /**
     * 首页 / 站点页线路行右端的「蓝色大字」：把**同一辆车**的分钟数与站数并成一句，
     * 形如「12分钟 · 7站」（对应车来了那一行的排版）。
     *
     * 只写上游真给了的：没有分钟就只写「7站」，没有站数就只写「12分钟」，
     * 绝不按站数反推分钟，也不拿第二辆车的数据来凑。状态文案原样照抄上游。
     */
    @JvmStatic
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
        // 车来了把「不到 1 分钟」给成 0；官方口径是「即将到站」，不是到站
        if (minutes == 0) return "即将到站$tail"
        if (stopsAway > 0) return "${stopsAway}站"
        if (status.isNotEmpty()) return status
        if (!realtime) return "暂无实时"
        return "暂无车"
    }

    /**
     * 首页 / 站点页线路行右端的「灰色小字」：紧跟着那辆车的真实数据，
     * 形如「下辆 37分钟 22站」。两个数都来自同一辆车的预测，缺哪个就不写哪个；
     * 上游没给第二辆车时返回空串，整行收起，不编一个数字出来。
     */
    @JvmStatic
    fun rowNextText(nextMinutes: Int, nextStopsAway: Int): String {
        if (nextMinutes < 0 && nextStopsAway <= 0) return ""
        val bits = ArrayList<String>(2)
        if (nextMinutes > 0) bits.add("${nextMinutes}分钟")
        else if (nextMinutes == 0) bits.add("即将到站")
        if (nextStopsAway > 0) bits.add("${nextStopsAway}站")
        if (bits.isEmpty()) return ""
        return "下辆 " + bits.joinToString(" ")
    }

    /** 「350米」「1.2公里」；<=0 返回 "" */
    @JvmStatic
    fun distanceText(meters: Int): String {
        if (meters <= 0) return ""
        if (meters < 1000) return "${meters}米"
        return kilometers(meters)
    }

    /** 毫秒时间戳 → 「22:46」（24 小时制，补零） */
    @JvmStatic
    fun clockText(epochMillis: Long): String {
        return SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMillis))
    }

    /** 现在是 now，再过 seconds 秒 → 「22:46」；seconds<=0 返回 "" */
    @JvmStatic
    fun clockAfter(seconds: Int, nowMillis: Long): String {
        if (seconds <= 0) return ""
        return clockText(nowMillis + seconds * 1000L)
    }

    /**
     * 车牌/自编号清洗：过滤上游机器哈希（32 位 MD5、40 位 SHA-1、UUID 之类），
     * 保留人可读的车牌或编号。判据逐字照抄 Trackr 的 `sanitizePlateOrFleetNo`：
     *  - 含汉字：≤12 字保留，超过当垃圾丢掉（机器串偶尔混进中文前缀）
     *  - 不含汉字：≥10 位（或含 `-` 且 ≥8 位）视为机器标识，丢掉
     *  - 其余只保留字母/数字/`·- ` 组成的短编号（如 417、K102、沪A·D8866）
     */
    @JvmStatic
    fun sanitizePlate(raw: String?): String {
        if (raw == null) return ""
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        var hasChinese = false
        for (c in trimmed) {
            if (c >= '\u4e00' && c <= '\u9fa5') {
                hasChinese = true
                break
            }
        }
        if (hasChinese) {
            return if (trimmed.length <= 12) trimmed else ""
        }
        // 无汉字：判定是否为长机器标识（如 40 位 SHA-1、32 位 MD5、UUID、或长数字串）
        val isMachineId = trimmed.length >= 10 || (trimmed.indexOf('-') >= 0 && trimmed.length >= 8)
        if (isMachineId) return ""
        // 纯英文/数字/常见符号的自编号，控制在合理长度内
        for (c in trimmed) {
            if (!(Character.isLetterOrDigit(c) || c == '·' || c == '-' || c == ' ')) return ""
        }
        return trimmed
    }

    /**
     * 米数 → 「1.3公里」：保留 1 位小数，四舍五入（第二位 ≥ 5 进位）。
     * 与 Trackr 的 `formatKilometers` 同算法：(meters + 50) / 100 就是 HALF_UP 后的十分位数。
     */
    private fun kilometers(meters: Int): String {
        val tenths = (meters + 50) / 100
        return "${tenths / 10}.${tenths % 10}公里"
    }
}
