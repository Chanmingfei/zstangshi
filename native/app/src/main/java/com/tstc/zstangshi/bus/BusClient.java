package com.tstc.zstangshi.bus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实时公交的数据源分发器。
 *
 * <p>底层有两套实现，对外的接口签名完全一致：
 * <ul>
 *   <li>{@link ChelaileClient} —— 车来了（默认）。到站时间更准，但个别城乡线路搜不到。</li>
 *   <li>{@link MygolbsClient} —— 掌上公交。线路更全（含城乡公交），协议在它自己类里，
 *       这层留着的掌上公交编排逻辑是它的专属分支。</li>
 * </ul>
 *
 * <p>用户在实时公交首页右上角切换，选完就记住（存 {@link BusStore}），
 * 下次打开直接用他选的那个。两套的 lineId / cityId 编号体系互不相通，
 * 所以城市按源各存一份、收藏按源过滤。
 *
 * <p>请求节奏统一控制：预测 3 并发 + 70ms 间隔，站点详情 3 并发，
 * 附近站点补线路 4 并发。上游对密集请求会直接掐连接，宁可慢一点。
 *
 * <p>全部方法都是阻塞的，调用方负责放到后台线程。
 */
public final class BusClient {

    /** 数据源：车来了。 */
    public static final int SRC_CHELAILE = 0;
    /** 数据源：掌上公交。 */
    public static final int SRC_MYGOLBS = 1;

    private static volatile int source = SRC_CHELAILE;

    /**
     * 默认城市：随数据源走 —— 车来了是数字编号「056」，
     * 掌上公交直接用城市名「唐山市」。{@link #setSource} 里更新。
     */
    public static String DEFAULT_CITY_ID = ChelaileClient.DEFAULT_CITY_ID;
    public static final String DEFAULT_CITY_NAME = "唐山";

    private BusClient() {
    }

    /** 当前数据源。 */
    public static int source() {
        return source;
    }

    /** 当前是不是掌上公交。 */
    public static boolean ml() {
        return source == SRC_MYGOLBS;
    }

    /** 数据源的中文名（顶栏按钮、提示用）。 */
    public static String sourceName() {
        return ml() ? "掌上公交" : "车来了";
    }

    /** 切换数据源（只切内存态；持久化在 {@link BusStore#setSource}）。 */
    public static void setSource(int s) {
        if (s != SRC_CHELAILE && s != SRC_MYGOLBS) s = SRC_CHELAILE;
        if (s == source) return;
        source = s;
        DEFAULT_CITY_ID = ml() ? MygolbsClient.DEFAULT_CITY : ChelaileClient.DEFAULT_CITY_ID;
        // 两套线路的站表缓存互不相通，整批作废
        MygolbsClient.clearCache();
    }

    // ──────────────────────────── 对外接口 ────────────────────────────

    /** 支持实时公交的城市。失败抛异常，由调用方兜底成「当前城市」。 */
    public static List<BusModels.City> cities() throws Exception {
        return ml() ? MygolbsClient.cities() : ChelaileClient.cities();
    }

    /** 附近站点（带每条线的到站）。limit 控制返回条数。 */
    public static List<BusModels.Station> nearby(String cityId, double lat, double lng, int limit)
            throws Exception {
        return ml() ? MygolbsClient.nearby(cityId, lat, lng, limit)
                : ChelaileClient.nearby(cityId, lat, lng, limit);
    }

    /**
     * 站点详情：一个站停靠的全部线路与到站。
     *
     * <p>掌上公交按站名查（{@code CMD=105}），站名在 {@code stationId} 里
     * （形如 {@code mg|唐山市|世纪花园站}），其余三个参数是为了兼容旧调用方保留的。
     */
    public static BusModels.Station stopDetail(String cityId, String physicalId, String namesakeId,
                                              String stationId, double lat, double lng) throws Exception {
        if (!ml()) return ChelaileClient.stopDetail(cityId, physicalId, namesakeId, stationId, lat, lng);
        BusModels.Station out = MygolbsClient.stopDetail(cityId, stationId);
        // 站点自己的坐标上游这里不给，别拿用户位置冒充，否则回填列表时距离会变成 0
        out.lat = 0;
        out.lng = 0;
        return out;
    }

