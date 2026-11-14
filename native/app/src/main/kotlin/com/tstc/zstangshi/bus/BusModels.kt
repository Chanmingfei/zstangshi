package com.tstc.zstangshi.bus

import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.Locale

/**
 * 实时公交的领域模型。
 *
 * 命名与字段口径沿用 `Trackr`（MIT）的 core/model，但去掉追踪服务、
 * 换乘、地图坐标投影等本项目用不到的字段 —— 这里只服务「二级页看下一班还有几分钟」。
 *
 * 全部写成静态嵌套类：这套 d8 遇到带 this$0 的非静态内部类会内部错误（见工程日志）。
 */
class BusModels private constructor() {

    /** 支持实时公交的城市。cityId 是车来了的城市编号（唐山 = 056）。 */
    class City(id: String, name: String) {
        @JvmField val id: String = id
        @JvmField val name: String = name
    }

    /**
     * 一条线路在某个站点的当前到站情况。
     * minutes < 0 表示上游没给预测（停运 / 还没发车 / 数据缺失），界面上不显示分钟。
     */
    class Arrival {
        @JvmField var lineId: String = ""
        @JvmField var lineName: String = ""
        @JvmField var direction: Int = 0
        @JvmField var startName: String = ""
        @JvmField var endName: String = ""

        /**
         * 这条线路在「当前这个站」上排第几站（上游 targetStation.order）。
         * 用户从某个站点点进线路详情时，它就是默认候车站 —— 用户想看的是
         * 「车到我刚看的这一站还要多久」，而不是「车到离我最近的那一站还要多久」。
         */
        @JvmField var orderInLine: Int = 0

        /** 这个站的 sId。虚拟站组共用 order=999，认站得靠它。 */
        @JvmField var stopId: String = ""

        /** 还有几分钟，向上取整；-1 = 无预测 */
        @JvmField var minutes: Int = -1

        /** 还有几站；-1 = 未知 */
        @JvmField var stopsAway: Int = -1

        /** 距离本站还有多少米；-1 = 未知 */
        @JvmField var distanceMeters: Int = -1

        /** 第二辆车（车来了首页那个「下辆」） */
        @JvmField var nextMinutes: Int = -1
        @JvmField var nextStopsAway: Int = -1

        /** 已进站 / 即将到站 */
        @JvmField var arrived: Boolean = false

        /** 上游给的状态文案（如「等待发车」「末班已过」） */
        @JvmField var status: String = ""

        /** 上游这一站这一趟到底有没有给实时数据（远端站点可能压根没算） */
        @JvmField var realtime: Boolean = false

        /**
         * 还在补预测。nearSts 只给最近几站算实时，其余的我们会按条补一次
         * （和线路详情同一个接口）。补的过程中显示「…」，别先写「暂无实时」
         * 再跳成「6分钟」，那样像在骗人。
         */
        @JvmField var pending: Boolean = false

        /**
         * 已经按线路详情那个接口算过至少一次。补过的行再刷新时就不要重新挂「…」了，
         * 否则每 20 秒「暂无预测」和「…」来回闪一下。
         */
        @JvmField var refreshed: Boolean = false

        /** 界面上那句短文案：已到站 / 即将到站 / 3 分钟 / 暂无预测 / 暂无车 */
        fun label(): String {
            if (pending) return "…"
            if (arrived) return "已到站"
            if (minutes >= 0) return if (minutes <= 0) "即将到站" else "$minutes 分钟"
            if (status.isNotEmpty()) return status
            // 知道还剩几站、但上游没给秒数：别写「暂无车」，那和左边的「还有 N 站」自相矛盾
            if (stopsAway > 0) return "暂无预测"
            if (!realtime) return "暂无实时"
            return "暂无车"
        }

        /** 车来了风格的「23分钟·14站」；没有分钟就只给站数。 */
        fun etaCompact(): String {
            if (pending) return "…"
            if (arrived) return "已到站"
            // 官方口径：小于 1 分钟是「即将到站」，不是「已到站」
            if (minutes == 0) return "即将到站"
            if (minutes > 0 && stopsAway > 0) return BusModels.minutesText(minutes) + "·" + stopsAway + "站"
            if (minutes > 0) return BusModels.minutesText(minutes)
            if (status.isNotEmpty()) return status
            if (stopsAway > 0) return "${stopsAway}站外"
            if (!realtime) return "暂无实时"
            return "暂无车"
        }

