package com.tstc.zstangshi.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.tstc.zstangshi.MainActivity
import com.tstc.zstangshi.R

/**
 * 课程焦点通知的构建。
 *
 * 视觉口径（对齐用户要求）：
 *   - **不做「显不显示地点」开关**，一律显示地点；
 *   - 地点**只出现一次**，固定落在展开态的主行：「距上课 25 分钟 · 测试教学楼」；
 *     `subText` 只放阶段标签（即将上课 / 上课中 / 课间），详情行是节次与时间，都不带地点；
 *   - 右侧/左上角的图标固定用**校徽**（透明 PNG，白色 alpha 遮罩，系统按深浅色自动染色），
 *     不再用日历图标，也不再把课程名渲染成文字位图。
 *
 * 实况能力靠 `android.requestPromotedOngoing` + `setShortCriticalText`，
 * 后者在 API 36 才公开，走 [Api36] 反射。
 *
 * ## 鸿蒙专版差异（见 [HarmonyLive]）
 *
 * 鸿蒙兼容层大多没有 API 36 的实况协议，此时**不降级成普通通知**，而是走「常驻 + 计时器」：
 *   - `setOngoing(true)` + `setOnlyAlertOnce(true)`：不可划掉，不反复响铃；
 *   - `setUsesChronometer(true)` + `setWhen(deadline)`：交给系统走秒，鸿蒙通知中心/锁屏
 *     会实时显示倒计时（与实况窗同样的时间语义）；
 *   - `setVisibility(PUBLIC)` + `setLocalOnly(true)`：锁屏可见、不跨设备同步；
 *   - 课前阶段仍关闭计时器，用「课程名 · 地点」的静态文本换掉倒计时（收起态信息量更大）。
 */
object FocusNotification {

    const val CHANNEL_ID = "course_focus"
    const val NOTIFY_ID = 47201
    const val PREVIEW_ID = 47202
    const val EXTRA_KEY = "focus_key"
    const val TAG_PREFIX = "course_focus:"

    /** 校徽主色，用作通知 accent / colorized 兜底。 */
    private const val BRAND_COLOR = 0xFF761519.toInt()

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val ch = NotificationChannel(CHANNEL_ID, "课程焦点通知", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = if (HarmonyLive.isHarmony()) {
            "课程实况窗：课前、上课中与课间的倒计时（鸿蒙专版）"
        } else {
            "上课前、上课中与课间的课程倒计时，可显示在超级岛 / 灵动岛"
        }
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    fun build(ctx: Context, r: FocusPlan.Reminder, now: Long, preview: Boolean = false): Notification {
        // 展开态的行顺序是：课程名 → 主行 → 节次/时间详情。
        // 主行（contentText，同时是 bigText 首行）承载「距上课多久 · 在哪」，
        // 这样地点只出现这一次 —— subText 与详情行都不能再带地点，否则岛上会重复。
        //
        // 未上课阶段胶囊要让位给「课程名 · 地点」，就得关掉系统计时器；
        // 而计时器一关，展开态那个大数字会一起消失（它是同一个开关）。
        // 所以这里自己把剩余时间算成文本：「距上课 25 分钟 · 测试教学楼」，
        // 再由 FocusController 每分钟刷新一次，数字不会冻住。
        val upcoming = r.phase == FocusPlan.Phase.UPCOMING
        val timeLabel = if (upcoming) {
            "距上课 " + remainText(r.deadline - now)
        } else {
            // 上课中 / 课间：大数字由系统计时器走秒，这里只留说明文字
            r.phase.countdown
        }
        val main = listOf(timeLabel, r.place)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

        // 只放阶段标签，**不带地点**（地点已在主行里），避免展开态出现两遍。
        val sub = r.phase.label

        // 收起态（胶囊）只有**一个**文本槽：要么是系统计时器，要么是一段静态文字，
        // 两者不能并存（设了计时器，shortCriticalText 就不显示）。
        //   · 还没上课：不占计时器，把槽让给「课程名 · 地点」，一眼知道上什么课、去哪；
        //   · 已上课 / 课间：用计时器走秒（剩余时间比课程名更时效），课程名在展开态里。
        val chip = if (upcoming) {
            listOf(r.title, r.place).filter { it.isNotEmpty() }.joinToString(" · ")
        } else {
            r.title
        }

        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle(r.title)
            .setContentText(main)
            .setSubText(sub)
            // 校徽（透明底 PNG，已做成白色 alpha 遮罩）
            .setSmallIcon(R.drawable.ic_focus_emblem)
            .setLargeIcon(BitmapFactory.decodeResource(ctx.resources, R.drawable.ic_focus_emblem_color))
            .setColor(BRAND_COLOR)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(if (r.detail.isEmpty()) main else main + "\n" + r.detail)
            )
            .setContentIntent(contentIntent(ctx, r, preview))
            .setCategory(Notification.CATEGORY_EVENT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 鸿蒙专版：常驻通知要能在锁屏显示（实况窗的等效形态），且不走云端同步
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .setTimeoutAfter(Math.max(1000L, r.deadline - now))

        if (upcoming) {
            // 关掉时间与计时器，胶囊才会退回「文本形态」显示 chip
            builder.setShowWhen(false)
            builder.setUsesChronometer(false)
        } else {
            builder.setShowWhen(true)
            builder.setWhen(r.deadline)
            builder.setUsesChronometer(true)
            builder.setChronometerCountDown(true)
        }

        // 胶囊右侧关键文本（计时器形态下会被系统忽略，不影响走秒）
        if (chip.isNotEmpty()) {
            Api36.setShortCriticalText(builder, chip)
        }

        val extras = Bundle()
        extras.putString(EXTRA_KEY, r.key)
        // Android 16 / QPR1 起，这个 extra 是「请求提升为实况通知」的正式开关
        extras.putBoolean("android.requestPromotedOngoing", true)
        builder.addExtras(extras)

        if (!preview) {
            builder.setDeleteIntent(deleteIntent(ctx, r))
        }

        var n = builder.build()
        if (Api36.hasPromotableCharacteristics(n)) return n
        // Android 16 最初版本要求 colorized 才认；QPR1 靠上面的 extra。
        builder.setColorized(true)
        n = builder.build()
        return n
    }

    /** 剩余时间的文字形式（未上课阶段用来替代被关掉的系统计时器）。 */
    private fun remainText(left: Long): String {
        val mins = Math.ceil(Math.max(0L, left) / 60000.0).toInt()
        return when {
            mins <= 0 -> "不到 1 分钟"
            mins < 60 -> mins.toString() + " 分钟"
            else -> (mins / 60).toString() + " 小时 " + (mins % 60) + " 分"
        }
    }

    private fun contentIntent(ctx: Context, r: FocusPlan.Reminder, preview: Boolean): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            .setData(Uri.parse("zstangshi://course-focus/" + Uri.encode(if (preview) "preview" else r.key)))
        return PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun deleteIntent(ctx: Context, r: FocusPlan.Reminder): PendingIntent {
        val intent = Intent(ctx, FocusReceiver::class.java)
            .setAction(FocusReceiver.ACTION_DISMISS)
            .setData(Uri.parse("zstangshi://focus-dismiss/" + Uri.encode(r.key)))
            .putExtra(EXTRA_KEY, r.key)
            .putExtra("lesson_end", r.lessonEnd)
        return PendingIntent.getBroadcast(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