    /**
     * 关键字搜索：站点 + 线路一起搜（中文站名、线路号如「17」「17路」都行）。
     *
     * <p>掌上公交的线路搜索一次就回全量，所以 {@link #searchMore} 没有更多可取，
     * 界面下滑翻页靠本地缓存分屏。
     */
    public static BusModels.SearchHit search(String cityId, String keyword, double lat, double lng)
            throws Exception {
        if (!ml()) return ChelaileClient.search(cityId, keyword, lat, lng);
        BusModels.SearchHit hit = new BusModels.SearchHit();
        try {
            hit.stations.addAll(MygolbsClient.searchStops(cityId, keyword));
        } catch (Exception ignored) {
            // 站点搜不到不该连累线路结果
        }
        hit.lines.addAll(MygolbsClient.searchLines(cityId, keyword));
        hit.stationCount = hit.stations.size();
        hit.lineCount = hit.lines.size();
        return hit;
    }

    /** 线路搜索：掌上公交一次给全量没有「更多」；车来了走翻页。 */
    public static List<BusModels.Line> searchMore(String cityId, String keyword, double lat, double lng)
            throws Exception {
        if (!ml()) return ChelaileClient.searchMore(cityId, keyword, lat, lng);
        return new ArrayList<>();
    }

    /**
     * 线路详情：站表 + 在途车辆。
     *
     * @param targetOrder 候车站的站序（1 基），>0 时用它问「车到这个站还有多久」
     * @param stationId   站点的 sId；有它时优先按 id 认站（同名站不会认错）
     */
    public static BusModels.LineDetail lineDetail(String cityId, String lineId, int targetOrder,
                                                 String stationId, double lat, double lng) throws Exception {
        if (!ml()) return ChelaileClient.lineDetail(cityId, lineId, targetOrder, stationId, lat, lng);
        String[] ref = MygolbsClient.parseLineId(lineId);
        if (ref == null) throw new IOException("这条线路来自旧的数据源，请重新搜索");
        String city = ref[0];
        String lineName = ref[1];
        int dir = "1".equals(ref[2]) ? 0 : 1;

        MygolbsClient.RouteInfo route = MygolbsClient.route(city, lineName, dir);
        if (route.stops.isEmpty()) throw new IOException("这条线路暂无站表数据");

        BusModels.LineDetail d = new BusModels.LineDetail();
        d.line.lineId = lineId;
        d.line.name = lineName;
        d.line.lineNo = lineName;
        d.line.direction = dir;
        d.line.startName = route.stops.get(0).name;
        d.line.endName = route.stops.get(route.stops.size() - 1).name;
        d.line.firstTime = route.firstTime;
        d.line.lastTime = route.lastTime;
        d.line.price = route.price;
        d.line.stationsNum = route.stops.size();

        // 缓存里的站对象要复制一份：下面会给 busRatio / nearest 赋值，不能改缓存
        for (BusModels.Stop src : route.stops) {
            BusModels.Stop stop = new BusModels.Stop();
            stop.id = src.id;
            stop.physicalId = src.physicalId;
            stop.name = src.name;
            stop.order = src.order;
            stop.orderLabel = src.orderLabel;
            stop.lat = src.lat;
            stop.lng = src.lng;
            d.stops.add(stop);
        }

        d.reverseId = MygolbsClient.lineId(city, lineName, 1 - dir);
        d.reverseStart = d.line.endName;
        d.reverseEnd = d.line.startName;

        // 在途车辆：104 的 index 是 0 起，车正开往第 index+1 站
        int ask = targetOrder > 0 ? targetOrder : d.stops.size();
        MygolbsClient.Realtime rt = MygolbsClient.realtime(city, lineName, dir, ask);
        d.busCount = rt.buses.size();
        for (MygolbsClient.Bus b : rt.buses) {
            int order = b.index + 1;
            if (order <= 0) continue;
            BusModels.Stop cur = stopByOrder(d.stops, order);
            if (cur == null) continue;
            BusModels.Stop prev = stopByOrder(d.stops, order - 1);
            double ratio = 1;
            if (prev != null && b.lat != 0 && b.lng != 0
                    && prev.lat != 0 && prev.lng != 0 && cur.lat != 0 && cur.lng != 0) {
                double seg = distanceMeters(prev.lat, prev.lng, cur.lat, cur.lng);
                double toPrev = distanceMeters(prev.lat, prev.lng, b.lat, b.lng);
                if (seg > 1) ratio = Math.max(0, Math.min(1, toPrev / seg));
            }
            // 同一段可能有两辆车，取最靠前的那辆画
            if (ratio > cur.busRatio) cur.busRatio = ratio;
        }
        for (BusModels.Stop s : d.stops) {
            if (s.busRatio >= 0) s.busHere = s.busRatio >= 0.9;
        }

        d.status = statusOf(rt, route, 0);
        d.nextDeparture = rt.planTime;

        // ── 候车站：进来时指定的那站，没指定就用离用户最近的那站 ──
        BusModels.Stop wait = null;
        if (stationId != null && !stationId.isEmpty()) {
            for (BusModels.Stop s : d.stops) {
                if (stationId.equals(s.id)) {
                    wait = s;
                    break;
                }
            }
        }
        if (wait == null && targetOrder > 0) {
            for (BusModels.Stop s : d.stops) {
                if (s.order == targetOrder) {
                    wait = s;
                    break;
                }
            }
        }
        // 「离我最近」跟着定位走，和候车站是两回事：
        // 用户点了别的站，最近站的标注也不该跟着跑
        BusModels.Stop nearest = null;
        if (lat > 0 && lng > 0 && !d.stops.isEmpty()) {
            double best = Double.MAX_VALUE;
            for (BusModels.Stop s : d.stops) {
                if (s.lat == 0 && s.lng == 0) continue;
                double m = distanceMeters(lat, lng, s.lat, s.lng);
                if (m < best) {
                    best = m;
                    nearest = s;
                }
            }
        }
        if (nearest != null) nearest.nearest = true;
        if (wait == null) wait = nearest;
        if (wait == null && !d.stops.isEmpty()) wait = d.stops.get(0);
        if (wait != null) {
            d.waitingStopName = wait.name;
            d.waitingOrder = wait.order;
            d.waitingStopId = wait.id;
            // 状态跟候车站有关：车已经过了这一站才算「末班已过」，不能拿整条线有没有车来判
            d.status = statusOf(rt, route, wait.order);
            fillEta(d, cityId, lineId, wait, lat, lng);
        }
        return d;
    }

