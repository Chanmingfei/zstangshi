package com.tstc.zstangshi.pages

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tstc.zstangshi.R
import com.tstc.zstangshi.data.Dates
import com.tstc.zstangshi.data.ScheduleSettings
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.data.Timetable
import com.tstc.zstangshi.data.WeekMath
import com.tstc.zstangshi.data.WeekOverlay
import com.tstc.zstangshi.ui.Sheet
import com.tstc.zstangshi.ui.TimetableGridView
import java.util.regex.Pattern

/**
 * 课表页。
 *
 * 主要解决旧版两个毛病：
 *   1. 免登录直接能看 —— 打开就读本地，读不到才提示去同步，不再把人踢回登录页；
 *   2. 课表铺满屏 —— 节次栏与星期行都画在网格里，左右不再留白。
 *
 * 周次切换点一次就重算 overlay 并重绘，不做页面重建。
 */
class TimetablePage(private val host: Activity, private val store: Store) : FrameLayout(host) {

    private val weekLabel: TextView
    private val prevBtn: TextView
    private val nextBtn: TextView
    private val title: TextView
    private val grid: TimetableGridView
    private val emptyBox: LinearLayout
    private val todayFab: TextView
    private var weekSwipe: WeekSwipe? = null
    private var fabShown = false
    private var weekInitialized = false

    private var timetable: Timetable? = null
    private lateinit var schedule: ScheduleSettings
    private var startDate = ""
    private var week = 1
    private var totalWeeks = 20

