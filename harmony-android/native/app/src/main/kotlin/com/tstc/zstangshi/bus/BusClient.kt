package com.tstc.zstangshi.bus

import java.io.IOException
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 实时公交的数据源分发器。
 *
 * 底层有两套实现，对外的接口签名完全一致：
 *
 *  - [ChelaileClient] —— 车来了（默认）。到站时间更准，但个别城乡线路搜不到。
 *  - [MygolbsClient] —— 掌上公交。线路更全（含城乡公交），协议在它自己类里，
 *    这层留着的掌上公交编排逻辑是它的专属分支。
 *
 * 用户在实时公交首页右上角切换，选完就记住（存 [BusStore]），
 * 下次打开直接用他选的那个。两套的 lineId / cityId 编号体系互不相通，
 * 所以城市按源各存一份、收藏按源过滤。
 *
 * 请求节奏统一控制：预测 3 并发 + 70ms 间隔，站点详情 3 并发，
 * 附近站点补线路 4 并发。上游对密集请求会直接掐连接，宁可慢一点。
 *
 * 全部方法都是阻塞的，调用方负责放到后台线程。
 */
class BusClient private constructor() {

    /** 一次车辆预测的结果（最快的车还有多久到、还剩几站、离多少米）。 */
    class Pred {
        @JvmField var secs: Int = -1
        @JvmField var stops: Int = -1
        @JvmField var dist: Int = -1

        /** 车真的在候车站上（上游明说「已到站」），跟「小于1分钟」是两回事 */
        @JvmField var arrived: Boolean = false

        /** 上面这几个数属于哪辆车（上游车辆标识）；空 = 不知道，界面按站序兜底 */
        @JvmField var busId: String = ""
        @JvmField var depDesc: String = ""
        @JvmField var preArrival: String = ""

        /** 紧随其后的那辆车（同一次预测里的真值）；-1 = 上游只给了一辆。 */
        @JvmField var nextSecs: Int = -1
        @JvmField var nextStops: Int = -1
        @JvmField var nextBusId: String = ""
    }

    /**
     * 补预测的进度回调，在后台线程上回调，调用方自己 post 到 UI。
     * 刻意只留一个方法：单方法接口能用 lambda，不受 d8 对内部类的那点脾气影响。
     */
    fun interface EtaWatcher {
        fun onEta(a: BusModels.Arrival)
    }

