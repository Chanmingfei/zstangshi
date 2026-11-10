package com.tstc.zstangshi.pages;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tstc.zstangshi.R;
import com.tstc.zstangshi.data.ScheduleSettings;
import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.data.Timetable;
import com.tstc.zstangshi.ui.OptionSheet;
import com.tstc.zstangshi.ui.Sheet;
import com.tstc.zstangshi.widget.TodayWidget;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 课程编辑器（新增 / 编辑 / 删除）。
 *
 * 版式沿用旧版网页里那张表：
 *   · 节次是两个「选择器」，选完在右边顺手显示 08:00-09:40，用户不用自己算；
 *   · 周次是 1..N 的格子直接勾（教务的周次本来就能是任意集合，起止 + 单双周表达不了），
 *     勾完显示「已选 N 周」，另给全选 / 单周 / 双周三个快捷；
 *   · 颜色是 8 个正圆，选中的加一圈描边；
 *   · 所有选择列表都走同一个圆角弹层，不用系统那个黑白 AlertDialog。
 */
public final class CourseEditor {

    public interface Callback {
        void onSaved();
    }

    private static final String[] DAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private CourseEditor() {}

    public static void show(Activity host, Store store, Timetable tt,
                            Timetable.Course course, Timetable.Session session,
                            int day, Callback cb) {
        show(host, store, tt, course, session, day, 0, cb);
    }

    /**
     * @param startSection 点空格进来时带上那一节的节次（0 表示不管，默认 1-2 节）
     */
    public static void show(Activity host, Store store, Timetable tt,
                            Timetable.Course course, Timetable.Session session,
                            int day, int secAt, Callback cb) {
        if (tt == null) {
            Toast.makeText(host, "先同步一次课表，再手工加课", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean creating = course == null;
        final int totalWeeks = tt.semester.totalWeeks <= 0 ? 20 : tt.semester.totalWeeks;
        final Timetable.Course target;
        final Timetable.Session ses;

        if (creating) {
            target = new Timetable.Course();
            target.key = "custom_" + System.currentTimeMillis();
            target.source = Timetable.SOURCE_CUSTOM;
            target.colorIndex = tt.courses.size() % 8;
            ses = new Timetable.Session();
            ses.dayOfWeek = Math.max(1, Math.min(7, day));
            ses.startSection = secAt > 0 ? Math.min(secAt, ScheduleSettings.MAX_SECTIONS) : 1;
            ses.endSection = Math.min(ScheduleSettings.MAX_SECTIONS, ses.startSection + 1);
            for (int i = 1; i <= Math.min(16, totalWeeks); i++) ses.weeks.add(i);
            ses.weeksRaw = "1-" + Math.min(16, totalWeeks) + "周";
        } else {
            target = course;
            ses = session == null
                    ? (course.sessions.isEmpty() ? new Timetable.Session() : course.sessions.get(0))
                    : session;
        }

        final ScheduleSettings sch = store.schedule();
        final int[] colorIndex = {target.colorIndex};
        final int[] startSection = {ses.startSection};
        final int[] endSection = {ses.endSection};
        final int[] weekDay = {ses.dayOfWeek};
        final Set<Integer> picked = new LinkedHashSet<>(ses.weeks);

        float d = host.getResources().getDisplayMetrics().density;
        int dp = Math.round(d);

        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);

        // ── 标题 ──
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp * 22, dp * 6, dp * 14, dp * 6);

        TextView title = new TextView(host);
        title.setText(creating ? "新增课程" : "编辑课程");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(host.getColor(R.color.text_primary));
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(host);
        close.setText("✕");
        close.setTextSize(15);
        close.setTextColor(host.getColor(R.color.text_tertiary));
        close.setPadding(dp * 12, dp * 6, dp * 12, dp * 6);
        head.addView(close);
        card.addView(head);

        ScrollView sc = new ScrollView(host);
        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp * 22, 0, dp * 22, 0);
        sc.addView(body);
        card.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── 课程名 ──
        body.addView(label(host, "课程名"));
        EditText name = input(host, "如：高等数学", target.name);
        body.addView(name, match(dp * 8));

        // ── 教师 / 教室 ──
        LinearLayout two = new LinearLayout(host);
        two.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(host);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout right = new LinearLayout(host);
        right.setOrientation(LinearLayout.VERTICAL);

        EditText teacher = input(host, "教师", ses.teacher.isEmpty() ? target.teacher : ses.teacher);
        EditText room = input(host, "教室", ses.location);
        left.addView(label(host, "教师"));
        left.addView(teacher, match(0));
        right.addView(label(host, "教室"));
        right.addView(room, match(0));
        two.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightLp.leftMargin = dp * 10;
        two.addView(right, rightLp);
        body.addView(two, match(0));

