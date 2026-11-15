package com.tstc.zstangshi.ui

import android.window.BackEvent
import android.window.OnBackAnimationCallback

/**
 * Android 14+ 的跟手返回回调（带进度）。
 *
 * ⚠️ 鸿蒙专版：**必须单独成文件**。
 *
 * `OnBackAnimationCallback` 是 API 34 才有的类。如果它和 [BackGestures] 写在同一
 * 个文件里（内部类），ART 在验证 `BackGestures` 的方法体时会一并解析到
 * `Animated` 的父类 —— 而鸿蒙上这个类根本不存在，于是抛 `NoClassDefFoundError`。
 *
 * `NoClassDefFoundError` 是 **Error 不是 Exception**，`catch (e: Exception)`
 * 抓不住，`catch (Throwable)` 理论上能抓，但类验证阶段就炸了，try 块还没进。
 * 这就是原版「一点登录就闪退」的第二个来源。
 *
 * 拆成独立文件后，只有 [BackGestures.install] 里显式 `new Animated(...)`
 * 那一刻才会加载这个类；外面再套一层 `SDK_INT >= 34` + try/catch 就彻底安全了。
 */
internal class AnimatedBackCallback(
    private val h: BackGestures.Handler
) : OnBackAnimationCallback {

    override fun onBackStarted(e: BackEvent) {
        try {
            h.onStart()
        } catch (ignored: Throwable) {
        }
    }

    override fun onBackProgressed(e: BackEvent) {
        var p = 0f
        try {
            p = e.progress
        } catch (ignored: Throwable) {
        }
        try {
            h.onProgress(Math.max(0f, Math.min(1f, p)))
        } catch (ignored: Throwable) {
        }
    }

    override fun onBackInvoked() {
        try {
            h.onCommit()
        } catch (ignored: Throwable) {
        }
    }

    override fun onBackCancelled() {
        try {
            h.onCancel()
        } catch (ignored: Throwable) {
        }
    }
}