package com.tstc.zstangshi

import android.graphics.Color
import android.graphics.Insets
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.pages.GradesPage
import com.tstc.zstangshi.pages.HomePage
import com.tstc.zstangshi.pages.MinePage
import com.tstc.zstangshi.pages.TimetablePage
import com.tstc.zstangshi.notif.FocusController
import com.tstc.zstangshi.ui.BackGestures
import com.tstc.zstangshi.ui.CapsuleDock
import com.tstc.zstangshi.ui.PageDeck
import com.tstc.zstangshi.ui.PullRefreshLayout
import com.tstc.zstangshi.update.AutoCheck

/**
 * 原生版主界面：一条悬浮胶囊 Dock + 四个页面。
 *
 * 结构（对应旧版 #app / #app-main / #tabbar 三层）：
 *   root(FrameLayout)
 *     ├ content   —— 页面容器，底部留出 Dock 的高度（内容能从胶囊下面穿过去）
 *     └ dockBox   —— 底部留白 + 胶囊，**只接住胶囊上的触摸**，左右/上下留白
 *                    全部穿透给页面（旧版这里是 bug：整条 90px 都吃掉手势）
 *
 * 页面用四个 View 缓存，切 tab 只切可见性，不重建 —— 课表页有滚动位置和
 * 选中周次这类状态，重建一次就要重新算，切回来会跳回第一周。
 */
class MainActivity : BaseActivity() {

    private lateinit var store: Store
    private lateinit var dock: CapsuleDock
    private lateinit var content: PageDeck

    private var homePage: HomePage? = null
    private var timetablePage: TimetablePage? = null
    private var gradesPage: GradesPage? = null
    private var minePage: MinePage? = null

