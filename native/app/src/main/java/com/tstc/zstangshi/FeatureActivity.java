package com.tstc.zstangshi;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tstc.zstangshi.data.Dates;
import com.tstc.zstangshi.data.JwglxtFetcher;
import com.tstc.zstangshi.data.ScheduleSettings;
import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.data.Timetable;
import com.tstc.zstangshi.data.WeekMath;
import com.tstc.zstangshi.data.WeekOverlay;
import com.tstc.zstangshi.jw.JwApi;
import com.tstc.zstangshi.ui.PullRefreshLayout;
import com.tstc.zstangshi.ui.TimetableGridView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * App 内的教务功能页：成绩 / 学分绩点 / 自主选课 / 推荐课表。
 *
 * 这四个入口以前是直接塞一个教务网页，跟「放弃 WebView 架构」的口径冲突；
 * 现在统一走 {@link JwApi} 的原生数据链，返回的是 App 自己画的卡片 / 课表网格。
 */
public class FeatureActivity extends BaseActivity {

    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_TITLE = "title";
    public static final String T_GRADES = "grades";
    public static final String T_GPA = "gpa";
    public static final String T_SELECTION = "selection";
    public static final String T_RECOMMEND = "recommend";

    public static void open(Activity host, String type, String title) {
        host.startActivity(new Intent(host, FeatureActivity.class)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_TITLE, title));
    }

    private Store store;
    private JwApi api;
    private String type;
    private String title;
    /** 整页的圆角卡片进出场（见 ui/PageSlide）。 */
    private com.tstc.zstangshi.ui.PageSlide slide;
    private LinearLayout body;
    private JwApi.Data current;
    private int[] filterPicked;
    private int semesterPicked = 0;
    private String[] semesterOptions = {"全部学期"};
    private JwApi.Data kbData;
    private Timetable kbTimetable;
    private int kbWeek = 1;
    private int kbTotalWeeks = 20;
    /** 推荐课表的周次切换模块（与个人课表同一套）：持久字段，翻周只换数据不重建视图。 */
    private TimetableGridView kbGrid;
    private TextView kbWeekLabel;
    private TextView kbFab;
    private boolean kbFabShown;
    private KbSwipe kbSwipe;
    /** 成绩页的下拉刷新容器（其他页不套，避免和课表网格抢手势）。 */
    private PullRefreshLayout pull;
    /** 本地快照的读取时间，用于界面上如实标注「数据截止」。 */
    private long cacheAt = 0L;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(this);
        api = new JwApi(store);
        type = getIntent().getStringExtra(EXTRA_TYPE);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (type == null) type = T_GRADES;
        if (title == null || title.isEmpty()) title = "教务查询";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pageColor());
        root.addView(header());

        ScrollView sc;
        // 成绩、学分绩点都用同一套下拉刷新：平时只看本地快照，想看新数据拉一下
        if (T_GRADES.equals(type) || T_GPA.equals(type)) {
            pull = new PullRefreshLayout(this);
            pull.setOnRefresh(() -> load(true));
            sc = pull.scrollView();
        } else {
            sc = new ScrollView(this);
            sc.setFillViewport(true);
            sc.setClipToPadding(false);
        }
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(28));
        sc.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 内容包一层 FrameLayout：「回到本周」悬浮按钮要叠在滚动区右下角
        FrameLayout content = new FrameLayout(this);
        View scroller = (T_GRADES.equals(type) || T_GPA.equals(type)) ? pull : sc;
        content.addView(scroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (T_RECOMMEND.equals(type)) {
            kbFab = buildKbFab();
            content.addView(kbFab, kbFabParams());
        }
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        // 窗口现在是全屏的（见 PageSlide.fullBleed），系统栏间距要自己留
        applyInsets(root);
        // 整页圆角卡片进场：和返回时的系统预测性返回动画用同一个圆角半径
        slide = new com.tstc.zstangshi.ui.PageSlide(this, pageColor());
        slide.enter();
        slide.attachBack();
        initialLoad();
    }

    /** 全屏窗口下的系统栏间距：内容自己让出来，页面底色仍然铺到屏幕四边。 */
    private void applyInsets(View root) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars =
                        insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        } else {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            root.setPadding(0, id > 0 ? getResources().getDimensionPixelSize(id) : 0, 0, 0);
        }
    }

    /** 「回到本周」悬浮按钮：外观与个人课表同一套（品牌色圆角胶囊，翻离本周才浮出）。 */
    private TextView buildKbFab() {
        TextView fab = new TextView(this);
        fab.setText("回到本周");
        fab.setTextSize(12.5f);
        fab.setTypeface(Typeface.DEFAULT_BOLD);
        fab.setGravity(Gravity.CENTER);
        fab.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.brand));
        bg.setCornerRadius(dp(22));
        fab.setBackground(bg);
        fab.setPadding(dp(16), 0, dp(16), 0);
        fab.setElevation(dp(5));
        fab.setContentDescription("回到本周");
        fab.setVisibility(View.GONE);
        fab.setAlpha(0f);
        fab.setOnClickListener(v -> {
            int w = currentKbWeek();
            if (w != kbWeek && kbSwipe != null) kbSwipe.flipTo(w);
        });
        return fab;
    }

    private FrameLayout.LayoutParams kbFabParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.rightMargin = dp(18);
        lp.bottomMargin = dp(18);
        return lp;
    }

    /**
     * 进页先画本地快照（秒开），成绩 / 绩点若本次登录会话还没抓过
     * （快照时间早于登录时间），再后台补一次最新数据 —— 不挡页面、不闪加载框。
     * 之后同一会话内重进不再联网，想看新数据靠下拉刷新。
     */
    private void initialLoad() {
        if (!T_RECOMMEND.equals(type)) {
            String cached = store.feature(type);
            if (!cached.isEmpty()) {
                JwApi.Data d = JwApi.Data.fromJson(cached);
                if (d.ok) {
                    cacheAt = d.at;
                    android.util.Log.d("ZSTS-JW", "功能页命中本地快照：" + type);
                    render(d);
                    if ((T_GRADES.equals(type) || T_GPA.equals(type))
                            && store.featureAt(type) < store.loginAt()) {
                        android.util.Log.d("ZSTS-JW", "本会话没抓过，进页补一次：" + type);
                        load(false);
                    }
                    return;
                }
            }
        }
        load(false);
    }

    private LinearLayout header() {
        LinearLayout h = com.tstc.zstangshi.ui.HeaderBar.create(this, title, v -> closePage());
        h.addView(com.tstc.zstangshi.ui.HeaderBar.action(this, "刷新", v -> load(true)));
        return h;
    }

    /** 关页面统一走滑动出场，别直接 finish 让窗口「啪」一下消失。 */
    private void closePage() {
        if (slide != null) {
            slide.finish();
        } else {
            finish();
        }
    }

    /**
     * 返回：Android 13 起返回手势走系统调度器，系统自己会把本页缩成圆角卡片退场，
     * 这里再叠一层滑动会两套动画打架 —— 所以只在没有预测性返回的老系统上补出场动画。
     */
    @Override
    public void onBackPressed() {
        if (slide != null && !com.tstc.zstangshi.ui.BackGestures.supported()) {
            slide.finish();
            return;
        }
        super.onBackPressed();
    }

    // ---------- 取数 ----------

    private void load() {
        load(false);
    }

    /** force 只由下拉刷新 / 顶部「刷新」按钮传入，才会真的重新打教务接口。 */
    private void load(boolean force) {
        if (pull != null) pull.setRefreshing(true);
        // 下拉刷新时保留现有列表，别先清空再转圈
        if (!force || current == null) showState("正在从教务读取…");
        new Thread(() -> work(force)).start();
    }

    private void work(boolean force) {
        if (store.user().isEmpty() || store.pass().isEmpty()) {
            runOnUiThread(() -> showError("还没有登录教务", true));
            return;
        }
        if (!api.login(store.user(), store.pass())) {
            String msg = api.loginMessage();
            runOnUiThread(() -> showError(msg.isEmpty() ? "登录教务失败" : msg, true));
            return;
        }
        JwApi.Data d;
        if (T_GPA.equals(type)) d = api.gpa();
        else if (T_SELECTION.equals(type)) d = api.selection();
        else if (T_RECOMMEND.equals(type)) d = api.recommendContext();
        else d = api.grades(force);
        runOnUiThread(() -> {
            finishRefresh();
            if (d.ok && !T_RECOMMEND.equals(type)) {
                cacheAt = d.at > 0 ? d.at : System.currentTimeMillis();
                String json = d.toJson();
                if (!json.isEmpty()) store.saveFeature(type, json);
            }
            render(d);
        });
    }

    private void finishRefresh() {
        if (pull != null) pull.setRefreshing(false);
    }

    private void showState(String text) {
        body.removeAllViews();
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(getColor(R.color.text_tertiary));
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(60), 0, dp(60));
        body.addView(v);
    }

    private void showError(String msg, boolean needLogin) {
        finishRefresh();
        body.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setBackground(getDrawable(R.drawable.card_bg));
        c.setPadding(dp(18), dp(24), dp(18), dp(24));

        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextSize(14);
        t.setTextColor(getColor(R.color.text_primary));
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(dp(3), 1f);
        c.addView(t);

        TextView btn = new TextView(this);
        btn.setText(needLogin ? "去登录教务" : "重试");
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(getColor(R.color.brand));
        btn.setBackground(getDrawable(R.drawable.btn_outline));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(18);
        c.addView(btn, lp);
        btn.setOnClickListener(v -> {
            if (needLogin) {
                startActivity(new Intent(this, LoginActivity.class)
                        .putExtra(LoginActivity.EXTRA_RETURN, true));
            } else {
                load(true);
            }
        });

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        body.addView(c, clp);
    }

    // ---------- 渲染 ----------

    private void render(JwApi.Data d) {
        if (!d.ok) {
            showError(d.message, false);
            return;
        }
        // 查询只命中一个班时，数据层已经把课表一起带回来了，直接画网格
        if (!d.kbJson.isEmpty()) {
            JwglxtFetcher.Result parsed = JwglxtFetcher.parse(d.kbJson);
            android.util.Log.d("ZSTS-JW", "推荐课表解析 原始=" + parsed.rawRecords
                    + " 行，课程=" + parsed.courseCount
                    + "，周次解析失败=" + parsed.failedWeekExpr);
            renderKb(d, parsed.ok() ? parsed.timetable : null);
            return;
        }
        current = d;
        if (T_RECOMMEND.equals(type) && !d.filterLabels.isEmpty()) {
            renderFilters(d);
            return;
        }
        paint();
    }

    /** 按当前筛选状态重画（学期筛选、级联选择都会走这里，不重新联网）。 */
    private void paint() {
        if (current == null) return;
        body.removeAllViews();
        body.addView(T_GPA.equals(type) ? bigCard(current) : headCard(current));
        body.addView(refreshHint());
        if (T_GRADES.equals(type)) body.addView(semesterFilter());
        for (int i = 0; i < current.rows.size(); i++) {
            JwApi.Row r = current.rows.get(i);
            if (T_GRADES.equals(type) && semesterPicked > 0
                    && !semesterOptions[semesterPicked].equals(r.tag)) continue;
            final int pos = i;
            View.OnClickListener click = T_RECOMMEND.equals(type)
                    ? v -> loadKb(pos) : null;
            body.addView(rowCard(r, click));
        }
    }

    /** 让「数据什么时候取的、怎么刷新」一眼可见，不假装是实时数据。 */
    private TextView refreshHint() {
        TextView t = new TextView(this);
        String when = cacheAt > 0
                ? new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(cacheAt))
                : "刚刚";
        String how = pull != null ? "下拉可刷新" : "点右上角「刷新」更新";
        t.setText(how + " · 读取于 " + when + "（本页只加载一次）");
        t.setTextSize(11.5f);
        t.setTextColor(getColor(R.color.text_tertiary));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private LinearLayout semesterFilter() {
        java.util.List<String> uniq = new java.util.ArrayList<>();
        for (JwApi.Row r : current.rows) {
            if (!r.tag.isEmpty() && !uniq.contains(r.tag)) uniq.add(r.tag);
        }
        // 教务返回顺序是乱的；「YYYY-YYYY · N」这种定长格式字典序就是时间顺序
        java.util.Collections.sort(uniq);
        uniq.add(0, "全部学期");
        semesterOptions = uniq.toArray(new String[0]);
        if (semesterPicked >= semesterOptions.length) semesterPicked = 0;
        return rowCard(new JwApi.Row("学期筛选", "只看某一学期的成绩",
                semesterOptions[semesterPicked], ""), v ->
                com.tstc.zstangshi.ui.OptionSheet.show(this, "选择学期",
                        semesterOptions, semesterPicked, i -> {
                            semesterPicked = i;
                            paint();
                        }));
    }

    /** 老版推荐课表的形态：年级 / 院系 / 专业 / 班级 四个下拉排两行，下面一个「查询」。 */
    private void renderFilters(JwApi.Data d) {
        body.removeAllViews();
        body.addView(headCard(d));
        if (filterPicked == null || filterPicked.length != d.filterLabels.size()) {
            filterPicked = new int[d.filterLabels.size()];
        }

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setBackground(getDrawable(R.drawable.card_bg));
        grid.setPadding(dp(12), dp(14), dp(12), dp(14));
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 2; col++) {
                int gi = row * 2 + col;
                if (gi >= d.filterLabels.size()) break;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
                if (col == 0) lp.rightMargin = dp(5);
                else lp.leftMargin = dp(5);
                line.addView(selectBox(d, gi), lp);
            }
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(10);
            grid.addView(line, rowLp);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.bottomMargin = dp(12);
        body.addView(grid, glp);

        TextView query = new TextView(this);
        query.setText("查询课表");
        query.setTextSize(14);
        query.setTypeface(Typeface.DEFAULT_BOLD);
        query.setGravity(Gravity.CENTER);
        query.setTextColor(0xFFFFFFFF);
        query.setBackground(getDrawable(R.drawable.btn_primary));
        query.setOnClickListener(v -> loadQuery());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(2);
        body.addView(query, lp);
    }

    /** 仿教务原页的 select：一行一个带边框的下拉框，点开弹选项层。 */
    private TextView selectBox(JwApi.Data d, int gi) {
        String[] names = d.filterNames.get(gi);
        int sel = filterPicked[gi] < names.length ? filterPicked[gi] : 0;
        TextView tv = new TextView(this);
        tv.setText(d.filterLabels.get(gi) + "：" + (sel == 0 ? "全部" : names[sel]) + "  ▾");
        tv.setTextSize(12.5f);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(10), 0, dp(8), 0);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        GradientDrawable g = new GradientDrawable();
        g.setColor(getColor(R.color.bg_surface));
        g.setStroke(1, getColor(R.color.border_subtle));
        g.setCornerRadius(dp(9));
        tv.setBackground(g);
        final int group = gi;
        tv.setOnClickListener(v -> openCascade(group));
        return tv;
    }

    /** 级联选项的后台任务串行化，避免两个线程同时改 activeIds。 */
    private final Object cascadeLock = new Object();

    /**
     * 年级(0)/院系(1) 是静态页面字典，直接弹；
     * 专业(2)/班级(3) 需先按已选年级 / 院系向服务端拉班级清单行，后台取完再弹层。
     * 没选上级条件时给 Toast 提示，不弹空层。
     */
    private void openCascade(int group) {
        if (group == 0 || group == 1) {
            showCascadeSheet(group);
            return;
        }
        if (filterPicked == null) return;
        final int[] picked = filterPicked.clone();
        if (group == 2 && picked[0] == 0 && picked[1] == 0) {
            android.widget.Toast.makeText(this, "请先选择年级或院系", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (group == 3 && picked[2] == 0) {
            android.widget.Toast.makeText(this, "请先选择专业", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final int g = group;
        android.widget.Toast.makeText(this, "正在加载选项…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String[][] opts = api.recommendOptions(picked, g);
            runOnUiThread(() -> {
                if (current == null || isFinishing()) return;
                // 只有一条（占位提示）时不弹层，直接提示
                if (opts[0].length <= 1) {
                    android.widget.Toast.makeText(this, opts[1].length > 0 ? opts[1][0] : "暂无选项",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (g < current.filterIds.size()) {
                    current.filterIds.set(g, opts[0]);
                    current.filterNames.set(g, opts[1]);
                }
                showCascadeSheet(g);
            });
        }, "jw-cascade-open").start();
    }

    private void showCascadeSheet(int group) {
        if (current == null || filterPicked == null) return;
        String[] names = current.filterNames.get(group);
        int sel = Math.max(0, Math.min(filterPicked[group], names.length - 1));
        final int g = group;
        com.tstc.zstangshi.ui.OptionSheet.show(this,
                "选择" + current.filterLabels.get(group), names, sel,
                k -> {
                    // 「选项加载失败，点此重试」：再等一次清单就绪后重开本级
                    if (k >= 0 && k < names.length
                            && "选项加载失败，点此重试".equals(names[k])) {
                        openCascade(g);
                        return;
                    }
                    pickFilter(g, k);
                });
    }

    /**
     * 选中某一级后清空下级，并按「已选条件」重算专业 / 班级的下拉内容。
     * 先即时重绘显示已选值，再在后台把两级选项刷新好，下一次点开就是收窄后的结果。
     */
    private void pickFilter(int group, int index) {
        if (current == null || filterPicked == null) return;
        filterPicked[group] = index;
        for (int j = group + 1; j < filterPicked.length; j++) filterPicked[j] = 0;
        // 上级条件一变，下级旧选项一条都不能留：先作废，再后台按新条件重算
        for (int j = group + 1; j < current.filterIds.size(); j++) {
            current.filterIds.set(j, new String[]{""});
            current.filterNames.set(j, new String[]{"全部"});
        }
        renderFilters(current);

        final int[] picked = filterPicked.clone();
        final JwApi.Data snapshot = current;
        new Thread(() -> {
            String[][] o1 = null, o2 = null, o3 = null;
            if (group < 1) o1 = api.recommendOptions(picked, 1);
            if (group < 2) o2 = api.recommendOptions(picked, 2);
            if (group < 3) o3 = api.recommendOptions(picked, 3);
            final String[][] r1 = o1, r2 = o2, r3 = o3;
            runOnUiThread(() -> {
                if (current != snapshot || isFinishing()) return;
                if (r1 != null) { snapshot.filterIds.set(1, r1[0]); snapshot.filterNames.set(1, r1[1]); }
                if (r2 != null) { snapshot.filterIds.set(2, r2[0]); snapshot.filterNames.set(2, r2[1]); }
                if (r3 != null) { snapshot.filterIds.set(3, r3[0]); snapshot.filterNames.set(3, r3[1]); }
                renderFilters(snapshot);
            });
        }, "jw-cascade-next").start();
    }

    private void loadQuery() {
        if (filterPicked == null) return;
        boolean any = false;
        for (int p : filterPicked) if (p > 0) { any = true; break; }
        if (!any) {
            android.widget.Toast.makeText(this,
                    "请先选择至少一个筛选条件，再点查询", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        showState("正在按条件查询课表…");
        final int[] picked = filterPicked.clone();
        new Thread(() -> {
            JwApi.Data d = api.recommendQuery(picked);
            runOnUiThread(() -> render(d));
        }).start();
    }

    private LinearLayout headCard(JwApi.Data d) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(getDrawable(R.drawable.card_bg));
        c.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView h = new TextView(this);
        h.setText(d.headline);
        h.setTextSize(21);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(getColor(R.color.text_primary));
        c.addView(h);

        if (!d.note.isEmpty()) {
            TextView n = new TextView(this);
            n.setText(d.note);
            n.setTextSize(11.5f);
            n.setTextColor(getColor(R.color.text_tertiary));
            n.setLineSpacing(dp(2), 1f);
            n.setPadding(0, dp(8), 0, 0);
            c.addView(n);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout bigCard(JwApi.Data d) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(getDrawable(R.drawable.card_bg));
        c.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView h = new TextView(this);
        h.setText("学分绩点");
        h.setTextSize(13);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(getColor(R.color.text_tertiary));
        c.addView(h);

        TextView big = new TextView(this);
        big.setText(d.headline);
        big.setTextSize(52);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        big.setTextColor(getColor(R.color.brand));
        big.setPadding(0, dp(10), 0, dp(4));
        c.addView(big);

        TextView label = new TextView(this);
        label.setText("平均学分绩点（GPA）");
        label.setTextSize(12.5f);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(getColor(R.color.text_secondary));
        c.addView(label);

        TextView note = new TextView(this);
        note.setText(d.note);
        note.setTextSize(11.5f);
        note.setGravity(Gravity.CENTER);
        note.setTextColor(getColor(R.color.text_tertiary));
        note.setPadding(0, dp(12), 0, 0);
        note.setLineSpacing(dp(2), 1f);
        c.addView(note);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private LinearLayout rowCard(JwApi.Row r, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(getDrawable(R.drawable.card_bg));
        c.setPadding(dp(15), dp(13), dp(15), dp(13));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(r.title);
        t.setTextSize(14.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(getColor(R.color.text_primary));
        left.addView(t);
        if (!r.sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(r.sub);
            s.setTextSize(11.5f);
            s.setTextColor(getColor(R.color.text_tertiary));
            s.setPadding(0, dp(4), 0, 0);
            left.addView(s);
        }
        c.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        if (!r.value.isEmpty()) {
            TextView v = new TextView(this);
            v.setText(r.value);
            v.setTextSize(16);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setTextColor(getColor(R.color.brand));
            right.addView(v);
        }
        if (!r.extra.isEmpty()) {
            TextView e = new TextView(this);
            e.setText(r.extra);
            e.setTextSize(11);
            e.setTextColor(getColor(R.color.text_tertiary));
            right.addView(e);
        }
        c.addView(right);
        if (click != null) {
            c.setClickable(true);
            c.setOnClickListener(click);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    // ---------- 推荐课表：点班级 → 查课表 → 画周课表网格 ----------

    private void loadKb(int pos) {
        showState("正在载入该班课表…");
        new Thread(() -> {
            JwApi.Data d = api.recommendKb(pos);
            Timetable t = null;
            if (d.ok) {
                JwglxtFetcher.Result parsed = JwglxtFetcher.parse(d.kbJson);
                if (parsed.ok()) t = parsed.timetable;
            }
            final Timetable tt = t;
            runOnUiThread(() -> renderKb(d, tt));
        }).start();
    }

    private void renderKb(JwApi.Data d, Timetable t) {
        kbData = d;
        kbTimetable = t;
        kbWeek = 1;
        kbTotalWeeks = t != null && t.semester.totalWeeks > 0 ? t.semester.totalWeeks : 20;
        // 推荐课表的周次写法比个人课表杂，解析失败的时段兜底成「全周」，
        // 否则这些课在任何一周都画不出来，看起来就是「课程不全」。
        if (t != null) {
            int total = t.semester.totalWeeks > 0 ? t.semester.totalWeeks : 20;
            for (Timetable.Course c : t.courses) {
                for (Timetable.Session s : c.sessions) {
                    if (!s.weeks.isEmpty()) continue;
                    for (int w = 1; w <= total; w++) s.weeks.add(w);
                    if (s.weeksRaw.isEmpty()) s.weeksRaw = "全周";
                }
            }
        }
        paintKb();
    }

    private void paintKb() {
        body.removeAllViews();
        kbGrid = null;

        body.addView(com.tstc.zstangshi.ui.HeaderBar
                .backLink(this, "重新筛选", v -> load()));

        if (!kbData.ok) {
            showErrorKeepBack(kbData.message);
            return;
        }
        body.addView(headCard(kbData));

        if (kbTimetable == null) {
            // 网格解析不出来时也不假装成功：把教务原始行如实列出来
            addKbFallbackRows(kbData.kbJson);
            return;
        }
        // 周次切换模块 + 网格都是持久视图：翻周走 applyKbWeek() 只换数据，
        // 这样跟手翻页动画（滑出 → 离屏换数据 → 滑入）才不会被打断
        body.addView(kbWeekBar());

        String start = kbStartDate();
        ScheduleSettings sch = store.schedule();
        sch.ensureCovers(ScheduleSettings.maxSectionUsed(kbTimetable));

        kbGrid = new TimetableGridView(this);
        kbGrid.setNight(night());
        kbGrid.setSchedule(sch);
        kbGrid.setOnCourseClickListener(this::showCourseDetail);
        kbSwipe = new KbSwipe(this);
        kbGrid.setOnTouchListener(kbSwipe);
        body.addView(kbGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(620)));
        applyKbWeek();
    }

    /**
     * 课表起始周一：教务给的优先。推荐课表解析出来的 Timetable 不带学期信息，
     * 兜底依次取「我的」本地已同步课表的起始日（同校同学期口径一致）→ 官方校历
     * → 今天所在周周一。之前直接退今天所在周，currentWeek 恒等于 1，
     * 「回到本周」和周首日期就全错了。
     */
    private String kbStartDate() {
        if (Dates.valid(kbTimetable.semester.startDate)) return kbTimetable.semester.startDate;
        Timetable local = store.loadTimetable();
        if (local != null && Dates.valid(local.semester.startDate)) {
            return local.semester.startDate;
        }
        com.tstc.zstangshi.data.AcademicCalendar.Term term =
                com.tstc.zstangshi.data.AcademicCalendar.termFor(
                        com.tstc.zstangshi.data.WeekMath.todayDateLabel());
        if (term != null && Dates.valid(term.classStart)) {
            return WeekMath.normalizeToMonday(term.classStart);
        }
        return WeekMath.normalizeToMonday(WeekMath.todayDateLabel());
    }

    /** 只更新网格数据与标签，不重建视图 —— 翻页动画的离屏中点只做这件事。 */
    private void applyKbWeek() {
        if (kbTimetable == null) return;
        String start = kbStartDate();
        if (kbWeekLabel != null) kbWeekLabel.setText("第 " + kbWeek + " 周");
        if (kbGrid != null) {
            kbGrid.setWeekDates(WeekMath.weekDates(start, kbWeek));
            kbGrid.setData(kbTimetable, WeekOverlay.build(kbTimetable, start, kbWeek), kbWeek);
        }
        updateKbFab();
    }

    /** 教务「本周」的规范值（学期外由 WeekMath 夹到 1 / 总周数）。 */
    private int currentKbWeek() {
        if (kbTimetable == null) return 1;
        return Math.max(1, Math.min(kbTotalWeeks,
                WeekMath.currentWeek(kbStartDate(), kbTotalWeeks)));
    }

    /** 与个人课表一致：翻离本周才浮出「回到本周」，回本周自动淡出。 */
    private void updateKbFab() {
        if (kbFab == null) return;
        boolean show = kbTimetable != null && !kbTimetable.courses.isEmpty()
                && kbWeek != currentKbWeek();
        if (show == kbFabShown) return;
        kbFabShown = show;
        kbFab.animate().cancel();
        if (show) {
            kbFab.setVisibility(View.VISIBLE);
            kbFab.setScaleX(0.82f);
            kbFab.setScaleY(0.82f);
            kbFab.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(230)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        } else {
            kbFab.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                    .setDuration(160)
                    .withEndAction(() -> {
                        if (!kbFabShown) kbFab.setVisibility(View.GONE);
                    }).start();
        }
    }

    /** 周次切换模块：外观照搬个人课表 —— ‹ [第 N 周] ›，胶囊点开全部教学周弹层。 */
    private LinearLayout kbWeekBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), dp(2), dp(2), dp(8));

        // 左侧占位把切换器推到右边（个人课表里这个位置是标题，模块同样靠右）
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView prev = kbStepBtn("‹", -1);
        bar.addView(prev);

        kbWeekLabel = new TextView(this);
        kbWeekLabel.setTextSize(13);
        kbWeekLabel.setGravity(Gravity.CENTER);
        kbWeekLabel.setTextColor(getColor(R.color.text_primary));
        GradientDrawable weekBg = new GradientDrawable();
        weekBg.setColor(getColor(R.color.brand_soft));
        weekBg.setCornerRadius(dp(9));
        kbWeekLabel.setBackground(weekBg);
        // 高度固定 dp(34)，与箭头同高，模块整体一条中线（与个人课表同规格）
        kbWeekLabel.setPadding(0, 0, 0, 0);
        kbWeekLabel.setOnClickListener(v -> openKbWeekPicker());
        bar.addView(kbWeekLabel, new LinearLayout.LayoutParams(dp(82), dp(34)));

        TextView next = kbStepBtn("›", 1);
        bar.addView(next);
        return bar;
    }

    private TextView kbStepBtn(String s, int delta) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(18);
        v.setGravity(Gravity.CENTER);
        // 单引号字形在字体里天然偏上，关掉字体 padding 才能光学居中
        v.setIncludeFontPadding(false);
        v.setTextColor(getColor(R.color.text_primary));
        v.setContentDescription(delta < 0 ? "上一周" : "下一周");
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(34)));
        v.setOnClickListener(x -> {
            int next = kbWeek + delta;
            if (next < 1 || next > kbTotalWeeks) return;
            if (kbSwipe != null) kbSwipe.flipTo(next);
        });
        return v;
    }

    /** 点顶部周次胶囊 → 底部弹层铺开全部教学周，点一格直接跳过去（与个人课表同一套）。 */
    private void openKbWeekPicker() {
        final com.tstc.zstangshi.ui.Sheet.Handle[] holder =
                new com.tstc.zstangshi.ui.Sheet.Handle[1];

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(4), dp(18), dp(14));

        TextView t = new TextView(this);
        t.setText("选择周次");
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(getColor(R.color.text_primary));
        t.setPadding(0, 0, 0, dp(4));
        box.addView(t);

        TextView hint = new TextView(this);
        hint.setText("当前第 " + kbWeek + " 周 · 共 " + kbTotalWeeks + " 周");
        hint.setTextSize(11.5f);
        hint.setTextColor(getColor(R.color.text_tertiary));
        hint.setPadding(0, 0, 0, dp(10));
        box.addView(hint);

        final int cols = 5;
        int rows = (kbTotalWeeks + cols - 1) / cols;
        for (int r = 0; r < rows; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, dp(3), 0, dp(3));
            for (int c = 0; c < cols; c++) {
                final int w = r * cols + c + 1;
                TextView cell = new TextView(this);
                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(13);
                if (w <= kbTotalWeeks) {
                    cell.setText(String.valueOf(w));
                    boolean picked = w == kbWeek;
                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(dp(10));
                    if (picked) {
                        bg.setColor(getColor(R.color.brand));
                        cell.setTextColor(0xFFFFFFFF);
                        cell.setTypeface(Typeface.DEFAULT_BOLD);
                    } else {
                        bg.setColor(night() ? 0x18FFFFFF : 0x140062CC);
                        cell.setTextColor(getColor(R.color.text_primary));
                    }
                    cell.setBackground(bg);
                    cell.setOnClickListener(v -> {
                        if (holder[0] != null) holder[0].dismiss();
                        if (kbSwipe != null) kbSwipe.flipTo(w);
                    });
                } else {
                    cell.setText("");
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
                lp.leftMargin = c == 0 ? 0 : dp(6);
                line.addView(cell, lp);
            }
            box.addView(line);
        }
        holder[0] = com.tstc.zstangshi.ui.Sheet.show(this, box,
                dp(96) + rows * dp(48));
    }

    /** 推荐课表里的课程块也要能点开详情（和课表页同一套底部弹层）。 */
    private void showCourseDetail(Timetable.Course c, Timetable.Session s, int day, int start) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(16));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.view.View dot = new android.view.View(this);
        android.graphics.drawable.GradientDrawable dg =
                new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dg.setColor(getColor(R.color.brand));
        dot.setBackground(dg);
        head.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView name = new TextView(this);
        name.setText(c.name);
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getColor(R.color.text_primary));
        name.setPadding(dp(10), 0, 0, 0);
        head.addView(name);
        box.addView(head);

        String teacher = s.teacher == null || s.teacher.isEmpty() ? c.teacher : s.teacher;
        TextView sub = new TextView(this);
        sub.setText("周" + "一二三四五六日".charAt(Math.max(0, Math.min(6, day - 1)))
                + " · 第 " + s.startSection + "-" + s.endSection + " 节"
                + (teacher.isEmpty() ? "" : " · " + teacher));
        sub.setTextSize(12);
        sub.setTextColor(getColor(R.color.text_tertiary));
        sub.setPadding(0, dp(6), 0, dp(12));
        box.addView(sub);

        ScheduleSettings sch = store.schedule();
        int rows = 0;
        box.addView(fieldRow("时间", sch.startTime(s.startSection) + " - "
                + sch.endTime(s.endSection)));
        rows++;
        if (!s.location.isEmpty()) {
            box.addView(fieldRow("地点", s.location
                    + (s.campus.isEmpty() ? "" : " · " + s.campus)));
            rows++;
        } else if (!s.campus.isEmpty()) {
            box.addView(fieldRow("校区", s.campus));
            rows++;
        }
        if (!s.weeksRaw.isEmpty()) {
            box.addView(fieldRow("周次", s.weeksRaw));
            rows++;
        }
        if (c.credit > 0) {
            box.addView(fieldRow("学分", String.format(Locale.CHINA, "%.1f", c.credit)));
            rows++;
        }
        com.tstc.zstangshi.ui.Sheet.show(this, box, dp(120) + rows * dp(38));
    }

    private LinearLayout fieldRow(String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(8), 0, dp(8));
        TextView kk = new TextView(this);
        kk.setText(k);
        kk.setTextSize(13);
        kk.setTextColor(getColor(R.color.text_tertiary));
        r.addView(kk, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView vv = new TextView(this);
        vv.setText(v);
        vv.setTextSize(13);
        vv.setTypeface(Typeface.DEFAULT_BOLD);
        vv.setTextColor(getColor(R.color.text_primary));
        r.addView(vv);
        return r;
    }

    private void showErrorKeepBack(String msg) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(getDrawable(R.drawable.card_bg));
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextSize(14);
        t.setTextColor(getColor(R.color.text_primary));
        c.addView(t);
        body.addView(c);
    }

    private void addKbFallbackRows(String json) {
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("kbList");
            if (arr == null) return;
            for (int i = 0; i < arr.length() && i < 40; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = str(o, "kcmc", "kcmcmc");
                if (name.isEmpty()) continue;
                String sub = "周" + str(o, "xqj", "xqjmc") + " · "
                        + str(o, "jc", "jcs") + " 节 · " + str(o, "cdmc", "jxdd")
                        + " · " + str(o, "xm", "jsxm");
                body.addView(rowCard(new JwApi.Row(name, sub, str(o, "zcd"), ""), null));
            }
        } catch (Exception ignored) {
        }
    }

    private static String str(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    /**
     * 跟手的周次翻页：与个人课表（TimetablePage.WeekSwipe）同一套节奏 ——
     * MOVE 按手指实时平移 + 轻微淡出（到边界加阻尼）；松手先滑出屏幕，
     * 在 alpha=0 的离屏阶段才做唯一一次换周数据（applyKbWeek），再从另一侧滑入。
     * 换周永远发生在看不见的时候，掉帧不会被眼睛捕捉到。
     *
     * 静态嵌套类 + page 字段，避开 d8 对 this$0 的内部错误。
     */
    private static final class KbSwipe implements View.OnTouchListener {

        private static final android.view.animation.PathInterpolator EASE_OUT =
                new android.view.animation.PathInterpolator(0.22f, 0.8f, 0.30f, 1f);
        private static final android.view.animation.PathInterpolator EASE_IN =
                new android.view.animation.PathInterpolator(0.4f, 0f, 0.8f, 0.4f);

        private final FeatureActivity page;
        private final android.view.GestureDetector detector;
        private final int slop;
        private float downX, downY, lastDx;
        private boolean dragging;
        private boolean animating;

        KbSwipe(FeatureActivity page) {
            this.page = page;
            slop = android.view.ViewConfiguration.get(page).getScaledTouchSlop();
            detector = new android.view.GestureDetector(page, new KbFling(page, this));
        }

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            if (page.kbTimetable == null || page.kbTimetable.courses.isEmpty()) return false;
            if (animating) return true; // 翻页过程中吞掉事件，避免误触课程格
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    lastDx = 0f;
                    dragging = false;
                    detector.onTouchEvent(e);
                    return false;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    if (!dragging) {
                        detector.onTouchEvent(e);
                        // 横向意图一旦确立（横移超过 slop 且不输纵移）立刻认领。
                        // 认领条件太苛 + 不锁父容器时，外层 ScrollView 会先把
                        // 事件截走，横滑就会“时灵时不灵”。
                        if (Math.abs(dx) < slop || Math.abs(dx) < Math.abs(dy)) {
                            return false;
                        }
                        dragging = true;
                        // 锁住父容器拦截：之后的 MOVE 不会中途被 ScrollView 抢走
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        page.kbGrid.setPressed(false);
                        page.kbGrid.cancelLongPress();
                    }
                    lastDx = dx;
                    drag(dx);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (dragging) {
                        dragging = false;
                        page.kbGrid.setPressed(false);
                        finishDrag(lastDx);
                        return true;
                    }
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                        lastDx = 0f;
                    }
                    detector.onTouchEvent(e);
                    return false;
                }
                default:
                    detector.onTouchEvent(e);
                    return false;
            }
        }

        private void drag(float dx) {
            float w = page.kbGrid.getWidth();
            if (w <= 0) return;
            boolean edge = (dx < 0 && page.kbWeek >= page.kbTotalWeeks)
                    || (dx > 0 && page.kbWeek <= 1);
            float d = dx * (edge ? 0.28f : 0.82f);
            page.kbGrid.setTranslationX(d);
            float p = Math.min(1f, Math.abs(d) / (w * 0.6f));
            page.kbGrid.setAlpha(1f - 0.28f * p);
        }

        private void finishDrag(float dx) {
            float w = page.kbGrid.getWidth();
            float threshold = Math.max(page.dp(48), w * 0.16f);
            int dir = dx < 0 ? 1 : -1;
            int target = page.kbWeek + dir;
            if (Math.abs(dx) < threshold || target < 1 || target > page.kbTotalWeeks) {
                springBack();
            } else {
                flip(dir, target);
            }
        }

        private void springBack() {
            animating = true;
            View g = page.kbGrid;
            g.animate().cancel();
            g.animate().translationX(0f).alpha(1f)
                    .setDuration(260).setInterpolator(EASE_OUT)
                    .withEndAction(() -> animating = false).start();
        }

        /** 按钮 / 周次格子 / 「回到本周」共用这一套过渡。 */
        void flipTo(int target) {
            if (target == page.kbWeek || animating) return;
            flip(target > page.kbWeek ? 1 : -1, target);
        }

        private void flip(int dir, int target) {
            if (target < 1 || target > page.kbTotalWeeks) {
                springBack();
                return;
            }
            animating = true;
            final View g = page.kbGrid;
            g.animate().cancel();
            float w = g.getWidth() > 0 ? g.getWidth() : page.dp(320);
            final float out = -dir * w * 0.32f;
            g.animate().translationX(out).alpha(0f)
                    .setDuration(140).setInterpolator(EASE_IN)
                    .withEndAction(() -> {
                        page.kbWeek = target;       // 离屏时换数据，只重绘一次
                        page.applyKbWeek();
                        g.setTranslationX(-out);
                        g.setAlpha(0f);
                        g.animate().translationX(0f).alpha(1f)
                                .setDuration(300).setInterpolator(EASE_OUT)
                                .withEndAction(() -> animating = false).start();
                    }).start();
        }
    }

    /** 快速甩动（MOVE 事件来不及形成拖拽）时的兜底翻页。 */
    private static final class KbFling
            extends android.view.GestureDetector.SimpleOnGestureListener {

        private final FeatureActivity page;
        private final KbSwipe ctrl;

        KbFling(FeatureActivity page, KbSwipe ctrl) {
            this.page = page;
            this.ctrl = ctrl;
        }

        @Override
        public boolean onDown(android.view.MotionEvent e) {
            return true;
        }

        @Override
        public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                               float vx, float vy) {
            if (e1 == null || e2 == null || ctrl.dragging) return false;
            float dx = e2.getX() - e1.getX();
            float dy = e2.getY() - e1.getY();
            if (Math.abs(dx) < page.dp(40) || Math.abs(dx) < Math.abs(dy)) {
                return false;
            }
            int dir = dx < 0 ? 1 : -1;
            int target = page.kbWeek + dir;
            if (target < 1 || target > page.kbTotalWeeks) return true;
            ctrl.flip(dir, target);
            return true;
        }
    }
}
