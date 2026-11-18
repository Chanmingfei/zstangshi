package com.tstc.zstangshi.notif

import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Android 16（API 36）「实况通知 / 焦点通知 / 超级岛 / 灵动岛」相关接口的封装。
 *
 * 本工程用 **android-34 的 android.jar** 编译（不升级构建链，保持 `aapt2+kotlinc+d8` 一条命令出包），
 * 而下面这些接口直到 API 36 才公开，所以只能反射调用：
 *   - `Notification.Builder#setShortCriticalText(CharSequence)`  API 36
 *   - `Notification#hasPromotableCharacteristics()`               API 36
 *   - `Notification#FLAG_PROMOTED_ONGOING`                        API 36
 *   - `NotificationManager#canPostPromotedNotifications()`        API 36
 *
 * 全部包在 `try/catch(Throwable)` 里：低版本 Android 或精简 ROM 上调用直接静默失败，
 * 普通通知照常显示，不会因为反射失败而崩溃。
 */
object Api36 {

    const val MIN_API = 36

    /** Android 13+ 的普通通知运行时权限。 */
    const val PERM_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /**
     * 实况（焦点）通知权限。
     *
     * ⚠️ 它和普通通知权限**不是同一回事**：
     * 系统清单里 `protectionLevel=normal|appop`，安装即授予（`checkSelfPermission` 直接是 GRANTED），
     * 但真正能否发布实况通知由 **AppOp** 控制，用户在系统里关掉后运行时弹窗**申请不回来**，
     * 只能引导到系统设置页手动打开。所以：
     *   - 状态查询走 [canPostPromoted]（官方 API 优先，AppOps 兜底）；
     *   - 兜底引导走 [openNotificationSettings]。
     */
    const val PERM_PROMOTED = "android.permission.POST_PROMOTED_NOTIFICATIONS"

    /** AppOp 名（`POST_PROMOTED_NOTIFICATIONS` 对应的字符串形式）。 */
    private const val OP_PROMOTED = "android:post_promoted_notifications"
    private const val OP_PROMOTED_ALT = "post_promoted_notifications"

    fun supported(): Boolean = Build.VERSION.SDK_INT >= MIN_API

    // ---------- 通知构建 ----------

    /** 胶囊右侧的「关键文本」（超级岛上显示的那一行）。 */
    fun setShortCriticalText(builder: Notification.Builder, text: CharSequence): Boolean {
        if (!supported()) return false
        return try {
            val m = Notification.Builder::class.java.getMethod(
                "setShortCriticalText", CharSequence::class.java)
            m.invoke(builder, text)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 系统是否认这条通知为可提升的实况通知。 */
    fun hasPromotableCharacteristics(n: Notification): Boolean {
        if (!supported()) return false
        return try {
            val m = Notification::class.java.getMethod("hasPromotableCharacteristics")
            (m.invoke(n) as? Boolean) ?: false
        } catch (t: Throwable) {
            false
        }
    }

    fun flagPromotedOngoing(): Int = try {
        Notification::class.java.getField("FLAG_PROMOTED_ONGOING").getInt(null)
    } catch (t: Throwable) {
        0
    }

    // ---------- 权限状态 ----------

    /** 普通通知权限（Android 13+ 运行时权限，低于 13 视为已授予）。 */
    fun notificationsPermissionGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return try {
            ctx.checkSelfPermission(PERM_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            true
        }
    }

    /**
     * 用户/系统是否允许本应用发布实况（焦点）通知。
     *
     * 优先用官方 `canPostPromotedNotifications()`；它在部分 ROM 上不存在或恒为 false，
     * 再用 AppOps 复核，避免把「系统没实现这个 API」误判成「用户拒绝了」。
     */
    fun canPostPromoted(ctx: Context): Boolean {
        if (!supported()) return false
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null) {
                val m = NotificationManager::class.java.getMethod("canPostPromotedNotifications")
                val r = m.invoke(nm) as? Boolean
                if (r == true) return true
                // 官方说法是 false：仍用 AppOp 复核一次（有的 ROM API 恒 false 但实际可用）
                if (appOpAllowed(ctx, OP_PROMOTED) || appOpAllowed(ctx, OP_PROMOTED_ALT)) return true
                return false
            }
        } catch (t: Throwable) {
            // 落到 AppOps 判断
        }
        return appOpAllowed(ctx, OP_PROMOTED) || appOpAllowed(ctx, OP_PROMOTED_ALT)
    }

    /** 直接查 AppOp（`unsafeCheckOpNoThrow`，不产生副作用）。 */
    private fun appOpAllowed(ctx: Context, op: String): Boolean {
        return try {
            val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                aom.unsafeCheckOpNoThrow(op, Process.myUid(), ctx.packageName)
            } else {
                @Suppress("DEPRECATION")
                aom.checkOpNoThrow(op, Process.myUid(), ctx.packageName)
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                // 未显式设置：跟着权限走，而该权限是安装即授予的
                AppOpsManager.MODE_DEFAULT -> true
                else -> false
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** 精确闹钟（课前准点提醒依赖它；关闭后会退化为不精确闹钟）。 */
    fun exactAlarmGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return true
            am.canScheduleExactAlarms()
        } catch (t: Throwable) {
            true
        }
    }

    // ---------- 引导到系统设置 ----------

    /**
     * 打开「本应用的通知设置」。
     *
     * 各家的「实况通知 / 焦点通知 / 流体云 / 灵动通知」开关都在这一页里：
     * API 36 起有独立的允许提升页（部分 ROM 有实现），没有就落到应用通知设置页。
     */
    fun openNotificationSettings(a: Activity, pkg: String): Boolean {
        // 1) API 36 的「允许提升的通知」专用页
        for (action in arrayOf(
            "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS",
            "android.settings.APP_NOTIFICATION_PROMOTION_SETTING"
        )) {
            val i = Intent(action)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                .putExtra("android.provider.extra.APP_PACKAGE", pkg)
            if (start(a, i)) return true
        }
        // 2) 应用通知设置页（ColorOS / HyperOS 的开关通常在这里）
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            .putExtra("android.provider.extra.APP_PACKAGE", pkg)
        if (start(a, i)) return true
        // 3) 应用详情页兜底
        return start(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg)))
    }

    /** 打开「允许精确闹钟」授权页。 */
    fun openExactAlarmSettings(a: Activity, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return start(a, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + pkg)))
    }

    /** 打开系统的应用通知总开关页（连通知权限都被关了时用）。 */
    fun openAppDetails(a: Activity, pkg: String): Boolean {
        return start(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg)))
    }

    private fun start(a: Activity, i: Intent): Boolean {
        return try {
            a.startActivity(i)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