    companion object {

        /** 数据源：车来了。 */
        const val SRC_CHELAILE: Int = 0

        /** 数据源：掌上公交。 */
        const val SRC_MYGOLBS: Int = 1

        @Volatile
        private var src: Int = SRC_CHELAILE

        /**
         * 默认城市：随数据源走 —— 车来了是数字编号「056」，
         * 掌上公交直接用城市名「唐山市」。[setSource] 里更新。
         */
        @JvmField
        var DEFAULT_CITY_ID: String = ChelaileClient.DEFAULT_CITY_ID

        const val DEFAULT_CITY_NAME: String = "唐山"

        /** 一圈最多解出多少站的坐标。 */
        private const val RESOLVE_MAX = 20

        /** 一轮最多放几站上屏。 */
        private const val PAGE_SIZE = 12

        /** 站点坐标缓存：环形探针一轮会重复探到同一批站，坐标不会变，存下来省请求。 */
        private val COORD_CACHE: MutableMap<String, DoubleArray> = ConcurrentHashMap<String, DoubleArray>()

        /** 当前数据源。 */
        @JvmStatic
        fun source(): Int {
            return src
        }

        /** 当前是不是掌上公交。 */
        @JvmStatic
        fun ml(): Boolean {
            return src == SRC_MYGOLBS
        }

        /** 数据源的中文名（顶栏按钮、提示用）。 */
        @JvmStatic
        fun sourceName(): String {
            return if (ml()) "掌上公交" else "车来了"
        }

        /** 切换数据源（只切内存态；持久化在 [BusStore.setSource]）。 */
        @JvmStatic
        fun setSource(s0: Int) {
            var s = s0
            if (s != SRC_CHELAILE && s != SRC_MYGOLBS) s = SRC_CHELAILE
            if (s == src) return
            src = s
            DEFAULT_CITY_ID = if (ml()) MygolbsClient.DEFAULT_CITY else ChelaileClient.DEFAULT_CITY_ID
            // 两套线路的站表缓存互不相通，整批作废
            MygolbsClient.clearCache()
        }

        // ──────────────────────────── 对外接口 ────────────────────────────

        /** 支持实时公交的城市。失败抛异常，由调用方兜底成「当前城市」。 */
        @JvmStatic
        @Throws(Exception::class)
        fun cities(): List<BusModels.City> {
            return if (ml()) MygolbsClient.cities() else ChelaileClient.cities()
        }

        /** 附近站点（带每条线的到站）。limit 控制返回条数。 */
        @JvmStatic
        @Throws(Exception::class)
        fun nearby(cityId: String, lat: Double, lng: Double, limit: Int): List<BusModels.Station> {
            return if (ml()) MygolbsClient.nearby(cityId, lat, lng, limit)
            else ChelaileClient.nearby(cityId, lat, lng, limit)
        }

        /**
         * 站点详情：一个站停靠的全部线路与到站。
         *
         * 掌上公交按站名查（`CMD=105`），站名在 `stationId` 里
         * （形如 `mg|唐山市|世纪花园站`），其余三个参数是为了兼容旧调用方保留的。
         */
        @JvmStatic
        @Throws(Exception::class)
        fun stopDetail(
            cityId: String, physicalId: String?, namesakeId: String?,
            stationId: String?, lat: Double, lng: Double
        ): BusModels.Station {
            if (!ml()) return ChelaileClient.stopDetail(cityId, physicalId, namesakeId, stationId, lat, lng)
            val out = MygolbsClient.stopDetail(cityId, stationId)
            // 站点自己的坐标上游这里不给，别拿用户位置冒充，否则回填列表时距离会变成 0
            out.lat = 0.0
            out.lng = 0.0
            return out
        }

        /**
         * 关键字搜索：站点 + 线路一起搜（中文站名、线路号如「17」「17路」都行）。
         *
         * 掌上公交的线路搜索一次就回全量，所以 [searchMore] 没有更多可取，
         * 界面下滑翻页靠本地缓存分屏。
         */
        @JvmStatic
        @Throws(Exception::class)
        fun search(cityId: String, keyword: String, lat: Double, lng: Double): BusModels.SearchHit {
            if (!ml()) return ChelaileClient.search(cityId, keyword, lat, lng)
            val hit = BusModels.SearchHit()
            try {
                hit.stations.addAll(MygolbsClient.searchStops(cityId, keyword))
            } catch (ignored: Exception) {
                // 站点搜不到不该连累线路结果
            }
            hit.lines.addAll(MygolbsClient.searchLines(cityId, keyword))
            hit.stationCount = hit.stations.size
            hit.lineCount = hit.lines.size
            return hit
        }

        /** 线路搜索：掌上公交一次给全量没有「更多」；车来了走翻页。 */
        @JvmStatic
        @Throws(Exception::class)
        fun searchMore(cityId: String, keyword: String, lat: Double, lng: Double): List<BusModels.Line> {
            if (!ml()) return ChelaileClient.searchMore(cityId, keyword, lat, lng)
            return ArrayList()
        }

        /**
         * 线路详情：站表 + 在途车辆。
         *
         * @param targetOrder 候车站的站序（1 基），>0 时用它问「车到这个站还有多久」
         * @param stationId   站点的 sId；有它时优先按 id 认站（同名站不会认错）
         */
        @JvmStatic
        @Throws(Exception::class)
        fun lineDetail(
            cityId: String, lineId: String, targetOrder: Int,
            stationId: String?, lat: Double, lng: Double
        ): BusModels.LineDetail {
            if (!ml()) return ChelaileClient.lineDetail(cityId, lineId, targetOrder, stationId, lat, lng)
            val ref = MygolbsClient.parseLineId(lineId)
            if (ref == null) throw IOException("这条线路来自旧的数据源，请重新搜索")
            val city = ref[0]
            val lineName = ref[1]
            val dir = if ("1" == ref[2]) 0 else 1

            val route = MygolbsClient.route(city, lineName, dir)
            if (route.stops.isEmpty()) throw IOException("这条线路暂无站表数据")

            val d = BusModels.LineDetail()
            d.line.lineId = lineId
            d.line.name = lineName
            d.line.lineNo = lineName
            d.line.direction = dir
            d.line.startName = route.stops[0].name
            d.line.endName = route.stops[route.stops.size - 1].name
            d.line.firstTime = route.firstTime
            d.line.lastTime = route.lastTime
            d.line.price = route.price
            d.line.stationsNum = route.stops.size

            // 缓存里的站对象要复制一份：下面会给 busRatio / nearest 赋值，不能改缓存
            for (srcStop in route.stops) {
                val stop = BusModels.Stop()
                stop.id = srcStop.id
                stop.physicalId = srcStop.physicalId
                stop.name = srcStop.name
                stop.order = srcStop.order
                stop.orderLabel = srcStop.orderLabel
                stop.lat = srcStop.lat
                stop.lng = srcStop.lng
                d.stops.add(stop)
            }

            d.reverseId = MygolbsClient.lineId(city, lineName, 1 - dir)
            d.reverseStart = d.line.endName
            d.reverseEnd = d.line.startName

            // 在途车辆：104 的 index 是 0 起，车正开往第 index+1 站
            val ask = if (targetOrder > 0) targetOrder else d.stops.size
            val rt = MygolbsClient.realtime(city, lineName, dir, ask)
            d.busCount = rt.buses.size
            for (b in rt.buses) {
                val order = b.index + 1
                if (order <= 0) continue
                val cur = stopByOrder(d.stops, order) ?: continue
                val prev = stopByOrder(d.stops, order - 1)
                var ratio = 1.0
                if (prev != null && b.lat != 0.0 && b.lng != 0.0
                    && prev.lat != 0.0 && prev.lng != 0.0 && cur.lat != 0.0 && cur.lng != 0.0
                ) {
                    val seg = distanceMeters(prev.lat, prev.lng, cur.lat, cur.lng)
                    val toPrev = distanceMeters(prev.lat, prev.lng, b.lat, b.lng)
                    if (seg > 1) ratio = Math.max(0.0, Math.min(1.0, toPrev / seg))
                }
                // 同一段可能有两辆车，取最靠前的那辆画
                if (ratio > cur.busRatio) cur.busRatio = ratio
            }
            for (s in d.stops) {
                if (s.busRatio >= 0) s.busHere = s.busRatio >= 0.9
            }
            // 逐车卡片：站表上的 busRatio 只是「哪段有车」，车辆列表要另一套字段
            MygolbsClient.fillVehicles(d, rt)

            d.status = statusOf(rt, route, 0)
            d.nextDeparture = rt.planTime

            // ── 候车站：进来时指定的那站，没指定就用离用户最近的那站 ──
            var wait: BusModels.Stop? = null
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
            // 用户点了别的站，最近站的标注也不该跟着跑
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
                // 状态跟候车站有关：车已经过了这一站才算「末班已过」，不能拿整条线有没有车来判
                d.status = statusOf(rt, route, wait.order)
                fillEta(d, cityId, lineId, wait, lat, lng)
            }
            return d
        }

