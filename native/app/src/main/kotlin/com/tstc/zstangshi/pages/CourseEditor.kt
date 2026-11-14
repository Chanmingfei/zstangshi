package com.tstc.zstangshi.pages

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tstc.zstangshi.R
import com.tstc.zstangshi.data.ScheduleSettings
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.data.Timetable
import com.tstc.zstangshi.data.WeekRange
import com.tstc.zstangshi.ui.OptionSheet
import com.tstc.zstangshi.ui.Sheet
import com.tstc.zstangshi.widget.TodayWidget
import java.util.Collections
import java.util.LinkedHashSet

/**
 * 课程编辑器（新增 / 编辑 / 删除）。
 *
 * 版式沿用旧版网页里那张表：
 *   · 节次是两个「选择器」，选完在右边顺手显示 08:00-09:40，用户不用自己算；
 *   · 周次是 1..N 的格子直接勾（教务的周次本来就能是任意集合，起止 + 单双周表达不了），
 *     勾完显示「已选 N 周」，另给全选 / 单周 / 双周三个快捷；
 *   · 颜色是 8 个正圆，选中的加一圈描边；
 *   · 所有选择列表都走同一个圆角弹层，不用系统那个黑白 AlertDialog。
 */
class CourseEditor private constructor() {

    fun interface Callback {
        fun onSaved()
    }

