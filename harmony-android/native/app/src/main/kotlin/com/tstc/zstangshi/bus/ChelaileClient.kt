package com.tstc.zstangshi.bus

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.Date
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 实时公交数据客户端：直连车来了（Chelaile）微信小程序协议。
 *
 * 协议细节移植自 `Trackr`（github.com/5151561/Trackr，MIT）的
 * `core/network/.../ChelaileApiClient.kt` 与 `ChelaileCrypto.kt`：
 * 请求参数按插入顺序拼 `"k"="v"&...` 加盐求 MD5 作为 `cryptoSign`，
 * 响应外层是 `**YGKJ{"jsonr":{"data":{"encryptResult":"..."}}}}`，
 * 用固定密钥做 AES/ECB/PKCS5 解密后才是真正的业务 JSON。
 *
 * 本项目不引任何第三方库：HTTP 用 [HttpURLConnection]、
 * 摘要用 [MessageDigest]、解密用 [Cipher]、JSON 用系统 `org.json`。
 * 全部方法都是阻塞的，调用方负责放到后台线程。
 */
class ChelaileClient private constructor() {

    /** 一次车辆预测的结果（最快的车还有多久到、还剩几站、离多少米）。 */
    class Pred {
        @JvmField var secs: Int = -1
        @JvmField var stops: Int = -1
        @JvmField var dist: Int = -1

        /** 官方明说车在候车站上（state=1），跟「小于1分钟」是两回事 */
        @JvmField var arrived: Boolean = false

        /**
         * 这几项是**哪一辆车**算出来的（buses[].busId）。大字说「到站了 / N 分钟」时，
         * 小字里的车牌、距本站距离、到达时刻必须读同一辆，不能再去按站序另挑一辆。
         */
        @JvmField var busId: String = ""
        @JvmField var depDesc: String = ""
        @JvmField var preArrival: String = ""

        /**
         * 紧随其后的那辆车，也取自**这一份** busDetail 响应（秒数次小的一辆）。
         * 首页那行「下辆 37分钟 22站」只读这里，不拿 nearSts 里另一个时刻、
         * 另一套口径的第二条 stnStates 来拼 —— 否则点进详情看到的「下一辆」对不上。
         * -1 = 这一轮上游只给了一辆车。
         */
        @JvmField var nextSecs: Int = -1
        @JvmField var nextStops: Int = -1

        /** 第二辆车的 busId：线路详情那句「下一辆」也认这一辆，和首页「下辆」对得上。 */
        @JvmField var nextBusId: String = ""
    }

    /**
     * 补预测的进度回调，在后台线程上回调，调用方自己 post 到 UI。
     * 刻意只留一个方法：单方法接口能用 lambda，不受 d8 对内部类的那点脾气影响。
     */
    fun interface EtaWatcher {
        fun onEta(a: BusModels.Arrival)
    }

    /** 一辆车到候车站的明细（encryptedBusDetail 里 buses[].travels[0]）。 */
    class BusEta {
        @JvmField var busId: String = ""
        @JvmField var seconds: Int = -1

        /** 到候车站的直线距离（米）；-1 = 上游没给 */
        @JvmField var distance: Int = -1

        /** 上游的推荐文案（如「已到站」「即将到站」），空 = 没给 */
        @JvmField var tip: String = ""

        /** 车正开往第几站（1 基） */
        @JvmField var order: Int = 0
    }

    /**
     * 一次 encryptedBusDetail 的结果：既给「最快那辆」的 [Pred]（首页/详情大字用），
     * 也把每辆车按 busId 摊开（线路详情逐车卡片用）。一次请求两处用，不重复打接口。
     */
    class BusDetail {
        @JvmField var pred: Pred = Pred()
        @JvmField val byBus: MutableMap<String, BusEta> = HashMap()
    }

    /** stnStates 里一辆车的原始状态。 */
    private class BusState(val stops: Int, val secs: Int, val dist: Int, val state: Int)

