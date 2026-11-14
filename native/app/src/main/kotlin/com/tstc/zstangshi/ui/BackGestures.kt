package com.tstc.zstangshi.ui

import android.app.Activity
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * 预测性返回（predictive back）的薄封装，只用 framework API。
 *
 * Android 13（API 33）起，返回手势走 [OnBackInvokedDispatcher]；
 * Android 14（API 34）起多了 [OnBackAnimationCallback]，手势还没松手就会
 * 持续回调 onBackProgressed —— 这就是「跟手动画」的来源。
 *
 * 用法：不在需要接管返回时调用 [uninstall]，交给系统做默认的退出动画。
 * 所有 API 33 以下一律短路返回 null，调用方按老路子走 onBackPressed。
 */
object BackGestures {

    /** 调用方只关心这四个时机。 */
    interface Handler {
        /** 手势被系统认定为返回，开始跟手。 */
        fun onStart()

        /** 手指当前位置对应的进度（0 = 刚开始，1 = 已到触发阈值）。 */
        fun onProgress(progress: Float)

        /** 松手，返回已触发。 */
        fun onCommit()

        /** 手势被取消（手指划回去了）。 */
        fun onCancel()
    }

    @JvmStatic
    fun supported(): Boolean {
        return Build.VERSION.SDK_INT >= 33
    }

    /**
     * 注册回调，返回值下一次 [uninstall] 时原样传回来。
     * 返回 null 表示这台机器/这个版本不支持，调用方不用管。
     */
    @JvmStatic
    fun install(a: Activity?, h: Handler?): Any? {
        if (Build.VERSION.SDK_INT < 33 || a == null || h == null) return null
        try {
            val d = a.onBackInvokedDispatcher
            if (d == null) return null
            // 34 起用带进度的回调，33 只有「已触发」这一下
            val cb: OnBackInvokedCallback = if (Build.VERSION.SDK_INT >= 34) Animated(h) else Plain(h)
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            return cb
        } catch (t: Throwable) {
            // 某些定制 ROM 的 dispatcher 会抛，退回 onBackPressed 兜底即可
            return null
        }
    }

    @JvmStatic
    fun uninstall(a: Activity?, token: Any?) {
        if (token == null || Build.VERSION.SDK_INT < 33 || a == null) return
        try {
            a.onBackInvokedDispatcher
                .unregisterOnBackInvokedCallback(token as OnBackInvokedCallback)
        } catch (ignored: Throwable) {
        }
    }

    /** Android 13：只有「触发」这一个回调。 */
    private class Plain(private val h: Handler) : OnBackInvokedCallback {
        override fun onBackInvoked() {
            h.onCommit()
        }
    }

    /** Android 14+：手势过程中持续下发进度，动画可以跟着手指走。 */
    private class Animated(private val h: Handler) : OnBackAnimationCallback {
        override fun onBackStarted(e: BackEvent) {
            h.onStart()
        }

        override fun onBackProgressed(e: BackEvent) {
            var p = 0f
            try {
                p = e.progress
            } catch (ignored: Throwable) {
            }
            h.onProgress(Math.max(0f, Math.min(1f, p)))
        }

        override fun onBackInvoked() {
            h.onCommit()
        }

        override fun onBackCancelled() {
            h.onCancel()
        }
    }
}
