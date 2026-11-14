package com.tstc.zstangshi.bus

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.ArrayList
import java.util.Calendar
import java.util.Collections
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * 掌上公交（mygolbs）数据源。
 *
 * 协议来自 H5 数据面：`POST https://h5.mygolbs.com/ApiData.do`，
 * 表单体只有一个 `CMD` 加若干大写参数，必须带 `Origin` / `Referer`，
 * 响应是明文 JSON、`status` 为 1 才算成功（不是 1 时 `msg` 是原因）。
 *
 * 用到的命令：
 *
 *  - 101 城市列表
 *  - 103 线路站表（站序、坐标、首末班、票价、routeId）
 *  - 104 线路实时（在途车辆 + 每辆车到指定站的分钟数）
 *  - 105 某站点停靠的线路与最近车文案
 *  - 106 经纬度周边站点
 *  - 110 站点搜索 / 114 线路搜索
 *  - 207 首站发车时刻表
 *
 * 线路与站点没有稳定 id，这里用「城市名 + 名称」自造：
 * 线路 `mg|唐山市|1路|1`（末段 1 上行 / 2 下行），站点 `mg|唐山市|鑫雅园小区站`。
 * 城市一律用掌上公交的城市名（如「唐山市」），车来了那套数字编号会被换成默认城市。
 */
class MygolbsClient private constructor() {

    /** 103 的解析结果（带缓存）。 */
    class RouteInfo {
        @JvmField var city: String = ""
        @JvmField var lineName: String = ""
        @JvmField var direction: Int = 0
        @JvmField var routeId: String = ""
        @JvmField var firstTime: String = ""
        @JvmField var lastTime: String = ""
        @JvmField var price: String = ""
        @JvmField var atMillis: Long = 0
        @JvmField val stops: MutableList<BusModels.Stop> = ArrayList()
    }

    /** 104 里的一辆在途车。 */
    class Bus {
        @JvmField var number: String = ""

        /** 0 起：正开往第 index+1 站 */
        @JvmField var index: Int = -1
        @JvmField var statusType: String = ""
        @JvmField var stationName: String = ""
        @JvmField var lat: Double = 0.0
        @JvmField var lng: Double = 0.0
    }

    /** 104 里「某辆车到请求那一站」的预测。 */
    class Eta {
        @JvmField var busNumber: String = ""

        /** 还有几站；0 = 车已在站上或即将到站，看 [arrived] 区分 */
        @JvmField var count: Int = -1

        /** 还有几分钟 */
        @JvmField var minutes: Int = -1
        @JvmField var distance: Int = -1

        /** 车真的在站上（上游 busToStationTips 明说「已到站」，或 0 站 0 米） */
        @JvmField var arrived: Boolean = false
    }

    /** 104 的整体结果。 */
    class Realtime {
        @JvmField var hasReal: Boolean = true
        @JvmField var runState: Int = 0
        @JvmField var planTime: String = ""

        /** 官方给的线路级状态（如「非运营时间」），只在没有任何可用车辆时生效 */
        @JvmField var statusTips: String = ""
        @JvmField val buses: MutableList<Bus> = ArrayList()
        @JvmField val etas: MutableList<Eta> = ArrayList()

        /** 最快的一辆车。 */
        fun first(): Eta? {
            return if (etas.isEmpty()) null else etas[0]
        }
    }

    companion object {

        private const val API = "https://h5.mygolbs.com/ApiData.do"
        private const val ORIGIN = "https://h5.mygolbs.com"
        private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36" +
            " (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 没有城市时的默认值：唐山。 */
        const val DEFAULT_CITY: String = "唐山市"

        private const val P_LINE = "mg|"
        private const val CONNECT_TIMEOUT = 8000
        private const val READ_TIMEOUT = 8000

        /** 站表这类静态数据缓存 6 小时：轮询时只打 104，不再重复拉站表。 */
        private const val ROUTE_TTL_MS: Long = 6 * 60 * 60 * 1000L

        private val ROUTE_CACHE: MutableMap<String, MygolbsClient.RouteInfo> = ConcurrentHashMap<String, MygolbsClient.RouteInfo>()

        /** 换数据源时整批作废站表缓存（BusClient.setSource 调）。 */
        @JvmStatic
        fun clearCache() {
            ROUTE_CACHE.clear()
        }

        // ──────────────────────────── 对外接口 ────────────────────────────

        /** 支持实时公交的城市。id 就是掌上公交的城市名。 */
        @JvmStatic
        @Throws(Exception::class)
        fun cities(): List<BusModels.City> {
            val j = call(single("CMD", "101"))
            val arr = firstArray(j, "data", "list")
            val out = ArrayList<BusModels.City>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                var name = c.optString("cityname")
                if (name.isEmpty()) name = c.optString("cityName")
                if (name.isEmpty()) name = c.optString("CITYNAME")
                name = name.trim()
                if (name.isEmpty()) continue
                out.add(BusModels.City(name, shortCityName(name)))
            }
            if (out.isEmpty()) out.add(BusModels.City(DEFAULT_CITY, "唐山"))
            return out
        }

