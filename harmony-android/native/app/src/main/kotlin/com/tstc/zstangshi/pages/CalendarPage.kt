package com.tstc.zstangshi.pages

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.tstc.zstangshi.R
import com.tstc.zstangshi.data.Dates
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.data.Timetable
import com.tstc.zstangshi.data.WeekMath
import java.util.Calendar

/**
 * 校历（月历视图，旧版那张日历的还原）。
 *
 * 结构：学期信息卡 → 月份切换（‹ 2026年11月 ›）→ 星期表头 → 6×7 的日期格子 → 选中那天的一句说明。
 *
 * 格子里的信息只放「真的推得出来的」：
 *   · 教学周里的日子带一个小点，当前周更亮；
 *   · 今天描一圈品牌色；
 *   · 不在本学期范围内的日子灰掉。
 * 放假、调休这类细项没有可靠数据源，就不编。
 */
class CalendarPage(private val host: Activity, private val store: Store) : LinearLayout(host) {

    private val monthLabel: TextView
    private val grid: MonthView
    private val dayInfo: TextView

    private var timetable: Timetable? = null
    private var startDate = ""
    private var totalWeeks = 20
    private var year: Int
    private var month: Int              // 当前显示的月份
    private var selected = ""           // yyyy-MM-dd

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(if (store.darkNow()) 0xFF0F1520.toInt() else host.getColor(R.color.bg_page))
        setPadding(dp(14), dp(10), dp(14), dp(10))

        val loaded = store.loadTimetable()
        timetable = loaded
        if (loaded != null) {
            startDate = loaded.semester.startDate
            totalWeeks = if (loaded.semester.totalWeeks <= 0) 20 else loaded.semester.totalWeeks
        }

        val now = Calendar.getInstance()
        year = now.get(Calendar.YEAR)
        month = now.get(Calendar.MONTH) + 1
        selected = WeekMath.todayDateLabel()

        // ── 学期信息 ──
        val card = LinearLayout(host)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.background = host.getDrawable(R.drawable.card_bg)

        val name = TextView(host)
        name.text = if (loaded == null || loaded.semester.name.isEmpty())
            "本学期" else loaded.semester.name
        name.setTextSize(15f)
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(host.getColor(R.color.text_primary))
        card.addView(name)

        val sub = TextView(host)
        sub.setTextSize(12f)
        sub.setTextColor(host.getColor(R.color.text_tertiary))
        sub.setPadding(0, dp(5), 0, 0)
        if (!Dates.valid(startDate)) {
            sub.text = "还没设置开学日期，去「我的」补上才能算周次"
        } else {
            val w = WeekMath.currentWeek(startDate, totalWeeks)
            sub.text = "第 1 周从 " + startDate + " 开始 · 共 " + totalWeeks + " 周" +
                    (if (w >= 1 && w <= totalWeeks) " · 现在第 " + w + " 周" else "")
        }
        card.addView(sub)
        addView(card, lp(dp(10)))

        // ── 月份切换 ──
        val nav = LinearLayout(host)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL

        val prev = arrow("‹")
        prev.setOnClickListener { shiftMonth(-1) }
        nav.addView(prev, LinearLayout.LayoutParams(dp(44), dp(40)))

