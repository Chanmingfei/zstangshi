package com.tstc.zstangshi.bus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 实时公交的领域模型。
 *
 * 命名与字段口径沿用 {@code Trackr}（MIT）的 core/model，但去掉追踪服务、
 * 换乘、地图坐标投影等本项目用不到的字段 —— 这里只服务「二级页看下一班还有几分钟」。
 *
 * 全部写成静态嵌套类：这套 d8 遇到带 this$0 的非静态内部类会内部错误（见工程日志）。
 */
public final class BusModels {

    private BusModels() {
    }

    /** 支持实时公交的城市。cityId 是车来了的城市编号（唐山 = 056）。 */
    public static final class City {
        public final String id;
        public final String name;

        public City(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * 一条线路在某个站点的当前到站情况。
     * minutes < 0 表示上游没给预测（停运 / 还没发车 / 数据缺失），界面上不显示分钟。
     */
    public static final class Arrival {
        public String lineId = "";
        public String lineName = "";
        public int direction = 0;
        public String startName = "";
        public String endName = "";
        /**
         * 这条线路在「当前这个站」上排第几站（上游 targetStation.order）。
         * 用户从某个站点点进线路详情时，它就是默认候车站 —— 用户想看的是
         * 「车到我刚看的这一站还要多久」，而不是「车到离我最近的那一站还要多久」。
         */
        public int orderInLine = 0;
        /** 这个站的 sId。虚拟站组共用 order=999，认站得靠它。 */
        public String stopId = "";
        /** 还有几分钟，向上取整；-1 = 无预测 */
        public int minutes = -1;
        /** 还有几站；-1 = 未知 */
        public int stopsAway = -1;
        /** 距离本站还有多少米；-1 = 未知 */
        public int distanceMeters = -1;
        /** 第二辆车（车来了首页那个「下辆」） */
        public int nextMinutes = -1;
        public int nextStopsAway = -1;
        /** 已进站 / 即将到站 */
        public boolean arrived = false;
        /** 上游给的状态文案（如「等待发车」「末班已过」） */
        public String status = "";
        /** 上游这一站这一趟到底有没有给实时数据（远端站点可能压根没算） */
        public boolean realtime = false;
        /**
         * 还在补预测。nearSts 只给最近几站算实时，其余的我们会按条补一次
         * （和线路详情同一个接口）。补的过程中显示「…」，别先写「暂无实时」
         * 再跳成「6分钟」，那样像在骗人。
         */
        public boolean pending = false;
        /**
         * 已经按线路详情那个接口算过至少一次。补过的行再刷新时就不要重新挂「…」了，
         * 否则每 20 秒「暂无预测」和「…」来回闪一下。
         */
        public boolean refreshed = false;

        /** 界面上那句短文案：已到站 / 即将到站 / 3 分钟 / 暂无预测 / 暂无车 */
        public String label() {
            if (pending) return "…";
            if (arrived) return "已到站";
            if (minutes >= 0) return minutes <= 0 ? "即将到站" : minutes + " 分钟";
            if (!status.isEmpty()) return status;
            // 知道还剩几站、但上游没给秒数：别写「暂无车」，那和左边的「还有 N 站」自相矛盾
            if (stopsAway > 0) return "暂无预测";
            if (!realtime) return "暂无实时";
            return "暂无车";
        }

        /** 车来了风格的「23分钟·14站」；没有分钟就只给站数。 */
        public String etaCompact() {
            if (pending) return "…";
            if (arrived) return "已到站";
            // 官方口径：小于 1 分钟是「即将到站」，不是「已到站」
            if (minutes == 0) return "即将到站";
            if (minutes > 0 && stopsAway > 0) return minutesText(minutes) + "·" + stopsAway + "站";
            if (minutes > 0) return minutesText(minutes);
            if (!status.isEmpty()) return status;
            if (stopsAway > 0) return stopsAway + "站外";
            if (!realtime) return "暂无实时";
            return "暂无车";
        }

        /** 「下辆 46分钟 25站」，没有第二辆返回空串。 */
        public String nextLabel() {
            if (nextMinutes < 0 && nextStopsAway < 0) return "";
            StringBuilder sb = new StringBuilder("下辆 ");
            if (nextMinutes == 0) sb.append("即将到站");
            else if (nextMinutes > 0) sb.append(nextMinutes).append("分钟");
            if (nextStopsAway > 0) {
                if (nextMinutes >= 0) sb.append(' ');
                sb.append(nextStopsAway).append("站");
            }
            return sb.toString();
        }

        /** 终点站，用来区分同号线路的两个方向 */
        public String destination() {
            return endName == null ? "" : endName;
        }
    }

    /** 附近站点 / 搜索结果里的站点。 */
    public static final class Station {
        public String id = "";
        public String physicalId = "";
        public String namesakeId = "";
        public String name = "";
        public int distanceMeters = -1;
        public double lat;
        public double lng;
        public final List<Arrival> arrivals = new ArrayList<>();
        /**
         * 同名站的不同物理站台。一次请求只能拿到被请求站台的完整到站数据，
         * 其余站台只回空占位 —— 所以界面要提供站台切换，不能简单合并。
         */
        public final List<Platform> platforms = new ArrayList<>();

        public String distanceLabel() {
            if (distanceMeters < 0) return "";
            if (distanceMeters < 1000) return distanceMeters + " m";
            return String.format(java.util.Locale.US, "%.1f km", distanceMeters / 1000.0);
        }
    }

    /** 物理站台。 */
    public static final class Platform {
        public String id = "";
        public String name = "";

        public Platform(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** 线路概要（搜索结果、站点详情里的每一行）。 */
    public static final class Line {
        public String lineId = "";
        public String name = "";
        /** 上游的短编号（r93552 这种），时刻表接口要它 */
        public String lineNo = "";
        public int direction = 0;
        public String startName = "";
        public String endName = "";
        public String firstTime = "";
        public String lastTime = "";
        public String price = "";
        public int stationsNum = 0;
        /** 站点详情页带上这一站的到站情况 */
        public final List<Arrival> arrivals = new ArrayList<>();

        public String terminusLabel() {
            if (startName.isEmpty() && endName.isEmpty()) return "";
            return startName + " → " + endName;
        }
    }

    /**
     * 一次搜索的结果：站点在前、线路在后。
     *
     * <p>上游一次只回前 3 个站点和前 10 条线路（正反方向各算一条），但会告诉我们
     * 实际命中多少（{@link #stationCount} / {@link #lineCount}）——线路可以在用户继续
     * 下滑时用 {@code more=true} 再要全量，站点没有再多的入口，所以界面上要如实写出
     * 「命中 N 个，只显示前 3 个」而不是假装全给了。
     */
    public static final class SearchHit {
        public final List<Station> stations = new ArrayList<>();
        public final List<Line> lines = new ArrayList<>();
        /** 上游报的命中总数；0 表示它没给这个字段 */
        public int lineCount;
        public int stationCount;

        public boolean empty() {
            return stations.isEmpty() && lines.isEmpty();
        }

        /** 站点是不是被截断了（上游只回前 3 个）。 */
        public boolean stationsTruncated() {
            return stationCount > stations.size();
        }

        /** 线路是不是被截断了（正反方向已合并成一行，所以直接跟命中数比）。 */
        public boolean linesTruncated() {
            return lineCount > lines.size();
        }
    }

    /** 线路详情里的一个站。 */
    public static final class Stop {
        public String id = "";
        public String physicalId = "";
        public String name = "";
        public int order = 0;
        /**
         * 站序的显示文案。上游有「虚拟站组」——挂在某一站下面的几个小站，
         * sId 写成 {@code 999$30$1}、order 一律给 999。直接显示就变成一排 999，
         * 所以这里还原成 {@code 30-1}；普通站就是 order 本身。
         */
        public String orderLabel = "";
        public double lat;
        public double lng;
        /** 当前有车在这一站（用于时间轴上标出车辆位置） */
        public boolean busHere = false;
        /**
         * 车辆在「上一站 → 本站」这段上的位置比例：0 = 刚过上一站，1 = 已到本站；
         * 负数表示这段没车。车在路上就标在两站中间，不硬贴到站上。
         */
        public double busRatio = -1;
        /** 离用户最近的那一站 */
        public boolean nearest = false;

        /** 点站名进站点详情时需要的对象。 */
        public Station toStation() {
            Station s = new Station();
            s.id = id;
            s.physicalId = physicalId;
            s.name = name;
            s.lat = lat;
            s.lng = lng;
            return s;
        }
    }

    /** 线路详情：站表 + 在途车辆 + 反向线路 + 到候车站的预测。 */
    public static final class LineDetail {
        public Line line = new Line();
        /** 反向线路 id；空 = 没有对向（单程 / 环线） */
        public String reverseId = "";
        public String reverseStart = "";
        public String reverseEnd = "";
        public final List<Stop> stops = new ArrayList<>();
        /** 在途车辆数 */
        public int busCount = 0;
        /** 运营状态文案，如「等待发车」「末班已过」 */
        public String status = "";
        /** 下一班发车时刻（首站） */
        public String nextDeparture = "";

        /** 候车站（离用户最近的站）与它的到站预测 */
        public String waitingStopName = "";
        public int waitingOrder = 0;
        /** 候车站的 sId。站序不一定唯一（虚拟站组共用 999），认站只能靠 id。 */
        public String waitingStopId = "";
        public int etaMinutes = -1;
        public int etaStops = -1;
        public int etaDistanceMeters = -1;
        /** 车真的在候车站上（上游明说「已到站」），优先级高于 etaMinutes==0 的「即将到站」 */
        public boolean etaArrived = false;
        /** 没车在途时上游估的到站时刻（车来了 preArrivalTime，形如 19:34），掌上公交没有这数据 */
        public String estArrival = "";

        public boolean reversible() {
            return reverseId != null && !reverseId.isEmpty();
        }

        /** 「小于1分钟·17站 / 8.8千米」，上游没有距离就只给站数。 */
        public String etaDetail() {
            StringBuilder sb = new StringBuilder();
            // 已到站就不写「小于1分钟」了，大字已经说明白
            if (etaMinutes == 0 && !etaArrived) sb.append("小于1分钟");
            if (etaStops > 0) {
                if (sb.length() > 0) sb.append("·");
                sb.append(etaStops).append("站");
            }
            if (etaDistanceMeters > 0) {
                if (sb.length() > 0) sb.append(" / ");
                if (etaDistanceMeters < 1000) sb.append(etaDistanceMeters).append("米");
                else sb.append(String.format(java.util.Locale.US, "%.1f千米", etaDistanceMeters / 1000.0));
            }
            return sb.toString();
        }
    }

    /** 首站发车时刻表：今日预测 + 昨日实际班次（上游 preStartTimetableNew）。 */
    public static final class Timetable {
        public String lineName = "";
        public String startName = "";
        public String endName = "";
        /** 「首站发车预测」/「历史班次首站发车时间」 */
        public String depTitle = "";
        public String tip = "";
        /** 今日预测的分时段班次（只给还没发的） */
        public final List<TtSegment> today = new ArrayList<>();
        /** 昨日实际跑出来的整日班次 */
        public final List<TtSegment> history = new ArrayList<>();
        /** 下一班发车时刻 HH:mm；空 = 今天没班次了 */
        public String nextDep = "";
        /** 下一班是从哪来的：「今日预测」/「昨日实际」/「明日首班」 */
        public String nextFrom = "";
        public String firstDep = "";
        public String lastDep = "";
        /** 发车间隔（分钟），上游没给就是 -1 */
        public int interval = -1;

        public List<String> todayTimes() {
            List<String> out = new ArrayList<>();
            for (TtSegment s : today) out.addAll(s.times);
            sortByClock(out);
            return out;
        }

        public List<String> historyTimes() {
            List<String> out = new ArrayList<>();
            for (TtSegment s : history) out.addAll(s.times);
            sortByClock(out);
            return out;
        }

        public boolean empty() {
            return today.isEmpty() && history.isEmpty();
        }
    }

    /** 时刻表里的一段时间：几点到几点、几分钟一班、具体班次。 */
    public static final class TtSegment {
        public String from = "";
        public String to = "";
        public String tips = "";
        /** 平均发车间隔（分钟），-1 = 上游没给，只能按班次算 */
        public int interval = -1;
        public final List<String> times = new ArrayList<>();

        /** 「06:24–10:00 · 约 21 分钟一班」，上游没给间隔就只写时段。 */
        public String label() {
            StringBuilder sb = new StringBuilder();
            if (!from.isEmpty() || !to.isEmpty()) sb.append(from).append("–").append(to);
            int iv = interval;
            if (iv <= 0 && times.size() > 1) iv = estimateInterval(times);
            if (iv > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("约 ").append(iv).append(" 分钟一班");
            }
            return sb.toString();
        }

        /** 用相邻班次的间隔中位数估一个，别让极端值带偏。 */
        private static int estimateInterval(List<String> times) {
            List<Integer> gaps = new ArrayList<>();
            for (int i = 1; i < times.size(); i++) {
                int a = minutesOf(times.get(i - 1));
                int b = minutesOf(times.get(i));
                if (a >= 0 && b > a) gaps.add(b - a);
            }
            if (gaps.isEmpty()) return -1;
            Collections.sort(gaps);
            return gaps.get(gaps.size() / 2);
        }
    }

    /** 按钟点排序：字符串排序会把 "6:24" 排到 "10:00" 后面，得按分钟数比。 */
    public static void sortByClock(List<String> times) {
        Collections.sort(times, (a, b) -> Integer.compare(minutesOf(a), minutesOf(b)));
    }

    /** 45 → 「45分钟」；104 → 「1小时44分」。城乡公交跑一趟动辄一个多小时，写「104分钟」读着费劲。 */
    public static String minutesText(int m) {
        if (m < 60) return m + "分钟";
        int h = m / 60;
        int r = m % 60;
        return r == 0 ? h + "小时" : h + "小时" + r + "分";
    }

    /** "07:43" → 463；不是钟点返回 -1。 */
    public static int minutesOf(String hhmm) {
        if (hhmm == null) return -1;
        String v = hhmm.trim();
        int i = v.indexOf(':');
        if (i <= 0) return -1;
        try {
            int h = Integer.parseInt(v.substring(0, i));
            int m = Integer.parseInt(v.substring(i + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }
}
