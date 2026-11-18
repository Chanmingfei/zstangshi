package com.tstc.zstangshi.notif

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 鸿蒙（HarmonyOS）「实况窗」适配层 —— **仅鸿蒙专版有，标准版直接走 Android 16 实况通知**。
 *
 * ## 为什么要单独做一层
 *
 * 「实况窗 / 超级岛 / 灵动岛」在 Android 侧的正式名字是 **实况通知（Live Updates）**，
 * 接口直到 **API 36** 才公开（`setShortCriticalText` + `requestPromotedOngoing` +
 * `FLAG_PROMOTED_ONGOING`）。而鸿蒙的 Android 兼容层版本跨度很大：
 *
 * | 设备 | Android 兼容层 | 实况协议 | 本层判定 |
 * |---|---|---|---|
 * | HarmonyOS 5.x / 原生 Android 16 / ColorOS 16 / HyperOS 3 | API 36+ | 支持 | [MODE_LIVE] |
 * | HarmonyOS 4.x / 3.x（AOSP 兼容层 API 29~33） | 无 | 不支持 | [MODE_PERSISTENT] |
 * | 通知被系统整体关闭 | — | — | [MODE_NONE] |
 *
 * 鸿蒙原生「实况窗」是 ArkTS（LiveViewKit / WantAgent + 服务卡片）能力，**APK 无法直接创建**；
 * 所以在 [MODE_PERSISTENT] 下走等效呈现：**常驻通知 + 系统计时器（chronometer）+ 关键文本**，
 * 在鸿蒙的通知中心 / 锁屏实时显示课程倒计时，行为与实况窗一致（只是形态由系统决定）。
 *
 * ## 套用到别的项目时
 *
 * 只需拷 `notif/` 整包，并把 `FocusController.liveSupported(ctx)` 的判定换成你自己的
 * `HarmonyLive.canShow(ctx)`；剩下三处能力探测（[isHarmony] / [mode] / [modeName]）
 * 与业务无关，可原样复用。
 */
object HarmonyLive {

    /** 系统实现了实况通知协议（API 36+）：可请求提升为实况窗。 */
    const val MODE_LIVE = 0

    /** 只有标准通知：走「常驻 + 计时器」等效实况窗。 */
    const val MODE_PERSISTENT = 1

    /** 连通知都发不出去（服务缺失 / 用户关闭通知）。 */
    const val MODE_NONE = 2

    /**
     * 是否是鸿蒙系统。
     *
     * 不看 `Build.MANUFACTURER`（兼容层里常被改写成 HUAWEI/Android），
     * 只认系统属性与显示版本串 —— 这几个在真机上稳定存在，且读取本身不抛。
     */
    fun isHarmony(): Boolean {
        for (key in arrayOf("os.harmony.version", "hw_sc.build.platform.version")) {
            val v = sysProp(key)
            if (v.isNotEmpty()) return true
        }
        val brand = try { Build.BRAND ?: "" } catch (t: Throwable) { "" }
        val display = try { Build.DISPLAY ?: "" } catch (t: Throwable) { "" }
        return brand.equals("harmony", true) || brand.equals("harmonyos", true) ||
                display.contains("Harmony", true) || display.contains("OHOS", true)
    }

    /** 当前生效的呈现模式，见 [MODE_LIVE] / [MODE_PERSISTENT] / [MODE_NONE]。 */
    fun mode(ctx: Context): Int {
        val nm = try {
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        } catch (t: Throwable) {
            null
        } ?: return MODE_NONE
        // 通知被整体禁用时，两种模式都发不出去
        try {
            if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return MODE_NONE
        } catch (t: Throwable) {
            // 精简 ROM 没有这个 API：当成可用
        }
        // 有实况协议就走实况窗，否则常驻倒计时兜底
        return if (Api36.supported()) MODE_LIVE else MODE_PERSISTENT
    }

    /** 通知能力是否可用（有任一档就算可用）。 */
    fun canShow(ctx: Context): Boolean = mode(ctx) != MODE_NONE

    /** 设置页展示用：当前是什么形态。 */
    fun modeName(ctx: Context): String = when (mode(ctx)) {
        MODE_LIVE -> if (isHarmony()) "鸿蒙实况窗 · 协议直连" else "系统实况通知"
        MODE_PERSISTENT -> "常驻倒计时 · 等效实况窗"
        else -> "系统不支持"
    }

    /** 设置页的补充说明（一行小字，解释为什么是这个形态）。 */
    fun modeHint(ctx: Context): String = when (mode(ctx)) {
        MODE_LIVE ->
            "课程会以实况窗形态出现在通知中心与锁屏，收起态显示课程名与地点，上课后走秒"
        MODE_PERSISTENT ->
            "当前系统没有实况窗协议，已用「常驻通知 + 系统计时器」等效呈现：通知不可划掉，倒计时实时走秒"
        else ->
            "请先在系统设置里允许「掌上唐师」的通知"
    }

    private fun sysProp(key: String): String {
        return try {
            val v = System.getProperty(key)
            if (v.isNullOrEmpty()) "" else v.trim()
        } catch (t: Throwable) {
            ""
        }
    }
}