        monthLabel = TextView(host)
        monthLabel.setTextSize(16f)
        monthLabel.typeface = Typeface.DEFAULT_BOLD
        monthLabel.setTextColor(host.getColor(R.color.text_primary))
        monthLabel.gravity = Gravity.CENTER
        nav.addView(monthLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val next = arrow("›")
        next.setOnClickListener { shiftMonth(1) }
        nav.addView(next, LinearLayout.LayoutParams(dp(44), dp(40)))

        // 「回到今天」：翻走几个月之后不用一格格点回来
        val todayBtn = TextView(host)
        todayBtn.text = "今天"
        todayBtn.setTextSize(12.5f)
        todayBtn.gravity = Gravity.CENTER
        todayBtn.setTextColor(host.getColor(R.color.brand))
        todayBtn.background = host.getDrawable(R.drawable.btn_outline)
        todayBtn.setOnClickListener { backToToday() }
        val tlp = LinearLayout.LayoutParams(dp(56), dp(30))
        tlp.leftMargin = dp(4)
        nav.addView(todayBtn, tlp)
        addView(nav)

        // ── 星期表头 ──
        val week = LinearLayout(host)
        week.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        for (s in names) {
            val t = TextView(host)
            t.text = s
            t.setTextSize(11.5f)
            t.gravity = Gravity.CENTER
            t.setTextColor(host.getColor(R.color.text_tertiary))
            week.addView(t, LinearLayout.LayoutParams(0, dp(26), 1f))
        }
        addView(week)

        // ── 日期格子 ──
        grid = MonthView(this)
        addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6 * 52)))
        grid.setOnPick { day ->
            selected = day
            grid.invalidate()
            updateDayInfo()
        }

        // ── 选中那天 ──
        dayInfo = TextView(host)
        dayInfo.setTextSize(12.5f)
        dayInfo.setTextColor(host.getColor(R.color.text_secondary))
        dayInfo.setLineSpacing(dp(3).toFloat(), 1f)
        dayInfo.setPadding(dp(16), dp(12), dp(16), dp(12))
        dayInfo.background = host.getDrawable(R.drawable.card_bg)
        val ilp = lp(dp(10))
        ilp.topMargin = dp(10)
        addView(dayInfo, ilp)
        dayInfo.layoutParams = ilp

        render()
    }

    /** 不管翻到哪个月，一键回到本月并把今天选中。 */
    private fun backToToday() {
        val now = Calendar.getInstance()
        year = now.get(Calendar.YEAR)
        month = now.get(Calendar.MONTH) + 1
        selected = WeekMath.todayDateLabel()
        render()
    }

    private fun shiftMonth(delta: Int) {
        month += delta
        if (month < 1) {
            month = 12
            year--
        } else if (month > 12) {
            month = 1
            year++
        }
        grid.invalidate()
        monthLabel.text = year.toString() + " 年 " + month + " 月"
        syncGridHeight()
    }

    private fun render() {
        monthLabel.text = year.toString() + " 年 " + month + " 月"
        updateDayInfo()
        grid.invalidate()
        syncGridHeight()
    }

    /** 当月占 5 行就别留第 6 行的空白（旧版也是按行数撑满的）。 */
    private fun syncGridHeight() {
        val c = Calendar.getInstance()
        c.set(year, month - 1, 1)
        val offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rows = Math.ceil((offset + days) / 7.0).toInt()
        val lp = grid.layoutParams
        val h = dp(Math.max(4, rows) * 52)
        if (lp != null && lp.height == h) return
        if (lp != null) {
            lp.height = h
            grid.layoutParams = lp
        }
    }

    private fun updateDayInfo() {
        if (!Dates.valid(selected) || !Dates.valid(startDate)) {
            dayInfo.text = "选一天看看是第几周"
            return
        }
        val week = Dates.weekOf(startDate, selected)
        val wd = "一二三四五六日"[Math.max(0, Math.min(6, Dates.dayOfWeek(selected) - 1))].toString()
        if (week < 1) {
            dayInfo.text = selected + " · 周" + wd + "\n开学前，第 1 周从 " + startDate + " 开始"
        } else if (week > totalWeeks) {
            dayInfo.text = selected + " · 周" + wd + "\n本学期（共 " + totalWeeks + " 周）已经结束"
        } else {
            dayInfo.text = selected + " · 周" + wd + " · 第 " + week + " 周"
        }
    }

    private fun arrow(s: String): TextView {
        val v = TextView(host)
        v.text = s
        v.setTextSize(20f)
        v.gravity = Gravity.CENTER
        v.setTextColor(host.getColor(R.color.text_primary))
        return v
    }

    private fun lp(bottom: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.bottomMargin = bottom
        return p
    }

    private fun dp(v: Int): Int {
        return Math.round(v * resources.displayMetrics.density)
    }

    // ================= 月历格子 =================

    /**
     * 必须是 static：当前这套 d8 只要碰到带 this$0 的非静态内部类就内部报错
     * （`NullPointerException: Cannot invoke "String.length()"`），
     * 所以外部实例改用 page 字段显式持有。
     */
    private class MonthView(private val page: CalendarPage) : View(page.context) {

        fun interface OnPick {
            fun onPick(ymd: String)
        }

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val density: Float = resources.displayMetrics.density
        private var onPick: OnPick? = null

        init {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 13.5f * resources.displayMetrics.scaledDensity
        }

        fun setOnPick(l: OnPick) {
            onPick = l
        }

        override fun onDraw(canvas: Canvas) {
            val night = page.store.darkNow()
            val ink = page.host.getColor(R.color.text_primary)
            val dim = page.host.getColor(R.color.text_tertiary)
            val brand = page.host.getColor(R.color.brand)

            val c = Calendar.getInstance()
            c.set(page.year, page.month - 1, 1)
            val firstDow = c.get(Calendar.DAY_OF_WEEK)  // 1=周日
            val offset = (firstDow + 5) % 7             // 周一为 0
            val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)

            val cw = width / 7f
            val ch = height / 6f
            val today = WeekMath.todayDateLabel()

            val fm = text.fontMetrics
            for (i in 0 until 42) {
                val dayNo = i - offset + 1
                val left = (i % 7) * cw
                val top = (i / 7) * ch
                if (dayNo < 1 || dayNo > days) continue

                val ymd = String.format("%04d-%02d-%02d", page.year, page.month, dayNo)
                val isToday = ymd == today
                val isPicked = ymd == page.selected

                // 选中 / 今天：一块圆角底。竖向只占 0.14~0.76，
                // 底下 0.86 那颗教学周小点才不会被压在圆角边上（之前是重叠的）
                if (isPicked || isToday) {
                    rect.set(left + cw * 0.16f, top + ch * 0.14f,
                            left + cw * 0.84f, top + ch * 0.74f)
                    p.setColor(if (isPicked) brand else (if (night) 0x2EFFFFFF else 0x1A0062CC))
                    canvas.drawRoundRect(rect, 13 * density, 13 * density, p)
                }

                text.setColor(if (isPicked) 0xFFFFFFFF.toInt() else ink)
                text.setTypeface(if (isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
                val baseline = top + ch * 0.44f - (fm.ascent + fm.descent) / 2f
                canvas.drawText(dayNo.toString(), left + cw / 2f, baseline, text)

                // 教学周里的小点（在数字下方，跟选中底分开）
                if (Dates.valid(page.startDate)) {
                    val w = Dates.weekOf(page.startDate, ymd)
                    if (w >= 1 && w <= page.totalWeeks) {
                        val now = WeekMath.currentWeek(page.startDate, page.totalWeeks)
                        p.setColor(if (isPicked) brand else (if (w == now) brand else dim))
                        canvas.drawCircle(left + cw / 2f, top + ch * 0.88f, 2.2f * density, p)
                    }
                }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP) {
                val cw = width / 7f
                val ch = height / 6f
                val col = (e.x / cw).toInt()
                val row = (e.y / ch).toInt()
                val c = Calendar.getInstance()
                c.set(page.year, page.month - 1, 1)
                val offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
                val dayNo = row * 7 + col - offset + 1
                val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
                if (dayNo >= 1 && dayNo <= days) {
                    val ymd = String.format("%04d-%02d-%02d", page.year, page.month, dayNo)
                    onPick?.onPick(ymd)
                }
                return true
            }
            return e.action == MotionEvent.ACTION_DOWN || super.onTouchEvent(e)
        }
    }
}
