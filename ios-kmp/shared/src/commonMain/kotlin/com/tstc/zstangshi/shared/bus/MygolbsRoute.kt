package com.tstc.zstangshi.shared.bus

import com.tstc.zstangshi.shared.Js

/**
 * 掌上公交的线路详情（CMD 103 站表 + 104 实时车辆 + 207 时刻表）。
 *
 * 输出刻意复用 [BusLineDetail] / [BusPrediction]：这样 Swift 的线详页一套代码能同时
 * 渲染两个数据源，不用写两套界面。
 */

// ──────────────────────────── 103 站表 ────────────────────────────

fun mygolbsRouteForm(cityName: String, lineName: String, dir: Int): String {
    val d = if (dir == 0) "1" else "2"
    return listOf(
        "CMD" to "103",
        "CITYNAME" to cityName,
        "CITYCODE" to "",
        "LINENAME" to lineName,
        "DIRECTION" to d,
        "CITYKEY" to ""
    ).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
}

/** 解析 103：站表 + 首末班 + 票价。坐标是 GCJ，要转回 WGS。 */
fun parseMygolbsRoute(body: String, cityName: String, lineName: String, dir: Int): BusLineDetail {
    val d = BusLineDetail()
    d.line.lineId = Mygolbs.lineId(cityName, lineName, dir)
    d.line.name = lineName
    d.line.direction = dir

    val start = body.indexOf('{')
    if (start < 0) return d
    val j = Js.parse(body.substring(start, ChelaileCrypto.jsonEnd(body, start))) as? Js.Obj ?: return d

    val fl = j.optArr("firstLast")
    val first = fl?.items?.firstOrNull() as? Js.Obj
    if (first != null) {
        d.line.firstTime = first.optString("first")
        d.line.lastTime = first.optString("last")
    }
    if (d.line.firstTime.isEmpty()) d.line.firstTime = j.optString("beginTime")
    if (d.line.lastTime.isEmpty()) d.line.lastTime = j.optString("endTime")

    // 票价藏在备注里：「票价：1元，...」
    val commonts = j.optString("commonts")
    val idx = commonts.indexOf("票价")
    if (idx >= 0) {
        val tail = commonts.substring(idx + 2)
        val colon = tail.indexOfAny(charArrayOf(':', '：'))
        if (colon >= 0) {
            val v = tail.substring(colon + 1).trim()
            val stop = v.indexOfAny(charArrayOf('，', ',', ' '))
            d.line.price = (if (stop > 0) v.substring(0, stop) else v).trim()
        }
    }

    val arr = j.optArr("data") ?: return d
    var i = 0
    for (item in arr.items) {
        val st = item as? Js.Obj ?: continue
        val stop = BusStop()
        var order = st.optInt("stationOrder", i + 1)
        if (order <= 0) order = i + 1
        stop.order = order
        stop.name = st.optString("stationName").trim()
        if (stop.name.isEmpty()) continue
        val w = Mygolbs.gcj2wgs(st.optDouble("station_lat", 0.0), st.optDouble("station_lon", 0.0))
        stop.lat = w[0]
        stop.lng = w[1]
        stop.id = Mygolbs.stopId(cityName, stop.name)
        stop.physicalId = stop.id
        d.addStop(stop)
        i++
    }
    d.line.stationsNum = d.stops.size
    if (d.stops.isNotEmpty()) {
        d.line.startName = d.stops.first().name
        d.line.endName = d.stops.last().name
    }
    return d
}

// ──────────────────────────── 104 实时车辆 ────────────────────────────

fun mygolbsRealtimeForm(cityName: String, lineName: String, dir: Int, order: Int): String {
    val d = if (dir == 0) "1" else "2"
    return listOf(
        "CMD" to "104",
        "CITYNAME" to cityName,
        "CITYCODE" to "",
        "LINENAME" to lineName,
        "DIRECTION" to d,
        "STATIONORDER" to order.toString(),
        "CITYKEY" to ""
    ).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
}

/**
 * 解析 104。三条判定必须照抄安卓（都是踩过坑写下来的）：
 *   1. `busToStationTime` 是**分钟小数**：≥1 折算成秒；0~1 算「即将到站」给 60 秒；≤0 无预测。
 *   2. `busToStationCount < 0` 且提示里有「非运营/收班/末班/停运」→ 这是**线路级状态**，不是过站。
 *   3. 其余 `count < 0` 是「已经过了这一站」，上游仍列出来且时间写 0，直接用会误判成「已到站」。
 */
