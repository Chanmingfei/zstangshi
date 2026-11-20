package com.tstc.zstangshi.ui

import android.app.Activity
import android.app.DownloadManager
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

import com.tstc.zstangshi.R
import com.tstc.zstangshi.WebViewActivity
import com.tstc.zstangshi.update.Updater

/**
 * 「我的 → 关于 → 检查更新」的底部弹层。
 *
 * 一个弹层走完四步：检查 → 有新版本（版本号 / 日期 / 大小 / 更新说明）→ 下载（带进度）
 * → 拉起系统安装器。和 App 里其它弹层共用 [Sheet] 与按钮样式，不另起一套观感。
 *
 * 下载交给系统 DownloadManager，弹层关掉也继续下；重新点「检查更新」时
 * 发现已经有下好的包，会直接给「安装」按钮，不让用户白下一遍。
 */
class UpdateSheet private constructor(
    private val host: Activity,
    private val onLabel: ((String, Boolean) -> Unit)?,
    /** 自动检查已经拿到了版本：直接展示，不再查一次（省一次网络往返）。 */
    private val preset: Updater.Release? = null
) {

    companion object {
        @JvmStatic
        fun show(host: Activity?, onLabel: ((String, Boolean) -> Unit)?) {
            if (host == null || host.isFinishing) return
            UpdateSheet(host, onLabel).open()
        }

        /** 从「发现新版本」的提醒里进来：直接停在更新说明页。 */
        @JvmStatic
        fun showFor(host: Activity?, onLabel: ((String, Boolean) -> Unit)?, release: Updater.Release?) {
            if (host == null || host.isFinishing || release == null) return
            UpdateSheet(host, onLabel, release).open()
        }

        /**
         * Release 正文是 Markdown（带标题、表格、图片、链接）。这里只留给人看的文字：
         * 去掉 HTML、图片、链接地址和各种前缀符号，连续空行压成一行。
         * 不追求完整渲染 —— 更新说明本来就是扫一眼的东西。
         */
        @JvmStatic
        fun plainNotes(src: String): String {
            if (src.isBlank()) return "这个版本没有写更新说明。"
            var t = src.replace("\r\n", "\n").replace("\r", "\n")
            t = t.replace(Regex("<!--[\\s\\S]*?-->"), "")
            t = t.replace(Regex("<[^>]+>"), "")
            t = t.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
            t = t.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            t = t.replace(Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE), "")
            t = t.replace(Regex("^\\s*>\\s?", RegexOption.MULTILINE), "")
            t = t.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "· ")
            t = t.replace(Regex("\\*\\*|__"), "")
            t = t.replace(Regex("`+"), "")
            t = t.replace(Regex("\n{3,}"), "\n\n")
            return t.trim()
        }
    }

    private val dp = Math.round(host.resources.displayMetrics.density)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var handle: Sheet.Handle
    private val mid = LinearLayout(host)

    private lateinit var primary: TextView
    private lateinit var secondary: TextView

    /** 下载进度那两件控件只建一次，轮询时只改数字，不整块重建。 */
    private var bar: ProgressBar? = null
    private var barText: TextView? = null

    private var currentVersion = ""
    private var release: Updater.Release? = null
    private var downloadId = -1L
    private var polling = false

    private fun open() {
        currentVersion = versionName()

        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(d(20), 0, d(20), d(14))

        val title = TextView(host)
        title.text = "软件更新"
        title.setTextSize(17f)
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(host.getColor(R.color.text_primary))
        title.setPadding(d(2), d(4), 0, d(3))
        box.addView(title)

        val sub = TextView(host)
        sub.text = "当前版本 " + currentVersion
        sub.setTextSize(12f)
        sub.setTextColor(host.getColor(R.color.text_tertiary))
        sub.setPadding(d(2), 0, 0, d(10))
        box.addView(sub)

        mid.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(host)
        scroll.isFillViewport = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(mid, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        primary = action("立即更新", true, 0)
        box.addView(primary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(44)))

        secondary = action("稍后", false, d(8))
        box.addView(secondary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(44)))
        // 次要按钮的 topMargin 在 action() 里已经写好，这里再补一次只为可读
        (secondary.layoutParams as LinearLayout.LayoutParams).topMargin = d(8)

        handle = Sheet.show(host, box, d(396))
        setPrimary("立即更新", false) { }
        setSecondary("稍后") { handle.dismiss() }
        val p = preset
        if (p != null) {
            // 自动检查已经把结果拿到了，直接渲染；resumeOrOffer 还会认领之前下好的包
            release = p
            onLabel?.invoke("v" + p.version + " 可更新", true)
            resumeOrOffer(p)
        } else {
            startCheck()
        }
    }

    // ──────────────────────────── 检查 ────────────────────────────

    private fun startCheck() {
        renderChecking()
        Thread({
            val r = Updater.check(currentVersion)
            main.post { onChecked(r) }
        }, "update-check").start()
    }

    private fun onChecked(r: Updater.Result) {
        if (!handle.isShowing()) return
        val err = r.error
        if (err != null) {
            onLabel?.invoke("检查失败", false)
            renderError(err)
            return
        }
        val rel = r.release
        if (r.hasUpdate && rel != null) {
            release = rel
            onLabel?.invoke("v" + rel.version + " 可更新", true)
            resumeOrOffer(rel)
        } else {
            onLabel?.invoke("已是最新", false)
            renderLatest()
        }
    }

    /**
     * 上次可能已经把这个版本下好了（弹层关掉、甚至进程重启）。
     * 下载 id 记在 preference 里，这里先认领回来：下好了直接给「安装」，
     * 下载中就直接续上进度，别让用户白下一遍。
     */
    private fun resumeOrOffer(r: Updater.Release) {
        val id = prefs().getLong("id", -1L)
        val ver = prefs().getString("ver", "")
        if (id <= 0 || ver != r.version) {
            renderUpdate(r, false)
            return
        }
        val p = Updater.query(host, id)
        when {
            p == null -> renderUpdate(r, false)
            p.state == DownloadManager.STATUS_SUCCESSFUL -> {
                downloadId = id
                renderUpdate(r, true)
            }
            p.state == DownloadManager.STATUS_RUNNING || p.state == DownloadManager.STATUS_PENDING -> {
                downloadId = id
                renderDownloading(r)
                paintProgress(p)
                poll()
            }
            else -> renderUpdate(r, false)
        }
    }

    private fun renderChecking() {
        mid.removeAllViews()
        setPrimary("检查中…", false) { }
        setSecondary("取消") { handle.dismiss() }

        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(d(2), d(24), d(2), d(24))

        val pb = ProgressBar(host)
        pb.isIndeterminate = true
        row.addView(pb, LinearLayout.LayoutParams(d(22), d(22)))

        val t = TextView(host)
        t.text = "正在连接 GitHub…"
        t.setTextSize(13.5f)
        t.setTextColor(host.getColor(R.color.text_secondary))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = d(10)
        row.addView(t, lp)
        mid.addView(row)
    }

    private fun renderLatest() {
        mid.removeAllViews()

        val t = TextView(host)
        t.text = "已是最新版本"
        t.setTextSize(17f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(host.getColor(R.color.text_primary))
        t.setPadding(d(2), d(20), 0, d(4))
        mid.addView(t)

        val s = TextView(host)
        s.text = "当前 " + currentVersion + "，不用更新。新版本发布后会在这里提示。"
        s.setTextSize(13f)
        s.setTextColor(host.getColor(R.color.text_secondary))
        s.setLineSpacing(d(3).toFloat(), 1f)
        s.setPadding(d(2), 0, d(2), d(10))
        mid.addView(s)

        setPrimary("重新检查", true) { startCheck() }
        setSecondary("知道了") { handle.dismiss() }
    }

    private fun renderUpdate(r: Updater.Release, downloaded: Boolean) {
        mid.removeAllViews()

        val ver = TextView(host)
        ver.text = "v" + r.version
        ver.setTextSize(22f)
        ver.typeface = Typeface.DEFAULT_BOLD
        ver.setTextColor(host.getColor(R.color.brand))
        ver.setPadding(d(2), d(6), 0, d(2))
        mid.addView(ver)

        val meta = TextView(host)
        val bits = ArrayList<String>()
        val date = Updater.dateText(r.publishedAt)
        if (date.isNotEmpty()) bits.add(date)
        val size = Updater.sizeText(r.size)
        if (size.isNotEmpty()) bits.add(size)
        meta.text = bits.joinToString(" · ")
        meta.setTextSize(12f)
        meta.setTextColor(host.getColor(R.color.text_tertiary))
        meta.setPadding(d(2), 0, 0, d(8))
        mid.addView(meta)

        val divider = View(host)
        divider.setBackgroundColor(host.getColor(R.color.border_subtle))
        val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp / 3))
        dlp.bottomMargin = d(8)
        mid.addView(divider, dlp)

        val notes = TextView(host)
        notes.text = plainNotes(r.notes)
        notes.setTextSize(12.5f)
        notes.setTextColor(host.getColor(R.color.text_secondary))
        notes.setLineSpacing(d(3).toFloat(), 1f)
        notes.setPadding(d(2), 0, d(2), d(8))
        mid.addView(notes)

        if (downloaded) {
            setPrimary("安装更新", true) { doInstall() }
            setSecondary("稍后") { handle.dismiss() }
        } else {
            setPrimary("立即更新", true) { startDownload() }
            setSecondary("稍后") { handle.dismiss() }
        }
    }

    private fun renderError(msg: String) {
        mid.removeAllViews()

        val t = TextView(host)
        t.text = "检查失败"
        t.setTextSize(16f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(host.getColor(R.color.text_primary))
        t.setPadding(d(2), d(20), 0, d(4))
        mid.addView(t)

        val s = TextView(host)
        s.text = msg
        s.setTextSize(13f)
        s.setTextColor(host.getColor(R.color.text_secondary))
        s.setLineSpacing(d(3).toFloat(), 1f)
        s.setPadding(d(2), 0, d(2), d(10))
        mid.addView(s)

        setPrimary("重试", true) { startCheck() }
        // 连不上更新接口时还有一条路：直接去发布页手动下载（走 Gitee，国内能开）
        setSecondary("去发布页") {
            WebViewActivity.open(host, Updater.RELEASES_PAGE, "版本发布页")
        }
    }

    // ──────────────────────────── 下载 ────────────────────────────

    private fun startDownload() {
        val r = release ?: return
        // 先要系统里的「安装未知应用」权限：先下再好几百 MB 却装不上，比提前问一句更难受
        if (!Updater.canInstall(host)) {
            guideInstallPermission()
            return
        }
        if (r.urls.isEmpty()) {
            renderError("这个版本没有挂安装包，请到发布页下载")
            return
        }
        renderPicking(r)
        // 几秒的测速放到后台：Gitee / GitHub / 代理三条地址谁先回就用谁
        Thread({
            val url = Updater.pickFastest(r.urls)
            main.post {
                if (!handle.isShowing()) return@post
                if (url == null) {
                    renderError("几个下载源都连不上，请稍后重试或到发布页下载")
                } else {
                    enqueue(r, url)
                }
            }
        }, "update-pick").start()
    }

    private fun enqueue(r: Updater.Release, url: String) {
        val id = Updater.startDownload(host, r, url)
        if (id < 0) {
            renderError("下载没能启动，请稍后重试")
            return
        }
        downloadId = id
        prefs().edit().putLong("id", id).putString("ver", r.version).apply()
        renderDownloading(r)
        poll()
    }

    private fun renderPicking(r: Updater.Release) {
        mid.removeAllViews()

        val t = TextView(host)
        t.text = "正在选择下载线路"
        t.setTextSize(16f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(host.getColor(R.color.text_primary))
        t.setPadding(d(2), d(20), 0, d(10))
        mid.addView(t)

        val pb = ProgressBar(host)
        pb.isIndeterminate = true
        mid.addView(pb, LinearLayout.LayoutParams(d(24), d(24)))

        val s = TextView(host)
        s.text = "会自动挑 Gitee 与 GitHub 里响应最快的一条，不用你选。"
        s.setTextSize(12f)
        s.setTextColor(host.getColor(R.color.text_tertiary))
        s.setPadding(d(2), d(10), d(2), d(8))
        mid.addView(s)

        setPrimary("请稍候…", false) { }
        setSecondary("取消") { handle.dismiss() }
    }

    private fun renderDownloading(r: Updater.Release) {
        mid.removeAllViews()

        val t = TextView(host)
        t.text = "正在下载 v" + r.version
        t.setTextSize(16f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(host.getColor(R.color.text_primary))
        t.setPadding(d(2), d(20), 0, d(10))
        mid.addView(t)

        val pb = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal)
        pb.max = 1000
        pb.progress = 0
        mid.addView(pb, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(6)))
        bar = pb

        val line = TextView(host)
        line.text = "0%"
        line.setTextSize(12.5f)
        line.setTextColor(host.getColor(R.color.text_secondary))
        line.setPadding(d(2), d(8), 0, d(8))
        mid.addView(line)
        barText = line

        val hint = TextView(host)
        hint.text = "下载在后台进行，关掉这个弹层也不会中断；完成后会直接打开安装程序。"
        hint.setTextSize(12f)
        hint.setTextColor(host.getColor(R.color.text_tertiary))
        hint.setLineSpacing(d(3).toFloat(), 1f)
        hint.setPadding(d(2), 0, d(2), d(8))
        mid.addView(hint)

        setPrimary("后台下载", true) { handle.dismiss() }
        setSecondary("取消下载") {
            if (downloadId > 0) Updater.cancel(host, downloadId)
            downloadId = -1
            prefs().edit().remove("id").remove("ver").apply()
            handle.dismiss()
        }
    }

    private fun poll() {
        if (polling) return
        polling = true
        val tick = object : Runnable {
            override fun run() {
                if (!handle.isShowing()) {
                    polling = false
                    return
                }
                val p = Updater.query(host, downloadId)
                if (p == null) {
                    main.postDelayed(this, 800)
                    return
                }
                when (p.state) {
                    DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                        paintProgress(p)
                        main.postDelayed(this, 400)
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        polling = false
                        paintProgress(p)
                        onLabel?.invoke("下载完成", true)
                        doInstall()
                    }
                    DownloadManager.STATUS_FAILED -> {
                        polling = false
                        prefs().edit().remove("id").remove("ver").apply()
                        renderError("下载失败，请检查网络后重试")
                    }
                    else -> main.postDelayed(this, 600)
                }
            }
        }
        main.postDelayed(tick, 300)
    }

    private fun paintProgress(p: Updater.Progress) {
        val total = if (p.total > 0) p.total else (release?.size ?: 0)
        val pct = if (total > 0) Math.min(100, Math.round(p.downloaded * 100f / total)) else 0
        bar?.progress = pct * 10
        val sb = StringBuilder()
        sb.append(pct).append('%')
        if (total > 0) sb.append(" · ").append(Updater.sizeText(p.downloaded))
            .append(" / ").append(Updater.sizeText(total))
        barText?.text = sb.toString()
    }

    // ──────────────────────────── 安装 ────────────────────────────

    private fun doInstall() {
        if (!Updater.canInstall(host)) {
            guideInstallPermission()
            return
        }
        val uri = Updater.downloadedUri(host, downloadId)
        if (uri == null) {
            renderError("安装包已经不在本地了，请重新下载")
            return
        }
        if (Updater.install(host, uri)) {
            handle.dismiss()
        } else {
            renderError("打不开系统安装程序，请到「我的 → 关于 → 项目地址」手动下载")
        }
    }

    private fun guideInstallPermission() {
        ConfirmDialog.show(host, "还要允许安装应用",
            "系统里得先允许「掌上唐师」安装应用，装更新时才不会卡住。\n\n" +
                    "点「去开启」打开开关，回到这里再点一次即可。",
            "稍后", "去开启") {
            if (!Updater.openInstallSettings(host)) {
                ConfirmDialog.alert(host, "打不开系统设置",
                    "请手动进入「设置 → 应用 → 掌上唐师 → 安装未知应用」，允许后再试")
            }
        }
    }

    // ──────────────────────────── 小工具 ────────────────────────────

    private fun setPrimary(text: String, enabled: Boolean, onClick: () -> Unit) {
        primary.text = text
        primary.isEnabled = enabled
        primary.alpha = if (enabled) 1f else 0.45f
        primary.setOnClickListener { if (enabled) onClick() }
    }

    private fun setSecondary(text: String, onClick: () -> Unit) {
        secondary.text = text
        secondary.setOnClickListener { onClick() }
    }

    private fun action(text: String, primaryStyle: Boolean, topMargin: Int): TextView {
        val v = TextView(host)
        v.text = text
        v.setTextSize(14f)
        v.gravity = Gravity.CENTER
        if (primaryStyle) {
            v.typeface = Typeface.DEFAULT_BOLD
            v.setTextColor(0xFFFFFFFF.toInt())
            v.background = host.getDrawable(R.drawable.btn_primary)
        } else {
            v.setTextColor(host.getColor(R.color.text_secondary))
            v.background = host.getDrawable(R.drawable.btn_outline)
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(44))
        lp.topMargin = topMargin
        v.layoutParams = lp
        if (primaryStyle) v.isClickable = true
        return v
    }

    private fun prefs() = host.getSharedPreferences("zsts_update", android.content.Context.MODE_PRIVATE)

    /** dp → px。 */
    private fun d(v: Int): Int {
        return Math.round(v * host.resources.displayMetrics.density)
    }

    private fun versionName(): String {
        return try {
            val pi = host.packageManager.getPackageInfo(host.packageName, 0)
            pi.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
