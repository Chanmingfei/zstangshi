package com.tstc.zstangshi.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

private const val IN_MS = 300
private const val OUT_MS = 230
/** 滑出 28% 屏宽时圆角就长满了，再往外只是平移。 */
private const val ROUND_AT = 0.28f

/**
 * 二级页面的「圆角卡片」进出场：整页当成一张带投影的圆角矩形，从屏幕右侧滑进来。
 *
 * 为什么必须自己画：
 *   系统只在「返回」方向画圆角卡片（AOSP CrossActivityBackAnimation 用
 *   ScreenDecorationsUtils.getWindowCornerRadius() 取设备圆角，把正在关闭的窗口裁圆角再缩到
 *   90%），「进入」方向走的是窗口动画，framework 的窗口动画只有 alpha/translate/scale，
 *   没有圆角，也没有公开 API 能给应用窗口设圆角。于是同一个页面进来是直角、出去是圆角。
 *
 * 做法（纯 framework）：
 *   1. 主题 windowIsTranslucent=true + 窗口底色透明 —— 窗口面才有 alpha 通道，圆角之外
 *      透得出下面那一页（这也是系统做跨 Activity 卡片动画的前提）；
 *   2. 在 android.R.id.content 里插一层自己建的 FrameLayout 当「卡片」，把页面根视图挪进去。
 *      圆角靠 setClipToOutline + ViewOutlineProvider，投影靠 setElevation ——
 *      这两样都作用在普通 View 上才可靠，直接对 DecorView 用是无效的（上一版就是栽在这：
 *      页面既没有圆角也没有投影，看着就是一块没边界的矩形）。
 *   3. 卡片的位移 = p × 屏宽，圆角 = 系统圆角 × clamp(p / 0.28)，落位时圆角归 0，
 *      所以静止状态和以前逐像素一致，四角不会留缝。
 *
 * 系统窗口动画用 overrideActivityTransition(OPEN, 0, 0)（官方文档指定的、14 起替代
 * overridePendingTransition 的方式）关掉，否则系统动画会和这套位移叠在一起。
 */
class PageSlide(a: Activity, pageColor: Int) {

    private val act: Activity = a
    /** 真正的「卡片」：页面根视图的父亲，圆角、投影、位移都加在它身上。 */
    private val card: FrameLayout
    private val maxRadius: Float
    /** 卡片浮起的高度（px），也就是投影的强度。 */
    private val shadowPx: Float

    /** 0 = 完全在位；1 = 完全滑出屏幕右侧。整页的外观全部是它的函数。 */
    private var p = 1f
    private var radius = 0f
    private var anim: ValueAnimator? = null
    private var entered = false
    private var finishing = false
    /** 返回手势回调的登记凭据（BackGestures 的 uninstall 要用）。 */
    private var backToken: Any? = null

    init {
        val win = a.window
        // 窗口底色透明：圆角之外才透得出下面那一页
        win.setBackgroundDrawable(ColorDrawable(0))
        // 让 content 铺满整个窗口（含状态栏/导航栏那两条），卡片才顶得到屏幕四角。
        // 内容与系统栏之间的间距改由页面根视图自己的 inset padding 负责（各页已有 applyInsets）。
        fullBleed(win)

        val dens = a.resources.displayMetrics.density
        maxRadius = windowCornerRadius(a)

        card = FrameLayout(a)
        card.setBackgroundColor(pageColor)
        card.setOutlineProvider(CardOutline(this))
        card.setClipToOutline(true)
        // 投影：卡片浮起来，被移开的那一侧边缘才会像系统那样有层次
        shadowPx = 10 * dens
        card.setElevation(shadowPx)
        if (Build.VERSION.SDK_INT >= 28) {
            card.setOutlineSpotShadowColor(0x70000000)
            card.setOutlineAmbientShadowColor(0x3D000000)
        }
        wrapContent(win, card)

        // 系统窗口动画关掉（官方文档：14 起用 overrideActivityTransition 代替 overridePendingTransition）
        if (Build.VERSION.SDK_INT >= 34) {
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            a.overridePendingTransition(0, 0)
        }

        // 首帧之前就摆到屏幕外，别先闪一整页再滑入
        radius = maxRadius
        card.translationX = width().toFloat()
        card.invalidateOutline()
    }

