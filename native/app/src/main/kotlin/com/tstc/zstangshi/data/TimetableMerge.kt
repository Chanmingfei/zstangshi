package com.tstc.zstangshi.data

/**
 * 教务课表与本地数据的合并。
 *
 * 口径（与「我的 → 同步课表」的说明一致）：
 *   - **教务是权威**：教务返回的课程一律以新数据为准，本地对教务课的修改会被覆盖；
 *   - **手工添加的课程永不删除**：`source=custom`（key 前缀 `custom_`）的课是用户自己录的，
 *     教务里本来就没有，同步时追加回去；
 *   - **手动调整（停课 / 调课 / 放假 / 补课）保留**：教务接口不返回这些，属于纯本地数据。
 *
 * 只用于「同一份账号的课表刷新」（同步按钮、开机自动同步）。
 * **切换账号是另一种口径：整体覆盖，不保留任何本地课程** —— 那种场景不要调这里。
 */
object TimetableMerge {

    /**
     * 把 [local] 里的手工课程与手动调整并进 [fresh]（教务新抓的课表），原地修改 [fresh]。
     *
     * @return 保留的手工课程数量（用于提示文案）
     */
    @JvmStatic
    fun keepManual(fresh: Timetable, local: Timetable?): Int {
        if (local == null) return 0

        val knownKeys = HashSet<String>()
        for (c in fresh.courses) {
            if (c.key.isNotEmpty()) knownKeys.add(c.key)
        }

        var kept = 0
        for (c in local.courses) {
            if (!isManual(c)) continue
            // 同一个 key 已存在（理论上不会，防御）
            if (c.key.isNotEmpty() && knownKeys.contains(c.key)) continue
            // 教务里已经有完全一样的课（同名 + 同节次），不再重复铺一份
            if (duplicatedIn(fresh, c)) continue
            fresh.courses.add(c)
            if (c.key.isNotEmpty()) knownKeys.add(c.key)
            kept++
        }

        // 停课 / 调课 / 放假 / 补课：教务不返回，属于本地数据，同步时保留
        for (a in local.adjusts) {
            if (fresh.adjusts.any { it.sameAs(a) }) continue
            if (a.id.isNotEmpty() && fresh.adjusts.any { it.id == a.id }) continue
            fresh.adjusts.add(a)
        }

        return kept
    }

    /** 用户手工录入的课程（兼容早期数据只写了 key 没写 source 的情况）。 */
    @JvmStatic
    fun isManual(c: Timetable.Course): Boolean {
        return c.source == Timetable.SOURCE_CUSTOM || c.key.startsWith("custom_")
    }

    /** [fresh] 里是否已有同名且同时段的课。 */
    private fun duplicatedIn(fresh: Timetable, c: Timetable.Course): Boolean {
        if (c.sessions.isEmpty()) return false
        for (g in fresh.courses) {
            if (g.name != c.name) continue
            for (gs in g.sessions) {
                for (cs in c.sessions) {
                    if (gs.dayOfWeek == cs.dayOfWeek &&
                        gs.startSection == cs.startSection &&
                        gs.endSection == cs.endSection
                    ) {
                        return true
                    }
                }
            }
        }
        return false
    }
}
