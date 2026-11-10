package com.tstc.zstangshi.pages;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tstc.zstangshi.R;
import com.tstc.zstangshi.data.Dates;
import com.tstc.zstangshi.data.ScheduleSettings;
import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.data.Timetable;
import com.tstc.zstangshi.data.WeekMath;
import com.tstc.zstangshi.data.WeekOverlay;
import com.tstc.zstangshi.ui.TimetableGridView;

/**
 * 课表页。
 *
 * 主要解决旧版两个毛病：
 *   1. 免登录直接能看 —— 打开就读本地，读不到才提示去同步，不再把人踢回登录页；
 *   2. 课表铺满屏 —— 节次栏与星期行都画在网格里，左右不再留白。
 *
 * 周次切换点一次就重算 overlay 并重绘，不做页面重建。
 */
public class TimetablePage extends FrameLayout {

    private final Activity host;
    private final Store store;

    private final TextView weekLabel;
    private final TextView prevBtn;
    private final TextView nextBtn;
    private final TextView title;
    private final TimetableGridView grid;
    private final LinearLayout emptyBox;
    private final TextView todayFab;
    private WeekSwipe weekSwipe;
    private boolean fabShown = false;
    private boolean weekInitialized = false;

    private Timetable timetable;
    private ScheduleSettings schedule;
    private String startDate = "";
    private int week = 1;
    private int totalWeeks = 20;

