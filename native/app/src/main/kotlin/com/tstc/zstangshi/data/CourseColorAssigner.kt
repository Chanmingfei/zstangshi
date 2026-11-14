package com.tstc.zstangshi.data

/**
 * 按规范化课程名统一分配色板索引。
 *
 * 同名课程（不同时段/不同周次）拿到同一颜色；不同课程名在同一学期内
 * 绝不撞色（色板 24 色，超过 24 门不同课才不得不复用）。手动课程和用户
 * 改过颜色的课程保持原色，并且算作「已占用」，自动分配会绕开它们。
 */
object CourseColorAssigner {

    /** 与 TimetableView.PALETTE_BG 保持同长度；数据层不依赖 UI 类 */
    const val PALETTE_SIZE = 24

    /**
     * 对课表内全部课程重新分配 colorIndex。
     * 返回映射（课程名 → 颜色索引），供调用方把同一配色同步到其他学期。
     */
    @JvmStatic
    fun assign(timetable: Timetable?): MutableMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        if (timetable?.courses == null) return out
        val used = BooleanArray(PALETTE_SIZE)
        val fixed = fixedColors(timetable, used)
        // 和手动设过颜色的同名课保持一致
        for ((name, color) in fixed) {
            for (c in timetable.courses) {
                if (isAuto(c) && normalizeName(c.name) == name) out[name] = color
            }
        }
        // 第一轮：已有颜色、而且这个颜色在本学期还没被别的课名占用 → 保持原色不动。
        // 这样同步回来的课程顺序变了、增删了一门课，其余课的颜色都不会跟着挪。
        for (course in timetable.courses) {
            if (!isAuto(course)) continue
            val name = normalizeName(course.name)
            if (out.containsKey(name)) continue
            val idx = Math.abs(course.colorIndex) % PALETTE_SIZE
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

    /** 新建手动课程时的默认颜色：本学期还没人用的第一个颜色。 */
    @JvmStatic
    fun firstFree(timetable: Timetable?): Int {
        val used = BooleanArray(PALETTE_SIZE)
        val courses = timetable?.courses
        if (courses != null) {
            for (c in courses) used[Math.abs(c.colorIndex) % PALETTE_SIZE] = true
        }
        val n = courses?.size ?: 0
        return firstFree(used, "#$n")
    }

    /**
     * 同一学期里不同课程名撞色时，把后出现的自动课程挪到空闲颜色上。
     * 跨学期统一配色会因为总课程名超过色板而循环，这一步保证单个学期内依旧不撞。
     */
    @JvmStatic
    fun dedupe(timetable: Timetable?) {
        if (timetable?.courses == null) return
        val used = BooleanArray(PALETTE_SIZE)
        val owner = HashMap<Int, String>()
        val fixed = fixedColors(timetable, used)
        for ((name, color) in fixed) owner.putIfAbsent(color, name)
        val decided = HashMap(fixed)
        for (course in timetable.courses) {
            if (!isAuto(course)) continue
            val name = normalizeName(course.name)
            var idx = decided[name]
            if (idx == null) {
                val want = Math.abs(course.colorIndex) % PALETTE_SIZE
                val who = owner[want]
                idx = if (who == null || who == name) want else firstFree(used, name)
                used[idx] = true
                owner.putIfAbsent(idx, name)
                decided[name] = idx
            }
            course.colorIndex = idx
        }
    }

    private fun isAuto(c: Timetable.Course): Boolean =
        Timetable.SOURCE_JWGLXT == c.source && !c.colorTouched

    /** 手动课程 / 用户改过色的课程：颜色不动，标记为已占用。返回 课程名 → 颜色。 */
    private fun fixedColors(t: Timetable, used: BooleanArray): MutableMap<String, Int> {
        val fixed = HashMap<String, Int>()
        for (c in t.courses) {
            if (isAuto(c)) continue
            val idx = Math.abs(c.colorIndex) % PALETTE_SIZE
            used[idx] = true
            fixed.putIfAbsent(normalizeName(c.name), idx)
        }
        return fixed
    }

    private fun firstFree(used: BooleanArray, name: String): Int {
        for (i in used.indices) if (!used[i]) return i
        return Math.abs(name.hashCode()) % used.size
    }

    /** 把已知的课程名配色应用到一个学期，未命中的名字保持原样。 */
    @JvmStatic
    fun applyKnown(timetable: Timetable?, knownColors: Map<String, Int>?) {
        if (timetable?.courses == null || knownColors == null) return
        for (course in timetable.courses) {
            if (Timetable.SOURCE_JWGLXT != course.source) continue
            if (course.colorTouched) continue
            val idx = knownColors[normalizeName(course.name)]
            if (idx != null) course.colorIndex = idx
        }
        dedupe(timetable)
    }

    @JvmStatic
    fun normalizeName(name: String?): String = (name ?: "")
        .replace('\u00A0', ' ')
        .replace('\u3000', ' ')
        .replace(Regex("\\s+"), "")
        .trim()
}