        /** 拉一次到「候车站」的车辆预测，填分钟 / 站数 / 距离。失败不影响站表。 */
        private fun fillEta(
            d: BusModels.LineDetail, cityId: String, lineId: String,
            wait: BusModels.Stop, lat: Double, lng: Double
        ) {
            val pr = predict(cityId, lineId, wait.order, wait.id, lat, lng) ?: return
            if (pr.secs >= 0) {
                d.etaMinutes = BusText.minutesOfSecs(pr.secs)
                d.etaStops = pr.stops
                d.etaDistanceMeters = pr.dist
                // 小字（车牌 / 距离 / 时刻 / 时速）必须读大字那辆车，见 BusModels.displayVehicle
                d.etaBusId = pr.busId
                d.etaNextBusId = pr.nextBusId
            }
            d.etaArrived = pr.arrived
            if (d.status.isEmpty()) {
                d.status = pr.depDesc
                if (d.nextDeparture.isEmpty()) d.nextDeparture = pr.preArrival
            }
        }

        // ───────────────────── 首站发车时刻表 ─────────────────────

        /** 首站发车时刻表：掌上公交走 `CMD=207`，车来了走 `preStartTimetableNew`。 */
        @JvmStatic
        @Throws(Exception::class)
        fun timetable(cityId: String, lineId: String, direction: Int, lineNo: String?): BusModels.Timetable {
            if (!ml()) return ChelaileClient.timetable(cityId, lineId, direction, lineNo)
            val ref = MygolbsClient.parseLineId(lineId)
            if (ref == null) throw IOException("这条线路来自旧的数据源，请重新搜索")
            val dir = if ("1" == ref[2]) 0 else 1
            return MygolbsClient.timetable(ref[0], ref[1], dir)
        }

