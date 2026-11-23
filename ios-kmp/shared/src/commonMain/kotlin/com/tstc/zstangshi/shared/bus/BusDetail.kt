package com.tstc.zstangshi.shared.bus

import com.tstc.zstangshi.shared.Js

/** 单车到候车站的预测。 */
class BusEta {
    var busId: String = ""
    var seconds: Int = -1
    var distanceMeters: Int = -1
    var tip: String = ""
    var order: Int = 0
}

/**
 * `line!encryptedBusDetail.action` 的结果：候车站的到站预测 + 逐车明细。
 *
 * 主卡大字（N 分钟）必须由**同一辆车**算出，小字（车牌/距离/到达时刻）也只准读它。
 * 这辆车刚停靠完时它的 order 已经走到下一站，按站序另挑就会挑到后面那辆，
 * 于是出现「到站了 · 快去上车 · 距本站 11.7 公里」这种自相矛盾的展示（追客踩过）。
 */
class BusPrediction {
    var depDesc: String = ""
    var preArrival: String = ""
    var secs: Int = -1
    var stops: Int = -1
    var distanceMeters: Int = -1
    var arrived: Boolean = false
    var busId: String = ""
    var nextBusId: String = ""
    var nextSecs: Int = -1
    var nextStops: Int = -1

    private val _buses = ArrayList<BusEta>()
    val buses: List<BusEta> get() = _buses

    internal fun addBus(e: BusEta) { _buses.add(e) }

    fun etaOf(busId: String): BusEta? = _buses.firstOrNull { it.busId == busId }
}

fun parseBusDetail(body: String): BusPrediction {
    val p = BusPrediction()
    val data = ChelaileCrypto.extract(body) ?: return p
    p.depDesc = data.optString("depDesc")
    p.preArrival = data.optString("preArrivalTime")

    val buses = data.optArr("buses") ?: return p
    var bestSecs = Int.MAX_VALUE
    var nextSecs = Int.MAX_VALUE

    for (item in buses.items) {
        val b = item as? Js.Obj ?: continue
        val busId = b.optString("busId")
        // 距离跟 travels 无关：上游对「这轮没有到站预测」的车照样给 distanceToWaitStn。
        // 先落距离，再判 ETA —— 否则这类车在逐车卡片上只剩空壳（追客 #60 回归用例）。
        val rawDist = b.optInt("distanceToWaitStn", -1)
        val travels = b.optArr("travels")
        val tr = if (travels == null || travels.size == 0) null else travels.items[0] as? Js.Obj
        val hasEta = tr != null
        val secs = if (tr == null) -1 else tr.optInt("travelTime", -1)
        // 官方口径透传：travelTime==0 就是到站。别用 buses[].state（那只是「停靠在任意站」）
        val arrived = secs == 0

        if (busId.isNotEmpty() && (hasEta || rawDist > 0)) {
            val e = BusEta()
            e.busId = busId
            e.seconds = secs
            e.distanceMeters = rawDist
            e.tip = if (tr == null) "" else tr.optString("recommTip")
            e.order = b.optInt("order", 0)
            p.addBus(e)
        }

        if (secs >= 0) {
            if (secs < bestSecs) {
                nextSecs = bestSecs
                bestSecs = secs
                p.busId = busId
                p.arrived = arrived
            } else if (secs < nextSecs) {
                nextSecs = secs
                p.nextBusId = busId
            }
        }
    }

    if (bestSecs != Int.MAX_VALUE) {
        p.secs = bestSecs
        val best = p.etaOf(p.busId)
        p.distanceMeters = best?.distanceMeters ?: -1
        p.stops = -1   // 站数上游在 travels 里没直接给，界面按站表与车辆 order 推
    }
    if (nextSecs != Int.MAX_VALUE) p.nextSecs = nextSecs
    return p
}
