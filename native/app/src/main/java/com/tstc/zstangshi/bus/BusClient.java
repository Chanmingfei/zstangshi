package com.tstc.zstangshi.bus;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 实时公交数据客户端：直连车来了（Chelaile）微信小程序协议。
 *
 * <p>协议细节移植自 {@code Trackr}（github.com/5151561/Trackr，MIT）的
 * {@code core/network/.../ChelaileApiClient.kt} 与 {@code ChelaileCrypto.kt}：
 * 请求参数按插入顺序拼 {@code "k"="v"&...} 加盐求 MD5 作为 {@code cryptoSign}，
 * 响应外层是 {@code **YGKJ{"jsonr":{"data":{"encryptResult":"..."}}}}，
 * 用固定密钥做 AES/ECB/PKCS5 解密后才是真正的业务 JSON。
 *
 * <p>本项目不引任何第三方库：HTTP 用 {@link HttpURLConnection}、
 * 摘要用 {@link MessageDigest}、解密用 {@link Cipher}、JSON 用系统 {@code org.json}。
 * 全部方法都是阻塞的，调用方负责放到后台线程。
 */
public final class BusClient {

    /** 车来了协议里的固定盐，签名用。 */
    private static final String SIGN_SALT = "qwihrnbtmj";
    /** 微信小程序通道密钥（ASCII 32 字节 → AES-256）。 */
    private static final String KEY_WX = "FF32AE65FBFD19414EAAFF6291A54B42";
    /** H5 通道备用密钥。 */
    private static final String KEY_H5 = "422556651C7F7B2B5C266EED06068230";

    private static final String BASE_URL = "https://web.chelaile.net.cn/api";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781"
            + "(0x6700143B) NetType/WIFI MiniProgramEnv/Windows WindowsWechat/WMPF";
    private static final String REFERER = "https://servicewechat.com/wx71d589ea01ce3321/814/page-frame.html";

    /** 默认城市：唐山。车来了城市编号 056。 */
    public static final String DEFAULT_CITY_ID = "056";
    public static final String DEFAULT_CITY_NAME = "唐山";

    private BusClient() {
    }

    // ──────────────────────────── 对外接口 ────────────────────────────

