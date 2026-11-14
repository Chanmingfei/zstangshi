package com.tstc.zstangshi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

import com.tstc.zstangshi.R

import com.tstc.zstangshi.data.Dates
import com.tstc.zstangshi.data.ScheduleSettings
import com.tstc.zstangshi.data.Timetable
import com.tstc.zstangshi.data.WeekMath
import com.tstc.zstangshi.data.WeekOverlay

import java.util.ArrayList
import java.util.LinkedHashMap

/**
 * 课表网格（自绘）。
 *
 * 为什么要自绘而不拼 View：一屏最多 7×16 = 112 个格子，用 View 会叠出上百个
 * onMeasure/onLayout；自绘一次 canvas 就完事，周次切换也不用重建视图树。
 *
 * 数据从 [Timetable] 直接取，按「星期 + 起止节次」聚合：
 *   - 同一格只有一门课 → 铺满整格
 *   - 同一格有多门课（真冲突）→ 横向平分，各自一个窄块，不互相遮挡
 *   - WeekOverlay 里标了停课 / 调走的时段不画
 *
 * 顶部星期行 + 左侧节次栏（节次号 + 起止时间）都画在同一张画布上，
 * 所以「两端铺满屏幕」这件事由 grid 自己保证，不依赖外层 padding。
 */