    init {
        setBackgroundColor(if (store.darkNow()) 0xFF0F1520.toInt() else host.getColor(R.color.bg_page))

        val col = LinearLayout(host)
        col.orientation = LinearLayout.VERTICAL
        addView(col, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ── 顶部：标题 + 周次切换 ──
        val header = LinearLayout(host)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val pad = dp(14)
        header.setPadding(pad, dp(8), pad, dp(8))

        title = TextView(host)
        title.setTextSize(17f)
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(host.getColor(R.color.text_primary))
        header.addView(title, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        prevBtn = stepBtn("‹", -1)
        header.addView(prevBtn)
        weekLabel = TextView(host)
        weekLabel.setTextSize(13f)
        weekLabel.gravity = Gravity.CENTER
        weekLabel.setTextColor(host.getColor(R.color.text_primary))
        // 旧版：点周次文字直接弹「选择周次」格子，不用一格格按 ‹ ›
        val weekBg = GradientDrawable()
        weekBg.setColor(host.getColor(R.color.brand_soft))
        weekBg.cornerRadius = dp(9).toFloat()
        weekLabel.background = weekBg
        // 高度固定成 dp(34)，与左右箭头、加号完全同高同行高，不再靠 padding 撑
        weekLabel.setPadding(0, 0, 0, 0)
        weekLabel.setOnClickListener { openWeekPicker() }
        val wlp = LinearLayout.LayoutParams(dp(82), dp(34))
        header.addView(weekLabel, wlp)
        nextBtn = stepBtn("›", 1)
        header.addView(nextBtn)

        val addBtn = TextView(host)
        addBtn.text = "＋"
        addBtn.setTextSize(18f)
        addBtn.gravity = Gravity.CENTER
        // ‹ › ＋ 三个符号字形在字体里高低不一，关掉字体 padding 让可见字形真正居中
        addBtn.setIncludeFontPadding(false)
        addBtn.setTextColor(host.getColor(R.color.brand))
        addBtn.contentDescription = "新增课程"
        addBtn.layoutParams = LinearLayout.LayoutParams(dp(40), dp(34))
        addBtn.setOnClickListener {
            CourseEditor.show(host, store, timetable, null, null,
                    Math.max(1, Math.min(7, Dates.dayOfWeek(
                            WeekMath.todayDateLabel()))),
                    CourseEditor.Callback { reload() })
        }
        header.addView(addBtn)

        col.addView(header, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── 网格 ──
        grid = TimetableGridView(host)
        grid.setOnCourseClickListener(object : TimetableGridView.OnCourseClickListener {
            override fun onCourseClick(course: Timetable.Course, session: Timetable.Session,
                                       day: Int, startSection: Int) {
                openDetail(course, session, day, startSection)
            }
        })
        // 点空格 = 就在这一格新建课程
        grid.setOnEmptyClickListener(object : TimetableGridView.OnEmptyClickListener {
            override fun onEmptyClick(dayOfWeek: Int, section: Int) {
                CourseEditor.show(host, store, timetable,
                        null, null, dayOfWeek, section, CourseEditor.Callback { reload() })
            }
        })
        // 左右滑 = 上一周 / 下一周：手指跟手位移 + 松手翻页过渡（不再是瞬切 + 硬弹一下）
        // 写成静态嵌套类：这套 d8 碰到匿名 / 非静态内部类会在 dex 化阶段内部报错
        val ws = WeekSwipe(this)
        weekSwipe = ws
        grid.setOnTouchListener(ws)
        col.addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 空态 ──
        emptyBox = LinearLayout(host)
        emptyBox.orientation = LinearLayout.VERTICAL
        emptyBox.gravity = Gravity.CENTER
        emptyBox.visibility = View.GONE
        val t1 = TextView(host)
        t1.text = "还没有课表"
        t1.setTextSize(16f)
        t1.typeface = Typeface.DEFAULT_BOLD
        t1.setTextColor(host.getColor(R.color.text_primary))
        t1.gravity = Gravity.CENTER
        val t2 = TextView(host)
        t2.text = "到「我的」里登录教务同步一次，之后没网也能看"
        t2.setTextSize(13f)
        t2.setTextColor(host.getColor(R.color.text_tertiary))
        t2.gravity = Gravity.CENTER
        emptyBox.addView(t1)
        emptyBox.addView(t2, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val elp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        elp.gravity = Gravity.CENTER
        addView(emptyBox, elp)

        // ── 右下角「回到本周」：只有翻到别的周才出现 ──
        // 页面根节点已被 MainActivity 加了 dockBand 的底部 padding，
        // 所以 BOTTOM 对齐天然落在胶囊栏上方，再留 12dp 呼吸空间。
        todayFab = TextView(host)
        todayFab.text = "回到本周"
        todayFab.setTextSize(12.5f)
        todayFab.typeface = Typeface.DEFAULT_BOLD
        todayFab.gravity = Gravity.CENTER
        todayFab.setTextColor(0xFFFFFFFF.toInt())
        val fabBg = GradientDrawable()
        fabBg.setColor(host.getColor(R.color.brand))
        fabBg.cornerRadius = dp(22).toFloat()
        todayFab.background = fabBg
        todayFab.setPadding(dp(16), 0, dp(16), 0)
        todayFab.elevation = dp(5).toFloat()
        todayFab.contentDescription = "回到本周"
        todayFab.visibility = View.GONE
        todayFab.alpha = 0f
        val flp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42))
        flp.gravity = Gravity.BOTTOM or Gravity.END
        flp.rightMargin = dp(18)
        flp.bottomMargin = dp(12)
        addView(todayFab, flp)
        todayFab.setOnClickListener { backToCurrentWeek() }

        reload()
    }

    private fun stepBtn(s: String, delta: Int): TextView {
        val v = TextView(host)
        v.text = s
        v.setTextSize(18f)
        v.gravity = Gravity.CENTER
        // 单引号字形（‹ ›）在字体里天然偏上，关掉字体 padding 才能光学居中
        v.setIncludeFontPadding(false)
        v.setTextColor(host.getColor(R.color.text_primary))
        v.contentDescription = if (delta < 0) "上一周" else "下一周"
        val lp = LinearLayout.LayoutParams(dp(36), dp(34))
        v.layoutParams = lp
        v.setOnClickListener {
            val next = week + delta
            if (next < 1 || next > totalWeeks) return@setOnClickListener
            val sw = weekSwipe
            if (sw != null) sw.flipTo(next) else setWeek(next)
        }
        return v
    }

    // ---------- 数据 ----------

    fun reload() {
        val t = store.loadTimetable()
        timetable = t
        schedule = store.schedule()
        if (t == null || t.courses.isEmpty()) {
            emptyBox.visibility = View.VISIBLE
            grid.visibility = View.GONE
            title.text = "课表"
            // 没有课表时周次切换毫无意义，藏起来别占位
            weekLabel.text = ""
            weekLabel.visibility = View.GONE
            prevBtn.visibility = View.GONE
            nextBtn.visibility = View.GONE
            hideFabNow()
            return
        }
        weekLabel.visibility = View.VISIBLE
        prevBtn.visibility = View.VISIBLE
        nextBtn.visibility = View.VISIBLE
        emptyBox.visibility = View.GONE
        grid.visibility = View.VISIBLE
        grid.setSchedule(schedule)

        startDate = t.semester.startDate
        totalWeeks = if (t.semester.totalWeeks <= 0) 20 else t.semester.totalWeeks
        val maxSection = ScheduleSettings.maxSectionUsed(t)
        schedule.ensureCovers(maxSection)
        grid.setSchedule(schedule)
        // 只在首次载入时定位到本周；之后切 tab / 编辑课程都保留用户正在看的那一周，
        // 否则「回到本周」按钮永远没机会出现（这也是底栏再点一次能回本周的配套行为）。
        if (!weekInitialized) {
            week = WeekMath.currentWeek(startDate, totalWeeks)
            weekInitialized = true
        }
        week = Math.max(1, Math.min(totalWeeks, week))
        setWeek(week)

        val name = t.semester.name
        title.text = if (name.isEmpty()) "课表" else shortName(name)
    }

    /** 点顶部周次 → 底部弹层铺开全部教学周，点一格直接跳过去。 */
    private fun openWeekPicker() {
        val holder = arrayOfNulls<Sheet.Handle>(1)

        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(4), dp(18), dp(14))

        val t = TextView(host)
        t.text = "选择周次"
        t.setTextSize(16f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(host.getColor(R.color.text_primary))
        t.setPadding(0, 0, 0, dp(4))
        box.addView(t)

        val hint = TextView(host)
        hint.text = "当前第 " + week + " 周 · 共 " + totalWeeks + " 周"
        hint.setTextSize(11.5f)
        hint.setTextColor(host.getColor(R.color.text_tertiary))
        hint.setPadding(0, 0, 0, dp(10))
        box.addView(hint)

        val cols = 5
        val rows = (totalWeeks + cols - 1) / cols
        for (r in 0 until rows) {
            val line = LinearLayout(host)
            line.orientation = LinearLayout.HORIZONTAL
            line.setPadding(0, dp(3), 0, dp(3))
            for (c in 0 until cols) {
                val w = r * cols + c + 1
                val cell = TextView(host)
                cell.gravity = Gravity.CENTER
                cell.setTextSize(13f)
                if (w <= totalWeeks) {
                    cell.text = w.toString()
                    val picked = w == week
                    val bg = GradientDrawable()
                    bg.cornerRadius = dp(10).toFloat()
                    if (picked) {
                        bg.setColor(host.getColor(R.color.brand))
                        cell.setTextColor(0xFFFFFFFF.toInt())
                        cell.typeface = Typeface.DEFAULT_BOLD
                    } else {
                        bg.setColor(if (store.darkNow()) 0x18FFFFFF else 0x140062CC)
                        cell.setTextColor(host.getColor(R.color.text_primary))
                    }
                    cell.background = bg
                    cell.setOnClickListener {
                        holder[0]?.dismiss()
                        val sw = weekSwipe
                        if (sw != null) sw.flipTo(w) else setWeek(w)
                    }
                } else {
                    cell.text = ""
                }
                val lp = LinearLayout.LayoutParams(0, dp(42), 1f)
                lp.leftMargin = if (c == 0) 0 else dp(6)
                line.addView(cell, lp)
            }
            box.addView(line)
        }
        holder[0] = Sheet.show(host, box,
                dp(96) + rows * dp(48))
    }

    private fun setWeek(w: Int) {
        week = w
        weekLabel.text = "第 " + w + " 周"
        val t = timetable
        val overlay = if (t == null)
            WeekOverlay.empty(w)
        else
            WeekOverlay.build(t, startDate, w)
        grid.setWeekDates(WeekMath.weekDates(startDate, w))
        grid.setData(t, overlay, w)
        updateTodayFab()
    }

    /** 教务「本周」的规范值（学期外由 WeekMath 夹到 1 / 总周数）。 */
    private fun currentWeek(): Int {
        val w = WeekMath.currentWeek(startDate, totalWeeks)
        return Math.max(1, Math.min(totalWeeks, w))
    }

    /** 只有翻到别的周才浮出「回到本周」，回本周自动淡出收起。 */
    private fun updateTodayFab() {
        val t = timetable
        val show = t != null && t.courses.isNotEmpty() && week != currentWeek()
        if (show == fabShown) return
        fabShown = show
        todayFab.animate().cancel()
        if (show) {
            todayFab.visibility = View.VISIBLE
            todayFab.scaleX = 0.82f
            todayFab.scaleY = 0.82f
            todayFab.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(230)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        } else {
            todayFab.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                .setDuration(160)
                .withEndAction { if (!fabShown) todayFab.visibility = View.GONE }
                .start()
        }
    }

    private fun hideFabNow() {
        fabShown = false
        todayFab.animate().cancel()
        todayFab.visibility = View.GONE
        todayFab.alpha = 0f
    }

    private fun backToCurrentWeek() {
        if (timetable == null) return
        val w = currentWeek()
        val sw = weekSwipe
        if (sw != null) sw.flipTo(w) else setWeek(w)
    }

    fun onShown() {
        reload()
    }

    /** 再点一次「课表」= 回到本周。 */
    fun jumpToToday() {
        backToCurrentWeek()
    }

    // ---------- 课程详情 ----------

    private fun openDetail(course: Timetable.Course, session: Timetable.Session, day: Int, startSection: Int) {
        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), 0, dp(22), dp(18))

        // ── 头部：课程色点 + 课名，下面一行「教师 · 周四 · 第 5-6 节」──
        val head = LinearLayout(host)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val dot = View(host)
        val g = GradientDrawable()
        g.setShape(GradientDrawable.OVAL)
        g.setColor(host.getColor(courseColorOf(course.colorIndex)))
        dot.background = g
        val dlp = LinearLayout.LayoutParams(dp(10), dp(10))
        dlp.rightMargin = dp(10)
        head.addView(dot, dlp)

        val name = TextView(host)
        name.text = course.name
        name.setTextSize(18f)
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(host.getColor(R.color.text_primary))
        head.addView(name, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(head)

        val teacher = session.teacherFor(week, course.teacher)
        val sub = StringBuilder()
        sub.append("周").append(arrayOf("一", "二", "三", "四", "五", "六", "日")[day - 1])
            .append("  ·  第").append(session.startSection)
        if (session.endSection != session.startSection) sub.append("-").append(session.endSection)
        sub.append(" 节")
        if (teacher.isNotEmpty()) sub.append("  ·  ").append(teacher)

        val subView = TextView(host)
        subView.text = sub.toString()
        subView.setTextSize(12.5f)
        subView.setTextColor(host.getColor(R.color.text_tertiary))
        subView.setPadding(0, dp(8), 0, dp(16))
        box.addView(subView)

        // ── 字段：一行一个，左标签右值（旧版的「分组」在这里按信息类型分组）──
        val info = LinearLayout(host)
        info.orientation = LinearLayout.VERTICAL
        info.background = host.getDrawable(R.drawable.card_bg)
        info.setPadding(dp(14), dp(6), dp(14), dp(6))

        info.addView(field("时间", schedule.startTime(session.startSection) +
                " - " + schedule.endTime(session.endSection)))
        if (session.campus.isNotEmpty()) info.addView(field("校区", session.campus))
        if (session.location.isNotEmpty()) info.addView(field("地点", session.location))
        if (session.weeksRaw.isNotEmpty()) info.addView(field("周次", session.weeksRaw))
        if (course.credit > 0) info.addView(field("学分", trimZero(course.credit)))
        if (course.note.isNotEmpty()) info.addView(field("备注", course.note))

        val sc = ScrollView(host)
        sc.addView(info)
        box.addView(sc, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 固定底部按钮 ──
        val edit = TextView(host)
        edit.text = "编辑这门课"
        edit.setTextSize(14f)
        edit.typeface = Typeface.DEFAULT_BOLD
        edit.gravity = Gravity.CENTER
        edit.setTextColor(host.getColor(R.color.brand))
        edit.background = host.getDrawable(R.drawable.btn_outline)
        val editLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        editLp.topMargin = dp(14)
        box.addView(edit, editLp)

        // 弹层高度按内容算：一行一个字段，加上头部和底部按钮，别留一大片空白
        var rows = 1
        if (session.campus.isNotEmpty()) rows++
        if (session.location.isNotEmpty()) rows++
        if (session.weeksRaw.isNotEmpty()) rows++
        if (course.credit > 0) rows++
        if (course.note.isNotEmpty()) rows++
        val sheetH = dp(150) + rows * dp(44)
        val cap = Math.round(resources.displayMetrics.heightPixels * 0.8f)
        val sheet = Sheet.show(host, box, Math.min(sheetH, cap))
        edit.setOnClickListener {
            sheet.dismiss()
            CourseEditor.show(host, store, timetable, course, session, day, CourseEditor.Callback { reload() })
        }
    }

    private fun field(label: String, value: String): LinearLayout {
        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(11), 0, dp(11))

        val l = TextView(host)
        l.text = label
        l.setTextSize(12.5f)
        l.setTextColor(host.getColor(R.color.text_tertiary))
        row.addView(l, LinearLayout.LayoutParams(dp(52),
                ViewGroup.LayoutParams.WRAP_CONTENT))

        val v = TextView(host)
        v.text = value
        v.setTextSize(13.5f)
        v.setTextColor(host.getColor(R.color.text_primary))
        row.addView(v, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun dp(v: Int): Int {
        return Math.round(v * resources.displayMetrics.density)
    }

    /**
     * 跟手的周次翻页。
     *
     * 旧版是「fling → 立刻 setWeek 重绘整张网格 → 从 60dp 处硬弹回 0」：重绘和位移撞在
     * 同一帧，看起来就是一顿。这里改成：
     *   MOVE 时按手指实时平移 + 轻微淡出（到边界加阻尼）；
     *   松手先滑出屏幕，在 alpha=0 的离屏阶段才做唯一一次 setData 换周，再从另一侧滑入。
     * 换周永远发生在看不见的时候，掉帧不会被眼睛捕捉到。
     *
     * 静态嵌套类 + page 字段，避开 d8 对 this$0 的内部错误。
     */
    private class WeekSwipe(private val page: TimetablePage) : View.OnTouchListener {

        private val detector: GestureDetector
        private val slop: Int
        private var downX = 0f
        private var downY = 0f
        private var lastDx = 0f
        var dragging = false
        private var animating = false

        init {
            slop = ViewConfiguration.get(page.context).scaledTouchSlop
            detector = GestureDetector(page.context, Fling(page, this))
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val t = page.timetable
            if (t == null || t.courses.isEmpty()) return false
            if (animating) return true // 翻页过程中吞掉事件，避免误触课程格
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    lastDx = 0f
                    dragging = false
                    detector.onTouchEvent(e)
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!dragging) {
                        detector.onTouchEvent(e)
                        // 只抢明显的横向手势，纵向滚动照旧交给网格
                        if (Math.abs(dx) < slop || Math.abs(dx) < Math.abs(dy) * 1.3f) {
                            return false
                        }
                        dragging = true
                        page.grid.setPressed(false)
                        page.grid.cancelLongPress()
                    }
                    lastDx = dx
                    drag(dx)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        page.grid.setPressed(false)
                        finishDrag(lastDx)
                        return true
                    }
                    detector.onTouchEvent(e)
                    return false
                }
                else -> {
                    detector.onTouchEvent(e)
                    return false
                }
            }
        }