    /** 一次车辆预测的结果（最快的车还有多久到、还剩几站、离多少米）。 */
    public static final class Pred {
        public int secs = -1;
        public int stops = -1;
        public int dist = -1;
        /** 车真的在候车站上（上游明说「已到站」），跟「小于1分钟」是两回事 */
        public boolean arrived = false;
        public String depDesc = "";
        public String preArrival = "";
    }

    /** 拉一次到「候车站」的车辆预测，填分钟 / 站数 / 距离。失败不影响站表。 */
    private static void fillEta(BusModels.LineDetail d, String cityId, String lineId,
                                BusModels.Stop wait, double lat, double lng) {
        Pred pr = predict(cityId, lineId, wait.order, wait.id, lat, lng);
        if (pr == null) return;
        if (pr.secs >= 0) {
            d.etaMinutes = (pr.secs + 59) / 60;
            d.etaStops = pr.stops;
            d.etaDistanceMeters = pr.dist;
        }
        d.etaArrived = pr.arrived;
        if (d.status.isEmpty()) {
            d.status = pr.depDesc;
            if (d.nextDeparture.isEmpty()) d.nextDeparture = pr.preArrival;
        }
    }

    // ───────────────────── 首站发车时刻表 ─────────────────────

    /** 首站发车时刻表：掌上公交走 {@code CMD=207}，车来了走 {@code preStartTimetableNew}。 */
    public static BusModels.Timetable timetable(String cityId, String lineId, int direction,
                                               String lineNo) throws Exception {
        if (!ml()) return ChelaileClient.timetable(cityId, lineId, direction, lineNo);
        String[] ref = MygolbsClient.parseLineId(lineId);
        if (ref == null) throw new IOException("这条线路来自旧的数据源，请重新搜索");
        int dir = "1".equals(ref[2]) ? 0 : 1;
        return MygolbsClient.timetable(ref[0], ref[1], dir);
    }