        /** 附近站点：106 拿站点和坐标，再逐站 105 补线路上车。 */
        @JvmStatic
        @Throws(Exception::class)
        fun nearby(cityId: String, lat: Double, lng: Double, limit: Int): List<BusModels.Station> {
            val stops = probeStops(cityId, lat, lng, limit)
            fillLines(cityId, stops, lat, lng)
            return stops
        }

        /**
         * 只探站点（106），不查线路。下滑加载时一次要探 8 个方位，
         * 若每个方位都连带查 105 就是上百次请求，必然被上游掐掉。
         */
        @JvmStatic
        @Throws(Exception::class)
        fun probeStops(cityId: String, lat: Double, lng: Double, limit: Int): List<BusModels.Station> {
            val city = MygolbsClient.city(cityId)
            // 上游按 GCJ-02 算距离，先把用户坐标转过去，否则整片站点会偏 500 米
            val g = wgs2gcj(lat, lng)
            val p = LinkedHashMap<String, String>()
            p["CMD"] = "106"
            p["CITYNAME"] = city
            p["LAT"] = fmt(g[0])
            p["LNG"] = fmt(g[1])
            p["MYLAT"] = fmt(g[0])
            p["MYLNG"] = fmt(g[1])
            val j = call(p)

            val arr = j.optJSONArray("data")
            val out = ArrayList<BusModels.Station>()
            if (arr == null) return out
            var i = 0
            while (i < arr.length() && out.size < limit) {
                val o = arr.optJSONObject(i)
                i++
                if (o == null) continue
                val name = o.optString("name").trim()
                if (name.isEmpty()) continue
                val s = newStation(city, name)
                val w = gcj2wgs(o.optDouble("lat", 0.0), o.optDouble("lon", 0.0))
                s.lat = w[0]
                s.lng = w[1]
                val dis = o.optInt("dis", -1)
                // dis 是上游按 GCJ 算的两点距离，同一坐标系内相对距离不变，可以直接用
                if (dis > 0) {
                    s.distanceMeters = dis
                } else if (s.lat != 0.0 && s.lng != 0.0) {
                    s.distanceMeters = Math.round(BusClient.distanceMeters(lat, lng, s.lat, s.lng)).toInt()
                }
                out.add(s)
            }
            return out
        }

        /** 给一批站点补上各自停靠的线路（4 并发，失败不影响其他站）。 */
        @JvmStatic
        fun fillLines(cityId: String, list: List<BusModels.Station>?, lat: Double, lng: Double) {
            if (list == null || list.isEmpty()) return
            val city = MygolbsClient.city(cityId)
            val todo = ArrayList(list)
            val workers = Math.min(4, todo.size)
            val ts = ArrayList<Thread>(workers)
            for (i in 0 until workers) {
                val idx = i
                val t = Thread({
                    var k = idx
                    while (k < todo.size) {
                        val s = todo[k]
                        try {
                            val full = stopLines(city, s.name)
                            s.arrivals.clear()
                            s.arrivals.addAll(full.arrivals)
                        } catch (ignored: Exception) {
                        }
                        try {
                            Thread.sleep(50)
                        } catch (ignored: InterruptedException) {
                        }
                        k += workers
                    }
                }, "mg-lines-$i")
                ts.add(t)
                t.start()
            }
            for (th in ts) {
                try {
                    th.join()
                } catch (ignored: InterruptedException) {
                }
            }
        }

        /** 站点详情：某个站停靠的全部线路。siteId 形如 `mg|唐山市|站名`。 */
        @JvmStatic
        @Throws(Exception::class)
        fun stopDetail(cityId: String, stationId: String?): BusModels.Station {
            val city = MygolbsClient.city(cityId)
            val name = stationNameOf(stationId)
            if (name.isEmpty()) throw IOException("站点缺少站名")
            return stopLines(city, name)
        }

