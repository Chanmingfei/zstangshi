package com.tstc.zstangshi.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 课程焦点通知的广播入口：
 *   - [ACTION_BOUNDARY]：AlarmManager 边界闹钟，到点重算（课前 N 分钟 / 上课 / 下课 / 课间结束）；
 *   - [ACTION_TICK]：课前每分钟一次，只为刷新「距上课 N 分钟」这个文本（计时器关掉了，时间得自己算）；
 *   - [ACTION_DISMISS]：用户划掉通知，本节内不再提醒；
 *   - 开机、应用更新、时间/时区变更：重新对齐闹钟。
 */
class FocusReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_BOUNDARY = "com.tstc.zstangshi.focus.BOUNDARY"
        const val ACTION_TICK = "com.tstc.zstangshi.focus.TICK"
        const val ACTION_DISMISS = "com.tstc.zstangshi.focus.DISMISS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        try {
            when (intent.action) {
                ACTION_DISMISS -> {
                    val key = intent.getStringExtra(FocusNotification.EXTRA_KEY)
                    val lessonEnd = intent.getLongExtra("lesson_end", 0L)
                    if (!key.isNullOrEmpty()) {
                        FocusController(app).dismiss(key, lessonEnd)
                    }
                }
                else -> FocusController(app).reconcile()
            }
        } catch (t: Throwable) {
            // 广播里任何异常都不能外抛
        } finally {
            try {
                pending.finish()
            } catch (ignored: Throwable) {
            }
        }
    }
}