    /**
     * 某条线的车到指定站还有多久。
     *
     * <p>首页和线路详情共用这一个口径 —— 都按站序问 {@code CMD=104}，
     * 上游直接给「还有几站 / 还有几分钟 / 还有多远」，不做二次折算。
     */
    public static Pred predict(String cityId, String lineId, int order, String stopId,
                               double lat, double lng) {
        if (!ml()) {
            ChelaileClient.Pred p =
                    ChelaileClient.predict(cityId, lineId, order, stopId, lat, lng);
            if (p == null) return null;
            Pred out = new Pred();
            out.secs = p.secs;
            out.stops = p.stops;
            out.dist = p.dist;
            out.arrived = p.arrived;
            out.depDesc = p.depDesc;
            out.preArrival = p.preArrival;
            return out;
        }
        try {
            String[] ref = MygolbsClient.parseLineId(lineId);
            if (ref == null) return null;
            int dir = "1".equals(ref[2]) ? 0 : 1;
            MygolbsClient.Realtime rt = MygolbsClient.realtime(ref[0], ref[1], dir, order);

            Pred out = new Pred();
            out.preArrival = rt.planTime;
            MygolbsClient.Eta e = rt.first();
            if (e != null && e.minutes >= 0) {
                out.secs = e.minutes * 60;
                out.stops = e.count;
                out.dist = e.distance;
                // 有一辆车在站上就算到站（多辆车时 0 分钟会把它排最前）
                for (MygolbsClient.Eta x : rt.etas) {
                    if (x.arrived) {
                        out.arrived = true;
                        break;
                    }
                }
            } else {
                out.secs = -1;
                out.depDesc = shortStatusOf(rt);
            }
            return out;
        } catch (Exception ignored) {
            // 预测是加分项，失败就只显示站表
            return null;
        }
    }

    /** 线路级的运营状态：停运 / 未开通实时 / 非运营时间 / 等待发车 / 末班已过。 */
    private static String statusOf(MygolbsClient.Realtime rt, MygolbsClient.RouteInfo route,
                                   int waitOrder) {
        if (rt.runState == 1) return "该线路已停运";
        if (!rt.hasReal) return "该线路未开通实时数据查询";
        // 只有「还有车没开过候车站」才不写状态；车全过站了才按首末班判
        boolean hasUpcoming = waitOrder <= 0;
        for (MygolbsClient.Bus b : rt.buses) {
            if (b.index < 0) continue;
            if (waitOrder <= 0 || b.index + 1 <= waitOrder) {
                hasUpcoming = true;
                break;
            }
        }
        if (hasUpcoming) return "";
        // 官方 104 明说的状态（如「非运营时间」）优先于按首末班推出来的文案，
        // 这样点进线路详情和首页补预测后显示的是同一句官方原话
        if (!rt.statusTips.isEmpty()) return rt.statusTips;
        int now = BusModels.minutesOf(MygolbsClient.nowHHMM());
        int first = BusModels.minutesOf(route.firstTime);
        int last = BusModels.minutesOf(route.lastTime);
        if (first >= 0 && now < first) return "等待发车";
        if (last >= 0 && now > last) return "末班已过";
        return "";
    }

    /** 没有车时的那句短文案。 */
    private static String shortStatusOf(MygolbsClient.Realtime rt) {
        if (rt.runState == 1) return "该线路已停运";
        if (!rt.hasReal) return "未开通实时";
        if (!rt.statusTips.isEmpty()) return rt.statusTips;
        if (!rt.planTime.isEmpty()) return "下趟 " + rt.planTime + " 发车";
        return "";
    }

    /** 一条到站行有没有实时数据（任一线路有就算有）。 */
    public static boolean hasRealtime(BusModels.Station s) {
        for (BusModels.Arrival a : s.arrivals) {
            if (a.realtime) return true;
        }
        return false;
    }

    // ──────────────────────── 补预测（到站分钟） ────────────────────────

    /**
     * 补预测的进度回调，在后台线程上回调，调用方自己 post 到 UI。
     * 刻意只留一个方法：单方法接口能用 lambda，不受 d8 对内部类的那点脾气影响。
     */
    public interface EtaWatcher {
        void onEta(BusModels.Arrival a);
    }

