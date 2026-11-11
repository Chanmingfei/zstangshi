package com.tstc.zstangshi.pages;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tstc.zstangshi.FeatureActivity;
import com.tstc.zstangshi.R;
import com.tstc.zstangshi.data.Dates;
import com.tstc.zstangshi.data.DayPlan;
import com.tstc.zstangshi.data.ScheduleSettings;
import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.data.Timetable;
import com.tstc.zstangshi.data.WeekMath;
import com.tstc.zstangshi.ui.PullRefreshLayout;

import java.util.Calendar;
import java.util.List;

/**
 * 首页：今天还有什么课。
 *
 * 与旧版（WebView 里的 #/home）一致的三块内容：
 *   1. 顶部一句「今天周二 · 第 10 周 · 11.10」；
 *   2. 一张「正在上 / 下一节」的大卡（课名、时间、教室、还有多久）；
 *   3. 今日全部课程，已经上完的置灰并打勾。
 *
 * 数据全部来自本地课表 + {@link DayPlan}（它会算上停课、调课、放假），
 * 所以断网也能用。下拉刷新：先按本地数据重画，再静默向教务对一次账 ——
 * 教务会话已被超时踢掉时，这次刷新顺带自动重登并重拉课表，全程只显示刷新动画。
 */
public class HomePage extends PullRefreshLayout {

    private final Activity host;
    private final Store store;
    private final LinearLayout body;