        /** 「下辆 46分钟 25站」，没有第二辆返回空串。 */
        fun nextLabel(): String {
            if (nextMinutes < 0 && nextStopsAway < 0) return ""
            val sb = StringBuilder("下辆 ")
            if (nextMinutes == 0) sb.append("即将到站")
            else if (nextMinutes > 0) sb.append(nextMinutes).append("分钟")
            if (nextStopsAway > 0) {
                if (nextMinutes >= 0) sb.append(' ')
                sb.append(nextStopsAway).append("站")
            }
            return sb.toString()
        }

        /** 终点站，用来区分同号线路的两个方向 */
        fun destination(): String {
            return if (endName == null) "" else endName
        }
    }

    /** 附近站点 / 搜索结果里的站点。 */
    class Station {
        @JvmField var id: String = ""
        @JvmField var physicalId: String = ""
        @JvmField var namesakeId: String = ""
        @JvmField var name: String = ""
        @JvmField var distanceMeters: Int = -1
        @JvmField var lat: Double = 0.0
        @JvmField var lng: Double = 0.0
        @JvmField val arrivals: MutableList<Arrival> = ArrayList()

        /**
         * 同名站的不同物理站台。一次请求只能拿到被请求站台的完整到站数据，
         * 其余站台只回空占位 —— 所以界面要提供站台切换，不能简单合并。
         */
        @JvmField val platforms: MutableList<Platform> = ArrayList()

        fun distanceLabel(): String {
            if (distanceMeters < 0) return ""
            if (distanceMeters < 1000) return "$distanceMeters m"
            return String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
        }
    }

    /** 物理站台。 */
    class Platform(id: String, name: String) {
        @JvmField val id: String = id
        @JvmField val name: String = name
    }

    /** 线路概要（搜索结果、站点详情里的每一行）。 */
    class Line {
        @JvmField var lineId: String = ""
        @JvmField var name: String = ""

        /** 上游的短编号（r93552 这种），时刻表接口要它 */
        @JvmField var lineNo: String = ""
        @JvmField var direction: Int = 0
        @JvmField var startName: String = ""
        @JvmField var endName: String = ""
        @JvmField var firstTime: String = ""
        @JvmField var lastTime: String = ""
        @JvmField var price: String = ""
        @JvmField var stationsNum: Int = 0

        /** 站点详情页带上这一站的到站情况 */
        @JvmField val arrivals: MutableList<Arrival> = ArrayList()

        fun terminusLabel(): String {
            if (startName.isEmpty() && endName.isEmpty()) return ""
            return "$startName → $endName"
        }
    }

    /**
     * 一次搜索的结果：站点在前、线路在后。
     *
     * 上游一次只回前 3 个站点和前 10 条线路（正反方向各算一条），但会告诉我们
     * 实际命中多少（[stationCount] / [lineCount]）——线路可以在用户继续
     * 下滑时用 `more=true` 再要全量，站点没有再多的入口，所以界面上要如实写出
     * 「命中 N 个，只显示前 3 个」而不是假装全给了。
     */
    class SearchHit {
        @JvmField val stations: MutableList<Station> = ArrayList()
        @JvmField val lines: MutableList<Line> = ArrayList()

        /** 上游报的命中总数；0 表示它没给这个字段 */
        @JvmField var lineCount: Int = 0
        @JvmField var stationCount: Int = 0

        fun empty(): Boolean {
            return stations.isEmpty() && lines.isEmpty()
        }

        /** 站点是不是被截断了（上游只回前 3 个）。 */
        fun stationsTruncated(): Boolean {
            return stationCount > stations.size
        }

        /** 线路是不是被截断了（正反方向已合并成一行，所以直接跟命中数比）。 */
        fun linesTruncated(): Boolean {
            return lineCount > lines.size
        }
    }