    companion object {

        /** 车来了协议里的固定盐，签名用。 */
        private const val SIGN_SALT = "qwihrnbtmj"

        /** 微信小程序通道密钥（ASCII 32 字节 → AES-256）。 */
        private const val KEY_WX = "FF32AE65FBFD19414EAAFF6291A54B42"

        /** H5 通道备用密钥。 */
        private const val KEY_H5 = "422556651C7F7B2B5C266EED06068230"

        private const val BASE_URL = "https://web.chelaile.net.cn/api"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
            " (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781" +
            "(0x6700143B) NetType/WIFI MiniProgramEnv/Windows WindowsWechat/WMPF"
        private const val REFERER = "https://servicewechat.com/wx71d589ea01ce3321/814/page-frame.html"

        /** 默认城市：唐山。车来了城市编号 056。 */
        const val DEFAULT_CITY_ID: String = "056"
        const val DEFAULT_CITY_NAME: String = "唐山"

        // ──────────────────────────── 对外接口 ────────────────────────────

        /** 支持实时公交的城市。失败抛异常，由调用方兜底成「当前城市」。 */
        @JvmStatic
        @Throws(Exception::class)
        fun cities(): List<BusModels.City> {
            // 城市列表走 web 站点的明文接口，不在 /api 下、也不需要签名
            val q = StringBuilder()
            for ((k, v) in base()) {
                if (q.length > 0) q.append('&')
                q.append(URLEncoder.encode(k, "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(v, "UTF-8"))
            }
            val raw = httpGet("https://web.chelaile.net.cn/wwd/ncitylist?$q")
            val start = raw.indexOf('{')
            if (start < 0) throw IOException("城市列表响应异常")
            val env = JSONObject(raw.substring(start, jsonEnd(raw, start)))
            val data = env.optJSONObject("data") ?: env
            val arr = data.optJSONArray("cityList")
            val out = ArrayList<BusModels.City>()
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val name = c.optString("cityName")
                val id = c.optString("cityId")
                if (name.isEmpty() || id.isEmpty()) continue
                out.add(BusModels.City(id, name))
            }
            return out
        }

        /** 附近站点（带每条线的到站）。limit 控制返回条数。 */
        @JvmStatic
        @Throws(Exception::class)
        fun nearby(cityId: String, lat: Double, lng: Double, limit: Int): List<BusModels.Station> {
            val p = base()
            p["cityId"] = cityId
            p["localCityId"] = "undefined"
            p["lat"] = fmt(lat)
            p["lng"] = fmt(lng)
            p["geo_lat"] = fmt(lat)
            p["geo_lng"] = fmt(lng)
            p["type"] = "5"
            p["permission"] = "0"

            val data = call("/bus/stop!encryptedHomePage.action", p)
            val arr = data.optJSONArray("nearSts")
            val out = ArrayList<BusModels.Station>()
            if (arr == null) return out
            var i = 0
            while (i < arr.length() && out.size < limit) {
                val s = arr.optJSONObject(i)
                i++
                if (s == null) continue
                val st = BusModels.Station()
                st.id = s.optString("sId")
                st.name = s.optString("sn")
                st.physicalId = s.optString("physicalStId")
                st.namesakeId = s.optString("namesakeStId")
                st.lat = s.optDouble("lat", 0.0)
                st.lng = s.optDouble("lng", 0.0)
                st.distanceMeters = if (s.has("distance")) s.optInt("distance", -1) else -1
                readArrivals(s.optJSONArray("lines"), st.arrivals)
                if (st.name.isEmpty()) continue
                out.add(st)
            }
            return out
        }

        /** 站点详情：一个物理站台下的全部线路与到站。 */
        @JvmStatic
        @Throws(Exception::class)
        fun stopDetail(cityId: String, physicalId: String?, namesakeId: String?,
                       stationId: String?, lat: Double, lng: Double): BusModels.Station {
            val p = base()
            p["cityId"] = cityId
            p["localCityId"] = cityId
            p["physicalStId"] = if (physicalId == null) "" else physicalId
            p["namesakeStId"] = if (namesakeId == null) "" else namesakeId
            p["firstLineId"] = ""
            p["stationId"] = if (stationId == null) "" else stationId
            val la = if (lat > 0) fmt(lat) else ""
            val ln = if (lng > 0) fmt(lng) else ""
            p["lat"] = la
            p["lng"] = ln
            p["geo_lat"] = la
            p["geo_lng"] = ln
            p["actionState"] = "1"
            p["permission"] = "0"

            val data = call("/bus/stop!encryptedPhyStnDetail.action", p)
            val list = data.optJSONArray("stationList")

            val out = BusModels.Station()
            out.id = if (stationId == null) "" else stationId
            out.physicalId = if (physicalId == null) "" else physicalId
            out.namesakeId = if (namesakeId == null) "" else namesakeId
            // 站点自己的坐标从响应里取；传进来的 lat/lng 是用户位置，别拿来当站点位置，
            // 否则回填列表时算出来的距离会变成 0
            out.lat = 0.0
            out.lng = 0.0

            val merged = ArrayList<BusModels.Arrival>()
            val seenPlatform = ArrayList<String>()
            if (list != null) {
                for (i in 0 until list.length()) {
                    val s = list.optJSONObject(i) ?: continue
                    if (out.name.isEmpty()) out.name = s.optString("sn")
                    if (out.id.isEmpty()) out.id = s.optString("sId")
                    if (out.physicalId.isEmpty()) out.physicalId = s.optString("physicalStId")
                    if (out.namesakeId.isEmpty()) out.namesakeId = s.optString("namesakeStId")
                    if (out.lat == 0.0) out.lat = s.optDouble("lat", 0.0)
                    if (out.lng == 0.0) out.lng = s.optDouble("lng", 0.0)
                    if (out.distanceMeters < 0 && s.has("distance")) out.distanceMeters = s.optInt("distance", -1)

                    // 每个站台记下来做切换；只有被请求的那个站台会带完整到站数据
                    val pid = s.optString("physicalStId")
                    if (pid.isNotEmpty() && !seenPlatform.contains(pid)) {
                        seenPlatform.add(pid)
                        out.platforms.add(BusModels.Platform(pid, s.optString("sn")))
                    }
                    readArrivals(s.optJSONArray("lines"), merged)
                }
            }
            out.arrivals.addAll(dedupeAndSort(merged))
            return out
        }

        /**
         * 「已到站」只认「还剩 0 分钟」。
         *
         * stnStates 里 `state=1`（车在站上）有时配着 `travelTime=60`，
         * 照 state 写「已到站」，点进线路详情却是「1 分钟 · 164 米」——同一个站
         * 两处说法打架。这里统一按秒数折算：能取整成 0 分钟才算到站；上游连秒数
         * 都没给时，才退回 state / 站数去判断。
         */
        private fun arrivedOf(minutes: Int, secs: Int, state: Int, stops: Int): Boolean {
            // 官方口径透传：travelTime==0 就是车到站（官方显示「已到站」）。
            // stnStates 里个别条目只给 state=1、不带秒数，是「车在本站」的官方标记；
            // 带 秒数 的条目一律按秒数说话（state=1 的远车停靠别站，不算到站）
            if (secs == 0) return true
            if (secs > 0) return false
            return state == 1 || stops == 0
        }

        /** 同一线路可能在多条占位里重复出现，按 lineId 去重，留下到站信息最好的那条。 */
        private fun dedupeAndSort(input: List<BusModels.Arrival>): List<BusModels.Arrival> {
            val best = LinkedHashMap<String, BusModels.Arrival>()
            for (a in input) {
                val cur = best[a.lineId]
                if (cur == null || rank(a) < rank(cur)) best[a.lineId] = a
            }
            val out = ArrayList(best.values)
            out.sortWith(Comparator<BusModels.Arrival> { x, y ->
                val rx = rank(x)
                val ry = rank(y)
                if (rx != ry) rx - ry else x.minutes - y.minutes
            })
            return out
        }

        /** 0 = 已到站，1 = 有分钟数，2 = 等待发车/末班已过，3 = 完全没数据。 */
        private fun rank(a: BusModels.Arrival): Int {
            if (a.arrived) return 0
            if (a.minutes >= 0) return 1
            if (a.status.isNotEmpty()) return 2
            return 3
        }

        /**
         * 关键字搜索：站点 + 线路一起搜（中文站名、线路号如「17」「17路」都行）。
         *
         * 上游这个接口一次只回前 3 个站点和前 10 条线路，同时把真实命中数放在
         * `stationCount` / `lineCount` 里。线路可以用 [searchMore]
         * 再要全量，站点没有更长的列表可翻，界面据此决定「还能不能继续加载」。
         */
        @JvmStatic
        @Throws(Exception::class)
        fun search(cityId: String, keyword: String, lat: Double, lng: Double): BusModels.SearchHit {
            val hit = BusModels.SearchHit()
            val result = searchResult(cityId, keyword, lat, lng, false) ?: return hit

            hit.lineCount = result.optInt("lineCount", 0)
            hit.stationCount = result.optInt("stationCount", 0)
            readStations(result, hit.stations)
            hit.lines.addAll(readLines(result))
            return hit
        }

        /**
         * 搜索的「还有更多」：同一个接口带 `more=true`，一次给全量线路
         * （实测「路」29 条线路 → 56 条正反方向记录，「1」43 条 → 84 条）。
         *
         * 代价是这种模式下上游不再回站点，所以只在用户已经清完第一屏时才调用。
         */
        @JvmStatic
        @Throws(Exception::class)
        fun searchMore(cityId: String, keyword: String, lat: Double, lng: Double): List<BusModels.Line> {
            val result = searchResult(cityId, keyword, lat, lng, true) ?: return ArrayList()
            return readLines(result)
        }

        @JvmStatic
        @Throws(Exception::class)
        fun searchResult(cityId: String, keyword: String, lat: Double, lng: Double,
                         more: Boolean): JSONObject? {
            val p = base()
            p["cityId"] = cityId
            p["localCityId"] = cityId
            p["key"] = keyword
            p["supportPhyStn"] = "true"
            if (more) p["more"] = "true"
            if (lat > 0 && lng > 0) {
                p["lat"] = fmt(lat)
                p["lng"] = fmt(lng)
                p["geo_lat"] = fmt(lat)
                p["geo_lng"] = fmt(lng)
            }
            return call("/bus/query!nSearch.action", p).optJSONObject("result")
        }

        /**
         * 上游把一条线的正反方向当两条记录返回（同名、同 lineNo）。搜索结果里合成一行：
         * 否则一屏 12 条其实只有 6 条线，翻五页全是成对的重复。方向在详情页里换，那里本来就是原地换向。
         */
        private fun readLines(result: JSONObject): List<BusModels.Line> {
            val uniq = LinkedHashMap<String, BusModels.Line>()
            val lines = result.optJSONArray("lines")
            if (lines == null) return ArrayList()
            for (i in 0 until lines.length()) {
                val l = lines.optJSONObject(i) ?: continue
                // 纯地铁线路没有实时在途数据，不列
                if (l.optInt("subwayV2", 0) == 1) continue
                val line = BusModels.Line()
                line.lineId = l.optString("lineId")
                line.name = l.optString("name")
                if (line.name.isEmpty()) line.name = l.optString("lineNo")
                line.lineNo = l.optString("lineNo")
                line.direction = l.optInt("direction", 0)
                line.startName = l.optString("startSn")
                line.endName = l.optString("endSn")
                line.firstTime = l.optString("firstTime")
                line.lastTime = l.optString("lastTime")
                if (line.lineId.isEmpty() || line.name.isEmpty()) continue

                val k = if (line.lineNo.isEmpty()) line.name else line.lineNo
                val old = uniq[k]
                // 留正方向那一行（direction=0）；只有反向时才先占位
                if (old == null || (old.direction != 0 && line.direction == 0)) {
                    uniq[k] = line
                }
            }
            return ArrayList(uniq.values)
        }

        /** 搜索结果里的站点自带经纬度和距离，可以直接点进站点详情。 */
        private fun readStations(result: JSONObject, out: MutableList<BusModels.Station>) {
            val arr = result.optJSONArray("stations") ?: return
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                if (s.optInt("subwayV2", 0) == 1) continue
                val st = BusModels.Station()
                st.name = s.optString("sn").trim()
                st.id = s.optString("sId")
                st.physicalId = s.optString("physicalStId")
                st.namesakeId = s.optString("namesakeStId")
                st.lat = s.optDouble("lat", 0.0)
                st.lng = s.optDouble("lng", 0.0)
                st.distanceMeters = if (s.has("distance")) s.optInt("distance", -1) else -1
                if (st.name.isEmpty() || st.id.isEmpty()) continue
                out.add(st)
            }
        }

        /**
         * 线路详情：站表 + 在途车辆。
         *
         * @param targetOrder 候车站的站序（1 基），>0 时会额外请求一次车辆预测，用于把
         *                    「最近一辆车在哪一站」标到站表上；传 0 也能出站表。
         */
        @JvmStatic
        @Throws(Exception::class)
        fun lineDetail(cityId: String, lineId: String, targetOrder: Int,
                       stationId: String?, lat: Double, lng: Double): BusModels.LineDetail {
            val p = base()
            p["cityId"] = cityId
            p["localCityId"] = cityId
            p["lineId"] = lineId
            val la = if (lat > 0) fmt(lat) else ""
            val ln = if (lng > 0) fmt(lng) else ""
            p["lat"] = la
            p["lng"] = ln
            p["geo_lat"] = la
            p["geo_lng"] = ln

            val data = call("/bus/line!encryptedLineDetail.action", p)

            val d = BusModels.LineDetail()
            val lo = data.optJSONObject("line")
            if (lo != null) {
                d.line.lineId = lineId
                d.line.name = lo.optString("name")
                if (d.line.name.isEmpty()) d.line.name = lo.optString("lineNo")
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
            // 收班/未运营时 depDesc 常是空串，官方状态写在 line.shortDesc
            // （和首页同一字段，如「不在运营时间」「末班时间已过」），
            // 没有就退一步用 nextOperationTimeDesc（如「首班预计明日 06:00 发车」）。
            // 不补这一层的话，首页显示运营状态、点进详情却变成「暂无预测」
            if (lo != null && d.status.isEmpty()) {
                d.status = lo.optString("shortDesc")
                if (d.status.isEmpty()) d.status = lo.optString("nextOperationTimeDesc")
            }

            val others = data.optJSONArray("otherlines")
            val rev = if (others == null) null else others.optJSONObject(0)
            if (rev != null) {
                d.reverseId = rev.optString("lineId")
                d.reverseStart = rev.optString("startSn")
                d.reverseEnd = rev.optString("endSn")
            }

            val sts = data.optJSONArray("stations")
            if (sts != null) {
                for (i in 0 until sts.length()) {
                    val s = sts.optJSONObject(i) ?: continue
                    val stop = BusModels.Stop()
                    stop.id = s.optString("sId")
                    stop.physicalId = s.optString("physicalStId")
                    stop.name = s.optString("sn")
                    // 站序一律用「在 stations 数组里的位置」。上游对「虚拟站组」会把一
                    // 串真实站点塞进一条 order=999 的记录里（sId 形如 999$13$4），而且组
                    // 后面站点的 order 还会往回退（组只占一个 order）。直接用 order 会
                    // 出现「13-1、13-2…」这种编号，和「全线 39 站」对不上。
                    // 数组本身就是行车顺序，位置即站序；buses[].order 也是这个口径。
                    stop.order = i + 1
                    stop.orderLabel = (i + 1).toString()
                    stop.lat = s.optDouble("wgsLat", 0.0)
                    stop.lng = s.optDouble("wgsLng", 0.0)
                    d.stops.add(stop)
                }
            }

            // 在途车辆：上游每辆车都带真实经纬度和 order（正开往第 order 站），
            // 用它到上一站的距离比例算出「在两站之间的什么位置」。
            val buses = data.optJSONArray("buses")
            if (buses != null) {
                for (i in 0 until buses.length()) {
                    val b = buses.optJSONObject(i) ?: continue
                    val order = b.optInt("order", 0)
                    if (order <= 0) continue
                    // 逐车卡片：上游这里给 order / busId / 车牌 / 时速 / 下一站，
                    // 到站 ETA 与距离要等候车站定下来后另问一次（下面 fillEta 里回填）
                    val v = BusModels.Vehicle()
                    v.id = b.optString("busId")
                    v.order = order
                    val plate = BusText.sanitizePlate(b.optString("licence"))
                    v.plate = if (plate.isEmpty()) BusText.sanitizePlate(v.id) else plate
                    v.speedKmh = b.optDouble("speed", 0.0)
                    v.nextStopName = b.optString("nextStationName").trim()
                    // ⚠️ lineDetail 里的 distanceToWaitStn 是「到这次请求 targetOrder 那一站的距离」，
                    // 而我们这次请求没带 targetOrder，上游给的是「到终点站的距离」
                    // （实测北京夜2：order 18/38/46 分别 19.8km / 8.6km / 2.7km，一路递减）。
                    // 拿它当「距本站」会显示十几公里，而且车子到站、busDetail 回了 -1 时
                    // 还会保留这个陈值 —— 就是「到站了 · 距本站 11.7公里」的来源。
                    // 距离一律留空，等 fillEta 用 busDetail 的逐车值回填。
                    v.distanceMeters = -1
                    d.vehicles.add(v)

                    val cur = stopByOrder(d.stops, order) ?: continue
                    val prev = stopByOrder(d.stops, order - 1)
                    val blat = b.optDouble("lat", 0.0)
                    val blng = b.optDouble("lng", 0.0)
                    var ratio = 1.0
                    if (prev != null && blat != 0.0 && blng != 0.0
                        && prev.lat != 0.0 && prev.lng != 0.0 && cur.lat != 0.0 && cur.lng != 0.0
                    ) {
                        val seg = distanceMeters(prev.lat, prev.lng, cur.lat, cur.lng)
                        val toPrev = distanceMeters(prev.lat, prev.lng, blat, blng)
                        if (seg > 1) ratio = Math.max(0.0, Math.min(1.0, toPrev / seg))
                    }
                    // 同一段可能有两辆车，取最靠前的那辆画
                    if (ratio > cur.busRatio) cur.busRatio = ratio
                    d.busCount++
                }
                for (s in d.stops) {
                    if (s.busRatio >= 0) s.busHere = s.busRatio >= 0.9
                }
            }

            // ── 候车站：进来时指定的那站，没指定就用离用户最近的那站 ──
            var wait: BusModels.Stop? = null
            // 指定了站点 id 就按 id 认站（虚拟站组的 order 都是 999，只按站序会认错站）
            if (stationId != null && stationId.isNotEmpty()) {
                for (s in d.stops) {
                    if (stationId == s.id) {
                        wait = s
                        break
                    }
                }
            }
            if (wait == null && targetOrder > 0) {
                for (s in d.stops) {
                    if (s.order == targetOrder) {
                        wait = s
                        break
                    }
                }
            }
            // 「离我最近」跟着定位走，和候车站是两回事：
            // 用户在线路详情里点了别的站，最近站的标注也不该跟着跑。
            var nearest: BusModels.Stop? = null
            if (lat > 0 && lng > 0 && d.stops.isNotEmpty()) {
                var best = Double.MAX_VALUE
                for (s in d.stops) {
                    if (s.lat == 0.0 && s.lng == 0.0) continue
                    val m = distanceMeters(lat, lng, s.lat, s.lng)
                    if (m < best) {
                        best = m
                        nearest = s
                    }
                }
            }
            if (nearest != null) nearest.nearest = true
            if (wait == null) wait = nearest
            if (wait == null && d.stops.isNotEmpty()) wait = d.stops[0]
            if (wait != null) {
                d.waitingStopName = wait.name
                d.waitingOrder = wait.order
                d.waitingStopId = wait.id
                // 候车站确定后就去查「车到这一站还有多久」——不管有没有定位，
                // 只要用户是从某个站点点进来的，这个数就该算出来
                fillEta(d, cityId, lineId, wait, stationId, lat, lng)
            }
            // 逐车卡片按站序升序：离候车站最近的车排最前
            Collections.sort(d.vehicles, Comparator<BusModels.Vehicle> { a, b -> a.order - b.order })
            return d
        }

        /**
         * 真实站序：虚拟站组（order=999，sId 形如 999$13$4）还原成它在整条线上的位置。
         *
         * 上游把一串连续的真实站点编码成「组」：`G` 是组前面最后一个站的 order，
         * `N` 是组内第几个，所以真实站序 = G + N（999$13$4 是第 17 站）。
         * 只取 G 会把这组里的 7 个站算成同一站，预测的「还剩几站」和车标位置都会错。
         */
        @JvmStatic
        fun effectiveOrder(order: Int, sId: String?): Int {
            if (order < 999) return order
            val seg = (if (sId == null) "" else sId).split("\$")
            if (seg.size >= 3) {
                try {
                    return Integer.parseInt(seg[1]) + Integer.parseInt(seg[2])
                } catch (ignored: NumberFormatException) {
                }
            }
            return order
        }

        /** 拉一次到「候车站」的车辆预测，填分钟 / 站数 / 距离。失败不影响站表。 */
        private fun fillEta(d: BusModels.LineDetail, cityId: String, lineId: String,
                            wait: BusModels.Stop, stationId: String?, lat: Double, lng: Double) {
            val bd = busDetail(cityId, lineId, wait.order, wait.id, lat, lng) ?: return
            val pr = bd.pred
            if (pr.secs >= 0) {
                // 官方口径透传：0 秒=已到站；60 秒内=即将到站（分钟位写 0，
                // 由 UI 统一渲染成「即将到站」）；再往上才是「N 分钟」
                d.etaArrived = pr.arrived
                d.etaMinutes = if (pr.secs > 0 && pr.secs < 60) 0 else (pr.secs + 59) / 60
                d.etaStops = pr.stops
                d.etaDistanceMeters = pr.dist
                // 大字是这辆车算出来的：小字（车牌 / 距离 / 到达时刻 / 时速）也只准读它。
                // 这辆车刚停靠完时，lineDetail 里它的 order 已经走到下一站，按站序另挑
                // 就会挑到后面那辆 —— 「到站了 · 快去上车 · 距本站 11.7公里」就是这么来的
                d.etaBusId = pr.busId
                d.etaNextBusId = pr.nextBusId
            } else if (pr.preArrival.isNotEmpty()) {
                // 没车在途：官方按发车计划估的「预计到站」时刻（preArrivalTime），
                // 有车在途时不给。取里面的 HH:mm，取不到就原样透传
                d.estArrival = hhmm(pr.preArrival)
            }
            if (d.status.isEmpty()) {
                d.status = pr.depDesc
                if (d.nextDeparture.isEmpty()) d.nextDeparture = pr.preArrival
            }
            // 逐车卡片：同一次响应里按 busId 回填到候车站的秒数 / 距离 / 到站判定
            for (v in d.vehicles) {
                val e = bd.byBus[v.id] ?: continue
                v.etaSeconds = e.seconds
                if (e.distance > 0) v.distanceMeters = e.distance
                // 到站判定照抄 Trackr：车正开往候车站（order 相同）且剩余秒数极小，
                // 或上游提示里明说「已到站」
                if (v.order == wait.order && (e.seconds in 0..20 || e.tip.contains("已到站"))) {
                    v.isArrived = true
                }
            }
        }

        /** 从任意文案里抠出第一个 HH:mm；没有就原样返回（空串不算）。 */
        private fun hhmm(s: String?): String {
            if (s == null) return ""
            val m = Pattern.compile("\\d{1,2}:\\d{2}").matcher(s)
            return if (m.find()) (m.group() ?: "") else s.trim()
        }

        // ───────────────────── 首站发车时刻表 ─────────────────────

        /**
         * 首站发车时刻表（车来了 `preStartTimetableNew`）。
         *
         * 响应是两块：`timetable` 是「今日发车预测」，上游只给还没发的那几班；
         * `yesterdayTimetable` 是「昨日实际班次」，一整天都全。下一班按
         * 「今日预测 → 昨日实际 → 明日首班」的顺序取：今日预测常常只覆盖未来几小时，
         * 晚一点就没有了，这时候不能空着，昨天同一时刻之后的那班一样有参考价值。
         */
        @JvmStatic
        @Throws(Exception::class)
        fun timetable(cityId: String, lineId: String, direction: Int,
                      lineNo: String?): BusModels.Timetable {
            val p = base()
            p["cityId"] = cityId
            p["lineId"] = lineId
            p["lineNo"] = if (lineNo == null) "" else lineNo
            p["direction"] = direction.toString()

            val data = call("/bus/line!preStartTimetableNew.action", p)
            val t = BusModels.Timetable()

            val lo = data.optJSONObject("line")
            if (lo != null) {
                t.lineName = lo.optString("name")
                t.startName = lo.optString("startSn")
                t.endName = lo.optString("endSn")
            }

            // ── 时刻表数据源检测 ──
            //
            // 上游同时给三块，按「越新越优先」取：
            //   ① timetable                 今日发车预测
            //   ② yesterdayTimetable        昨日实际班次
            //   ③ beforeYesterdayTimetable  前日实际班次
            //
            // 最近上游经常把 ①② 一起回空，只留 ③（实测唐山 1 路：0 段 / 0 段 / 4 段，
            // timeTableType=2）。原来只认 ①②，于是判成「没有时刻表」，
            // 线路详情里那张首站发车卡整张消失。
            //
            // 所以逐块探一遍：块里**真有班次**才算数（光有分组、班次列表空的也算空，
            // 否则会渲染出一堆空网格）；取到哪块就在文案里标清是哪天的数据。
            val todayBlock = data.optJSONObject("timetable")
            val yBlock = data.optJSONObject("yesterdayTimetable")
            val bBlock = data.optJSONObject("beforeYesterdayTimetable")

            val todaySegs = readScheduled(todayBlock)
            val ySegs = readScheduled(yBlock)
            val bSegs = readScheduled(bBlock)

            if (todaySegs.isNotEmpty()) {
                t.depTitle = todayBlock?.optString("depTitle").orEmpty()
                t.tip = todayBlock?.optString("tip").orEmpty()
                t.today.addAll(todaySegs)
            }
            if (ySegs.isNotEmpty()) {
                t.history.addAll(ySegs)
                t.historyTitle = "昨日实际班次"
                if (t.depTitle.isEmpty()) t.depTitle = yBlock?.optString("depTitle").orEmpty()
                if (t.tip.isEmpty()) t.tip = yBlock?.optString("tip").orEmpty()
            } else if (bSegs.isNotEmpty()) {
                // 前两天都空：退到前日的实际班次，一样是首站发车时间，能参考
                t.history.addAll(bSegs)
                t.historyTitle = "前日实际班次"
                if (t.depTitle.isEmpty()) t.depTitle = bBlock?.optString("depTitle").orEmpty()
                if (t.tip.isEmpty()) t.tip = "本时刻表取自前日的实际发车记录，仅供参考"
            }

            val todayTimes = t.todayTimes()
            val histTimes = t.historyTimes()
            if (histTimes.isNotEmpty()) {
                t.firstDep = histTimes[0]
                t.lastDep = histTimes[histTimes.size - 1]
            }
            for (seg in t.today) {
                if (seg.interval > 0) {
                    t.interval = seg.interval
                    break
                }
            }

            // 「现在」用手机本地时间：界面上说的「今天」就是用户手机上的今天
            val now = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            var next = firstAfter(todayTimes, now)
            if (next != null) {
                t.nextDep = next
                t.nextFrom = "今日预测"
            } else {
                next = firstAfter(histTimes, now)
                if (next != null) {
                    t.nextDep = next
                    // 「昨日」还是「前日」，跟弹层里的分节标题同一个口径
                    t.nextFrom = if ("前日实际班次" == t.historyTitle) "前日实际" else "昨日实际"
                } else if (histTimes.isNotEmpty()) {
                    t.nextDep = histTimes[0]
                    t.nextFrom = "明日首班"
                }
            }
            return t
        }

        private fun readTtSegments(block: JSONObject): List<BusModels.TtSegment> {
            val out = ArrayList<BusModels.TtSegment>()
            val segs = block.optJSONArray("timetable") ?: return out
            for (i in 0 until segs.length()) {
                val s = segs.optJSONObject(i) ?: continue
                val seg = BusModels.TtSegment()
                seg.from = s.optString("fTime")
                seg.to = s.optString("eTime")
                seg.tips = s.optString("depIntervalTips")
                seg.interval = s.optInt("depInterval", -1)
                val sch = s.optJSONArray("schedules")
                if (sch != null) {
                    for (k in 0 until sch.length()) {
                        // 班次有两种写法：{"time":"07:43"} 和直接给字符串
                        val o = sch.optJSONObject(k)
                        val time = if (o != null) o.optString("time") else sch.optString(k)
                        if (time != null && time.isNotEmpty()) seg.times.add(time.trim())
                    }
                }
                val plain = s.optJSONArray("times")
                if (plain != null && seg.times.isEmpty()) {
                    for (k in 0 until plain.length()) {
                        val time = plain.optString(k)
                        if (time.isNotEmpty()) seg.times.add(time.trim())
                    }
                }
                BusModels.sortByClock(seg.times)
                out.add(seg)
            }
            return out
        }

        /**
         * 读一块时刻表，只留**真有班次**的分组。
         * 上游有时候会回「分组有、班次列表是空的」的壳子，直接当数据用会渲染出一排空网格，
         * 下一班也算不出来 —— 这种和「没有数据」一样处理。
         */
        private fun readScheduled(block: JSONObject?): List<BusModels.TtSegment> {
            if (block == null) return emptyList()
            val out = ArrayList<BusModels.TtSegment>()
            for (s in readTtSegments(block)) {
                if (s.times.isNotEmpty()) out.add(s)
            }
            return out
        }

        /** 排好序的时刻表里第一个「不早于 now」的班次；都不早于就返回 null。 */
        private fun firstAfter(sorted: List<String>, now: String): String? {
            val n = BusModels.minutesOf(now)
            if (n < 0) return null
            for (t in sorted) {
                val m = BusModels.minutesOf(t)
                if (m >= n) return t
            }
            return null
        }

        /**
         * 某条线的车到指定站还有多久。
         *
         * 首页和线路详情共用这一个口径 —— 之前首页只读 nearSts 里的 stnStates，
         * 稍远一点的站点上游压根不给，于是首页写「暂无实时」，点进线路详情却算出 14 分钟。
         * 统一走这里，两处数字才对得上。
         */
        @JvmStatic
        fun predict(cityId: String, lineId: String, order: Int, stopId: String?,
                    lat: Double, lng: Double): Pred? {
            val bd = busDetail(cityId, lineId, order, stopId, lat, lng) ?: return null
            return bd.pred
        }

        /**
         * 一次 encryptedBusDetail，两样东西一起拿：
         *  - [BusDetail.pred]：最快那辆的秒数 / 站数 / 距离（和 [predict] 的老口径完全一致）
         *  - [BusDetail.byBus]：每辆车（busId）到候车站的 travelTime / 距离 / 推荐文案
         *
         * 逐车卡片和「最快一辆」必须来自同一次响应，否则同一个页面两处数字会打架。
         */
        @JvmStatic
        fun busDetail(cityId: String, lineId: String, order: Int, stopId: String?,
                      lat: Double, lng: Double): BusDetail? {
            try {
                val p = base()
                p["cshow"] = "busDetail"
                p["specail"] = "0"
                p["specialType"] = "undefined"
                p["cityId"] = cityId
                p["localCityId"] = cityId
                p["lineId"] = lineId
                p["targetOrder"] = order.toString()
                p["specialTargetOrder"] = order.toString()
                p["stationId"] = if (stopId == null) "" else stopId
                // 没有定位也要能算：候车站是靠 targetOrder 指定的，坐标只是附带的参考
                val la = if (lat > 0) fmt(lat) else ""
                val ln = if (lng > 0) fmt(lng) else ""
                p["lat"] = la
                p["lng"] = ln
                p["geo_lat"] = la
                p["geo_lng"] = ln
                p["userId"] = ""
                p["h5Id"] = ""
                p["unionId"] = ""
                p["accountId"] = ""
                p["secret"] = ""

                val data = call("/bus/line!encryptedBusDetail.action", p)
                val bd = BusDetail()
                val out = bd.pred
                out.depDesc = data.optString("depDesc")
                out.preArrival = data.optString("preArrivalTime")

                val buses = data.optJSONArray("buses") ?: return bd

                var bestSecs = Int.MAX_VALUE
                var bestArrived = false
                var nextSecs = Int.MAX_VALUE
                var nextStops = -1
                var nextBusId = ""
                for (i in 0 until buses.length()) {
                    val b = buses.optJSONObject(i) ?: continue
                    val travels = b.optJSONArray("travels")
                    if (travels == null || travels.length() == 0) continue
                    val tr = travels.optJSONObject(0) ?: continue
                    val secs = tr.optInt("travelTime", -1)
                    // 官方口径直接透传：travelTime==0 就是车到站了（官方显示「已到站」）。
                    // 别用 buses[].state——实测那只是「车停靠在任意一站」的标记，
                    // 离候车站 30 分钟开外的车也一样是 1，拿来判定永远误报
                    val arrived = secs == 0

                    // 逐车明细：上游只给「还没过站」的车列 travels，正好是卡片要的那批
                    val bId = b.optString("busId")
                    if (bId.isNotEmpty()) {
                        val e = BusEta()
                        e.busId = bId
                        e.seconds = secs
                        e.tip = tr.optString("recommTip")
                        e.order = b.optInt("order", 0)
                        val dist = b.optInt("distanceToWaitStn", -1)
                        e.distance = if (dist > 0) dist else -1
                        bd.byBus[bId] = e
                    }

                    if (secs < 0) continue
                    val busOrder = b.optInt("order", 0)
                    // 虚拟站组的 order 是 999，真站序藏在 sId 里（999$30$1 → 30），
                    // 直接拿 999 去减会算出「还有 974 站」
                    val mine = effectiveOrder(order, stopId)
                    val stops = if (busOrder > 0) Math.max(0, mine - busOrder) else -1
                    val dist = b.optInt("distanceToWaitStn", -1)
                    // 取秒数最小的那辆（同秒数先到先得，别在两辆车之间来回跳），
                    // 并把它的 busId 记下来：秒数 / 距离是这辆车的，界面上的小字也只准读这辆。
                    // 被顶下来的那辆顺势成为「下辆」，秒数和站数都是同一份响应里的真值。
                    if (secs < bestSecs) {
                        if (bestSecs != Int.MAX_VALUE) {
                            nextSecs = bestSecs
                            nextStops = out.stops
                            nextBusId = out.busId
                        }
                        bestSecs = secs
                        bestArrived = arrived
                        out.busId = bId
                        out.stops = stops
                        out.dist = if (dist > 0) dist else -1
                    } else if (secs < nextSecs) {
                        nextSecs = secs
                        nextStops = stops
                        nextBusId = bId
                    }
                }
                out.secs = if (bestSecs == Int.MAX_VALUE) -1 else bestSecs
                out.arrived = bestArrived
                out.nextSecs = if (nextSecs == Int.MAX_VALUE) -1 else nextSecs
                out.nextStops = nextStops
                out.nextBusId = nextBusId
                return bd
            } catch (ignored: Exception) {
                // 预测是加分项，失败就只显示站表
                return null
            }
        }

        /**
         * 把一批「正显示在屏幕上」的到站行整体重算一遍。
         *
         * 首页的 nearSts 只对最近几站算实时，而且同一趟车它给的秒数常常是 -1
         * （只剩站数），点进线路详情却能算出「1 分钟」——两处说的不是一回事。
         * 这里统一改走 [predict]，和线路详情同一个接口、同一个口径。
         *
         * @param force   true = 已经有数的行也重算（定时刷新 / 进页面先用这一档）；
         *                false = 只补还没有数据的那几条
         * @param limit   这一轮最多算几条，防止一次轰几十个请求被上游掐掉
         * @param watcher 每条回来回调一次，界面只换那一行文字
         */
        @JvmStatic
        fun refreshEta(cityId: String, shown: List<BusModels.Arrival>?,
                       lat: Double, lng: Double, limit: Int,
                       force: Boolean, watcher: EtaWatcher?) {
            if (shown == null || shown.isEmpty()) return
            val todo = ArrayList<BusModels.Arrival>()
            val stopIds = ArrayList<String>()
            for (a in shown) {
                if (todo.size >= limit) break
                // 不知道这个站在线路上排第几就没法算预测，跳过（这些行本来也只显示状态文案）
                if (a.orderInLine <= 0) continue
                if (!force && (a.arrived || a.minutes >= 0)) continue
                // 一点数据都没有的先挂「…」，别让人以为这站真没车；
                // 已经算过的就别再闪一次了
                if (!a.arrived && a.minutes < 0 && !a.refreshed) a.pending = true
                todo.add(a)
                stopIds.add(if (a.stopId == null) "" else a.stopId)
            }
            runPredict(cityId, todo, stopIds, lat, lng, watcher)
        }

        private fun runPredict(cityId: String, todo: List<BusModels.Arrival>,
                               stopIds: List<String>, lat: Double, lng: Double,
                               watcher: EtaWatcher?) {
            if (todo.isEmpty()) return

            val n = todo.size
            // 三条并发就够了：上游对这个接口有限流，几十条一起打会直接被掐连接，
            // 那时候用户看到的还是一排「暂无实时」，比慢一点更糟
            val workers = Math.min(3, n)
            Thread({
                val ts = ArrayList<Thread>(workers)
                for (i in 0 until workers) {
                    val idx = i
                    val t = Thread({
                        var k = idx
                        while (k < n) {
                            val a = todo[k]
                            var pr = predict(cityId, a.lineId, a.orderInLine, stopIds[k], lat, lng)
                            if (pr == null) {
                                // 网络抖一下就重试一次，别让这一排永远停在「暂无实时」
                                try {
                                    Thread.sleep(220)
                                } catch (ignored: InterruptedException) {
                                }
                                pr = predict(cityId, a.lineId, a.orderInLine, stopIds[k], lat, lng)
                            }
                            if (pr != null && pr.secs >= 0) {
                                // 与线路详情同一口径：60 秒内都是「即将到站」
                                a.minutes = BusText.minutesOfSecs(pr.secs)
                                a.stopsAway = pr.stops
                                a.distanceMeters = pr.dist
                                a.realtime = true
                                // travelTime==0 是官方的「已到站」
                                if (pr.arrived) a.arrived = true
                                // 第二辆车也用这一份 busDetail 的真值替换 nearSts 的旧值；
                                // 这一轮上游只给一辆车就清空，绝不把别的时刻的数字留着当真
                                a.nextMinutes = BusText.minutesOfSecs(pr.nextSecs)
                                a.nextStopsAway = if (pr.nextSecs >= 0) pr.nextStops else -1
                            }
                            // 补不到就老实收起「…」，别一直转
                            a.pending = false
                            a.refreshed = true
                            if (watcher != null) watcher.onEta(a)
                            try {
                                Thread.sleep(70)
                            } catch (ignored: InterruptedException) {
                            }
                            k += workers
                        }
                    }, "bus-eta-$i")
                    ts.add(t)
                    t.start()
                }
                for (th in ts) {
                    try {
                        th.join()
                    } catch (ignored: InterruptedException) {
                    }
                }
            }, "bus-eta-all").start()
        }

        /** 球面距离（米）。站与用户都在 WGS84 下，直接算即可。 */
        @JvmStatic
        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6371000.0
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lng2 - lng1)
            val h = Math.sin(dp / 2) * Math.sin(dp / 2) +
                Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
            return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
        }

