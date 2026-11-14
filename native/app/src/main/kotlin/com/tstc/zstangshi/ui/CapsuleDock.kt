package com.tstc.zstangshi.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator

import com.tstc.zstangshi.R

/**
 * 悬浮胶囊 Dock —— 直接取自烫梨（github.com/augustlies/tangli, GPL-3.0）的 CapsuleDock，
 * 按本项目改成 4 个入口、图标在上文字在下。
 *
 * 交互与烫梨完全一致：
 *   点一下 —— 选中块以 320ms + OvershootInterpolator(1.4) 回弹滑过去；
 *   按住横拖 —— 选中块跟手、横向拉长到 1.06，每过一个入口轻震一次，松手吸附；
 *   按着别的格 —— 先在那里浮出一颗淡胶囊预告落点。
 *
 * 与原版的差异只有三处，其余逐行保留：
 *   1. 入口 3 → 4 个（烫梨是 课表/拦截/设置），标签与图标全部换成自己的；
 *   2. 文字从「图标在左」改为「图标在上」（4 项时横排会挤，竖向堆叠才留得住 44dp 热区）；
 *   3. 去掉了 androidx 依赖（本项目零第三方库，只用 framework API），配色改成读资源，
 *      浅色/深色由 View 自己按 Configuration 判断，不再依赖烫梨的 G 全局状态类。
 */
open class CapsuleDock @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    fun interface OnTabSelectedListener {
        fun onTabSelected(index: Int)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons = arrayOfNulls<Drawable>(ICONS.size)
    private val tmp = RectF()
    private val density: Float

    private var selected = 0
    /** 选中块中心所在位置，单位「格」（0..n-1，可以是小数）。 */
    private var pos = 0f
    private var anim: ValueAnimator? = null
    private var listener: OnTabSelectedListener? = null
    private var reselect: OnTabSelectedListener? = null

    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    /** 按下时预览要切去的那一格（-1 = 没按着），松手才真正切。 */
    private var pressedSlot = -1
    private var lastHoverTab = -1

    init {
        density = resources.displayMetrics.density
        textPaint.setTextAlign(Paint.Align.CENTER)
        textPaint.setTextSize(10.5f * resources.displayMetrics.scaledDensity)
        strokePaint.setStyle(Paint.Style.STROKE)
        strokePaint.setStrokeWidth(Math.max(1f, density * 0.8f))
        for (i in ICONS.indices) {
            val d = context.getDrawable(ICONS[i])
            icons[i] = d?.mutate()
        }
        setClickable(true)
        setElevation(8f * density)
        setOutlineProvider(CapsuleOutline())
        updateDescription()
    }

    fun setOnTabSelectedListener(l: OnTabSelectedListener?) {
        this.listener = l
    }

    /** 点已经选中的那一项（比如在课表页再点「课表」= 回到本周）。 */
    fun setOnTabReselectedListener(l: OnTabSelectedListener?) {
        this.reselect = l
    }

    /** 外部切页时同步选中块（animate=false 或还没布局时直接就位）。 */
    fun setSelected(index: Int, animate: Boolean) {
        val i = Math.max(0, Math.min(LABELS.size - 1, index))
        selected = i
        updateDescription()
        if (!animate || width == 0) {
            anim?.cancel()
            pos = i.toFloat()
            invalidate()
            return
        }
        animateTo(i)
    }

    private fun updateDescription() {
        contentDescription = "底部导航，当前：" + LABELS[selected] + "。左右拖动或点按切换：首页、课表、成绩、我的"
    }

    fun selectedTab(): Int {
        return selected
    }

