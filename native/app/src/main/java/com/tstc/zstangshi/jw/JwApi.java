package com.tstc.zstangshi.jw;

import com.tstc.zstangshi.data.Store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教务四项查询功能（成绩 / 学分绩点 / 选课 / 推荐课表）的原生实现。
 *
 * 旧版 WebView 架构里这些是 H5 页面，路径与字段都来自 2026-11 的 PC / 真机实测
 * （docs/diagnostics 与 data/jwxtClient.js ENDPOINTS）。这里全部翻成 framework API：
 * 复用 {@link JwClient} 的 Cookie 会话，JSON 用系统自带 org.json，不引第三方库。
 *
 * 口径：只读教务原文，不本地换算绩点、不编造课程；抓不到就如实把原因带回去。
 */
public final class JwApi {

    private static final String GRADES_PAGE = "/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005&layout=default";
    private static final String GRADES_DATA = "/cjcx/cjcx_cxXsgrcj.html?doType=query";
    private static final String GRADES_DETAIL = "/cjcx/cjcx_cxXsKccjList.html?gnmkdm=N305007";
    private static final String GPA_PAGE = "/xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=N105515&layout=default";

    private static final String SELECTION = "/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default";
    private static final String SELECTION_DISPLAY = "/xsxk/zzxkyzb_cxZzxkYzbDisplay.html?gnmkdm=N253512";
    private static final String SELECTION_PART = "/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html?gnmkdm=N253512";
    private static final String SELECTION_CHOOSED = "/xsxk/zzxkyzb_cxZzxkYzbChoosed.html?gnmkdm=N253512";

    private static final String RECOMMEND_PAGE = "/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=N214505&layout=default";
    private static final String RECOMMEND_LIST = "/kbdy/bjkbdy_cxBjkbdyTjkbList.html";
    private static final String RECOMMEND_SFKZ = "/kbdy/bjkbdy_cxXnxqsfkz.html?gnmkdm=N214505";
    private static final String RECOMMEND_PREVIEW = "/kbdy/bjkbdy_ylBjkbdyIndex.html?gnmkdm=N214505";
    private static final String RECOMMEND_KB = "/kbdy/bjkbdy_cxBjKb.html?gnmkdm=N214505";

    /** 一行展示数据：主标题 / 副标题 / 右侧值 / 附加说明。 */
    public static final class Row {
        public final String title;
        public final String sub;
        public final String value;
        public final String extra;
        /** 分组标签（成绩用它按学期筛选）。 */
        public final String tag;

        public Row(String t, String s, String v, String e) {
            this(t, s, v, e, "");
        }

        public Row(String t, String s, String v, String e, String tag) {
            title = t;
            sub = s;
            value = v;
            extra = e;
            this.tag = tag;
        }
    }

    public static final class Data {
        public boolean ok;
        public String message = "";
        public String headline = "";
        public String note = "";
        public final List<Row> rows = new ArrayList<>();
        /** 推荐课表的级联筛选：一组 = 一个下拉框的候选项。 */
        public final List<String> filterKeys = new ArrayList<>();
        public final List<String> filterLabels = new ArrayList<>();
        public final List<String[]> filterIds = new ArrayList<>();
        public final List<String[]> filterNames = new ArrayList<>();
        /** 推荐课表的班级原始行，点某一行时按 index 回查课表。 */
        public final List<JSONObject> classes = new ArrayList<>();
        /** 推荐课表接口原文，交给 JwglxtFetcher 解析成 Timetable。 */
        public String kbJson = "";
        /** 落盘快照的时间戳，界面上如实告诉用户数据是什么时候取的。 */
        public long at = 0L;

