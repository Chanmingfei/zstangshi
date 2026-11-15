package com.tstc.zstangshi.harmony

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 鸿蒙专版的**安全网**。
 *
 * 背景：安卓版装在鸿蒙上「一点登录就闪退」，根因是三处 Android 专有 API
 * （AppWidgetManager / OnBackAnimationCallback / WindowInsets.Type）在鸿蒙上不存在，
 * 抛出的又是 `NoClassDefFoundError` 这类 **Error 而不是 Exception**，
 * 普通 `catch (e: Exception)` 抓不住，异常一路抛到 UI 线程顶层 → 进程被杀。
 *
 * 前面已经逐个修了那三处。但鸿蒙的兼容层到底还缺什么，没人能穷举完 ——
 * 所以再加这一层：装上 `Thread.UncaughtExceptionHandler`，
 * 任何漏网的异常都先在本地落一条日志（供排查），然后**吞掉**，
 * 让用户回到桌面而不是看到「应用已停止」。
 *
 * 为什么吞掉而不是重新抛：
 *   重抛等于沿用原崩溃。既然我们的目标就是「别闪退」，那就必须吃掉。
 *   代价是可能留下不一致的界面状态 —— 但用户回到桌面再点进来，
 *   Activity 会重新创建，比整机闪退体验好得多。
 *
 * 崩溃日志写到 `filesDir/crash/harmony-*.log`，最多保留最近 10 个。
 */
object CrashGuard {

    private const val TAG = "ZSTS-CRASH"
    private const val MAX_LOGS = 10

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 里调一次即可。 */
    @JvmStatic
    fun install(app: Application) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val swallowed = try {
                handle(app, thread, throwable)
                true
            } catch (t: Throwable) {
                false
            }
            if (!swallowed) {
                // 连日志都写不出来：交回系统，至少别变成死循环
                try {
                    previous?.uncaughtException(thread, throwable)
                } catch (ignored: Throwable) {
                }
            }
        }
        Log.i(TAG, "CrashGuard installed")
    }

    private fun handle(app: Application, thread: Thread, throwable: Throwable) {
        val text = buildString {
            appendLine("time=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("thread=" + thread.name)
            appendLine("sdk=" + Build.VERSION.SDK_INT)
            appendLine("device=" + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("brand=" + Build.BRAND + " / " + Build.DISPLAY)
            appendLine("harmonyHint=" + isHarmonyHint())
            appendLine("---- stack ----")
            val sw = StringWriter()
            PrintWriter(sw).use { throwable.printStackTrace(it) }
            append(sw.toString())
        }
        Log.e(TAG, text)
        writeLog(app, text)
    }

    /**
     * 粗判是不是鸿蒙环境。
     * 鸿蒙的兼容层通常会把 Build 里带上 huawei / harmony 的字样，
     * 但这是启发式判断，只用于日志分组，不作为任何行为分支依据。
     */
    private fun isHarmonyHint(): Boolean {
        val hay = (
                Build.BRAND + " " + Build.MANUFACTURER + " " + Build.MODEL + " " +
                        Build.DISPLAY + " " + Build.HARDWARE + " " + Build.VERSION.RELEASE
                ).lowercase(Locale.US)
        return hay.contains("harmony") || hay.contains("hongmeng") || hay.contains("openharmony")
    }

    private fun writeLog(app: Application, text: String) {
        try {
            val dir = File(app.filesDir, "crash")
            if (!dir.exists()) dir.mkdirs()
            val name = "harmony-" +
                    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
            File(dir, name).writeText(text, Charsets.UTF_8)

            // 只留最近 MAX_LOGS 个
            val all = dir.listFiles()
            if (all != null && all.size > MAX_LOGS) {
                all.sortedBy { it.lastModified() }
                        .take(all.size - MAX_LOGS)
                        .forEach { it.delete() }
            }
        } catch (t: Throwable) {
            // 写日志失败无所谓
        }
    }
}