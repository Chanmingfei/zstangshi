package com.tstc.zstangshi.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

import com.tstc.zstangshi.R

/**
 * 三联段控（浅色 / 深色 / 跟随系统）。
 *
 * 与底栏那套胶囊是一路的：点哪一段，胶囊就带着一点点过冲滑过去，文字颜色
 * 跟着位置插值 —— 所以中间那一下不会出现「字先变色、块后到」的割裂感。
 *
 * 外框保持原来的圆角矩形（不是纯胶囊），胶囊按 R-3dp 收缩，四角才不会顶出来。
 */
open class SegmentedControl(c: Context, labels: Array<String>, initial: Int) : View(c) {

    fun interface OnChange {
        fun onChanged(index: Int)
    }

    private val labels = labels
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    /** 建的时候记下深浅，动画重绘时不再去读 Store —— 否则点了深色，胶囊滑动的那几帧
     *  就已经按新配色画了，看起来比整页先变色 */
    private val night: Boolean
    private val density: Float
    private val radius: Float
    private val inset: Float

    /** 胶囊当前停在第几段（带小数，动画中） */
    private var pos = 0f
    private var selected = 0
    private var onChange: OnChange? = null
    private var anim: ValueAnimator? = null

    init {
        this.selected = Math.max(0, Math.min(labels.size - 1, initial))
        this.pos = this.selected.toFloat()
        density = c.resources.displayMetrics.density
        night = com.tstc.zstangshi.data.Store(c).darkNow()
        radius = 13 * density
        inset = 3 * density

        borderPaint.setStyle(Paint.Style.STROKE)
        borderPaint.setStrokeWidth(Math.max(1f, density * 0.8f))
        textPaint.setTextAlign(Paint.Align.CENTER)
        textPaint.setTextSize(13 * c.resources.displayMetrics.scaledDensity)
        setClickable(true)
    }

    fun setOnChange(l: OnChange?) {
        this.onChange = l
    }

    fun setSelected(i: Int, animate: Boolean) {
        val target = Math.max(0, Math.min(labels.size - 1, i))
        if (target == selected && animate) return
        selected = target
        if (!animate) {
            pos = target.toFloat()
            invalidate()
            return
        }
        anim?.cancel()
        val a = ValueAnimator.ofFloat(pos, target.toFloat())
        anim = a
        a.setDuration(300)
        a.setInterpolator(OvershootInterpolator(1.35f))
        a.addUpdateListener { x ->
            pos = x.animatedValue as Float
            invalidate()
        }
        a.start()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = View.MeasureSpec.getSize(wSpec)
        val h = Math.round(40 * density)
        setMeasuredDimension(w, View.resolveSize(h, hSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = Math.min(radius, h / 2f)

        val night = isNight()

        // 轨道
        rect.set(0f, 0f, w, h)
        trackPaint.setColor(if (night) 0xFF1A2230.toInt() else 0xFFEDEFF4.toInt())
        canvas.drawRoundRect(rect, r, r, trackPaint)
        borderPaint.setColor(if (night) 0x1FFFFFFF else 0x1C14203A)
        val hs = borderPaint.strokeWidth / 2f
        rect.set(hs, hs, w - hs, h - hs)
        canvas.drawRoundRect(rect, r, r, borderPaint)

        // 滑动的胶囊
        val seg = w / labels.size.toFloat()
        val left = pos * seg + inset
        val right = (pos + 1) * seg - inset
        rect.set(left, inset, right, h - inset)
        pillPaint.setColor(resources.getColor(R.color.brand, null))
        val pr = Math.min(r - inset, (h - inset * 2) / 2f)
        canvas.drawRoundRect(rect, pr, pr, pillPaint)

        // 文字：离胶囊越近越白，越远越是次级灰
        val fm = textPaint.fontMetrics
        val cy = h / 2f - (fm.ascent + fm.descent) / 2f
        val on = 0xFFFFFFFF.toInt()
        val off = resources.getColor(R.color.text_secondary, null)
        for (i in labels.indices) {
            val d = Math.abs(i - pos)
            val near = Math.max(0f, 1f - Math.min(1f, d))
            textPaint.setColor(mix(off, on, near))
            textPaint.setTypeface(if (near > 0.5f) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
            canvas.drawText(labels[i], seg * (i + 0.5f), cy, textPaint)
        }
    }

    private fun isNight(): Boolean {
        return night
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            var i = (e.x / (width / labels.size.toFloat())).toInt()
            i = Math.max(0, Math.min(labels.size - 1, i))
            if (i != selected) {
                setSelected(i, true)
                onChange?.onChanged(i)
            }
            performClick()
            return true
        }
        return e.action == MotionEvent.ACTION_DOWN || super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private fun mix(a: Int, b: Int, t: Float): Int {
            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            val r = Math.round(ar + (br - ar) * t)
            val g = Math.round(ag + (bg - ag) * t)
            val bl = Math.round(ab + (bb - ab) * t)
            return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
        }
    }
}