    /** 线路详情里的一个站。 */
    class Stop {
        @JvmField var id: String = ""
        @JvmField var physicalId: String = ""
        @JvmField var name: String = ""
        @JvmField var order: Int = 0

        /**
         * 站序的显示文案。上游有「虚拟站组」——挂在某一站下面的几个小站，
         * sId 写成 `999$30$1`、order 一律给 999。直接显示就变成一排 999，
         * 所以这里还原成 `30-1`；普通站就是 order 本身。
         */
        @JvmField var orderLabel: String = ""
        @JvmField var lat: Double = 0.0
        @JvmField var lng: Double = 0.0

        /** 当前有车在这一站（用于时间轴上标出车辆位置） */
        @JvmField var busHere: Boolean = false

        /**
         * 车辆在「上一站 → 本站」这段上的位置比例：0 = 刚过上一站，1 = 已到本站；
         * 负数表示这段没车。车在路上就标在两站中间，不硬贴到站上。
         */
        @JvmField var busRatio: Double = -1.0

        /** 离用户最近的那一站 */
        @JvmField var nearest: Boolean = false

        /** 点站名进站点详情时需要的对象。 */
        fun toStation(): Station {
            val s = Station()
            s.id = id
            s.physicalId = physicalId
            s.name = name
            s.lat = lat
            s.lng = lng
            return s
        }
    }

    /** 线路详情：站表 + 在途车辆 + 反向线路 + 到候车站的预测。 */
    class LineDetail {
        @JvmField var line: Line = Line()

        /** 反向线路 id；空 = 没有对向（单程 / 环线） */
        @JvmField var reverseId: String = ""
        @JvmField var reverseStart: String = ""
        @JvmField var reverseEnd: String = ""
        @JvmField val stops: MutableList<Stop> = ArrayList()

        /** 在途车辆数 */
        @JvmField var busCount: Int = 0

        /** 运营状态文案，如「等待发车」「末班已过」 */
        @JvmField var status: String = ""

        /** 下一班发车时刻（首站） */
        @JvmField var nextDeparture: String = ""

        /** 候车站（离用户最近的站）与它的到站预测 */
        @JvmField var waitingStopName: String = ""
        @JvmField var waitingOrder: Int = 0

        /** 候车站的 sId。站序不一定唯一（虚拟站组共用 999），认站只能靠 id。 */
        @JvmField var waitingStopId: String = ""
        @JvmField var etaMinutes: Int = -1
        @JvmField var etaStops: Int = -1
        @JvmField var etaDistanceMeters: Int = -1

        /** 车真的在候车站上（上游明说「已到站」），优先级高于 etaMinutes==0 的「即将到站」 */
        @JvmField var etaArrived: Boolean = false

        /** 没车在途时上游估的到站时刻（车来了 preArrivalTime，形如 19:34），掌上公交没有这数据 */
        @JvmField var estArrival: String = ""

        fun reversible(): Boolean {
            return reverseId != null && reverseId.isNotEmpty()
        }

        /** 「小于1分钟·17站 / 8.8千米」，上游没有距离就只给站数。 */
        fun etaDetail(): String {
            val sb = StringBuilder()
            // 已到站就不写「小于1分钟」了，大字已经说明白
            if (etaMinutes == 0 && !etaArrived) sb.append("小于1分钟")
            if (etaStops > 0) {
                if (sb.length > 0) sb.append("·")
                sb.append(etaStops).append("站")
            }
            if (etaDistanceMeters > 0) {
                if (sb.length > 0) sb.append(" / ")
                if (etaDistanceMeters < 1000) sb.append(etaDistanceMeters).append("米")
                else sb.append(String.format(Locale.US, "%.1f千米", etaDistanceMeters / 1000.0))
            }
            return sb.toString()
        }
    }

    /** 首站发车时刻表：今日预测 + 昨日实际班次（上游 preStartTimetableNew）。 */
    class Timetable {
        @JvmField var lineName: String = ""
        @JvmField var startName: String = ""
        @JvmField var endName: String = ""

