package com.tstc.zstangshi.pages;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tstc.zstangshi.FeatureActivity;
import com.tstc.zstangshi.R;
import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.jw.JwApi;
import com.tstc.zstangshi.ui.OptionSheet;
import com.tstc.zstangshi.ui.PullRefreshLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 底栏「成绩」页：点开直接展示教务成绩。
 *
 * 数据用 FeatureActivity 同一份本地快照（store.feature("grades")），有快照就秒开、
 * 不联网；没有快照才后台取一次并落盘。顶栏「刷新」与顶部下拉刷新都能强制更新，
 * 「更多」进完整页（那里有学期筛选和下拉刷新）。不再先摆本地课表统计概览。
 */
public class GradesPage extends PullRefreshLayout {

    private final Activity host;
    private final Store store;
    private final LinearLayout body;
    private volatile boolean loading = false;
    private JwApi.Data currentData;
    private int semesterPicked = 0;
    private String[] semesterOptions = {"全部学期"};

    public GradesPage(Activity host, Store store) {
        super(host);
        this.host = host;
        this.store = store;
        setBackgroundColor(store.darkNow() ? 0xFF0F1520 : host.getColor(R.color.bg_page));

        ScrollView sc = scrollView();
        sc.setBackgroundColor(store.darkNow() ? 0xFF0F1520 : host.getColor(R.color.bg_page));
        sc.setClipToPadding(false);

        body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(24));
        sc.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setOnRefresh(() -> load(true));
        build();
    }

    /** 只重画，不联网；MainActivity 切回这个 tab 时调用，保证和完整页刷新后的缓存一致。 */
    public void reload() {
        build();
    }

    private void build() {
        body.removeAllViews();
        body.addView(bar(), lp());

        String cached = store.feature(FeatureActivity.T_GRADES);
        JwApi.Data d = cached.isEmpty() ? null : JwApi.Data.fromJson(cached);
        if (d == null || !d.ok) {
            currentData = null;
            state(loading ? "正在从教务读取成绩…" : "还没有教务成绩", false);
            if (!loading) load(false);
            return;
        }
        currentData = d;
        body.addView(overview(d), lp());
        // 学期筛选（与完整成绩页一致：纯客户端按行 tag 过滤，不重新联网）
        body.addView(semesterFilter(), lp());
        for (JwApi.Row r : d.rows) {
            if (semesterPicked > 0 && !semesterOptions[semesterPicked].equals(r.tag)) continue;
            body.addView(courseCard(r), lp());
        }
        // 本次登录会话还没抓过成绩（快照早于登录时间）：先显快照，再悄悄补一次；
        // 抓完 featureAt 会晚于 loginAt，同一会话内切回这个 tab 不会再联网
        if (!loading && store.featureAt(FeatureActivity.T_GRADES) < store.loginAt()) {
            load(false);
        }
    }

    /**
     * 学期筛选：把行里出现的学期去重、排序（"YYYY-YYYY · N" 字典序即时间序），
     * 选了只在本地过滤，不重新联网。多学期才显示，单学期没必要筛。
     */
    private LinearLayout semesterFilter() {
        java.util.List<String> uniq = new java.util.ArrayList<>();
        if (currentData != null) {
            for (JwApi.Row r : currentData.rows) {
                if (!r.tag.isEmpty() && !uniq.contains(r.tag)) uniq.add(r.tag);
            }
        }
        java.util.Collections.sort(uniq);
        uniq.add(0, "全部学期");
        semesterOptions = uniq.toArray(new String[0]);
        if (semesterPicked >= semesterOptions.length) semesterPicked = 0;
        final boolean multi = uniq.size() > 2; // 去掉「全部学期」后仍多于 1 个
        return rowCard(new JwApi.Row("学期筛选",
                        multi ? "只看某一学期的成绩" : "当前仅一个学期",
                        semesterOptions[semesterPicked], ""),
                multi ? v -> OptionSheet.show(host, "选择学期",
                        semesterOptions, semesterPicked, i -> {
                            semesterPicked = i;
                            build();
                        }) : null);
    }

    /** 仿 FeatureActivity.rowCard：左标题+副标题，右当前值，可点击展开选项。 */
    private LinearLayout rowCard(JwApi.Row r, android.view.View.OnClickListener click) {
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(host.getDrawable(R.drawable.card_bg));
        c.setPadding(dp(15), dp(13), dp(15), dp(13));

        LinearLayout left = new LinearLayout(host);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(host);
        t.setText(r.title);
        t.setTextSize(14.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(host.getColor(R.color.text_primary));
        left.addView(t);
        if (!r.sub.isEmpty()) {
            TextView s = new TextView(host);
            s.setText(r.sub);
            s.setTextSize(11.5f);
            s.setTextColor(host.getColor(R.color.text_tertiary));
            s.setPadding(0, dp(4), 0, 0);
            left.addView(s);
        }
        c.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(host);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        if (!r.value.isEmpty()) {
            TextView v = new TextView(host);
            v.setText(r.value);
            v.setTextSize(16);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setTextColor(host.getColor(R.color.brand));
            right.addView(v);
        }
        if (!r.extra.isEmpty()) {
            TextView e = new TextView(host);
            e.setText(r.extra);
            e.setTextSize(11);
            e.setTextColor(host.getColor(R.color.text_tertiary));
            right.addView(e);
        }
        c.addView(right);
        if (click != null) {
            c.setClickable(true);
            c.setOnClickListener(click);
        }
        return c;
    }

    private LinearLayout bar() {
        LinearLayout h = new LinearLayout(host);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(2), dp(2), dp(2), dp(8));

        TextView title = new TextView(host);
        title.setText("教务成绩");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(host.getColor(R.color.text_primary));
        h.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView more = new TextView(host);
        more.setText("更多 ›");
        more.setTextSize(13);
        more.setTextColor(host.getColor(R.color.brand));
        more.setPadding(dp(12), dp(8), dp(6), dp(8));
        more.setOnClickListener(v -> FeatureActivity.open(host,
                FeatureActivity.T_GRADES, "成绩"));
        h.addView(more);

        // 刷新中在右上角放一个转圈，别只把「刷新」换成文字
        if (loading) {
            ProgressBar spin = spinner();
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(15), dp(15));
            sp.rightMargin = dp(2);
            h.addView(spin, sp);
        }

        TextView refresh = new TextView(host);
        refresh.setText(loading ? "读取中" : "刷新");
        refresh.setEnabled(!loading);
        refresh.setTextSize(13);
        refresh.setTextColor(host.getColor(R.color.brand));
        refresh.setPadding(dp(10), dp(8), dp(2), dp(8));
        refresh.setOnClickListener(v -> load(true));
        h.addView(refresh);
        return h;
    }

    private ProgressBar spinner() {
        ProgressBar p = new ProgressBar(host, null, android.R.attr.progressBarStyleSmall);
        p.setIndeterminate(true);
        p.setIndeterminateTintList(ColorStateList.valueOf(host.getColor(R.color.brand)));
        return p;
    }

    private LinearLayout overview(JwApi.Data d) {
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(host.getDrawable(R.drawable.card_bg));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView h = new TextView(host);
        h.setText(d.headline);
        h.setTextSize(22);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(host.getColor(R.color.brand));
        c.addView(h);

        if (!d.note.isEmpty()) {
            TextView n = new TextView(host);
            n.setText(d.note);
            n.setTextSize(11.5f);
            n.setTextColor(host.getColor(R.color.text_tertiary));
            n.setLineSpacing(dp(2), 1f);
            n.setPadding(0, dp(6), 0, 0);
            c.addView(n);
        }
        String when = d.at > 0
                ? new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(d.at))
                : "刚刚";
        TextView t = new TextView(host);
        t.setText("读取于 " + when + " · 点「刷新」更新，不自动重复加载");
        t.setTextSize(11);
        t.setTextColor(host.getColor(R.color.text_tertiary));
        t.setPadding(0, dp(8), 0, 0);
        c.addView(t);
        return c;
    }

    private LinearLayout courseCard(JwApi.Row r) {
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(host.getDrawable(R.drawable.card_bg));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout left = new LinearLayout(host);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(host);
        name.setText(r.title);
        name.setTextSize(14.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(host.getColor(R.color.text_primary));
        left.addView(name);
        if (!r.sub.isEmpty()) {
            TextView sub = new TextView(host);
            sub.setText(r.sub);
            sub.setTextSize(11.5f);
            sub.setTextColor(host.getColor(R.color.text_tertiary));
            sub.setLineSpacing(dp(2), 1f);
            sub.setPadding(0, dp(4), 0, 0);
            left.addView(sub);
        }
        c.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(host);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        right.setPadding(dp(10), 0, 0, 0);
        TextView score = new TextView(host);
        score.setText(r.value.isEmpty() ? "—" : r.value);
        score.setTextSize(19);
        score.setTypeface(Typeface.DEFAULT_BOLD);
        score.setTextColor(host.getColor(R.color.brand));
        score.setGravity(Gravity.CENTER);
        right.addView(score);
        if (!r.extra.isEmpty()) {
            TextView point = new TextView(host);
            point.setText("绩点 " + r.extra);
            point.setTextSize(11);
            point.setTextColor(host.getColor(R.color.text_tertiary));
            right.addView(point);
        }
        c.addView(right);
        return c;
    }

    private void state(String text, boolean failed) {
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setBackground(host.getDrawable(R.drawable.card_bg));
        c.setPadding(dp(18), dp(26), dp(18), dp(26));

        if (loading) {
            ProgressBar spin = spinner();
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(26), dp(26));
            sp.bottomMargin = dp(12);
            c.addView(spin, sp);
        }

        TextView t = new TextView(host);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(host.getColor(R.color.text_secondary));
        t.setLineSpacing(dp(3), 1f);
        c.addView(t);

        if (failed) {
            TextView retry = new TextView(host);
            retry.setText("重新读取");
            retry.setTextSize(14);
            retry.setTypeface(Typeface.DEFAULT_BOLD);
            retry.setGravity(Gravity.CENTER);
            retry.setTextColor(host.getColor(R.color.brand));
            retry.setBackground(host.getDrawable(R.drawable.btn_outline));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            rp.topMargin = dp(16);
            retry.setLayoutParams(rp);
            retry.setOnClickListener(v -> load(true));
            c.addView(retry);
        }
        body.addView(c, lp());
    }

    /** 只有「首次没快照」或用户点刷新才会走这里。 */
    private void load(final boolean force) {
        if (loading) return;
        loading = true;
        host.runOnUiThread(this::build);
        new Thread(() -> {
            JwApi api = new JwApi(store);
            JwApi.Data d = null;
            String err = "";
            if (store.user().isEmpty() || store.pass().isEmpty()) {
                err = "还没有登录教务，请先到「我的」登录";
            } else if (!api.login(store.user(), store.pass())) {
                err = api.loginMessage().isEmpty() ? "登录教务失败" : api.loginMessage();
            } else {
                d = api.grades(force);
            }
            if (d != null && d.ok) {
                String json = d.toJson();
                if (!json.isEmpty()) store.saveFeature(FeatureActivity.T_GRADES, json);
            }
            final JwApi.Data result = d;
            final String msg = err.isEmpty()
                    ? (d == null || d.message.isEmpty() ? "成绩读取失败" : d.message) : err;
            host.runOnUiThread(() -> {
                loading = false;
                setRefreshing(false);
                if (result == null || !result.ok) {
                    body.removeAllViews();
                    body.addView(bar(), lp());
                    state(msg, true);
                } else {
                    build();
                }
            });
        }, "jw-grades-tab").start();
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(10);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