        // ── 校区（教务给的是 xqmc，自己加的课也该能记） ──
        body.addView(label(host, "校区"));
        EditText campus = input(host, "如：东校区（可不填）", ses.campus);
        body.addView(campus, match(dp * 8));

        // ── 星期 ──
        body.addView(label(host, "星期"));
        final TextView dayValue = valueRow(host, DAYS[weekDay[0] - 1]);
        dayValue.setOnClickListener(v -> OptionSheet.show(host, "星期几", DAYS, weekDay[0] - 1, i -> {
            weekDay[0] = i + 1;
            dayValue.setText(DAYS[i]);
        }));
        body.addView(dayValue, match(0));

        // ── 节次：两个选择器 + 右边显示时间 ──
        body.addView(label(host, "节次"));
        LinearLayout secRow = new LinearLayout(host);
        secRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView fromValue = valueRow(host, "第 " + startSection[0] + " 节");
        final TextView toValue = valueRow(host, "第 " + endSection[0] + " 节");
        final TextView timeHint = new TextView(host);
        timeHint.setTextSize(12);
        timeHint.setTextColor(host.getColor(R.color.text_tertiary));
        timeHint.setGravity(Gravity.CENTER);

        String[] secOpts = new String[ScheduleSettings.MAX_SECTIONS];
        for (int i = 0; i < secOpts.length; i++) {
            secOpts[i] = "第 " + (i + 1) + " 节";
        }
        fromValue.setOnClickListener(v -> OptionSheet.show(host, "从第几节开始", secOpts,
                startSection[0] - 1, i -> {
                    startSection[0] = i + 1;
                    if (endSection[0] < startSection[0]) endSection[0] = startSection[0];
                    fromValue.setText("第 " + startSection[0] + " 节");
                    toValue.setText("第 " + endSection[0] + " 节");
                    timeHint.setText(sch.startTime(startSection[0]) + " - "
                            + sch.endTime(endSection[0]));
                }));
        toValue.setOnClickListener(v -> {
            int from = startSection[0];
            String[] ends = new String[ScheduleSettings.MAX_SECTIONS - from + 1];
            for (int i = from; i <= ScheduleSettings.MAX_SECTIONS; i++) {
                ends[i - from] = "第 " + i + " 节";
            }
            OptionSheet.show(host, "上到第几节", ends, endSection[0] - from, i -> {
                endSection[0] = from + i;
                toValue.setText("第 " + endSection[0] + " 节");
                timeHint.setText(sch.startTime(startSection[0]) + " - "
                        + sch.endTime(endSection[0]));
            });
        });
        timeHint.setText(sch.startTime(startSection[0]) + " - " + sch.endTime(endSection[0]));

        secRow.addView(fromValue, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams toLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        toLp.leftMargin = dp * 8;
        toLp.rightMargin = dp * 10;
        secRow.addView(toValue, toLp);
        secRow.addView(timeHint);
        body.addView(secRow, match(0));

        // ── 周次：1..N 直接勾 ──
        body.addView(label(host, "上课周次"));
        final TextView weekHint = new TextView(host);
        weekHint.setTextSize(11.5f);
        weekHint.setTextColor(host.getColor(R.color.text_tertiary));

        LinearLayout weekBox = new LinearLayout(host);
        weekBox.setOrientation(LinearLayout.VERTICAL);
        final List<TextView> cells = new ArrayList<>();
        int perRow = 7;
        LinearLayout row = null;
        for (int w = 1; w <= totalWeeks; w++) {
            if ((w - 1) % perRow == 0) {
                row = new LinearLayout(host);
                row.setOrientation(LinearLayout.HORIZONTAL);
                weekBox.addView(row, match(dp * 6));
            }
            final int week = w;
            final TextView cell = weekCell(host, w, picked.contains(w));
            cell.setOnClickListener(v -> {
                if (picked.contains(week)) picked.remove(week);
                else picked.add(week);
                paintWeekCell(cell, picked.contains(week), host);
                weekHint.setText(weekHint(picked));
            });
            cells.add(cell);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp * 36, 1f);
            lp.rightMargin = dp * 6;
            row.addView(cell, lp);
        }
        body.addView(weekBox, match(0));