        /**
         * 某条线的车到指定站还有多久。
         *
         * 首页和线路详情共用这一个口径 —— 都按站序问 `CMD=104`，
         * 上游直接给「还有几站 / 还有几分钟 / 还有多远」，不做二次折算。
         */
        @JvmStatic
        fun predict(cityId: String, lineId: String, order: Int, stopId: String?,
                    lat: Double, lng: Double): Pred? {
            if (!ml()) {
                val p = ChelaileClient.predict(cityId, lineId, order, stopId, lat, lng) ?: return null
                val out = Pred()
                out.secs = p.secs
                out.stops = p.stops
                out.dist = p.dist
                out.arrived = p.arrived
                out.busId = p.busId
                out.depDesc = p.depDesc
                out.preArrival = p.preArrival
                out.nextSecs = p.nextSecs
                out.nextStops = p.nextStops
                out.nextBusId = p.nextBusId
                return out
            }
            try {
                val ref = MygolbsClient.parseLineId(lineId) ?: return null
                val dir = if ("1" == ref[2]) 0 else 1
                val rt = MygolbsClient.realtime(ref[0], ref[1], dir, order)

                val out = Pred()
                out.preArrival = rt.planTime
                val e = rt.first()
                if (e != null && e.minutes >= 0) {
                    out.secs = e.minutes * 60
                    out.stops = e.count
                    out.dist = e.distance
                    out.busId = e.busNumber
                    // 有一辆车在站上就算到站（多辆车时 0 分钟会把它排最前）
                    for (x in rt.etas) {
                        if (x.arrived) {
                            out.arrived = true
                            // 大字说「到站了」时小字要读同一辆，别拿排第一的那辆顶替
                            if (x.busNumber.isNotEmpty()) out.busId = x.busNumber
                            break
                        }
                    }
                } else {
                    out.secs = -1
                    out.depDesc = shortStatusOf(rt)
                }
                // 掌上公交 104 的 etas 已按分钟升序排好，第二项就是「下辆」；
                // 同样不拿别处的数字来凑，没给就不写
                if (rt.etas.size > 1) {
                    val s2 = rt.etas[1]
                    if (s2.minutes >= 0) {
                        out.nextSecs = s2.minutes * 60
                        out.nextStops = s2.count
                        out.nextBusId = s2.busNumber
                    }
                }
                return out
            } catch (ignored: Exception) {
                // 预测是加分项，失败就只显示站表
                return null
            }
        }