fun parseMygolbsRealtime(body: String): BusPrediction {
    val p = BusPrediction()
    val start = body.indexOf('{')
    if (start < 0) return p
    val j = Js.parse(body.substring(start, ChelaileCrypto.jsonEnd(body, start))) as? Js.Obj ?: return p

    p.preArrival = j.optString("planTime")

    // 车辆清单（车牌 + 当前所在站）
    val list = j.optArr("list")
    if (list != null) {
        for (item in list.items) {
            val b = item as? Js.Obj ?: continue
            val number = b.optString("busNumber")
            if (number.isEmpty()) continue
            val v = BusVehicle()
            v.id = number
            v.plate = number
            val idx = b.optInt("index", -1)
            v.order = if (idx >= 0) idx + 1 else 0
            v.nextStopName = b.optString("stationName").trim()
            p.addVehicle(v)
        }
    }

    // 逐车到站
    val eta = j.optArr("routeOnStationRTimeInfoList")
    var bestSecs = Int.MAX_VALUE
    var nextSecs = Int.MAX_VALUE
    if (eta != null) {
        for (item in eta.items) {
            val e = item as? Js.Obj ?: continue
            val bus = e.optString("busNumber")
            if (bus.isEmpty()) continue
            val min = e.optDouble("busToStationTime", -1.0)
            val secs = when {
                min >= 1.0 -> (min * 60).toInt()
                min > 0.0 -> 60
                else -> -1
            }
            val dist = e.optInt("busToStationDistance", -1)
            val count = e.optInt("busToStationCount", -1)
            val tips = e.optString("busToStationTips")

            if (count < 0 && (tips.contains("非运营") || tips.contains("收班") ||
                        tips.contains("末班") || tips.contains("停运"))
            ) {
                if (p.depDesc.isEmpty()) p.depDesc = tips
                continue
            }
            if (count < 0) continue

            val be = BusEta()
            be.busId = bus
            be.seconds = secs
            be.distanceMeters = dist
            be.tip = tips
            p.addBus(be)

            if (secs >= 0) {
                if (secs < bestSecs) {
                    nextSecs = bestSecs
                    bestSecs = secs
                    p.busId = bus
                    p.arrived = secs == 0
                } else if (secs < nextSecs) {
                    nextSecs = secs
                    p.nextBusId = bus
                }
            }
        }
    }

    if (bestSecs != Int.MAX_VALUE) {
        p.secs = bestSecs
        p.distanceMeters = p.etaOf(p.busId)?.distanceMeters ?: -1
    }
    if (nextSecs != Int.MAX_VALUE) p.nextSecs = nextSecs
    return p
}

// ──────────────────────────── 207 时刻表 ────────────────────────────

fun mygolbsTimetableForm(cityName: String, lineName: String, dir: Int): String {
    val d = if (dir == 0) "1" else "2"
    return listOf(
        "CMD" to "207",
        "CITYNAME" to cityName,
        "LINENAME" to lineName,
        "DIRECTION" to d
    ).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
}

/** 解析 207：`data:[{startTime,endTime,interval,timeList[]}]` 一类的分段结构。 */
fun parseMygolbsTimetable(body: String, lineName: String): BusTimetable {
    val t = BusTimetable()
    t.lineName = lineName
    val start = body.indexOf('{')
    if (start < 0) return t
    val j = Js.parse(body.substring(start, ChelaileCrypto.jsonEnd(body, start))) as? Js.Obj ?: return t

    val arr = j.optArr("data") ?: j.optArr("timeList") ?: return t
    val seg = BusTimetableSegment()
    var added = false
    for (item in arr.items) {
        when (item) {
            is Js.Str -> if (item.value.isNotEmpty()) { seg.addTime(item.value); added = true }
            is Js.Obj -> {
                val from = item.optString("startTime").ifEmpty { item.optString("fTime") }
                val to = item.optString("endTime").ifEmpty { item.optString("eTime") }
                if (from.isNotEmpty()) seg.fromTime = from
                if (to.isNotEmpty()) seg.toTime = to
                val times = item.optArr("timeList") ?: item.optArr("times")
                if (times != null) {
                    for (x in times.items) {
                        val s = (x as? Js.Str)?.value ?: ""
                        if (s.isNotEmpty()) { seg.addTime(s); added = true }
                    }
                }
                val single = item.optString("time")
                if (single.isNotEmpty()) { seg.addTime(single); added = true }
            }
            else -> {}
        }
    }
    if (added) {
        t.addToday(seg)
        t.depTitle = "今日发车"
        val times = t.todayTimes()
        if (times.isNotEmpty()) {
            t.firstDep = times.first()
            t.lastDep = times.last()
        }
    }
    return t
}

private fun enc(s: String): String = com.tstc.zstangshi.shared.net.percentEncode(s)