    /** 进入动画：从右往左落到原位，圆角随手收掉。 */
    fun enter() {
        if (entered) return
        entered = true
        whenLaidOut(Enter(this))
    }

    /** 顶栏返回箭头 / 页面自己要关的时候走这个：滑出屏幕再 finish。 */
    fun finish() {
        if (finishing) return
        finishing = true
        stop()
        val ms = Math.max(120, (OUT_MS * Math.max(0.3f, 1f - p)).toInt())
        slideTo(1f, ms, Finisher(act))
    }

    /**
     * 接管「二级页 → 一级页」的预测性返回手势。
     *
     * 手势和进场共用同一个 p：手指拖多少，整张圆角卡片就往右移多少、圆角就长多少，
     * 松手滑完再 finish。
     */
    fun attachBack() {
        if (backToken != null || finishing || !BackGestures.supported()) return
        backToken = BackGestures.install(act, ExitHandler(this))
    }

    /** 交还返回手势（WebView 还有历史可退时，返回要归网页翻页用）。 */
    fun detachBack() {
        if (backToken == null) return
        BackGestures.uninstall(act, backToken)
        backToken = null
    }

    // ---------- 内部 ----------

    /**
     * 返回手势的页面内拦截器：返回 true 表示这次「返回」被页面自己消化掉了
     * （例如实时公交的线路详情里，从「全部车辆 / 单车跟随」退回站点卡），
     * 此时整页不位移、也不退出，只在松手时执行 [backAction]。
     *
     * 不拦截时一切照旧：手势拖多少、卡片走多少，松手滑出屏幕再 finish。
     */
    var backInterceptor: (() -> Boolean)? = null
    /** 被拦截的那次返回，真正要做的事（在松手时调用，和滑出屏幕同一时机）。 */
    var backAction: (() -> Unit)? = null

    /** 本次手势是否已被页面消化（消化掉的手势不参与位移/退出）。 */
    private var intercepted = false

    private fun gestureStart() {
        val i = backInterceptor
        intercepted = i != null && i()
        if (intercepted) return
        stop()
    }

    private fun gestureProgress(progress: Float) {
        if (intercepted) return
        apply(Math.max(0f, Math.min(1f, progress)))
    }

    private fun gestureCommit() {
        if (intercepted) {
            intercepted = false
            backAction?.invoke()
            return
        }
        if (finishing) return
        finishing = true
        stop()
        val ms = Math.max(110, (OUT_MS * Math.max(0.25f, 1f - p)).toInt())
        slideTo(1f, ms, Finisher(act))
    }

    private fun gestureCancel() {
        if (intercepted) {
            intercepted = false
            return
        }
        slideTo(0f, 200)
    }

    private fun width(): Int {
        val w = card.width
        if (w > 0) return w
        return act.resources.displayMetrics.widthPixels
    }

    /**
     * 唯一的外观函数：位移 = p × 屏宽，圆角 = maxRadius × clamp(p / ROUND_AT)。
     * 圆角每帧都 invalidateOutline 一次 —— 半径变化必须让 RenderNode 重新裁，
     * 只在「数值不等」时才刷会漏掉「布局前 outline 为空」的那一帧。
     */
    private fun apply(value: Float) {
        p = value
        card.translationX = width().toFloat() * value
        radius = if (value <= 0.001f) 0f else maxRadius * Math.min(1f, value / ROUND_AT)
        // 投影跟着位移淡入：落位时 elevation 归 0，静止状态不会在上下边缘留一条阴影线
        card.setElevation(shadowPx * Math.min(1f, value * 3f))
        card.invalidateOutline()
    }

    private fun slideTo(target: Float, ms: Int) {
        slideTo(target, ms, null)
    }

    private fun slideTo(target: Float, ms: Int, done: Runnable?) {
        stop()
        val v = ValueAnimator.ofFloat(p, target)
        v.setDuration(ms.toLong())
        v.setInterpolator(DecelerateInterpolator(1.2f))
        v.addUpdateListener { x -> apply(x.animatedValue as Float) }
        if (done != null) v.addListener(End(this, done))
        anim = v
        v.start()
    }