        private fun drag(dx: Float) {
            val w = page.grid.width
            if (w <= 0) return
            val edge = (dx < 0 && page.week >= page.totalWeeks) ||
                    (dx > 0 && page.week <= 1)
            val d = dx * (if (edge) 0.28f else 0.82f)
            page.grid.translationX = d
            val p = Math.min(1f, Math.abs(d) / (w * 0.6f))
            page.grid.alpha = 1f - 0.28f * p
        }

        private fun finishDrag(dx: Float) {
            val w = page.grid.width.toFloat()
            val threshold = Math.max(page.dp(60).toFloat(), w * 0.20f)
            val dir = if (dx < 0) 1 else -1
            val target = page.week + dir
            if (Math.abs(dx) < threshold || target < 1 || target > page.totalWeeks) {
                springBack()
            } else {
                flip(dir, target)
            }
        }

        private fun springBack() {
            animating = true
            val g: View = page.grid
            g.animate().cancel()
            g.animate().translationX(0f).alpha(1f)
                .setDuration(260).setInterpolator(EASE_OUT)
                .withEndAction { animating = false }.start()
        }

        /** 按钮 / 周次格子 / 「回到本周」共用这一套过渡。 */
        fun flipTo(target: Int) {
            if (target == page.week || animating) return
            flip(if (target > page.week) 1 else -1, target)
        }