        /** 线路级的运营状态：停运 / 未开通实时 / 非运营时间 / 等待发车 / 末班已过。 */
        private fun statusOf(rt: MygolbsClient.Realtime, route: MygolbsClient.RouteInfo,
                             waitOrder: Int): String {
            if (rt.runState == 1) return "该线路已停运"
            if (!rt.hasReal) return "该线路未开通实时数据查询"
            // 只有「还有车没开过候车站」才不写状态；车全过站了才按首末班判
            var hasUpcoming = waitOrder <= 0
            for (b in rt.buses) {
                if (b.index < 0) continue
                if (waitOrder <= 0 || b.index + 1 <= waitOrder) {
                    hasUpcoming = true
                    break
                }
            }
            if (hasUpcoming) return ""
            // 官方 104 明说的状态（如「非运营时间」）优先于按首末班推出来的文案，
            // 这样点进线路详情和首页补预测后显示的是同一句官方原话
            if (rt.statusTips.isNotEmpty()) return rt.statusTips
            val now = BusModels.minutesOf(MygolbsClient.nowHHMM())
            val first = BusModels.minutesOf(route.firstTime)
            val last = BusModels.minutesOf(route.lastTime)
            if (first >= 0 && now < first) return "等待发车"
            if (last >= 0 && now > last) return "末班已过"
            return ""
        }

        /** 没有车时的那句短文案。 */
        private fun shortStatusOf(rt: MygolbsClient.Realtime): String {
            if (rt.runState == 1) return "该线路已停运"
            if (!rt.hasReal) return "未开通实时"
            if (rt.statusTips.isNotEmpty()) return rt.statusTips
            if (rt.planTime.isNotEmpty()) return "下趟 " + rt.planTime + " 发车"
            return ""
        }

        /** 一条到站行有没有实时数据（任一线路有就算有）。 */
        @JvmStatic
        fun hasRealtime(s: BusModels.Station): Boolean {
            for (a in s.arrivals) {
                if (a.realtime) return true
            }
            return false
        }

        // ──────────────────────── 补预测（到站分钟） ────────────────────────

