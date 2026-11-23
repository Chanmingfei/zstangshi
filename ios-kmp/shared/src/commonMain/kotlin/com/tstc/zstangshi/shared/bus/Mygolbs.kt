package com.tstc.zstangshi.shared.bus

import com.tstc.zstangshi.shared.Js
import com.tstc.zstangshi.shared.net.percentEncode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 掌上公交（mygolbs）数据源 —— 从 Android `bus/MygolbsClient.kt` 移植。
 *
 * 协议特征：`POST https://h5.mygolbs.com/ApiData.do`，表单只有 `CMD` + 大写参数，
 * 必须带 `Origin` / `Referer`（Swift 侧发请求时补上）。
 *
 * 与车来了的关键差异（移植时最容易搞错的地方）：
 *   1. 城市用**中文名**（`唐山市`），不是数字 cityId；
 *   2. 坐标按 **GCJ-02** 计算距离，查询前必须把 WGS-84 转过去，否则整片站点偏 500 米；
 *   3. 到站不是秒数，而是中文文案（「7站」「即将到站」「小于1分钟」「1小时44分钟」）；
 *   4. 没有统一的 lineId，用 `mg|城市|线路名|1|2` 自己编码。
 */
object Mygolbs {

    const val API = "https://h5.mygolbs.com/ApiData.do"
    const val ORIGIN = "https://h5.mygolbs.com"
    const val REFERER = "https://h5.mygolbs.com/"
    const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120 Mobile Safari/537.36 MicroMessenger/8.0.44"

    /** 掌上公交的城市标识就是中文名。 */
    const val DEFAULT_CITY = "唐山市"

    private const val P_LINE = "mg|"

    /** 把 cityId 归一到掌上公交要的城市名。 */
    fun cityName(cityId: String?): String {
        if (cityId.isNullOrEmpty()) return DEFAULT_CITY
        if (cityId.startsWith(P_LINE)) {
            val seg = cityId.substring(P_LINE.length).split("|")
            if (seg.size >= 3 && seg[0].isNotEmpty()) return seg[0]
        }
        // 数字 cityId（车来了那套）时要落到默认城市，不能把 "056" 当城市名
        if (cityId.all { it.isDigit() }) return DEFAULT_CITY
        return cityId
    }

    fun lineId(city: String, lineName: String, dir: Int): String =
        P_LINE + city + "|" + lineName + "|" + (if (dir == 0) "1" else "2")

    fun stopId(city: String, stationName: String): String = P_LINE + city + "|" + stationName

    /** 解析 `mg|城市|线路名|1`；不是本数据源格式返回 null。 */
    fun parseLineId(lineId: String?): List<String>? {
        if (lineId == null || !lineId.startsWith(P_LINE)) return null
        val seg = lineId.substring(P_LINE.length).split("|")
        if (seg.size != 3 || seg[0].isEmpty() || seg[1].isEmpty()) return null
        return seg
    }

    // ──────────────────────────── 请求表单 ────────────────────────────

    /** CMD=106 附近站点。坐标先转 GCJ-02（上游按 GCJ 算距离）。 */
    fun nearbyForm(cityName: String, wgsLat: Double, wgsLng: Double): String {
        val g = wgs2gcj(wgsLat, wgsLng)
        val la = format6(g[0])
        val ln = format6(g[1])
        return form(
            "CMD" to "106",
            "CITYNAME" to cityName,
            "LAT" to la,
            "LNG" to ln,
            "MYLAT" to la,
            "MYLNG" to ln
        )
    }

    /** CMD=105 某站的线路与最近一辆车。 */
    fun stopLinesForm(cityName: String, stationName: String): String = form(
        "CMD" to "105",
        "CITYNAME" to cityName,
        "STATIONNAME" to stationName,
        "MYLAT" to "",
        "MYLNG" to "",
        "CITYKEY" to ""
    )

