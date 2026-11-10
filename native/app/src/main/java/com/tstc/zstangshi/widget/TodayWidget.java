package com.tstc.zstangshi.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import com.tstc.zstangshi.MainActivity;
import com.tstc.zstangshi.R;

/**
 * 「今日课程」小组件。
 *
 * 刷新策略（与旧版最大的不同就在这里）：
 *   - 系统那 30 分钟的 updatePeriodMillis 只当兜底；
 *   - 真正准的是自己在「下一次上课 / 下课 / 课前 60 分钟」这几个时刻各排一个
 *     AlarmManager 闹钟，到点重算一次。所以第三门下课时，第四门会立刻顶上来，
 *     不会像旧版那样因为只有三格而永远停在前三门。
 *
 * 内容由 {@link WidgetData} 算，行由 {@link RowsService} 渲染（ListView 结构，
 * 行数不受 RemoteViews 静态布局的限制）。
 */
public class TodayWidget extends AppWidgetProvider {

    private static final String ACTION_TICK = "com.tstc.zstangshi.widget.TICK";
    /** 列表最多渲染几行（再多也没意义，用户看的是接下来要上的课） */
    static final int MAX_ROWS = 6;

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        render(c, mgr, ids);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        if (ACTION_TICK.equals(intent.getAction())) {
            refreshAll(c);
        }
    }

    @Override
    public void onEnabled(Context c) {
        refreshAll(c);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager mgr, int id, Bundle opts) {
        render(c, mgr, new int[]{id});
    }

    @Override
    public void onDisabled(Context c) {
        cancelTick(c);
    }

    /** 全量重画 + 重排下一个闹钟。App 里同步完课表、改过开学日期都要调它。 */
    public static void refreshAll(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        if (mgr == null) return;
        ComponentName cn = new ComponentName(c, TodayWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) return;
        render(c, mgr, ids);
    }

    static void render(Context c, AppWidgetManager mgr, int[] ids) {
        if (ids == null || ids.length == 0) return;
        WidgetData.Snapshot snap = WidgetData.load(c, MAX_ROWS);
        android.util.Log.d("ZSTS-WIDGET", "rows=" + snap.card.chips.size()
                + " more=" + snap.card.more + " nextMin=" + snap.nextRefreshMin
                + " title=" + snap.card.title);

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_today);
        v.setTextViewText(R.id.w_title, snap.card.title);
        v.setTextViewText(R.id.w_meta, snap.card.sub);

        boolean noRows = snap.card.chips.isEmpty();
        v.setViewVisibility(R.id.w_list, noRows ? View.GONE : View.VISIBLE);
        // 没有课程条时，用一句话占住中间，而不是留一片空白
        String note = snap.card.note;
        if (noRows && (note == null || note.isEmpty())) note = c.getString(R.string.widget_none);
        v.setTextViewText(R.id.w_none, note == null ? "" : note);
        v.setViewVisibility(R.id.w_none, noRows ? View.VISIBLE : View.GONE);

        Intent svc = new Intent(c, RowsService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, ids[0]);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        v.setRemoteAdapter(R.id.w_list, svc);

        Intent open = new Intent(c, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.w_title, pi);
        v.setOnClickPendingIntent(R.id.w_meta, pi);
        v.setOnClickPendingIntent(R.id.w_none, pi);
        v.setPendingIntentTemplate(R.id.w_list, pi);

        mgr.updateAppWidget(ids, v);
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list);

        scheduleTick(c, snap.nextRefreshMin);
    }

    /** 在下一个边界时刻叫醒自己（分钟精度，最多排到今晚上）。 */
    private static void scheduleTick(Context c, int nextMin) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        cancelTick(c);

        long at;
        if (nextMin < 0) {
            // 今天没有边界了：排到明天 00:05，让「今天」翻页
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 5);
            cal.set(java.util.Calendar.SECOND, 0);
            at = cal.getTimeInMillis();
        } else {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, nextMin / 60);
            cal.set(java.util.Calendar.MINUTE, nextMin % 60);
            cal.set(java.util.Calendar.SECOND, 2);
            at = cal.getTimeInMillis();
            if (at <= System.currentTimeMillis()) at = System.currentTimeMillis() + 60_000L;
        }

        PendingIntent pi = tickIntent(c);
        // 精确闹钟需要权限且部分 ROM 会拦；用 set() 即可（系统会微调几分钟，可接受）
        am.set(AlarmManager.RTC_WAKEUP, at, pi);
    }

    private static PendingIntent tickIntent(Context c) {
        Intent i = new Intent(c, TodayWidget.class);
        i.setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelTick(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(tickIntent(c));
    }
}
