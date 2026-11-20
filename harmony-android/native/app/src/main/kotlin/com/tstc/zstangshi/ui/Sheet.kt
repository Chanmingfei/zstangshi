package com.tstc.zstangshi.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

import com.tstc.zstangshi.R

/**
 * 底部弹层（旧版 sheet 的原生复刻）。
 *
 * 旧版是 CSS 的 translate3d + 遮罩淡入，这边用同一套节奏：
 *   · 遮罩从 0 淡到 0.38，220ms；
 *   · 卡片从底下 40dp 处滑上来，上滑用 Decelerate（先快后停），300ms；
 *   · 关闭时反过来，等动画跑完再 dismiss，所以不会「啪」一下消失。
 *
 * 顶部那条小横杠既是最像原版的一处细节，也顺手当了可以下拉关闭的手势区。
 */
object Sheet {

    /** 当前开着的弹层数量：自动更新提醒靠它判断「现在弹会不会叠在别的弹层上」。 */
    private val openCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 有没有弹层正开着。 */
    @JvmStatic
    fun anyShowing(): Boolean {
        return openCount.get() > 0
    }

    class Handle internal constructor(
        private val dialog: Dialog,
        private val card: View,
        private val scrim: View,
        private val height: Int
    ) {
        private var closing = false

        fun dismiss() {
            if (closing) return
            closing = true
            card.animate()
                .translationY(height.toFloat())
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .withEndAction {
                    try {
                        dialog.dismiss()
                    } catch (ignored: Exception) {
                    }
                }
                .start()
            scrim.animate().alpha(0f).setDuration(180).start()
        }

        fun isShowing(): Boolean {
            return dialog.isShowing
        }
    }

    /**
     * @param content 已经填好内容的卡片（宽度会撑满，高度按内容）
     * @param height  卡片高度（px）。传 0 表示按内容自适应
     */
    @JvmStatic
    fun show(host: Activity, content: View, height: Int): Handle {
        val d = host.resources.displayMetrics.density
        val dp = Math.round(d)
        val screenH = host.resources.displayMetrics.heightPixels
        val cardH = if (height > 0) height else Math.min(screenH * 3 / 4, dp * 420)

        val dlg = Dialog(host, android.R.style.Theme_Translucent_NoTitleBar)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = FrameLayout(host)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )

        // 遮罩：点一下关掉（旧版点空白处也是关）
        val scrim = View(host)
        scrim.setBackgroundColor(0x00000000)
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val card = LinearLayout(host)
        card.orientation = LinearLayout.VERTICAL
        card.setBackground(host.getDrawable(R.drawable.sheet_bg))
        card.setElevation((dp * 12).toFloat())

        // 顶部小横杠
        val barWrap = FrameLayout(host)
        barWrap.setPadding(0, dp * 8, 0, dp * 6)
        val bar = View(host)
        val g = GradientDrawable()
        g.setColor(host.getColor(R.color.border_subtle))
        g.setCornerRadius((dp * 2).toFloat())
        bar.background = g
        val blp = FrameLayout.LayoutParams(dp * 36, Math.max(3, dp * 3))
        blp.gravity = Gravity.CENTER_HORIZONTAL
        barWrap.addView(bar, blp)
        card.addView(
            barWrap,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val clp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH)
        clp.gravity = Gravity.BOTTOM
        root.addView(card, clp)

        dlg.setContentView(root)
        // 卡片先自己拿住焦点：不这样的话，弹层里第一个 EditText 会抢焦点，
        // ScrollView 跟着滚一屏，用户看到的就不是「刚打开时那个位置」了
        card.isFocusableInTouchMode = true
        card.requestFocus()
        // 卡片先自己拿住焦点：不这样的话，弹层里第一个 EditText 会抢焦点，
        // ScrollView 跟着滚一屏，用户看到的就不是「刚打开时那个位置」了
        card.isFocusableInTouchMode = true
        card.requestFocus()
        val win = dlg.window
        if (win != null) {
            win.setBackgroundDrawable(ColorDrawable(0x00000000))
            win.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            win.setGravity(Gravity.BOTTOM)
            syncSystemBars(host, win)
        }

        val handle = Handle(dlg, card, scrim, cardH)
        // 遮罩和横杠都关弹层；卡片内部不吞事件
        scrim.setOnClickListener { handle.dismiss() }
        barWrap.setOnClickListener { handle.dismiss() }

        dlg.setOnShowListener {
            card.translationY = cardH.toFloat()
            card.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
            scrim.alpha = 0f
            scrim.animate().alpha(0.38f).setDuration(220).start()
        }

        // 下拉横杠也能关：手指往下拖过 90dp 就松手关闭
        val downY = floatArrayOf(0f)
        barWrap.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY[0] = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = Math.max(0f, e.rawY - downY[0])
                    card.translationY = dy
                    scrim.alpha = Math.max(0f, 0.38f * (1 - dy / cardH))
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - downY[0]
                    if (dy > dp * 90) {
                        handle.dismiss()
                    } else {
                        card.animate().translationY(0f).setDuration(180)
                            .setInterpolator(DecelerateInterpolator(1.4f)).start()
                        scrim.animate().alpha(0.38f).setDuration(180).start()
                    }
                    true
                }
                else -> false
            }
        }

        dlg.setOnDismissListener { openCount.decrementAndGet() }
        openCount.incrementAndGet()
        dlg.show()
        return handle
    }

    /**
     * 弹窗状态栏修复：Dialog 用的 Theme_Translucent_NoTitleBar 是全屏半透明主题，
     * 弹窗窗口会接管状态栏/导航栏的装饰，而该主题默认不带夜间图标位、状态栏底色是黑的
     * —— 表现为弹层一开，顶部状态栏整条变黑，关掉又恢复。
     * 这里把宿主 Activity 当前的状态栏/导航栏颜色与亮暗图标位原样抄给弹窗窗口，
     * 让弹窗期间的系统栏观感与弹层打开前完全一致。
     *
     * ⚠️ 鸿蒙专版：整段包 try/catch。`statusBarColor` / `navigationBarColor`
     * 在鸿蒙上没有实现，读取或设置都可能抛 Error（不是 Exception）。
     * 这只是观感优化，失败就算了，绝不能因此崩。
     */
    private fun syncSystemBars(host: Activity, win: Window) {
        try {
            val hostWin = host.window ?: return
            // setStatusBarColor 生效的前提：窗口自己画系统栏背景
            win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            val hostStatus = hostWin.statusBarColor
            @Suppress("DEPRECATION")
            val hostNav = hostWin.navigationBarColor
            @Suppress("DEPRECATION")
            win.setStatusBarColor(hostStatus)
            @Suppress("DEPRECATION")
            win.setNavigationBarColor(hostNav)
            @Suppress("DEPRECATION")
            val hostDecor = hostWin.decorView
            val decor = win.decorView
            @Suppress("DEPRECATION")
            val lightMask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            var vis = decor.systemUiVisibility
            @Suppress("DEPRECATION")
            val hostVis = hostDecor.systemUiVisibility
            vis = (vis and lightMask.inv()) or (hostVis and lightMask)
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = vis
        } catch (t: Throwable) {
            // 鸿蒙 / 无系统栏 API 的设备：跳过
        }
    }
}
