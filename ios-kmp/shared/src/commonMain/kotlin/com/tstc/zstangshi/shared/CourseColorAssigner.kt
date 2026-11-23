package com.tstc.zstangshi.shared

import kotlin.math.abs

/**
 * 按规范化课程名统一分配色板索引（从 Android `data/CourseColorAssigner.kt` 移植）。
 *
 * 目标：同名课程（不同时段/周次）同色；同一学期内不同课程名不撞色；
 * 手动课程与用户改过色的课程保持原色，并算作「已占用」。
 */
object CourseColorAssigner {

    const val PALETTE_SIZE = 24

    fun assign(timetable: Timetable?): MutableMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        if (timetable == null) return out
        val used = BooleanArray(PALETTE_SIZE)
        val fixed = fixedColors(timetable, used)
        for ((name, color) in fixed) {
            for (c in timetable.courses) {
                if (isAuto(c) && normalizeName(c.name) == name) out[name] = color
            }
        }
        // 第一轮：已有颜色且未被别的课名占用 → 保持原色
        for (course in timetable.courses) {
            if (!isAuto(course)) continue
            val name = normalizeName(course.name)
            if (out.containsKey(name)) continue
            val idx = abs(course.colorIndex) % PALETTE_SIZE
            if (!used[idx]) {
                used[idx] = true
                out[name] = idx
            }
        }
        // 第二轮：撞色的课名换到第一个空闲颜色
        for (course in timetable.courses) {
            if (!isAuto(course)) continue
            val name = normalizeName(course.name)
            var idx = out[name]
            if (idx == null) {
                idx = firstFree(used, name)
                used[idx] = true
                out[name] = idx
            }
            course.colorIndex = idx
        }
        return out
    }

    fun dedupe(timetable: Timetable?) {
        if (timetable == null) return
        val used = BooleanArray(PALETTE_SIZE)
        val owner = HashMap<Int, String>()
        val fixed = fixedColors(timetable, used)
        for ((name, color) in fixed) if (!owner.containsKey(color)) owner[color] = name
        val decided = HashMap(fixed)
        for (course in timetable.courses) {
            if (!isAuto(course)) continue
            val name = normalizeName(course.name)
            var idx = decided[name]
            if (idx == null) {
                val want = abs(course.colorIndex) % PALETTE_SIZE
                val who = owner[want]
                idx = if (who == null || who == name) want else firstFree(used, name)
                used[idx] = true
                if (!owner.containsKey(idx)) owner[idx] = name
                decided[name] = idx
            }
            course.colorIndex = idx
        }
    }

    private fun isAuto(c: Course): Boolean = SOURCE_JWGLXT == c.source && !c.colorTouched

    private fun fixedColors(t: Timetable, used: BooleanArray): MutableMap<String, Int> {
        val fixed = HashMap<String, Int>()
        for (c in t.courses) {
            if (isAuto(c)) continue
            val idx = abs(c.colorIndex) % PALETTE_SIZE
            used[idx] = true
            val n = normalizeName(c.name)
            if (!fixed.containsKey(n)) fixed[n] = idx
        }
        return fixed
    }

    private fun firstFree(used: BooleanArray, name: String): Int {
        for (i in used.indices) if (!used[i]) return i
        return abs(name.hashCode()) % used.size
    }

    fun normalizeName(name: String?): String = (name ?: "")
        .replace('\u00A0', ' ')
        .replace('\u3000', ' ')
        .replace(Regex("\\s+"), "")
        .trim()
}