    private var index = 0
    private var dockBand = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)

        // 课程焦点通知：每次进入 App 对齐一次（后台线程，避开主线程 IO）
        Thread {
            try {
                FocusController(applicationContext).reconcile()
            } catch (t: Throwable) {
            }
        }.start()

        val root = FrameLayout(this)
        root.setBackgroundColor(if (store.darkNow()) 0xFF0F1520.toInt() else getColor(R.color.bg_page))

        // 页面带：四个主页面横向排开，切 tab = 整条带平移（连续平移，途经中间页）
        content = PageDeck(this)
        content.id = View.generateViewId()
        content.setSlotWidth(resources.displayMetrics.widthPixels)
        root.addView(
            content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val dockBox = FrameLayout(this)
        val d = resources.displayMetrics.density
        dockBand = Math.round(12 * d + 64 * d + 14 * d)
        val dockLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dockLp.gravity = Gravity.BOTTOM
        root.addView(dockBox, dockLp)

        dock = CapsuleDock(this)
        val dlp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.round(64 * d)
        )
        dlp.leftMargin = Math.round(22 * d)
        dlp.rightMargin = Math.round(22 * d)
        dlp.topMargin = Math.round(12 * d)
        dlp.bottomMargin = Math.round(14 * d)
        dockBox.addView(dock, dlp)

        dock.setOnTabSelectedListener { i -> show(i) }
        dock.setOnTabReselectedListener { i -> onReselect(i) }

        setContentView(root)
        applyInsets(root, dockBox)

        if (fadeInNext) {
            fadeInNext = false
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        // 内容区不留白：列表要从胶囊「底下」穿过去，胶囊左右的空隙才看得到内容
        // （留白留给各页自己的 ScrollView，clipToPadding=false 之后再滚，最后一项也不会被挡）

        if (b != null) index = b.getInt(STATE_TAB, 0)
        show(index)

        // 启动时自动检查一次更新：延后 2.6s、后台低优先级线程，没网或连不上都静默；
        // 有新版本才弹提醒（可「忽略此版本」）。同一个进程只跑一次。
        AutoCheck.maybeCheck(this)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(STATE_TAB, index)
    }

    /** 宽度变了（横竖屏/分屏）：槽位宽跟着变，否则整条带会错位。 */
    override fun onConfigurationChanged(cfg: android.content.res.Configuration) {
        super.onConfigurationChanged(cfg)
        val w = resources.displayMetrics.widthPixels
        backActive = false
        content.cancelAnimation()
        content.setSlotWidth(w)
        content.jumpTo(index.toFloat())
    }

    /** 从登录页返回时把「我的」刷一遍（只有真走了登录流程才重建，普通切页不刷）。 */
    override fun onResume() {
        super.onResume()
        minePage?.onReturned()
    }

    /**
     * 通知权限授权结果（对应 MinePage 的 REQ_NOTIFY）。
     *
     * 只负责把结果交回「我的」页：那里会重新检查**普通通知**与**焦点通知（AppOp）**
     * 两项权限，齐了才真正打开开关；缺焦点权限时会引导去系统设置页。
     * 权限链逻辑放在 MinePage 里，避免这里和界面各自判断一套。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9001) {
            minePage?.onReturned()
        }
    }

    /** 给首页宫格用：点「我的课表 / 成绩」直接切到对应底栏项。 */
    fun switchTab(i: Int) {
        if (i < 0 || i > 3) return
        show(i)
    }

    /** 状态栏顶部内边距 + 手势条底部内边距（老的 fitsSystemWindows 在浮层上不生效）。 */
    private fun applyInsets(root: View, dockBox: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, 0)
                val lp = dockBox.layoutParams as FrameLayout.LayoutParams
                lp.bottomMargin = bars.bottom
                dockBox.layoutParams = lp

                insets
            }
        } else {
            val status = statusBarHeight()
            root.setPadding(0, status, 0, 0)
        }
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun onReselect(i: Int) {
        if (i == 0 && homePage != null) homePage!!.reload()
        if (i == 1 && timetablePage != null) timetablePage!!.jumpToToday()
    }

    private fun show(i: Int) {
        show(i, true)
    }

    /**
     * 切页：把「页面带」整条平移 -index*宽。
     *
     * 页面按底栏顺序横向排开（首页|课表|成绩|我的），所以从首页点到「我的」时，
     * 课表、成绩会真的依次掠过屏幕，而不是两页对着擦。底栏胶囊不动，也不接管
     * 任何左右滑动手势（课表页自己要用横滑翻周次，主页面加手势会打架）。
     */
    private fun show(i: Int, animate: Boolean) {
        val from = index

        // 切深色模式会 recreate()，onSaveInstanceState 靠这个 index 回到原页
        index = i
        if (dock.selectedTab() != i) dock.setSelected(i, true)

        val page = pageAt(i)
        applyScrollPadding(page)
        content.put(i, page)

        // 把起点到终点之间的槽位都补出来：否则跨格平移时中间是空白，
        // 看不到「课表、成绩依次掠过」的连续感（页面本身仍是懒创建的）。
        val lo = Math.min(from, i)
        val hi = Math.max(from, i)
        for (k in lo..hi) {
            if (k == i) continue
            val mid = pageAt(k)
            applyScrollPadding(mid)
            content.put(k, mid)
        }

        // 时长按「基准 + 每多一格小幅追加」算，不随距离等比放大：
        // 首页→课表 290ms、首页→成绩 335ms、首页→我的 380ms，
        // 三者差不到 0.1s，观感上就是同一套节奏；又不会因为完全固定时长
        // 让跨三格的那次快到看不清中间两页。
        // 用浮点剩余距离是为了接住「返回手势松手时已经拖了一半」的情况。
        val remain = Math.abs(i - content.position())
        var dur: Long = (PAGE_SLIDE_MS + Math.round(PAGE_SLIDE_STEP_MS * Math.max(0f, remain - 1f))).toLong()
        dur = Math.min(PAGE_SLIDE_MAX_MS.toLong(), Math.max(PAGE_SLIDE_MIN_MS.toLong(), dur))

        content.cancelAnimation()
        if (animate && remain > 0.01f && !isFinishing && !isDestroyed) {
            content.animateTo(i.toFloat(), dur)
        } else {
            content.jumpTo(i.toFloat())
        }

        (page as? HomePage)?.reload()
        (page as? TimetablePage)?.onShown()
        (page as? GradesPage)?.reload()
        (page as? MinePage)?.onShown()

        syncBackGesture()
    }

    /**
     * 让卡片的滚动区延伸到胶囊底下：padding 留在 ScrollView 上、clipToPadding 关掉，
     * 静止时最后一项照样在胶囊上方，动起来又能从胶囊下面滑过去。
     */
    private fun applyScrollPadding(page: View) {
        if (page is ScrollView) {
            page.clipToPadding = false
            page.setPadding(
                page.paddingLeft, page.paddingTop, page.paddingRight,
                dockBand + safeBottom()
            )
        } else if (page is PullRefreshLayout) {
            val sv = page.scrollView()
            sv.clipToPadding = false
            sv.setPadding(
                sv.paddingLeft, sv.paddingTop, sv.paddingRight,
                dockBand + safeBottom()
            )
        } else {
            // 课表网格这种不滚动的页面，只能自己让开胶囊，否则 11、12 节永远被压住
            page.setPadding(
                page.paddingLeft, page.paddingTop, page.paddingRight,
                dockBand
            )
        }
    }

    private fun safeBottom(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = window.decorView.rootWindowInsets
            if (insets != null) return insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        }
        return 0
    }

    private fun pageAt(i: Int): View {
        when (i) {
            1 -> {
                if (timetablePage == null) timetablePage = TimetablePage(this, store)
                return timetablePage!!
            }
            2 -> {
                if (gradesPage == null) gradesPage = GradesPage(this, store)
                return gradesPage!!
            }
            3 -> {
                if (minePage == null) minePage = MinePage(this, store) { onTimetableChanged() }
                return minePage!!
            }
            else -> {
                if (homePage == null) homePage = HomePage(this, store)
                return homePage!!
            }
        }
    }

    /** 课表数据变了（我的页同步 / 首页下拉刷新）就让相关页面重读本地数据。 */
    fun onTimetableChanged() {
        homePage?.reload()
        timetablePage?.reload()
        gradesPage?.reload()
    }

    override fun onBackPressed() {
        // 首页按返回 = 回桌面，不再有「退回登录页」这种事（旧版的返回栈 bug）
        if (index != 0) {
            show(0)
            return
        }
        super.onBackPressed()
    }

    // ---------- 预测性返回（Android 13+ / 跟手动画 Android 14+） ----------
    //
    // 走官方 API：manifest 打开 enableOnBackInvokedCallback 之后，系统不再分发
    // onBackPressed，而是把返回手势交给 OnBackInvokedDispatcher；API 34 起换成
    // OnBackAnimationCallback，就能在松手之前拿到 progress 做跟手动画。

    /** 注册中的返回回调；null = 现在没接管，交给系统。 */
    private var backToken: Any? = null

    /** 手势开始时页面带的位置（取消要回到这里）。 */
    private var backFromX = 0f
    private var backActive = false

    /**
     * 只有不在首页时才接管返回：那时返回 = 回首页。回到首页就注销，让系统做
     * 标准的退出动画（back-to-home），自己不掺和。
     */
    private fun syncBackGesture() {
        if (!BackGestures.supported()) return
        try {
            val want = index != 0 && !isFinishing && !isDestroyed
            if (want && backToken == null) {
                backToken = BackGestures.install(this, BackHandler(this))
            } else if (!want && backToken != null) {
                BackGestures.uninstall(this, backToken)
                backToken = null
            }
        } catch (ignored: Throwable) {
        }
    }

    fun backStart() {
        if (index == 0) return
        content.cancelAnimation()
        backFromX = content.position()
        backActive = true
    }

    /**
     * 跟手：progress 0→1 表示「从当前页退到首页」的完成度。
     * 整条页面带按比例往回走 —— 和切 tab 是同一套连续平移，不缩放、不淡出。
     */
    fun backProgress(p: Float) {
        if (!backActive) return
        val k = Math.max(0f, Math.min(1f, p))
        content.jumpTo(backFromX * (1f - k))
    }

    fun backCommit() {
        if (!backActive) {
            show(0, true)
            return
        }
        backActive = false
        // 从松手时的位置继续滑到 0（show 里就是 animate 当前值 → 目标值，不会跳）
        show(0, true)
    }

    fun backCancel() {
        if (!backActive) return
        backActive = false
        content.animateTo(backFromX, 200L)
    }

    /** 转给 Activity 的静态嵌套类：d8 不允许非静态内部类。 */
    private class BackHandler(private val host: MainActivity) : BackGestures.Handler {
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

    companion object {
        private const val STATE_TAB = "tab_index"

        /**
         * 切页基准时长：不管从哪一页点到哪一页都是这个时间，手感才统一。
         * 跨多格只按「每多一格 +45ms」略微加时、并封顶 400ms，
         * 否则从首页点「我的」会滑三倍久，和点「课表」明显不是一套节奏。
         */
        private const val PAGE_SLIDE_MS = 290
        private const val PAGE_SLIDE_STEP_MS = 45
        private const val PAGE_SLIDE_MAX_MS = 400

        /** 跟手松手后剩余距离很小时的下限，别缩到一闪。 */
        private const val PAGE_SLIDE_MIN_MS = 160

        /** 换浅色/深色要 recreate()，标记一下让新界面淡入，别硬切一下 */
        private var fadeInNext = false

        @JvmStatic
        fun beginThemeRestart() {
            fadeInNext = true
        }
    }
}
