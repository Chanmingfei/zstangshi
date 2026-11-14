package com.tstc.zstangshi.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.tstc.zstangshi.MainActivity
import com.tstc.zstangshi.R
import java.util.Calendar

/**
 * 「今日课程」小组件。
 *
 * 刷新策略：
 *   - 系统那 30 分钟的 updatePeriodMillis 只当兜底；
 *   - 真正准的是自己在「下一次上课 / 下课 / 课前 60 分钟」这几个时刻各排一个
 *     AlarmManager 闹钟，到点重算一次。所以第三门下课时，第四门会立刻顶上来，
 *     不会像旧版那样因为只有三格而永远停在前三门。
 *
 * 内容由 [WidgetData] 算，行由 [RowsService] 渲染（ListView 结构，
 * 行数不受 RemoteViews 静态布局的限制）。
 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        render(c, mgr, ids)
    }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        if (ACTION_TICK == intent.action) {
            refreshAll(c)
        }
    }

    override fun onEnabled(c: Context) {
        refreshAll(c)
    }

    override fun onAppWidgetOptionsChanged(c: Context, mgr: AppWidgetManager, id: Int, opts: Bundle?) {
        render(c, mgr, intArrayOf(id))
    }

    override fun onDisabled(c: Context) {
        cancelTick(c)
    }

    companion object {
        private const val ACTION_TICK = "com.tstc.zstangshi.widget.TICK"

        /** 列表最多渲染几行（再多也没意义，用户看的是接下来要上的课） */
        internal const val MAX_ROWS = 6

        /** 全量重画 + 重排下一个闹钟。App 里同步完课表、改过开学日期都要调它。 */
        @JvmStatic
        fun refreshAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c) ?: return
            val cn = ComponentName(c, TodayWidget::class.java)
            val ids = mgr.getAppWidgetIds(cn)
            if (ids == null || ids.isEmpty()) return
            render(c, mgr, ids)
        }

        internal fun render(c: Context, mgr: AppWidgetManager, ids: IntArray?) {
            if (ids == null || ids.isEmpty()) return
            val snap = WidgetData.load(c, MAX_ROWS)
            Log.d(
                "ZSTS-WIDGET", "rows=" + snap.card.chips.size +
                    " more=" + snap.card.more + " nextMin=" + snap.nextRefreshMin +
                    " title=" + snap.card.title
            )

            val v = RemoteViews(c.packageName, R.layout.widget_today)
            v.setTextViewText(R.id.w_title, snap.card.title)
            v.setTextViewText(R.id.w_meta, snap.card.sub)

            val noRows = snap.card.chips.isEmpty()
            v.setViewVisibility(R.id.w_list, if (noRows) View.GONE else View.VISIBLE)
            // 没有课程条时，用一句话占住中间，而不是留一片空白
            var note = snap.card.note
            if (noRows && note.isEmpty()) note = c.getString(R.string.widget_none)
            v.setTextViewText(R.id.w_none, note)
            v.setViewVisibility(R.id.w_none, if (noRows) View.VISIBLE else View.GONE)

            val svc = Intent(c, RowsService::class.java)
            svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, ids[0])
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            v.setRemoteAdapter(R.id.w_list, svc)

            val open = Intent(c, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.w_title, pi)
            v.setOnClickPendingIntent(R.id.w_meta, pi)
            v.setOnClickPendingIntent(R.id.w_none, pi)
            v.setPendingIntentTemplate(R.id.w_list, pi)

            mgr.updateAppWidget(ids, v)
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list)

            scheduleTick(c, snap.nextRefreshMin)
        }

        /** 在下一个边界时刻叫醒自己（分钟精度，最多排到今晚上）。 */
        private fun scheduleTick(c: Context, nextMin: Int) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            cancelTick(c)

            var at: Long
            if (nextMin < 0) {
                // 今天没有边界了：排到明天 00:05，让「今天」翻页
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 5)
                cal.set(Calendar.SECOND, 0)
                at = cal.timeInMillis
            } else {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, nextMin / 60)
                cal.set(Calendar.MINUTE, nextMin % 60)
                cal.set(Calendar.SECOND, 2)
                at = cal.timeInMillis
                if (at <= System.currentTimeMillis()) at = System.currentTimeMillis() + 60_000L
            }

            val pi = tickIntent(c)
            // 精确闹钟需要权限且部分 ROM 会拦；用 set() 即可（系统会微调几分钟，可接受）
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }

        private fun tickIntent(c: Context): PendingIntent {
            val i = Intent(c, TodayWidget::class.java)
            i.action = ACTION_TICK
            return PendingIntent.getBroadcast(
                c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun cancelTick(c: Context) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(tickIntent(c))
        }
    }
}
