package com.tstc.zstangshi.ui

import android.app.Activity
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * 预测性返回（predictive back）的薄封装，只用 framework API。
 *
 * Android 13（API 33）起，返回手势走 [OnBackInvokedDispatcher]；
 * Android 14（API 34）起多了 `OnBackAnimationCallback`，手势还没松手就会
 * 持续回调 onBackProgressed —— 这就是「跟手动画」的来源。
 *
 * ⚠️ 鸿蒙专版改动：
 *
 * 1. 带进度的回调抽到独立文件 [AnimatedBackCallback]。
 *    原来 `Animated` 是这个文件里的内部类，ART 验证本类方法体时会去解析
 *    `OnBackAnimationCallback`（API 34 才有），鸿蒙上这个类不存在 →
 *    `NoClassDefFoundError`。那是 **Error 不是 Exception**，try 块根本来不及生效。
 *    拆成独立文件后，只有真正 `new AnimatedBackCallback(...)` 时才加载它。
 *
 * 2. 判断「是否支持」不再只看 `SDK_INT`，还加了一次静态自检
 *    （见 [animatedAvailable]），鸿蒙上报的 SDK_INT 可能是 34 但类其实没有。
 *
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

    /**
     * 带进度的回调是否真的可用。
     *
     * 不能只看 SDK_INT：鸿蒙等兼容层可能报 34，但 `OnBackAnimationCallback`
     * 类并不存在。这里做一次「真的能加载吗」的探测，任何异常都当不支持。
     */
    private fun animatedAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return try {
            @Suppress("UNUSED_EXPRESSION")
            Class.forName("android.window.OnBackAnimationCallback")
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 系统是否支持 OnBackInvokedDispatcher（不支持就走 onBackPressed）。 */
    @JvmStatic
    fun supported(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            Class.forName("android.window.OnBackInvokedDispatcher")
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 注册回调，返回值下一次 [uninstall] 时原样传回来。
     * 返回 null 表示这台机器/这个版本不支持，调用方不用管。
     */
    @JvmStatic
    fun install(a: Activity?, h: Handler?): Any? {
        if (a == null || h == null) return null
        if (!supported()) return null
        try {
            val d = a.onBackInvokedDispatcher ?: return null

            // 34+ 且有真进度回调：用跟手版本；否则退回「只有触发」的版本
            var cb: OnBackInvokedCallback? = null
            if (animatedAvailable()) {
                cb = try {
                    AnimatedBackCallback(h)
                } catch (t: Throwable) {
                    // 类加载/实例化失败：退回不跟手的版本
                    null
                }
            }
            if (cb == null) cb = Plain(h)

            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            return cb
        } catch (t: Throwable) {
            // 某些定制 ROM / 鸿蒙的 dispatcher 会抛，退回 onBackPressed 兜底即可
            return null
        }
    }

    @JvmStatic
    fun uninstall(a: Activity?, token: Any?) {
        if (token == null || a == null) return
        if (!supported()) return
        try {
            a.onBackInvokedDispatcher
                .unregisterOnBackInvokedCallback(token as OnBackInvokedCallback)
        } catch (ignored: Throwable) {
        }
    }

    /** Android 13：只有「触发」这一个回调。 */
    private class Plain(private val h: Handler) : OnBackInvokedCallback {
        override fun onBackInvoked() {
            try {
                h.onCommit()
            } catch (ignored: Throwable) {
            }
        }
    }
}