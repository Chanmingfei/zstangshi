package com.tstc.zstangshi.widget

import android.content.Context
import com.tstc.zstangshi.R
import com.tstc.zstangshi.data.Dates
import com.tstc.zstangshi.data.DayPlan
import com.tstc.zstangshi.data.ScheduleSettings
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.data.WeekMath
import com.tstc.zstangshi.data.WidgetDay
import java.util.Calendar

/**
 * 小组件要显示的内容：把「课表 + 今天几点 + 作息」算成一张卡。
 *
 * 之前小组件是按「列表里第 1/2/3 门课」渲染的，行数写死，第 4 门以后永远轮不到；
 * 现在改成每次刷新都调 [WidgetDay.build]，它内部会先 `remaining(today, nowMin)` 把
 * 「已经下课的课」全部滤掉，再取前 cap 条 —— 所以第四门课会在第三门下课时
 * 自动补上来。刷新时刻由 [TodayWidget] 在「上课 / 下课」边界上触发。
 */
internal object WidgetData {

    class Snapshot {
        @JvmField var card: WidgetDay.Card = WidgetDay.Card()
        @JvmField var state: Int = WidgetDay.NO_TIMETABLE

        /** 下一次需要重画的时刻（今天第几分钟）；-1 = 今天不用再刷 */
        @JvmField var nextRefreshMin: Int = -1
    }

    fun load(c: Context, cap: Int): Snapshot {
        val store = Store(c)
        val snap = Snapshot()

        val t = store.loadTimetable()
        val today = WeekMath.todayDateLabel()
        val nowMin = nowMinute()

        if (t == null || t.courses.isEmpty()) {
            snap.state = WidgetDay.NO_TIMETABLE
            snap.card = WidgetDay.build(snap.state, today, 0, "", null, null, nowMin, cap)
            return snap
        }

        val start = t.semester.startDate
        val week = if (Dates.valid(start)) Dates.weekOf(start, today) else -1
        val total = if (t.semester.totalWeeks <= 0) 20 else t.semester.totalWeeks

        if (!Dates.valid(start)) {
            snap.state = WidgetDay.NO_START
            snap.card = WidgetDay.build(snap.state, today, 0, start, null, null, nowMin, cap)
            return snap
        }
        if (week < 1) {
            snap.state = WidgetDay.BEFORE
            snap.card = WidgetDay.build(snap.state, today, 0, start, null, null, nowMin, cap)
            return snap
        }
        if (week > total) {
            snap.state = WidgetDay.AFTER
            snap.card = WidgetDay.build(snap.state, today, 0, start, null, null, nowMin, cap)
            return snap
        }

        val sch = store.schedule()
        sch.ensureCovers(ScheduleSettings.maxSectionUsed(t))
        val n = ScheduleSettings.MAX_SECTIONS
        val secStart = IntArray(n + 1)
        val secEnd = IntArray(n + 1)
        for (i in 1..n) {
            secStart[i] = sch.startMinutes(i)
            secEnd[i] = sch.endMinutes(i)
        }

        val todayItems = DayPlan.of(t, start, today, secStart, secEnd)
        val tomorrowYmd = Dates.addDays(today, 1)
        val tomorrow = DayPlan.of(t, start, tomorrowYmd, secStart, secEnd)

        snap.state = WidgetDay.OK
        snap.card = WidgetDay.build(WidgetDay.OK, today, week, start, todayItems, tomorrow, nowMin, cap)
        // 下次刷新：最近一次上课/下课/课前 60 分钟；今天没有了就等第二天零点
        snap.nextRefreshMin = WidgetDay.nextRefreshMin(todayItems, nowMin)
        return snap
    }

    fun nowMinute(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** 颜色下标 → 色条资源 */
    fun barColorRes(idx: Int): Int {
        val ids = intArrayOf(
            R.color.course_0, R.color.course_1,
            R.color.course_2, R.color.course_3,
            R.color.course_4, R.color.course_5,
            R.color.course_6, R.color.course_7
        )
        return ids[Math.abs(idx) % 8]
    }
}