        /** 成绩等功能页的本地快照：进页秒开，只有下拉刷新才重新联网。 */
        public String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("ok", ok);
                o.put("message", message);
                o.put("headline", headline);
                o.put("note", note);
                o.put("at", at == 0 ? System.currentTimeMillis() : at);
                JSONArray a = new JSONArray();
                for (Row r : rows) {
                    JSONObject x = new JSONObject();
                    x.put("t", r.title);
                    x.put("s", r.sub);
                    x.put("v", r.value);
                    x.put("e", r.extra);
                    x.put("g", r.tag);
                    a.put(x);
                }
                o.put("rows", a);
                return o.toString();
            } catch (Exception e) {
                return "";
            }
        }

        public static Data fromJson(String json) {
            Data d = new Data();
            try {
                JSONObject o = new JSONObject(json);
                d.ok = o.optBoolean("ok");
                d.message = o.optString("message", "");
                d.headline = o.optString("headline", "");
                d.note = o.optString("note", "");
                d.at = o.optLong("at", 0L);
                JSONArray a = o.optJSONArray("rows");
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject x = a.optJSONObject(i);
                        if (x == null) continue;
                        d.rows.add(new Row(x.optString("t", ""), x.optString("s", ""),
                                x.optString("v", ""), x.optString("e", ""),
                                x.optString("g", "")));
                    }
                }
            } catch (Exception ignored) {
                d.ok = false;
            }
            return d;
        }
    }

    private final JwClient client;
    private final List<JSONObject> classRows = new ArrayList<>();
    private final List<String> recKeys = new ArrayList<>();
    private final List<String[]> recIds = new ArrayList<>();
    private final List<String[]> recNames = new ArrayList<>();
    private final List<String> recLabels = new ArrayList<>();
    /** 当前每一组真实生效的下拉值（联动后会替换页面初始解析的那份）。 */
    private final List<String[]> activeIds = new ArrayList<>();
    /** 级联选项读写（recIds/activeIds）的同步锁，避免后台线程并发改坏。 */
    private final Object cascadeLock = new Object();
    private String recXnm = "";
    private String recXqm = "";
    private String loginMessage = "";
    private String savedUser = "";
    private String savedPass = "";

    /**
     * 进程级班级清单缓存：教务 467 个班、每页固定 10 条，一次并行翻页取全。
     * 老版就是「进页拿全量字典 → 级联全在本地过滤」，这里把它做成一次预热，
     * 之后四级联动和「查询」都是内存操作，零网络往返。
     */
    private static volatile List<JSONObject> CLASS_ROWS = null;
    private static volatile long CLASS_ROWS_AT = 0L;
    private static volatile String CLASS_ROWS_SEM = "";
    private static volatile boolean CLASS_LOADING = false;
    private static final long CLASS_TTL = 20 * 60_000L;

    /**
     * 按「已选上级条件」缓存的班级清单（老版 rowsByGrade 口径）。
     * 关键：逐级筛选不能等全量预热 —— 选了院系就按 jg_id 查那一个院系的班级，
     * 页数少、结果确定，点开专业下拉时一定只有该院系的专业。
     */
    private static final Map<String, List<JSONObject>> SCOPE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public JwApi(Store store) {
        client = new JwClient(store);
    }

    public boolean login(String user, String pass) {
        savedUser = user;
        savedPass = pass;
        JwClient.Result r = client.login(user, pass);
        loginMessage = r.message;
        return r.ok;
    }

    public String loginMessage() {
        return loginMessage;
    }

    // ---------- 成绩 ----------

    /** 进程内成绩快照：同一进程里反复进成绩页不再重复打教务接口。 */
    private static volatile Data gradeCache = null;
    private static volatile long gradeCacheAt = 0L;
    private static final long GRADE_TTL = 30 * 60_000L;

    public Data grades() {
        return grades(false);
    }

    /** force=true 只由「下拉刷新」触发，其余情况一律读缓存。 */
    public Data grades(boolean force) {
        Data c = gradeCache;
        if (!force && c != null && c.ok
                && System.currentTimeMillis() - gradeCacheAt < GRADE_TTL) {
            android.util.Log.d("ZSTS-JW", "成绩命中进程缓存，跳过网络");
            return c;
        }
        Data d = networkGrades();
        if (d.ok) {
            d.at = System.currentTimeMillis();
            gradeCache = d;
            gradeCacheAt = d.at;
        }
        return d;
    }

    /**
     * 老版口径：页面壳只是壳，真实数据在两个 POST 里；两者没有依赖，并行发。
     * 明细（N305007）是「附加」不是「替代」——它失败只少分项，绝不能拖垮总评。
     */
    private Data networkGrades() {
        Data d = new Data();
        Map<String, String> f = new LinkedHashMap<>();
        f.put("xnm", "");
        f.put("xqm", "");
        f.put("zd_fzdm", "N305005-xs");
        f.put("page", "1");
        f.put("rows", "200");
        f.put("limit", "200");
        f.put("queryModel.showCount", "200");
        f.put("queryModel.pageSize", "200");
        f.put("sidx", "");
        f.put("sord", "asc");
        Map<String, String> df = new LinkedHashMap<>();
        df.put("xnm", "");
        df.put("xqm", "");
        df.put("page", "1");
        df.put("rows", "1000");
        df.put("limit", "1000");
        df.put("queryModel.showCount", "1000");
        df.put("queryModel.pageSize", "1000");
        df.put("sidx", "");
        df.put("sord", "asc");

        final String[] main = new String[1];
        final String[] detail = new String[1];
        Thread t1 = new Thread(() -> main[0] = client.post(GRADES_DATA, f), "jw-grades");
        Thread t2 = new Thread(() -> detail[0] = client.post(GRADES_DETAIL, df), "jw-detail");
        t1.start();
        t2.start();
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 少数情况下教务会要求先访问 gnmkdm 页面建立会话语境；只在主表异常时补这一步，
        // 正常路径省掉这一次往返（老版也是两个 POST 直连）。
        String json = main[0];
        if (badPayload(json)) {
            client.get(GRADES_PAGE);
            json = client.post(GRADES_DATA, f);
        }
        if (badPayload(json) && relogin()) {
            json = client.post(GRADES_DATA, f);
        }
        if (json == null || json.trim().isEmpty()) return fail(d, "成绩接口没有响应，请检查网络");

        final Map<String, String> comps = detail[0] == null
                ? new LinkedHashMap<>() : gradeComponents(detail[0]);

        try {
            JSONArray arr = firstArray(new JSONObject(json));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = pick(o, "kcmc", "kcmcmc", "courseName");
                if (name.isEmpty()) continue;
                String score = pick(o, "cj", "score");
                String credit = pick(o, "xf", "credits");
                String point = pick(o, "jd", "xfjd");
                String sem = pick(o, "xnmmc", "xnm") + " · " + pick(o, "xqmmc", "xqm");
                String kind = pick(o, "kcxzmc", "kclbmc");
                String parts = comps.get("j:" + pick(o, "jxb_id", "key"));
                if (parts == null || parts.isEmpty()) parts = comps.get("n:" + name);
                String sub = sem
                        + (credit.isEmpty() ? "" : " · " + credit + " 学分")
                        + (kind.isEmpty() ? "" : " · " + kind)
                        + (parts == null || parts.isEmpty() ? "" : "\n" + parts);
                d.rows.add(new Row(name, sub, score.isEmpty() ? "—" : score, point, sem));
            }
        } catch (Exception e) {
            return fail(d, "成绩数据解析失败：" + e.getMessage());
        }
        if (d.rows.isEmpty()) return fail(d, "教务暂未返回成绩（可能还没出分）");
        d.ok = true;
        d.headline = d.rows.size() + " 门";
        d.note = "数据来自教务「信息查询 → 学生成绩查询」；五级制/缓考等文字成绩保留原文";
        return d;
    }

    private static boolean badPayload(String s) {
        return s == null || s.contains("login_slogin") || !s.trim().startsWith("{");
    }

    /** 会话在免探测窗口里过期时的兜底：强制重登一次，别把登录页当成成绩显示。 */
    private boolean relogin() {
        if (savedUser.isEmpty() || savedPass.isEmpty()) return false;
        client.clearSession();
        JwClient.Result r = client.login(savedUser, savedPass);
        android.util.Log.d("ZSTS-JW", "成绩会话失效，重新登录=" + r.ok);
        return r.ok;
    }

    /** N305007 明细行 → 按教学班 / 课名归组的「平时 x · 期末 y」。总评行丢掉，避免和主表重复。 */
    private static Map<String, String> gradeComponents(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JSONArray arr = firstArray(new JSONObject(json));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String label = pick(o, "xmblmc");
                String score = pick(o, "xmcj");
                if (label.isEmpty() || score.isEmpty() || label.contains("总评")) continue;
                String text = label + " " + score;
                addComp(out, "j:" + pick(o, "jxb_id", "jxbmc"), text);
                addComp(out, "n:" + pick(o, "kcmc", "kcmcmc"), text);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void addComp(Map<String, String> m, String key, String text) {
        if (key == null || key.length() < 3) return;
        String old = m.get(key);
        m.put(key, old == null ? text : old + " · " + text);
    }

    // ---------- 学分绩点 ----------

    public Data gpa() {
        Data d = new Data();
        String html = client.get(GPA_PAGE);
        if (html == null) return fail(d, "学业情况页打不开，请检查网络");
        // 这一页服务端渲染能到 1MB，整页去标签纯属浪费：只切关键字前后的一段
        // （统计时间另切一小段）。
        int at = html.indexOf("平均学分绩点");
        String around = at >= 0
                ? html.substring(Math.max(0, at - 200), Math.min(html.length(), at + 600))
                : html.substring(0, Math.min(html.length(), 4000));
        String text = stripTags(around);
        String timeText = "";
        int stat = html.indexOf("统计时间");
        if (stat >= 0) {
            timeText = stripTags(html.substring(stat,
                    Math.min(html.length(), stat + 200)));
        }
        android.util.Log.d("ZSTS-JW", "GPA 页 " + html.length() + " 字节，含绩点关键字="
                + text.contains("平均学分绩点") + "，被踢回登录=" + html.contains("login_slogin"));
        // 服务端把标签、全半角括号混着渲染，别把括号写死：从「平均学分绩点」往后
        // 40 个非数字字符内抓第一个数字，就是教务印出来的那个权威值。
        Matcher m = Pattern.compile("平均学分绩点[^0-9]{0,40}([0-9]+(?:\\.[0-9]+)?)")
                .matcher(text);
        if (!m.find()) {
            android.util.Log.d("ZSTS-JW", "GPA 页文本开头=" + text.substring(0, Math.min(300, text.length())));
            return fail(d, "学业情况页找不到平均学分绩点，教务可能改版或会话已过期");
        }
        d.ok = true;
        d.headline = m.group(1);
        Matcher st = Pattern.compile(
                "统计时间\\s*([0-9-]{10}\\s+[0-9:]{8})\\s*之前有效").matcher(timeText);
        d.note = st.find() ? "统计截至 " + st.group(1) + " · 教务权威值，App 不做二次计算"
                : "数据来自教务「信息查询 → 学生学业情况查询」";
        d.rows.add(new Row("关于这个数字",
                "公选课是否计入、重修取哪次成绩、五级制如何折算，均由教务口径决定", "", ""));
        d.rows.add(new Row("提示", "此页面信息仅做学业修读情况参考", "", ""));
        return d;
    }

    // ---------- 自主选课 ----------

    public Data selection() {
        Data d = new Data();
        String page = client.get(SELECTION);
        if (page == null) return fail(d, "选课页打不开，请检查网络");
        Map<String, String> hidden = hiddenInputs(page);
        String notice = noticeOf(page);

        // 已选课程（HTML 片段），闸门开不开都先看一眼
        String chosen = client.get(SELECTION_CHOOSED);
        for (String name : chosenNames(chosen)) {
            d.rows.add(new Row(name, "已选课程", "", ""));
        }

        String gate = hidden.containsKey("iskxk") ? hidden.get("iskxk") : "";
        if (!"1".equals(gate)) {
            d.ok = true;
            d.headline = "当前不在选课时间窗";
            d.note = notice.isEmpty()
                    ? "教务自主选课未开放；开放后这里会显示课池、余量与上课时间"
                    : notice;
            return d;
        }

        Map<String, String> disp = new LinkedHashMap<>();
        disp.put("xkkz_id", first(hidden, "firstXkkzId", "xkkz_id"));
        disp.put("kklxdm", first(hidden, "firstKklxdm", "kklxdm"));
        disp.put("xszxzt", first(hidden, "xszxzt"));
        disp.put("njdm_id", first(hidden, "njdm_id"));
        disp.put("zyh_id", first(hidden, "zyh_id"));
        disp.put("kspage", "0");
        disp.put("jspage", "0");
        if (disp.get("xkkz_id").isEmpty()) {
            d.ok = true;
            d.headline = "选课已开放，但读不到选课上下文";
            d.note = "教务页面没给出 xkkz_id，暂只能在教务网页里选课";
            return d;
        }
        String display = client.post(SELECTION_DISPLAY, disp);
        if (display != null) hidden.putAll(hiddenInputs(display));

        Map<String, String> part = new LinkedHashMap<>(hidden);
        part.put("kspage", "1");
        part.put("jspage", "15");
        String pool = client.post(SELECTION_PART, part);
        if (pool == null) return fail(d, "课池接口没有响应");
        try {
            JSONObject root = new JSONObject(pool);
            JSONObject scope = root.optJSONObject("rst");
            JSONArray arr = firstArray(scope == null ? root : scope);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = pick(o, "kcmc", "kcmcmc");
                if (name.isEmpty()) continue;
                String teacher = pick(o, "jsxm", "jsm", "xm");
                String time = pick(o, "sksj", "jsxx", "jxdd");
                String cap = pick(o, "jxbrl", "rsyxz");
                String got = pick(o, "yxzrs", "rsz");
                String sub = (teacher.isEmpty() ? "" : teacher + " · ") + time;
                String value = cap.isEmpty() ? "" : got + "/" + cap;
                d.rows.add(new Row(name, sub, value, ""));
            }
        } catch (Exception e) {
            return fail(d, "课池解析失败：" + e.getMessage());
        }
        d.ok = true;
        d.headline = "课池 " + d.rows.size() + " 门";
        d.note = "课程池与余量以教务自主选课数据为准；本页只读，抢课请到教务网页完成";
        return d;
    }

    // ---------- 推荐课表 ----------

    /**
     * 进页只拉「年级 / 院系 / 专业 / 班级」四级下拉和学期码，不查课表 —— 旧版
     * 推荐课表就是这个形态：勾完条件点「查询」才显示课表。
     */
    /**
     * 推荐课表筛选页：先拿到学期，再把「全量班级清单」一次拉回内存。
     * 之后年级/院系/专业/班级四级联动全部在内存里按行字段过滤，零网络请求，
     * 也避开了「页面字典 zyh_id 短码」和「清单行 zyh_id UUID」对不上的坑。
     */
    public Data recommendContext() {
        Data d = new Data();
        classRows.clear();
        recKeys.clear();
        recIds.clear();
        recNames.clear();
        recLabels.clear();
        activeIds.clear();
        SCOPE_CACHE.clear();
        String page = client.get(RECOMMEND_PAGE);
        if (page == null) return fail(d, "推荐课表页打不开，请检查网络");
        recXnm = JwClient.selectedOption(page, "xnm");
        recXqm = JwClient.selectedOption(page, "xqm");
        if (recXnm.isEmpty() || recXqm.isEmpty()) return fail(d, "推荐课表页找不到学年/学期");

        // 四个下拉全部用页面自带的全量字典（年级 / 院系 / 专业 / 班级），与老版一致：
        // 年级 / 院系：用页面全量字典（短码，与清单行一致，可直接用）。
        // 专业 / 班级：不取页面字典——页面 zyh_id 是短码、和清单行 zyh_id(UUID) 对不上，
        // 会变成"全量不联动"。正确口径对齐老版 web（recommend.js）：选了年级+院系后向
        // 服务端拉「班级清单」行，再从行里提取 专业=zyh_id(UUID)+zymc、班级=bh_id+bjmc，
        // 选中的专业值(UUID)直接交给服务端做码值映射，绝不在客户端拿 UUID 比短码。
        String[][] dict0 = selectOptions(page, "njdm_id");
        String[][] dict1 = selectOptions(page, "jg_id");
        recKeys.add("njdm_id"); recLabels.add("年级");
        recKeys.add("jg_id");   recLabels.add("院系");
        recKeys.add("zyh_id");  recLabels.add("专业");
        recKeys.add("bh_id");   recLabels.add("班级");
        recIds.add(dict0[0]); recNames.add(dict0[1]);
        recIds.add(dict1[0]); recNames.add(dict1[1]);
        // 专业 / 班级初始为占位，选了年级+院系后由 recommendOptions 从服务端行里填充
        recIds.add(new String[]{""}); recNames.add(new String[]{"全部"});
        recIds.add(new String[]{""}); recNames.add(new String[]{"全部"});
        d.filterKeys.addAll(recKeys);
        d.filterLabels.addAll(recLabels);
        for (int g = 0; g < 4; g++) {
            d.filterIds.add(recIds.get(g));
            d.filterNames.add(recNames.get(g));
            if (g < activeIds.size()) activeIds.set(g, recIds.get(g));
            else activeIds.add(recIds.get(g));
        }

        // 不再预热全量班级清单；专业 / 班级按所选年级 / 院系按需向服务端拉取（loadScope）。
        d.ok = true;
        d.headline = "筛选条件";
        d.note = "先选年级 / 院系，专业 / 班级会自动按范围加载；选完点「查询」；课表来自教务「推荐课表打印」";
        android.util.Log.d("ZSTS-JW", "推荐课表字典就绪：年级=" + dict0[0].length
                + " 院系=" + dict1[0].length);
        return d;
    }

    /** 按选中的四级字典码向服务端查班级（zyh_id 用页面短码，由教务做码值映射）。 */
    public Data recommendQuery(int[] picked) {
        Data d = new Data();
        classRows.clear();
        if (recXnm.isEmpty() || recXqm.isEmpty()) {
            return fail(d, "请先进入筛选页拉取学年 / 学期");
        }
        // 把选中的四级字典码拼成服务端筛选条件
        Map<String, String> filter = new LinkedHashMap<>();
        String[] fk = {"njdm_id", "jg_id", "zyh_id", "bh_id"};
        for (int i = 0; i < fk.length; i++) {
            String v = valueOf(picked, i);
            if (!v.isEmpty()) filter.put(fk[i], v);
        }
        if (filter.isEmpty()) {
            return fail(d, "请至少选择一个班级（年级 / 院系 / 专业 / 班级）");
        }
        List<JSONObject> rows = fetchAllClasses(client, recXnm, recXqm, filter);
        if (rows == null || rows.isEmpty()) {
            return fail(d, "没有符合条件的班级，换个筛选条件试试");
        }
        final int cap = 80; // 一个班一张卡片，过多时只列前 80 个
        int matched = 0;
        for (JSONObject row : rows) {
            String name = pick(row, "bjmc", "bj", "bh_id");
            if (name.isEmpty()) continue;
            matched++;
            classRows.add(row);
            if (d.rows.size() >= cap) continue;
            d.classes.add(row);
            String sub = pick(row, "njmc", "njdm_id") + " · "
                    + pick(row, "zymc", "zyh_id") + " · " + pick(row, "jgmc");
            d.rows.add(new Row(name, sub, pick(row, "xkrs") + " 人", ""));
        }
        if (matched == 0) return fail(d, "没有符合条件的班级，换个筛选条件试试");
        if (d.rows.size() == 1) {
            Data kb = recommendKb(0);
            if (kb.ok) return kb;
            d.note = kb.message;
        }
        d.ok = true;
        d.headline = "匹配 " + matched + " 个班级";
        if (d.note.isEmpty()) {
            d.note = matched > cap
                    ? "仅列出前 " + cap + " 个，请把条件选细一点"
                    : "点一个班级查看推荐课表";
        }
        return d;
    }

    private static String[][] selectOptions(String html, String id) {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ids.add("");
        names.add("全部");
        Matcher sel = Pattern.compile(
                "<select[^>]*(?:id|name)=[\"']" + Pattern.quote(id) + "[\"'][^>]*>([\\s\\S]*?)</select>",
                Pattern.CASE_INSENSITIVE).matcher(html == null ? "" : html);
        if (sel.find()) {
            Matcher opt = Pattern.compile(
                    "<option[^>]*value=[\"']([^\"']*)[\"'][^>]*>([^<]*)<",
                    Pattern.CASE_INSENSITIVE).matcher(sel.group(1));
            // 老版页面里班级字典有 2198 项，之前硬截到 120 会让绝大多数班级查不到
            while (opt.find() && ids.size() < 4000) {
                String v = opt.group(1).trim();
                if (v.isEmpty()) continue;
                String n = opt.group(2).trim();
                ids.add(v);
                names.add(n.isEmpty() ? v : n);
            }
        }
        return new String[][]{ids.toArray(new String[0]), names.toArray(new String[0])};
    }

    /** 班级清单行里的四级字段（院系 id 在行里叫 jgdm，页面字典里叫 jg_id，值相同）。 */
    private static final String[][] OPT_FIELDS = {
            {"njdm_id", "njmc"}, {"jgdm", "jgmc"},
            {"zyh_id", "zymc"}, {"bh_id", "bjmc"}
    };

    /** 全量清单是否已就绪（就绪后所有联动纯内存、零等待）。 */
    public boolean cascadeReady() {
        List<JSONObject> all = CLASS_ROWS;
        return all != null && !all.isEmpty();
    }

    /** 等清单就绪，最多等 ms 毫秒（在后台线程里调用，别在主线程）。 */
    public void waitForRows(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!cascadeReady() && System.currentTimeMillis() < end) {
            try { Thread.sleep(250); } catch (InterruptedException e) { break; }
        }
    }

    /**
     * 逐级联动：完全在内存里按「行字段」过滤全量班级清单。
     * 选了教育学院 → 专业只来自教育学院的行；再选小学教育 → 班级只来自该专业的行。
     * 年级 / 院系 是顶层条件，取页面字典（值跟清单行一致）；专业 / 班级必须逐级收窄，
     * 因此没选上一级时直接提示先选上级，绝不把 467 个班一股脑倒给用户。
     */
    /**
     * 级联候选项（与老版 web recommend.js 一致）：
     * 年级(0)/院系(1) 直接返回页面全量字典；
     * 专业(2)/班级(3) 需先用已选年级/院系向服务端拉「班级清单」行，再从行里提取——
     * 专业 = 行里唯一的 zyh_id(UUID) + zymc；班级 = 按选中专业 UUID 过滤出的 bh_id + bjmc。
     * 选中的值（专业=UUID、班级=bh_id 短码）交给 recommendQuery 拼服务端筛选，由教务映射。
     */
    public String[][] recommendOptions(int[] picked, int group) {
        synchronized (cascadeLock) {
            if (group == 0 || group == 1) {
                String[][] o = group < recIds.size()
                        ? new String[][]{recIds.get(group), recNames.get(group)}
                        : new String[][]{new String[]{""}, new String[]{"全部"}};
                syncOpts(group, o);
                return o;
            }
            String grade = valueOf(picked, 0);
            String dept = valueOf(picked, 1);
            if (group == 2) {
                if (grade.isEmpty() && dept.isEmpty()) return placeholder("请先选择年级或院系");
                List<JSONObject> rows = loadScope(grade, dept);
                LinkedHashMap<String, String> m = new LinkedHashMap<>();
                for (JSONObject r : rows) {
                    String id = pick(r, "zyh_id");
                    String nm = pick(r, "zymc");
                    if (!id.isEmpty() && !m.containsKey(id)) m.put(id, nm.isEmpty() ? id : nm);
                }
                if (m.isEmpty()) return placeholder("该范围暂无专业");
                String[][] o = toArrays(m);
                syncOpts(2, o);
                return o;
            }
            // group == 3 班级：依赖已选专业（其 UUID）
            String majorId = valueOf(picked, 2);
            if (majorId.isEmpty()) return placeholder("请先选择专业");
            List<JSONObject> rows = loadScope(grade, dept);
            LinkedHashMap<String, String> m = new LinkedHashMap<>();
            for (JSONObject r : rows) {
                if (!majorId.equals(pick(r, "zyh_id"))) continue;
                String id = pick(r, "bh_id");
                String nm = pick(r, "bjmc", "bj");
                if (!id.isEmpty() && !m.containsKey(id)) m.put(id, nm.isEmpty() ? id : nm);
            }
            if (m.isEmpty()) return placeholder("该专业暂无班级");
            String[][] o = toArrays(m);
            syncOpts(3, o);
            return o;
        }
    }

    private static String[][] placeholder(String msg) {
        return new String[][]{new String[]{""}, new String[]{msg}};
    }

    private static String[][] toArrays(LinkedHashMap<String, String> m) {
        String[][] o = new String[2][m.size() + 1];
        o[0][0] = "";
        o[1][0] = "全部";
        int i = 1;
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            o[0][i] = e.getKey();
            o[1][i] = e.getValue();
            i++;
        }
        return o;
    }

    /** 把某一级候选项同步进 recIds/activeIds，保证 recommendQuery 的 valueOf 取到真实值（专业=UUID）。 */
    private void syncOpts(int group, String[][] o) {
        if (group < recIds.size()) recIds.set(group, o[0]); else recIds.add(o[0]);
        if (group < recNames.size()) recNames.set(group, o[1]); else recNames.add(o[1]);
        if (group < activeIds.size()) activeIds.set(group, o[0]); else activeIds.add(o[0]);
    }

    /** 按年级/院系拉班级清单行（分页取全），按 (年级|院系) 缓存，避免重复翻页。 */
    private List<JSONObject> loadScope(String grade, String dept) {
        String key = (grade.isEmpty() ? "*" : grade) + "|" + (dept.isEmpty() ? "*" : dept);
        List<JSONObject> cached = SCOPE_CACHE.get(key);
        if (cached != null) return cached;
        Map<String, String> filter = new LinkedHashMap<>();
        if (!grade.isEmpty()) filter.put("njdm_id", grade);
        if (!dept.isEmpty()) filter.put("jg_id", dept);
        List<JSONObject> rows = fetchAllClasses(client, recXnm, recXqm, filter);
        if (rows != null && !rows.isEmpty()) SCOPE_CACHE.put(key, rows);
        return rows != null ? rows : new ArrayList<>();
    }

    /** 从全量清单里抽第 group 级的去重选项，并按已选上级条件过滤。 */
    private String[][] optionsFor(int[] picked, int group) {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ids.add("");
        names.add("全部");
        List<JSONObject> rows = CLASS_ROWS;
        if (rows == null || rows.isEmpty()) {
            names.set(0, "选项加载中…");
            return new String[][]{ids.toArray(new String[0]), names.toArray(new String[0])};
        }
        // 逐级：专业要先选院系，班级要先选专业
        if (group == 2 && (picked == null || valueOf(picked, 1).isEmpty())) {
            names.set(0, "请先选择院系");
            return new String[][]{ids.toArray(new String[0]), names.toArray(new String[0])};
        }
        if (group == 3 && (picked == null || valueOf(picked, 2).isEmpty())) {
            names.set(0, "请先选择专业");
            return new String[][]{ids.toArray(new String[0]), names.toArray(new String[0])};
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JSONObject row : rows) {
            if (!matchPrefix(row, picked, group)) continue;
            String id = pick(row, OPT_FIELDS[group][0]);
            if (id.isEmpty() || !seen.add(id)) continue;
            String name = pick(row, OPT_FIELDS[group][1]);
            ids.add(id);
            names.add(name.isEmpty() ? id : name);
        }
        return new String[][]{ids.toArray(new String[0]), names.toArray(new String[0])};
    }

    /** 前 group 组条件是否都命中这一行；picked=0 表示「全部」，不约束。 */
    private boolean matchPrefix(JSONObject row, int[] picked, int group) {
        int limit = Math.min(group, OPT_FIELDS.length);
        for (int i = 0; i < limit; i++) {
            String want = valueOf(picked, i);
            if (want.isEmpty()) continue;
            if (!want.equals(pick(row, OPT_FIELDS[i][0]))) return false;
        }
        return true;
    }

    private String valueOf(int[] picked, int i) {
        if (picked == null || i >= picked.length || picked[i] <= 0) return "";
        String[] ids = i < activeIds.size() ? activeIds.get(i) : recIds.get(i);
        return picked[i] < ids.length ? ids[picked[i]] : "";
    }

    /** 进页后台预热一次全量班级清单，进程内 20 分钟复用，不阻塞界面。 */
    private void warmClassRows() {
        String sem = recXnm + "-" + recXqm;
        List<JSONObject> c = CLASS_ROWS;
        if (c != null && !c.isEmpty() && sem.equals(CLASS_ROWS_SEM)
                && System.currentTimeMillis() - CLASS_ROWS_AT < CLASS_TTL) return;
        if (CLASS_LOADING) return;
        CLASS_LOADING = true;
        final JwClient cl = client;
        final String xnm = recXnm, xqm = recXqm, key = sem;
        new Thread(() -> {
            try {
                List<JSONObject> rows = fetchAllClasses(cl, xnm, xqm, new LinkedHashMap<>());
                if (!rows.isEmpty()) {
                    CLASS_ROWS = rows;
                    CLASS_ROWS_AT = System.currentTimeMillis();
                    CLASS_ROWS_SEM = key;
                    android.util.Log.d("ZSTS-JW", "班级清单预热完成 " + rows.size() + " 行");
                }
            } catch (Throwable t) {
                android.util.Log.w("ZSTS-JW", "班级清单预热失败：" + t);
            } finally {
                CLASS_LOADING = false;
            }
        }, "jw-class-warm").start();
    }

    /**
     * 教务每页固定 10 条：先取第 1 页问总行数，再把剩余页按 4 并发一次拉完。
     * 老版是串行翻最多 50 页；这里并发后等待时间约为原来的 1/4，且按页码顺序回填，
     * 下拉顺序稳定。单页失败只丢那一页，不影响整体。
     */
    private static List<JSONObject> fetchAllClasses(JwClient cl, String xnm, String xqm,
                                                    Map<String, String> filter) {
        List<JSONObject> out = new ArrayList<>();
        String first = cl.post(RECOMMEND_LIST, pagedBody(xnm, xqm, 1, filter));
        if (first == null) return out;
        int total = 0;
        int per = 0;
        try {
            JSONObject root = new JSONObject(first);
            total = root.optInt("totalResult", 0);
            JSONArray arr = firstArray(root);
            per = arr.length();
            for (int i = 0; i < per; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(o);
            }
        } catch (Exception e) {
            return out;
        }
        if (per == 0 || out.size() >= total) return out;
        int pages = Math.min(60, (total + per - 1) / per);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<JSONArray>> futures = new ArrayList<>();
        for (int p = 2; p <= pages; p++) {
            final int page = p;
            futures.add(pool.submit(() -> {
                String s = cl.post(RECOMMEND_LIST, pagedBody(xnm, xqm, page, filter));
                if (s == null) return null;
                try {
                    return firstArray(new JSONObject(s));
                } catch (Exception e) {
                    return null;
                }
            }));
        }
        for (Future<JSONArray> f : futures) {
            try {
                JSONArray a = f.get();
                if (a == null) continue;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) out.add(o);
                }
            } catch (Exception ignored) {
            }
        }
        pool.shutdown();
        return out;
    }

    private static Map<String, String> pagedBody(String xnm, String xqm, int page,
                                                 Map<String, String> filter) {
        Map<String, String> f = listBody(xnm, xqm, page);
        f.putAll(filter);
        return f;
    }

    /** 按 recommendClasses() 结果里的下标查该班课表，返回可直接喂给 JwglxtFetcher 的 JSON。 */
    public Data recommendKb(int index) {
        Data d = new Data();
        if (index < 0 || index >= classRows.size()) return fail(d, "班级下标无效，请重新查询");
        JSONObject row = classRows.get(index);
        Map<String, String> m = ylMap(row);

        String sfkz = client.post(RECOMMEND_SFKZ, m);
        if (sfkz != null && "true".equals(sfkz.trim().replace("\"", ""))) {
            return fail(d, "该学年的推荐课表当前不可查看（教务限制）");
        }
        client.post(RECOMMEND_PREVIEW, m); // 还原「预览已打开」的会话语境，失败不阻断
        String kb = client.post(RECOMMEND_KB, m);
        if (kb == null) return fail(d, "班级课表接口没有返回");
        try {
            JSONObject root = new JSONObject(kb);
            JSONArray list = root.optJSONArray("kbList");
            android.util.Log.d("ZSTS-JW", "推荐课表 kbList "
                    + (list == null ? 0 : list.length()) + " 行");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o == null) continue;
                    if (pick(o, "rs").isEmpty()) copy(o, "jxbrs", "rs");
                    if (pick(o, "yxrs").isEmpty()) copy(o, "xkrs", "yxrs");
                }
                root.put("kbList", list);
                kb = root.toString();
            }
        } catch (Exception e) {
            return fail(d, "班级课表解析失败：" + e.getMessage());
        }
        d.ok = true;
        d.kbJson = kb;
        d.headline = pick(row, "bjmc", "bj", "bh_id");
        d.note = "推荐课表 · " + pick(row, "njmc") + " · " + pick(row, "zymc");
        return d;
    }

    private static Map<String, String> listBody(String xnm, String xqm, int page) {
        Map<String, String> f = new LinkedHashMap<>();
        String[] emptyKeys = {"xqh_id", "njdm_id", "xb_id", "jg_id", "zyh_id", "zyfx_id",
                "bh_id", "xsdm", "pyccdm", "kclxdm", "kclbdm", "sfzhsjk",
                "kbsjlyqz", "zs", "yf"};
        for (String k : emptyKeys) f.put(k, "");
        f.put("sfcxxqh", "0");
        f.put("xnm", xnm);
        f.put("xqm", xqm);
        f.put("page", String.valueOf(page));
        f.put("rows", "10");
        return f;
    }

    private static Map<String, String> ylMap(JSONObject row) {
        Map<String, String> f = new LinkedHashMap<>();
        String[] keys = {"xnm", "xqm", "xnmc", "xqmmc", "xqh_id", "njdm_id", "zyh_id",
                "bh_id", "tjkbzdm", "tjkbzxsdm", "zymc", "jgmc", "njmc", "bj",
                "xkrs", "jsxm", "lxdh", "bh"};
        for (String k : keys) f.put(k, pick(row, k));
        String[] blanks = {"zs", "xsdm", "kclxdm", "kclbdm", "kbsjlyqz", "yf"};
        for (String k : blanks) f.put(k, "");
        f.put("zxszjjs", "false");
        f.put("akcxqjchb", "false");
        f.put("kzlx", "ck");
        f.put("sfcxxqh", f.get("xqh_id").isEmpty() ? "0" : "1");
        return f;
    }

    // ---------- 小工具 ----------

    private static Data fail(Data d, String msg) {
        d.ok = false;
        d.message = msg;
        return d;
    }

    /** 教务各接口封套不一致：{items} / {rows} / {data} / {tmpList} / 裸数组都认。 */
    private static JSONArray firstArray(JSONObject o) {
        if (o == null) return new JSONArray();
        String[] keys = {"items", "rows", "data", "tmpList", "list"};
        for (String k : keys) {
            JSONArray a = o.optJSONArray(k);
            if (a != null) return a;
        }
        return new JSONArray();
    }

    /** 字段名大小写不敏感地取第一个非空值。 */
    private static String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v != null && !String.valueOf(v).trim().isEmpty()) {
                return String.valueOf(v).trim();
            }
        }
        // 再按小写 key 兜一遍（教务有时给 KCMC / kcmc 混用）
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String real = it.next();
            for (String k : keys) {
                if (real.equalsIgnoreCase(k)) {
                    Object v = o.opt(real);
                    if (v != null && !String.valueOf(v).trim().isEmpty()) {
                        return String.valueOf(v).trim();
                    }
                }
            }
        }
        return "";
    }

    private static void copy(JSONObject o, String from, String to) {
        String v = pick(o, from);
        if (!v.isEmpty()) {
            try {
                o.put(to, v);
            } catch (Exception ignored) {
            }
        }
    }

    private static String first(Map<String, String> m, String... keys) {
        for (String k : keys) {
            String v = m.get(k);
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private static Map<String, String> hiddenInputs(String html) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher tags = Pattern.compile("<input\\b[^>]*type=[\"']hidden[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE).matcher(html == null ? "" : html);
        while (tags.find()) {
            String tag = tags.group();
            String name = attr(tag, "name");
            if (name.isEmpty()) name = attr(tag, "id");
            if (!name.isEmpty()) out.put(name, attr(tag, "value"));
        }
        return out;
    }

    private static String attr(String tag, String key) {
        Matcher m = Pattern.compile(key + "\\s*=\\s*[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    private static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ");
    }

    /** 选课页顶部的「当前不在选课时间窗」原文提示。 */
    private static String noticeOf(String html) {
        Matcher m = Pattern.compile("(?s)<div[^>]*id=[\"']nodata[\"'][^>]*>(.*?)</div>",
                Pattern.CASE_INSENSITIVE).matcher(html == null ? "" : html);
        if (m.find()) {
            String s = stripTags(m.group(1)).trim();
            if (s.length() > 2 && s.length() < 120) return s;
        }
        return "";
    }

    /** 已选课程是服务端渲染的 HTML 片段，按单元格抓课程名。 */
    private static List<String> chosenNames(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<td[^>]*>\\s*([^<>]{2,40})\\s*</td>",
                Pattern.CASE_INSENSITIVE).matcher(html == null ? "" : html);
        while (m.find() && out.size() < 30) {
            String s = stripTags(m.group(1)).trim().replaceAll("\\s+", "");
            if (s.length() < 2 || s.matches("[0-9\\W]+")) continue;
            if (s.contains("课程") && s.length() < 8) continue;
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }
}
