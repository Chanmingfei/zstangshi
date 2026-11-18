package com.tstc.zstangshi.notif

import com.tstc.zstangshi.data.Dates
import com.tstc.zstangshi.data.DayPlan
import com.tstc.zstangshi.data.ScheduleSettings
import com.tstc.zstangshi.data.Timetable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 课程焦点通知的「提醒计划」。
 *
 * 输入：课表 + 开学日 + 作息 + 提前分钟数 + 当前时刻。
 * 输出：此刻应当发布的一条（或几条）通知，以及下一次需要醒来重算的时刻。
 *
 * 三个阶段与 Android 实况通知的倒计时终点（deadline）对应：
 *   UPCOMING  即将上课 —— 从「上课前 N 分钟」开始，倒计时到上课
 *   IN_CLASS  上课中   —— 倒计时到**本节**下课（多节连上时每节单独计）
 *   BREAK     课间     —— 多节连上的中间休息，倒计时到下一节上课
 *
 * 数据源复用 [DayPlan.of]，因此停课/调课/放假/补课都会被自然算进去。
 */
object FocusPlan {

    enum class Phase(val label: String, val countdown: String) {
        UPCOMING("即将上课", "距上课"),
        IN_CLASS("上课中", "距下课"),
        BREAK("课间", "距继续上课"),
    }

    class Reminder {
        @JvmField var key: String = ""
        @JvmField var title: String = ""
        /** 地点：默认总是显示（不做「显不显示地点」开关）。 */
        @JvmField var place: String = ""
        /** 节次 + 时间 + 补课/调课标签，用于通知展开后的详情行。 */
        @JvmField var detail: String = ""
        @JvmField var phase: Phase = Phase.UPCOMING
        /** 倒计时终点（epoch millis）。 */
        @JvmField var deadline: Long = 0L
        /** 这节课整体结束时刻，用于「划掉后本节不再提醒」的过期判断。 */
        @JvmField var lessonEnd: Long = 0L
    }

    class Plan {
        @JvmField var reminders: MutableList<Reminder> = ArrayList()
        /** 下一次需要重算的时刻（epoch millis），0 表示没有后续事件。 */
        @JvmField var nextBoundary: Long = 0L
        @JvmField var untimed: Int = 0
    }

    @JvmStatic
    fun build(t: Timetable?, start: String?, sch: ScheduleSettings?, leadMinutes: Int, now: Long): Plan {
        val plan = Plan()
        if (t == null || sch == null || !Dates.valid(start)) return plan

        sch.ensureCovers(ScheduleSettings.maxSectionUsed(t))
        val max = ScheduleSettings.MAX_SECTIONS
        val secStart = IntArray(max + 1)
        val secEnd = IntArray(max + 1)
        for (i in 1..max) {
            secStart[i] = sch.startMinutes(i)
            secEnd[i] = sch.endMinutes(i)
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayYmd = sdf.format(Date(now))
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrowYmd = sdf.format(cal.time)

        var next = 0L
        val consider = fun(boundary: Long) {
            if (boundary > now && (next == 0L || boundary < next)) next = boundary
        }

        for (ymd in arrayOf(todayYmd, tomorrowYmd)) {
            val base = dayStartMillis(ymd)
            if (base <= 0L) continue
            val items = DayPlan.of(t, start, ymd, secStart, secEnd)
            for (item in items) {
                if (item.startMin < 0 || item.endMin <= item.startMin) {
                    plan.untimed++
                    continue
                }
                val startMs = base + item.startMin * 60000L
                val endMs = base + item.endMin * 60000L
                if (now >= endMs) continue

                val noticeStart = startMs - leadMinutes * 60000L
                consider(noticeStart)
                consider(startMs)
                consider(endMs)

                val hasSections = item.startSection in 1..max && item.endSection in 1..max
                if (hasSections) {
                    for (sec in item.startSection..item.endSection) {
                        consider(base + secStart[sec] * 60000L)
                        consider(base + secEnd[sec] * 60000L)
                    }
                }
                if (now < noticeStart) continue

                val r = Reminder()
                r.key = ymd + "|" + item.name + "|" + item.startMin + "|" + item.endMin
                r.title = item.name
                r.place = item.placeLabel()
                r.detail = listOf(item.sections, item.timeLabel(), item.tag)
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                r.lessonEnd = endMs

                if (now < startMs) {
                    r.phase = Phase.UPCOMING
                    r.deadline = startMs
                } else {
                    var phase = Phase.IN_CLASS
                    var deadline = endMs
                    if (hasSections) {
                        var matched = false
                        for (sec in item.startSection..item.endSection) {
                            val s = base + secStart[sec] * 60000L
                            val e = base + secEnd[sec] * 60000L
                            if (now < s) {
                                // 上一节已下课、下一节还没开始 → 课间
                                phase = Phase.BREAK
                                deadline = s
                                matched = true
                                break
                            }
                            if (now < e) {
                                phase = Phase.IN_CLASS
                                deadline = e
                                matched = true
                                break
                            }
                        }
                        if (!matched) continue
                    }
                    r.phase = phase
                    r.deadline = deadline
                }
                plan.reminders.add(r)
            }
        }

        plan.reminders.sortBy { it.deadline }
        plan.nextBoundary = next
        return plan
    }

    /** "yyyy-MM-dd" → 当天 00:00 的 epoch millis；非法返回 0。 */
    private fun dayStartMillis(ymd: String): Long {
        try {
            val p = ymd.split("-")
            if (p.size != 3) return 0L
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        } catch (e: Exception) {
            return 0L
        }
    }
}