    private fun form(vararg kv: Pair<String, String>): String {
        val sb = StringBuilder()
        for ((k, v) in kv) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(percentEncode(k)).append('=').append(percentEncode(v))
        }
        return sb.toString()
    }

    // ──────────────────────────── 解析 ────────────────────────────

    /**
     * CMD=106 的响应：`{data:[{name,lat,lon,dis}]}`。
     * 注意 `lat/lon` 是 GCJ-02，要转回 WGS-84 再存（否则后面算距离会重复偏移）。
     */
    fun parseStops(body: String, cityName: String, userLat: Double, userLng: Double, limit: Int): List<BusStation> {
        val start = body.indexOf('{')
        if (start < 0) return emptyList()
        val root = Js.parse(body.substring(start, ChelaileCrypto.jsonEnd(body, start))) as? Js.Obj
            ?: return emptyList()
        val arr = root.optArr("data") ?: return emptyList()
        val out = ArrayList<BusStation>()
        for (item in arr.items) {
            if (out.size >= limit) break
            val o = item as? Js.Obj ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val st = BusStation()
            st.name = name
            st.id = stopId(cityName, name)
            st.physicalId = st.id
            st.namesakeId = st.id
            val g = gcj2wgs(o.optDouble("lat", 0.0), o.optDouble("lon", 0.0))
            st.lat = g[0]
            st.lng = g[1]
            val dis = o.optInt("dis", -1)
            st.distanceMeters = when {
                dis > 0 -> dis
                st.lat != 0.0 && st.lng != 0.0 && userLat != 0.0 ->
                    Geo.haversineMeters(userLat, userLng, st.lat, st.lng)
                else -> -1
            }
            out.add(st)
        }
        return out
    }

    /**
     * CMD=105 的响应：`{data:[{lineName,upperOrDown,from,to,neartext,neartime,routeBean}]}`。
     *
     * 几个必守的口径：
     *   - `routeBean.routeType == "3"` 是地铁，纯地铁没有公交实时数据，跳过；
     *   - `upperOrDown` 1 → 上行（dir 0），其余 → 下行；
     *   - 只有 `neartext` 明说「已到站 / 正在进站」才算 arrived，
     *     「即将到站」只把分钟归零（官方口径，别混）。
     */
    fun parseStopLines(body: String, cityName: String, stationName: String): BusStation {
        val s = BusStation()
        s.name = stationName
        s.id = stopId(cityName, stationName)
        s.physicalId = s.id
        s.namesakeId = s.id

        val start = body.indexOf('{')
        if (start < 0) return s
        val root = Js.parse(body.substring(start, ChelaileCrypto.jsonEnd(body, start))) as? Js.Obj ?: return s
        val arr = root.optArr("data") ?: return s

        for (item in arr.items) {
            val o = item as? Js.Obj ?: continue
            var lineName = o.optString("lineName")
            if (lineName.isEmpty()) lineName = o.optString("routeName")
            lineName = lineName.trim()
            if (lineName.isEmpty()) continue

            val bean = o.optObj("routeBean")
            if (bean != null && "3" == bean.optString("routeType")) continue
            if (lineName.contains("地铁")) continue

            var up = o.optString("upperOrDown")
            if (up.isEmpty()) up = "1"
            val dir = if ("1" == up) 0 else 1

            val a = BusArrival()
            a.lineId = lineId(cityName, lineName, dir)
            a.lineName = lineName
            a.direction = dir
            a.startName = o.optString("from")
            a.endName = o.optString("to")
            a.orderInLine = orderOf(o)
            a.realtime = hasRealOf(bean)

            val nearText = cleanNearText(o.optString("neartext"))
            val nearTime = o.optString("neartime").trim()
            a.status = nearText
            a.stopsAway = stopsOf(nearText)
            a.minutes = minutesOfText(nearTime)
            if (nearText.contains("已到站") || nearText.contains("正在进站")) {
                a.arrived = true
                if (a.minutes < 0) a.minutes = 0
            } else if (nearText.contains("即将到站") && a.minutes < 0) {
                a.minutes = 0
            }
            a.pending = a.realtime && !a.arrived && a.minutes < 0 && a.orderInLine > 0
            s.addArrival(a)
        }
        return s
    }

    private fun orderOf(o: Js.Obj): Int {
        val order = o.optInt("stationOrder", 0)
        if (order > 0) return order
        val sid = o.optString("stationID").trim()
        if (sid.isEmpty()) return 0
        val v = sid.toIntOrNull() ?: return 0
        return if (v >= 0) v + 1 else 0
    }

    private fun hasRealOf(bean: Js.Obj?): Boolean {
        if (bean == null) return true
        if (!bean.has("hasReal")) return true
        return bean.optInt("hasReal", 1) != 0
    }

    /** 「7站 [开往 xxx]」→「7站」；「1站 [到达 xxx]」→「1站」。 */
    fun cleanNearText(raw: String?): String {
        if (raw == null) return ""
        var v = raw.trim()
        var cut = v.indexOf('[')
        val cut2 = v.indexOf('【')
        if (cut < 0 || (cut2 >= 0 && cut2 < cut)) cut = cut2
        if (cut >= 0) v = v.substring(0, cut)
        v = v.trim()
        if (v.endsWith("到达")) v = v.substring(0, v.length - 2).trim()
        return v
    }

    /** 「7站」→ 7；「即将到站」「已到站」→ 0；其余 -1。 */
    fun stopsOf(text: String?): Int {
        if (text.isNullOrEmpty()) return -1
        if (text.contains("即将到站") || text.contains("已到站")) return 0
        val i = text.indexOf('站')
        if (i <= 0) return -1
        return text.substring(0, i).trim().toIntOrNull() ?: -1
    }

    /**
     * 「7分钟」→ 7；「小于1分钟」→ 0；「1小时44分钟」→ 104。
     *
     * 小时段必须单独取再加：只抓「分」前面的数字会把 1 小时 44 分读成 44 分钟。
     */
    fun minutesOfText(text: String?): Int {
        if (text == null) return -1
        val v = text.trim()
        if (v.isEmpty()) return -1
        if (v.contains("小于1分钟") || v.contains("小于一分钟")) return 0

        var hours = -1
        var hUnit = v.indexOf("小时")
        if (hUnit < 0) hUnit = v.indexOf('时')
        if (hUnit > 0) {
            var hs = hUnit
            while (hs > 0 && (v[hs - 1].isDigit() || v[hs - 1] == '.')) hs--
            if (hs < hUnit) hours = v.substring(hs, hUnit).toDoubleOrNull()?.toInt() ?: -1
        }

        var mins = -1
        var unit = v.indexOf("分钟")
        if (unit < 0) unit = v.indexOf('分')
        if (unit >= 0) {
            var ms = unit
            while (ms > 0 && (v[ms - 1].isDigit() || v[ms - 1] == '.')) ms--
            if (ms < unit) mins = v.substring(ms, unit).toDoubleOrNull()?.toInt() ?: -1
        }

        return when {
            hours >= 0 && mins >= 0 -> hours * 60 + mins
            hours >= 0 -> hours * 60
            mins >= 0 -> mins
            else -> -1
        }
    }

    // ──────────────────────────── 坐标纠偏 ────────────────────────────

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun wgs2gcj(lat: Double, lng: Double): DoubleArray {
        if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        val magic = 1 - EE * sin(radLat) * sin(radLat)
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(lat + dLat, lng + dLng)
    }

    fun gcj2wgs(lat: Double, lng: Double): DoubleArray {
        if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        val g = wgs2gcj(lat, lng)
        return doubleArrayOf(lat - (g[0] - lat), lng - (g[1] - lng))
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun transformLng(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }

    private fun format6(v: Double): String {
        val scaled = (abs(v) * 1_000_000.0 + 0.5).toLong()
        val int = scaled / 1_000_000
        val frac = (scaled % 1_000_000).toString().padStart(6, '0')
        return (if (v < 0) "-" else "") + int + "." + frac
    }
}
