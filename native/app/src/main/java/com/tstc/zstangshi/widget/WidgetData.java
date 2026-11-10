package com.tstc.zstangshi.widget;

import android.content.Context;

import com.tstc.zstangshi.data.Dates;
import com.tstc.zstangshi.data.DayPlan;
import com.tstc.zstangshi.data.ScheduleSettings;
import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.data.Timetable;
import com.tstc.zstangshi.data.WeekMath;
import com.tstc.zstangshi.data.WidgetDay;

import java.util.Calendar;
import java.util.List;

/**
 * 小组件要显示的内容：把「课表 + 今天几点 + 作息」算成一张卡。
 *
 * 这里正是旧版那个 bug 的根治点。之前小组件是按「列表里第 1/2/3 门课」渲染的，
 * 行数写死，第 4 门以后永远轮不到；现在改成每次刷新都调
 * {@link WidgetDay#build}，它内部会先 {@code remaining(today, nowMin)} 把
 * 「已经下课的课」全部滤掉，再取前 cap 条 —— 所以第四门课会在第三门下课时
 * 自动补上来。刷新时刻由 {@link TodayWidget} 在「上课 / 下课」边界上触发。
 */
final class WidgetData {

    static final class Snapshot {
        WidgetDay.Card card = new WidgetDay.Card();
        int state = WidgetDay.NO_TIMETABLE;
        /** 下一次需要重画的时刻（今天第几分钟）；-1 = 今天不用再刷 */
        int nextRefreshMin = -1;
    }

    private WidgetData() {}

    static Snapshot load(Context c, int cap) {
        Store store = new Store(c);
        Snapshot snap = new Snapshot();

        Timetable t = store.loadTimetable();
        String today = WeekMath.todayDateLabel();
        int nowMin = nowMinute();

        if (t == null || t.courses.isEmpty()) {
            snap.state = WidgetDay.NO_TIMETABLE;
            snap.card = WidgetDay.build(snap.state, today, 0, "", null, null, nowMin, cap);
            return snap;
        }

        String start = t.semester.startDate;
        int week = Dates.valid(start) ? Dates.weekOf(start, today) : -1;
        int total = t.semester.totalWeeks <= 0 ? 20 : t.semester.totalWeeks;

        if (!Dates.valid(start)) {
            snap.state = WidgetDay.NO_START;
            snap.card = WidgetDay.build(snap.state, today, 0, start, null, null, nowMin, cap);
            return snap;
        }
        if (week < 1) {
            snap.state = WidgetDay.BEFORE;
            snap.card = WidgetDay.build(snap.state, today, 0, start, null, null, nowMin, cap);
            return snap;
        }
        if (week > total) {
            snap.state = WidgetDay.AFTER;
            snap.card = WidgetDay.build(snap.state, today, 0, start, null, null, nowMin, cap);
            return snap;
        }

        ScheduleSettings sch = store.schedule();
        sch.ensureCovers(ScheduleSettings.maxSectionUsed(t));
        int n = ScheduleSettings.MAX_SECTIONS;
        int[] secStart = new int[n + 1];
        int[] secEnd = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            secStart[i] = sch.startMinutes(i);
            secEnd[i] = sch.endMinutes(i);
        }

        List<DayPlan.Item> todayItems = DayPlan.of(t, start, today, secStart, secEnd);
        String tomorrowYmd = Dates.addDays(today, 1);
        List<DayPlan.Item> tomorrow = DayPlan.of(t, start, tomorrowYmd, secStart, secEnd);

        snap.state = WidgetDay.OK;
        snap.card = WidgetDay.build(WidgetDay.OK, today, week, start, todayItems, tomorrow, nowMin, cap);
        // 下次刷新：最近一次上课/下课/课前 60 分钟；今天没有了就等第二天零点
        snap.nextRefreshMin = WidgetDay.nextRefreshMin(todayItems, nowMin);
        return snap;
    }

    static int nowMinute() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** 颜色下标 → 色条资源 */
    static int barColorRes(int idx) {
        int[] ids = {
                com.tstc.zstangshi.R.color.course_0, com.tstc.zstangshi.R.color.course_1,
                com.tstc.zstangshi.R.color.course_2, com.tstc.zstangshi.R.color.course_3,
                com.tstc.zstangshi.R.color.course_4, com.tstc.zstangshi.R.color.course_5,
                com.tstc.zstangshi.R.color.course_6, com.tstc.zstangshi.R.color.course_7,
        };
        return ids[Math.abs(idx) % 8];
    }
}