open class TimetableGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    fun interface OnCourseClickListener {
        fun onCourseClick(course: Timetable.Course, session: Timetable.Session, day: Int, startSection: Int)
    }

    fun interface OnEmptyClickListener {
        fun onEmptyClick(dayOfWeek: Int, section: Int)
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density: Float

    private var timetable: Timetable? = null
    private var schedule: ScheduleSettings = ScheduleSettings.createDefault()
    private var overlay: WeekOverlay? = null
    private var week = 1
    private var night = false

    /** 画出来的块，用来做点击命中（绘制顺序即优先级，后画的在上） */
    private val blocks = ArrayList<Block>()
    private var courseListener: OnCourseClickListener? = null
    private var emptyListener: OnEmptyClickListener? = null
    private var downX = 0f
    private var downY = 0f

    private val headH: Float
    private val slotW: Float

    private class Block {
        var r = RectF()
        lateinit var course: Timetable.Course
        lateinit var session: Timetable.Session
        var day = 0
        var start = 0
    }

    init {
        density = resources.displayMetrics.density
        headH = 34f * density
        slotW = 46f * density
        text.setTextAlign(Paint.Align.CENTER)
        text.setTypeface(Typeface.DEFAULT)
    }

    fun setOnCourseClickListener(l: OnCourseClickListener?) {
        courseListener = l
    }

    fun setOnEmptyClickListener(l: OnEmptyClickListener?) {
        emptyListener = l
    }

    fun setSchedule(s: ScheduleSettings?) {
        if (s != null) schedule = s
        invalidate()
    }

    fun setData(t: Timetable?, o: WeekOverlay?, week: Int) {
        this.timetable = t
        this.overlay = o
        this.week = week
        invalidate()
    }

    fun week(): Int {
        return week
    }

    fun setNight(night: Boolean) {
        this.night = night
        invalidate()
    }

    // ---------- 几何 ----------

    private fun gridTop(): Float {
        return headH
    }

    private fun cellW(): Float {
        return (width - slotW) / 7f
    }

    private fun cellH(): Float {
        val n = Math.max(1, schedule.sectionCount())
        return (height - gridTop()) / n.toFloat()
    }

    /** 某天某节的矩形（day 1..7，section 1..n） */
    private fun cellRect(day: Int, startSection: Int, endSection: Int, out: RectF) {
        val cw = cellW()
        val ch = cellH()
        val left = slotW + (day - 1) * cw
        val top = gridTop() + (startSection - 1) * ch
        out.set(left, top, left + cw, top + (endSection - startSection + 1) * ch)
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        blocks.clear()

        val bgPage = if (night) 0xFF0F1520.toInt() else resources.getColor(R.color.bg_page, null)
        val surface = if (night) 0xFF1A2230.toInt() else resources.getColor(R.color.bg_surface, null)
        val line = if (night) 0x1FFFFFFF else resources.getColor(R.color.border_subtle, null)
        val tMain = if (night) 0xFFF2F5FA.toInt() else resources.getColor(R.color.text_primary, null)
        val tSub = if (night) 0xFF8B93A5.toInt() else resources.getColor(R.color.text_tertiary, null)

        canvas.drawColor(bgPage)

        val cw = cellW()
        val ch = cellH()
        val n = Math.max(1, schedule.sectionCount())

        // 星期头（滚动区之上不滚动，直接跟着画）
        p.setColor(surface)
        rect.set(0f, 0f, w.toFloat(), headH)
        canvas.drawRect(rect, p)
        text.setColor(tMain)
        text.setTextSize(12f * density)
        text.setTypeface(Typeface.DEFAULT_BOLD)
        val todayDow = Dates.dayOfWeek(WeekMath.todayDateLabel())
        for (i in 1..7) {
            val cx = slotW + (i - 0.5f) * cw
            val isToday = i == todayDow
            text.setColor(if (isToday) resources.getColor(R.color.brand, null) else tMain)
            canvas.drawText("周" + DAY_NAMES[i - 1], cx, headH * 0.52f, text)
            text.setTextSize(9.5f * density)
            text.setColor(if (isToday) resources.getColor(R.color.brand, null) else tSub)
            val wd = weekDates
            val d = if (wd != null && wd.size >= i) wd[i - 1] else ""
            if (d.isNotEmpty()) canvas.drawText(d, cx, headH * 0.86f, text)
            text.setTextSize(12f * density)
            text.setTypeface(Typeface.DEFAULT_BOLD)
        }

        // 斑马纹：偶数节次铺一层极淡的底（旧版就是这样，读起来不会串行）
        p.setColor(if (night) 0x0DFFFFFF else 0x08000000)
        for (i in 0 until n) {
            if (i % 2 == 1) continue
            val top = gridTop() + i * ch
            canvas.drawRect(slotW, top, w.toFloat(), top + ch, p)
        }

        // 网格线
        p.setColor(line)
        p.setStrokeWidth(Math.max(1f, density * 0.6f))
        for (i in 0..n) {
            val y = gridTop() + i * ch
            canvas.drawLine(0f, y, w.toFloat(), y, p)
        }
        for (i in 0..7) {
            val x = slotW + i * cw
            canvas.drawLine(x, gridTop(), x, h.toFloat(), p)
        }

        // 节次栏
        text.setTypeface(Typeface.DEFAULT)
        for (s in 1..n) {
            val top = gridTop() + (s - 1) * ch
            text.setTextSize(11f * density)
            text.setColor(tMain)
            canvas.drawText(s.toString(), slotW / 2f, top + ch * 0.42f, text)
            text.setTextSize(7.5f * density)
            text.setColor(tSub)
            canvas.drawText(schedule.startTime(s), slotW / 2f, top + ch * 0.68f, text)
            canvas.drawText(schedule.endTime(s), slotW / 2f, top + ch * 0.86f, text)
        }

        // 课程块
        val tt = timetable
        if (tt != null) drawCourses(canvas, tt)

        // 空课：不画任何东西（旧版会画一个「+」，用户明确说过无课时不显示卡片）
    }

    private var weekDates: Array<String>? = null

    fun setWeekDates(dates: Array<String>?) {
        this.weekDates = dates
    }

    private fun drawCourses(canvas: Canvas, tt: Timetable) {
        // 按「星期 + 起止」聚合
        val grouped = LinkedHashMap<String, MutableList<Array<Any>>>()
        val ov = overlay
        for (c in tt.courses) {
            for (s in c.sessions) {
                if (!s.isActive(week)) continue
                if (ov != null) {
                    val m = ov.cancelled[s]
                    if (m != null) continue
                }
                val key = "" + s.dayOfWeek + ":" + s.startSection + "-" + s.endSection
                var list = grouped[key]
                if (list == null) {
                    list = ArrayList<Array<Any>>()
                    grouped[key] = list
                }
                list.add(arrayOf<Any>(c, s))
            }
        }

        val cw = cellW()
        val inset = 1.5f * density
        val radius = 9f * density

        for (e in grouped.entries) {
            val list = e.value
            val count = list.size
            for (i in 0 until count) {
                val c = list[i][0] as Timetable.Course
                val s = list[i][1] as Timetable.Session
                val idx = Math.abs(c.colorIndex) % 8

                val r = RectF()
                cellRect(s.dayOfWeek, s.startSection, s.endSection, r)
                val partW = r.width() / count
                r.left += i * partW + inset
                r.right = r.left + partW - inset * 2f
                r.top += inset
                r.bottom -= inset

                val soft = resources.getColor(courseSoft(idx), null)
                p.setColor(if (night) withAlpha(soft, 0x2E) else soft)
                canvas.drawRoundRect(r, radius, radius, p)
                // 左侧色条
                p.setColor(resources.getColor(courseColor(idx), null))
                rect.set(r.left, r.top, r.left + 2.5f * density, r.bottom)
                canvas.drawRoundRect(rect, 1.5f * density, 1.5f * density, p)

                // 课程名用课程色（与色条同色），比深墨色更容易一眼认出是哪门课
                text.setTextAlign(Paint.Align.LEFT)
                text.setTypeface(Typeface.DEFAULT_BOLD)
                text.setTextSize(10f * density)
                text.setColor(resources.getColor(courseColor(idx), null))
                val tx = r.left + 6f * density
                val maxW = r.width() - 9f * density
                val lines = wrap(c.name, maxW, 2)
                var ty = r.top + 12f * density
                for (ln in lines) {
                    canvas.drawText(ln, tx, ty, text)
                    ty += 11f * density
                }

                // 教室（块高够才画）
                if (r.height() > 34f * density && s.location.isNotEmpty()) {
                    text.setTypeface(Typeface.DEFAULT)
                    text.setTextSize(8.5f * density)
                    text.setColor(resources.getColor(R.color.text_secondary, null))
                    val loc = wrap(s.location, maxW, 1)
                    if (loc.isNotEmpty()) canvas.drawText(loc[0], tx, ty + 1f * density, text)
                }
                text.setTextAlign(Paint.Align.CENTER)

                val b = Block()
                b.r.set(r)
                b.course = c
                b.session = s
                b.day = s.dayOfWeek
                b.start = s.startSection
                blocks.add(b)
            }
        }
    }

    /** 按宽度折行，最多 max 行（超出用省略号）。中文按字符宽估算即可。 */
    private fun wrap(s: String?, maxW: Float, max: Int): MutableList<String> {
        val out = ArrayList<String>()
        if (s == null || s.isEmpty() || maxW <= 0f) return out
        val cur = StringBuilder()
        var acc = 0f
        for (i in 0 until s.length) {
            val ch = s[i]
            val cwid = text.measureText(ch.toString())
            if (acc + cwid > maxW && cur.length > 0) {
                out.add(cur.toString())
                if (out.size == max) {
                    val last = out[max - 1]
                    out[max - 1] = last.substring(0, Math.max(1, last.length - 1)) + "…"
                    return out
                }
                cur.setLength(0)
                acc = 0f
            }
            cur.append(ch)
            acc += cwid
        }
        if (cur.length > 0) out.add(cur.toString())
        return out
    }

    private fun courseColor(idx: Int): Int {
        val ids = intArrayOf(
            R.color.course_0, R.color.course_1, R.color.course_2, R.color.course_3,
            R.color.course_4, R.color.course_5, R.color.course_6, R.color.course_7
        )
        return ids[idx % 8]
    }

    private fun courseSoft(idx: Int): Int {
        val ids = intArrayOf(
            R.color.course_0_soft, R.color.course_1_soft, R.color.course_2_soft, R.color.course_3_soft,
            R.color.course_4_soft, R.color.course_5_soft, R.color.course_6_soft, R.color.course_7_soft
        )
        return ids[idx % 8]
    }

    // ---------- 点击 ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (Math.abs(e.x - downX) > 12 * density || Math.abs(e.y - downY) > 12 * density) {
                    return true
                }
                handleTap(e.x, e.y)
                return true
            }
            else -> return super.onTouchEvent(e)
        }
    }

    private fun handleTap(x: Float, y: Float) {
        for (i in blocks.size - 1 downTo 0) {
            val b = blocks[i]
            if (b.r.contains(x, y)) {
                courseListener?.onCourseClick(b.course, b.session, b.day, b.start)
                return
            }
        }
        if (x < slotW || y < gridTop()) return
        val day = ((x - slotW) / cellW()).toInt() + 1
        val section = ((y - gridTop()) / cellH()).toInt() + 1
        if (day < 1 || day > 7 || section < 1 || section > schedule.sectionCount()) return
        emptyListener?.onEmptyClick(day, section)
    }

    companion object {
        private val DAY_NAMES = arrayOf("一", "二", "三", "四", "五", "六", "日")

        /** 取源色 RGB、套上目标透明度（深色下课程块用半透明底，避免刺眼） */
        private fun withAlpha(color: Int, alpha: Int): Int {
            return (alpha shl 24) or (color and 0x00FFFFFF)
        }
    }
}
