package com.tstc.zstangshi.pages;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tstc.zstangshi.R;
import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.data.Timetable;
import com.tstc.zstangshi.jw.JwClient;
import com.tstc.zstangshi.widget.TodayWidget;

/**
 * 我的：登录教务、同步课表、开学日期、深色模式、关于。
 *
 * 登录与同步放在这里而不是首屏，是为了兑现「没登录也能看课表」：
 * 首屏直接进课表，需要更新时才来这里按一下。
 */
public class MinePage extends ScrollView {

    public interface OnTimetableChanged {
        void onChanged();
    }

    private final Activity host;
    private final Store store;
    private final OnTimetableChanged callback;
    private final LinearLayout body;

    private TextView accountTitle;
    private TextView accountSub;
    private TextView accountMeta;
    private TextView accountAvatar;
    private TextView accountTag;
    private TextView syncHint;
    private boolean awaitingLogin;

    public MinePage(Activity host, Store store, OnTimetableChanged cb) {
        super(host);
        this.host = host;
        this.store = store;
        this.callback = cb;
        setBackgroundColor((store.darkNow() ? 0xFF0F1520 : host.getColor(R.color.bg_page)));
        setFillViewport(true);

        body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(24));
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        build();
    }

    private void build() {
        body.removeAllViews();

        // ── 账号卡（旧版：圆形头像 + 姓名主体 + 打码学号 + 状态标签） ──
        LinearLayout card = card();
        LinearLayout hero = new LinearLayout(host);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);

        accountAvatar = new TextView(host);
        accountAvatar.setTextSize(20);
        accountAvatar.setTypeface(Typeface.DEFAULT_BOLD);
        accountAvatar.setGravity(Gravity.CENTER);
        accountAvatar.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable avBg =
                new android.graphics.drawable.GradientDrawable();
        avBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        avBg.setColor(host.getColor(R.color.brand));
        accountAvatar.setBackground(avBg);
        hero.addView(accountAvatar, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout who = new LinearLayout(host);
        who.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams whoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        whoLp.leftMargin = dp(12);

        accountTitle = new TextView(host);
        accountTitle.setTextSize(16);
        accountTitle.setTypeface(Typeface.DEFAULT_BOLD);
        accountTitle.setTextColor(host.getColor(R.color.text_primary));
        who.addView(accountTitle);

        accountSub = new TextView(host);
        accountSub.setTextSize(12);
        accountSub.setTextColor(host.getColor(R.color.text_tertiary));
        accountSub.setPadding(0, dp(3), 0, 0);
        who.addView(accountSub);
        hero.addView(who, whoLp);

        accountTag = new TextView(host);
        accountTag.setTextSize(11);
        accountTag.setTypeface(Typeface.DEFAULT_BOLD);
        accountTag.setTextColor(host.getColor(R.color.brand));
        accountTag.setPadding(dp(10), dp(5), dp(10), dp(5));
        android.graphics.drawable.GradientDrawable tagBg =
                new android.graphics.drawable.GradientDrawable();
        tagBg.setCornerRadius(dp(9));
        tagBg.setColor(store.darkNow() ? 0x332E86E8 : 0x1A0062CC);
        accountTag.setBackground(tagBg);
        hero.addView(accountTag);
        card.addView(hero);

        accountMeta = new TextView(host);
        accountMeta.setTextSize(11.5f);
        accountMeta.setTextColor(host.getColor(R.color.text_tertiary));
        accountMeta.setPadding(0, dp(10), 0, 0);
        card.addView(accountMeta);

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, 0);

        TextView loginBtn = btn(isLoggedIn() ? "重新登录" : "登录教务", false);
        loginBtn.setOnClickListener(v -> showLogin());
        row.addView(loginBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView syncBtn = btn("同步课表", true);
        syncBtn.setOnClickListener(v -> doSync(syncBtn));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        slp.leftMargin = dp(10);
        row.addView(syncBtn, slp);
        card.addView(row);

        syncHint = new TextView(host);
        syncHint.setTextSize(11);
        syncHint.setTextColor(host.getColor(R.color.text_tertiary));
        syncHint.setPadding(0, dp(10), 0, 0);
        card.addView(syncHint);

        // 自动登录：冷启动跳过登录页。没有存密码就开不了，引导先登录一次
        card.addView(switchItem("自动登录", store.autoLogin(), (sw, on) -> {
            if (on && store.pass().isEmpty()) {
                store.setAutoLogin(false);
                paintSwitch(sw, false);
                Toast.makeText(host, "先登录一次并记住密码，才能自动登录", Toast.LENGTH_SHORT).show();
                return;
            }
            store.setAutoLogin(on);
            Toast.makeText(host, on ? "下次打开直接自动登录" : "已关闭自动登录，下次要先登录",
                    Toast.LENGTH_SHORT).show();
        }));

        // ── 课表设置（只保留「学期与教学周」：学年 / 学期 / 第一周周一都在那张表单里，
        //    单独的开学日、节次数量入口没有存在价值，反而把页面堆乱） ──
        LinearLayout set = card();
        set.addView(sectionTitle("课表设置"));

        Timetable t = store.loadTimetable();
        String sem = t == null ? "—" : t.semester.name;
        set.addView(item("学期与教学周", sem.isEmpty() ? "点这里设置" : sem, v -> openTermSheet()));

        // ── 外观（三联段控，跟底栏一套胶囊动效） ──
        LinearLayout look = card();
        look.addView(sectionTitle("外观"));
        com.tstc.zstangshi.ui.SegmentedControl seg =
                new com.tstc.zstangshi.ui.SegmentedControl(host,
                        new String[]{"浅色", "深色", "跟随系统"}, store.themeMode());
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        segLp.topMargin = dp(4);
        look.addView(seg, segLp);
        seg.setOnChange(i -> {
            store.setThemeMode(i);
            // 等胶囊滑到位（260ms）再重建，重建完由 MainActivity 淡入，
            // 这样看到的是「胶囊滑过去 → 界面换色」两个动作，而不是硬切
            com.tstc.zstangshi.MainActivity.beginThemeRestart();
            seg.postDelayed(host::recreate, 260);
        });

        // ── 桌面小组件（独立板块，和「关于」区分开） ──
        LinearLayout widgetCard = card();
        widgetCard.addView(sectionTitle("桌面小组件"));
        widgetCard.addView(item("添加今日课程", "一键放到桌面", v -> pinWidget()));
        widgetCard.addView(item("刷新小组件", "立即重画今日课程", v -> {
            TodayWidget.refreshAll(host);
            Toast.makeText(host, "已刷新", Toast.LENGTH_SHORT).show();
        }));

        // ── 关于 ──
        LinearLayout about = card();
        about.addView(sectionTitle("关于"));
        about.addView(item("版本", versionName(), null));
        about.addView(item("数据来源", "正方教务系统 · 教务处校历", null));

        // 开发者后面直接挂两个平台主页（logo 与文字等大，走 App 内置浏览器）
        TextView devName = new TextView(host);
        devName.setText("陈铭霏");
        devName.setTextSize(12.5f);
        devName.setTextColor(host.getColor(R.color.text_secondary));
        devName.setSingleLine(true);
        LinearLayout.LayoutParams devLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        devLp.rightMargin = dp(4);
        devName.setLayoutParams(devLp);
        about.addView(customItem("开发者", devName,
                linkChip(R.drawable.ic_github, "GitHub主页", GITHUB_HOME, "GitHub"),
                linkChip(R.drawable.ic_gitee, "Gitee主页", GITEE_HOME, "Gitee")));

        about.addView(item("荣誉出品", "百度唐山师范学院贴吧吧务组", null));

        // 项目地址：GitHub 与 Gitee 两个仓库，同样应用内打开
        about.addView(customItem("项目地址",
                linkChip(R.drawable.ic_github, "GitHub仓库", REPO_GITHUB, "GitHub 仓库"),
                linkChip(R.drawable.ic_gitee, "Gitee仓库", REPO_GITEE, "Gitee 仓库")));

        refreshAccount();
    }

    // ---------- 开发者主页 / 项目仓库 ----------

    private static final String GITHUB_HOME = "https://github.com/Chanmingfei/";
    private static final String GITEE_HOME = "https://gitee.com/mingfei123/";
    private static final String REPO_GITHUB = "https://github.com/Chanmingfei/zstangshi";
    private static final String REPO_GITEE = "https://gitee.com/mingfei123/zstangshi";

    /** 左边标题、右边一串自定义控件的行（纯文本行用 item()）。 */
    private LinearLayout customItem(String label, View... right) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView l = new TextView(host);
        l.setText(label);
        l.setTextSize(14);
        l.setTextColor(host.getColor(R.color.text_primary));
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        for (View v : right) row.addView(v);
        return row;
    }

    /** 平台链接：小 logo + 品牌色文字，点开走 App 内置浏览器。 */
    private LinearLayout linkChip(int iconRes, String text, String url, String title) {
        LinearLayout chip = new LinearLayout(host);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setClickable(true);
        chip.setBackground(linkRipple());
        chip.setPadding(dp(6), dp(5), dp(6), dp(5));

        ImageView logo = new ImageView(host);
        logo.setImageResource(iconRes);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 与文字等大：按 12.5sp 的实际像素高度取正方形
        int side = Math.round(12.5f * getResources().getDisplayMetrics().scaledDensity);
        chip.addView(logo, new LinearLayout.LayoutParams(side, side));

        TextView tv = new TextView(host);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(host.getColor(R.color.brand));
        tv.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(4);
        chip.addView(tv, tlp);

        chip.setOnClickListener(v -> com.tstc.zstangshi.WebViewActivity.open(host, url, title));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(2);
        chip.setLayoutParams(lp);
        return chip;
    }

    /** 链接的按压水波纹：内容透明，只留一层圆角涟漪。 */
    private android.graphics.drawable.Drawable linkRipple() {
        android.graphics.drawable.GradientDrawable mask =
                new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(9));
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x220062CC), null, mask);
    }

    // ---------- 学期与教学周（旧版那张「学期与教学周设置」表单） ----------

    /**
     * 学年 / 学期 / 第一周周一 —— 这三个才是「第几周」的真正输入。
     * 保存后立刻切到该学期并落盘，课表、首页、小组件都跟着重算。
     */
    private void openTermSheet() {
        Timetable t = store.loadTimetable();
        String curId = t == null ? store.activeSemester() : t.semester.id;
        if (curId == null) curId = "";
        final String activeId = curId;
        String curStart = t == null ? "" : t.semester.startDate;
        if (curStart == null || curStart.isEmpty()) curStart = store.startDate(curId);

        // 学年候选：8 月起算新学年，前后各留两年
        java.util.Calendar now = java.util.Calendar.getInstance();
        int base = now.get(java.util.Calendar.MONTH) + 1 >= 8
                ? now.get(java.util.Calendar.YEAR) : now.get(java.util.Calendar.YEAR) - 1;
        final String[] years = new String[4];
        for (int i = 0; i < 4; i++) years[i] = (base - 2 + i) + "-" + (base - 1 + i);

        final String[] year = {curId.length() >= 9 ? curId.substring(0, 9) : years[2]};
        final String[] term = {curId.endsWith("-2") ? "第二学期" : "第一学期"};
        final String[] start = {curStart};

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(4), dp(18), dp(12));

        TextView title = new TextView(host);
        title.setText("学期与教学周");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(host.getColor(R.color.text_primary));
        title.setPadding(0, 0, 0, dp(4));
        box.addView(title);

        LinearLayout yearRow = item("学年", year[0] + " 学年", null);
        yearRow.setOnClickListener(v -> com.tstc.zstangshi.ui.OptionSheet.show(host, "选择学年",
                years, indexOf(years, year[0]), i -> {
                    year[0] = years[i];
                    ((TextView) yearRow.getChildAt(1)).setText(year[0] + " 学年");
                }));
        box.addView(yearRow);

        final String[] termOpts = {"第一学期", "第二学期"};
        LinearLayout termRow = item("学期", term[0], null);
        termRow.setOnClickListener(v -> com.tstc.zstangshi.ui.OptionSheet.show(host, "选择学期",
                termOpts, term[0].equals("第二学期") ? 1 : 0, i -> {
                    term[0] = termOpts[i];
                    ((TextView) termRow.getChildAt(1)).setText(term[0]);
                }));
        box.addView(termRow);

        LinearLayout startRow = item("第一周周一", start[0].isEmpty() ? "选一个日期" : start[0], null);
        startRow.setOnClickListener(v -> {
            java.util.Calendar c = java.util.Calendar.getInstance();
            if (com.tstc.zstangshi.data.Dates.valid(start[0])) {
                String[] p = start[0].split("-");
                try {
                    c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
                } catch (Exception ignored) {
                }
            }
            new android.app.DatePickerDialog(host, (picker, y, m, d) -> {
                start[0] = String.format("%04d-%02d-%02d", y, m + 1, d);
                ((TextView) startRow.getChildAt(1)).setText(start[0]);
            }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH),
                    c.get(java.util.Calendar.DAY_OF_MONTH)).show();
        });
        box.addView(startRow);

        TextView hint = new TextView(host);
        hint.setText("保存后按这个学期与第一周算周数，重启仍生效；非周一的日期会自动归到那一周的周一。");
        hint.setTextSize(11.5f);
        hint.setTextColor(host.getColor(R.color.text_tertiary));
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, dp(8), 0, dp(14));
        box.addView(hint);

        // ── 底部：取消 / 保存并生效 ──
        LinearLayout actions = new LinearLayout(host);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        TextView cancel = new TextView(host);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextColor(host.getColor(R.color.text_secondary));
        cancel.setBackground(host.getDrawable(R.drawable.btn_outline));

        TextView save = new TextView(host);
        save.setText("保存并生效");
        save.setTextSize(14);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setGravity(Gravity.CENTER);
        save.setTextColor(0xFFFFFFFF);
        save.setBackground(host.getDrawable(R.drawable.btn_primary));

        LinearLayout.LayoutParams halfLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        actions.addView(cancel, halfLp);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        saveLp.leftMargin = dp(10);
        actions.addView(save, saveLp);
        box.addView(actions);

        com.tstc.zstangshi.ui.Sheet.Handle sheet =
                com.tstc.zstangshi.ui.Sheet.show(host, box, dp(360));
        cancel.setOnClickListener(v -> sheet.dismiss());
        save.setOnClickListener(v -> {
            String id = year[0] + (term[0].equals("第二学期") ? "-2" : "-1");
            if (start[0] == null || start[0].isEmpty()
                    || !com.tstc.zstangshi.data.Dates.valid(start[0])) {
                Toast.makeText(host, "先选第一周周一的日期", Toast.LENGTH_SHORT).show();
                return;
            }
            String monday = com.tstc.zstangshi.data.WeekMath.normalizeToMonday(start[0]);
            if (!id.equals(activeId) && store.loadTimetable(id) == null) {
                Toast.makeText(host, "这个学期还没有课表，先登录教务同步一次", Toast.LENGTH_LONG).show();
                return;
            }
            store.setActiveSemester(id);
            store.setStartDate(id, monday);
            sheet.dismiss();
            if (callback != null) callback.onChanged();
            build();
            Toast.makeText(host, "已按第 1 周 " + monday + " 生效", Toast.LENGTH_SHORT).show();
        });
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return 0;
    }

    /** 系统支持的话直接弹「添加小组件」确认框，省得用户去桌面长按翻列表。 */    private void pinWidget() {
        android.appwidget.AppWidgetManager mgr =
                android.appwidget.AppWidgetManager.getInstance(host);
        if (mgr == null || !mgr.isRequestPinAppWidgetSupported()) {
            Toast.makeText(host, "请在桌面长按 → 小组件里添加「今日课程」", Toast.LENGTH_LONG).show();
            return;
        }
        android.content.ComponentName cn = new android.content.ComponentName(
                host, com.tstc.zstangshi.widget.TodayWidget.class);
        try {
            mgr.requestPinAppWidget(cn, null, null);
        } catch (Exception e) {
            Toast.makeText(host, "请在桌面长按 → 小组件里添加「今日课程」", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshAccount() {
        boolean logged = isLoggedIn();
        String name = store.studentName(store.user());
        String masked = maskNo(store.user());

        // 姓名是主体，学号弱化成第二行；抓不到姓名时回退展示学号（旧版口径）
        accountTitle.setText(logged
                ? (name.isEmpty() ? (masked.isEmpty() ? "已登录" : masked) : name)
                : "未登录");
        accountSub.setText(logged
                ? (name.isEmpty() ? "登录状态由教务会话维持" : masked)
                : "登录教务后可同步课表与成绩");
        accountTag.setText(logged ? "已登录" : "访客");
        accountAvatar.setText(name.isEmpty() ? "唐" : name.substring(0, 1));

        long last = store.lastSync();
        Timetable t = store.loadTimetable();
        // 自动校正开学日：没手动改过、且官方校历覆盖本学期时，用校历开学日覆盖旧的猜测值
        if (t != null && !t.semester.startDateTouched) {
            com.tstc.zstangshi.data.AcademicCalendar.Term term =
                    com.tstc.zstangshi.data.AcademicCalendar.termById(t.semester.id);
            if (term == null) term = com.tstc.zstangshi.data.AcademicCalendar.termFor(
                    com.tstc.zstangshi.data.WeekMath.todayDateLabel());
            if (term != null && !term.classStart.isEmpty()
                    && !term.classStart.equals(t.semester.startDate)) {
                t.semester.startDate = com.tstc.zstangshi.data.WeekMath.normalizeToMonday(term.classStart);
                store.setStartDate(t.semester.id, t.semester.startDate);
            }
        }
        if (t != null && !t.courses.isEmpty()) {
            accountMeta.setText("本地课表 " + t.courses.size() + " 门 · "
                    + (last > 0 ? "上次同步 " + ago(last) : "从未同步"));
        } else {
            accountMeta.setText("本地还没有课表，登录教务同步一次");
        }
        syncHint.setText(logged
                ? "同步会以教务为准覆盖本地改动（手工加的课保留）"
                : "密码只存在本机，用于自动登录教务");
    }

    /** 学号只露前 4 后 4，跟旧版一致。 */
    private static String maskNo(String no) {
        if (no == null || no.length() < 8) return no == null ? "" : no;
        return no.substring(0, 4) + "****" + no.substring(no.length() - 4);
    }

    private boolean isLoggedIn() {
        return !store.user().isEmpty() && !store.pass().isEmpty();
    }

    // ---------- 登录 ----------

    private void showLogin() {
        // 不再在这里弹系统输入框：登录页是独立页面（品牌图 + 记住密码 + 自动登录），
        // 从「我的」进去登录成功后原地回到本页，不再新开一个 MainActivity。
        awaitingLogin = true;
        host.startActivity(new Intent(host, com.tstc.zstangshi.LoginActivity.class)
                .putExtra(com.tstc.zstangshi.LoginActivity.EXTRA_RETURN, true));
    }

    /** 登录页回来时叫一次：走了登录流程才重建，正常切页不白白刷一遍。 */
    public void onReturned() {
        if (awaitingLogin) {
            awaitingLogin = false;
            build();
        }
    }

    // ---------- 同步 ----------

    private void doSync(View btn) {
        if (!isLoggedIn()) {
            Toast.makeText(host, "先登录教务", Toast.LENGTH_SHORT).show();
            showLogin();
            return;
        }
        if (btn != null) btn.setEnabled(false);
        Toast.makeText(host, "正在同步课表…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            JwClient c = new JwClient(store);
            JwClient.Result login = c.login(store.user(), store.pass());
            if (!login.ok) {
                host.runOnUiThread(() -> {
                    if (btn != null) btn.setEnabled(true);
                    Toast.makeText(host, login.message, Toast.LENGTH_LONG).show();
                });
                return;
            }
            if (!login.studentName.isEmpty()) store.setStudentName(login.studentName, store.user());
            JwClient.Result tt = c.fetchTimetable();
            if (!tt.ok) {
                host.runOnUiThread(() -> {
                    if (btn != null) btn.setEnabled(true);
                    Toast.makeText(host, tt.message, Toast.LENGTH_LONG).show();
                });
                return;
            }
            // 开学日期：教务接口不给，沿用本地已有的；第一次同步时用今天往前推到周一兜底
            String old = store.startDate(tt.semesterId);
            if (old == null || old.isEmpty()) {
                tt.timetable.semester.startDate = guessStart(tt.timetable);
            } else {
                tt.timetable.semester.startDate = old;
            }
            store.saveTimetable(tt.timetable);
            TodayWidget.refreshAll(host);

            host.runOnUiThread(() -> {
                if (btn != null) btn.setEnabled(true);
                refreshAccount();
                Toast.makeText(host, "同步完成：" + tt.timetable.courses.size() + " 门课",
                        Toast.LENGTH_SHORT).show();
                if (callback != null) callback.onChanged();
            });
        }).start();
    }

    /**
     * 开学日期：教务接口不给，第一次同步时用「教务处校历」的开学日（第 1 周周一）。
     * 唐山师范学院 2026-2027-1 校历为 2026-08-31 正式上课；若校历未覆盖当前学期，
     * 再退回「今天所在周周一」保底（用户手动改过会记住 startDateTouched）。
     */
    private String guessStart(Timetable t) {
        com.tstc.zstangshi.data.AcademicCalendar.Term term =
                com.tstc.zstangshi.data.AcademicCalendar.termById(t.semester.id);
        if (term == null) {
            term = com.tstc.zstangshi.data.AcademicCalendar.termFor(
                    com.tstc.zstangshi.data.WeekMath.todayDateLabel());
        }
        if (term != null && !term.classStart.isEmpty()) {
            return com.tstc.zstangshi.data.WeekMath.normalizeToMonday(term.classStart);
        }
        // 兜底：仍按今天所在周周一（极端情况下校历未覆盖）
        return com.tstc.zstangshi.data.WeekMath.normalizeToMonday(
                com.tstc.zstangshi.data.WeekMath.todayDateLabel());
    }

    public void onShown() {
        build();
    }

    // ---------- 小工具 ----------

    /** 造一张卡片并**已经挂到 body 上**（返回它只是为了往里加内容，别再 addView 一次）。 */
    /** 造一张卡片并**已经挂到 body 上**（返回它只是为了往里加内容，别再 addView 一次）。 */
    private LinearLayout card() {
        LinearLayout l = new LinearLayout(host);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(host.getDrawable(R.drawable.card_bg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        body.addView(l, lp);
        return l;
    }

    private TextView sectionTitle(String s) {
        TextView v = new TextView(host);
        v.setText(s);
        v.setTextSize(13);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(host.getColor(R.color.text_tertiary));
        v.setPadding(0, 0, 0, dp(8));
        return v;
    }

    private LinearLayout item(String label, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView l = new TextView(host);
        l.setText(label);
        l.setTextSize(14);
        l.setTextColor(host.getColor(R.color.text_primary));
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(host);
        v.setText(value);
        v.setTextSize(13);
        v.setTextColor(host.getColor(R.color.text_tertiary));
        row.addView(v);

        if (click != null) row.setOnClickListener(click);
        return row;
    }

    private interface SwitchChange {
        void onChanged(View btn, boolean on);
    }

    /** 开关的「开/关」胶囊外观。toggle 与外部回滚（比如没密码不让开自动登录）共用一份。 */
    private void paintSwitch(View view, boolean on) {
        TextView sw = (TextView) view;
        sw.setText(on ? "开" : "关");
        sw.setTextSize(13);
        sw.setGravity(Gravity.CENTER);
        sw.setPadding(dp(14), dp(6), dp(14), dp(6));
        sw.setBackground(host.getDrawable(on ? R.drawable.pill_on : R.drawable.pill_off));
        sw.setTextColor(on ? 0xFFFFFFFF : host.getColor(R.color.text_secondary));
    }

    private LinearLayout switchItem(String label, boolean on, SwitchChange cb) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView l = new TextView(host);
        l.setText(label);
        l.setTextSize(14);
        l.setTextColor(host.getColor(R.color.text_primary));
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final boolean[] state = {on};
        TextView sw = new TextView(host);
        paintSwitch(sw, on);
        sw.setOnClickListener(v -> {
            state[0] = !state[0];
            paintSwitch(sw, state[0]);
            cb.onChanged(sw, state[0]);
        });
        row.addView(sw);
        return row;
    }

    private TextView btn(String s, boolean primary) {
        TextView v = new TextView(host);
        v.setText(s);
        v.setTextSize(14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackground(host.getDrawable(primary ? R.drawable.btn_primary : R.drawable.btn_outline));
        v.setTextColor(primary ? 0xFFFFFFFF : host.getColor(R.color.brand));
        return v;
    }

    private String versionName() {
        try {
            return host.getPackageManager().getPackageInfo(host.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "—";
        }
    }

    private static String ago(long ts) {
        long min = (System.currentTimeMillis() - ts) / 60000;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long h = min / 60;
        if (h < 24) return h + " 小时前";
        return (h / 24) + " 天前";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