    /**
     * 把一批「正显示在屏幕上」的到站行整体重算一遍。
     *
     * <p>站点的线路接口（{@code CMD=105}）只给「7站」「即将到站」这类文案，
     * 没有秒数；分钟数要靠 {@code CMD=104} 按站序问。两处统一走 {@link #predict}，
     * 首页和点进去看到的数才对得上。
     *
     * @param force   true = 已经有数的行也重算（定时刷新 / 进页面先用这一档）；
     *                false = 只补还没有数据的那几条
     * @param limit   这一轮最多算几条，防止一次轰几十个请求被上游掐掉
     * @param watcher 每条回来回调一次，界面只换那一行文字
     */
    public static void refreshEta(final String cityId, final List<BusModels.Arrival> shown,
                                  final double lat, final double lng, final int limit,
                                  final boolean force, final EtaWatcher watcher) {
        if (shown == null || shown.isEmpty()) return;
        // 车来了那套有自己的补预测编排（walk 进出站、按 sId 认站），直接用它的
        if (!ml()) {
            ChelaileClient.refreshEta(cityId, shown, lat, lng, limit, force,
                    watcher == null ? null : watcher::onEta);
            return;
        }
        final List<BusModels.Arrival> todo = new ArrayList<>();
        final List<String> stopIds = new ArrayList<>();
        for (BusModels.Arrival a : shown) {
            if (todo.size() >= limit) break;
            // 不知道这个站在线路上排第几就没法算预测，跳过（这些行本来也只显示状态文案）
            if (a.orderInLine <= 0) continue;
            if (!force && (a.arrived || a.minutes >= 0)) continue;
            // 一点数据都没有的先挂「…」，别让人以为这站真没车；
            // 已经算过的就别再闪一次了
            if (!a.arrived && a.minutes < 0 && !a.refreshed) a.pending = true;
            todo.add(a);
            stopIds.add(a.stopId == null ? "" : a.stopId);
        }
        runPredict(cityId, todo, stopIds, lat, lng, watcher);
    }

    private static void runPredict(final String cityId, final List<BusModels.Arrival> todo,
                                   final List<String> stopIds, final double lat, final double lng,
                                   final EtaWatcher watcher) {
        if (todo.isEmpty()) return;

        final int n = todo.size();
        // 三条并发就够了：上游对这个接口有限流，几十条一起打会直接被掐连接，
        // 那时候用户看到的还是一排「暂无实时」，比慢一点更糟
        final int workers = Math.min(3, n);
        new Thread(() -> {
            Thread[] ts = new Thread[workers];
            for (int i = 0; i < workers; i++) {
                final int idx = i;
                ts[i] = new Thread(() -> {
                    for (int k = idx; k < n; k += workers) {
                        BusModels.Arrival a = todo.get(k);
                        Pred pr = predict(cityId, a.lineId, a.orderInLine, stopIds.get(k), lat, lng);
                        if (pr == null) {
                            // 网络抖一下就重试一次，别让这一排永远停在「暂无实时」
                            try { Thread.sleep(220); } catch (InterruptedException ignored) { }
                            pr = predict(cityId, a.lineId, a.orderInLine, stopIds.get(k), lat, lng);
                        }
                        if (pr != null && pr.secs >= 0) {
                            a.minutes = (pr.secs + 59) / 60;
                            a.stopsAway = pr.stops;
                            a.distanceMeters = pr.dist;
                            a.realtime = true;
                            // 「已到站」只认上游明说；0 分钟是「即将到站」，别混
                            if (pr.arrived) a.arrived = true;
                        } else if (pr != null && !pr.depDesc.isEmpty()) {
                            // 补不到分钟数，但官方给了线路状态（如「非运营时间」）：
                            // 覆掉首页 105 给的那句，让首页和点进详情看到同一句话
                            a.status = pr.depDesc;
                        }
                        // 补不到就老实收起「…」，别一直转
                        a.pending = false;
                        a.refreshed = true;
                        if (watcher != null) watcher.onEta(a);
                        try { Thread.sleep(70); } catch (InterruptedException ignored) { }
                    }
                }, "bus-eta-" + i);
                ts[i].start();
            }
            for (Thread t : ts) {
                try { t.join(); } catch (InterruptedException ignored) { }
            }
        }, "bus-eta-all").start();
    }

