package com.tstc.zstangshi.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import org.json.JSONObject

/**
 * 课表与设置的本地存储（SharedPreferences + JSON）。
 *
 * 设计要点（沿用旧版踩过的坑）：
 *   - 按学期分 key（`tt_2026-2027-1`），换学期不互相覆盖；
 *   - 只推进先写本地、再动 UI：教务抓回来的第一件事落盘，进程被杀也不丢；
 *   - 读的时候如果当前学期没有，就回退到「最近更新的一份」——这样冷启动
 *     即使不知道当前学期是哪个，也能直接进课表，不需要登录；
 *   - 开学日期（startDate）跟课表存在一起，不依赖内置校历。
 */
class Store(c: Context) {

    private val app: Context = c.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- 课表 ----------

    fun saveTimetable(t: Timetable?) {
        if (t?.semester == null) return
        try {
            val json = t.toJson().toString()
            sp.edit()
                .putString(keyOf(t.semester.id), json)
                .putString(KEY_ACTIVE, t.semester.id)
                .putString(KEY_START + t.semester.id, t.semester.startDate)
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .apply()
        } catch (ignored: Exception) {
        }
    }

    /** 当前学期（没记录就取最近更新过的那份）。 */
    fun loadTimetable(): Timetable? {
        val active = sp.getString(KEY_ACTIVE, "")
        val t = read(active)
        if (t != null && t.courses.isNotEmpty()) return t
        return loadLatest()
    }

    fun loadTimetable(semesterId: String?): Timetable? = read(semesterId)

    private fun read(semesterId: String?): Timetable? {
        if (semesterId.isNullOrEmpty()) return null
        val json = sp.getString(keyOf(semesterId), null) ?: return null
        return try {
            val t = Timetable.fromJson(JSONObject(json))
            val start = sp.getString(KEY_START + semesterId, "") ?: ""
            if (start.isNotEmpty()) t.semester.startDate = start
            t
        } catch (e: Exception) {
            null
        }
    }

    /** 所有存过的学期 id（按 key 排序，方便做学期切换）。 */
    fun semesters(): MutableList<String> {
        val out = ArrayList<String>()
        for (key in sp.all.keys) {
            if (key.startsWith(KEY_PREFIX)) {
                out.add(key.substring(KEY_PREFIX.length))
            }
        }
        out.sort()
        out.reverse()
        return out
    }

    /** 最近更新的一份（跨学期兜底：冷启动不知道当前学期时用它）。 */
    fun loadLatest(): Timetable? {
        var best: Timetable? = null
        var bestId: String? = null
        for (id in semesters()) {
            val t = read(id)
            if (t == null || t.courses.isEmpty()) continue
            if (best == null || (bestId != null && id > bestId)) {
                best = t
                bestId = id
            }
        }
        return best
    }

    fun hasLocal(): Boolean {
        val t = loadTimetable()
        return t != null && t.courses.isNotEmpty()
    }

    /** 切「当前学期」但不改数据（只挪 active 指针）。 */
    fun setActiveSemester(semesterId: String?) {
        if (semesterId.isNullOrEmpty()) return
        sp.edit().putString(KEY_ACTIVE, semesterId).apply()
    }

    fun activeSemester(): String = sp.getString(KEY_ACTIVE, "") ?: ""

    fun setStartDate(semesterId: String?, ymd: String?) {
        if (semesterId.isNullOrEmpty()) return
        sp.edit().putString(KEY_START + semesterId, ymd ?: "").apply()
        val t = read(semesterId)
        if (t != null) {
            t.semester.startDate = ymd ?: ""
            saveTimetable(t)
        }
    }

    fun startDate(semesterId: String?): String =
        sp.getString(KEY_START + (semesterId ?: ""), "") ?: ""

    fun lastSync(): Long = sp.getLong(KEY_LAST_SYNC, 0)

    // ---------- 设置 ----------

    fun sectionCount(): Int = sp.getInt(KEY_SECTION_COUNT, ScheduleSettings.DEFAULT_SECTION_COUNT)

    fun setSectionCount(n: Int) {
        val tmp = ScheduleSettings.createDefault()
        tmp.setSectionCount(n)
        sp.edit().putInt(KEY_SECTION_COUNT, tmp.sectionCount()).apply()
    }

    fun firstStart(): String =
        sp.getString(KEY_FIRST_START, ScheduleSettings.DEFAULT_FIRST_CLASS_START)
            ?: ScheduleSettings.DEFAULT_FIRST_CLASS_START

    fun setFirstStart(hm: String?) {
        sp.edit().putString(KEY_FIRST_START, hm).apply()
    }

    fun schedule(): ScheduleSettings {
        val s = ScheduleSettings.createDefault()
        s.firstClassStart = firstStart()
        s.setSectionCount(sectionCount())
        s.ensureCovers(s.sectionCount())
        return s
    }

    fun nightMode(): Boolean = sp.getBoolean(KEY_NIGHT, false)

    fun setNightMode(on: Boolean) {
        sp.edit().putBoolean(KEY_NIGHT, on).apply()
    }

    // ---------- 外观（浅色 / 深色 / 跟随系统） ----------

    /** 是否要看深色那套资源：明确选深色 → 深；明确选浅色 → 浅；跟随系统 → 看系统 uiMode。 */
    fun darkNow(): Boolean {
        val m = themeMode()
        if (m == THEME_DARK) return true
        if (m == THEME_LIGHT) return false
        // 跟随系统：读「交付给本 App 的配置」里的夜间位。
        // 注意不能用 Resources.getSystem() —— 很多国产 ROM 的深色模式不会动 framework 的
        // 全局 uiMode，但会给 App 下发的配置设正确的夜间位；用 app 上下文读才拿得到。
        val sys = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return sys == Configuration.UI_MODE_NIGHT_YES
    }