        // 快捷：全选 / 单周 / 双周
        LinearLayout quick = new LinearLayout(host);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.addView(quickBtn(host, "全选", () -> {
            picked.clear();
            for (int w = 1; w <= totalWeeks; w++) picked.add(w);
            refreshWeeks(cells, picked, host, weekHint);
        }), new LinearLayout.LayoutParams(0, dp * 32, 1f));
        LinearLayout.LayoutParams q1 = new LinearLayout.LayoutParams(0, dp * 32, 1f);
        q1.leftMargin = dp * 8;
        quick.addView(quickBtn(host, "单周", () -> {
            picked.clear();
            for (int w = 1; w <= totalWeeks; w += 2) picked.add(w);
            refreshWeeks(cells, picked, host, weekHint);
        }), q1);
        LinearLayout.LayoutParams q2 = new LinearLayout.LayoutParams(0, dp * 32, 1f);
        q2.leftMargin = dp * 8;
        quick.addView(quickBtn(host, "双周", () -> {
            picked.clear();
            for (int w = 2; w <= totalWeeks; w += 2) picked.add(w);
            refreshWeeks(cells, picked, host, weekHint);
        }), q2);
        LinearLayout.LayoutParams quickLp = match(0);
        quickLp.rightMargin = dp * 6;
        body.addView(quick, quickLp);

        weekHint.setText(weekHint(picked));
        LinearLayout.LayoutParams whLp = match(0);
        whLp.topMargin = dp * 8;
        body.addView(weekHint, whLp);