    /** 支持实时公交的城市。失败抛异常，由调用方兜底成「当前城市」。 */
    public static List<BusModels.City> cities() throws Exception {
        // 城市列表走 web 站点的明文接口，不在 /api 下、也不需要签名
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : base().entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        String raw = httpGet("https://web.chelaile.net.cn/wwd/ncitylist?" + q);
        int start = raw.indexOf('{');
        if (start < 0) throw new java.io.IOException("城市列表响应异常");
        JSONObject env = new JSONObject(raw.substring(start, jsonEnd(raw, start)));
        JSONObject data = env.optJSONObject("data");
        if (data == null) data = env;
        JSONArray arr = data.optJSONArray("cityList");
        List<BusModels.City> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            String name = c.optString("cityName");
            String id = c.optString("cityId");
            if (name.isEmpty() || id.isEmpty()) continue;
            out.add(new BusModels.City(id, name));
        }
        return out;
    }

    /** 附近站点（带每条线的到站）。limit 控制返回条数。 */
    public static List<BusModels.Station> nearby(String cityId, double lat, double lng, int limit)
            throws Exception {
        Map<String, String> p = base();
        p.put("cityId", cityId);
        p.put("localCityId", "undefined");
        p.put("lat", fmt(lat));
        p.put("lng", fmt(lng));
        p.put("geo_lat", fmt(lat));
        p.put("geo_lng", fmt(lng));
        p.put("type", "5");
        p.put("permission", "0");

        JSONObject data = call("/bus/stop!encryptedHomePage.action", p);
        JSONArray arr = data.optJSONArray("nearSts");
        List<BusModels.Station> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && out.size() < limit; i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            BusModels.Station st = new BusModels.Station();
            st.id = s.optString("sId");
            st.name = s.optString("sn");
            st.physicalId = s.optString("physicalStId");
            st.namesakeId = s.optString("namesakeStId");
            st.lat = s.optDouble("lat", 0);
            st.lng = s.optDouble("lng", 0);
            st.distanceMeters = s.has("distance") ? s.optInt("distance", -1) : -1;
            readArrivals(s.optJSONArray("lines"), st.arrivals);
            if (st.name.isEmpty()) continue;
            out.add(st);
        }
        return out;
    }

    /** 站点详情：一个物理站台下的全部线路与到站。 */
    public static BusModels.Station stopDetail(String cityId, String physicalId, String namesakeId,
                                              String stationId, double lat, double lng) throws Exception {
        Map<String, String> p = base();
        p.put("cityId", cityId);
        p.put("localCityId", cityId);
        p.put("physicalStId", physicalId == null ? "" : physicalId);
        p.put("namesakeStId", namesakeId == null ? "" : namesakeId);
        p.put("firstLineId", "");
        p.put("stationId", stationId == null ? "" : stationId);
        String la = lat > 0 ? fmt(lat) : "";
        String ln = lng > 0 ? fmt(lng) : "";
        p.put("lat", la);
        p.put("lng", ln);
        p.put("geo_lat", la);
        p.put("geo_lng", ln);
        p.put("actionState", "1");
        p.put("permission", "0");

        JSONObject data = call("/bus/stop!encryptedPhyStnDetail.action", p);
        JSONArray list = data.optJSONArray("stationList");

        BusModels.Station out = new BusModels.Station();
        out.id = stationId == null ? "" : stationId;
        out.physicalId = physicalId == null ? "" : physicalId;
        out.namesakeId = namesakeId == null ? "" : namesakeId;
        // 站点自己的坐标从响应里取；传进来的 lat/lng 是用户位置，别拿来当站点位置，
        // 否则回填列表时算出来的距离会变成 0
        out.lat = 0;
        out.lng = 0;

        List<BusModels.Arrival> merged = new ArrayList<>();
        List<String> seenPlatform = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject s = list.optJSONObject(i);
                if (s == null) continue;
                if (out.name.isEmpty()) out.name = s.optString("sn");
                if (out.id.isEmpty()) out.id = s.optString("sId");
                if (out.physicalId.isEmpty()) out.physicalId = s.optString("physicalStId");
                if (out.namesakeId.isEmpty()) out.namesakeId = s.optString("namesakeStId");
                if (out.lat == 0) out.lat = s.optDouble("lat", 0);
                if (out.lng == 0) out.lng = s.optDouble("lng", 0);
                if (out.distanceMeters < 0 && s.has("distance")) out.distanceMeters = s.optInt("distance", -1);

                // 每个站台记下来做切换；只有被请求的那个站台会带完整到站数据
                String pid = s.optString("physicalStId");
                if (!pid.isEmpty() && !seenPlatform.contains(pid)) {
                    seenPlatform.add(pid);
                    out.platforms.add(new BusModels.Platform(pid, s.optString("sn")));
                }
                readArrivals(s.optJSONArray("lines"), merged);
            }
        }
        out.arrivals.addAll(dedupeAndSort(merged));
        return out;
    }

    /**
     * 「已到站」只认「还剩 0 分钟」。
     *
     * <p>stnStates 里 {@code state=1}（车在站上）有时配着 {@code travelTime=60}，
     * 照 state 写「已到站」，点进线路详情却是「1 分钟 · 164 米」——同一个站
     * 两处说法打架。这里统一按秒数折算：能取整成 0 分钟才算到站；上游连秒数
     * 都没给时，才退回 state / 站数去判断。
     */
    private static boolean arrivedOf(int minutes, int secs, int state, int stops) {
        if (secs >= 0) return minutes == 0;
        return state == 1 || stops == 0;
    }

    /** 同一线路可能在多条占位里重复出现，按 lineId 去重，留下到站信息最好的那条。 */
    private static List<BusModels.Arrival> dedupeAndSort(List<BusModels.Arrival> in) {
        Map<String, BusModels.Arrival> best = new LinkedHashMap<>();
        for (BusModels.Arrival a : in) {
            BusModels.Arrival cur = best.get(a.lineId);
            if (cur == null || rank(a) < rank(cur)) best.put(a.lineId, a);
        }
        List<BusModels.Arrival> out = new ArrayList<>(best.values());
        out.sort((x, y) -> {
            int rx = rank(x), ry = rank(y);
            if (rx != ry) return rx - ry;
            return x.minutes - y.minutes;
        });
        return out;
    }

    /** 0 = 已到站，1 = 有分钟数，2 = 等待发车/末班已过，3 = 完全没数据。 */
    private static int rank(BusModels.Arrival a) {
        if (a.arrived) return 0;
        if (a.minutes >= 0) return 1;
        if (!a.status.isEmpty()) return 2;
        return 3;
    }

    /**
     * 关键字搜索：站点 + 线路一起搜（中文站名、线路号如「17」「17路」都行）。
     *
     * <p>上游这个接口一次只回前 3 个站点和前 10 条线路，同时把真实命中数放在
     * {@code stationCount} / {@code lineCount} 里。线路可以用 {@link #searchMore}
     * 再要全量，站点没有更长的列表可翻，界面据此决定「还能不能继续加载」。
     */
    public static BusModels.SearchHit search(String cityId, String keyword, double lat, double lng)
            throws Exception {
        BusModels.SearchHit hit = new BusModels.SearchHit();
        JSONObject result = searchResult(cityId, keyword, lat, lng, false);
        if (result == null) return hit;

        hit.lineCount = result.optInt("lineCount", 0);
        hit.stationCount = result.optInt("stationCount", 0);
        readStations(result, hit.stations);
        hit.lines.addAll(readLines(result));
        return hit;
    }

    /**
     * 搜索的「还有更多」：同一个接口带 {@code more=true}，一次给全量线路
     * （实测「路」29 条线路 → 56 条正反方向记录，「1」43 条 → 84 条）。
     *
     * <p>代价是这种模式下上游不再回站点，所以只在用户已经清完第一屏时才调用。
     */
    public static List<BusModels.Line> searchMore(String cityId, String keyword, double lat, double lng)
            throws Exception {
        JSONObject result = searchResult(cityId, keyword, lat, lng, true);
        if (result == null) return new ArrayList<>();
        return readLines(result);
    }

    private static JSONObject searchResult(String cityId, String keyword, double lat, double lng,
                                           boolean more) throws Exception {
        Map<String, String> p = base();
        p.put("cityId", cityId);
        p.put("localCityId", cityId);
        p.put("key", keyword);
        p.put("supportPhyStn", "true");
        if (more) p.put("more", "true");
        if (lat > 0 && lng > 0) {
            p.put("lat", fmt(lat));
            p.put("lng", fmt(lng));
            p.put("geo_lat", fmt(lat));
            p.put("geo_lng", fmt(lng));
        }
        return call("/bus/query!nSearch.action", p).optJSONObject("result");
    }

    /**
     * 上游把一条线的正反方向当两条记录返回（同名、同 lineNo）。搜索结果里合成一行：
     * 否则一屏 12 条其实只有 6 条线，翻五页全是成对的重复。方向在详情页里换，那里本来就是原地换向。
     */
    private static List<BusModels.Line> readLines(JSONObject result) {
        Map<String, BusModels.Line> uniq = new LinkedHashMap<>();
        JSONArray lines = result.optJSONArray("lines");
        if (lines == null) return new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) {
            JSONObject l = lines.optJSONObject(i);
            if (l == null) continue;
            // 纯地铁线路没有实时在途数据，不列
            if (l.optInt("subwayV2", 0) == 1) continue;
            BusModels.Line line = new BusModels.Line();
            line.lineId = l.optString("lineId");
            line.name = l.optString("name");
            if (line.name.isEmpty()) line.name = l.optString("lineNo");
            line.lineNo = l.optString("lineNo");
            line.direction = l.optInt("direction", 0);
            line.startName = l.optString("startSn");
            line.endName = l.optString("endSn");
            line.firstTime = l.optString("firstTime");
            line.lastTime = l.optString("lastTime");
            if (line.lineId.isEmpty() || line.name.isEmpty()) continue;

            String key = line.lineNo.isEmpty() ? line.name : line.lineNo;
            BusModels.Line old = uniq.get(key);
            // 留正方向那一行（direction=0）；只有反向时才先占位
            if (old == null || (old.direction != 0 && line.direction == 0)) {
                uniq.put(key, line);
            }
        }
        return new ArrayList<>(uniq.values());
    }

    /** 搜索结果里的站点自带经纬度和距离，可以直接点进站点详情。 */
    private static void readStations(JSONObject result, List<BusModels.Station> out) {
        JSONArray arr = result.optJSONArray("stations");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            if (s.optInt("subwayV2", 0) == 1) continue;
            BusModels.Station st = new BusModels.Station();
            st.name = s.optString("sn").trim();
            st.id = s.optString("sId");
            st.physicalId = s.optString("physicalStId");
            st.namesakeId = s.optString("namesakeStId");
            st.lat = s.optDouble("lat", 0);
            st.lng = s.optDouble("lng", 0);
            st.distanceMeters = s.has("distance") ? s.optInt("distance", -1) : -1;
            if (st.name.isEmpty() || st.id.isEmpty()) continue;
            out.add(st);
        }
    }

    /**
     * 线路详情：站表 + 在途车辆。
     *
     * @param targetOrder 候车站的站序（1 基），>0 时会额外请求一次车辆预测，用于把
     *                    「最近一辆车在哪一站」标到站表上；传 0 也能出站表。
     */
    public static BusModels.LineDetail lineDetail(String cityId, String lineId, int targetOrder,
                                                 String stationId, double lat, double lng) throws Exception {
        Map<String, String> p = base();
        p.put("cityId", cityId);
        p.put("localCityId", cityId);
        p.put("lineId", lineId);
        String la = lat > 0 ? fmt(lat) : "";
        String ln = lng > 0 ? fmt(lng) : "";
        p.put("lat", la);
        p.put("lng", ln);
        p.put("geo_lat", la);
        p.put("geo_lng", ln);

        JSONObject data = call("/bus/line!encryptedLineDetail.action", p);

        BusModels.LineDetail d = new BusModels.LineDetail();
        JSONObject lo = data.optJSONObject("line");
        if (lo != null) {
            d.line.lineId = lineId;
            d.line.name = lo.optString("name");
            if (d.line.name.isEmpty()) d.line.name = lo.optString("lineNo");
            d.line.lineNo = lo.optString("lineNo");
            d.line.direction = lo.optInt("direction", 0);
            d.line.startName = lo.optString("startSn");
            d.line.endName = lo.optString("endSn");
            d.line.firstTime = lo.optString("firstTime");
            d.line.lastTime = lo.optString("lastTime");
            d.line.price = lo.optString("price");
            d.line.stationsNum = lo.optInt("stationsNum", 0);
        }
        d.status = data.optString("depDesc");
        d.nextDeparture = data.optString("preArrivalTime");

        JSONArray others = data.optJSONArray("otherlines");
        JSONObject rev = others == null ? null : others.optJSONObject(0);
        if (rev != null) {
            d.reverseId = rev.optString("lineId");
            d.reverseStart = rev.optString("startSn");
            d.reverseEnd = rev.optString("endSn");
        }

        JSONArray sts = data.optJSONArray("stations");
        if (sts != null) {
            for (int i = 0; i < sts.length(); i++) {
                JSONObject s = sts.optJSONObject(i);
                if (s == null) continue;
                BusModels.Stop stop = new BusModels.Stop();
                stop.id = s.optString("sId");
                stop.physicalId = s.optString("physicalStId");
                stop.name = s.optString("sn");
                // 站序一律用「在 stations 数组里的位置」。上游对「虚拟站组」会把一
                // 串真实站点塞进一条 order=999 的记录里（sId 形如 999$13$4），而且组
                // 后面站点的 order 还会往回退（组只占一个 order）。直接用 order 会
                // 出现「13-1、13-2…」这种编号，和「全线 39 站」对不上。
                // 数组本身就是行车顺序，位置即站序；buses[].order 也是这个口径。
                stop.order = i + 1;
                stop.orderLabel = String.valueOf(i + 1);
                stop.lat = s.optDouble("wgsLat", 0);
                stop.lng = s.optDouble("wgsLng", 0);
                d.stops.add(stop);
            }
        }

        // 在途车辆：上游每辆车都带真实经纬度和 order（正开往第 order 站），
        // 用它到上一站的距离比例算出「在两站之间的什么位置」。
        JSONArray buses = data.optJSONArray("buses");
        if (buses != null) {
            for (int i = 0; i < buses.length(); i++) {
                JSONObject b = buses.optJSONObject(i);
                if (b == null) continue;
                int order = b.optInt("order", 0);
                if (order <= 0) continue;
                BusModels.Stop cur = stopByOrder(d.stops, order);
                if (cur == null) continue;
                BusModels.Stop prev = stopByOrder(d.stops, order - 1);
                double blat = b.optDouble("lat", 0), blng = b.optDouble("lng", 0);
                double ratio = 1;
                if (prev != null && blat != 0 && blng != 0
                        && prev.lat != 0 && prev.lng != 0 && cur.lat != 0 && cur.lng != 0) {
                    double seg = distanceMeters(prev.lat, prev.lng, cur.lat, cur.lng);
                    double toPrev = distanceMeters(prev.lat, prev.lng, blat, blng);
                    if (seg > 1) ratio = Math.max(0, Math.min(1, toPrev / seg));
                }
                // 同一段可能有两辆车，取最靠前的那辆画
                if (ratio > cur.busRatio) cur.busRatio = ratio;
                d.busCount++;
            }
            for (BusModels.Stop s : d.stops) {
                if (s.busRatio >= 0) s.busHere = s.busRatio >= 0.9;
            }
        }

        // ── 候车站：进来时指定的那站，没指定就用离用户最近的那站 ──
        BusModels.Stop wait = null;
        // 指定了站点 id 就按 id 认站（虚拟站组的 order 都是 999，只按站序会认错站）
        if (!stationId.isEmpty()) {
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
        // 用户在线路详情里点了别的站，最近站的标注也不该跟着跑。
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
            // 候车站确定后就去查「车到这一站还有多久」——不管有没有定位，
            // 只要用户是从某个站点点进来的，这个数就该算出来
            fillEta(d, cityId, lineId, wait, stationId, lat, lng);
        }
        return d;
    }

    /**
     * 真实站序：虚拟站组（order=999，sId 形如 999$13$4）还原成它在整条线上的位置。
     *
     * 上游把一串连续的真实站点编码成「组」：{@code G} 是组前面最后一个站的 order，
     * {@code N} 是组内第几个，所以真实站序 = G + N（999$13$4 是第 17 站）。
     * 只取 G 会把这组里的 7 个站算成同一站，预测的「还剩几站」和车标位置都会错。
     */
    static int effectiveOrder(int order, String sId) {
        if (order < 999) return order;
        String[] seg = (sId == null ? "" : sId).split("\\$");
        if (seg.length >= 3) {
            try {
                return Integer.parseInt(seg[1]) + Integer.parseInt(seg[2]);
            } catch (NumberFormatException ignored) {
            }
        }
        return order;
    }

    /** 拉一次到「候车站」的车辆预测，填分钟 / 站数 / 距离。失败不影响站表。 */
    private static void fillEta(BusModels.LineDetail d, String cityId, String lineId,
                                BusModels.Stop wait, String stationId, double lat, double lng) {
        Pred pr = predict(cityId, lineId, wait.order, wait.id, lat, lng);
        if (pr == null) return;
        if (pr.secs >= 0) {
            d.etaMinutes = (pr.secs + 59) / 60;
            d.etaStops = pr.stops;
            d.etaDistanceMeters = pr.dist;
        }
        if (d.status.isEmpty()) {
            d.status = pr.depDesc;
            if (d.nextDeparture.isEmpty()) d.nextDeparture = pr.preArrival;
        }
    }

    /** 一次车辆预测的结果（最快的车还有多久到、还剩几站、离多少米）。 */
    public static final class Pred {
        public int secs = -1;
        public int stops = -1;
        public int dist = -1;
        public String depDesc = "";
        public String preArrival = "";
    }

    // ───────────────────── 首站发车时刻表 ─────────────────────

    /**
     * 首站发车时刻表（车来了 {@code preStartTimetableNew}）。
     *
     * <p>响应是两块：{@code timetable} 是「今日发车预测」，上游只给还没发的那几班；
     * {@code yesterdayTimetable} 是「昨日实际班次」，一整天都全。下一班按
     * 「今日预测 → 昨日实际 → 明日首班」的顺序取：今日预测常常只覆盖未来几小时，
     * 晚一点就没有了，这时候不能空着，昨天同一时刻之后的那班一样有参考价值。
     */
    public static BusModels.Timetable timetable(String cityId, String lineId, int direction,
                                               String lineNo) throws Exception {
        Map<String, String> p = base();
        p.put("cityId", cityId);
        p.put("lineId", lineId);
        p.put("lineNo", lineNo == null ? "" : lineNo);
        p.put("direction", String.valueOf(direction));

        JSONObject data = call("/bus/line!preStartTimetableNew.action", p);
        BusModels.Timetable t = new BusModels.Timetable();

        JSONObject lo = data.optJSONObject("line");
        if (lo != null) {
            t.lineName = lo.optString("name");
            t.startName = lo.optString("startSn");
            t.endName = lo.optString("endSn");
        }

        JSONObject todayBlock = data.optJSONObject("timetable");
        JSONObject yBlock = data.optJSONObject("yesterdayTimetable");
        if (todayBlock != null) {
            t.depTitle = todayBlock.optString("depTitle");
            t.tip = todayBlock.optString("tip");
            t.today.addAll(readTtSegments(todayBlock));
        }
        if (yBlock != null) {
            if (t.depTitle.isEmpty()) t.depTitle = yBlock.optString("depTitle");
            if (t.tip.isEmpty()) t.tip = yBlock.optString("tip");
            t.history.addAll(readTtSegments(yBlock));
        }

        List<String> todayTimes = t.todayTimes();
        List<String> histTimes = t.historyTimes();
        if (!histTimes.isEmpty()) {
            t.firstDep = histTimes.get(0);
            t.lastDep = histTimes.get(histTimes.size() - 1);
        }
        for (BusModels.TtSegment seg : t.today) {
            if (seg.interval > 0) {
                t.interval = seg.interval;
                break;
            }
        }

        // 「现在」用手机本地时间：界面上说的「今天」就是用户手机上的今天
        String now = new java.text.SimpleDateFormat("HH:mm", Locale.US)
                .format(new java.util.Date());
        String next = firstAfter(todayTimes, now);
        if (next != null) {
            t.nextDep = next;
            t.nextFrom = "今日预测";
        } else {
            next = firstAfter(histTimes, now);
            if (next != null) {
                t.nextDep = next;
                t.nextFrom = "昨日实际";
            } else if (!histTimes.isEmpty()) {
                t.nextDep = histTimes.get(0);
                t.nextFrom = "明日首班";
            }
        }
        return t;
    }

    private static List<BusModels.TtSegment> readTtSegments(JSONObject block) {
        List<BusModels.TtSegment> out = new ArrayList<>();
        JSONArray segs = block.optJSONArray("timetable");
        if (segs == null) return out;
        for (int i = 0; i < segs.length(); i++) {
            JSONObject s = segs.optJSONObject(i);
            if (s == null) continue;
            BusModels.TtSegment seg = new BusModels.TtSegment();
            seg.from = s.optString("fTime");
            seg.to = s.optString("eTime");
            seg.tips = s.optString("depIntervalTips");
            seg.interval = s.optInt("depInterval", -1);
            JSONArray sch = s.optJSONArray("schedules");
            if (sch != null) {
                for (int k = 0; k < sch.length(); k++) {
                    // 班次有两种写法：{"time":"07:43"} 和直接给字符串
                    JSONObject o = sch.optJSONObject(k);
                    String time = o != null ? o.optString("time") : sch.optString(k);
                    if (time != null && !time.isEmpty()) seg.times.add(time.trim());
                }
            }
            JSONArray plain = s.optJSONArray("times");
            if (plain != null && seg.times.isEmpty()) {
                for (int k = 0; k < plain.length(); k++) {
                    String time = plain.optString(k);
                    if (!time.isEmpty()) seg.times.add(time.trim());
                }
            }
            BusModels.sortByClock(seg.times);
            out.add(seg);
        }
        return out;
    }

    /** 排好序的时刻表里第一个「不早于 now」的班次；都不早于就返回 null。 */
    private static String firstAfter(List<String> sorted, String now) {
        int n = BusModels.minutesOf(now);
        if (n < 0) return null;
        for (String t : sorted) {
            int m = BusModels.minutesOf(t);
            if (m >= n) return t;
        }
        return null;
    }

    /**
     * 某条线的车到指定站还有多久。
     *
     * 首页和线路详情共用这一个口径 —— 之前首页只读 nearSts 里的 stnStates，
     * 稍远一点的站点上游压根不给，于是首页写「暂无实时」，点进线路详情却算出 14 分钟。
     * 统一走这里，两处数字才对得上。
     */
    public static Pred predict(String cityId, String lineId, int order, String stopId,
                               double lat, double lng) {
        try {
            Map<String, String> p = base();
            p.put("cshow", "busDetail");
            p.put("specail", "0");
            p.put("specialType", "undefined");
            p.put("cityId", cityId);
            p.put("localCityId", cityId);
            p.put("lineId", lineId);
            p.put("targetOrder", String.valueOf(order));
            p.put("specialTargetOrder", String.valueOf(order));
            p.put("stationId", stopId == null ? "" : stopId);
            // 没有定位也要能算：候车站是靠 targetOrder 指定的，坐标只是附带的参考
            String la = lat > 0 ? fmt(lat) : "";
            String ln = lng > 0 ? fmt(lng) : "";
            p.put("lat", la);
            p.put("lng", ln);
            p.put("geo_lat", la);
            p.put("geo_lng", ln);
            p.put("userId", "");
            p.put("h5Id", "");
            p.put("unionId", "");
            p.put("accountId", "");
            p.put("secret", "");

            JSONObject data = call("/bus/line!encryptedBusDetail.action", p);
            Pred out = new Pred();
            out.depDesc = data.optString("depDesc");
            out.preArrival = data.optString("preArrivalTime");

            JSONArray buses = data.optJSONArray("buses");
            if (buses == null) return out;

            int bestSecs = Integer.MAX_VALUE;
            for (int i = 0; i < buses.length(); i++) {
                JSONObject b = buses.optJSONObject(i);
                if (b == null) continue;
                JSONArray travels = b.optJSONArray("travels");
                if (travels == null || travels.length() == 0) continue;
                JSONObject tr = travels.optJSONObject(0);
                if (tr == null) continue;
                int secs = tr.optInt("travelTime", -1);
                if (secs < 0 || secs >= bestSecs) continue;
                bestSecs = secs;
                int busOrder = b.optInt("order", 0);
                // 虚拟站组的 order 是 999，真站序藏在 sId 里（999$30$1 → 30），
                // 直接拿 999 去减会算出「还有 974 站」
                int mine = effectiveOrder(order, stopId);
                out.stops = busOrder > 0 ? Math.max(0, mine - busOrder) : -1;
                int dist = b.optInt("distanceToWaitStn", -1);
                out.dist = dist > 0 ? dist : -1;
            }
            out.secs = bestSecs == Integer.MAX_VALUE ? -1 : bestSecs;
            return out;
        } catch (Exception ignored) {
            // 预测是加分项，失败就只显示站表
            return null;
        }
    }

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
     * <p>首页的 nearSts 只对最近几站算实时，而且同一趟车它给的秒数常常是 -1
     * （只剩站数），点进线路详情却能算出「1 分钟」——两处说的不是一回事。
     * 这里统一改走 {@link #predict}，和线路详情同一个接口、同一个口径。
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
                            // 和线路详情同一个口径：只有 0 分钟才写「已到站」，
                            // 站数归零但还要 1 分钟，就老老实实显示「1 分钟」
                            a.arrived = arrivedOf(a.minutes, pr.secs, 0, pr.stops);
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

    // ──────────────────────────── 协议实现 ────────────────────────────

    private static Map<String, String> base() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("s", "h5");
        p.put("wxs", "wx_app");
        p.put("sign", "1");
        p.put("h5RealData", "1");
        p.put("v", "3.11.28");
        p.put("src", "weixinapp_cx");
        p.put("ctm_mp", "mp_wx");
        p.put("vc", "2");
        p.put("favoriteGray", "1");
        p.put("gpstype", "wgs");
        p.put("geo_type", "wgs");
        p.put("scene", "1256");
        return p;
    }

    /** 车来了签名：按参数插入顺序拼 {@code "k"="v"&...} 再加盐取 MD5 小写。 */
    static String cryptoSign(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append('"').append(e.getKey()).append("\"=\"").append(e.getValue()).append('"');
        }
        sb.append(SIGN_SALT);
        return md5Hex(sb.toString());
    }

    /** 发一次 GET，返回解密后的业务 JSON。 */
    private static JSONObject call(String path, Map<String, String> params) throws Exception {
        Map<String, String> signed = new LinkedHashMap<>(params);
        signed.put("cryptoSign", cryptoSign(params));

        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : signed.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        String raw = httpGet(BASE_URL + path + "?" + q);
        return extract(raw);
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", REFERER);
            c.setRequestProperty("xweb_xhr", "1");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new java.io.IOException("HTTP " + code);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            if (code >= 400) throw new java.io.IOException("HTTP " + code);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 剥掉 {@code **YGKJ} 前后缀，取 JSON 主体；{@code jsonr.data} 里若含
     * {@code encryptResult} 则解密后返回业务 JSON。
     */
    private static JSONObject extract(String raw) throws Exception {
        int start = raw.indexOf('{');
        if (start < 0) throw new java.io.IOException("响应不是 JSON");
        int end = jsonEnd(raw, start);
        JSONObject env = new JSONObject(raw.substring(start, end));

        JSONObject jsonr = env.optJSONObject("jsonr");
        Object data = jsonr == null ? env.opt("data") : jsonr.opt("data");

        if (data instanceof String) {
            String s = ((String) data).trim();
            if (s.isEmpty()) return new JSONObject();
            try {
                return new JSONObject(decrypt(s));
            } catch (Exception e) {
                return new JSONObject(s);
            }
        }
        if (data instanceof JSONObject) {
            JSONObject d = (JSONObject) data;
            String enc = d.optString("encryptResult");
            if (!enc.isEmpty()) return new JSONObject(decrypt(enc));
            return d;
        }
        return jsonr != null ? jsonr : env;
    }

    /** 括号计数找 JSON 结尾（跳过字符串字面量里的花括号）。 */
    private static int jsonEnd(String raw, int start) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        int last = raw.lastIndexOf('}');
        return last > start ? last + 1 : raw.length();
    }

    /** AES/ECB/PKCS5 解密。两把密钥都试，解出来必须是合法 JSON 才算成功。 */
    private static String decrypt(String b64) throws Exception {
        byte[] cipher = Base64.decode(b64.replaceAll("\\s", ""), Base64.DEFAULT);
        String[] keys = {KEY_WX, KEY_H5};
        Exception last = null;
        for (String k : keys) {
            try {
                Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k.getBytes(StandardCharsets.UTF_8), "AES"));
                String text = new String(c.doFinal(cipher), StandardCharsets.UTF_8).trim();
                if (text.startsWith("{") || text.startsWith("[")) return text;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new IllegalStateException("解密失败") : last;
    }

    static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ──────────────────────────── 解析辅助 ────────────────────────────

    /**
     * 解析 {@code lines[]}。每条线的 {@code stnStates} 里 {@code value} 是
     * 「距本站还剩几站」，{@code travelTime} 是秒，按到站先后排序后取前两辆：
     * 第一辆进 {@code minutes/stopsAway}，第二辆进 {@code nextMinutes/nextStopsAway}
     * （对应车来了首页那个「下辆」）。
     */
    private static void readArrivals(JSONArray lines, List<BusModels.Arrival> into) {
        if (lines == null) return;
        for (int i = 0; i < lines.length(); i++) {
            JSONObject li = lines.optJSONObject(i);
            if (li == null) continue;
            JSONObject lo = li.optJSONObject("line");
            if (lo == null) continue;
            if (lo.optInt("subwayV2", 0) == 1) continue;

            BusModels.Arrival a = new BusModels.Arrival();
            a.lineId = lo.optString("lineId");
            a.lineName = lo.optString("name");
            if (a.lineName.isEmpty()) a.lineName = lo.optString("lineNo");
            a.direction = lo.optInt("direction", 0);
            a.startName = lo.optString("startSn");
            a.endName = lo.optString("endSn");
            a.status = lo.optString("shortDesc");
            if (a.status.isEmpty()) a.status = lo.optString("nextOperationTimeDesc");
            if (a.lineName.isEmpty()) continue;
            // 同一站的其他站台会回一批「只有名字、没有 lineId」的占位线路，
            // 它们没有到站数据，混进来就变成一排「开往 — / 暂无车」
            if (a.lineId.isEmpty()) continue;

            // 这个站在该线路上的站序：站点详情和首页的每条 line 都带 targetStation，
            // 用它才能让「从 A 站进线路详情」默认候车站就是 A 站。
            // 虚拟站组的 order 是 999，要用 sId 还原成真实站序，否则预测会算到别的站去。
            JSONObject target = li.optJSONObject("targetStation");
            if (target != null) {
                a.stopId = target.optString("sId");
                a.orderInLine = effectiveOrder(target.optInt("order", 0), a.stopId);
            }

            JSONArray states = li.optJSONArray("stnStates");
            a.realtime = states != null && states.length() > 0;
            List<BusState> cand = new ArrayList<>();
            if (states != null) {
                for (int k = 0; k < states.length(); k++) {
                    JSONObject b = states.optJSONObject(k);
                    if (b == null) continue;
                    int stops = b.optInt("value", -1);
                    int secs = b.optInt("travelTime", -1);
                    int state = b.optInt("state", 0);
                    int dist = b.optInt("distanceToDest", -1);
                    // state == 1 是「已到站」：这种条目有时 value/travelTime 都是 -1，
                    // 但它确实代表有车在站上，不能当成没数据丢掉
                    if (stops < 0 && secs < 0 && state != 1) continue;
                    cand.add(new BusState(stops, secs, dist, state));
                }
            }
            cand.sort((x, y) -> {
                int sx = x.stops >= 0 ? x.stops : 9999;
                int sy = y.stops >= 0 ? y.stops : 9999;
                if (sx != sy) return sx - sy;
                return (x.secs < 0 ? 99999 : x.secs) - (y.secs < 0 ? 99999 : y.secs);
            });

            // 没有到站数据的，等下单独补一次预测；先标上 pending，界面显示「…」
            a.pending = cand.isEmpty() && a.orderInLine > 0;
            if (!cand.isEmpty()) {
                BusState f = cand.get(0);
                a.stopsAway = f.stops;
                a.minutes = f.secs >= 0 ? (f.secs + 59) / 60 : -1;
                a.distanceMeters = f.dist;
                a.arrived = arrivedOf(a.minutes, f.secs, f.state, f.stops);
                if (cand.size() > 1) {
                    BusState s2 = cand.get(1);
                    a.nextStopsAway = s2.stops;
                    a.nextMinutes = s2.secs >= 0 ? (s2.secs + 59) / 60 : -1;
                }
            }
            into.add(a);
        }
    }

    /** stnStates 里一辆车的原始状态。 */
    private static final class BusState {
        final int stops;
        final int secs;
        final int dist;
        final int state;

        BusState(int stops, int secs, int dist, int state) {
            this.stops = stops;
            this.secs = secs;
            this.dist = dist;
            this.state = state;
        }
    }

    /** 把车辆标到站表对应站序上；标到了返回 true。 */
    private static BusModels.Stop stopByOrder(List<BusModels.Stop> stops, int order) {
        for (BusModels.Stop s : stops) {
            if (s.order == order) return s;
        }
        return null;
    }

    /** 坐标固定 6 位小数，避免 Locale 把小数点变成逗号。 */
    private static String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    // ──────────────────── 首页下滑：往外扩一圈找更远的站 ────────────────────

    /**
     * 附近站点接口一次只给最近的 15 个，没有翻页参数，而且**只有最靠前的两三个站**
     * 带实时到站数据。想再往外看，就以用户位置为中心、按 8 个方位把查询点挪到
     * ring×700 米处各查一次，合起来去重；每个新站再单独查一次站点详情，
     * 拿它自己的到站数据和经纬度——距离和到站时间都是真算出来的，不估。
     */
    /** 一轮最多解出多少站的坐标（每个站一次详情请求，别一次打太多）。 */
    private static final int RESOLVE_MAX = 20;
    /** 一轮最多往外给几站，剩下的留给下一轮——列表一段一段长，手感和无限列表一致。 */
    private static final int PAGE_SIZE = 12;
    /** 站点坐标缓存：环形探针一轮会重复探到同一批站，坐标不会变，存下来省请求。 */
    private static final Map<String, double[]> COORD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static List<BusModels.Station> fartherStations(String cityId, double lat, double lng,
                                                          int ring, Set<String> exclude) {
        final int dirs = 8;
        final double radius = 700.0 * Math.max(1, ring);
        final List<BusModels.Station> bucket = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread[] probes = new Thread[dirs];
        for (int i = 0; i < dirs; i++) {
            final double bearing = i * 360.0 / dirs;
            probes[i] = new Thread(() -> {
                try {
                    double[] p = offsetPoint(lat, lng, radius, bearing);
                    bucket.addAll(nearby(cityId, p[0], p[1], 15));
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

        // 附近站点接口不给经纬度（只有服务端算好的 distance），所以每个新站得单独查一次
        // 详情拿坐标——距离、排序、以后回填都得用它。查过的进缓存，下一圈不再问。
        List<BusModels.Station> todo = new ArrayList<>();
        for (BusModels.Station s : uniq.values()) {
            double[] c = COORD_CACHE.get(s.id);
            if (c != null) {
                s.lat = c[0];
                s.lng = c[1];
            } else {
                todo.add(s);
            }
        }
        if (todo.size() > RESOLVE_MAX) todo = new ArrayList<>(todo.subList(0, RESOLVE_MAX));

        final List<BusModels.Station> task = todo;
        final int workers = Math.min(4, task.size());
        Thread[] ts = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> {
                for (int k = idx; k < task.size(); k += workers) {
                    BusModels.Station s = task.get(k);
                    mergeDetail(s, fetchStationDetail(cityId, s, lat, lng));
                    if (s.lat != 0 && s.lng != 0) {
                        if (COORD_CACHE.size() > 600) COORD_CACHE.clear();
                        COORD_CACHE.put(s.id, new double[]{s.lat, s.lng});
                    }
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException ignored) {
                    }
                }
            }, "bus-resolve-" + i);
            ts[i].start();
        }
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException ignored) {
            }
        }

        List<BusModels.Station> keep = new ArrayList<>();
        for (BusModels.Station s : uniq.values()) {
            if (s.lat == 0 || s.lng == 0) continue;      // 拿不到坐标就不硬塞，免得写个假距离
            s.distanceMeters = (int) Math.round(distanceMeters(lat, lng, s.lat, s.lng));
            keep.add(s);
        }
        java.util.Collections.sort(keep, (a, b) ->
                Integer.compare(a.distanceMeters, b.distanceMeters));
        if (keep.size() > PAGE_SIZE) keep = new ArrayList<>(keep.subList(0, PAGE_SIZE));
        return keep;
    }

    /** 任一线路有实时数据就算这站有数据。 */
    public static boolean hasRealtime(BusModels.Station s) {
        for (BusModels.Arrival a : s.arrivals) {
            if (a.realtime) return true;
        }
        return false;
    }

    /**
     * 批量拉站点详情，只往回带数据、不动传进来的对象。
     *
     * <p>首页要先把 nearby 那点数据立刻画出来（一行列表 200~400ms 就能上屏），
     * 详情这批一两秒才回来，所以不在工作线程里改 {@code Station}：那边还在读，
     * 这边改 arrivals 会撞出并发问题。取回来交给 UI 线程合并。
     *
     * @return stationId → 详情
     */
    public static Map<String, BusModels.Station> stationDetails(String cityId,
                                                               List<BusModels.Station> list,
                                                               final double lat, final double lng) {
        final Map<String, BusModels.Station> out = new java.util.concurrent.ConcurrentHashMap<>();
        final List<BusModels.Station> todo = new ArrayList<>(list);
        if (todo.isEmpty()) return out;
        // 一次开 8 个线程打过去，上游会掐掉一部分连接（掐掉的那几个站就永远停在
        // 「暂无实时」）。三条并发、每条之间歇一下，慢一两秒但每个站都能拿到。
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
        // 注意是**合并**不是替换：详情只会给被请求那个站台的完整数据，其他站台是占位，
        // 直接覆盖会把别的站台的线路弄丢——首页要显示全部线路，站台切换留给站点详情页。
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