        // ──────────────────────────── 协议实现 ────────────────────────────

        private fun base(): MutableMap<String, String> {
            val p = LinkedHashMap<String, String>()
            p["s"] = "h5"
            p["wxs"] = "wx_app"
            p["sign"] = "1"
            p["h5RealData"] = "1"
            p["v"] = "3.11.28"
            p["src"] = "weixinapp_cx"
            p["ctm_mp"] = "mp_wx"
            p["vc"] = "2"
            p["favoriteGray"] = "1"
            p["gpstype"] = "wgs"
            p["geo_type"] = "wgs"
            p["scene"] = "1256"
            return p
        }

        /** 车来了签名：按参数插入顺序拼 `"k"="v"&...` 再加盐取 MD5 小写。 */
        @JvmStatic
        fun cryptoSign(params: Map<String, String>): String {
            val sb = StringBuilder()
            for ((k, v) in params) {
                if (sb.length > 0) sb.append('&')
                sb.append('"').append(k).append("\"=\"").append(v).append('"')
            }
            sb.append(SIGN_SALT)
            return md5Hex(sb.toString())
        }

        /** 发一次 GET，返回解密后的业务 JSON。 */
        @JvmStatic
        @Throws(Exception::class)
        fun call(path: String, params: Map<String, String>): JSONObject {
            val signed = LinkedHashMap(params)
            signed["cryptoSign"] = cryptoSign(params)

            val q = StringBuilder()
            for ((k, v) in signed) {
                if (q.length > 0) q.append('&')
                q.append(URLEncoder.encode(k, "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(v, "UTF-8"))
            }
            val raw = httpGet(BASE_URL + path + "?" + q)
            return extract(raw)
        }

        private fun httpGet(urlStr: String): String {
            val c = URL(urlStr).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "GET"
                c.connectTimeout = 12000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Referer", REFERER)
                c.setRequestProperty("xweb_xhr", "1")
                c.setRequestProperty("Accept", "*/*")
                c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                val code = c.responseCode
                val ins = if (code >= 400) c.errorStream else c.inputStream
                if (ins == null) throw IOException("HTTP $code")
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var n = ins.read(buf)
                while (n > 0) {
                    bos.write(buf, 0, n)
                    n = ins.read(buf)
                }
                ins.close()
                if (code >= 400) throw IOException("HTTP $code")
                return String(bos.toByteArray(), StandardCharsets.UTF_8)
            } finally {
                c.disconnect()
            }
        }

        /**
         * 剥掉 `**YGKJ` 前后缀，取 JSON 主体；`jsonr.data` 里若含
         * `encryptResult` 则解密后返回业务 JSON。
         */
        private fun extract(raw: String): JSONObject {
            val start = raw.indexOf('{')
            if (start < 0) throw IOException("响应不是 JSON")
            val end = jsonEnd(raw, start)
            val env = JSONObject(raw.substring(start, end))

            val jsonr = env.optJSONObject("jsonr")
            val data: Any? = if (jsonr == null) env.opt("data") else jsonr.opt("data")

            if (data is String) {
                val s = data.trim()
                if (s.isEmpty()) return JSONObject()
                return try {
                    JSONObject(decrypt(s))
                } catch (e: Exception) {
                    JSONObject(s)
                }
            }
            if (data is JSONObject) {
                val enc = data.optString("encryptResult")
                if (enc.isNotEmpty()) return JSONObject(decrypt(enc))
                return data
            }
            return if (jsonr != null) jsonr else env
        }

        /** 括号计数找 JSON 结尾（跳过字符串字面量里的花括号）。 */
        private fun jsonEnd(raw: String, start: Int): Int {
            var depth = 0
            var inStr = false
            var esc = false
            for (i in start until raw.length) {
                val c = raw[i]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                    continue
                }
                if (c == '"') inStr = true
                else if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            val last = raw.lastIndexOf('}')
            return if (last > start) last + 1 else raw.length
        }

        /** AES/ECB/PKCS5 解密。两把密钥都试，解出来必须是合法 JSON 才算成功。 */
        private fun decrypt(b64: String): String {
            val cipherBytes = Base64.decode(Regex("\\s").replace(b64, ""), Base64.DEFAULT)
            val keys = arrayOf(KEY_WX, KEY_H5)
            var last: Exception? = null
            for (k in keys) {
                try {
                    val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k.toByteArray(StandardCharsets.UTF_8), "AES"))
                    val text = String(c.doFinal(cipherBytes), StandardCharsets.UTF_8).trim()
                    if (text.startsWith("{") || text.startsWith("[")) return text
                } catch (e: Exception) {
                    last = e
                }
            }
            throw last ?: IllegalStateException("解密失败")
        }

        @JvmStatic
        fun md5Hex(s: String): String {
            return try {
                val md = MessageDigest.getInstance("MD5")
                val d = md.digest(s.toByteArray(StandardCharsets.UTF_8))
                val sb = StringBuilder(d.size * 2)
                for (b in d) sb.append(String.format(Locale.US, "%02x", b))
                sb.toString()
            } catch (e: Exception) {
                ""
            }
        }

        // ──────────────────────────── 解析辅助 ────────────────────────────

        /**
         * 解析 `lines[]`。每条线的 `stnStates` 里 `value` 是
         * 「距本站还剩几站」，`travelTime` 是秒，按到站先后排序后取前两辆：
         * 第一辆进 `minutes/stopsAway`，第二辆进 `nextMinutes/nextStopsAway`
         * （对应车来了首页那个「下辆」）。
         */
        private fun readArrivals(lines: JSONArray?, into: MutableList<BusModels.Arrival>) {
            if (lines == null) return
            for (i in 0 until lines.length()) {
                val li = lines.optJSONObject(i) ?: continue
                val lo = li.optJSONObject("line") ?: continue
                if (lo.optInt("subwayV2", 0) == 1) continue

                val a = BusModels.Arrival()
                a.lineId = lo.optString("lineId")
                a.lineName = lo.optString("name")
                if (a.lineName.isEmpty()) a.lineName = lo.optString("lineNo")
                a.direction = lo.optInt("direction", 0)
                a.startName = lo.optString("startSn")
                a.endName = lo.optString("endSn")
                a.status = lo.optString("shortDesc")
                if (a.status.isEmpty()) a.status = lo.optString("nextOperationTimeDesc")
                if (a.lineName.isEmpty()) continue
                // 同一站的其他站台会回一批「只有名字、没有 lineId」的占位线路，
                // 它们没有到站数据，混进来就变成一排「开往 — / 暂无车」
                if (a.lineId.isEmpty()) continue

                // 这个站在该线路上的站序：站点详情和首页的每条 line 都带 targetStation，
                // 用它才能让「从 A 站进线路详情」默认候车站就是 A 站。
                // 虚拟站组的 order 是 999，要用 sId 还原成真实站序，否则预测会算到别的站去。
                val target = li.optJSONObject("targetStation")
                if (target != null) {
                    a.stopId = target.optString("sId")
                    a.orderInLine = effectiveOrder(target.optInt("order", 0), a.stopId)
                }

                val states = li.optJSONArray("stnStates")
                a.realtime = states != null && states.length() > 0
                val cand = ArrayList<BusState>()
                if (states != null) {
                    for (k in 0 until states.length()) {
                        val b = states.optJSONObject(k) ?: continue
                        val stops = b.optInt("value", -1)
                        val secs = b.optInt("travelTime", -1)
                        val state = b.optInt("state", 0)
                        val dist = b.optInt("distanceToDest", -1)
                        // state == 1 是「已到站」：这种条目有时 value/travelTime 都是 -1，
                        // 但它确实代表有车在站上，不能当成没数据丢掉
                        if (stops < 0 && secs < 0 && state != 1) continue
                        cand.add(BusState(stops, secs, dist, state))
                    }
                }
                cand.sortWith(Comparator<BusState> { x, y ->
                    val sx = if (x.stops >= 0) x.stops else 9999
                    val sy = if (y.stops >= 0) y.stops else 9999
                    if (sx != sy) sx - sy
                    else (if (x.secs < 0) 99999 else x.secs) - (if (y.secs < 0) 99999 else y.secs)
                })

                // 没有到站数据的，等下单独补一次预测；先标上 pending，界面显示「…」
                a.pending = cand.isEmpty() && a.orderInLine > 0
                if (cand.isNotEmpty()) {
                    val f = cand[0]
                    a.stopsAway = f.stops
                    a.distanceMeters = f.dist
                    // 与线路详情同一口径：60 秒内是「即将到站」（分钟位写 0），
                    // travelTime==0 是「已到站」。先算分钟再判到站，别拿还没赋值的字段
                    a.minutes = BusText.minutesOfSecs(f.secs)
                    a.arrived = arrivedOf(a.minutes, f.secs, f.state, f.stops)
                    if (cand.size > 1) {
                        val s2 = cand[1]
                        a.nextStopsAway = s2.stops
                        a.nextMinutes = BusText.minutesOfSecs(s2.secs)
                    }
                }
                into.add(a)
            }
        }

        /** 把车辆标到站表对应站序上；标到了返回 true。 */
        private fun stopByOrder(stops: List<BusModels.Stop>, order: Int): BusModels.Stop? {
            for (s in stops) {
                if (s.order == order) return s
            }
            return null
        }

        /** 坐标固定 6 位小数，避免 Locale 把小数点变成逗号。 */
        private fun fmt(v: Double): String {
            return String.format(Locale.US, "%.6f", v)
        }

        // ──────────────────── 首页下滑：往外扩一圈找更远的站 ────────────────────

        /**
         * 附近站点接口一次只给最近的 15 个，没有翻页参数，而且**只有最靠前的两三个站**
         * 带实时到站数据。想再往外看，就以用户位置为中心、按 8 个方位把查询点挪到
         * ring×700 米处各查一次，合起来去重；每个新站再单独查一次站点详情，
         * 拿它自己的到站数据和经纬度——距离和到站时间都是真算出来的，不估。
         */

        /** 一轮最多解出多少站的坐标（每个站一次详情请求，别一次打太多）。 */
        private const val RESOLVE_MAX = 20

        /** 一轮最多往外给几站，剩下的留给下一轮——列表一段一段长，手感和无限列表一致。 */
        private const val PAGE_SIZE = 12

        /** 站点坐标缓存：环形探针一轮会重复探到同一批站，坐标不会变，存下来省请求。 */
        private val COORD_CACHE: MutableMap<String, DoubleArray> = ConcurrentHashMap<String, DoubleArray>()

        @JvmStatic
        fun fartherStations(cityId: String, lat: Double, lng: Double,
                            ring: Int, exclude: Set<String>?): List<BusModels.Station> {
            val dirs = 8
            val radius = 700.0 * Math.max(1, ring)
            val bucket: MutableList<BusModels.Station> = Collections.synchronizedList(ArrayList<BusModels.Station>())
            val probes = ArrayList<Thread>(dirs)
            for (i in 0 until dirs) {
                val bearing = i * 360.0 / dirs
                val t = Thread({
                    try {
                        val p = offsetPoint(lat, lng, radius, bearing)
                        bucket.addAll(nearby(cityId, p[0], p[1], 15))
                    } catch (ignored: Exception) {
                    }
                }, "bus-probe-$i")
                probes.add(t)
                t.start()
            }
            for (th in probes) {
                try {
                    th.join()
                } catch (ignored: InterruptedException) {
                }
            }

            val uniq = LinkedHashMap<String, BusModels.Station>()
            for (s in bucket) {
                if (s.id.isEmpty()) continue
                if (exclude != null && exclude.contains(s.id)) continue
                if (!uniq.containsKey(s.id)) uniq[s.id] = s
            }
            if (uniq.isEmpty()) return ArrayList()

            // 附近站点接口不给经纬度（只有服务端算好的 distance），所以每个新站得单独查一次
            // 详情拿坐标——距离、排序、以后回填都得用它。查过的进缓存，下一圈不再问。
            var todo = ArrayList<BusModels.Station>()
            for (s in uniq.values) {
                val c = COORD_CACHE[s.id]
                if (c != null) {
                    s.lat = c[0]
                    s.lng = c[1]
                } else {
                    todo.add(s)
                }
            }
            if (todo.size > RESOLVE_MAX) todo = ArrayList(todo.subList(0, RESOLVE_MAX))

            val task = todo
            val workers = Math.min(4, task.size)
            val ts = ArrayList<Thread>(workers)
            for (i in 0 until workers) {
                val idx = i
                val t = Thread({
                    var k = idx
                    while (k < task.size) {
                        val s = task[k]
                        mergeDetail(s, fetchStationDetail(cityId, s, lat, lng))
                        if (s.lat != 0.0 && s.lng != 0.0) {
                            if (COORD_CACHE.size > 600) COORD_CACHE.clear()
                            COORD_CACHE[s.id] = doubleArrayOf(s.lat, s.lng)
                        }
                        try {
                            Thread.sleep(40)
                        } catch (ignored: InterruptedException) {
                        }
                        k += workers
                    }
                }, "bus-resolve-$i")
                ts.add(t)
                t.start()
            }
            for (th in ts) {
                try {
                    th.join()
                } catch (ignored: InterruptedException) {
                }
            }

            var keep = ArrayList<BusModels.Station>()
            for (s in uniq.values) {
                if (s.lat == 0.0 || s.lng == 0.0) continue      // 拿不到坐标就不硬塞，免得写个假距离
                s.distanceMeters = Math.round(distanceMeters(lat, lng, s.lat, s.lng)).toInt()
                keep.add(s)
            }
            Collections.sort(keep, Comparator<BusModels.Station> { a, b ->
                a.distanceMeters.compareTo(b.distanceMeters)
            })
            if (keep.size > PAGE_SIZE) keep = ArrayList(keep.subList(0, PAGE_SIZE))
            return keep
        }

        /** 任一线路有实时数据就算这站有数据。 */
        @JvmStatic
        fun hasRealtime(s: BusModels.Station): Boolean {
            for (a in s.arrivals) {
                if (a.realtime) return true
            }
            return false
        }

        /**
         * 批量拉站点详情，只往回带数据、不动传进来的对象。
         *
         * 首页要先把 nearby 那点数据立刻画出来（一行列表 200~400ms 就能上屏），
         * 详情这批一两秒才回来，所以不在工作线程里改 `Station`：那边还在读，
         * 这边改 arrivals 会撞出并发问题。取回来交给 UI 线程合并。
         *
         * @return stationId → 详情
         */
        @JvmStatic
        fun stationDetails(cityId: String, list: List<BusModels.Station>,
                           lat: Double, lng: Double): MutableMap<String, BusModels.Station> {
            val out: MutableMap<String, BusModels.Station> = ConcurrentHashMap<String, BusModels.Station>()
            val todo = ArrayList(list)
            if (todo.isEmpty()) return out
            // 一次开 8 个线程打过去，上游会掐掉一部分连接（掐掉的那几个站就永远停在
            // 「暂无实时」）。三条并发、每条之间歇一下，慢一两秒但每个站都能拿到。
            val workers = Math.min(3, todo.size)
            val ts = ArrayList<Thread>(workers)
            for (i in 0 until workers) {
                val idx = i
                val t = Thread({
                    var k = idx
                    while (k < todo.size) {
                        val s = todo[k]
                        val full = fetchStationDetail(cityId, s, lat, lng)
                        if (full != null) out[s.id] = full
                        try {
                            Thread.sleep(60)
                        } catch (ignored: InterruptedException) {
                        }
                        k += workers
                    }
                }, "bus-st-$i")
                ts.add(t)
                t.start()
            }
            for (th in ts) {
                try {
                    th.join()
                } catch (ignored: InterruptedException) {
                }
            }
            return out
        }

        /** 拉一个站的详情并合并回列表里的那条记录（UI 线程调用）。 */
        @JvmStatic
        fun mergeDetail(s: BusModels.Station?, full: BusModels.Station?) {
            if (s == null || full == null) return
            // 注意是**合并**不是替换：详情只会给被请求那个站台的完整数据，其他站台是占位，
            // 直接覆盖会把别的站台的线路弄丢——首页要显示全部线路，站台切换留给站点详情页。
            if (full.lat != 0.0 && full.lng != 0.0) {
                s.lat = full.lat
                s.lng = full.lng
            }
            if (s.platforms.isEmpty()) s.platforms.addAll(full.platforms)
            if (full.arrivals.isEmpty()) return
            val fresh = LinkedHashMap<String, BusModels.Arrival>()
            for (a in full.arrivals) fresh[key(a)] = a
            for (k in s.arrivals.indices) {
                val old = s.arrivals[k]
                val neu = fresh.remove(key(old)) ?: continue
                // 详情不保证每趟都给实时：新来这条是空的时候，别把已经算好的分钟数冲掉，
                // 只把站序这类静态信息补上
                val oldHasData = old.arrived || old.minutes >= 0
                val neuHasData = neu.arrived || neu.minutes >= 0 || neu.realtime
                if (!neuHasData && oldHasData) {
                    if (neu.orderInLine > 0) old.orderInLine = neu.orderInLine
                    if (old.stopId.isEmpty()) old.stopId = neu.stopId
                    if (old.status.isEmpty()) old.status = neu.status
                    continue
                }
                s.arrivals[k] = neu
            }
            s.arrivals.addAll(fresh.values)
        }

        /** 拉一个站的详情；失败歇一下再试一次。 */
        private fun fetchStationDetail(cityId: String, s: BusModels.Station,
                                       lat: Double, lng: Double): BusModels.Station? {
            var full: BusModels.Station? = null
            try {
                full = stopDetail(cityId, s.physicalId, s.namesakeId, s.id, lat, lng)
            } catch (ignored: Exception) {
            }
            if (full == null) {
                try {
                    Thread.sleep(260)
                    full = stopDetail(cityId, s.physicalId, s.namesakeId, s.id, lat, lng)
                } catch (ignored: Exception) {
                }
            }
            return full
        }

        private fun key(a: BusModels.Arrival): String {
            return a.lineId + "#" + a.direction
        }

        /** 从 (lat,lng) 沿方位角走 meters 米后的坐标（球面公式，够用）。 */
        private fun offsetPoint(lat: Double, lng: Double, meters: Double, bearingDeg: Double): DoubleArray {
            val r = 6371000.0
            val br = Math.toRadians(bearingDeg)
            val la1 = Math.toRadians(lat)
            val lo1 = Math.toRadians(lng)
            val dr = meters / r
            val la2 = Math.asin(Math.sin(la1) * Math.cos(dr) +
                Math.cos(la1) * Math.sin(dr) * Math.cos(br))
            val lo2 = lo1 + Math.atan2(Math.sin(br) * Math.sin(dr) * Math.cos(la1),
                Math.cos(dr) - Math.sin(la1) * Math.sin(la2))
            return doubleArrayOf(Math.toDegrees(la2), Math.toDegrees(lo2))
        }
    }
}