    private fun stop() {
        val v = anim
        anim = null
        if (v != null) {
            v.removeAllUpdateListeners()
            v.removeAllListeners()
            v.cancel()
        }
    }

    /** 卡片宽高就绪后再跑（onCreate 里 setContentView 之后还没 measure，宽是 0）。 */
    private fun whenLaidOut(r: Runnable) {
        if (card.width > 0 && card.height > 0) {
            r.run()
            return
        }
        card.addOnLayoutChangeListener(LaidOut(this, r))
    }

    /** 圆角只认「当前半径」，所以每帧改半径后 invalidateOutline 即刻生效。 */
    private class CardOutline(private val host: PageSlide) : ViewOutlineProvider() {

        override fun getOutline(v: View, o: Outline) {
            val w = v.width
            val h = v.height
            if (w <= 0 || h <= 0) {
                o.setEmpty()
                return
            }
            val r = Math.min(host.radius, Math.min(w, h) / 2f)
            if (r <= 0.5f) {
                o.setRect(0, 0, w, h)
            } else {
                o.setRoundRect(0, 0, w, h, r)
            }
        }
    }

    private class LaidOut(private val host: PageSlide, private val after: Runnable) :
        View.OnLayoutChangeListener {

        override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
                                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
            if (v.width <= 0 || v.height <= 0) return
            v.removeOnLayoutChangeListener(this)
            after.run()
        }
    }

    private class Enter(private val host: PageSlide) : Runnable {

        override fun run() {
            host.slideTo(0f, IN_MS)
        }
    }

    private class End(private val host: PageSlide, private val after: Runnable) :
        AnimatorListenerAdapter() {

        override fun onAnimationEnd(animation: Animator) {
            if (host.anim !== animation) return
            host.anim = null
            after.run()
        }
    }

    /** 返回手势 → 整页跟手右移；松手滑完再 finish，取消则滑回原位。 */
    private class ExitHandler(private val host: PageSlide) : BackGestures.Handler {

        override fun onStart() {
            host.gestureStart()
        }

        override fun onProgress(progress: Float) {
            host.gestureProgress(progress)
        }

        override fun onCommit() {
            host.gestureCommit()
        }

        override fun onCancel() {
            host.gestureCancel()
        }
    }

    private class Finisher(private val act: Activity) : Runnable {

        override fun run() {
            if (act.isFinishing || act.isDestroyed) return
            act.finish()
            act.overridePendingTransition(0, 0)
        }
    }

    companion object {

        /**
         * 把窗口切成「内容铺满全屏」：API 30+ 走官方 setDecorFitsSystemWindows(false)，
         * 再往下用 LAYOUT_FULLSCREEN / LAYOUT_HIDE_NAVIGATION 两套老 flag 等效实现。
         */
        private fun fullBleed(win: Window) {
            if (Build.VERSION.SDK_INT >= 30) {
                win.setDecorFitsSystemWindows(false)
            } else {
                win.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            }
        }

        /**
         * 把 content 里已有的页面根视图搬进自建卡片，再把卡片放回 content。
         * 直接对 DecorView 设 outline/裁剪不生效，必须换成普通 View 才裁得出圆角。
         */
        private fun wrapContent(win: Window, card: FrameLayout) {
            val root = win.findViewById<View>(android.R.id.content)
            if (root !is ViewGroup) return
            val host = root
            while (host.childCount > 0) {
                val child = host.getChildAt(0)
                host.removeViewAt(0)
                card.addView(
                    child,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            host.addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        /**
         * 系统把「窗口圆角」放在 android 包的 rounded_corner_radius 维度（与系统返回动画同源）；
         * 取不到（老系统 / 改了资源的 ROM）就退回 24dp，观感和主流机型基本一致。
         */
        private fun windowCornerRadius(a: Activity): Float {
            try {
                val id = a.resources.getIdentifier(
                    "rounded_corner_radius", "dimen", "android"
                )
                if (id > 0) {
                    val r = a.resources.getDimensionPixelSize(id).toFloat()
                    if (r > 0) return r
                }
            } catch (ignored: Throwable) {
            }
            return Math.max(24f * a.resources.displayMetrics.density, 1f)
        }
    }
}
