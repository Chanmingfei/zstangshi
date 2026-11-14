package com.tstc.zstangshi

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Insets
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.tstc.zstangshi.ui.BackGestures
import com.tstc.zstangshi.ui.HeaderBar
import com.tstc.zstangshi.ui.PageSlide

/**
 * 内置浏览器（教务 / 学工那些没有原生接口的页面）。
 *
 * 为什么不跳系统浏览器：教务系统要登录态，跳到浏览器等于换了一个会话，
 * 用户还得再登一次；这里和 App 共享 Cookie，登录过就是登录状态。
 *
 * 也顺手接管了返回键：能后退就后退，退到头才关页面 —— 比直接把用户弹回桌面友好。
 */
class WebViewActivity : BaseActivity() {

    private var web: WebView? = null
    private var bar: ProgressBar? = null

    /** 整页的圆角卡片进出场（见 ui/PageSlide）。 */
    private var slide: PageSlide? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val url = intent?.getStringExtra(EXTRA_URL)
        val title = intent?.getStringExtra(EXTRA_TITLE)
        if (url.isNullOrEmpty()) {
            finish()
            return
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pageColor())

        // ── 顶栏：统一 HeaderBar，返回箭头与标题对齐 ──
        val head = HeaderBar.create(
            this,
            if (title.isNullOrEmpty()) "教务系统" else title
        ) { onBackPressed() }
        head.addView(
            HeaderBar.action(this, "刷新") { web?.reload() }
        )
        root.addView(head)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar!!.max = 100
        bar!!.visibility = View.GONE
        root.addView(
            bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2f)
            )
        )

        web = WebView(this)
        val s: WebSettings = web!!.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.userAgentString = s.userAgentString + " ZSTS/3.0"

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        // 具名内部类而不是匿名类：d8 遇到「匿名类 + @Override 框架方法」会内部报错
        web!!.webViewClient = PlainClient(this)
        web!!.webChromeClient = ProgressClient(bar!!)

        root.addView(
            web, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)
        applyInsets(root)
        window.statusBarColor = Color.TRANSPARENT

        // 整页圆角卡片进场：与系统预测性返回动画同一个圆角半径
        slide = PageSlide(this, pageColor())
        slide!!.enter()

        backRoot = root
        syncBack()
        web!!.loadUrl(url)
    }

    // ---------- 预测性返回 ----------
    //
    // 分两种情况，两者都用 framework 的 OnBackInvokedCallback：
    //   · 网页还能后退 → 网页自己跟手右移，松手 goBack；
    //   · 网页已到栈底（要真正关掉这个页面）→ 把返回交给 PageSlide，整张圆角卡片
    //     跟手滑出、松手 finish，和从一级页进这个页面的动画是同一套。

    private var backToken: Any? = null
    private var backRoot: View? = null

    /** 有历史时接管给网页，退到栈底再交给整页滑动出场。 */
    fun syncBack() {
        if (!BackGestures.supported()) return
        val want = web != null && web!!.canGoBack()
        try {
            if (want && backToken == null) {
                // 网页要自己用返回键，整页出场先让开
                slide?.detachBack()
                backToken = BackGestures.install(this, BackHandler(this))
            } else if (!want && backToken != null) {
                BackGestures.uninstall(this, backToken)
                backToken = null
            }
            if (!want) slide?.attachBack()
        } catch (ignored: Throwable) {
        }
    }

    fun backStart() {
        val root = backRoot ?: return
        root.animate().cancel()
        root.translationX = 0f
        root.alpha = 1f
    }

    /** 跟手：整页随手指右移（纯位移，不改变不透明度）。 */
    fun backProgress(p: Float) {
        val root = backRoot ?: return
        var w = root.width
        if (w <= 0) w = resources.displayMetrics.widthPixels
        root.translationX = p * w * 0.3f
    }

    fun backCommit() {
        val root = backRoot
        if (root == null) {
            if (web != null && web!!.canGoBack()) web!!.goBack()
            return
        }
        // 能注册到这里，说明网页一定有历史：退回上一屏，页面滑回原位
        root.animate().cancel()
        if (web != null && web!!.canGoBack()) web!!.goBack()
        root.animate().translationX(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun backCancel() {
        val root = backRoot ?: return
        root.animate().cancel()
        root.animate().translationX(0f)
            .setDuration(190)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 静态嵌套类（d8 不允许非静态内部类）。 */
    private class BackHandler(private val host: WebViewActivity) : BackGestures.Handler {
        override fun onStart() {
            host.backStart()
        }

        override fun onProgress(progress: Float) {
            host.backProgress(progress)
        }

        override fun onCommit() {
            host.backCommit()
        }

        override fun onCancel() {
            host.backCancel()
        }
    }

    private fun applyInsets(root: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars: Insets = insets.getInsets(WindowInsets.Type.systemBars())
                // 窗口是半透明的，adjustResize 不一定可靠：这里自己把输入法高度让出来，
                // 免得页面里的搜索框被键盘盖住
                val ime: Insets = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom))
                insets
            }
        } else {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            root.setPadding(0, if (id > 0) resources.getDimensionPixelSize(id) else 0, 0, 0)
        }
    }

    override fun onBackPressed() {
        if (web != null && web!!.canGoBack()) {
            web!!.goBack()
            return
        }
        // 13 起由系统的预测性返回负责退场，别叠我们自己的滑动
        if (slide != null && !BackGestures.supported()) {
            slide!!.finish()
            return
        }
        super.onBackPressed()
    }

    /**
     * 主框架加载失败时，https 自动换 http 再试一次。
     *
     * 唐师的教务在移动数据下 443 不通、只有 80 —— 旧版就是靠这个探测活下来的，
     * 这里保留：不弹错误页，静默降级。
     */
    private class PlainClient(private val host: WebViewActivity) : WebViewClient() {
        private var retried = false

        /** 每翻一页都重新判断「还能不能后退」，手势接管与否跟着变。 */
        override fun doUpdateVisitedHistory(v: WebView, u: String?, isReload: Boolean) {
            host.syncBack()
        }

        override fun shouldOverrideUrlLoading(v: WebView, u: String?): Boolean {
            return false
        }

        override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (!req.isForMainFrame || retried) return
            val u = req.url.toString()
            if (!u.startsWith("https://")) return
            retried = true
            v.loadUrl("http://" + u.substring("https://".length))
        }
    }

    private class ProgressClient(private val bar: ProgressBar) : WebChromeClient() {
        override fun onProgressChanged(v: WebView, p: Int) {
            bar.visibility = if (p >= 100) View.GONE else View.VISIBLE
            bar.progress = p
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        @JvmStatic
        fun open(host: Activity, url: String, title: String) {
            val i = Intent(host, WebViewActivity::class.java)
            i.putExtra(EXTRA_URL, url)
            i.putExtra(EXTRA_TITLE, title)
            host.startActivity(i)
        }
    }
}