    companion object {

        private val DAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        @JvmStatic
        fun show(host: Activity, store: Store, tt: Timetable?,
                 course: Timetable.Course?, session: Timetable.Session?,
                 day: Int, cb: Callback?) {
            show(host, store, tt, course, session, day, 0, cb)
        }

        /**
         * @param secAt 点空格进来时带上那一节的节次（0 表示不管，默认 1-2 节）
         */
        @JvmStatic
        fun show(host: Activity, store: Store, tt: Timetable?,
                 course: Timetable.Course?, session: Timetable.Session?,
                 day: Int, secAt: Int, cb: Callback?) {
            if (tt == null) {
                Toast.makeText(host, "先同步一次课表，再手工加课", Toast.LENGTH_SHORT).show()
                return
            }
            val creating = course == null
            val totalWeeks = if (tt.semester.totalWeeks <= 0) 20 else tt.semester.totalWeeks
            val target: Timetable.Course
            val ses: Timetable.Session

            if (course == null) {
                target = Timetable.Course()
                target.key = "custom_" + System.currentTimeMillis()
                target.source = Timetable.SOURCE_CUSTOM
                target.colorIndex = tt.courses.size % 8
                ses = Timetable.Session()
                ses.dayOfWeek = Math.max(1, Math.min(7, day))
                ses.startSection = if (secAt > 0) Math.min(secAt, ScheduleSettings.MAX_SECTIONS) else 1
                ses.endSection = Math.min(ScheduleSettings.MAX_SECTIONS, ses.startSection + 1)
                for (i in 1..Math.min(16, totalWeeks)) ses.weeks.add(i)
                ses.weeksRaw = "1-" + Math.min(16, totalWeeks) + "周"
            } else {
                target = course
                ses = if (session == null)
                    (if (course.sessions.isEmpty()) Timetable.Session() else course.sessions[0])
                else
                    session
            }

            val sch = store.schedule()
            val colorIndex = intArrayOf(target.colorIndex)
            val startSection = intArrayOf(ses.startSection)
            val endSection = intArrayOf(ses.endSection)
            val weekDay = intArrayOf(ses.dayOfWeek)
            val picked: MutableSet<Int> = LinkedHashSet(ses.weeks)

            val d = host.resources.displayMetrics.density
            val dp = Math.round(d)

            val card = LinearLayout(host)
            card.orientation = LinearLayout.VERTICAL

            // ── 标题 ──
            val head = LinearLayout(host)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.setPadding(dp * 22, dp * 6, dp * 14, dp * 6)

            val title = TextView(host)
            title.text = if (creating) "新增课程" else "编辑课程"
            title.setTextSize(17f)
            title.typeface = Typeface.DEFAULT_BOLD
            title.setTextColor(host.getColor(R.color.text_primary))
            head.addView(title, LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val close = TextView(host)
            close.text = "✕"
            close.setTextSize(15f)
            close.setTextColor(host.getColor(R.color.text_tertiary))
            close.setPadding(dp * 12, dp * 6, dp * 12, dp * 6)
            head.addView(close)
            card.addView(head)

            val sc = ScrollView(host)
            val body = LinearLayout(host)
            body.orientation = LinearLayout.VERTICAL
            body.setPadding(dp * 22, 0, dp * 22, 0)
            sc.addView(body)
            card.addView(sc, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            // ── 课程名 ──
            body.addView(label(host, "课程名"))
            val name = input(host, "如：高等数学", target.name)
            body.addView(name, match(dp * 8))

            // ── 教师 / 教室 ──
            val two = LinearLayout(host)
            two.orientation = LinearLayout.HORIZONTAL
            val left = LinearLayout(host)
            left.orientation = LinearLayout.VERTICAL
            val right = LinearLayout(host)
            right.orientation = LinearLayout.VERTICAL

            val teacher = input(host, "教师", if (ses.teacher.isEmpty()) target.teacher else ses.teacher)
            val room = input(host, "教室", ses.location)
            left.addView(label(host, "教师"))
            left.addView(teacher, match(0))
            right.addView(label(host, "教室"))
            right.addView(room, match(0))
            two.addView(left, LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val rightLp = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            rightLp.leftMargin = dp * 10
            two.addView(right, rightLp)
            body.addView(two, match(0))

            // ── 校区（教务给的是 xqmc，自己加的课也该能记） ──
            body.addView(label(host, "校区"))
            val campus = input(host, "如：东校区（可不填）", ses.campus)
            body.addView(campus, match(dp * 8))

            // ── 星期 ──
            body.addView(label(host, "星期"))
            val dayValue = valueRow(host, DAYS[weekDay[0] - 1])
            dayValue.setOnClickListener {
                OptionSheet.show(host, "星期几", DAYS, weekDay[0] - 1, object : OptionSheet.OnPick {
                    override fun onPick(index: Int) {
                        weekDay[0] = index + 1
                        dayValue.text = DAYS[index]
                    }
                })
            }
            body.addView(dayValue, match(0))

            // ── 节次：两个选择器 + 右边显示时间 ──
            body.addView(label(host, "节次"))
            val secRow = LinearLayout(host)
            secRow.orientation = LinearLayout.HORIZONTAL
            val fromValue = valueRow(host, "第 " + startSection[0] + " 节")
            val toValue = valueRow(host, "第 " + endSection[0] + " 节")
            val timeHint = TextView(host)
            timeHint.setTextSize(12f)
            timeHint.setTextColor(host.getColor(R.color.text_tertiary))
            timeHint.gravity = Gravity.CENTER

            val secOpts = Array(ScheduleSettings.MAX_SECTIONS) { i -> "第 " + (i + 1) + " 节" }
            fromValue.setOnClickListener {
                OptionSheet.show(host, "从第几节开始", secOpts, startSection[0] - 1,
                        object : OptionSheet.OnPick {
                            override fun onPick(index: Int) {
                                startSection[0] = index + 1
                                if (endSection[0] < startSection[0]) endSection[0] = startSection[0]
                                fromValue.text = "第 " + startSection[0] + " 节"
                                toValue.text = "第 " + endSection[0] + " 节"
                                timeHint.text = sch.startTime(startSection[0]) + " - " +
                                        sch.endTime(endSection[0])
                            }
                        })
            }
            toValue.setOnClickListener {
                val from = startSection[0]
                val ends = Array(ScheduleSettings.MAX_SECTIONS - from + 1) { i ->
                    "第 " + (from + i) + " 节"
                }
                OptionSheet.show(host, "上到第几节", ends, endSection[0] - from,
                        object : OptionSheet.OnPick {
                            override fun onPick(index: Int) {
                                endSection[0] = from + index
                                toValue.text = "第 " + endSection[0] + " 节"
                                timeHint.text = sch.startTime(startSection[0]) + " - " +
                                        sch.endTime(endSection[0])
                            }
                        })
            }
            timeHint.text = sch.startTime(startSection[0]) + " - " + sch.endTime(endSection[0])

            secRow.addView(fromValue, LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val toLp = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            toLp.leftMargin = dp * 8
            toLp.rightMargin = dp * 10
            secRow.addView(toValue, toLp)
            secRow.addView(timeHint)
            body.addView(secRow, match(0))

            // ── 周次：1..N 直接勾 ──
            body.addView(label(host, "上课周次"))
            val weekHint = TextView(host)
            weekHint.setTextSize(11.5f)
            weekHint.setTextColor(host.getColor(R.color.text_tertiary))

            val weekBox = LinearLayout(host)
            weekBox.orientation = LinearLayout.VERTICAL
            val cells = ArrayList<TextView>()
            val perRow = 7
            var row: LinearLayout? = null
            for (w in 1..totalWeeks) {
                if ((w - 1) % perRow == 0) {
                    val r = LinearLayout(host)
                    r.orientation = LinearLayout.HORIZONTAL
                    weekBox.addView(r, match(dp * 6))
                    row = r
                }
                val week = w
                val cell = weekCell(host, w, picked.contains(w))
                cell.setOnClickListener {
                    if (picked.contains(week)) picked.remove(week)
                    else picked.add(week)
                    paintWeekCell(cell, picked.contains(week), host)
                    weekHint.text = weeksSummary(picked)
                }
                cells.add(cell)
                val lp = LinearLayout.LayoutParams(0, dp * 36, 1f)
                lp.rightMargin = dp * 6
                row!!.addView(cell, lp)
            }
            body.addView(weekBox, match(0))

            // 快捷：全选 / 单周 / 双周
            val quick = LinearLayout(host)
            quick.orientation = LinearLayout.HORIZONTAL
            quick.addView(quickBtn(host, "全选", Runnable {
                picked.clear()
                for (w in 1..totalWeeks) picked.add(w)
                refreshWeeks(cells, picked, host, weekHint)
            }), LinearLayout.LayoutParams(0, dp * 32, 1f))
            val q1 = LinearLayout.LayoutParams(0, dp * 32, 1f)
            q1.leftMargin = dp * 8
            quick.addView(quickBtn(host, "单周", Runnable {
                picked.clear()
                var w = 1
                while (w <= totalWeeks) {
                    picked.add(w)
                    w += 2
                }
                refreshWeeks(cells, picked, host, weekHint)
            }), q1)
            val q2 = LinearLayout.LayoutParams(0, dp * 32, 1f)
            q2.leftMargin = dp * 8
            quick.addView(quickBtn(host, "双周", Runnable {
                picked.clear()
                var w = 2
                while (w <= totalWeeks) {
                    picked.add(w)
                    w += 2
                }
                refreshWeeks(cells, picked, host, weekHint)
            }), q2)
            val quickLp = match(0)
            quickLp.rightMargin = dp * 6
            body.addView(quick, quickLp)

            weekHint.text = weeksSummary(picked)
            val whLp = match(0)
            whLp.topMargin = dp * 8
            body.addView(weekHint, whLp)

            // ── 颜色：8 个正圆 ──
            body.addView(label(host, "颜色"))
            val colors = LinearLayout(host)
            colors.orientation = LinearLayout.HORIZONTAL
            val dots = ArrayList<View>()
            for (i in 0 until 8) {
                val idx = i
                val dot = View(host)
                dot.background = dotDrawable(host, idx, colorIndex[0] == idx)
                dot.setOnClickListener {
                    colorIndex[0] = idx
                    for (k in dots.indices) {
                        dots[k].background = dotDrawable(host, k, colorIndex[0] == k)
                    }
                }
                dots.add(dot)
                val lp = LinearLayout.LayoutParams(dp * 30, dp * 30)
                lp.rightMargin = dp * 10
                colors.addView(dot, lp)
            }
            val colorsLp = match(0)
            colorsLp.bottomMargin = dp * 4
            body.addView(colors, colorsLp)

            // ── 底部按钮 ──
            val actions = LinearLayout(host)
            actions.orientation = LinearLayout.HORIZONTAL
            actions.setPadding(dp * 22, dp * 10, dp * 22, dp * 16)

            val holder = arrayOfNulls<Sheet.Handle>(1)

            if (!creating) {
                val del = TextView(host)
                del.text = "删除"
                del.setTextSize(14f)
                del.gravity = Gravity.CENTER
                del.setTextColor(0xFFE0533F.toInt())
                del.background = round(host.getColor(R.color.bg_sunken), dp * 12)
                del.setOnClickListener {
                    OptionSheet.confirm(host, "删除这门课？",
                            target.name + "\n只会删掉当前的这一个时段，其他时段的课还在。",
                            "删除这个时段", true, Runnable {
                                target.sessions.remove(ses)
                                if (target.sessions.isEmpty()) tt.courses.remove(target)
                                save(host, store, tt, holder[0], cb)
                            })
                }
                actions.addView(del, LinearLayout.LayoutParams(0, dp * 46, 1f))
            }

            val cancel = TextView(host)
            cancel.text = "取消"
            cancel.setTextSize(14f)
            cancel.gravity = Gravity.CENTER
            cancel.setTextColor(host.getColor(R.color.text_secondary))
            cancel.background = round(host.getColor(R.color.bg_sunken), dp * 12)
            cancel.setOnClickListener {
                holder[0]?.dismiss()
            }
            val clp = LinearLayout.LayoutParams(0, dp * 46, 1f)
            clp.leftMargin = if (creating) 0 else dp * 10
            actions.addView(cancel, clp)

            val saveBtn = TextView(host)
            saveBtn.text = "保存"
            saveBtn.setTextSize(14f)
            saveBtn.typeface = Typeface.DEFAULT_BOLD
            saveBtn.gravity = Gravity.CENTER
            saveBtn.setTextColor(0xFFFFFFFF.toInt())
            saveBtn.background = round(host.getColor(R.color.brand), dp * 12)
            saveBtn.setOnClickListener {
                val nm = name.text.toString().trim()
                if (nm.isEmpty()) {
                    Toast.makeText(host, "课程名不能空着", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (picked.isEmpty()) {
                    Toast.makeText(host, "至少勾一周", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (endSection[0] < startSection[0]) endSection[0] = startSection[0]

                target.name = nm
                target.colorIndex = colorIndex[0]
                target.colorTouched = true
                val t = teacher.text.toString().trim()
                ses.teacher = t
                if (creating) target.teacher = t
                ses.location = room.text.toString().trim()
                ses.campus = campus.text.toString().trim()
                ses.dayOfWeek = weekDay[0]
                ses.startSection = startSection[0]
                ses.endSection = endSection[0]
                ses.weeks = ArrayList(picked)
                Collections.sort(ses.weeks)
                ses.weeksRaw = WeekRange.compress(ses.weeks) + "周"

                if (creating) {
                    target.sessions.add(ses)
                    tt.courses.add(target)
                }
                save(host, store, tt, holder[0], cb)
            }
            val slp = LinearLayout.LayoutParams(0, dp * 46, 1.4f)
            slp.leftMargin = dp * 10
            actions.addView(saveBtn, slp)

            card.addView(actions, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val sheetH = Math.round(host.resources.displayMetrics.heightPixels * 0.86f)
            holder[0] = Sheet.show(host, card, sheetH)
            close.setOnClickListener {
                holder[0]?.dismiss()
            }
        }

        // ---------- 保存 ----------

        private fun save(host: Activity, store: Store, tt: Timetable, sheet: Sheet.Handle?, cb: Callback?) {
            store.saveTimetable(tt)
            TodayWidget.refreshAll(host)
            if (sheet != null) sheet.dismiss()
            Toast.makeText(host, "已保存", Toast.LENGTH_SHORT).show()
            if (cb != null) cb.onSaved()
        }

        // ---------- 小控件 ----------

        private fun refreshWeeks(cells: List<TextView>, picked: Set<Int>,
                                 host: Activity, hint: TextView) {
            for (i in cells.indices) {
                paintWeekCell(cells[i], picked.contains(i + 1), host)
            }
            hint.text = weeksSummary(picked)
        }

        private fun weeksSummary(picked: Set<Int>): String {
            if (picked.isEmpty()) return "还没有选周次"
            val sorted = ArrayList(picked)
            Collections.sort(sorted)
            return "已选 " + sorted.size + " 周 · " +
                    WeekRange.compress(sorted) + "周"
        }

        private fun weekCell(host: Activity, week: Int, on: Boolean): TextView {
            val dp = Math.round(host.resources.displayMetrics.density)
            val v = TextView(host)
            v.text = week.toString()
            v.setTextSize(12.5f)
            v.gravity = Gravity.CENTER
            paintWeekCell(v, on, host)
            return v
        }

        private fun paintWeekCell(v: TextView, on: Boolean, host: Activity) {
            val dp = Math.round(host.resources.displayMetrics.density)
            val g = GradientDrawable()
            g.cornerRadius = (dp * 10).toFloat()
            g.setColor(host.getColor(if (on) R.color.brand else R.color.bg_sunken))
            v.background = g
            v.setTextColor(if (on) 0xFFFFFFFF.toInt() else host.getColor(R.color.text_secondary))
            v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        private fun quickBtn(host: Activity, text: String, onClick: Runnable): TextView {
            val dp = Math.round(host.resources.displayMetrics.density)
            val v = TextView(host)
            v.text = text
            v.setTextSize(12f)
            v.gravity = Gravity.CENTER
            v.setTextColor(host.getColor(R.color.brand))
            val g = GradientDrawable()
            g.cornerRadius = (dp * 10).toFloat()
            g.setColor(host.getColor(R.color.brand_soft))
            v.background = g
            v.setOnClickListener { onClick.run() }
            return v
        }

        private fun label(host: Activity, s: String): TextView {
            val dp = Math.round(host.resources.displayMetrics.density)
            val v = TextView(host)
            v.text = s
            v.setTextSize(12f)
            v.typeface = Typeface.DEFAULT_BOLD
            v.setTextColor(host.getColor(R.color.text_secondary))
            v.setPadding(dp * 2, dp * 14, 0, dp * 6)
            return v
        }

        private fun input(host: Activity, hint: String, value: String?): EditText {
            val dp = Math.round(host.resources.displayMetrics.density)
            val e = EditText(host)
            e.hint = hint
            e.setHintTextColor(host.getColor(R.color.text_tertiary))
            e.setTextColor(host.getColor(R.color.text_primary))
            e.setTextSize(14.5f)
            e.setText(if (value == null) "" else value)
            e.background = host.getDrawable(R.drawable.input_bg)
            e.setPadding(dp * 12, dp * 11, dp * 12, dp * 11)
            e.setSingleLine(true)
            return e
        }

        private fun valueRow(host: Activity, value: String): TextView {
            val dp = Math.round(host.resources.displayMetrics.density)
            val v = TextView(host)
            v.text = value
            v.setTextSize(14.5f)
            v.setTextColor(host.getColor(R.color.text_primary))
            v.background = host.getDrawable(R.drawable.input_bg)
            v.setPadding(dp * 12, dp * 11, dp * 12, dp * 11)
            v.setSingleLine(true)
            return v
        }

        private fun match(topMargin: Int): LinearLayout.LayoutParams {
            val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = topMargin
            return lp
        }

        /** 正圆：宽高都定死 30dp，别用 weight 拉成椭圆。 */
        private fun dotDrawable(host: Activity, index: Int, selected: Boolean): GradientDrawable {
            val d = host.resources.displayMetrics.density
            val g = GradientDrawable()
            g.setShape(GradientDrawable.OVAL)
            g.setSize(Math.round(30 * d), Math.round(30 * d))
            g.setColor(host.getColor(courseColor(index)))
            if (selected) {
                g.setStroke(Math.round(2.5f * d), host.getColor(R.color.text_primary))
            }
            return g
        }

        private fun round(color: Int, radius: Int): GradientDrawable {
            val g = GradientDrawable()
            g.setColor(color)
            g.cornerRadius = radius.toFloat()
            return g
        }

        private fun courseColor(idx: Int): Int {
            val ids = intArrayOf(R.color.course_0, R.color.course_1, R.color.course_2, R.color.course_3,
                    R.color.course_4, R.color.course_5, R.color.course_6, R.color.course_7)
            return ids[Math.abs(idx) % 8]
        }
    }
}