    fun themeMode(): Int {
        if (!sp.contains(KEY_THEME)) {
            // 老版本只有一个「深色模式」开关，升级上来按它落位，不跳变
            return if (sp.getBoolean(KEY_NIGHT, false)) THEME_DARK else THEME_SYSTEM
        }
        return sp.getInt(KEY_THEME, THEME_SYSTEM)
    }

    fun setThemeMode(mode: Int) {
        val m = if (mode == THEME_LIGHT || mode == THEME_DARK) mode else THEME_SYSTEM
        sp.edit().putInt(KEY_THEME, m)
            .putBoolean(KEY_NIGHT, m == THEME_DARK)
            .apply()
    }

    // ---------- 登录信息 ----------

    fun user(): String = sp.getString(KEY_LOGIN_USER, "") ?: ""

    fun pass(): String = sp.getString(KEY_LOGIN_PASS, "") ?: ""

    fun saveLogin(user: String?, pass: String?) {
        sp.edit().putString(KEY_LOGIN_USER, user).putString(KEY_LOGIN_PASS, pass).apply()
    }

    /** 只记学号不记密码（「记住密码」没勾时用），下次打开登录页只预填学号。 */
    fun saveUser(user: String?) {
        sp.edit().putString(KEY_LOGIN_USER, user).remove(KEY_LOGIN_PASS).apply()
    }

    fun clearLogin() {
        sp.edit().remove(KEY_LOGIN_USER).remove(KEY_LOGIN_PASS)
            .remove(KEY_STUDENT_NAME).remove(KEY_NAME_OWNER).apply()
    }

    /**
     * 教务个人信息页抓到的姓名。姓名绑定学号存取：
     * 换了账号旧姓名一律视为不存在，界面回退显示学号，登录时再重新抓。
     */
    fun studentName(owner: String?): String {
        val n = sp.getString(KEY_STUDENT_NAME, "") ?: ""
        return if (owner != null && owner == (sp.getString(KEY_NAME_OWNER, "") ?: "")) n else ""
    }

    fun setStudentName(name: String?, owner: String?) {
        if (name == null || name.trim().isEmpty()) return
        sp.edit().putString(KEY_STUDENT_NAME, name.trim())
            .putString(KEY_NAME_OWNER, owner ?: "").apply()
    }

    /**
     * 最近一次教务登录成功的时间。功能页用它判断「本次登录会话抓过没有」：
     * 快照时间早于登录时间 = 这个会话还没抓过，进页面时补一次，之后只认下拉刷新。
     */
    fun loginAt(): Long = sp.getLong(KEY_LOGIN_AT, 0L)

    fun setLoginAt(t: Long) {
        sp.edit().putLong(KEY_LOGIN_AT, t).apply()
    }

    /**
     * 上次真正连得上的教务协议（"http" / "https"）。
     * 校外移动数据下 443 常常直接超时，每次都从 https 试会白等一轮超时。
     */
    fun originScheme(): String = sp.getString(KEY_ORIGIN, "") ?: ""

    fun setOriginScheme(scheme: String?) {
        if (scheme == null) return
        sp.edit().putString(KEY_ORIGIN, scheme).apply()
    }

    /** 记住密码（勾了才会把密码落盘）。 */
    fun rememberPwd(): Boolean = sp.getBoolean(KEY_REMEMBER, pass().isNotEmpty())

    fun setRememberPwd(on: Boolean) {
        sp.edit().putBoolean(KEY_REMEMBER, on).apply()
    }

    /** 自动登录：冷启动直接进加载页，登录成功后跳主界面。 */
    fun autoLogin(): Boolean = sp.getBoolean(KEY_AUTO_LOGIN, false)

    fun setAutoLogin(on: Boolean) {
        sp.edit().putBoolean(KEY_AUTO_LOGIN, on).apply()
    }

    /** 清课表（保留登录与设置）。 */
    fun clearTimetable() {
        val e = sp.edit()
        for (id in semesters()) e.remove(keyOf(id))
        e.remove(KEY_ACTIVE).apply()
    }

    // ---------- 教务功能页缓存（成绩 / 绩点 / 选课） ----------

    /**
     * 进页先显示本地快照，不联网；只有下拉刷新或第一次没快照才去教务取数。
     * 这样「单次页面只加载一次」，返回、重进、切页都不会重复打接口。
     */
    fun saveFeature(type: String, json: String?) {
        sp.edit().putString("feature_$type", json)
            .putLong("feature_${type}_at", System.currentTimeMillis()).apply()
    }

    fun feature(type: String): String = sp.getString("feature_$type", "") ?: ""

    fun featureAt(type: String): Long = sp.getLong("feature_${type}_at", 0L)

    companion object {
        private const val FILE = "zsts"
        private const val KEY_PREFIX = "tt_"
        private const val KEY_ACTIVE = "active_semester"
        private const val KEY_START = "startDate_"
        private const val KEY_LOGIN_USER = "login_user"
        private const val KEY_LOGIN_PASS = "login_pass"
        private const val KEY_STUDENT_NAME = "student_name"
        private const val KEY_NAME_OWNER = "student_name_owner"
        private const val KEY_LOGIN_AT = "login_at"
        private const val KEY_ORIGIN = "origin_scheme"
        private const val KEY_REMEMBER = "remember_pwd"
        private const val KEY_AUTO_LOGIN = "auto_login"
        private const val KEY_SECTION_COUNT = "section_count"
        private const val KEY_FIRST_START = "first_start"
        private const val KEY_NIGHT = "night_mode"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LAST_SYNC = "last_sync"

        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_SYSTEM = 2

        private fun keyOf(semesterId: String?): String = KEY_PREFIX + (semesterId ?: "")
    }
}