    public TimetablePage(Activity host, Store store) {
        super(host);
        this.host = host;
        this.store = store;
        setBackgroundColor((store.darkNow() ? 0xFF0F1520 : host.getColor(R.color.bg_page)));

        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── 顶部：标题 + 周次切换 ──
        LinearLayout header = new LinearLayout(host);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(14);
        header.setPadding(pad, dp(8), pad, dp(8));

        title = new TextView(host);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(host.getColor(R.color.text_primary));
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        prevBtn = stepBtn("‹", -1);
        header.addView(prevBtn);
        weekLabel = new TextView(host);
        weekLabel.setTextSize(13);
        weekLabel.setGravity(Gravity.CENTER);
        weekLabel.setTextColor(host.getColor(R.color.text_primary));
        // 旧版：点周次文字直接弹「选择周次」格子，不用一格格按 ‹ ›
        android.graphics.drawable.GradientDrawable weekBg =
                new android.graphics.drawable.GradientDrawable();
        weekBg.setColor(host.getColor(R.color.brand_soft));
        weekBg.setCornerRadius(dp(9));
        weekLabel.setBackground(weekBg);
        // 高度固定成 dp(34)，与左右箭头、加号完全同高同行高，不再靠 padding 撑
        weekLabel.setPadding(0, 0, 0, 0);
        weekLabel.setOnClickListener(v -> openWeekPicker());
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(dp(82), dp(34));
        header.addView(weekLabel, wlp);
        nextBtn = stepBtn("›", 1);
        header.addView(nextBtn);

        TextView addBtn = new TextView(host);
        addBtn.setText("＋");
        addBtn.setTextSize(18);
        addBtn.setGravity(Gravity.CENTER);
        // ‹ › ＋ 三个符号字形在字体里高低不一，关掉字体 padding 让可见字形真正居中
        addBtn.setIncludeFontPadding(false);
        addBtn.setTextColor(host.getColor(R.color.brand));
        addBtn.setContentDescription("新增课程");
        addBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(34)));
        addBtn.setOnClickListener(v -> CourseEditor.show(host, store, timetable, null, null,
                Math.max(1, Math.min(7, com.tstc.zstangshi.data.Dates.dayOfWeek(
                        com.tstc.zstangshi.data.WeekMath.todayDateLabel()))), this::reload));
        header.addView(addBtn);

        col.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 网格 ──
        grid = new TimetableGridView(host);
        grid.setOnCourseClickListener(this::openDetail);
        // 点空格 = 就在这一格新建课程
        grid.setOnEmptyClickListener((d, sec) -> CourseEditor.show(host, store, timetable,
                null, null, d, sec, this::reload));
        // 左右滑 = 上一周 / 下一周：手指跟手位移 + 松手翻页过渡（不再是瞬切 + 硬弹一下）
        // 写成静态嵌套类：这套 d8 碰到匿名 / 非静态内部类会在 dex 化阶段内部报错
        weekSwipe = new WeekSwipe(this);
        grid.setOnTouchListener(weekSwipe);
        col.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── 空态 ──
        emptyBox = new LinearLayout(host);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setVisibility(GONE);
        TextView t1 = new TextView(host);
        t1.setText("还没有课表");
        t1.setTextSize(16);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(host.getColor(R.color.text_primary));
        t1.setGravity(Gravity.CENTER);
        TextView t2 = new TextView(host);
        t2.setText("到「我的」里登录教务同步一次，之后没网也能看");
        t2.setTextSize(13);
        t2.setTextColor(host.getColor(R.color.text_tertiary));
        t2.setGravity(Gravity.CENTER);
        emptyBox.addView(t1);
        emptyBox.addView(t2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.gravity = Gravity.CENTER;
        addView(emptyBox, elp);

        // ── 右下角「回到本周」：只有翻到别的周才出现 ──
        // 页面根节点已被 MainActivity 加了 dockBand 的底部 padding，
        // 所以 BOTTOM 对齐天然落在胶囊栏上方，再留 12dp 呼吸空间。
        todayFab = new TextView(host);
        todayFab.setText("回到本周");
        todayFab.setTextSize(12.5f);
        todayFab.setTypeface(Typeface.DEFAULT_BOLD);
        todayFab.setGravity(Gravity.CENTER);
        todayFab.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable fabBg =
                new android.graphics.drawable.GradientDrawable();
        fabBg.setColor(host.getColor(R.color.brand));
        fabBg.setCornerRadius(dp(22));
        todayFab.setBackground(fabBg);
        todayFab.setPadding(dp(16), 0, dp(16), 0);
        todayFab.setElevation(dp(5));
        todayFab.setContentDescription("回到本周");
        todayFab.setVisibility(GONE);
        todayFab.setAlpha(0f);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.rightMargin = dp(18);
        flp.bottomMargin = dp(12);
        addView(todayFab, flp);
        todayFab.setOnClickListener(v -> backToCurrentWeek());

        reload();
    }

    private TextView stepBtn(String s, int delta) {
        TextView v = new TextView(host);
        v.setText(s);
        v.setTextSize(18);
        v.setGravity(Gravity.CENTER);
        // 单引号字形（‹ ›）在字体里天然偏上，关掉字体 padding 才能光学居中
        v.setIncludeFontPadding(false);
        v.setTextColor(host.getColor(R.color.text_primary));
        v.setContentDescription(delta < 0 ? "上一周" : "下一周");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(34));
        v.setLayoutParams(lp);
        v.setOnClickListener(x -> {
            int next = week + delta;
            if (next < 1 || next > totalWeeks) return;
            if (weekSwipe != null) weekSwipe.flipTo(next);
            else setWeek(next);
        });
        return v;
    }

    // ---------- 数据 ----------

    public void reload() {
        timetable = store.loadTimetable();
        schedule = store.schedule();
        if (timetable == null || timetable.courses.isEmpty()) {
            emptyBox.setVisibility(VISIBLE);
            grid.setVisibility(GONE);
            title.setText("课表");
            // 没有课表时周次切换毫无意义，藏起来别占位
            weekLabel.setText("");
            weekLabel.setVisibility(GONE);
            prevBtn.setVisibility(GONE);
            nextBtn.setVisibility(GONE);
            hideFabNow();
            return;
        }
        weekLabel.setVisibility(VISIBLE);
        prevBtn.setVisibility(VISIBLE);
        nextBtn.setVisibility(VISIBLE);
        emptyBox.setVisibility(GONE);
        grid.setVisibility(VISIBLE);
        grid.setSchedule(schedule);

        startDate = timetable.semester.startDate;
        totalWeeks = timetable.semester.totalWeeks <= 0 ? 20 : timetable.semester.totalWeeks;
        int maxSection = ScheduleSettings.maxSectionUsed(timetable);
        schedule.ensureCovers(maxSection);
        grid.setSchedule(schedule);
        // 只在首次载入时定位到本周；之后切 tab / 编辑课程都保留用户正在看的那一周，
        // 否则「回到本周」按钮永远没机会出现（这也是底栏再点一次能回本周的配套行为）。
        if (!weekInitialized) {
            week = WeekMath.currentWeek(startDate, totalWeeks);
            weekInitialized = true;
        }
        week = Math.max(1, Math.min(totalWeeks, week));
        setWeek(week);

        String name = timetable.semester.name;
        title.setText(name == null || name.isEmpty() ? "课表" : shortName(name));
    }

    private static String shortName(String name) {
        // 「2026-2027 学年第一学期」→「26-27 第一学期」：标题只剩一行的地方，年份不必写全
        String s = name.replace("学年", "").replace(" ", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4})-(\\d{4})").matcher(s);
        if (m.find()) {
            s = m.replaceFirst(m.group(1).substring(2) + "-" + m.group(2).substring(2));
        }
        return s;
    }

    /** 点顶部周次 → 底部弹层铺开全部教学周，点一格直接跳过去。 */
    private void openWeekPicker() {
        final com.tstc.zstangshi.ui.Sheet.Handle[] holder =
                new com.tstc.zstangshi.ui.Sheet.Handle[1];

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(4), dp(18), dp(14));

        TextView t = new TextView(host);
        t.setText("选择周次");
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(host.getColor(R.color.text_primary));
        t.setPadding(0, 0, 0, dp(4));
        box.addView(t);

        TextView hint = new TextView(host);
        hint.setText("当前第 " + week + " 周 · 共 " + totalWeeks + " 周");
        hint.setTextSize(11.5f);
        hint.setTextColor(host.getColor(R.color.text_tertiary));
        hint.setPadding(0, 0, 0, dp(10));
        box.addView(hint);

        final int cols = 5;
        int rows = (totalWeeks + cols - 1) / cols;
        for (int r = 0; r < rows; r++) {
            LinearLayout line = new LinearLayout(host);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, dp(3), 0, dp(3));
            for (int c = 0; c < cols; c++) {
                final int w = r * cols + c + 1;
                TextView cell = new TextView(host);
                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(13);
                if (w <= totalWeeks) {
                    cell.setText(String.valueOf(w));
                    boolean picked = w == week;
                    android.graphics.drawable.GradientDrawable bg =
                            new android.graphics.drawable.GradientDrawable();
                    bg.setCornerRadius(dp(10));
                    if (picked) {
                        bg.setColor(host.getColor(R.color.brand));
                        cell.setTextColor(0xFFFFFFFF);
                        cell.setTypeface(Typeface.DEFAULT_BOLD);
                    } else {
                        bg.setColor(store.darkNow() ? 0x18FFFFFF : 0x140062CC);
                        cell.setTextColor(host.getColor(R.color.text_primary));
                    }
                    cell.setBackground(bg);
                    cell.setOnClickListener(v -> {
                        if (holder[0] != null) holder[0].dismiss();
                        if (weekSwipe != null) weekSwipe.flipTo(w);
                        else setWeek(w);
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
        holder[0] = com.tstc.zstangshi.ui.Sheet.show(host, box,
                dp(96) + rows * dp(48));
    }

    private void setWeek(int w) {
        week = w;
        weekLabel.setText("第 " + w + " 周");
        WeekOverlay overlay = timetable == null
                ? WeekOverlay.empty(w)
                : WeekOverlay.build(timetable, startDate, w);
        grid.setWeekDates(WeekMath.weekDates(startDate, w));
        grid.setData(timetable, overlay, w);
        updateTodayFab();
    }

    /** 教务「本周」的规范值（学期外由 WeekMath 夹到 1 / 总周数）。 */
    private int currentWeek() {
        int w = WeekMath.currentWeek(startDate, totalWeeks);
        return Math.max(1, Math.min(totalWeeks, w));
    }

    /** 只有翻到别的周才浮出「回到本周」，回本周自动淡出收起。 */
    private void updateTodayFab() {
        boolean show = timetable != null && !timetable.courses.isEmpty()
                && week != currentWeek();
        if (show == fabShown) return;
        fabShown = show;
        todayFab.animate().cancel();
        if (show) {
            todayFab.setVisibility(VISIBLE);
            todayFab.setScaleX(0.82f);
            todayFab.setScaleY(0.82f);
            todayFab.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(230)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        } else {
            todayFab.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                    .setDuration(160)
                    .withEndAction(() -> {
                        if (!fabShown) todayFab.setVisibility(GONE);
                    }).start();
        }
    }

    private void hideFabNow() {
        fabShown = false;
        todayFab.animate().cancel();
        todayFab.setVisibility(GONE);
        todayFab.setAlpha(0f);
    }

    private void backToCurrentWeek() {
        if (timetable == null) return;
        int w = currentWeek();
        if (weekSwipe != null) weekSwipe.flipTo(w);
        else setWeek(w);
    }

    public void onShown() {
        reload();
    }

    /** 再点一次「课表」= 回到本周。 */
    public void jumpToToday() {
        backToCurrentWeek();
    }

    // ---------- 课程详情 ----------

    private void openDetail(Timetable.Course course, Timetable.Session session, int day, int startSection) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), 0, dp(22), dp(18));

        // ── 头部：课程色点 + 课名，下面一行「教师 · 周四 · 第 5-6 节」──
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        android.view.View dot = new android.view.View(host);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(host.getColor(courseColorOf(course.colorIndex)));
        dot.setBackground(g);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.rightMargin = dp(10);
        head.addView(dot, dlp);

        TextView name = new TextView(host);
        name.setText(course.name);
        name.setTextSize(18);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(host.getColor(R.color.text_primary));
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(head);

        String teacher = session.teacherFor(week, course.teacher);
        StringBuilder sub = new StringBuilder();
        sub.append("周").append(new String[]{"一", "二", "三", "四", "五", "六", "日"}[day - 1])
                .append("  ·  第").append(session.startSection);
        if (session.endSection != session.startSection) sub.append("-").append(session.endSection);
        sub.append(" 节");
        if (teacher != null && !teacher.isEmpty()) sub.append("  ·  ").append(teacher);

        TextView subView = new TextView(host);
        subView.setText(sub.toString());
        subView.setTextSize(12.5f);
        subView.setTextColor(host.getColor(R.color.text_tertiary));
        subView.setPadding(0, dp(8), 0, dp(16));
        box.addView(subView);

        // ── 字段：一行一个，左标签右值（旧版的「分组」在这里按信息类型分组）──
        LinearLayout info = new LinearLayout(host);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackground(host.getDrawable(R.drawable.card_bg));
        info.setPadding(dp(14), dp(6), dp(14), dp(6));

        info.addView(field("时间", schedule.startTime(session.startSection)
                + " - " + schedule.endTime(session.endSection)));
        if (!session.campus.isEmpty()) info.addView(field("校区", session.campus));
        if (!session.location.isEmpty()) info.addView(field("地点", session.location));
        if (!session.weeksRaw.isEmpty()) info.addView(field("周次", session.weeksRaw));
        if (course.credit > 0) info.addView(field("学分", trimZero(course.credit)));
        if (!course.note.isEmpty()) info.addView(field("备注", course.note));

        ScrollView sc = new ScrollView(host);
        sc.addView(info);
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── 固定底部按钮 ──
        TextView edit = new TextView(host);
        edit.setText("编辑这门课");
        edit.setTextSize(14);
        edit.setTypeface(Typeface.DEFAULT_BOLD);
        edit.setGravity(Gravity.CENTER);
        edit.setTextColor(host.getColor(R.color.brand));
        edit.setBackground(host.getDrawable(R.drawable.btn_outline));
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        editLp.topMargin = dp(14);
        box.addView(edit, editLp);

        // 弹层高度按内容算：一行一个字段，加上头部和底部按钮，别留一大片空白
        int rows = 1;
        if (!session.campus.isEmpty()) rows++;
        if (!session.location.isEmpty()) rows++;
        if (!session.weeksRaw.isEmpty()) rows++;
        if (course.credit > 0) rows++;
        if (!course.note.isEmpty()) rows++;
        int sheetH = dp(150) + rows * dp(44);
        int cap = Math.round(getResources().getDisplayMetrics().heightPixels * 0.8f);
        com.tstc.zstangshi.ui.Sheet.Handle sheet =
                com.tstc.zstangshi.ui.Sheet.show(host, box, Math.min(sheetH, cap));
        edit.setOnClickListener(v -> {
            sheet.dismiss();
            CourseEditor.show(host, store, timetable, course, session, day, this::reload);
        });
    }

    private LinearLayout field(String label, String value) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));

        TextView l = new TextView(host);
        l.setText(label);
        l.setTextSize(12.5f);
        l.setTextColor(host.getColor(R.color.text_tertiary));
        row.addView(l, new LinearLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView v = new TextView(host);
        v.setText(value);
        v.setTextSize(13.5f);
        v.setTextColor(host.getColor(R.color.text_primary));
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static String trimZero(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static int courseColorOf(int idx) {
        int[] ids = {R.color.course_0, R.color.course_1, R.color.course_2, R.color.course_3,
                R.color.course_4, R.color.course_5, R.color.course_6, R.color.course_7};
        return ids[Math.abs(idx) % 8];
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * 跟手的周次翻页。
     *
     * 旧版是「fling → 立刻 setWeek 重绘整张网格 → 从 60dp 处硬弹回 0」：重绘和位移撞在
     * 同一帧，看起来就是一顿。这里改成：
     *   MOVE 时按手指实时平移 + 轻微淡出（到边界加阻尼）；
     *   松手先滑出屏幕，在 alpha=0 的离屏阶段才做唯一一次 setData 换周，再从另一侧滑入。
     * 换周永远发生在看不见的时候，掉帧不会被眼睛捕捉到。
     *
     * 静态嵌套类 + page 字段，避开 d8 对 this$0 的内部错误。
     */
    private static final class WeekSwipe implements View.OnTouchListener {

        private static final android.view.animation.PathInterpolator EASE_OUT =
                new android.view.animation.PathInterpolator(0.22f, 0.8f, 0.30f, 1f);
        private static final android.view.animation.PathInterpolator EASE_IN =
                new android.view.animation.PathInterpolator(0.4f, 0f, 0.8f, 0.4f);

        private final TimetablePage page;
        private final android.view.GestureDetector detector;
        private final int slop;
        private float downX, downY, lastDx;
        private boolean dragging;
        private boolean animating;

        WeekSwipe(TimetablePage page) {
            this.page = page;
            slop = android.view.ViewConfiguration.get(page.getContext()).getScaledTouchSlop();
            detector = new android.view.GestureDetector(page.getContext(), new Fling(page, this));
        }

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            if (page.timetable == null || page.timetable.courses.isEmpty()) return false;
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
                        // 只抢明显的横向手势，纵向滚动照旧交给网格
                        if (Math.abs(dx) < slop || Math.abs(dx) < Math.abs(dy) * 1.3f) {
                            return false;
                        }
                        dragging = true;
                        page.grid.setPressed(false);
                        page.grid.cancelLongPress();
                    }
                    lastDx = dx;
                    drag(dx);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (dragging) {
                        dragging = false;
                        page.grid.setPressed(false);
                        finishDrag(lastDx);
                        return true;
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
            float w = page.grid.getWidth();
            if (w <= 0) return;
            boolean edge = (dx < 0 && page.week >= page.totalWeeks)
                    || (dx > 0 && page.week <= 1);
            float d = dx * (edge ? 0.28f : 0.82f);
            page.grid.setTranslationX(d);
            float p = Math.min(1f, Math.abs(d) / (w * 0.6f));
            page.grid.setAlpha(1f - 0.28f * p);
        }

        private void finishDrag(float dx) {
            float w = page.grid.getWidth();
            float threshold = Math.max(page.dp(60), w * 0.20f);
            int dir = dx < 0 ? 1 : -1;
            int target = page.week + dir;
            if (Math.abs(dx) < threshold || target < 1 || target > page.totalWeeks) {
                springBack();
            } else {
                flip(dir, target);
            }
        }

        private void springBack() {
            animating = true;
            View g = page.grid;
            g.animate().cancel();
            g.animate().translationX(0f).alpha(1f)
                    .setDuration(260).setInterpolator(EASE_OUT)
                    .withEndAction(() -> animating = false).start();
        }

        /** 按钮 / 周次格子 / 「回到本周」共用这一套过渡。 */
        void flipTo(int target) {
            if (target == page.week || animating) return;
            flip(target > page.week ? 1 : -1, target);
        }

        private void flip(int dir, int target) {
            if (target < 1 || target > page.totalWeeks) {
                springBack();
                return;
            }
            animating = true;
            final View g = page.grid;
            g.animate().cancel();
            float w = g.getWidth() > 0 ? g.getWidth() : page.dp(320);
            final float out = -dir * w * 0.32f;
            g.animate().translationX(out).alpha(0f)
                    .setDuration(140).setInterpolator(EASE_IN)
                    .withEndAction(() -> {
                        page.setWeek(target);      // 离屏时换数据，只重绘一次
                        g.setTranslationX(-out);
                        g.setAlpha(0f);
                        g.animate().translationX(0f).alpha(1f)
                                .setDuration(300).setInterpolator(EASE_OUT)
                                .withEndAction(() -> animating = false).start();
                    }).start();
        }
    }

    /** 快速甩动（MOVE 事件来不及形成拖拽）时的兜底翻页。 */
    private static final class Fling
            extends android.view.GestureDetector.SimpleOnGestureListener {

        private final TimetablePage page;
        private final WeekSwipe ctrl;

        Fling(TimetablePage page, WeekSwipe ctrl) {
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
            if (Math.abs(dx) < page.dp(52) || Math.abs(dx) < Math.abs(dy) * 1.2f) {
                return false;
            }
            int dir = dx < 0 ? 1 : -1;
            int target = page.week + dir;
            if (target < 1 || target > page.totalWeeks) return true;
            ctrl.flip(dir, target);
            return true;
        }
    }
}
