package com.tstc.zstangshi.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * 全局作息配置：第 1 节开始时间、每节时长、每节课后休息。
 *
 * 时间按顺序顺延计算：第 N 节开始 = 第 1 节开始 + 前面所有节的时长与课间休息。
 */
class ScheduleSettings {

    class Section {
        @JvmField var section: Int = 0
        @JvmField var durationMinutes: Int = 45
        @JvmField var breakAfterMinutes: Int = 0

        constructor()

        constructor(section: Int, durationMinutes: Int, breakAfterMinutes: Int) {
            this.section = section
            this.durationMinutes = durationMinutes
            this.breakAfterMinutes = breakAfterMinutes
        }

        @Throws(JSONException::class)
        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("section", section)
            o.put("durationMinutes", durationMinutes)
            o.put("breakAfterMinutes", breakAfterMinutes)
            return o
        }
    }

    @JvmField var firstClassStart: String = DEFAULT_FIRST_CLASS_START

    /** 每天显示几节（MIN_SECTIONS..MAX_SECTIONS）。 */
    @JvmField var sectionCount: Int = DEFAULT_SECTION_COUNT
    @JvmField var sections: MutableList<Section> = ArrayList()

    init {
        resetToDefaults()
    }

    fun resetToDefaults() {
        firstClassStart = DEFAULT_FIRST_CLASS_START
        sectionCount = DEFAULT_SECTION_COUNT
        sections.clear()
        for (i in 0 until MAX_SECTIONS) {
            sections.add(Section(i + 1, DEFAULT_DURATIONS[i], DEFAULT_BREAKS[i]))
        }
    }

    operator fun get(sectionNumber: Int): Section = section(sectionNumber)

    fun section(sectionNumber: Int): Section {
        val idx = Math.max(0, Math.min(MAX_SECTIONS - 1, sectionNumber - 1))
        if (sections.size < MAX_SECTIONS) normalizeSections()
        return sections[idx]
    }

    fun startMinutes(sectionNumber: Int): Int {
        var first = parseHm(firstClassStart)
        if (first < 0) first = parseHm(DEFAULT_FIRST_CLASS_START)
        var minutes = first
        for (i in 1 until sectionNumber) {
            val s = section(i)
            minutes += Math.max(1, s.durationMinutes)
            minutes += Math.max(0, s.breakAfterMinutes)
        }
        return minutes
    }

    fun endMinutes(sectionNumber: Int): Int =
        startMinutes(sectionNumber) + Math.max(1, section(sectionNumber).durationMinutes)

    fun startTime(sectionNumber: Int): String = formatHm(startMinutes(sectionNumber))

    fun endTime(sectionNumber: Int): String = formatHm(endMinutes(sectionNumber))

    /** 按每节起止（分钟，下标 = 节次）回写成「第 1 节开始 + 时长 + 课间」。 */
    fun applyRanges(start: IntArray, end: IntArray) {
        normalizeSections()
        val n = Math.min(MAX_SECTIONS, Math.min(start.size, end.size) - 1)
        if (n < 1) return
        if (n > sectionCount) sectionCount = clamp(n, MIN_SECTIONS, MAX_SECTIONS)
        firstClassStart = formatHm(start[1])
        for (i in 1..n) {
            val s = section(i)
            s.durationMinutes = Math.max(1, end[i] - start[i])
            if (i < n) s.breakAfterMinutes = Math.max(0, start[i + 1] - end[i])
        }
    }

    fun sectionCount(): Int = clamp(sectionCount, MIN_SECTIONS, MAX_SECTIONS)

    fun setSectionCount(n: Int) {
        sectionCount = clamp(n, MIN_SECTIONS, MAX_SECTIONS)
    }

    /**
     * 导入的课表用到了更靠后的节次（比如第 12 节）时自动扩到那一节；只会变多不会变少。
     * 返回是否改动。
     */
    fun ensureCovers(maxSection: Int): Boolean {
        val want = clamp(maxSection, MIN_SECTIONS, MAX_SECTIONS)
        if (want <= sectionCount()) return false
        normalizeSections()
        // 原来最后一节后面没留课间的话，补一个默认 10 分钟，免得新加的节次贴着上一节
        for (i in sectionCount() until want) {
            val prev = section(i)
            if (prev.breakAfterMinutes == 0) prev.breakAfterMinutes = 10
        }
        sectionCount = want
        return true
    }

    fun rangeLabel(sectionNumber: Int): String =
        startTime(sectionNumber) + "-" + endTime(sectionNumber)

    @Throws(JSONException::class)
    fun toJson(): JSONObject {
        normalizeSections()
        val o = JSONObject()
        o.put("schemaVersion", SCHEMA_VERSION)
        o.put("firstClassStart", firstClassStart)
        o.put("sectionCount", sectionCount())
        val arr = JSONArray()
        for (s in sections) arr.put(s.toJson())
        o.put("sections", arr)
        return o
    }

    /** 分享码里用的紧凑作息：f=第 1 节开始，n=每天几节，d=每节时长，b=课后休息（只写前 n 节）。 */
    @Throws(JSONException::class)
    fun toShare(): JSONObject {
        normalizeSections()
        val o = JSONObject()
        o.put("f", firstClassStart)
        val n = sectionCount()
        o.put("n", n)
        val d = JSONArray()
        val b = JSONArray()
        for (i in 1..n) {
            d.put(section(i).durationMinutes)
            b.put(section(i).breakAfterMinutes)
        }
        o.put("d", d)
        o.put("b", b)
        return o
    }

    /** 两份作息在前 max(两者节数) 节上的上下课时间是否完全一样。 */
    fun sameTimesAs(other: ScheduleSettings?): Boolean {
        if (other == null) return false
        val n = Math.max(sectionCount(), other.sectionCount())
        for (i in 1..n) {
            if (startMinutes(i) != other.startMinutes(i) || endMinutes(i) != other.endMinutes(i)) return false
        }
        return true
    }

    private fun normalizeSections() {
        while (sections.size < MAX_SECTIONS) {
            val i = sections.size
            sections.add(Section(i + 1, DEFAULT_DURATIONS[i], DEFAULT_BREAKS[i]))
        }
        if (sections.size > MAX_SECTIONS) {
            sections = ArrayList(sections.subList(0, MAX_SECTIONS))
        }
        for (i in sections.indices) {
            val s = sections[i]
            s.section = i + 1
            s.durationMinutes = clamp(s.durationMinutes, 1, 300)
            s.breakAfterMinutes = clamp(s.breakAfterMinutes, 0, 600)
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1

        /** 每天最多几节：不同学校从 8 节到 16 节不等，作息表按最大值存，sectionCount 决定显示几节。 */
        const val MAX_SECTIONS = 16
        const val MIN_SECTIONS = 8

        /** 唐山师范学院标准作息：上午 4 节、下午 4 节、晚上 4 节，共 12 节。 */
        const val DEFAULT_SECTION_COUNT = 12
        const val DEFAULT_FIRST_CLASS_START = "08:00"

        /** 每节统一 45 分钟。 */
        private val DEFAULT_DURATIONS = intArrayOf(
            45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45, 45
        )

        /**
         * 每节课后休息（分钟），下标 = 节次-1。依据唐山师范学院标准作息时间表：
         * 第1节08:00-08:45(休5)→第2节08:50-09:35(休20)→第3节09:55-10:40(休5)→第4节10:45-11:30(午休150→14:00)
         * 第5节14:00-14:45(休5)→第6节14:50-15:35(休20)→第7节15:55-16:40(休5)→第8节16:45-17:30(休5)→第9节17:35-18:20(晚餐40→19:00)
         * 第10节19:00-19:45(休5)→第11节19:50-20:35(休5)→第12节20:40-21:25(休0)
         */
        private val DEFAULT_BREAKS = intArrayOf(
            5, 20, 5, 150, 5, 20, 5, 5, 40, 5, 5, 0, 10, 10, 10, 0
        )

        @JvmStatic
        fun createDefault(): ScheduleSettings = ScheduleSettings()

        /** 课表里用到的最大节次（没有课返回 0）。 */
        @JvmStatic
        fun maxSectionUsed(t: Timetable?): Int {
            var max = 0
            val courses = t?.courses ?: return 0
            for (c in courses) {
                for (s in c.sessions) {
                    max = Math.max(max, Math.max(s.startSection, s.endSection))
                }
            }
            return Math.min(MAX_SECTIONS, max)
        }

        /** 解析分享码里的作息；格式不对返回 null。没写到的节次沿用默认值。 */
        @JvmStatic
        fun fromShare(o: JSONObject?): ScheduleSettings? {
            if (o == null || parseHm(o.optString("f", "")) < 0) return null
            val d = o.optJSONArray("d")
            val b = o.optJSONArray("b")
            if (d == null || b == null || d.length() == 0) return null
            val s = createDefault()
            s.firstClassStart = o.optString("f")
            s.sectionCount = clamp(o.optInt("n", d.length()), MIN_SECTIONS, MAX_SECTIONS)
            for (i in 0 until Math.min(MAX_SECTIONS, d.length())) {
                val x = s.sections[i]
                x.durationMinutes = clamp(d.optInt(i, x.durationMinutes), 1, 300)
                if (i < b.length()) x.breakAfterMinutes = clamp(b.optInt(i, x.breakAfterMinutes), 0, 600)
            }
            return s
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun fromJson(json: String): ScheduleSettings = fromJson(JSONObject(json))

        @JvmStatic
        @Throws(JSONException::class)
        fun fromJson(o: JSONObject?): ScheduleSettings {
            val ver = o?.optInt("schemaVersion", 0) ?: 0
            if (ver > SCHEMA_VERSION) {
                throw JSONException("作息配置版本 $ver 高于当前支持版本 $SCHEMA_VERSION")
            }

            val out = createDefault()
            if (o == null) return out

            val first = o.optString("firstClassStart", DEFAULT_FIRST_CLASS_START)
            if (parseHm(first) >= 0) out.firstClassStart = first
            out.sectionCount = clamp(o.optInt("sectionCount", DEFAULT_SECTION_COUNT), MIN_SECTIONS, MAX_SECTIONS)

            val arr = o.optJSONArray("sections")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val so = arr.optJSONObject(i) ?: continue
                    val number = so.optInt("section", i + 1)
                    if (number < 1 || number > MAX_SECTIONS) continue
                    val s = out.sections[number - 1]
                    s.durationMinutes = clamp(so.optInt("durationMinutes", s.durationMinutes), 1, 300)
                    s.breakAfterMinutes = clamp(so.optInt("breakAfterMinutes", s.breakAfterMinutes), 0, 600)
                }
            }
            return out
        }

        @JvmStatic
        fun parseHm(hm: String?): Int {
            if (hm == null) return -1
            val p = hm.trim().split(":")
            if (p.size != 2) return -1
            return try {
                val h = p[0].toInt()
                val m = p[1].toInt()
                if (h < 0 || h > 23 || m < 0 || m > 59) return -1
                h * 60 + m
            } catch (e: NumberFormatException) {
                -1
            }
        }

        @JvmStatic
        fun formatHm(minutes: Int): String {
            val day = 24 * 60
            val m = ((minutes % day) + day) % day
            return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
        }

        private fun clamp(v: Int, min: Int, max: Int): Int = Math.max(min, Math.min(max, v))
    }
}
