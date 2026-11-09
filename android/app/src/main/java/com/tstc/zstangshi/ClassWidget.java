package com.tstc.zstangshi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.List;

/**
 * ClassWidget —— 桌面小组件「今日课程」
 *
 * 为什么不做成自绘 View / 前台服务：
 *   RemoteViews 是唯一不需要常驻进程就能画在桌面上的方案。App 被杀掉、手机重启之后
 *   桌面仍然要能显示今天的课，所以数据必须落在 SharedPreferences（见 WidgetData），
 *   渲染必须是纯函数：读磁盘 → 算状态 → setXxx。
 *
 * 刷新时机（四道保险，全都不依赖精确闹钟权限）：
 *   ① onUpdate / 用户改尺寸 —— 系统回调；
 *   ② App 内课表一有改动，H5 推快照后立刻 refreshAll；
 *   ③ widget_info 的 updatePeriodMillis=30 分钟，系统兜底；
 *   ④ AlarmManager.set（**非精确**，无需 SCHEDULE_EXACT_ALARM）在下一个
 *      「上课 / 下课 / 零点」时刻安排一次重画。
 *
 * 为什么可以容忍闹钟不准：
 *   布局里所有文案都是「时间无关」的 —— 只有「正在上 / 下一节 14:00」这种在某个区间内
 *   恒定成立的表述，没有「还有 12 分钟」这种一刷新就过期的倒计时。晚一两分钟重画，
 *   用户看到的仍然是正确的一句话；而为了秒级精确去申请精确闹钟权限，代价太大。
 */
public class ClassWidget extends AppWidgetProvider {

    private static final String TAG = "zsts-widget";

    /** 自己发给自己的一次「该重画了」广播（闹钟触发） */
    static final String ACTION_TICK = "com.tstc.zstangshi.action.WIDGET_TICK";
    private static final int REQ_TICK = 9001;
    private static final int REQ_OPEN = 9002;

    /** 小组件最多画 4 行，与 widget_today.xml 的固定行数一致 */
    private static final int[] ROW_IDS = {R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4};
    private static final int[] BAR_IDS = {R.id.w_bar1, R.id.w_bar2, R.id.w_bar3, R.id.w_bar4};
    private static final int[] NAME_IDS = {R.id.w_name1, R.id.w_name2, R.id.w_name3, R.id.w_name4};
    private static final int[] META_IDS = {R.id.w_meta1, R.id.w_meta2, R.id.w_meta3, R.id.w_meta4};
    private static final int[] TIME_IDS = {R.id.w_time1, R.id.w_time2, R.id.w_time3, R.id.w_time4};

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        // 不直接用回调里的 ids：refreshAll 会重新查一遍，顺带把「下一个边界」的闹钟排上，
        // 少一条容易忘记同步的代码路径
        refreshAll(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        // 用户拉大 / 缩小：能放几行跟着变
        render(context, manager, id);
    }

    @Override
    public void onEnabled(Context context) {
        // 第一个小组件被放到桌面：立刻画一次（否则会停在 initialLayout 的「载入中」）
        refreshAll(context);
    }

    @Override
    public void onDisabled(Context context) {
        cancelSchedule(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_TICK.equals(intent.getAction())) {
            refreshAll(context);
            return;
        }
        super.onReceive(context, intent);
    }

    /* ═══ 对外入口 ═══════════════════════════════════════════════════════ */

