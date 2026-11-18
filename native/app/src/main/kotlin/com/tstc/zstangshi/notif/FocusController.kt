package com.tstc.zstangshi.notif

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tstc.zstangshi.data.Store
import org.json.JSONObject

/**
 * 课程焦点通知的调度中枢。
 *
 * 职责：
 *   1. 读写开关 / 提前分钟数；
 *   2. 按当前时刻重算计划（[FocusPlan]）并发布 / 取消通知；
 *   3. 用 AlarmManager 在下一个边界（课前 N 分钟、上课、下课、下一节）醒来重算；
 *   4. 记录「划掉本门课」的临时屏蔽，到这节课结束自动失效。
 *
 * 所有入口都吞掉 `Throwable`：通知子系统在精简 ROM 上出问题时不能把主流程带崩。
 */
class FocusController(private val ctx: Context) {

    companion object {
        const val PREFS = "focus_notify"
        const val KEY_ENABLED = "enabled"
        const val KEY_LEAD = "leadMinutes"
        const val KEY_DISMISSED = "dismissed"
        const val KEY_NEXT = "nextBoundary"
        const val KEY_PREVIEW_VISIBLE = "previewVisible"

        val LEAD_OPTIONS = intArrayOf(0, 5, 10, 15, 30)
        const val DEFAULT_LEAD = 10

        fun prefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

        fun leadMinutes(ctx: Context): Int {
            val v = prefs(ctx).getInt(KEY_LEAD, DEFAULT_LEAD)
            return if (LEAD_OPTIONS.contains(v)) v else DEFAULT_LEAD
        }

        fun setEnabled(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        }

        fun setLead(ctx: Context, minutes: Int) {
            prefs(ctx).edit().putInt(KEY_LEAD, if (LEAD_OPTIONS.contains(minutes)) minutes else DEFAULT_LEAD).apply()
        }

        /** 系统是否具备实况通知能力（API 36+）。 */
        fun liveSupported(): Boolean = Api36.supported()

        fun leadLabel(minutes: Int): String = if (minutes <= 0) "上课时" else "提前 $minutes 分钟"
    }

    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    private val p = prefs(ctx)

    // ---------- 开关 / 设置 ----------

    fun setEnabled(on: Boolean): Boolean {
        if (on && !Api36.supported()) return false
        setEnabled(ctx, on)
        if (on) FocusNotification.ensureChannel(ctx)
        reconcile()
        return true
    }

    fun setLead(minutes: Int) {
        setLead(ctx, minutes)
        reconcile()
    }

    // ---------- 核心重算 ----------

    fun reconcile() {
        try {
            FocusNotification.ensureChannel(ctx)
            if (!isEnabled(ctx) || nm == null || !notificationsAllowed()) {
                cancelAll()
                cancelAlarm()
                return
            }
            val store = Store(ctx)
            val t = store.loadTimetable()
            if (t == null) {
                cancelAll()
                cancelAlarm()
                return
            }
            val start = store.startDate(t.semester.id).ifEmpty { t.semester.startDate }
            val now = System.currentTimeMillis()
            val plan = FocusPlan.build(t, start, store.schedule(), leadMinutes(ctx), now)

            val dismissed = dismissedMap()
            val desired = HashMap<String, FocusPlan.Reminder>()
            for (r in plan.reminders) {
                if (isDismissed(dismissed, r, now)) continue
                desired[r.key] = r
            }

            // 取消已不需要的（换课、划掉、过期）
            for (a in nm.activeNotifications) {
                if (a.id != FocusNotification.NOTIFY_ID) continue
                val key = a.notification.extras.getString(FocusNotification.EXTRA_KEY)
                if (key == null || !desired.containsKey(key)) {
                    nm.cancel(a.tag, a.id)
                }
            }

            // 发布需要的
            for (r in desired.values) {
                try {
                    val n = FocusNotification.build(ctx, r, now, false)
                    nm.notify(FocusNotification.TAG_PREFIX + r.key, FocusNotification.NOTIFY_ID, n)
                } catch (t2: Throwable) {
                    // 单条失败不影响其它课程
                }
            }

            scheduleAlarm(plan.nextBoundary)

            // 课前阶段关掉了系统计时器（胶囊要显示课程名 + 地点），
            // 「距上课 N 分钟」是算出来的文本，得每分钟刷一次才不会停在旧数字上。
            val needTick = desired.values.any { it.phase == FocusPlan.Phase.UPCOMING }
            if (needTick) scheduleTick() else cancelTick()
        } catch (t: Throwable) {
            // 通知子系统异常不能影响 App 主流程
        }
    }

    fun cancelAll() {
        try {
            nm?.activeNotifications?.forEach {
                if (it.id == FocusNotification.NOTIFY_ID || it.id == FocusNotification.PREVIEW_ID) {
                    nm.cancel(it.tag, it.id)
                }
            }
        } catch (t: Throwable) {
        }
    }

    // ---------- 划掉处理 ----------

    /** 用户把某门课的通知划掉：本节课内不再提醒。 */
    fun dismiss(key: String, lessonEnd: Long) {
        try {
            val now = System.currentTimeMillis()
            val map = dismissedMap()
            map[key] = if (lessonEnd > now) lessonEnd else now + 60_000L
            writeDismissed(map, now)
            cancelOne(key)
            reconcile()
        } catch (t: Throwable) {
        }
    }

    fun restoreAll() {
        try {
            p.edit().remove(KEY_DISMISSED).apply()
            reconcile()
        } catch (t: Throwable) {
        }
    }

    // ---------- 预览 ----------