    public HomePage(Activity host, Store store) {
        super(host);
        this.host = host;
        this.store = store;
        setBackgroundColor((store.darkNow() ? 0xFF0F1520 : host.getColor(R.color.bg_page)));

        ScrollView sc = scrollView();
        sc.setBackgroundColor(store.darkNow() ? 0xFF0F1520 : host.getColor(R.color.bg_page));
        sc.setFillViewport(true);

        body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(24));
        sc.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setOnRefresh(this::refreshFromJw);
        build();
    }

    /**
     * 首页下拉刷新。本地部分立刻重画（日期时间推进）；然后只向教务核对一次
     * 登录状态 —— 会话被超时踢掉就自动重登续期，不动课表数据（个人课表以
     * 「我的」页手动同步为准）。不弹提示、不摆加载页：只有下拉动画本身。
     */
    private void refreshFromJw() {
        build();
        if (store.user().isEmpty() || store.pass().isEmpty()) {
            setRefreshing(false);
            return;
        }
        new Thread(() -> {
            long start = android.os.SystemClock.elapsedRealtime();
            try {
                // 只登录不同步课表：登录成功 = 会话续上（被踢了也会自动重登）
                com.tstc.zstangshi.jw.Auth.loginOnly(store.user(), store.pass(), store);
            } catch (Exception e) {
                android.util.Log.d("ZSTS", "首页刷新核对登录态失败: " + e);
            }
            // 会话正常时核对一瞬就完，动画立刻收起会让人怀疑刷新没发生：
            // 保底让动画转满 2 秒再收，作为「刷新过了」的安抚反馈
            long wait = Math.max(0L, 2000L - (android.os.SystemClock.elapsedRealtime() - start));
            host.runOnUiThread(() -> {
                build();
                postDelayed(this::setRefreshingOff, wait);
            });
        }, "home-refresh").start();
    }

    private void setRefreshingOff() {
        setRefreshing(false);
    }

    private void build() {
        body.removeAllViews();

        Timetable t = store.loadTimetable();
        String today = WeekMath.todayDateLabel();
        int nowMin = nowMinute();
        int dow = Dates.dayOfWeek(today);

        // ── 顶部日期行 ──
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(4), 0, dp(4), dp(12));

        TextView big = new TextView(host);
        big.setTextSize(24);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextColor(host.getColor(R.color.text_primary));
        big.setText(greeting());

        TextView sub = new TextView(host);
        sub.setTextSize(13);
        sub.setTextColor(host.getColor(R.color.text_tertiary));
        sub.setPadding(0, dp(6), 0, 0);
        String weekLabel = "";
        if (t != null && Dates.valid(t.semester.startDate)) {
            int w = WeekMath.currentWeek(t.semester.startDate, t.semester.totalWeeks);
            if (w >= 1) weekLabel = " · 第 " + w + " 周";
        }
        sub.setText("今天周" + "一二三四五六日".charAt(Math.max(0, Math.min(6, dow - 1)))
                + " · " + shortDate(today) + weekLabel);

        head.addView(big);
        head.addView(sub);
        body.addView(head);

        if (t == null || t.courses.isEmpty()) {
            body.addView(emptyCard("还没有课表", "到「我的」里登录教务同步一次"));
            addShortcuts();
            return;
        }

        // ── 今天的课 ──
        ScheduleSettings sch = store.schedule();
        sch.ensureCovers(ScheduleSettings.maxSectionUsed(t));
        int n = ScheduleSettings.MAX_SECTIONS;
        int[] ss = new int[n + 1];
        int[] se = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            ss[i] = sch.startMinutes(i);
            se[i] = sch.endMinutes(i);
        }
        List<DayPlan.Item> items = DayPlan.of(t, t.semester.startDate, today, ss, se);

        if (items.isEmpty()) {
            body.addView(emptyCard("今天没课", "好好休息，或者预习一下明天的"));
            addShortcuts();
            return;
        }

        DayPlan.Item current = null, next = null;
        int left = 0;
        for (DayPlan.Item it : items) {
            if (it.startMin <= nowMin && it.endMin > nowMin) {
                current = it;
                break;
            }
            if (it.startMin > nowMin) {
                next = it;
                break;
            }
        }

        DayPlan.Item focus = current != null ? current : next;
        if (focus != null) {
            body.addView(focusCard(focus, current != null, nowMin));
        }

        TextView listTitle = new TextView(host);
        listTitle.setText("今天的课 · " + items.size() + " 门");
        listTitle.setTextSize(13);
        listTitle.setTypeface(Typeface.DEFAULT_BOLD);
        listTitle.setTextColor(host.getColor(R.color.text_tertiary));
        listTitle.setPadding(dp(4), dp(6), 0, dp(8));
        body.addView(listTitle);

        for (DayPlan.Item it : items) {
            body.addView(courseRow(it, nowMin));
        }

        addShortcuts();
    }

    /** 不管今天有没有课，宫格和学工侧门都得在（以前「今天没课」直接 return 把它们吞了）。 */
    private void addShortcuts() {
        body.addView(gridTitle("常用功能"));
        body.addView(quickGrid());
        body.addView(affairsCard());
        body.addView(campusArt());
    }

    /**
     * 页脚那张校园线稿：不着卡片、不加底色，直接压在页面背景上。
     * 原图是白底蓝线的位图，已经处理成「透明底 + 纯白线」的单色蒙版，
     * 这里用品牌色染色，深浅色主题共用一张图、颜色自动跟着换。
     */
    private android.widget.ImageView campusArt() {
        android.widget.ImageView iv = new android.widget.ImageView(host);
        iv.setImageResource(R.drawable.home_campus);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setAdjustViewBounds(true);
        iv.setColorFilter(host.getColor(R.color.brand));
        iv.setContentDescription("唐山师范学院校园");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(4);
        iv.setLayoutParams(lp);
        return iv;
    }

    /** 「常用功能」小标题：和旧版一样，宫格上方一行灰字。 */
    private TextView gridTitle(String s) {
        TextView v = new TextView(host);
        v.setText(s);
        v.setTextSize(13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(host.getColor(R.color.text_tertiary));
        v.setPadding(dp(4), dp(18), 0, dp(10));
        return v;
    }

    /**
     * 首页宫格。8 个位置铺满两行：课表 / 校历是本地页，成绩 / 绩点 / 选课 / 推荐课表
     * 走 {@link com.tstc.zstangshi.FeatureActivity} 的原生教务查询，
     * 「实时公交」是独立的原生二级页，只有「更多功能」打开原教务站点网页。
     *
     * 原来第 8 格是「学工系统」，和下面那张 {@link #affairsCard()} 横条入口重复，
     * 已让位给实时公交 —— 学工只保留横条一处，避免同一页出现两个同义入口。
     */
    private LinearLayout quickGrid() {
        LinearLayout wrap = new LinearLayout(host);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(host.getDrawable(R.drawable.card_bg));
        wrap.setPadding(dp(7), dp(14), dp(7), dp(8));

        LinearLayout row1 = gridRow();
        row1.addView(cell(R.drawable.ic_nav_timetable, "我的课表", () -> tab(1)));
        row1.addView(cell(R.drawable.ic_nav_grades, "成绩",
                () -> feature(FeatureActivity.T_GRADES, "成绩")));
        row1.addView(cell(R.drawable.ic_quick_gpa, "学分绩点",
                () -> feature(FeatureActivity.T_GPA, "学分绩点")));
        row1.addView(cell(R.drawable.ic_nav_calendar, "校历", this::openCalendar));
        wrap.addView(row1);

        LinearLayout row2 = gridRow();
        row2.addView(cell(R.drawable.ic_quick_select, "选课",
                () -> feature(FeatureActivity.T_SELECTION, "自主选课")));
        row2.addView(cell(R.drawable.ic_quick_recommend, "推荐课表",
                () -> feature(FeatureActivity.T_RECOMMEND, "推荐课表")));
        row2.addView(cell(R.drawable.ic_quick_bus, "实时公交",
                () -> com.tstc.zstangshi.BusActivity.open(host)));
        row2.addView(cell(R.drawable.ic_quick_more, "更多功能", () -> browse(JWXT, "教务系统")));
        wrap.addView(row2);
        return wrap;
    }

    private void feature(String type, String title) {
        com.tstc.zstangshi.FeatureActivity.open(host, type, title);
    }

    /** 首页的学工入口：已经能进应用了，就不再是「仅登录」侧门。 */
    private LinearLayout affairsCard() {
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(host.getDrawable(R.drawable.card_bg));
        c.setPadding(dp(14), dp(13), dp(14), dp(13));
        c.setClickable(true);
        c.setOnClickListener(v -> browse(XUEGONG, "学工系统"));

        TextView ic = new TextView(host);
        ic.setText("↗");
        ic.setTextSize(18);
        ic.setTypeface(Typeface.DEFAULT_BOLD);
        ic.setGravity(Gravity.CENTER);
        ic.setTextColor(host.getColor(R.color.brand));
        ic.setBackground(roundRect(host.getColor(R.color.brand_soft), dp(10)));
        c.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout text = new LinearLayout(host);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        TextView t = new TextView(host);
        t.setText("登录学工系统");
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(host.getColor(R.color.text_primary));
        text.addView(t);
        TextView d = new TextView(host);
        d.setText("奖助贷、请假、综合素质 · 外部网页");
        d.setTextSize(11);
        d.setTextColor(host.getColor(R.color.text_tertiary));
        d.setPadding(0, dp(2), 0, 0);
        text.addView(d);
        c.addView(text, tlp);

        TextView arrow = new TextView(host);
        arrow.setText("›");
        arrow.setTextSize(22);
        arrow.setTextColor(host.getColor(R.color.text_tertiary));
        c.addView(arrow);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    // 教务与学工原站点。「更多功能」是教务首页，学工系统只提供 http。
    // 教务在校园网下 443 常常不通，这里直接用登录时探测过的 http（WebView 另有协议兜底）。
    private static final String JWXT = "http://jwxt.tstc.edu.cn/xtgl/index_initMenu.html";
    private static final String XUEGONG = "http://sws.tstc.edu.cn/XGTSTC/Phone/#/login";

    private LinearLayout gridRow() {
        LinearLayout r = new LinearLayout(host);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    private LinearLayout cell(int icon, String label, Runnable onClick) {
        LinearLayout cell = new LinearLayout(host);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(8), 0, dp(8));
        cell.setClickable(true);
        cell.setBackground(ripple());
        cell.setOnClickListener(v -> onClick.run());

        TextView ic = new TextView(host);
        ic.setTextSize(0);
        ic.setGravity(Gravity.CENTER);
        android.graphics.drawable.Drawable d =
                host.getDrawable(icon).mutate();
        d.setTint(host.getColor(R.color.brand));
        ic.setCompoundDrawablesWithIntrinsicBounds(null, d, null, null);
        int pad = dp(11);
        ic.setPadding(pad, pad, pad, pad);
        ic.setBackground(roundRect(host.getColor(R.color.brand_soft), dp(14)));
        cell.addView(ic, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView t = new TextView(host);
        t.setText(label);
        t.setTextSize(11.5f);
        t.setTextColor(host.getColor(R.color.text_secondary));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, 0);
        cell.addView(t);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cell.setLayoutParams(lp);
        return cell;
    }

    private android.graphics.drawable.Drawable roundRect(int color, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private android.graphics.drawable.Drawable ripple() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(14));
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x1A0062CC), g, null);
    }

    private void tab(int i) {
        if (host instanceof com.tstc.zstangshi.MainActivity) {
            ((com.tstc.zstangshi.MainActivity) host).switchTab(i);
        }
    }

    private void openCalendar() {
        host.startActivity(new android.content.Intent(host,
                com.tstc.zstangshi.CalendarActivity.class));
    }

    /** 用内置 WebView 打开，和 App 共享登录态，不跳系统浏览器。 */
    private void browse(String url, String title) {
        com.tstc.zstangshi.WebViewActivity.open(host, url, title);
    }

    private LinearLayout focusCard(DayPlan.Item it, boolean ongoing, int nowMin) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(host.getDrawable(R.drawable.card_bg));

        TextView tag = new TextView(host);
        tag.setText(ongoing ? "正在上" : "下一节");
        tag.setTextSize(11.5f);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setTextColor(host.getColor(R.color.brand));

        TextView name = new TextView(host);
        name.setText(it.name);
        name.setTextSize(19);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(host.getColor(R.color.text_primary));
        name.setPadding(0, dp(8), 0, 0);

        TextView meta = new TextView(host);
        meta.setTextSize(12.5f);
        meta.setTextColor(host.getColor(R.color.text_secondary));
        meta.setPadding(0, dp(8), 0, 0);
        String loc = it.placeLabel().isEmpty() ? "" : " · " + it.placeLabel();
        meta.setText(it.timeLabel() + loc);

        TextView cd = new TextView(host);
        cd.setTextSize(12.5f);
        cd.setTextColor(host.getColor(R.color.brand));
        cd.setPadding(0, dp(8), 0, 0);
        int mins = ongoing ? it.endMin - nowMin : it.startMin - nowMin;
        cd.setText(ongoing ? "还剩 " + mins + " 分钟下课" : mins + " 分钟后上课");

        card.addView(tag);
        card.addView(name);
        card.addView(meta);
        card.addView(cd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout courseRow(DayPlan.Item it, int nowMin) {
        boolean done = it.endMin <= nowMin;

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(host.getDrawable(R.drawable.card_bg));

        // 左侧色条
        TextView bar = new TextView(host);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(3), dp(30));
        blp.rightMargin = dp(10);
        bar.setBackgroundColor(host.getColor(barColor(it.colorIndex)));
        row.addView(bar, blp);

        LinearLayout mid = new LinearLayout(host);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(host);
        name.setText(it.name);
        name.setTextSize(14);
        name.setTypeface(done ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        name.setTextColor(host.getColor(done ? R.color.text_tertiary : R.color.text_primary));

        TextView line = new TextView(host);
        line.setTextSize(11.5f);
        line.setTextColor(host.getColor(R.color.text_tertiary));
        String loc = it.placeLabel().isEmpty() ? "" : "  " + it.placeLabel();
        line.setText(it.timeLabel() + loc);

        mid.addView(name);
        mid.addView(line);
        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = new TextView(host);
        state.setTextSize(11);
        state.setTextColor(host.getColor(R.color.text_tertiary));
        state.setText(done ? "已上完" : (it.startMin <= nowMin ? "进行中" : ""));
        row.addView(state);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private LinearLayout emptyCard(String title, String sub) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(16), dp(36), dp(16), dp(36));
        card.setBackground(host.getDrawable(R.drawable.card_bg));

        TextView a = new TextView(host);
        a.setText(title);
        a.setTextSize(15);
        a.setTypeface(Typeface.DEFAULT_BOLD);
        a.setTextColor(host.getColor(R.color.text_primary));
        a.setGravity(Gravity.CENTER);

        TextView b = new TextView(host);
        b.setText(sub);
        b.setTextSize(12.5f);
        b.setTextColor(host.getColor(R.color.text_tertiary));
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(8), 0, 0);

        card.addView(a);
        card.addView(b);
        return card;
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 6) return "夜深了";
        if (h < 11) return "早上好";
        if (h < 14) return "中午好";
        if (h < 18) return "下午好";
        return "晚上好";
    }

    private static String shortDate(String ymd) {
        String[] p = ymd.split("-");
        if (p.length != 3) return ymd;
        return Integer.parseInt(p[1]) + " 月 " + Integer.parseInt(p[2]) + " 日";
    }

    private static int nowMinute() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private int barColor(int idx) {
        int[] ids = {R.color.course_0, R.color.course_1, R.color.course_2, R.color.course_3,
                R.color.course_4, R.color.course_5, R.color.course_6, R.color.course_7};
        return ids[Math.abs(idx) % 8];
    }

    public void reload() {
        build();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