    /**
     * 重画桌面上所有「今日课程」小组件，并把下一次刷新排上。
     * NativeBridge 在收到 H5 推来的新快照后调用，H5 不必知道桌面上有几个实例。
     */
    static void refreshAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, ClassWidget.class));
            for (int id : ids) render(context, manager, id);
        } catch (Exception e) {
            Log.w(TAG, "刷新失败", e);
        }
        schedule(context);
    }

    /* ═══ 渲染 ═══════════════════════════════════════════════════════════ */

    private static void render(Context context, AppWidgetManager manager, int widgetId) {
        try {
            RemoteViews views = build(context, manager, widgetId);
            manager.updateAppWidget(widgetId, views);
        } catch (Exception e) {
            // 桌面上抛异常会弹「小组件已停止」，比空白更吓人：吞掉，下一轮再试
            Log.w(TAG, "渲染失败 id=" + widgetId, e);
        }
    }

    private static RemoteViews build(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_today);
        views.setOnClickPendingIntent(R.id.w_root, openApp(context));

        WidgetData.Snapshot snapshot = WidgetData.load(context);
        int maxRows = maxRows(manager, widgetId);

        if (snapshot == null || !snapshot.hasData) {
            views.setViewVisibility(R.id.w_rows, android.view.View.GONE);
            views.setViewVisibility(R.id.w_none, android.view.View.GONE);
            views.setViewVisibility(R.id.w_empty, android.view.View.VISIBLE);
            // 底部那行在这时候是废话：w_empty 已经写了「打开 App 同步」，
            // 两行近义提示叠在一起只会显得没做完（真机截图确认过）
            views.setViewVisibility(R.id.w_foot, android.view.View.GONE);
            views.setTextViewText(R.id.w_week, "");
            views.setTextViewText(R.id.w_sub, snapshot == null ? "" : subText(snapshot, 0));
            return views;
        }

        views.setViewVisibility(R.id.w_empty, android.view.View.GONE);
        views.setTextViewText(R.id.w_week, snapshot.weekNo > 0 ? "第 " + snapshot.weekNo + " 周" : "");
        views.setTextViewText(R.id.w_sub, subText(snapshot, snapshot.courses.size()));

        int now = minutesOfDay();
        List<WidgetData.Course> courses = snapshot.courses;
        int shown = Math.min(Math.min(courses.size(), maxRows), ROW_IDS.length);

        // 今天一门课都没有：行区域整块换成一个居中提示。
        // 若只是把 4 行都设成 GONE，w_rows 的 weight=1 还在，会把标题和底部那行顶到
        // 上下两头，中间留出一大片白 —— 4×2 下尤其像没画完。
        boolean noneToday = shown == 0;
        views.setViewVisibility(R.id.w_rows, noneToday ? android.view.View.GONE : android.view.View.VISIBLE);
        views.setViewVisibility(R.id.w_none, noneToday ? android.view.View.VISIBLE : android.view.View.GONE);
        views.setViewVisibility(R.id.w_foot, noneToday ? android.view.View.GONE : android.view.View.VISIBLE);

        int accent = context.getColor(R.color.w_accent);
        int subColor = context.getColor(R.color.w_sub);
        int ink = context.getColor(R.color.w_ink);

        for (int i = 0; i < ROW_IDS.length; i += 1) {
            if (i >= shown) {
                views.setViewVisibility(ROW_IDS[i], android.view.View.GONE);
                continue;
            }
            WidgetData.Course course = courses.get(i);
            boolean running = running(course, now);
            boolean done = !running && isDone(course, now);

            views.setViewVisibility(ROW_IDS[i], android.view.View.VISIBLE);
            views.setTextViewText(NAME_IDS[i], course.name);
            views.setTextViewText(TIME_IDS[i], course.start);
            views.setTextViewText(META_IDS[i], running ? "正在上 · " + placeOf(course) : placeOf(course));
            views.setInt(BAR_IDS[i], "setBackgroundColor", barColor(context, course.type));
            views.setTextColor(NAME_IDS[i], done ? subColor : ink);
            views.setTextColor(TIME_IDS[i], running ? accent : subColor);
            views.setTextColor(META_IDS[i], running ? accent : subColor);
        }

        views.setTextViewText(R.id.w_foot, footText(snapshot, now, shown));
        return views;
    }

    /** 「11 月 11 日 · 周二 · 共 4 门」 */
    private static String subText(WidgetData.Snapshot snapshot, int total) {
        StringBuilder out = new StringBuilder();
        if (!snapshot.dateText.isEmpty()) out.append(snapshot.dateText);
        if (!snapshot.weekday.isEmpty()) {
            if (out.length() > 0) out.append(" · ");
            out.append("周").append(snapshot.weekday);
        }
        if (total > 0) out.append(" · 共 ").append(total).append(" 门");
        return out.toString();
    }

    /**
     * 底部一行：只说「在某区间内恒成立」的话。
     * 刻意不写「还有 12 分钟」—— 闹钟不精确，倒计时会变得比没有更糟。
     */
    private static String footText(WidgetData.Snapshot snapshot, int now, int shown) {
        List<WidgetData.Course> courses = snapshot.courses;
        String suffix = courses.size() > shown ? " · 还有 " + (courses.size() - shown) + " 门" : "";
        for (WidgetData.Course course : courses) {
            if (running(course, now)) return "现在上 " + shortName(course.name) + " · " + course.end + " 下课" + suffix;
        }
        for (WidgetData.Course course : courses) {
            if (!isDone(course, now)) return "下一节 " + course.start + " · " + shortName(course.name) + suffix;
        }
        if (courses.isEmpty()) return "今天没有课";
        return "今天的课都上完了" + suffix;
    }

    private static String placeOf(WidgetData.Course course) {
        String place = course.location.isEmpty() ? "地点待定" : course.location;
        return course.sectionsText.isEmpty() ? place : course.sectionsText + " · " + place;
    }

    /** 名字太长时只留前 12 个字，避免底部一行被挤成省略号 */
    private static String shortName(String name) {
        return name.length() <= 12 ? name : name.substring(0, 12) + "…";
    }

    /** 课程性质 → 左侧色条颜色（深色模式由 values-night 覆盖） */
    private static int barColor(Context context, String type) {
        switch (type == null ? "" : type) {
            case "elective": return context.getColor(R.color.course_elective);
            case "public": return context.getColor(R.color.course_public);
            case "practice": return context.getColor(R.color.course_practice);
            default: return context.getColor(R.color.course_required);
        }
    }

    /**
     * 能放几行：靠系统给的最小高度估。
     * 固定开销（上下内边距 + 标题两行 + 底部一行）约 84dp，每行 34dp。
     */
    private static int maxRows(AppWidgetManager manager, int widgetId) {
        int height = 0;
        try {
            Bundle options = manager.getAppWidgetOptions(widgetId);
            if (options != null) height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        } catch (Exception ignored) {
            // 拿不到就用默认值
        }
        if (height <= 0) return 2;
        // 4×2 默认高度（约 146dp）下要放得下 2 行课：头部（标题 + 日期）约 44dp、
        // 底部提示约 16dp、上下内边距 18dp，剩下每行 26dp（与 widget_today.xml 的行高一致）
        int rows = (height - 70) / 26;
        return Math.max(1, Math.min(ROW_IDS.length, rows));
    }

    /* ═══ 时间判断 ═══════════════════════════════════════════════════════ */

    private static int minutesOfDay() {
        Calendar now = Calendar.getInstance();
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    }

    private static int minutesOf(String hhmm) {
        if (hhmm == null || hhmm.length() != 5 || hhmm.charAt(2) != ':') return -1;
        try {
            return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 左闭右闭：08:45 这一分钟算第 1 节还没下课，与页面「进行中」口径一致 */
    private static boolean running(WidgetData.Course course, int now) {
        int start = minutesOf(course.start);
        int end = minutesOf(course.end);
        if (start < 0 || end < 0) return false;
        return now >= start && now <= end;
    }

    private static boolean isDone(WidgetData.Course course, int now) {
        int end = minutesOf(course.end);
        return end >= 0 && now > end;
    }

    /* ═══ 刷新调度 ═══════════════════════════════════════════════════════ */

    /**
     * 在下一个边界（某节课开始 / 结束，或明天零点）安排一次重画。
     * 用 set() 而不是 setExactAndAllowWhileIdle()：后者要 SCHEDULE_EXACT_ALARM 权限，
     * 一个课表小组件不该为此让用户去系统设置里开开关。
     */
    static void schedule(Context context) {
        try {
            long trigger = nextBoundary(context);
            PendingIntent pending = tickIntent(context);
            AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarm == null) return;
            alarm.set(AlarmManager.RTC, trigger, pending);
        } catch (Exception e) {
            Log.w(TAG, "安排刷新失败", e);
        }
    }

    private static void cancelSchedule(Context context) {
        try {
            AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarm != null) alarm.cancel(tickIntent(context));
        } catch (Exception ignored) {
            // 取消失败最坏只是多醒一次
        }
    }

    private static PendingIntent tickIntent(Context context) {
        Intent intent = new Intent(context, ClassWidget.class);
        intent.setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(
                context, REQ_TICK, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        // 点小组件直达课表：桌面摆着今日课程，点开却落在首页要多走一步
        intent.putExtra(MainActivity.EXTRA_PATH, "/timetable");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context, REQ_OPEN, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * 下一个需要重画的时刻（毫秒时间戳）：所有课的起止时间与明天零点里，取最近的一个。
     * 至少 60 秒之后 —— 否则「刚下课」那一刻会安排一个已经过去的闹钟，形成死循环。
     */
    private static long nextBoundary(Context context) {
        Calendar now = Calendar.getInstance();
        long base = now.getTimeInMillis();
        long best;

        Calendar midnight = (Calendar) now.clone();
        midnight.add(Calendar.DAY_OF_MONTH, 1);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 30);
        midnight.set(Calendar.MILLISECOND, 0);
        best = midnight.getTimeInMillis();

        WidgetData.Snapshot snapshot = WidgetData.load(context);
        if (snapshot != null) {
            for (WidgetData.Course course : snapshot.courses) {
                best = Math.min(best, atToday(course.start, base, best));
                best = Math.min(best, atToday(course.end, base, best));
            }
        }
        long floor = base + 60000;
        return best < floor ? floor : best;
    }

    /**
     * 'HH:MM' → 今天的毫秒时间戳。
     * 已经过去（或不足 60 秒）的时刻直接丢弃：否则「刚下课」会立刻又安排一次刷新，
     * 傍晚之后就会退化成每分钟唤醒一次。
     */
    private static long atToday(String hhmm, long base, long fallback) {
        int minutes = minutesOf(hhmm);
        if (minutes < 0) return fallback;
        Calendar at = Calendar.getInstance();
        at.setTimeInMillis(base);
        at.set(Calendar.HOUR_OF_DAY, minutes / 60);
        at.set(Calendar.MINUTE, minutes % 60);
        at.set(Calendar.SECOND, 5);
        at.set(Calendar.MILLISECOND, 0);
        long when = at.getTimeInMillis();
        return when > base + 60000 ? when : fallback;
    }
}
