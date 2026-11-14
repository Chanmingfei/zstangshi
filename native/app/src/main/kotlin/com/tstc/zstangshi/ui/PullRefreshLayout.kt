package com.tstc.zstangshi.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

import com.tstc.zstangshi.R

/**
 * 轻量下拉刷新容器（项目零第三方依赖，不能用 androidx SwipeRefreshLayout）。
 *
 * 只在成绩页这类「进页读一次缓存、想看新数据再手动拉」的页面用：
 * 内容区自带 ScrollView，顶部是一条高度随手指变化的提示条；滚到顶部继续下拉
 * 超过阈值就触发回调，松手不足阈值自动收回。不会拦截正常的列表滚动。
 *
 * 提示条是「左侧图标 + 右侧文字」：下拉时画一个跟随手势翻转的箭头，
 * 达到阈值翻成向上；真正刷新时换成 indeterminate 旋转进度圈（品牌色），
 * 不再是干巴巴一行文字。
 */
open class PullRefreshLayout(c: Context) : LinearLayout(c) {

    private val header: LinearLayout
    private val label: TextView
    private val arrow: ArrowView
    private val spinner: ProgressBar
    private val scroll: ScrollView
    private val trigger: Int
    private val maxPull: Int
    private val busyHeight: Int

    private var downY = 0f
    private var downX = 0f
    private var dragging = false
    private var refreshing = false
    private var onRefresh: Runnable? = null
    /** 刷新中的提示语，各页面按自己的数据源改文案（默认按教务说）。 */
    private var busyText = "正在从教务刷新…"

    init {
        orientation = LinearLayout.VERTICAL
        val d = c.resources.displayMetrics.density
        trigger = Math.round(62 * d)
        maxPull = Math.round(108 * d)
        busyHeight = Math.round(50 * d)

        header = LinearLayout(c)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER
        header.isClickable = true

        val icon = Math.round(22 * d)
        val iconHost = FrameLayout(c)
        arrow = ArrowView(c, c.getColor(R.color.brand))
        spinner = ProgressBar(c, null, android.R.attr.progressBarStyleSmall)
        spinner.isIndeterminate = true
        spinner.indeterminateTintList = ColorStateList.valueOf(c.getColor(R.color.brand))
        spinner.visibility = View.GONE
        iconHost.addView(arrow, FrameLayout.LayoutParams(icon, icon, Gravity.CENTER))
        iconHost.addView(spinner, FrameLayout.LayoutParams(icon, icon, Gravity.CENTER))
        header.addView(iconHost, LinearLayout.LayoutParams(icon, icon))

        label = TextView(c)
        label.gravity = Gravity.CENTER_VERTICAL
        label.setTextSize(12.5f)
        label.setTextColor(c.getColor(R.color.text_secondary))
        label.includeFontPadding = false
        label.setText("下拉刷新")
        label.setPadding(Math.round(9 * d), 0, 0, 0)
        header.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        scroll = ScrollView(c)
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    /** 把页面内容放进这个 ScrollView（而不是自己再建一个）。 */
    fun scrollView(): ScrollView {
        return scroll
    }

    fun setOnRefresh(r: Runnable?) {
        onRefresh = r
    }

    /** 换数据源时改一句提示语，别让「正在从教务刷新」跑到不是教务的页面上。 */
    fun setRefreshText(busy: String?) {
        if (busy != null && busy.isNotEmpty()) busyText = busy
    }

    fun setRefreshing(on: Boolean) {
        refreshing = on
        dragging = false
        if (on) {
            arrow.visibility = View.GONE
            spinner.visibility = View.VISIBLE
        } else {
            spinner.visibility = View.GONE
            arrow.visibility = View.VISIBLE
            arrow.rotation = 0f
        }
        setHeader(if (on) busyHeight else 0, if (on) busyText else "下拉刷新")
    }

    private fun setHeader(h: Int, text: String) {
        val lp = header.layoutParams as LinearLayout.LayoutParams
        lp.height = Math.max(0, h)
        header.layoutParams = lp
        header.alpha = if (refreshing) 1f else Math.min(1f, h / trigger.toFloat())
        label.text = text
        if (!refreshing) arrow.rotation = if (h >= trigger) 180f else 0f
    }

    private fun headerHeight(): Int {
        return (header.layoutParams as LinearLayout.LayoutParams).height
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (refreshing) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                downX = ev.x
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                val dx = ev.x - downX
                // 必须是「已经在顶部 + 明显向下 + 以竖向为主」才抢手势
                if (dy > 12 && dy > Math.abs(dx) * 1.4f && scroll.scrollY <= 0) {
                    dragging = true
                    return true
                }
            }
            else -> {
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val h = Math.min(maxPull.toFloat(), (ev.y - downY) * 0.55f).toInt()
                setHeader(Math.max(0, h), if (h >= trigger) "松开立即刷新" else "下拉刷新")
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val fire = headerHeight() >= trigger
                dragging = false
                val r = onRefresh
                if (fire && r != null) {
                    setRefreshing(true)
                    post(r)
                } else {
                    setHeader(0, "下拉刷新")
                }
                return true
            }
            else -> return true
        }
    }

    /** Canvas 画的向下箭头：几何居中，旋转 180° 就是「松开刷新」的向上箭头。 */
    private class ArrowView(c: Context, color: Int) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        init {
            paint.setColor(color)
            paint.setStyle(Paint.Style.STROKE)
            paint.setStrokeWidth(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    2f, c.resources.displayMetrics
                )
            )
            paint.setStrokeCap(Paint.Cap.ROUND)
            paint.setStrokeJoin(Paint.Join.ROUND)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val arm = Math.min(width, height) * 0.24f
            path.reset()
            path.moveTo(cx - arm, cy - arm * 0.55f)
            path.lineTo(cx, cy + arm * 0.55f)
            path.lineTo(cx + arm, cy - arm * 0.55f)
            canvas.drawPath(path, paint)
        }
    }
}