        fun flip(dir: Int, target: Int) {
            if (target < 1 || target > page.totalWeeks) {
                springBack()
                return
            }
            animating = true
            val g: View = page.grid
            g.animate().cancel()
            val w = if (g.width > 0) g.width.toFloat() else page.dp(320).toFloat()
            val out = -dir * w * 0.32f
            g.animate().translationX(out).alpha(0f)
                .setDuration(140).setInterpolator(EASE_IN)
                .withEndAction {
                    page.setWeek(target) // 离屏时换数据，只重绘一次
                    g.translationX = -out
                    g.alpha = 0f
                    g.animate().translationX(0f).alpha(1f)
                        .setDuration(300).setInterpolator(EASE_OUT)
                        .withEndAction { animating = false }.start()
                }.start()
        }

        companion object {
            private val EASE_OUT = PathInterpolator(0.22f, 0.8f, 0.30f, 1f)
            private val EASE_IN = PathInterpolator(0.4f, 0f, 0.8f, 0.4f)
        }
    }

    /** 快速甩动（MOVE 事件来不及形成拖拽）时的兜底翻页。 */
    private class Fling(private val page: TimetablePage, private val ctrl: WeekSwipe) :
            GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent,
                             velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null || ctrl.dragging) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (Math.abs(dx) < page.dp(52) || Math.abs(dx) < Math.abs(dy) * 1.2f) {
                return false
            }
            val dir = if (dx < 0) 1 else -1
            val target = page.week + dir
            if (target < 1 || target > page.totalWeeks) return true
            ctrl.flip(dir, target)
            return true
        }
    }

    companion object {
        /** 「2026-2027 学年第一学期」→「26-27 第一学期」：标题只剩一行的地方，年份不必写全 */
        private fun shortName(name: String): String {
            var s = name.replace("学年", "").replace(" ", "")
            val m = Pattern
                .compile("(\\d{4})-(\\d{4})").matcher(s)
            if (m.find()) {
                s = m.replaceFirst(m.group(1).substring(2) + "-" + m.group(2).substring(2))
            }
            return s
        }

        private fun trimZero(v: Double): String {
            return if (v == Math.floor(v)) v.toInt().toString() else v.toString()
        }

        private fun courseColorOf(idx: Int): Int {
            val ids = intArrayOf(R.color.course_0, R.color.course_1, R.color.course_2, R.color.course_3,
                    R.color.course_4, R.color.course_5, R.color.course_6, R.color.course_7)
            return ids[Math.abs(idx) % 8]
        }
    }
}
