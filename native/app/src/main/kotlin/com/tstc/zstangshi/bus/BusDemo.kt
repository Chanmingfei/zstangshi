package com.tstc.zstangshi.bus

/**
 * 临时演示线路：**只为了测试界面**，数据全是编的，不联网、不读缓存。
 *
 * 为什么要它：真实线路上不一定同时出现「多辆车在途 + 有车已过本站 + 有车正在进站」
 * 这些状态，想验「全部车辆 / 单车跟随 / 到达大字」就得碰运气。
 *
 * 用法：`BusActivity` 首页顶部那张「测试」卡片点进来，lineId 是 [ID]；
 * 换向会走 [ID_REVERSE]，两边都能开。
 *
 * 下线方式：把 [SHOW_ENTRY] 改 false（入口卡片消失），
 * 或者连 `BusClient` 里那两行 `isDemo` 分支一起删掉，再删本文件。
 */
object BusDemo {

    /** 首页是否显示「测试」入口卡片。 */
    const val SHOW_ENTRY = true

    const val ID = "demo-1"
    const val ID_REVERSE = "demo-1r"

    /** 从首页点进来时默认候车在第几站（前面 6 辆放得下）。 */
    const val DEFAULT_ORDER = 8

    private const val LINE_NAME = "游1路"

    private val STOPS = arrayOf(
        "唐山站", "站前路", "光明南里", "西山道口", "国防道口", "学院路口",
        "裕华道口", "建设路北口", "唐山百货大楼", "广场", "大钊公园", "抗震纪念碑广场",
        "新华道口", "文化路口", "南新道口", "复兴路口", "友谊路口", "学院南路",
        "唐胥路口", "南湖公园西门", "凤凰台", "丹凤朝阳", "南湖公园东门", "南湖公园"
    )

    /** 每辆车的静态部分：站序、车牌、时速。ETA/距离按候车站现算。 */
    private class Seed(val order: Int, val plate: String, val speed: Double)

    private val SEEDS = arrayOf(
        Seed(2, "冀B·D2357", 24.0),
        Seed(3, "冀B·D4415", 31.0),
        Seed(5, "冀B·D9021", 18.0),
        Seed(6, "冀B·D7788", 42.0),
        Seed(7, "冀B·D8866", 27.0),
        Seed(10, "冀B·D5120", 35.0)
    )

    @JvmStatic
    fun isDemo(lineId: String): Boolean = lineId == ID || lineId == ID_REVERSE

    /** 首页入口卡片上那行副标题用。 */
    @JvmStatic
    fun summary(): String = "$LINE_NAME · 唐山站 → 南湖公园 · ${SEEDS.size} 辆车在途"

    // ──────────────────────────── 线路详情 ────────────────────────────

    @JvmStatic
    fun detail(lineId: String, targetOrder: Int, nowMillis: Long): BusModels.LineDetail {
        val reverse = lineId == ID_REVERSE
        val d = BusModels.LineDetail()

        d.line.lineId = lineId
        d.line.name = LINE_NAME
        d.line.lineNo = LINE_NAME
        d.line.direction = if (reverse) 1 else 0
        d.line.startName = if (reverse) STOPS[STOPS.size - 1] else STOPS[0]
        d.line.endName = if (reverse) STOPS[0] else STOPS[STOPS.size - 1]
        d.line.firstTime = "05:40"
        d.line.lastTime = "21:00"
        d.line.price = "1元"
        d.line.stationsNum = STOPS.size
        // 换向两边都能开
        d.reverseId = if (reverse) ID else ID_REVERSE
        d.reverseStart = d.line.endName
        d.reverseEnd = d.line.startName

        val n = STOPS.size
        val order = if (targetOrder in 1..n) targetOrder else DEFAULT_ORDER

        for (i in 0 until n) {
            val s = BusModels.Stop()
            val seq = i + 1
            val name = if (reverse) STOPS[n - 1 - i] else STOPS[i]
            s.id = "demo-s$seq"
            // 演示站没有真实站点详情，留空——点了只会弹一句「附带小站」，不会去联网报错
            s.physicalId = ""
            s.name = name
            s.order = seq
            s.orderLabel = seq.toString()
            s.nearest = seq == order
            d.stops.add(s)
        }

        // 车按站序走：每 3 秒在一段里往前挪一点，刷新（20 秒一轮）时看得出在动
        val phase = ((nowMillis / 3000L) % 10L).toDouble() / 10.0

        var nearestOrder = -1
        var nearestEta = -1
        var nearestStops = -1
        var nearestArrived = false
        for (seed in SEEDS) {
            val v = BusModels.Vehicle()
            v.id = "demo-bus-" + seed.plate
            v.plate = seed.plate
            v.order = seed.order
            v.speedKmh = seed.speed
            v.nextStopName = if (seed.order in 1 until n) d.stops[seed.order].name else d.line.endName
            val left = order - seed.order
            if (left >= 0) {
                // 到候车站还有几站 → 按每站约 115 秒估
                v.etaSeconds = Math.max(45, left * 115 + 40)
                v.distanceMeters = left * 620 + 180
                if (left == 0) {
                    v.isArrived = true
                    v.etaSeconds = 0
                    v.distanceMeters = 30
                }
            } else {
                v.etaSeconds = -1
                v.distanceMeters = -1
            }
            d.vehicles.add(v)

            // 时间轴上标出车的位置：车「正开往」的那一站挂一个比例
            if (seed.order in 1..n) d.stops[seed.order - 1].busRatio = phase

            // 候车站就看「还没开过去、且站序最大」的那辆
            if (left >= 0 && seed.order > nearestOrder) {
                nearestOrder = seed.order
                nearestStops = left
                nearestEta = v.etaSeconds
                nearestArrived = v.isArrived
            }
        }
        d.busCount = d.vehicles.size

        // 候车站与大字
        d.waitingOrder = order
        d.waitingStopName = d.stops[order - 1].name
        d.waitingStopId = d.stops[order - 1].id
        d.etaStops = nearestStops
        d.etaMinutes = if (nearestEta >= 0) Math.max(0, Math.round(nearestEta / 60.0).toInt()) else -1
        d.etaArrived = nearestArrived
        d.etaDistanceMeters = if (nearestStops >= 0) nearestStops * 620 + 180 else -1
        return d
    }

    // ──────────────────────────── 首站发车时刻表 ────────────────────────────

    @JvmStatic
    fun timetable(lineId: String): BusModels.Timetable {
        val reverse = lineId == ID_REVERSE
        val t = BusModels.Timetable()
        t.startName = if (reverse) STOPS[STOPS.size - 1] else STOPS[0]
        t.endName = if (reverse) STOPS[0] else STOPS[STOPS.size - 1]
        t.depTitle = "首站发车"
        t.interval = 15
        t.firstDep = "05:40"
        t.lastDep = "21:00"

        val seg = BusModels.TtSegment()
        seg.from = "06:00"
        seg.to = "20:45"
        seg.interval = 15
        var h = 6
        while (h <= 20) {
            var m = 0
            while (m < 60) {
                seg.times.add(String.format(java.util.Locale.US, "%02d:%02d", h, m))
                m += 15
            }
            h++
        }
        t.history.add(seg)
        return t
    }
}