        /** CMD=105：某站点的线路与最近一辆车的文案。 */
        @JvmStatic
        @Throws(Exception::class)
        fun stopLines(city: String, stationName: String): BusModels.Station {
            val p = LinkedHashMap<String, String>()
            p["CMD"] = "105"
            p["CITYNAME"] = city
            p["STATIONNAME"] = stationName
            p["MYLAT"] = ""
            p["MYLNG"] = ""
            p["CITYKEY"] = ""
            val j = call(p)

            val s = newStation(city, stationName)
            val arr = j.optJSONArray("data") ?: return s
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                var lineName = o.optString("lineName")
                if (lineName.isEmpty()) lineName = o.optString("routeName")
                lineName = lineName.trim()
                if (lineName.isEmpty()) continue
                // 纯地铁线路没有公交实时数据
                val bean = o.optJSONObject("routeBean")
                if (bean != null && "3" == bean.optString("routeType")) continue
                if (lineName.contains("地铁")) continue

                var up = o.optString("upperOrDown")
                if (up.isEmpty()) up = "1"
                val dir = if ("1" == up) 0 else 1

                val a = BusModels.Arrival()
                a.lineId = lineId(city, lineName, dir)
                a.lineName = lineName
                a.direction = dir
                a.startName = o.optString("from")
                a.endName = o.optString("to")
                a.stopId = s.id
                a.orderInLine = orderOf(o)
                a.realtime = hasRealOf(bean)

                val nearText = cleanNearText(o.optString("neartext"))
                val nearTime = o.optString("neartime").trim()
                a.status = nearText
                a.stopsAway = stopsOf(nearText)
                a.minutes = minutesOfText(nearTime)
                if (nearText.contains("已到站") || nearText.contains("正在进站")) {
                    // 只有文案明说「已到站 / 正在进站」才算真到站
                    a.arrived = true
                    if (a.minutes < 0) a.minutes = 0
                } else if (nearText.contains("即将到站") && a.minutes < 0) {
                    // 官方口径：车还差一点距离时叫「即将到站」配「小于1分钟」，
                    // 不叫「已到站」；这里只把分钟归零，arrived 不动
                    a.minutes = 0
                }
                // 只有确定要算的行才挂「…」，否则界面会一直转
                a.pending = a.realtime && !a.arrived && a.minutes < 0 && a.orderInLine > 0
                s.arrivals.add(a)
            }
            return s
        }

        /** CMD=103：站表 + 首末班 + 票价 + routeId（6 小时缓存）。 */
        @JvmStatic
        @Throws(Exception::class)
        fun route(cityId: String, lineName: String, dir: Int): MygolbsClient.RouteInfo {
            val city = MygolbsClient.city(cityId)
            val dirStr = if (dir == 0) "1" else "2"
            val key = "$city|$lineName|$dirStr"
            val cached = ROUTE_CACHE[key]
            val now = System.currentTimeMillis()
            if (cached != null && now - cached.atMillis < ROUTE_TTL_MS) return cached

            val p = LinkedHashMap<String, String>()
            p["CMD"] = "103"
            p["CITYNAME"] = city
            p["CITYCODE"] = ""
            p["LINENAME"] = lineName
            p["DIRECTION"] = dirStr
            p["CITYKEY"] = ""
            val j = call(p)

            val info = MygolbsClient.RouteInfo()
            info.city = city
            info.lineName = lineName
            info.direction = dir
            info.routeId = j.optString("routeId", "")
            info.atMillis = now

            val fl = j.optJSONArray("firstLast")
            val first = if (fl == null) null else fl.optJSONObject(0)
            if (first != null) {
                info.firstTime = first.optString("first", "")
                info.lastTime = first.optString("last", "")
            }
            if (info.firstTime.isEmpty()) info.firstTime = j.optString("beginTime", "")
            if (info.lastTime.isEmpty()) info.lastTime = j.optString("endTime", "")

            val commonts = j.optString("commonts", "")
            val idx = commonts.indexOf("票价")
            if (idx >= 0) {
                val tail = commonts.substring(idx + 2)
                val colon = indexOfAny(tail, ":：")
                if (colon >= 0) {
                    val v = tail.substring(colon + 1).trim()
                    val stop = indexOfAny(v, "，, ")
                    info.price = (if (stop > 0) v.substring(0, stop) else v).trim()
                }
            }

            val data = j.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val st = data.optJSONObject(i) ?: continue
                    val stop = BusModels.Stop()
                    var order = st.optInt("stationOrder", i + 1)
                    if (order <= 0) order = i + 1
                    stop.order = order
                    stop.orderLabel = order.toString()
                    stop.name = st.optString("stationName", "").trim()
                    val w = gcj2wgs(st.optDouble("station_lat", 0.0), st.optDouble("station_lon", 0.0))
                    stop.lat = w[0]
                    stop.lng = w[1]
                    stop.id = stopId(city, stop.name)
                    stop.physicalId = stop.id
                    if (stop.name.isEmpty()) continue
                    info.stops.add(stop)
                }
            }
            // 空站表不缓存，下次重新拉
            if (!info.stops.isEmpty()) {
                if (ROUTE_CACHE.size > 200) ROUTE_CACHE.clear()
                ROUTE_CACHE[key] = info
            }
            return info
        }

        /** CMD=104：这条线当前的车辆，以及每辆车到 [order] 站的分钟数。 */
        @JvmStatic
        @Throws(Exception::class)
        fun realtime(cityId: String, lineName: String, dir: Int, order: Int): MygolbsClient.Realtime {
            val city = MygolbsClient.city(cityId)
            val p = LinkedHashMap<String, String>()
            p["CMD"] = "104"
            p["CITYNAME"] = city
            p["CITYCODE"] = ""
            p["LINENAME"] = lineName
            p["DIRECTION"] = if (dir == 0) "1" else "2"
            p["STATIONORDER"] = order.toString()
            p["CITYKEY"] = ""
            val j = call(p)

            val rt = MygolbsClient.Realtime()
            rt.hasReal = j.optInt("hasReal", 1) != 0
            rt.runState = j.optInt("runState", 0)
            rt.planTime = j.optString("planTime", "").trim()

            val buses = j.optJSONArray("list")
            if (buses != null) {
                for (i in 0 until buses.length()) {
                    val b = buses.optJSONObject(i) ?: continue
                    val bus = MygolbsClient.Bus()
                    bus.number = b.optString("busNumber", "")
                    bus.index = b.optInt("index", -1)
                    bus.statusType = b.optString("statusType", "")
                    bus.stationName = b.optString("stationName", "")
                    val bw = gcj2wgs(b.optDouble("bus_lat", 0.0), b.optDouble("bus_lng", 0.0))
                    bus.lat = bw[0]
                    bus.lng = bw[1]
                    rt.buses.add(bus)
                }
            }

            val eta = j.optJSONArray("routeOnStationRTimeInfoList")
            if (eta != null) {
                for (i in 0 until eta.length()) {
                    val e = eta.optJSONObject(i) ?: continue
                    val tips = e.optString("busToStationTips", "")
                    val count = e.optInt("busToStationCount", -1)
                    // 收班/非运营时官方在这里明说「非运营时间」「已过末班时刻」（都配 count=-1），
                    // 是线路级状态，不是某辆车的到站 —— 记下来给首页和线路详情统一用，
                    // 别当过站车丢掉。关键字按官方实际出现过的文案收，宁缺毋滥
                    if (count < 0 && (tips.contains("非运营") || tips.contains("收班") ||
                            tips.contains("末班") || tips.contains("停运"))
                    ) {
                        if (rt.statusTips.isEmpty()) rt.statusTips = tips
                        continue
                    }
                    // count < 0 是「这辆车已经过了这一站」，上游仍把它列出来、时间写 0，
                    // 直接用会把「刚开走」当成「已到站」
                    if (count < 0) continue
                    val item = MygolbsClient.Eta()
                    item.busNumber = e.optString("busNumber", "")
                    item.count = count
                    item.minutes = minutesOfText(e.optString("busToStationTimeTips", ""))
                    if (item.minutes < 0) {
                        val t = e.optDouble("busToStationTime", -1.0)
                        if (t >= 0) item.minutes = Math.round(t).toInt()
                    }
                    if (item.minutes < 0) continue
                    item.distance = e.optInt("busToStationDistance", -1)
                    // 官方口径：车真在站上时 busToStationTips 给「已到站」、距离 0；
                    // 还差一小段时给「即将到站」配「小于1分钟」。两者必须分开
                    item.arrived = tips.contains("已到站") ||
                        (count == 0 && item.distance == 0)
                    rt.etas.add(item)
                }
            }
            Collections.sort(rt.etas, Comparator<MygolbsClient.Eta> { a, b ->
                a.minutes.compareTo(b.minutes)
            })
            // 有真车就不需要线路级状态了（比如列表里既有真车又混了一条「非运营时间」），
            // 状态只在线路确实没车可用时才生效
            if (rt.etas.isNotEmpty()) rt.statusTips = ""
            return rt
        }

        /** CMD=114 线路搜索。同一线路的两个方向合成一条，返回的 lineId 指向正方向。 */
        @JvmStatic
        @Throws(Exception::class)
        fun searchLines(cityId: String, keyword: String): List<BusModels.Line> {
            val city = MygolbsClient.city(cityId)
            val p = LinkedHashMap<String, String>()
            p["CMD"] = "114"
            p["CITYNAME"] = city
            p["KEYWORD"] = keyword
            p["CITYKEY"] = ""
            val j = call(p)
            val arr = firstArray(j, "buslines", "data", "list")

            val uniq = LinkedHashMap<String, BusModels.Line>()
            val beanByName = LinkedHashMap<String, JSONObject>()
            if (arr == null) return ArrayList()
            val both = ArrayList<BusModels.Line>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                var name = o.optString("lineName", "").trim()
                if (name.isEmpty()) name = o.optString("LINENAME", "").trim()
                if (name.isEmpty()) continue
                val bean = o.optJSONObject("routeBean")
                if (bean != null && "3" == bean.optString("routeType")) continue
                if (name.contains("地铁")) continue
                if (bean != null) beanByName[name] = bean

                var up = o.optString("upperOrDown", "")
                if (up.isEmpty()) up = "1"
                val line = BusModels.Line()
                line.name = name
                line.lineNo = name
                line.direction = if ("1" == up) 0 else 1
                line.lineId = lineId(city, name, line.direction)
                line.startName = firstNonEmpty(o.optString("from"), o.optString("startStation"))
                line.endName = firstNonEmpty(o.optString("to"), o.optString("endStation"))
                line.price = o.optString("comments", "")
                both.add(line)
            }
            for (l in both) {
                val old = uniq[l.name]
                if (old == null) {
                    uniq[l.name] = l
                } else if (old.direction != 0 && l.direction == 0) {
                    uniq[l.name] = l
                }
            }
            // 首末班要从 routeBean 的方向数组里取，搜索结果本身常常没有
            for (l in uniq.values) {
                val bean = beanByName[l.name] ?: continue
                val key = if (l.direction == 0) "seutimes" else "sedtimes"
                val wins = bean.optJSONArray(key)
                val w = if (wins == null) null else wins.optJSONObject(0)
                if (w != null) {
                    if (l.firstTime.isEmpty()) l.firstTime = w.optString("first", "")
                    if (l.lastTime.isEmpty()) l.lastTime = w.optString("last", "")
                }
            }
            return ArrayList(uniq.values)
        }

        /** CMD=110 站点搜索。 */
        @JvmStatic
        @Throws(Exception::class)
        fun searchStops(cityId: String, keyword: String): List<BusModels.Station> {
            val city = MygolbsClient.city(cityId)
            val p = LinkedHashMap<String, String>()
            p["CMD"] = "110"
            p["CITYNAME"] = city
            p["KEYWORD"] = keyword
            p["CITYKEY"] = ""
            val j = call(p)
            val arr = j.optJSONArray("busstations")
            val out = ArrayList<BusModels.Station>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("stationName", "").trim()
                if (name.isEmpty() || name.contains("地铁")) continue
                var dup = false
                for (e in out) {
                    if (e.name == name) {
                        dup = true
                        break
                    }
                }
                if (dup) continue
                val s = newStation(city, name)
                val w = gcj2wgs(o.optDouble("station_lat", 0.0), o.optDouble("station_lon", 0.0))
                s.lat = w[0]
                s.lng = w[1]
                out.add(s)
            }
            return out
        }

        /** CMD=207：首站发车时刻表。要先用 103 拿到 routeId。 */
        @JvmStatic
        @Throws(Exception::class)
        fun timetable(cityId: String, lineName: String, dir: Int): BusModels.Timetable {
            val city = MygolbsClient.city(cityId)
            val info = route(city, lineName, dir)
            val tt = BusModels.Timetable()
            tt.lineName = lineName
            tt.startName = if (info.stops.isEmpty()) "" else info.stops[0].name
            tt.endName = if (info.stops.isEmpty()) "" else info.stops[info.stops.size - 1].name
            tt.firstDep = info.firstTime
            tt.lastDep = info.lastTime
            if (info.routeId.isEmpty()) {
                tt.tip = "该线路暂不提供首站发车时刻表"
                return tt
            }

            val p = LinkedHashMap<String, String>()
            p["CMD"] = "207"
            p["CITYNAME"] = city
            p["DIRECTION"] = if (dir == 0) "1" else "2"
            p["ROUTEID"] = info.routeId
            p["CITYKEY"] = ""
            val j = call(p)

            val planned = "1" == j.optString("special", "0")
            tt.depTitle = if (planned) "计划发车时刻" else "历史推算班次"
            tt.tip = if (planned) "本时刻表为计划发车时间，可能临时调整，仅供参考"
            else "本时刻表数据根据历史数据计算得出，仅供参考"

            val groups = j.optJSONArray("list")
            if (groups != null) {
                for (i in 0 until groups.length()) {
                    val g = groups.optJSONObject(i) ?: continue
                    val seg = BusModels.TtSegment()
                    val span = splitGroup(g.optString("group", ""))
                    seg.from = span[0]
                    seg.to = span[1]
                    val peak = g.optInt("peak", 0)
                    if (peak == 1) seg.tips = "早高峰"
                    else if (peak == 2) seg.tips = "晚高峰"
                    val times = g.optJSONArray("times")
                    if (times != null) {
                        for (k in 0 until times.length()) {
                            val t = times.optJSONObject(k) ?: continue
                            // isDelete=1 是临时取消的班次，界面没有删除线样式，直接不列
                            if (t.optInt("isDelete", 0) == 1) continue
                            val clock = normalizeClock(t.optString("time", ""))
                            if (clock.isNotEmpty()) seg.times.add(clock)
                        }
                    }
                    BusModels.sortByClock(seg.times)
                    if (seg.times.isNotEmpty() || seg.from.isNotEmpty()) tt.today.add(seg)
                }
            }
            val all = tt.todayTimes()
            if (all.isNotEmpty()) {
                tt.firstDep = all[0]
                tt.lastDep = all[all.size - 1]
            }
            var next = normalizeClock(j.optString("nextTime", ""))
            if (next.isEmpty()) next = firstAfter(all, nowHHMM())
            tt.nextDep = next
            tt.nextFrom = if (next.isEmpty()) "" else (if (planned) "计划班次" else "历史推算")
            if (all.size > 1) {
                val span = BusModels.minutesOf(all[all.size - 1]) - BusModels.minutesOf(all[0])
                if (span > 0) tt.interval = Math.max(1, Math.round(span / (all.size - 1).toFloat()))
            }
            if (tt.empty()) tt.tip = "该线路暂不提供首站发车时刻表"
            return tt
        }

        // ──────────────────────────── id 与文案 ────────────────────────────

        /** 城市名规范化：老版本存的车来了编号（056）一律换回默认城市。 */
        @JvmStatic
        fun city(cityId: String?): String {
            if (cityId == null) return DEFAULT_CITY
            val v = cityId.trim()
            if (v.isEmpty()) return DEFAULT_CITY
            if (v.endsWith("市") || v.endsWith("县") || v.endsWith("区")) return v
            var digits = true
            for (i in v.indices) {
                if (!Character.isDigit(v[i])) {
                    digits = false
                    break
                }
            }
            if (digits) return DEFAULT_CITY
            return v + "市"
        }

        /** 列表里显示的城市名去掉「市」，和车来了那套观感一致。 */
        @JvmStatic
        fun shortCityName(name: String?): String {
            if (name == null) return ""
            val v = name.trim()
            if (v.endsWith("市") && v.length > 2) return v.substring(0, v.length - 1)
            return v
        }

        @JvmStatic
        fun lineId(city: String, lineName: String, dir: Int): String {
            return P_LINE + city + "|" + lineName + "|" + (if (dir == 0) "1" else "2")
        }

        /** 解析线路 id，返回 {城市, 线路名, "1"/"2"}；不是本数据源的格式返回 null。 */
        @JvmStatic
        fun parseLineId(lineId: String?): Array<String>? {
            if (lineId == null || !lineId.startsWith(P_LINE)) return null
            val seg = lineId.substring(P_LINE.length).split("|")
            if (seg.size != 3) return null
            if (seg[0].isEmpty() || seg[1].isEmpty()) return null
            return seg.toTypedArray()
        }

        @JvmStatic
        fun stopId(city: String, stationName: String): String {
            return P_LINE + city + "|" + stationName
        }

        /** 从站点 id 里取站名；不是本数据源格式就当整串是站名。 */
        @JvmStatic
        fun stationNameOf(stationId: String?): String {
            if (stationId == null) return ""
            if (!stationId.startsWith(P_LINE)) return stationId.trim()
            val seg = stationId.substring(P_LINE.length).split("|")
            if (seg.size != 2) return ""
            return seg[1].trim()
        }

        @JvmStatic
        fun sameSource(lineId: String?): Boolean {
            return parseLineId(lineId) != null
        }

        // ──────────────────────────── 坐标系 ────────────────────────────

        /**
         * 掌上公交的经纬度是 GCJ-02（火星坐标），手机定位给的是 WGS-84。
         *
         * 直接把 WGS 传过去，站点会整体偏 400~500 米 —— 表现出来就是
         * 「同样的位置，首页显示的最远/最近站点全变了」。所以：请求前把用户坐标转成
         * GCJ-02，解析回来再把站点和车辆的坐标转回 WGS-84，站内距离、最近站判断才准。
         */
        private const val A: Double = 6378245.0
        private const val EE: Double = 0.00669342162296594323

        /** WGS-84 → GCJ-02。 */
        @JvmStatic
        fun wgs2gcj(lat: Double, lng: Double): DoubleArray {
            if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
            var dLat = transformLat(lng - 105.0, lat - 35.0)
            var dLng = transformLng(lng - 105.0, lat - 35.0)
            val radLat = lat / 180.0 * Math.PI
            val magic = 1 - EE * Math.sin(radLat) * Math.sin(radLat)
            val sqrtMagic = Math.sqrt(magic)
            dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
            dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI)
            return doubleArrayOf(lat + dLat, lng + dLng)
        }

        /** GCJ-02 → WGS-84（一次反解，误差不到 1 米，画站线够用）。 */
        @JvmStatic
        fun gcj2wgs(lat: Double, lng: Double): DoubleArray {
            if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
            val g = wgs2gcj(lat, lng)
            return doubleArrayOf(lat - (g[0] - lat), lng - (g[1] - lng))
        }

        private fun outOfChina(lat: Double, lng: Double): Boolean {
            return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
        }

        private fun transformLat(x: Double, y: Double): Double {
            var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
                0.2 * Math.sqrt(Math.abs(x))
            r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            r += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
            r += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
            return r
        }

        private fun transformLng(x: Double, y: Double): Double {
            var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
                0.1 * Math.sqrt(Math.abs(x))
            r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            r += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
            r += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
            return r
        }

        // ──────────────────────────── 解析辅助 ────────────────────────────

        private fun newStation(city: String, name: String): BusModels.Station {
            val s = BusModels.Station()
            s.name = name
            s.id = stopId(city, name)
            s.physicalId = s.id
            s.namesakeId = s.id
            return s
        }

        /** 105 的 stationOrder 恒为 0，真正能用的站序在 stationID（0 起，站序 = +1）。 */
        private fun orderOf(o: JSONObject): Int {
            val order = o.optInt("stationOrder", 0)
            if (order > 0) return order
            val sid = o.optString("stationID", "").trim()
            if (sid.isEmpty()) return 0
            return try {
                val v = Integer.parseInt(sid)
                if (v >= 0) v + 1 else 0
            } catch (e: NumberFormatException) {
                0
            }
        }

        private fun hasRealOf(bean: JSONObject?): Boolean {
            if (bean == null) return true
            if (!bean.has("hasReal")) return true
            return bean.optInt("hasReal", 1) != 0
        }

        /** 「7站 [开往 xxx]」→「7站」；「1站 [到达 xxx]」→「1站」。 */
        @JvmStatic
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

        private fun containsArriveText(text: String): Boolean {
            return text.contains("即将到站") || text.contains("已到站") || text.contains("正在进站")
        }

        /** 「7站」→ 7；「即将到站」→ 0；其余 -1。 */
        @JvmStatic
        fun stopsOf(text: String?): Int {
            if (text == null || text.isEmpty()) return -1
            if (text.contains("即将到站") || text.contains("已到站")) return 0
            val i = text.indexOf('站')
            if (i <= 0) return -1
            val head = text.substring(0, i).trim()
            return try {
                Integer.parseInt(head)
            } catch (e: NumberFormatException) {
                -1
            }
        }

        /**
         * 「7分钟」→ 7；「小于1分钟」→ 0；「14:05 到达」→ -1。
         *
         * 远途线路的文案是「1小时44分钟」，只抓「分」前面的数字会读成 44 分钟，
         * 白白丢掉整段小时（官方小程序显示 1小时44分，我们显示 44 分钟就是这么来的）。
         * 所以小时段和分钟段分别取，再相加。
         */
        @JvmStatic
        fun minutesOfText(text: String?): Int {
            if (text == null) return -1
            val v = text.trim()
            if (v.isEmpty()) return -1
            if (v.contains("小于1分钟") || v.contains("小于一分钟")) return 0

            var hours = -1
            var hUnit = v.indexOf("小时")
            if (hUnit < 0) hUnit = v.indexOf("时")
            if (hUnit > 0) {
                var hs = hUnit
                while (hs > 0 && (Character.isDigit(v[hs - 1]) || v[hs - 1] == '.')) {
                    hs--
                }
                if (hs < hUnit) hours = parseIntQuiet(v.substring(hs, hUnit))
            }

            var mins = -1
            var unit = v.indexOf("分钟")
            if (unit < 0) unit = v.indexOf("分")
            if (unit >= 0) {
                var start = unit
                while (start > 0 && (Character.isDigit(v[start - 1]) || v[start - 1] == '.')) {
                    start--
                }
                if (start < unit) mins = parseIntQuiet(v.substring(start, unit))
            }

            if (hours < 0 && mins < 0) return -1
            return Math.max(hours, 0) * 60 + Math.max(mins, 0)
        }

        private fun parseIntQuiet(raw: String): Int {
            return try {
                Math.round(java.lang.Double.parseDouble(raw)).toInt()
            } catch (e: NumberFormatException) {
                -1
            }
        }

        /** 「07:43」→ 原样；「7:5」→「07:05」；解析不出返回空串。 */
        @JvmStatic
        fun normalizeClock(raw: String?): String {
            if (raw == null) return ""
            val v = raw.trim()
            if (v.isEmpty()) return ""
            val i = v.indexOf(':')
            if (i <= 0) return ""
            return try {
                val h = Integer.parseInt(v.substring(0, i).trim())
                val m = Integer.parseInt(v.substring(i + 1).trim())
                if (h < 0 || h > 23 || m < 0 || m > 59) "" else pad(h) + ":" + pad(m)
            } catch (e: NumberFormatException) {
                ""
            }
        }

        private fun pad(v: Int): String {
            return if (v < 10) "0$v" else v.toString()
        }

        private fun splitGroup(group: String?): Array<String> {
            val v = if (group == null) "" else group.trim()
            if (v.isEmpty()) return arrayOf("", "")
            var i = v.indexOf('~')
            if (i < 0) i = v.indexOf('～')
            if (i < 0) return arrayOf(normalizeClock(v), "")
            return arrayOf(normalizeClock(v.substring(0, i)), normalizeClock(v.substring(i + 1)))
        }

        private fun firstAfter(sorted: List<String>, now: String): String {
            val n = BusModels.minutesOf(now)
            if (n < 0) return ""
            for (t in sorted) {
                if (BusModels.minutesOf(t) >= n) return t
            }
            return ""
        }

        /** 现在几点（HH:mm），北京时区。 */
        @JvmStatic
        fun nowHHMM(): String {
            val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
            return pad(c.get(Calendar.HOUR_OF_DAY)) + ":" + pad(c.get(Calendar.MINUTE))
        }

        private fun firstNonEmpty(a: String?, b: String?): String {
            if (a != null && a.trim().isNotEmpty()) return a.trim()
            return if (b == null) "" else b.trim()
        }

        private fun indexOfAny(s: String, chars: String): Int {
            var best = -1
            for (i in s.indices) {
                if (chars.indexOf(s[i]) >= 0) {
                    best = i
                    break
                }
            }
            return best
        }

        private fun firstArray(j: JSONObject, vararg keys: String): JSONArray? {
            for (k in keys) {
                val arr = j.optJSONArray(k)
                if (arr != null) return arr
            }
            return null
        }

        private fun single(k: String, v: String): MutableMap<String, String> {
            val p = LinkedHashMap<String, String>()
            p[k] = v
            return p
        }

        @JvmStatic
        fun fmt(v: Double): String {
            return String.format(Locale.US, "%.6f", v)
        }

        // ──────────────────────────── HTTP ────────────────────────────

        @JvmStatic
        @Throws(Exception::class)
        fun call(params: Map<String, String>): JSONObject {
            val sb = StringBuilder()
            for ((k, v) in params) {
                if (sb.length > 0) sb.append('&')
                sb.append(URLEncoder.encode(k, "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(if (v == null) "" else v, "UTF-8"))
            }
            val body = sb.toString().toByteArray(charset("UTF-8"))

            val c = URL(API).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = CONNECT_TIMEOUT
                c.readTimeout = READ_TIMEOUT
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.setRequestProperty("Origin", ORIGIN)
                c.setRequestProperty("Referer", "$ORIGIN/")
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept", "*/*")

                val os = c.outputStream
                os.write(body)
                os.flush()
                os.close()

                val code = c.responseCode
                val ins = if (code >= 400) c.errorStream else c.inputStream
                if (ins == null) throw IOException("掌上公交无响应 HTTP $code")
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var n = ins.read(chunk)
                while (n > 0) {
                    buf.write(chunk, 0, n)
                    n = ins.read(chunk)
                }
                ins.close()

                val text = buf.toString("UTF-8")
                val start = text.indexOf('{')
                if (start < 0) throw IOException("掌上公交响应异常")
                val json = JSONObject(text.substring(start, jsonEnd(text, start)))
                val status = json.optString("status", "")
                if ("1" != status) {
                    throw IOException(
                        "掌上公交返回 status=" + status +
                            " msg=" + json.optString("msg", "")
                    )
                }
                return json
            } finally {
                c.disconnect()
            }
        }

        /** 从 start 那个 '{' 找到配对的 '}'（字符串里的括号不算）。 */
        private fun jsonEnd(s: String, start: Int): Int {
            var depth = 0
            var inStr = false
            var esc = false
            for (i in start until s.length) {
                val ch = s[i]
                if (inStr) {
                    if (esc) esc = false
                    else if (ch == '\\') esc = true
                    else if (ch == '"') inStr = false
                    continue
                }
                if (ch == '"') inStr = true
                else if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            return s.length
        }
    }
}