        /** 「首站发车预测」/「历史班次首站发车时间」 */
        @JvmField var depTitle: String = ""
        @JvmField var tip: String = ""

        /** 今日预测的分时段班次（只给还没发的） */
        @JvmField val today: MutableList<TtSegment> = ArrayList()

        /** 昨日实际跑出来的整日班次 */
        @JvmField val history: MutableList<TtSegment> = ArrayList()

        /** 下一班发车时刻 HH:mm；空 = 今天没班次了 */
        @JvmField var nextDep: String = ""

        /** 下一班是从哪来的：「今日预测」/「昨日实际」/「明日首班」 */
        @JvmField var nextFrom: String = ""
        @JvmField var firstDep: String = ""
        @JvmField var lastDep: String = ""

        /** 发车间隔（分钟），上游没给就是 -1 */
        @JvmField var interval: Int = -1

        fun todayTimes(): List<String> {
            val out = ArrayList<String>()
            for (s in today) out.addAll(s.times)
            BusModels.sortByClock(out)
            return out
        }

        fun historyTimes(): List<String> {
            val out = ArrayList<String>()
            for (s in history) out.addAll(s.times)
            BusModels.sortByClock(out)
            return out
        }

        fun empty(): Boolean {
            return today.isEmpty() && history.isEmpty()
        }
    }

    /** 时刻表里的一段时间：几点到几点、几分钟一班、具体班次。 */
    class TtSegment {
        @JvmField var from: String = ""
        @JvmField var to: String = ""
        @JvmField var tips: String = ""

        /** 平均发车间隔（分钟），-1 = 上游没给，只能按班次算 */
        @JvmField var interval: Int = -1
        @JvmField val times: MutableList<String> = ArrayList()

        /** 「06:24–10:00 · 约 21 分钟一班」，上游没给间隔就只写时段。 */
        fun label(): String {
            val sb = StringBuilder()
            if (from.isNotEmpty() || to.isNotEmpty()) sb.append(from).append("–").append(to)
            var iv = interval
            if (iv <= 0 && times.size > 1) iv = estimateInterval(times)
            if (iv > 0) {
                if (sb.length > 0) sb.append(" · ")
                sb.append("约 ").append(iv).append(" 分钟一班")
            }
            return sb.toString()
        }

        companion object {
            /** 用相邻班次的间隔中位数估一个，别让极端值带偏。 */
            private fun estimateInterval(times: List<String>): Int {
                val gaps = ArrayList<Int>()
                for (i in 1 until times.size) {
                    val a = BusModels.minutesOf(times[i - 1])
                    val b = BusModels.minutesOf(times[i])
                    if (a >= 0 && b > a) gaps.add(b - a)
                }
                if (gaps.isEmpty()) return -1
                Collections.sort(gaps)
                return gaps[gaps.size / 2]
            }
        }
    }

    companion object {

        /** 按钟点排序：字符串排序会把 "6:24" 排到 "10:00" 后面，得按分钟数比。 */
        @JvmStatic
        fun sortByClock(times: MutableList<String>) {
            Collections.sort(times, Comparator<String> { a, b -> minutesOf(a).compareTo(minutesOf(b)) })
        }

        /** 45 →「45分钟」；104 →「1小时44分」。城乡公交跑一趟动辄一个多小时，写「104分钟」读着费劲。 */
        @JvmStatic
        fun minutesText(m: Int): String {
            if (m < 60) return "${m}分钟"
            val h = m / 60
            val r = m % 60
            return if (r == 0) "${h}小时" else "${h}小时${r}分"
        }

        /** "07:43" → 463；不是钟点返回 -1。 */
        @JvmStatic
        fun minutesOf(hhmm: String?): Int {
            if (hhmm == null) return -1
            val v = hhmm.trim()
            val i = v.indexOf(':')
            if (i <= 0) return -1
            return try {
                val h = Integer.parseInt(v.substring(0, i))
                val m = Integer.parseInt(v.substring(i + 1).trim())
                if (h < 0 || h > 23 || m < 0 || m > 59) -1 else h * 60 + m
            } catch (e: Exception) {
                -1
            }
        }
    }
}