        /**
         * 把一批「正显示在屏幕上」的到站行整体重算一遍。
         *
         * 站点的线路接口（`CMD=105`）只给「7站」「即将到站」这类文案，
         * 没有秒数；分钟数要靠 `CMD=104` 按站序问。两处统一走 [predict]，
         * 首页和点进去看到的数才对得上。
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
            // 车来了那套有自己的补预测编排（walk 进出站、按 sId 认站），直接用它的
            if (!ml()) {
                val w = watcher
                ChelaileClient.refreshEta(cityId, shown, lat, lng, limit, force,
                    if (w == null) null else ChelaileClient.EtaWatcher { a -> w.onEta(a) })
                return
            }
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
                                // 和线路详情 / 车来了链路同一步换算：1..59 秒写 0（即将到站）
                                a.minutes = BusText.minutesOfSecs(pr.secs)
                                a.stopsAway = pr.stops
                                a.distanceMeters = pr.dist
                                a.realtime = true
                                // 「已到站」只认上游明说；0 分钟是「即将到站」，别混
                                if (pr.arrived) a.arrived = true
                                // 「下辆」与大字来自同一次预测，上游没给第二辆就清空
                                a.nextMinutes = BusText.minutesOfSecs(pr.nextSecs)
                                a.nextStopsAway = if (pr.nextSecs >= 0) pr.nextStops else -1
                            } else if (pr != null && pr.depDesc.isNotEmpty()) {
                                // 补不到分钟数，但官方给了线路状态（如「非运营时间」）：
                                // 覆掉首页 105 给的那句，让首页和点进详情看到同一句话
                                a.status = pr.depDesc
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

        private fun stopByOrder(stops: List<BusModels.Stop>, order: Int): BusModels.Stop? {
            for (s in stops) {
                if (s.order == order) return s
            }
            return null
        }

        // ──────────────────── 首页下滑：往外扩一圈找更远的站 ────────────────────

        @JvmStatic
        fun fartherStations(cityId: String, lat: Double, lng: Double,
                            ring: Int, exclude: Set<String>?): List<BusModels.Station> {
            // 车来了那套要逐站解坐标（106 不带坐标），编排完全不同，直接用它的
            if (!ml()) return ChelaileClient.fartherStations(cityId, lat, lng, ring, exclude)
            val dirs = 8
            val radius = 700.0 * Math.max(1, ring)
            val bucket: MutableList<BusModels.Station> = Collections.synchronizedList(ArrayList<BusModels.Station>())
            val probes = ArrayList<Thread>(dirs)
            for (i in 0 until dirs) {
                val bearing = i * 360.0 / dirs
                val t = Thread({
                    try {
                        val p = offsetPoint(lat, lng, radius, bearing)
                        // 这里只要站点和坐标：8 个方位 × 每站再查一次线路就是上百次请求，
                        // 上游会直接掐连接。线路等筛出这一屏之后再补。
                        bucket.addAll(MygolbsClient.probeStops(cityId, p[0], p[1], 15))
                    } catch (ignored: Exception) {
                    }
                }, "bus-probe-$i")
                probes.add(t)
                t.start()
            }
            for (t in probes) {
                try {
                    t.join()
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

            // 探到的站已经带坐标（106 直接给 lat/lon），缓存只是省得下一圈重算距离
            var keep = ArrayList<BusModels.Station>()
            for (s in uniq.values) {
                if (s.lat == 0.0 || s.lng == 0.0) {
                    val c = COORD_CACHE[s.id]
                    if (c == null) continue      // 没有坐标就不硬塞，免得写个假距离
                    s.lat = c[0]
                    s.lng = c[1]
                }
                if (COORD_CACHE.size > 600) COORD_CACHE.clear()
                COORD_CACHE[s.id] = doubleArrayOf(s.lat, s.lng)
                s.distanceMeters = Math.round(distanceMeters(lat, lng, s.lat, s.lng)).toInt()
                keep.add(s)
            }
            Collections.sort(keep, Comparator<BusModels.Station> { a, b ->
                a.distanceMeters.compareTo(b.distanceMeters)
            })
            if (keep.size > PAGE_SIZE) keep = ArrayList(keep.subList(0, PAGE_SIZE))
            if (keep.size > RESOLVE_MAX) keep = ArrayList(keep.subList(0, RESOLVE_MAX))

            // 这一屏才补线路（12 个请求，4 并发）
            MygolbsClient.fillLines(cityId, keep, lat, lng)
            return keep
        }

        /**
         * 批量拉站点详情，只往回带数据、不动传进来的对象。
         *
         * 首页要先把附近站点立刻画出来，详情这批稍后才回来，
         * 所以不在工作线程里改 `Station`：那边还在读，这边改 arrivals 会撞出并发问题。
         * 取回来交给 UI 线程合并。
         *
         * @return stationId → 详情
         */
        @JvmStatic
        fun stationDetails(cityId: String, list: List<BusModels.Station>,
                           lat: Double, lng: Double): MutableMap<String, BusModels.Station> {
            if (!ml()) return ChelaileClient.stationDetails(cityId, list, lat, lng)
            val out: MutableMap<String, BusModels.Station> = ConcurrentHashMap<String, BusModels.Station>()
            val todo = ArrayList<BusModels.Station>()
            for (s in list) {
                // 掌上公交的附近站点接口已经带回了停靠线路，这里只补「还没有线路」的站，
                // 否则首页刚拉完 8 个站又整批重拉一遍，白等一秒多
                if (s.arrivals.isEmpty()) todo.add(s)
            }
            if (todo.isEmpty()) return out
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
            // 注意是**合并**不是替换：详情里没有坐标和距离，直接覆盖会把列表上算好的位置弄丢
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