    private fun animateTo(index: Int) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(pos, index.toFloat())
        anim = a
        a.setDuration(320)
        a.setInterpolator(OvershootInterpolator(1.4f))
        a.addUpdateListener { x ->
            pos = x.animatedValue as Float
            invalidate()
        }
        a.start()
    }

    private fun pad(): Float {
        return 5f * density
    }

    private fun cellWidth(): Float {
        return (width - pad() * 2f) / LABELS.size
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cell = cellWidth()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
                lastHoverTab = selected
                pressedSlot = Math.round(slotAt(e.x, pad(), cell, LABELS.size))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(e.x - downX)
                val dy = Math.abs(e.y - downY)
                // 明显是横着拖（横向 > 12dp 且比纵向大）才接管；轻微抖动仍算点按
                if (!dragging && dx > 12f * density && dx > dy * 1.5f) {
                    dragging = true
                    pressedSlot = -1
                    anim?.cancel()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    pos = slotAt(e.x, pad(), cell, LABELS.size)
                    val hover = Math.round(pos)
                    if (hover != lastHoverTab) {
                        lastHoverTab = hover
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasDrag = dragging
                val target = if (wasDrag) Math.round(pos)
                else (if (pressedSlot >= 0) pressedSlot else Math.round(slotAt(e.x, pad(), cell, LABELS.size)))
                dragging = false
                pressedSlot = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                animateTo(target)
                if (target != selected) {
                    selected = target
                    updateDescription()
                    if (!wasDrag) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    listener?.onTabSelected(target)
                } else {
                    performClick()
                    if (!wasDrag) reselect?.onTabSelected(target)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                pressedSlot = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                animateTo(selected)
                return true
            }
            else -> return super.onTouchEvent(e)
        }
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    /** 胶囊轮廓：阴影跟着圆角走。写成静态内部类而不是匿名类 —— d8 对
     *  「同一文件里的匿名内部类 + JDK17 nest 属性」会崩（编译期 NPE），静态类没有这个问题。 */
    private class CapsuleOutline : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val r = h / 2f

        val night = (resources.configuration.uiMode
                and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val ink = getColor(if (night) R.color.text_primary else R.color.text_primary)
        val sub = getColor(R.color.text_tertiary)
        val card = getColor(R.color.bg_surface)
        val pillColor: Int

        if (night) {
            bgPaint.setColor(0xF21C222E.toInt())
            strokePaint.setColor(0x2EFFFFFF.toInt())
            pillColor = 0x1FFFFFFF
        } else {
            bgPaint.setColor(0xFAFFFFFF.toInt())
            strokePaint.setColor(0x1C14203A)
            pillColor = 0x0F14203A
        }

        tmp.set(0f, 0f, w, h)
        canvas.drawRoundRect(tmp, r, r, bgPaint)
        val hs = strokePaint.strokeWidth / 2f
        tmp.set(hs, hs, w - hs, h - hs)
        canvas.drawRoundRect(tmp, r, r, strokePaint)

        val cell = cellWidth()
        val p = pad()
        // 选中块：拖动时略微拉长，像被手指拽着
        val stretch = if (dragging) 1.06f else 1f
        val cx = p + cell * (pos + 0.5f)
        val halfW = cell / 2f * stretch
        tmp.set(cx - halfW, p, cx + halfW, h - p)
        pillPaint.setColor(pillColor)
        val pr = tmp.height() / 2f
        canvas.drawRoundRect(tmp, pr, pr, pillPaint)
        if (pressedSlot >= 0 && pressedSlot != Math.round(pos)) {
            // 按着别的格：先在那里浮出一颗淡胶囊，告诉你松手会去哪
            val pcx = p + cell * (pressedSlot + 0.5f)
            tmp.set(pcx - cell / 2f, p, pcx + cell / 2f, h - p)
            pillPaint.setColor(if (night) 0x14FFFFFF else 0x0A14203A)
            canvas.drawRoundRect(tmp, pr, pr, pillPaint)
        }

        // 图标在上、文字在下：4 项横排时若「左图标右文字」会把每格挤到 60dp 以下
        val iconSize = 20f * density
        val gap = 2f * density
        val fm = textPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val block = iconSize + gap + textH
        val top = h / 2f - block / 2f
        val offColor = sub

        for (i in LABELS.indices) {
            val icx = p + cell * (i + 0.5f)
            // 离选中块越近越接近「选中色」，拖动过程中颜色平滑过渡
            val near = Math.max(0f, 1f - Math.abs(pos - i))
            val color = mixColor(offColor, if (night) 0xFFF2F5FA.toInt() else ink, near)
            textPaint.setColor(color)
            textPaint.setTypeface(if (near > 0.5f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)

            val d = icons[i]
            if (d != null) {
                val left = Math.round(icx - iconSize / 2f)
                val t = Math.round(top)
                d.setBounds(left, t, Math.round(left + iconSize), Math.round(t + iconSize))
                d.setTint(color)
                d.draw(canvas)
            }
            canvas.drawText(LABELS[i], icx, top + iconSize + gap - fm.ascent, textPaint)
        }
    }

    private fun getColor(id: Int): Int {
        return resources.getColor(id, null)
    }

    companion object {
        private val LABELS = arrayOf("首页", "课表", "成绩", "我的")
        private val ICONS = intArrayOf(
            R.drawable.ic_nav_home,
            R.drawable.ic_nav_timetable,
            R.drawable.ic_nav_grades,
            R.drawable.ic_nav_mine,
        )

        /** 手指 x → 格位置（小数，夹在 0..n-1）。 */
        @JvmStatic
        fun slotAt(x: Float, left: Float, cell: Float, n: Int): Float {
            if (cell <= 0) return 0f
            val p = (x - left) / cell - 0.5f
            return Math.max(0f, Math.min((n - 1).toFloat(), p))
        }

        private fun mixColor(a: Int, b: Int, t: Float): Int {
            val tt = Math.max(0f, Math.min(1f, t))
            val aa = (a ushr 24) and 0xFF
            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val ba = (b ushr 24) and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            val ra = Math.round(aa + (ba - aa) * tt)
            val rr = Math.round(ar + (br - ar) * tt)
            val rg = Math.round(ag + (bg - ag) * tt)
            val rb = Math.round(ab + (bb - ab) * tt)
            return (ra shl 24) or (rr shl 16) or (rg shl 8) or rb
        }
    }
}
