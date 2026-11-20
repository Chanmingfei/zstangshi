package com.tstc.zstangshi.update

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.Process

import com.tstc.zstangshi.ui.Sheet
import com.tstc.zstangshi.ui.UpdatePrompt

/**
 * 启动时自动检查更新（每次进程只跑一次）。
 *
 * 刻意做得很「轻」，因为它跑在用户刚进 App 的时候，不能和自动登录、首屏渲染抢资源：
 *
 *   · **延后**：进主界面后 2.6 秒才动手，那时自动登录已经在加载页跑完、首页也画好了；
 *   · **低优先级**：后台线程 + `THREAD_PRIORITY_BACKGROUND`，调度上让着前台；
 *   · **只跑一次**：静态标记，深色模式 recreate、切页、从后台回来都**不会**再查；
 *   · **没网不查**：直接跳过，不显示「检查失败」这类无意义提示；
 *   · **失败静默**：自动检查只处理「有新版本」，连不上就什么都不做 —— 想确认版本的人
 *     可以走「我的 → 关于 → 检查更新」，那里会显示失败原因。
 *
 * 忽略规则：用户点过「忽略此版本」后，这个版本号不再提醒，但要**继续查**，
 * 直到出现更高的版本号（见 [isIgnored] 的版本比较，而不是简单相等）。
 */
object AutoCheck {

    private const val PREFS = "zsts_update"
    private const val KEY_IGNORED = "ignored"

    /** 进主界面后等这么久再查：让自动登录和首屏都先跑完。 */
    private const val DELAY_MS = 2600L

    /** 同一个进程只查一次；recreate / 切页 / 回前台都不重置。 */
    @Volatile
    private var started = false

    /**
     * 在 `MainActivity.onCreate` 里调一次即可。
     * 只认第一次调用，后面的调用直接返回。
     */
    @JvmStatic
    fun maybeCheck(host: Activity?) {
        if (host == null) return
        synchronized(AutoCheck::class.java) {
            if (started) return
            started = true
        }
        if (host.isFinishing || host.isDestroyed) return
        if (!online(host)) return

        val app = host.applicationContext
        val main = Handler(Looper.getMainLooper())
        main.postDelayed({
            if (host.isFinishing || host.isDestroyed) return@postDelayed
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val r = Updater.check(versionName(app))
                val rel = r.release
                if (r.hasUpdate && rel != null && !isIgnored(app, rel.version)) {
                    main.post {
                        if (host.isFinishing || host.isDestroyed) return@post
                        // 用户正好开着别的弹层（比如刚点了「检查更新」）：别叠两层，
                        // 但也不能白查 —— 等它关掉再提醒。
                        showWhenFree(host, rel, main, 0)
                    }
                }
            }, "update-autocheck").start()
        }, DELAY_MS)
    }

    /**
     * 等当前弹层关掉再提醒。最多重试 20 次（约 30 秒），再久就不打扰了 ——
     * 用户已经在专心干别的，下次启动还会再查一遍。
     */
    private fun showWhenFree(
        host: Activity,
        rel: Updater.Release,
        main: Handler,
        attempt: Int
    ) {
        if (host.isFinishing || host.isDestroyed) return
        if (Sheet.anyShowing() && attempt < 20) {
            main.postDelayed({ showWhenFree(host, rel, main, attempt + 1) }, 1500L)
            return
        }
        if (Sheet.anyShowing()) return
        UpdatePrompt.show(host, rel)
    }

    /** 用户点过「忽略此版本」：记下来，等更高版本再提醒。 */
    @JvmStatic
    fun ignore(context: Context, version: String) {
        if (version.isEmpty()) return
        prefs(context).edit().putString(KEY_IGNORED, version).apply()
    }

    /** 被忽略的是哪个版本（空 = 没忽略过）。给「我的」页和调试用。 */
    @JvmStatic
    fun ignoredVersion(context: Context): String {
        return prefs(context).getString(KEY_IGNORED, "") ?: ""
    }

    /**
     * 只忽略「同一个或更低」的版本：用户忽略 4.0.7 之后，4.0.8 照样要提醒。
     * 用版本比较而不是字符串相等，是为了兜住「先忽略 4.0.8、线上又回滚到 4.0.7」这类边角。
     */
    private fun isIgnored(context: Context, version: String): Boolean {
        val ig = ignoredVersion(context)
        if (ig.isEmpty()) return false
        return Updater.compare(version, ig) <= 0
    }

    /** 有没有可用的网络（Wi-Fi / 移动数据 / 以太网都算）。 */
    private fun online(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(n) ?: return false
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            // 拿不到网络状态时宁可查 —— 查失败本身是静默的，不会打扰用户
            true
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun versionName(context: Context): String {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
