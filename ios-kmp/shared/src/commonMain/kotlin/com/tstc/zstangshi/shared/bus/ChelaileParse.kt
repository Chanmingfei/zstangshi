package com.tstc.zstangshi.shared.bus

import com.tstc.zstangshi.shared.Js

/**
 * 车来了两个列表接口的解析（从 Android `ChelaileClient.nearby / lineDetail` 移植）。
 *
 * 移植时只保留了界面真正渲染的字段；上游那堆 `dispLineSize` / `sortPolicy` /
 * `busDisplayConfig` 之类的展示配置一律忽略 —— 我们有自己的排版。
 */

/** 内部临时结构：一辆候选车的原始读数，用于排序。 */
private class Candidate(val stops: Int, val secs: Int, val dist: Int, val state: Int)

/**
 * 附近站点。`limit` 控制返回条数（上游一次最多给 15 个站）。
 *
 * 注意：上游只给**最靠前的两三个站**带实时到站，其余站的 `lines` 里没有 `stnStates`，
 * 解析出来就是 `realtime=false` + `pending=true`，界面要能显示「…」而不是「暂无车」。
 */
fun parseNearby(body: String, limit: Int, userLat: Double = 0.0, userLng: Double = 0.0): List<BusStation> {
    val data = ChelaileCrypto.extract(body) ?: return emptyList()
    val arr = data.optArr("nearSts") ?: return emptyList()
    val out = ArrayList<BusStation>()
    for (item in arr.items) {
        if (out.size >= limit) break
        val s = item as? Js.Obj ?: continue
        // 纯地铁站不作为「附近公交站」展示（追客 issue 里踩过：混进来一排没车的站）
        val isSubway = s.optInt("isSubway", 0) == 1 || s.optInt("subwayV2", 0) == 1
        val sn = s.optString("sn").trim()
        if (isSubway || Geo.isSubwayStationName(sn)) continue

        val st = BusStation()
        st.id = s.optString("sId")
        st.name = sn
        st.physicalId = s.optString("physicalStId")
        st.namesakeId = s.optString("namesakeStId")
        st.lat = s.optDouble("lat", 0.0)
        st.lng = s.optDouble("lng", 0.0)
        // 上游给的距离优先；没给就用用户位置与站点坐标自算，别把距离留成 -1
        val serverDist = if (s.has("distance")) s.optInt("distance", -1) else -1
        st.distanceMeters = if (serverDist > 0) serverDist
        else Geo.haversineMeters(userLat, userLng, st.lat, st.lng)
        readArrivals(s.optArr("lines")) { st.addArrival(it) }
        if (st.name.isEmpty()) continue
        out.add(st)
    }
    return out
}

/** 线路详情：站表 + 在途车辆。 */
fun parseLineDetail(body: String, lineId: String): BusLineDetail {
    val d = BusLineDetail()
    val data = ChelaileCrypto.extract(body) ?: return d

    val lo = data.optObj("line")
    if (lo != null) {
        d.line.lineId = lineId
        d.line.name = lo.optString("name").ifEmpty { lo.optString("lineNo") }
        d.line.lineNo = lo.optString("lineNo")
        d.line.direction = lo.optInt("direction", 0)
        d.line.startName = lo.optString("startSn")
        d.line.endName = lo.optString("endSn")
        d.line.firstTime = lo.optString("firstTime")
        d.line.lastTime = lo.optString("lastTime")
        d.line.price = lo.optString("price")
        d.line.stationsNum = lo.optInt("stationsNum", 0)
    }
    d.status = data.optString("depDesc")
    d.nextDeparture = data.optString("preArrivalTime")
    // 收班/未运营时 depDesc 常是空串，官方状态写在 line.shortDesc，
    // 再退一步用 nextOperationTimeDesc。不补这层，首页有状态、详情变「暂无预测」。
    if (lo != null && d.status.isEmpty()) {
        d.status = lo.optString("shortDesc")
        if (d.status.isEmpty()) d.status = lo.optString("nextOperationTimeDesc")
    }

    val rev = data.optArr("otherlines")?.items?.firstOrNull() as? Js.Obj
    if (rev != null) {
        d.reverseId = rev.optString("lineId")
        d.reverseStart = rev.optString("startSn")
        d.reverseEnd = rev.optString("endSn")
    }

    val sts = data.optArr("stations")
    if (sts != null) {
        for (i in sts.items.indices) {
            val s = sts.items[i] as? Js.Obj ?: continue
            val stop = BusStop()
            stop.id = s.optString("sId")
            stop.physicalId = s.optString("physicalStId")
            stop.name = s.optString("sn")
            // 站序取数组位置：虚拟站组（sId 形如 999$13$4、order=999）
            // 会让 order 出现「13-1、13-2」这种编号，和「全线 N 站」对不上。
            stop.order = i + 1
            stop.lat = s.optDouble("wgsLat", 0.0)
            stop.lng = s.optDouble("wgsLng", 0.0)
            d.addStop(stop)
        }
    }

    val buses = data.optArr("buses")
    if (buses != null) {
        for (item in buses.items) {
            val b = item as? Js.Obj ?: continue
            val order = b.optInt("order", 0)
            if (order <= 0) continue
            val v = BusVehicle()
            v.id = b.optString("busId")
            val plate = BusText.sanitizePlate(b.optString("licence"))
            v.plate = plate.ifEmpty { BusText.sanitizePlate(v.id) }
            v.order = order
            v.speedKmh = b.optDouble("speed", 0.0)
            v.nextStopName = b.optString("nextStationName").trim()
            d.addVehicle(v)
        }
        d.sortVehiclesByOrder()
    }
    return d
}

