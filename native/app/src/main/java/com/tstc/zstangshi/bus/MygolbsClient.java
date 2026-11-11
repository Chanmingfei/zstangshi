package com.tstc.zstangshi.bus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 掌上公交（mygolbs）数据源。
 *
 * <p>协议来自 H5 数据面：{@code POST https://h5.mygolbs.com/ApiData.do}，
 * 表单体只有一个 {@code CMD} 加若干大写参数，必须带 {@code Origin} / {@code Referer}，
 * 响应是明文 JSON、{@code status} 为 1 才算成功（不是 1 时 {@code msg} 是原因）。
 *
 * <p>用到的命令：
 * <ul>
 *   <li>101 城市列表</li>
 *   <li>103 线路站表（站序、坐标、首末班、票价、routeId）</li>
 *   <li>104 线路实时（在途车辆 + 每辆车到指定站的分钟数）</li>
 *   <li>105 某站点停靠的线路与最近车文案</li>
 *   <li>106 经纬度周边站点</li>
 *   <li>110 站点搜索 / 114 线路搜索</li>
 *   <li>207 首站发车时刻表</li>
 * </ul>
 *
 * <p>线路与站点没有稳定 id，这里用「城市名 + 名称」自造：
 * 线路 {@code mg|唐山市|1路|1}（末段 1 上行 / 2 下行），站点 {@code mg|唐山市|鑫雅园小区站}。
 * 城市一律用掌上公交的城市名（如「唐山市」），车来了那套数字编号会被换成默认城市。
 */
public final class MygolbsClient {

    private static final String API = "https://h5.mygolbs.com/ApiData.do";
    private static final String ORIGIN = "https://h5.mygolbs.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 没有城市时的默认值：唐山。 */
    public static final String DEFAULT_CITY = "唐山市";

    private static final String P_LINE = "mg|";
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 8000;

    /** 站表这类静态数据缓存 6 小时：轮询时只打 104，不再重复拉站表。 */
    private static final long ROUTE_TTL_MS = 6 * 60 * 60 * 1000L;

    private static final Map<String, RouteInfo> ROUTE_CACHE = new ConcurrentHashMap<>();

    private MygolbsClient() {
    }

    /** 换数据源时整批作废站表缓存（BusClient.setSource 调）。 */
    public static void clearCache() {
        ROUTE_CACHE.clear();
    }

    // ──────────────────────────── 对外接口 ────────────────────────────

    /** 支持实时公交的城市。id 就是掌上公交的城市名。 */
    public static List<BusModels.City> cities() throws Exception {
        JSONObject j = call(single("CMD", "101"));
        JSONArray arr = firstArray(j, "data", "list");
        List<BusModels.City> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            String name = c.optString("cityname");
            if (name.isEmpty()) name = c.optString("cityName");
            if (name.isEmpty()) name = c.optString("CITYNAME");
            name = name.trim();
            if (name.isEmpty()) continue;
            out.add(new BusModels.City(name, shortCityName(name)));
        }
        if (out.isEmpty()) out.add(new BusModels.City(DEFAULT_CITY, "唐山"));
        return out;
    }

    /** 附近站点：106 拿站点和坐标，再逐站 105 补线路上车。 */
    public static List<BusModels.Station> nearby(String cityId, double lat, double lng, int limit)
            throws Exception {
        List<BusModels.Station> stops = probeStops(cityId, lat, lng, limit);
        fillLines(cityId, stops, lat, lng);
        return stops;
    }

    /**
     * 只探站点（106），不查线路。下滑加载时一次要探 8 个方位，
     * 若每个方位都连带查 105 就是上百次请求，必然被上游掐掉。
     */
    public static List<BusModels.Station> probeStops(String cityId, double lat, double lng, int limit)
            throws Exception {
        String city = city(cityId);
        // 上游按 GCJ-02 算距离，先把用户坐标转过去，否则整片站点会偏 500 米
        double[] g = wgs2gcj(lat, lng);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("CMD", "106");
        p.put("CITYNAME", city);
        p.put("LAT", fmt(g[0]));
        p.put("LNG", fmt(g[1]));
        p.put("MYLAT", fmt(g[0]));
        p.put("MYLNG", fmt(g[1]));
        JSONObject j = call(p);

        JSONArray arr = j.optJSONArray("data");
        List<BusModels.Station> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && out.size() < limit; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name").trim();
            if (name.isEmpty()) continue;
            BusModels.Station s = newStation(city, name);
            double[] w = gcj2wgs(o.optDouble("lat", 0), o.optDouble("lon", 0));
            s.lat = w[0];
            s.lng = w[1];
            int dis = o.optInt("dis", -1);
            // dis 是上游按 GCJ 算的两点距离，同一坐标系内相对距离不变，可以直接用
            if (dis > 0) {
                s.distanceMeters = dis;
            } else if (s.lat != 0 && s.lng != 0) {
                s.distanceMeters = (int) Math.round(BusClient.distanceMeters(lat, lng, s.lat, s.lng));
            }
            out.add(s);
        }
        return out;
    }

    /** 给一批站点补上各自停靠的线路（4 并发，失败不影响其他站）。 */
    public static void fillLines(String cityId, List<BusModels.Station> list, double lat, double lng) {
        if (list == null || list.isEmpty()) return;
        final String city = city(cityId);
        final List<BusModels.Station> todo = new ArrayList<>(list);
        final int workers = Math.min(4, todo.size());
        Thread[] ts = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> {
                for (int k = idx; k < todo.size(); k += workers) {
                    BusModels.Station s = todo.get(k);
                    try {
                        BusModels.Station full = stopLines(city, s.name);
                        s.arrivals.clear();
                        s.arrivals.addAll(full.arrivals);
                    } catch (Exception ignored) {
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                    }
                }
            }, "mg-lines-" + i);
            ts[i].start();
        }
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }
    }

    /** 站点详情：某个站停靠的全部线路。siteId 形如 {@code mg|唐山市|站名}。 */
    public static BusModels.Station stopDetail(String cityId, String stationId) throws Exception {
        String city = city(cityId);
        String name = stationNameOf(stationId);
        if (name.isEmpty()) throw new IOException("站点缺少站名");
        return stopLines(city, name);
    }

    /** CMD=105：某站点的线路与最近一辆车的文案。 */
    public static BusModels.Station stopLines(String city, String stationName) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("CMD", "105");
        p.put("CITYNAME", city);
        p.put("STATIONNAME", stationName);
        p.put("MYLAT", "");
        p.put("MYLNG", "");
        p.put("CITYKEY", "");
        JSONObject j = call(p);

        BusModels.Station s = newStation(city, stationName);
        JSONArray arr = j.optJSONArray("data");
        if (arr == null) return s;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String lineName = o.optString("lineName");
            if (lineName.isEmpty()) lineName = o.optString("routeName");
            lineName = lineName.trim();
            if (lineName.isEmpty()) continue;
            // 纯地铁线路没有公交实时数据
            JSONObject bean = o.optJSONObject("routeBean");
            if (bean != null && "3".equals(bean.optString("routeType"))) continue;
            if (lineName.contains("地铁")) continue;

            String up = o.optString("upperOrDown");
            if (up.isEmpty()) up = "1";
            int dir = "1".equals(up) ? 0 : 1;

            BusModels.Arrival a = new BusModels.Arrival();
            a.lineId = lineId(city, lineName, dir);
            a.lineName = lineName;
            a.direction = dir;
            a.startName = o.optString("from");
            a.endName = o.optString("to");
            a.stopId = s.id;
            a.orderInLine = orderOf(o);
            a.realtime = hasRealOf(bean);

            String nearText = cleanNearText(o.optString("neartext"));
            String nearTime = o.optString("neartime").trim();
            a.status = nearText;
            a.stopsAway = stopsOf(nearText);
            a.minutes = minutesOfText(nearTime);
            if (nearText.contains("已到站") || nearText.contains("正在进站")) {
                // 只有文案明说「已到站 / 正在进站」才算真到站
                a.arrived = true;
                if (a.minutes < 0) a.minutes = 0;
            } else if (nearText.contains("即将到站") && a.minutes < 0) {
                // 官方口径：车还差一点距离时叫「即将到站」配「小于1分钟」，
                // 不叫「已到站」；这里只把分钟归零，arrived 不动
                a.minutes = 0;
            }
            // 只有确定要算的行才挂「…」，否则界面会一直转
            a.pending = a.realtime && !a.arrived && a.minutes < 0 && a.orderInLine > 0;
            s.arrivals.add(a);
        }
        return s;
    }

    /** CMD=103：站表 + 首末班 + 票价 + routeId（6 小时缓存）。 */
    public static RouteInfo route(String cityId, String lineName, int dir) throws Exception {
        String city = city(cityId);
        String dirStr = dir == 0 ? "1" : "2";
        String key = city + "|" + lineName + "|" + dirStr;
        RouteInfo cached = ROUTE_CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.atMillis < ROUTE_TTL_MS) return cached;

        Map<String, String> p = new LinkedHashMap<>();
        p.put("CMD", "103");
        p.put("CITYNAME", city);
        p.put("CITYCODE", "");
        p.put("LINENAME", lineName);
        p.put("DIRECTION", dirStr);
        p.put("CITYKEY", "");
        JSONObject j = call(p);

        RouteInfo info = new RouteInfo();
        info.city = city;
        info.lineName = lineName;
        info.direction = dir;
        info.routeId = j.optString("routeId", "");
        info.atMillis = now;

        JSONArray fl = j.optJSONArray("firstLast");
        JSONObject first = fl == null ? null : fl.optJSONObject(0);
        if (first != null) {
            info.firstTime = first.optString("first", "");
            info.lastTime = first.optString("last", "");
        }
        if (info.firstTime.isEmpty()) info.firstTime = j.optString("beginTime", "");
        if (info.lastTime.isEmpty()) info.lastTime = j.optString("endTime", "");

        String commonts = j.optString("commonts", "");
        int idx = commonts.indexOf("票价");
        if (idx >= 0) {
            String tail = commonts.substring(idx + 2);
            int colon = indexOfAny(tail, ":：");
            if (colon >= 0) {
                String v = tail.substring(colon + 1).trim();
                int stop = indexOfAny(v, "，, ");
                info.price = (stop > 0 ? v.substring(0, stop) : v).trim();
            }
        }

        JSONArray data = j.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject st = data.optJSONObject(i);
                if (st == null) continue;
                BusModels.Stop stop = new BusModels.Stop();
                int order = st.optInt("stationOrder", i + 1);
                if (order <= 0) order = i + 1;
                stop.order = order;
                stop.orderLabel = String.valueOf(order);
                stop.name = st.optString("stationName", "").trim();
                double[] w = gcj2wgs(st.optDouble("station_lat", 0), st.optDouble("station_lon", 0));
                stop.lat = w[0];
                stop.lng = w[1];
                stop.id = stopId(city, stop.name);
                stop.physicalId = stop.id;
                if (stop.name.isEmpty()) continue;
                info.stops.add(stop);
            }
        }
        // 空站表不缓存，下次重新拉
        if (!info.stops.isEmpty()) {
            if (ROUTE_CACHE.size() > 200) ROUTE_CACHE.clear();
            ROUTE_CACHE.put(key, info);
        }
        return info;
    }

    /** CMD=104：这条线当前的车辆，以及每辆车到 {@code order} 站的分钟数。 */
    public static Realtime realtime(String cityId, String lineName, int dir, int order) throws Exception {
        String city = city(cityId);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("CMD", "104");
        p.put("CITYNAME", city);
        p.put("CITYCODE", "");
        p.put("LINENAME", lineName);
        p.put("DIRECTION", dir == 0 ? "1" : "2");
        p.put("STATIONORDER", String.valueOf(order));
        p.put("CITYKEY", "");
        JSONObject j = call(p);

        Realtime rt = new Realtime();
        rt.hasReal = j.optInt("hasReal", 1) != 0;
        rt.runState = j.optInt("runState", 0);
        rt.planTime = j.optString("planTime", "").trim();

        JSONArray buses = j.optJSONArray("list");
        if (buses != null) {
            for (int i = 0; i < buses.length(); i++) {
                JSONObject b = buses.optJSONObject(i);
                if (b == null) continue;
                Bus bus = new Bus();
                bus.number = b.optString("busNumber", "");
                bus.index = b.optInt("index", -1);
                bus.statusType = b.optString("statusType", "");
                bus.stationName = b.optString("stationName", "");
                double[] bw = gcj2wgs(b.optDouble("bus_lat", 0), b.optDouble("bus_lng", 0));
                bus.lat = bw[0];
                bus.lng = bw[1];
                rt.buses.add(bus);
            }
        }

        JSONArray eta = j.optJSONArray("routeOnStationRTimeInfoList");
        if (eta != null) {
            for (int i = 0; i < eta.length(); i++) {
                JSONObject e = eta.optJSONObject(i);
                if (e == null) continue;
                String tips = e.optString("busToStationTips", "");
                int count = e.optInt("busToStationCount", -1);
                // 收班/非运营时官方在这里明说「非运营时间」「已过末班时刻」（都配 count=-1），
                // 是线路级状态，不是某辆车的到站 —— 记下来给首页和线路详情统一用，
                // 别当过站车丢掉。关键字按官方实际出现过的文案收，宁缺毋滥
                if (count < 0 && (tips.contains("非运营") || tips.contains("收班")
                        || tips.contains("末班") || tips.contains("停运"))) {
                    if (rt.statusTips.isEmpty()) rt.statusTips = tips;
                    continue;
                }
                // count < 0 是「这辆车已经过了这一站」，上游仍把它列出来、时间写 0，
                // 直接用会把「刚开走」当成「已到站」
                if (count < 0) continue;
                Eta item = new Eta();
                item.busNumber = e.optString("busNumber", "");
                item.count = count;
                item.minutes = minutesOfText(e.optString("busToStationTimeTips", ""));
                if (item.minutes < 0) {
                    double t = e.optDouble("busToStationTime", -1);
                    if (t >= 0) item.minutes = (int) Math.round(t);
                }
                if (item.minutes < 0) continue;
                item.distance = e.optInt("busToStationDistance", -1);
                // 官方口径：车真在站上时 busToStationTips 给「已到站」、距离 0；
                // 还差一小段时给「即将到站」配「小于1分钟」。两者必须分开
                item.arrived = tips.contains("已到站")
                        || (count == 0 && item.distance == 0);
                rt.etas.add(item);
            }
        }
        Collections.sort(rt.etas, (a, b) -> Integer.compare(a.minutes, b.minutes));
        // 有真车就不需要线路级状态了（比如列表里既有真车又混了一条「非运营时间」），
        // 状态只在线路确实没车可用时才生效
        if (!rt.etas.isEmpty()) rt.statusTips = "";
        return rt;
    }

    /** CMD=114 线路搜索。同一线路的两个方向合成一条，返回的 lineId 指向正方向。 */
    public static List<BusModels.Line> searchLines(String cityId, String keyword) throws Exception {
        String city = city(cityId);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("CMD", "114");
        p.put("CITYNAME", city);
        p.put("KEYWORD", keyword);
        p.put("CITYKEY", "");
        JSONObject j = call(p);
        JSONArray arr = firstArray(j, "buslines", "data", "list");

        Map<String, BusModels.Line> uniq = new LinkedHashMap<>();
        Map<String, JSONObject> beanByName = new LinkedHashMap<>();
        if (arr == null) return new ArrayList<>();
        List<BusModels.Line> both = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("lineName", "").trim();
            if (name.isEmpty()) name = o.optString("LINENAME", "").trim();
            if (name.isEmpty()) continue;
            JSONObject bean = o.optJSONObject("routeBean");
            if (bean != null && "3".equals(bean.optString("routeType"))) continue;
            if (name.contains("地铁")) continue;
            if (bean != null) beanByName.put(name, bean);

            String up = o.optString("upperOrDown", "");
            if (up.isEmpty()) up = "1";
            BusModels.Line line = new BusModels.Line();
            line.name = name;
            line.lineNo = name;
            line.direction = "1".equals(up) ? 0 : 1;
            line.lineId = lineId(city, name, line.direction);
            line.startName = firstNonEmpty(o.optString("from"), o.optString("startStation"));
            line.endName = firstNonEmpty(o.optString("to"), o.optString("endStation"));
            line.price = o.optString("comments", "");
            both.add(line);
        }
        for (BusModels.Line l : both) {
            BusModels.Line old = uniq.get(l.name);
            if (old == null) {
                uniq.put(l.name, l);
            } else if (old.direction != 0 && l.direction == 0) {
                uniq.put(l.name, l);
            }
        }
        // 首末班要从 routeBean 的方向数组里取，搜索结果本身常常没有
        for (BusModels.Line l : uniq.values()) {
            JSONObject bean = beanByName.get(l.name);
            if (bean == null) continue;
            String key = l.direction == 0 ? "seutimes" : "sedtimes";
            JSONArray wins = bean.optJSONArray(key);
            JSONObject w = wins == null ? null : wins.optJSONObject(0);
            if (w != null) {
                if (l.firstTime.isEmpty()) l.firstTime = w.optString("first", "");
                if (l.lastTime.isEmpty()) l.lastTime = w.optString("last", "");
            }
        }
        return new ArrayList<>(uniq.values());
    }

    /** CMD=110 站点搜索。 */
    public static List<BusModels.Station> searchStops(String cityId, String keyword) throws Exception {
        String city = city(cityId);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("CMD", "110");
        p.put("CITYNAME", city);
        p.put("KEYWORD", keyword);
        p.put("CITYKEY", "");
        JSONObject j = call(p);
        JSONArray arr = j.optJSONArray("busstations");
        List<BusModels.Station> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("stationName", "").trim();
            if (name.isEmpty() || name.contains("地铁")) continue;
            boolean dup = false;
            for (BusModels.Station e : out) {
                if (e.name.equals(name)) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            BusModels.Station s = newStation(city, name);
            double[] w = gcj2wgs(o.optDouble("station_lat", 0), o.optDouble("station_lon", 0));
            s.lat = w[0];
            s.lng = w[1];
            out.add(s);
        }
        return out;
    }

    /** CMD=207：首站发车时刻表。要先用 103 拿到 routeId。 */
    public static BusModels.Timetable timetable(String cityId, String lineName, int dir)
            throws Exception {
        String city = city(cityId);
        RouteInfo info = route(city, lineName, dir);
        BusModels.Timetable tt = new BusModels.Timetable();
        tt.lineName = lineName;
        tt.startName = info.stops.isEmpty() ? "" : info.stops.get(0).name;
        tt.endName = info.stops.isEmpty() ? "" : info.stops.get(info.stops.size() - 1).name;
        tt.firstDep = info.firstTime;
        tt.lastDep = info.lastTime;
        if (info.routeId.isEmpty()) {
            tt.tip = "该线路暂不提供首站发车时刻表";
            return tt;
        }

        Map<String, String> p = new LinkedHashMap<>();
        p.put("CMD", "207");
        p.put("CITYNAME", city);
        p.put("DIRECTION", dir == 0 ? "1" : "2");
        p.put("ROUTEID", info.routeId);
        p.put("CITYKEY", "");
        JSONObject j = call(p);

        boolean planned = "1".equals(j.optString("special", "0"));
        tt.depTitle = planned ? "计划发车时刻" : "历史推算班次";
        tt.tip = planned ? "本时刻表为计划发车时间，可能临时调整，仅供参考"
                : "本时刻表数据根据历史数据计算得出，仅供参考";

        JSONArray groups = j.optJSONArray("list");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null) continue;
                BusModels.TtSegment seg = new BusModels.TtSegment();
                String[] span = splitGroup(g.optString("group", ""));
                seg.from = span[0];
                seg.to = span[1];
                int peak = g.optInt("peak", 0);
                if (peak == 1) seg.tips = "早高峰";
                else if (peak == 2) seg.tips = "晚高峰";
                JSONArray times = g.optJSONArray("times");
                if (times != null) {
                    for (int k = 0; k < times.length(); k++) {
                        JSONObject t = times.optJSONObject(k);
                        if (t == null) continue;
                        // isDelete=1 是临时取消的班次，界面没有删除线样式，直接不列
                        if (t.optInt("isDelete", 0) == 1) continue;
                        String clock = normalizeClock(t.optString("time", ""));
                        if (!clock.isEmpty()) seg.times.add(clock);
                    }
                }
                BusModels.sortByClock(seg.times);
                if (!seg.times.isEmpty() || !seg.from.isEmpty()) tt.today.add(seg);
            }
        }
        List<String> all = tt.todayTimes();
        if (!all.isEmpty()) {
            tt.firstDep = all.get(0);
            tt.lastDep = all.get(all.size() - 1);
        }
        String next = normalizeClock(j.optString("nextTime", ""));
        if (next.isEmpty()) next = firstAfter(all, nowHHMM());
        tt.nextDep = next;
        tt.nextFrom = next.isEmpty() ? "" : (planned ? "计划班次" : "历史推算");
        if (all.size() > 1) {
            int span = BusModels.minutesOf(all.get(all.size() - 1)) - BusModels.minutesOf(all.get(0));
            if (span > 0) tt.interval = Math.max(1, Math.round(span / (float) (all.size() - 1)));
        }
        if (tt.empty()) tt.tip = "该线路暂不提供首站发车时刻表";
        return tt;
    }

    // ──────────────────────────── id 与文案 ────────────────────────────

    /** 城市名规范化：老版本存的车来了编号（056）一律换回默认城市。 */
    public static String city(String cityId) {
        if (cityId == null) return DEFAULT_CITY;
        String v = cityId.trim();
        if (v.isEmpty()) return DEFAULT_CITY;
        if (v.endsWith("市") || v.endsWith("县") || v.endsWith("区")) return v;
        boolean digits = true;
        for (int i = 0; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) {
                digits = false;
                break;
            }
        }
        if (digits) return DEFAULT_CITY;
        return v + "市";
    }

    /** 列表里显示的城市名去掉「市」，和车来了那套观感一致。 */
    public static String shortCityName(String name) {
        if (name == null) return "";
        String v = name.trim();
        if (v.endsWith("市") && v.length() > 2) return v.substring(0, v.length() - 1);
        return v;
    }

    public static String lineId(String city, String lineName, int dir) {
        return P_LINE + city + "|" + lineName + "|" + (dir == 0 ? "1" : "2");
    }

    /** 解析线路 id，返回 {城市, 线路名, "1"/"2"}；不是本数据源的格式返回 null。 */
    public static String[] parseLineId(String lineId) {
        if (lineId == null || !lineId.startsWith(P_LINE)) return null;
        String[] seg = lineId.substring(P_LINE.length()).split("\\|", -1);
        if (seg.length != 3) return null;
        if (seg[0].isEmpty() || seg[1].isEmpty()) return null;
        return seg;
    }

    public static String stopId(String city, String stationName) {
        return P_LINE + city + "|" + stationName;
    }

    /** 从站点 id 里取站名；不是本数据源格式就当整串是站名。 */
    static String stationNameOf(String stationId) {
        if (stationId == null) return "";
        if (!stationId.startsWith(P_LINE)) return stationId.trim();
        String[] seg = stationId.substring(P_LINE.length()).split("\\|", -1);
        if (seg.length != 2) return "";
        return seg[1].trim();
    }

    static boolean sameSource(String lineId) {
        return parseLineId(lineId) != null;
    }

    // ──────────────────────────── 坐标系 ────────────────────────────

    /**
     * 掌上公交的经纬度是 GCJ-02（火星坐标），手机定位给的是 WGS-84。
     *
     * <p>直接把 WGS 传过去，站点会整体偏 400~500 米 —— 表现出来就是
     * 「同样的位置，首页显示的最远/最近站点全变了」。所以：请求前把用户坐标转成
     * GCJ-02，解析回来再把站点和车辆的坐标转回 WGS-84，站内距离、最近站判断才准。
     */
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    /** WGS-84 → GCJ-02。 */
    public static double[] wgs2gcj(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = 1 - EE * Math.sin(radLat) * Math.sin(radLat);
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lng + dLng};
    }

    /** GCJ-02 → WGS-84（一次反解，误差不到 1 米，画站线够用）。 */
    public static double[] gcj2wgs(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double[] g = wgs2gcj(lat, lng);
        return new double[]{lat - (g[0] - lat), lng - (g[1] - lng)};
    }

    private static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        r += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return r;
    }

    private static double transformLng(double x, double y) {
        double r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        r += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return r;
    }

    // ──────────────────────────── 解析辅助 ────────────────────────────

    private static BusModels.Station newStation(String city, String name) {
        BusModels.Station s = new BusModels.Station();
        s.name = name;
        s.id = stopId(city, name);
        s.physicalId = s.id;
        s.namesakeId = s.id;
        return s;
    }

    /** 105 的 stationOrder 恒为 0，真正能用的站序在 stationID（0 起，站序 = +1）。 */
    private static int orderOf(JSONObject o) {
        int order = o.optInt("stationOrder", 0);
        if (order > 0) return order;
        String sid = o.optString("stationID", "").trim();
        if (sid.isEmpty()) return 0;
        try {
            int v = Integer.parseInt(sid);
            return v >= 0 ? v + 1 : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean hasRealOf(JSONObject bean) {
        if (bean == null) return true;
        if (!bean.has("hasReal")) return true;
        return bean.optInt("hasReal", 1) != 0;
    }

    /** 「7站 [开往 xxx]」→「7站」；「1站 [到达 xxx]」→「1站」。 */
    static String cleanNearText(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        int cut = v.indexOf('[');
        int cut2 = v.indexOf('【');
        if (cut < 0 || (cut2 >= 0 && cut2 < cut)) cut = cut2;
        if (cut >= 0) v = v.substring(0, cut);
        v = v.trim();
        if (v.endsWith("到达")) v = v.substring(0, v.length() - 2).trim();
        return v;
    }

    private static boolean containsArriveText(String text) {
        return text.contains("即将到站") || text.contains("已到站") || text.contains("正在进站");
    }

    /** 「7站」→ 7；「即将到站」→ 0；其余 -1。 */
    static int stopsOf(String text) {
        if (text == null || text.isEmpty()) return -1;
        if (text.contains("即将到站") || text.contains("已到站")) return 0;
        int i = text.indexOf('站');
        if (i <= 0) return -1;
        String head = text.substring(0, i).trim();
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 「7分钟」→ 7；「小于1分钟」→ 0；「14:05 到达」→ -1。
     *
     * <p>远途线路的文案是「1小时44分钟」，只抓「分」前面的数字会读成 44 分钟，
     * 白白丢掉整段小时（官方小程序显示 1小时44分，我们显示 44 分钟就是这么来的）。
     * 所以小时段和分钟段分别取，再相加。
     */
    static int minutesOfText(String text) {
        if (text == null) return -1;
        String v = text.trim();
        if (v.isEmpty()) return -1;
        if (v.contains("小于1分钟") || v.contains("小于一分钟")) return 0;

        int hours = -1;
        int hUnit = v.indexOf("小时");
        if (hUnit < 0) hUnit = v.indexOf("时");
        if (hUnit > 0) {
            int hs = hUnit;
            while (hs > 0 && (Character.isDigit(v.charAt(hs - 1)) || v.charAt(hs - 1) == '.')) {
                hs--;
            }
            if (hs < hUnit) hours = parseIntQuiet(v.substring(hs, hUnit));
        }

        int mins = -1;
        int unit = v.indexOf("分钟");
        if (unit < 0) unit = v.indexOf("分");
        if (unit >= 0) {
            int start = unit;
            while (start > 0 && (Character.isDigit(v.charAt(start - 1)) || v.charAt(start - 1) == '.')) {
                start--;
            }
            if (start < unit) mins = parseIntQuiet(v.substring(start, unit));
        }

        if (hours < 0 && mins < 0) return -1;
        return Math.max(hours, 0) * 60 + Math.max(mins, 0);
    }

    private static int parseIntQuiet(String raw) {
        try {
            return (int) Math.round(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 「07:43」→ 原样；「7:5」→「07:05」；解析不出返回空串。 */
    static String normalizeClock(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.isEmpty()) return "";
        int i = v.indexOf(':');
        if (i <= 0) return "";
        try {
            int h = Integer.parseInt(v.substring(0, i).trim());
            int m = Integer.parseInt(v.substring(i + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return "";
            return pad(h) + ":" + pad(m);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String pad(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String[] splitGroup(String group) {
        String v = group == null ? "" : group.trim();
        if (v.isEmpty()) return new String[]{"", ""};
        int i = v.indexOf('~');
        if (i < 0) i = v.indexOf('～');
        if (i < 0) return new String[]{normalizeClock(v), ""};
        return new String[]{normalizeClock(v.substring(0, i)), normalizeClock(v.substring(i + 1))};
    }

    private static String firstAfter(List<String> sorted, String now) {
        int n = BusModels.minutesOf(now);
        if (n < 0) return "";
        for (String t : sorted) {
            if (BusModels.minutesOf(t) >= n) return t;
        }
        return "";
    }

    /** 现在几点（HH:mm），北京时区。 */
    static String nowHHMM() {
        java.util.Calendar c = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return pad(c.get(java.util.Calendar.HOUR_OF_DAY)) + ":" + pad(c.get(java.util.Calendar.MINUTE));
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return b == null ? "" : b.trim();
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                best = i;
                break;
            }
        }
        return best;
    }

    private static JSONArray firstArray(JSONObject j, String... keys) {
        for (String k : keys) {
            JSONArray arr = j.optJSONArray(k);
            if (arr != null) return arr;
        }
        return null;
    }

    private static Map<String, String> single(String k, String v) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(k, v);
        return p;
    }

    static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.6f", v);
    }

    // ──────────────────────────── 结构 ────────────────────────────

    /** 103 的解析结果（带缓存）。 */
    public static final class RouteInfo {
        public String city = "";
        public String lineName = "";
        public int direction = 0;
        public String routeId = "";
        public String firstTime = "";
        public String lastTime = "";
        public String price = "";
        public long atMillis = 0;
        public final List<BusModels.Stop> stops = new ArrayList<>();
    }

    /** 104 里的一辆在途车。 */
    public static final class Bus {
        public String number = "";
        /** 0 起：正开往第 index+1 站 */
        public int index = -1;
        public String statusType = "";
        public String stationName = "";
        public double lat;
        public double lng;
    }

    /** 104 里「某辆车到请求那一站」的预测。 */
    public static final class Eta {
        public String busNumber = "";
        /** 还有几站；0 = 车已在站上或即将到站，看 {@link #arrived} 区分 */
        public int count = -1;
        /** 还有几分钟 */
        public int minutes = -1;
        public int distance = -1;
        /** 车真的在站上（上游 busToStationTips 明说「已到站」，或 0 站 0 米） */
        public boolean arrived = false;
    }

    /** 104 的整体结果。 */
    public static final class Realtime {
        public boolean hasReal = true;
        public int runState = 0;
        public String planTime = "";
        /** 官方给的线路级状态（如「非运营时间」），只在没有任何可用车辆时生效 */
        public String statusTips = "";
        public final List<Bus> buses = new ArrayList<>();
        public final List<Eta> etas = new ArrayList<>();

        /** 最快的一辆车。 */
        public Eta first() {
            return etas.isEmpty() ? null : etas.get(0);
        }
    }

    // ──────────────────────────── HTTP ────────────────────────────

    static JSONObject call(Map<String, String> params) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        byte[] body = sb.toString().getBytes("UTF-8");

        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Origin", ORIGIN);
            c.setRequestProperty("Referer", ORIGIN + "/");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");

            OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) throw new IOException("掌上公交无响应 HTTP " + code);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) > 0) buf.write(chunk, 0, n);
            is.close();

            String text = buf.toString("UTF-8");
            int start = text.indexOf('{');
            if (start < 0) throw new IOException("掌上公交响应异常");
            JSONObject json = new JSONObject(text.substring(start, jsonEnd(text, start)));
            String status = json.optString("status", "");
            if (!"1".equals(status)) {
                throw new IOException("掌上公交返回 status=" + status
                        + " msg=" + json.optString("msg", ""));
            }
            return json;
        } finally {
            c.disconnect();
        }
    }

    /** 从 start 那个 '{' 找到配对的 '}'（字符串里的括号不算）。 */
    private static int jsonEnd(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') inStr = true;
            else if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return s.length();
    }
}