    /** 球面距离（米）。站与用户都在 WGS84 下，直接算即可。 */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    private static BusModels.Stop stopByOrder(List<BusModels.Stop> stops, int order) {
        for (BusModels.Stop s : stops) {
            if (s.order == order) return s;
        }
        return null;
    }

    // ──────────────────── 首页下滑：往外扩一圈找更远的站 ────────────────────

    /** 一圈最多解出多少站的坐标。 */
    private static final int RESOLVE_MAX = 20;
    /** 一轮最多放几站上屏。 */
    private static final int PAGE_SIZE = 12;
    /** 站点坐标缓存：环形探针一轮会重复探到同一批站，坐标不会变，存下来省请求。 */
    private static final Map<String, double[]> COORD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static List<BusModels.Station> fartherStations(String cityId, double lat, double lng,
                                                          int ring, Set<String> exclude) {
        // 车来了那套要逐站解坐标（106 不带坐标），编排完全不同，直接用它的
        if (!ml()) return ChelaileClient.fartherStations(cityId, lat, lng, ring, exclude);
        final int dirs = 8;
        final double radius = 700.0 * Math.max(1, ring);
        final List<BusModels.Station> bucket = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread[] probes = new Thread[dirs];
        for (int i = 0; i < dirs; i++) {
            final double bearing = i * 360.0 / dirs;
            probes[i] = new Thread(() -> {
                try {
                    double[] p = offsetPoint(lat, lng, radius, bearing);
                    // 这里只要站点和坐标：8 个方位 × 每站再查一次线路就是上百次请求，
                    // 上游会直接掐连接。线路等筛出这一屏之后再补。
                    bucket.addAll(MygolbsClient.probeStops(cityId, p[0], p[1], 15));
                } catch (Exception ignored) {
                }
            }, "bus-probe-" + i);
            probes[i].start();
        }
        for (Thread t : probes) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }

        Map<String, BusModels.Station> uniq = new LinkedHashMap<>();
        for (BusModels.Station s : bucket) {
            if (s.id.isEmpty()) continue;
            if (exclude != null && exclude.contains(s.id)) continue;
            if (!uniq.containsKey(s.id)) uniq.put(s.id, s);
        }
        if (uniq.isEmpty()) return new ArrayList<>();

        // 探到的站已经带坐标（106 直接给 lat/lon），缓存只是省得下一圈重算距离
        List<BusModels.Station> keep = new ArrayList<>();
        for (BusModels.Station s : uniq.values()) {
            if (s.lat == 0 || s.lng == 0) {
                double[] c = COORD_CACHE.get(s.id);
                if (c == null) continue;      // 没有坐标就不硬塞，免得写个假距离
                s.lat = c[0];
                s.lng = c[1];
            }
            if (COORD_CACHE.size() > 600) COORD_CACHE.clear();
            COORD_CACHE.put(s.id, new double[]{s.lat, s.lng});
            s.distanceMeters = (int) Math.round(distanceMeters(lat, lng, s.lat, s.lng));
            keep.add(s);
        }
        java.util.Collections.sort(keep, (a, b) ->
                Integer.compare(a.distanceMeters, b.distanceMeters));
        if (keep.size() > PAGE_SIZE) keep = new ArrayList<>(keep.subList(0, PAGE_SIZE));
        if (keep.size() > RESOLVE_MAX) keep = new ArrayList<>(keep.subList(0, RESOLVE_MAX));