// ──────────────────────────── 到站解析 ────────────────────────────

/**
 * 解析 `lines[]`：每条的 `stnStates` 里 `value` = 距本站还剩几站，
 * `travelTime` = 秒。按到站先后排序后取前两辆。
 */
private fun readArrivals(lines: Js.Arr?, onEach: (BusArrival) -> Unit) {
    if (lines == null) return
    for (item in lines.items) {
        val li = item as? Js.Obj ?: continue
        val lo = li.optObj("line") ?: continue
        if (lo.optInt("subwayV2", 0) == 1) continue   // 地铁线路不走这套

        val a = BusArrival()
        a.lineId = lo.optString("lineId")
        a.lineName = lo.optString("name").ifEmpty { lo.optString("lineNo") }
        a.direction = lo.optInt("direction", 0)
        a.startName = lo.optString("startSn")
        a.endName = lo.optString("endSn")
        a.status = lo.optString("shortDesc").ifEmpty { lo.optString("nextOperationTimeDesc") }
        if (a.lineName.isEmpty()) continue
        // 同一站的其他站台会回「只有名字没有 lineId」的占位线路，混进来就是一排「暂无车」
        if (a.lineId.isEmpty()) continue

        val target = li.optObj("targetStation")
        if (target != null) {
            a.orderInLine = effectiveOrder(target.optInt("order", 0), target.optString("sId"))
        }

        val states = li.optArr("stnStates")
        a.realtime = states != null && states.size > 0
        val cand = ArrayList<Candidate>()
        if (states != null) {
            for (s in states.items) {
                val b = s as? Js.Obj ?: continue
                val stops = b.optInt("value", -1)
                val secs = b.optInt("travelTime", -1)
                val state = b.optInt("state", 0)
                val dist = b.optInt("distanceToDest", -1)
                // state==1 是「已到站」，条目可能 value/travelTime 都是 -1，不能丢
                if (stops < 0 && secs < 0 && state != 1) continue
                cand.add(Candidate(stops, secs, dist, state))
            }
        }
        cand.sortWith(compareBy({ if (it.stops >= 0) it.stops else 9999 },
            { if (it.secs < 0) 99999 else it.secs }))

        a.pending = cand.isEmpty() && a.orderInLine > 0
        if (cand.isNotEmpty()) {
            val f = cand[0]
            a.stopsAway = f.stops
            a.distanceMeters = f.dist
            a.minutes = BusText.minutesOfSecs(f.secs)
            a.arrived = BusText.arrivedOf(a.minutes, f.secs, f.state, f.stops)
            if (cand.size > 1) {
                val s2 = cand[1]
                a.nextStopsAway = s2.stops
                a.nextMinutes = BusText.minutesOfSecs(s2.secs)
            }
        }
        onEach(a)
    }
}

/** 虚拟站组（order=999、sId 形如 `999$13$4`）还原成真实站序。 */
fun effectiveOrder(order: Int, sId: String?): Int {
    if (order < 999) return order
    val seg = (sId ?: "").split("$")
    if (seg.size >= 3) {
        val a = seg[1].toIntOrNull()
        val b = seg[2].toIntOrNull()
        if (a != null && b != null) return a + b
    }
    return order
}

// ──────────────────────────── 站点详情 ────────────────────────────

/**
 * 站点详情（`stop!encryptedPhyStnDetail.action`）。
 *
 * 返回的是一个物理站台下的多个「同名站台」：同一路公交在不同方向/不同站台分别停，
 * 上游拆成多条 stationList。所以这里**不合并**，交给界面用站台切换（和追客一致）。
 */
