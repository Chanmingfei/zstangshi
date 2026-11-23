package com.tstc.zstangshi.shared.bus

import com.tstc.zstangshi.shared.Js

/**
 * 线路搜索的「结果行」。
 *
 * 为什么不直接把 `Js.Obj` 交给 Swift：那等于让 Swift 去摸一棵动态 JSON 树，
 * 桥接层每取一个字段都要处理 Optional / 类型判断。这里 K/N 的 `data class`
 * 会导出成带属性的普通 ObjC 类，Swift 侧 `hit.name` 就完事。
 */
class BusLineHit(
    val lineId: String,
    val name: String,
    val no: String,
    val startSn: String,
    val endSn: String
) {
    override fun toString(): String = "$name($no) $startSn→$endSn"
}

/**
 * 解析 `query!nSearch.action` 的响应。
 *
 * 真实响应结构（2026-11 实测）：
 *   `**YGKJ{"jsonr":{"data":{"result":{"lineCount":2,"lines":[{...}]}}}}YGKJ##`
 * 注意同名线路会因方向不同出现两次（`direction` 不同），界面上通常合成一行 ——
 * 这里保留原始条数，去重交给界面（和 Android 的 `mergeDirections` 保持一致的分工）。
 */
fun parseSearchHits(body: String): List<BusLineHit> {
    val env = ChelaileCrypto.extract(body) ?: return emptyList()
    val result = env.optObj("result") ?: return emptyList()
    val lines = result.optArr("lines") ?: return emptyList()
    val out = ArrayList<BusLineHit>(lines.size)
    for (item in lines.items) {
        val o = item as? Js.Obj ?: continue
        val lineId = o.optString("lineId")
        val name = o.optString("name").ifEmpty { o.optString("lineNo") }
        if (lineId.isEmpty() || name.isEmpty()) continue
        out.add(
            BusLineHit(
                lineId = lineId,
                name = name,
                no = o.optString("lineNo"),
                startSn = o.optString("startSn"),
                endSn = o.optString("endSn")
            )
        )
    }
    return out
}

/** 搜索结果：线路 + 站点。安卓搜索页分两组展示，站点只列最近的 3 个并给提示。 */
class BusSearchResult {
    var lineCount: Int = 0
    var stationCount: Int = 0
    private val _lines = ArrayList<BusLineHit>()
    private val _stations = ArrayList<BusStation>()
    val lines: List<BusLineHit> get() = _lines
    val stations: List<BusStation> get() = _stations

    internal fun addLine(h: BusLineHit) { _lines.add(h) }
    internal fun addStation(s: BusStation) { _stations.add(s) }

    internal fun sortStationsByDistance() {
        val sorted = _stations.sortedBy { if (it.distanceMeters >= 0) it.distanceMeters else Int.MAX_VALUE }
        _stations.clear()
        _stations.addAll(sorted)
    }
}

/**
 * 解析 `query!nSearch.action` 的完整结果。
 *
 * `result.stations` 里带 `distance`（米），安卓按距离升序只显示前 3 个，
 * 并在下面写一句「共 N 个站点命中，这里只列最接近的 3 个 · 站名写全一些能找得更准」。
 */
fun parseSearchResult(body: String): BusSearchResult {
    val out = BusSearchResult()
    val env = ChelaileCrypto.extract(body) ?: return out
    val result = env.optObj("result") ?: return out
    out.lineCount = result.optInt("lineCount", 0)
    out.stationCount = result.optInt("stationCount", 0)

    val lines = result.optArr("lines")
    if (lines != null) {
        for (item in lines.items) {
            val o = item as? Js.Obj ?: continue
            val lineId = o.optString("lineId")
            val name = o.optString("name").ifEmpty { o.optString("lineNo") }
            if (lineId.isEmpty() || name.isEmpty()) continue
            out.addLine(
                BusLineHit(
                    lineId = lineId,
                    name = name,
                    no = o.optString("lineNo"),
                    startSn = o.optString("startSn"),
                    endSn = o.optString("endSn")
                )
            )
        }
    }

    val stations = result.optArr("stations")
    if (stations != null) {
        for (item in stations.items) {
            val o = item as? Js.Obj ?: continue
            val name = o.optString("sn").trim()
            val id = o.optString("sId")
            if (name.isEmpty() || id.isEmpty()) continue
            val st = BusStation()
            st.id = id
            st.name = name
            st.physicalId = o.optString("physicalStId")
            st.namesakeId = o.optString("namesakeStId")
            st.lat = o.optDouble("lat", 0.0)
            st.lng = o.optDouble("lng", 0.0)
            st.distanceMeters = if (o.has("distance")) o.optInt("distance", -1) else -1
            out.addStation(st)
        }
        // 按距离升序（-1 排后面），界面只取前 3
        out.sortStationsByDistance()
    }
    return out
}
