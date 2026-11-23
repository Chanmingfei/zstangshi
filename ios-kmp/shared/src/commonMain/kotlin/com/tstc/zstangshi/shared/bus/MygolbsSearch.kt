package com.tstc.zstangshi.shared.bus

import com.tstc.zstangshi.shared.Js
import com.tstc.zstangshi.shared.net.percentEncode

/**
 * 掌上公交的搜索（CMD 114 线路 / 110 站点）。
 *
 * 输出复用 [BusLineHit] / [BusStation]，让 Swift 的搜索页一套界面服务两个数据源。
 */

fun mygolbsSearchLinesForm(cityName: String, keyword: String): String =
    listOf("CMD" to "114", "CITYNAME" to cityName, "KEYWORD" to keyword, "CITYKEY" to "")
        .joinToString("&") { (k, v) -> "${percentEncode(k)}=${percentEncode(v)}" }

fun mygolbsSearchStopsForm(cityName: String, keyword: String): String =
    listOf("CMD" to "110", "CITYNAME" to cityName, "KEYWORD" to keyword, "CITYKEY" to "")
        .joinToString("&") { (k, v) -> "${percentEncode(k)}=${percentEncode(v)}" }

/** 114：同一线路的上下行会各回一条，按线路名去重（保留先出现的方向）。 */
fun parseMygolbsSearchLines(body: String, cityName: String): List<BusLineHit> {
    val start = body.indexOf('{')
    if (start < 0) return emptyList()
    val root = Js.parse(body.substring(start, ChelaileCrypto.jsonEnd(body, start))) as? Js.Obj
        ?: return emptyList()
    val arr = root.optArr("data") ?: return emptyList()

    val seen = LinkedHashMap<String, BusLineHit>()
    for (item in arr.items) {
        val o = item as? Js.Obj ?: continue
        var name = o.optString("lineName").trim()
        if (name.isEmpty()) name = o.optString("LINENAME").trim()
        if (name.isEmpty()) continue
        val bean = o.optObj("routeBean")
        if (bean != null && "3" == bean.optString("routeType")) continue   // 纯地铁
        if (seen.containsKey(name)) continue

        var up = o.optString("upperOrDown")
        if (up.isEmpty()) up = "1"
        val dir = if ("1" == up) 0 else 1
        val hit = BusLineHit(
            lineId = Mygolbs.lineId(cityName, name, dir),
            name = name,
            no = o.optString("lineNo").ifEmpty { name.replace("路", "") },
            startSn = o.optString("from").ifEmpty { o.optString("startStation") },
            endSn = o.optString("to").ifEmpty { o.optString("endStation") }
        )
        seen[name] = hit
    }
    return seen.values.toList()
}

/** 110：`busstations[]`，字段 stationName。上游不给距离，界面就不显示距离。 */
fun parseMygolbsSearchStops(body: String, cityName: String): List<BusStation> {
    val start = body.indexOf('{')
    if (start < 0) return emptyList()
    val root = Js.parse(body.substring(start, ChelaileCrypto.jsonEnd(body, start))) as? Js.Obj
        ?: return emptyList()
    val arr = root.optArr("busstations") ?: return emptyList()

    val out = ArrayList<BusStation>()
    for (item in arr.items) {
        val o = item as? Js.Obj ?: continue
        val name = o.optString("stationName").trim()
        if (name.isEmpty()) continue
        if (out.any { it.name == name }) continue
        val st = BusStation()
        st.name = name
        st.id = Mygolbs.stopId(cityName, name)
        st.physicalId = st.id
        st.namesakeId = st.id
        st.distanceMeters = o.optInt("distance", -1)
        out.add(st)
    }
    return out
}

/**
 * 把 114 + 110 两个响应合成一个搜索结果（Swift 只调这一个函数）。
 *
 * `BusSearchResult` 的 add 方法是 internal，Swift 侧调不到，
 * 所以在 Kotlin 这边组合好再交出去。
 */
fun composeMygolbsSearchResult(cityName: String, linesBody: String, stopsBody: String): BusSearchResult {
    val out = BusSearchResult()
    val lines = parseMygolbsSearchLines(linesBody, cityName)
    val stops = parseMygolbsSearchStops(stopsBody, cityName)
    for (l in lines) out.addLine(l)
    for (st in stops) out.addStation(st)
    out.lineCount = lines.size
    out.stationCount = stops.size
    out.sortStationsByDistance()
    return out
}