fun parseStopDetail(body: String, userLat: Double = 0.0, userLng: Double = 0.0): List<BusStation> {
    val data = ChelaileCrypto.extract(body) ?: return emptyList()
    val list = data.optArr("stationList") ?: return emptyList()
    val out = ArrayList<BusStation>()
    for (item in list.items) {
        val st = item as? Js.Obj ?: continue
        val station = BusStation()
        station.id = st.optString("sId")
        station.name = st.optString("sn")
        station.physicalId = st.optString("physicalStId")
        station.namesakeId = st.optString("namesakeStId")
        station.lat = st.optDouble("lat", 0.0)
        station.lng = st.optDouble("lng", 0.0)
        val serverDist = if (st.has("distance")) st.optInt("distance", -1) else -1
        station.distanceMeters = if (serverDist > 0) serverDist
        else Geo.haversineMeters(userLat, userLng, station.lat, station.lng)
        readArrivals(st.optArr("lines")) { station.addArrival(it) }
        if (station.name.isEmpty()) continue
        out.add(station)
    }
    return out
}

// ──────────────────────────── 发车时刻表 ────────────────────────────

/**
 * 首站发车时刻表。
 *
 * 上游给三块，按「越新越优先」取，但**块里真有班次才算数**：
 * 实测唐山 1 路曾出现 today=0 段 / yesterday=0 段 / beforeYesterday=4 段，
 * 只认前两块就会把整张首站发车卡判成「没有时刻表」。
 * 这里三块都探，取到哪块就在 `historyTitle` 里写清是哪天的数据。
 */
fun parseTimetable(body: String): BusTimetable {
    val t = BusTimetable()
    val data = ChelaileCrypto.extract(body) ?: return t

    val lo = data.optObj("line")
    if (lo != null) {
        t.lineName = lo.optString("name")
        t.startName = lo.optString("startSn")
        t.endName = lo.optString("endSn")
    }

    val todayBlock = data.optObj("timetable")
    val yBlock = data.optObj("yesterdayTimetable")
    val bBlock = data.optObj("beforeYesterdayTimetable")

    val todaySegs = readScheduled(todayBlock)
    val ySegs = readScheduled(yBlock)
    val bSegs = readScheduled(bBlock)

    if (todaySegs.isNotEmpty()) {
        t.depTitle = todayBlock?.optString("depTitle") ?: ""
        t.tip = todayBlock?.optString("tip") ?: todayBlock?.optString("depDesc") ?: ""
        todaySegs.forEach { t.addToday(it) }
    }
    if (ySegs.isNotEmpty()) {
        ySegs.forEach { t.addHistory(it) }
        t.historyTitle = "昨日实际班次"
        if (t.depTitle.isEmpty()) t.depTitle = yBlock?.optString("depTitle") ?: ""
        if (t.tip.isEmpty()) t.tip = yBlock?.optString("tip") ?: ""
    } else if (bSegs.isNotEmpty()) {
        bSegs.forEach { t.addHistory(it) }
        t.historyTitle = "前日实际班次"
        if (t.depTitle.isEmpty()) t.depTitle = bBlock?.optString("depTitle") ?: ""
        if (t.tip.isEmpty()) t.tip = "本时刻表取自前日的实际发车记录，仅供参考"
    }

    val hist = t.historyTimes()
    if (hist.isNotEmpty()) {
        t.firstDep = hist.first()
        t.lastDep = hist.last()
    }
    for (seg in t.today) {
        if (seg.interval > 0) { t.interval = seg.interval; break }
    }
    return t
}

/** 一段内的班次。`schedules` 是对象数组，`times` 是字符串数组，两种都兼容。 */
private fun readScheduled(block: Js.Obj?): List<BusTimetableSegment> {
    if (block == null) return emptyList()
    val segs = block.optArr("timetable") ?: return emptyList()
    val out = ArrayList<BusTimetableSegment>()
    for (item in segs.items) {
        val seg = item as? Js.Obj ?: continue
        val s = BusTimetableSegment()
        s.fromTime = seg.optString("fTime")
        s.toTime = seg.optString("eTime")
        s.interval = seg.optInt("depInterval", -1)

        val schedules = seg.optArr("schedules")
        if (schedules != null && schedules.size > 0) {
            for (sc in schedules.items) {
                val time = when (sc) {
                    is Js.Obj -> sc.optString("time")
                    is Js.Str -> sc.value
                    else -> ""
                }
                if (time.isNotEmpty()) s.addTime(time)
            }
        } else {
            val times = seg.optArr("times")
            if (times != null) {
                for (sc in times.items) {
                    val time = (sc as? Js.Str)?.value ?: ""
                    if (time.isNotEmpty()) s.addTime(time)
                }
            }
        }
        // 光有分组、班次为空的段不算数（否则渲染出一堆空网格）
        if (s.times.isEmpty()) continue
        out.add(s)
    }
    return out
}