    /**
     * 发一条示例通知。
     *
     * [phase] 决定演示哪种形态：
     *   - `UPCOMING`：还没上课 —— 收起态显示「课程名 · 地点」，没有倒计时；
     *   - `IN_CLASS`：已上课   —— 收起态显示倒计时，展开态有地点。
     */
    fun preview(phase: FocusPlan.Phase = FocusPlan.Phase.IN_CLASS): Boolean {
        return try {
            if (!Api36.supported()) return false
            FocusNotification.ensureChannel(ctx)
            val now = System.currentTimeMillis()
            val r = FocusPlan.Reminder()
            r.key = "preview"
            r.title = "示例课程"
            r.place = "东校区 3-518"
            r.detail = "3-4节 · 10:10-11:45"
            r.phase = phase
            // 课前预览给 25 分钟，和真实提前量接近，方便看「距上课 25 分钟 · 地点」这行
            val left = if (phase == FocusPlan.Phase.UPCOMING) 25 * 60_000L else 120_000L
            r.deadline = now + left
            r.lessonEnd = now + left
            nm?.notify(FocusNotification.PREVIEW_ID, FocusNotification.build(ctx, r, now, true))
            p.edit().putBoolean(KEY_PREVIEW_VISIBLE, true).apply()
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun stopPreview() {
        try {
            nm?.cancel(FocusNotification.PREVIEW_ID)
            p.edit().putBoolean(KEY_PREVIEW_VISIBLE, false).apply()
        } catch (t: Throwable) {
        }
    }

    // ---------- 状态查询 ----------

    fun notificationsAllowed(): Boolean {
        val manager = nm ?: return false
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = manager.getNotificationChannel(FocusNotification.CHANNEL_ID)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun canPostPromoted(): Boolean = Api36.canPostPromoted(ctx)

    /** 普通通知权限是否已授予（Android 13+ 运行时权限）。 */
    fun notifyPermissionOk(): Boolean = Api36.notificationsPermissionGranted(ctx)

    /** 精确闹钟是否可用（关闭时提醒会退化为不精确，不阻断功能）。 */
    fun exactAlarmOk(): Boolean = Api36.exactAlarmGranted(ctx)

    fun hasActive(): Boolean = try {
        nm?.activeNotifications?.any { it.id == FocusNotification.NOTIFY_ID } ?: false
    } catch (t: Throwable) {
        false
    }

    fun hasHidden(): Boolean = try {
        val now = System.currentTimeMillis()
        dismissedMap().values.any { it > now }
    } catch (t: Throwable) {
        false
    }

    // ---------- 闹钟 ----------

    private fun scheduleAlarm(at: Long) {
        val manager = am ?: return
        val pi = alarmIntent()
        try {
            if (at <= 0L) {
                manager.cancel(pi)
                pi.cancel()
                p.edit().putLong(KEY_NEXT, 0L).apply()
                return
            }
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            p.edit().putLong(KEY_NEXT, at).apply()
        } catch (t: Throwable) {
        }
    }

    private fun cancelAlarm() {
        try {
            val pi = alarmIntent()
            am?.cancel(pi)
            pi.cancel()
            p.edit().putLong(KEY_NEXT, 0L).apply()
            cancelTick()
        } catch (t: Throwable) {
        }
    }

    // ---------- 每分钟刷新（课前倒计时文本） ----------

    private fun scheduleTick() {
        val manager = am ?: return
        val pi = tickIntent()
        try {
            val now = System.currentTimeMillis()
            // 下一个整分钟 + 2 秒：分钟一变就尽快刷，避免看到「25 分钟」其实只剩 24 分半
            val next = now - now % 60000L + 62000L
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        } catch (t: Throwable) {
        }
    }

    private fun cancelTick() {
        try {
            val pi = tickIntent()
            am?.cancel(pi)
            pi.cancel()
        } catch (t: Throwable) {
        }
    }

    private fun tickIntent(): PendingIntent {
        val i = Intent(ctx, FocusReceiver::class.java).setAction(FocusReceiver.ACTION_TICK)
        return PendingIntent.getBroadcast(
            ctx, 2, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alarmIntent(): PendingIntent {
        val i = Intent(ctx, FocusReceiver::class.java).setAction(FocusReceiver.ACTION_BOUNDARY)
        return PendingIntent.getBroadcast(
            ctx, 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---------- 划掉记录 ----------

    private fun dismissedMap(): MutableMap<String, Long> {
        val raw = p.getString(KEY_DISMISSED, "") ?: return HashMap()
        if (raw.isEmpty()) return HashMap()
        return try {
            val o = JSONObject(raw)
            val out = HashMap<String, Long>()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = o.optLong(k)
            }
            out
        } catch (e: Exception) {
            HashMap()
        }
    }

    private fun isDismissed(map: Map<String, Long>, r: FocusPlan.Reminder, now: Long): Boolean {
        val until = map[r.key] ?: return false
        return now < until
    }

    private fun writeDismissed(map: Map<String, Long>, now: Long) {
        try {
            val o = JSONObject()
            for ((k, v) in map) if (v > now) o.put(k, v)
            p.edit().putString(KEY_DISMISSED, o.toString()).apply()
        } catch (t: Throwable) {
        }
    }

    private fun cancelOne(key: String) {
        try {
            nm?.activeNotifications?.forEach {
                if (it.id == FocusNotification.NOTIFY_ID &&
                    it.tag == FocusNotification.TAG_PREFIX + key
                ) {
                    nm.cancel(it.tag, it.id)
                }
            }
        } catch (t: Throwable) {
        }
    }
}