        // 这一屏才补线路（12 个请求，4 并发）
        MygolbsClient.fillLines(cityId, keep, lat, lng);
        return keep;
    }

    /**
     * 批量拉站点详情，只往回带数据、不动传进来的对象。
     *
     * <p>首页要先把附近站点立刻画出来，详情这批稍后才回来，
     * 所以不在工作线程里改 {@code Station}：那边还在读，这边改 arrivals 会撞出并发问题。
     * 取回来交给 UI 线程合并。
     *
     * @return stationId → 详情
     */
    public static Map<String, BusModels.Station> stationDetails(String cityId,
                                                               List<BusModels.Station> list,
                                                               final double lat, final double lng) {
        if (!ml()) return ChelaileClient.stationDetails(cityId, list, lat, lng);
        final Map<String, BusModels.Station> out = new java.util.concurrent.ConcurrentHashMap<>();
        final List<BusModels.Station> todo = new ArrayList<>();
        for (BusModels.Station s : list) {
            // 掌上公交的附近站点接口已经带回了停靠线路，这里只补「还没有线路」的站，
            // 否则首页刚拉完 8 个站又整批重拉一遍，白等一秒多
            if (s.arrivals.isEmpty()) todo.add(s);
        }
        if (todo.isEmpty()) return out;
        final int workers = Math.min(3, todo.size());
        Thread[] ts = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> {
                for (int k = idx; k < todo.size(); k += workers) {
                    BusModels.Station s = todo.get(k);
                    BusModels.Station full = fetchStationDetail(cityId, s, lat, lng);
                    if (full != null) out.put(s.id, full);
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException ignored) {
                    }
                }
            }, "bus-st-" + i);
            ts[i].start();
        }
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }
        return out;
    }

    /** 拉一个站的详情并合并回列表里的那条记录（UI 线程调用）。 */
    public static void mergeDetail(BusModels.Station s, BusModels.Station full) {
        if (s == null || full == null) return;
        // 注意是**合并**不是替换：详情里没有坐标和距离，直接覆盖会把列表上算好的位置弄丢
        if (full.lat != 0 && full.lng != 0) {
            s.lat = full.lat;
            s.lng = full.lng;
        }
        if (s.platforms.isEmpty()) s.platforms.addAll(full.platforms);
        if (full.arrivals.isEmpty()) return;
        Map<String, BusModels.Arrival> fresh = new LinkedHashMap<>();
        for (BusModels.Arrival a : full.arrivals) fresh.put(key(a), a);
        for (int k = 0; k < s.arrivals.size(); k++) {
            BusModels.Arrival old = s.arrivals.get(k);
            BusModels.Arrival neu = fresh.remove(key(old));
            if (neu == null) continue;
            // 详情不保证每趟都给实时：新来这条是空的时候，别把已经算好的分钟数冲掉，
            // 只把站序这类静态信息补上
            boolean oldHasData = old.arrived || old.minutes >= 0;
            boolean neuHasData = neu.arrived || neu.minutes >= 0 || neu.realtime;
            if (!neuHasData && oldHasData) {
                if (neu.orderInLine > 0) old.orderInLine = neu.orderInLine;
                if (old.stopId.isEmpty()) old.stopId = neu.stopId;
                if (old.status.isEmpty()) old.status = neu.status;
                continue;
            }
            s.arrivals.set(k, neu);
        }
        s.arrivals.addAll(fresh.values());
    }

    /** 拉一个站的详情；失败歇一下再试一次。 */
    private static BusModels.Station fetchStationDetail(String cityId, BusModels.Station s,
                                                        double lat, double lng) {
        BusModels.Station full = null;
        try {
            full = stopDetail(cityId, s.physicalId, s.namesakeId, s.id, lat, lng);
        } catch (Exception ignored) {
        }
        if (full == null) {
            try {
                Thread.sleep(260);
                full = stopDetail(cityId, s.physicalId, s.namesakeId, s.id, lat, lng);
            } catch (Exception ignored) {
            }
        }
        return full;
    }

    private static String key(BusModels.Arrival a) {
        return a.lineId + "#" + a.direction;
    }

    /** 从 (lat,lng) 沿方位角走 meters 米后的坐标（球面公式，够用）。 */
    private static double[] offsetPoint(double lat, double lng, double meters, double bearingDeg) {
        double r = 6371000.0;
        double br = Math.toRadians(bearingDeg);
        double la1 = Math.toRadians(lat), lo1 = Math.toRadians(lng);
        double dr = meters / r;
        double la2 = Math.asin(Math.sin(la1) * Math.cos(dr)
                + Math.cos(la1) * Math.sin(dr) * Math.cos(br));
        double lo2 = lo1 + Math.atan2(Math.sin(br) * Math.sin(dr) * Math.cos(la1),
                Math.cos(dr) - Math.sin(la1) * Math.sin(la2));
        return new double[]{Math.toDegrees(la2), Math.toDegrees(lo2)};
    }
}