        // ── 颜色：8 个正圆 ──
        body.addView(label(host, "颜色"));
        LinearLayout colors = new LinearLayout(host);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        final List<View> dots = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int idx = i;
            View dot = new View(host);
            dot.setBackground(dotDrawable(host, idx, colorIndex[0] == idx));
            dot.setOnClickListener(v -> {
                colorIndex[0] = idx;
                for (int k = 0; k < dots.size(); k++) {
                    dots.get(k).setBackground(dotDrawable(host, k, colorIndex[0] == k));
                }
            });
            dots.add(dot);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp * 30, dp * 30);
            lp.rightMargin = dp * 10;
            colors.addView(dot, lp);
        }
        LinearLayout.LayoutParams colorsLp = match(0);
        colorsLp.bottomMargin = dp * 4;
        body.addView(colors, colorsLp);

        // ── 底部按钮 ──
        LinearLayout actions = new LinearLayout(host);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp * 22, dp * 10, dp * 22, dp * 16);

        final Sheet.Handle[] holder = new Sheet.Handle[1];

        if (!creating) {
            TextView del = new TextView(host);
            del.setText("删除");
            del.setTextSize(14);
            del.setGravity(Gravity.CENTER);
            del.setTextColor(0xFFE0533F);
            del.setBackground(round(host.getColor(R.color.bg_sunken), dp * 12));
            del.setOnClickListener(v -> OptionSheet.confirm(host, "删除这门课？",
                    target.name + "\n只会删掉当前的这一个时段，其他时段的课还在。",
                    "删除这个时段", true, () -> {
                        target.sessions.remove(ses);
                        if (target.sessions.isEmpty()) tt.courses.remove(target);
                        save(host, store, tt, holder[0], cb);
                    }));
            actions.addView(del, new LinearLayout.LayoutParams(0, dp * 46, 1f));
        }

        TextView cancel = new TextView(host);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextColor(host.getColor(R.color.text_secondary));
        cancel.setBackground(round(host.getColor(R.color.bg_sunken), dp * 12));
        cancel.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp * 46, 1f);
        clp.leftMargin = creating ? 0 : dp * 10;
        actions.addView(cancel, clp);

        TextView save = new TextView(host);
        save.setText("保存");
        save.setTextSize(14);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setGravity(Gravity.CENTER);
        save.setTextColor(0xFFFFFFFF);
        save.setBackground(round(host.getColor(R.color.brand), dp * 12));
        save.setOnClickListener(v -> {
            String nm = name.getText().toString().trim();
            if (nm.isEmpty()) {
                Toast.makeText(host, "课程名不能空着", Toast.LENGTH_SHORT).show();
                return;
            }
            if (picked.isEmpty()) {
                Toast.makeText(host, "至少勾一周", Toast.LENGTH_SHORT).show();
                return;
            }
            if (endSection[0] < startSection[0]) endSection[0] = startSection[0];

            target.name = nm;
            target.colorIndex = colorIndex[0];
            target.colorTouched = true;
            String t = teacher.getText().toString().trim();
            ses.teacher = t;
            if (creating) target.teacher = t;
            ses.location = room.getText().toString().trim();
            ses.campus = campus.getText().toString().trim();
            ses.dayOfWeek = weekDay[0];
            ses.startSection = startSection[0];
            ses.endSection = endSection[0];
            ses.weeks = new ArrayList<>(picked);
            java.util.Collections.sort(ses.weeks);
            ses.weeksRaw = com.tstc.zstangshi.data.WeekRange.compress(ses.weeks) + "周";

            if (creating) {
                target.sessions.add(ses);
                tt.courses.add(target);
            }
            save(host, store, tt, holder[0], cb);
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp * 46, 1.4f);
        slp.leftMargin = dp * 10;
        actions.addView(save, slp);

        card.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int sheetH = Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.86f);
        holder[0] = Sheet.show(host, card, sheetH);
        close.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
        });
    }

    // ---------- 保存 ----------

    private static void save(Activity host, Store store, Timetable tt, Sheet.Handle sheet, Callback cb) {
        store.saveTimetable(tt);
        TodayWidget.refreshAll(host);
        if (sheet != null) sheet.dismiss();
        Toast.makeText(host, "已保存", Toast.LENGTH_SHORT).show();
        if (cb != null) cb.onSaved();
    }

    // ---------- 小控件 ----------

    private static void refreshWeeks(List<TextView> cells, Set<Integer> picked,
                                     Activity host, TextView hint) {
        for (int i = 0; i < cells.size(); i++) {
            paintWeekCell(cells.get(i), picked.contains(i + 1), host);
        }
        hint.setText(weekHint(picked));
    }

    private static String weekHint(Set<Integer> picked) {
        if (picked.isEmpty()) return "还没有选周次";
        List<Integer> sorted = new ArrayList<>(picked);
        java.util.Collections.sort(sorted);
        return "已选 " + sorted.size() + " 周 · "
                + com.tstc.zstangshi.data.WeekRange.compress(sorted) + "周";
    }

    private static TextView weekCell(Activity host, int week, boolean on) {
        int dp = Math.round(host.getResources().getDisplayMetrics().density);
        TextView v = new TextView(host);
        v.setText(String.valueOf(week));
        v.setTextSize(12.5f);
        v.setGravity(Gravity.CENTER);
        paintWeekCell(v, on, host);
        return v;
    }

    private static void paintWeekCell(TextView v, boolean on, Activity host) {
        int dp = Math.round(host.getResources().getDisplayMetrics().density);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp * 10);
        g.setColor(host.getColor(on ? R.color.brand : R.color.bg_sunken));
        v.setBackground(g);
        v.setTextColor(on ? 0xFFFFFFFF : host.getColor(R.color.text_secondary));
        v.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private static TextView quickBtn(Activity host, String text, Runnable onClick) {
        int dp = Math.round(host.getResources().getDisplayMetrics().density);
        TextView v = new TextView(host);
        v.setText(text);
        v.setTextSize(12);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(host.getColor(R.color.brand));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp * 10);
        g.setColor(host.getColor(R.color.brand_soft));
        v.setBackground(g);
        v.setOnClickListener(x -> onClick.run());
        return v;
    }

    private static TextView label(Activity host, String s) {
        int dp = Math.round(host.getResources().getDisplayMetrics().density);
        TextView v = new TextView(host);
        v.setText(s);
        v.setTextSize(12);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(host.getColor(R.color.text_secondary));
        v.setPadding(dp * 2, dp * 14, 0, dp * 6);
        return v;
    }

    private static EditText input(Activity host, String hint, String value) {
        int dp = Math.round(host.getResources().getDisplayMetrics().density);
        EditText e = new EditText(host);
        e.setHint(hint);
        e.setHintTextColor(host.getColor(R.color.text_tertiary));
        e.setTextColor(host.getColor(R.color.text_primary));
        e.setTextSize(14.5f);
        e.setText(value == null ? "" : value);
        e.setBackground(host.getDrawable(R.drawable.input_bg));
        e.setPadding(dp * 12, dp * 11, dp * 12, dp * 11);
        e.setSingleLine(true);
        return e;
    }

    private static TextView valueRow(Activity host, String value) {
        int dp = Math.round(host.getResources().getDisplayMetrics().density);
        TextView v = new TextView(host);
        v.setText(value);
        v.setTextSize(14.5f);
        v.setTextColor(host.getColor(R.color.text_primary));
        v.setBackground(host.getDrawable(R.drawable.input_bg));
        v.setPadding(dp * 12, dp * 11, dp * 12, dp * 11);
        v.setSingleLine(true);
        return v;
    }

    private static LinearLayout.LayoutParams match(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    /** 正圆：宽高都定死 30dp，别用 weight 拉成椭圆。 */
    private static GradientDrawable dotDrawable(Activity host, int index, boolean selected) {
        float d = host.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setSize(Math.round(30 * d), Math.round(30 * d));
        g.setColor(host.getColor(courseColor(index)));
        if (selected) {
            g.setStroke(Math.round(2.5f * d), host.getColor(R.color.text_primary));
        }
        return g;
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static int courseColor(int idx) {
        int[] ids = {R.color.course_0, R.color.course_1, R.color.course_2, R.color.course_3,
                R.color.course_4, R.color.course_5, R.color.course_6, R.color.course_7};
        return ids[Math.abs(idx) % 8];
    }
}
