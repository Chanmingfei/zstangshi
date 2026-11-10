package com.tstc.zstangshi.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 课表与设置的本地存储（SharedPreferences + JSON）。
 *
 * 设计要点（沿用旧版踩过的坑）：
 *   - 按学期分 key（{@code tt_2026-2027-1}），换学期不互相覆盖；
 *   - 只推进先写本地、再动 UI：教务抓回来的第一件事落盘，进程被杀也不丢；
 *   - 读的时候如果当前学期没有，就回退到「最近更新的一份」——这样冷启动
 *     即使不知道当前学期是哪个，也能直接进课表，不需要登录；
 *   - 开学日期（startDate）跟课表存在一起，不依赖内置校历。
 */
public final class Store {

    private static final String FILE = "zsts";
    private static final String KEY_PREFIX = "tt_";
    private static final String KEY_ACTIVE = "active_semester";
    private static final String KEY_START = "startDate_";
    private static final String KEY_LOGIN_USER = "login_user";
    private static final String KEY_LOGIN_PASS = "login_pass";
    private static final String KEY_STUDENT_NAME = "student_name";
    private static final String KEY_NAME_OWNER = "student_name_owner";
    private static final String KEY_LOGIN_AT = "login_at";
    private static final String KEY_ORIGIN = "origin_scheme";
    private static final String KEY_REMEMBER = "remember_pwd";
    private static final String KEY_AUTO_LOGIN = "auto_login";
    private static final String KEY_SECTION_COUNT = "section_count";
    private static final String KEY_FIRST_START = "first_start";
    private static final String KEY_NIGHT = "night_mode";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_LAST_SYNC = "last_sync";

    private final SharedPreferences sp;
    private final Context app;

    public Store(Context c) {
        app = c.getApplicationContext();
        sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String keyOf(String semesterId) {
        return KEY_PREFIX + (semesterId == null ? "" : semesterId);
    }

    // ---------- 课表 ----------

    public void saveTimetable(Timetable t) {
        if (t == null || t.semester == null) return;
        try {
            String json = t.toJson().toString();
            sp.edit()
                    .putString(keyOf(t.semester.id), json)
                    .putString(KEY_ACTIVE, t.semester.id)
                    .putString(KEY_START + t.semester.id, t.semester.startDate)
                    .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** 当前学期（没记录就取最近更新过的那份）。 */
    public Timetable loadTimetable() {
        String active = sp.getString(KEY_ACTIVE, "");
        Timetable t = read(active);
        if (t != null && !t.courses.isEmpty()) return t;
        return loadLatest();
    }

    public Timetable loadTimetable(String semesterId) {
        return read(semesterId);
    }

    private Timetable read(String semesterId) {
        if (semesterId == null || semesterId.isEmpty()) return null;
        String json = sp.getString(keyOf(semesterId), null);
        if (json == null) return null;
        try {
            Timetable t = Timetable.fromJson(new JSONObject(json));
            String start = sp.getString(KEY_START + semesterId, "");
            if (!start.isEmpty()) t.semester.startDate = start;
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** 所有存过的学期 id（按 key 排序，方便做学期切换）。 */
    public List<String> semesters() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_PREFIX)) {
                out.add(e.getKey().substring(KEY_PREFIX.length()));
            }
        }
        Collections.sort(out);
        Collections.reverse(out);
        return out;
    }

    /** 最近更新的一份（跨学期兜底：冷启动不知道当前学期时用它）。 */
    public Timetable loadLatest() {
        Timetable best = null;
        String bestId = null;
        for (String id : semesters()) {
            Timetable t = read(id);
            if (t == null || t.courses.isEmpty()) continue;
            if (best == null || id.compareTo(bestId) > 0) {
                best = t;
                bestId = id;
            }
        }
        return best;
    }

    public boolean hasLocal() {
        Timetable t = loadTimetable();
        return t != null && !t.courses.isEmpty();
    }

    /** 切「当前学期」但不改数据（只挪 active 指针）。 */
    public void setActiveSemester(String semesterId) {
        if (semesterId == null || semesterId.isEmpty()) return;
        sp.edit().putString(KEY_ACTIVE, semesterId).apply();
    }

    public String activeSemester() {
        return sp.getString(KEY_ACTIVE, "");
    }

    public void setStartDate(String semesterId, String ymd) {
        if (semesterId == null || semesterId.isEmpty()) return;
        sp.edit().putString(KEY_START + semesterId, ymd == null ? "" : ymd).apply();
        Timetable t = read(semesterId);
        if (t != null) {
            t.semester.startDate = ymd == null ? "" : ymd;
            saveTimetable(t);
        }
    }

    public String startDate(String semesterId) {
        return sp.getString(KEY_START + (semesterId == null ? "" : semesterId), "");
    }

    public long lastSync() {
        return sp.getLong(KEY_LAST_SYNC, 0);
    }

    // ---------- 设置 ----------

    public int sectionCount() {
        return sp.getInt(KEY_SECTION_COUNT, ScheduleSettings.DEFAULT_SECTION_COUNT);
    }

    public void setSectionCount(int n) {
        ScheduleSettings tmp = ScheduleSettings.createDefault();
        tmp.setSectionCount(n);
        sp.edit().putInt(KEY_SECTION_COUNT, tmp.sectionCount()).apply();
    }

    public String firstStart() {
        return sp.getString(KEY_FIRST_START, ScheduleSettings.DEFAULT_FIRST_CLASS_START);
    }

    public void setFirstStart(String hm) {
        sp.edit().putString(KEY_FIRST_START, hm).apply();
    }

    public ScheduleSettings schedule() {
        ScheduleSettings s = ScheduleSettings.createDefault();
        s.firstClassStart = firstStart();
        s.setSectionCount(sectionCount());
        s.ensureCovers(s.sectionCount());
        return s;
    }

    public boolean nightMode() {
        return sp.getBoolean(KEY_NIGHT, false);
    }

    public void setNightMode(boolean on) {
        sp.edit().putBoolean(KEY_NIGHT, on).apply();
    }

    // ---------- 外观（浅色 / 深色 / 跟随系统） ----------

    public static final int THEME_LIGHT = 0, THEME_DARK = 1, THEME_SYSTEM = 2;

    /** 是否要看深色那套资源：明确选深色 → 深；明确选浅色 → 浅；跟随系统 → 看系统 uiMode。 */
    public boolean darkNow() {
        int m = themeMode();
        if (m == THEME_DARK) return true;
        if (m == THEME_LIGHT) return false;
        // 跟随系统：读「交付给本 App 的配置」里的夜间位。
        // 注意不能用 Resources.getSystem() —— 很多国产 ROM 的深色模式不会动 framework 的
        // 全局 uiMode，但会给 App 下发的配置设正确的夜间位；用 app 上下文读才拿得到。
        int sys = app.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return sys == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public int themeMode() {
        if (!sp.contains(KEY_THEME)) {
            // 老版本只有一个「深色模式」开关，升级上来按它落位，不跳变
            return sp.getBoolean(KEY_NIGHT, false) ? THEME_DARK : THEME_SYSTEM;
        }
        return sp.getInt(KEY_THEME, THEME_SYSTEM);
    }

    public void setThemeMode(int mode) {
        int m = mode == THEME_LIGHT || mode == THEME_DARK ? mode : THEME_SYSTEM;
        sp.edit().putInt(KEY_THEME, m)
                .putBoolean(KEY_NIGHT, m == THEME_DARK)
                .apply();
    }

    // ---------- 登录信息 ----------

    public String user() {
        return sp.getString(KEY_LOGIN_USER, "");
    }

    public String pass() {
        return sp.getString(KEY_LOGIN_PASS, "");
    }

    public void saveLogin(String user, String pass) {
        sp.edit().putString(KEY_LOGIN_USER, user).putString(KEY_LOGIN_PASS, pass).apply();
    }

    /** 只记学号不记密码（「记住密码」没勾时用），下次打开登录页只预填学号。 */
    public void saveUser(String user) {
        sp.edit().putString(KEY_LOGIN_USER, user).remove(KEY_LOGIN_PASS).apply();
    }

    public void clearLogin() {
        sp.edit().remove(KEY_LOGIN_USER).remove(KEY_LOGIN_PASS)
                .remove(KEY_STUDENT_NAME).remove(KEY_NAME_OWNER).apply();
    }

    /**
     * 教务个人信息页抓到的姓名。姓名绑定学号存取：
     * 换了账号旧姓名一律视为不存在，界面回退显示学号，登录时再重新抓。
     */
    public String studentName(String owner) {
        String n = sp.getString(KEY_STUDENT_NAME, "");
        return owner != null && owner.equals(sp.getString(KEY_NAME_OWNER, "")) ? n : "";
    }

    public void setStudentName(String name, String owner) {
        if (name == null || name.trim().isEmpty()) return;
        sp.edit().putString(KEY_STUDENT_NAME, name.trim())
                .putString(KEY_NAME_OWNER, owner == null ? "" : owner).apply();
    }

    /**
     * 最近一次教务登录成功的时间。功能页用它判断「本次登录会话抓过没有」：
     * 快照时间早于登录时间 = 这个会话还没抓过，进页面时补一次，之后只认下拉刷新。
     */
    public long loginAt() {
        return sp.getLong(KEY_LOGIN_AT, 0L);
    }

    public void setLoginAt(long t) {
        sp.edit().putLong(KEY_LOGIN_AT, t).apply();
    }

    /**
     * 上次真正连得上的教务协议（"http" / "https"）。
     * 校外移动数据下 443 常常直接超时，每次都从 https 试会白等一轮超时。
     */
    public String originScheme() {
        return sp.getString(KEY_ORIGIN, "");
    }

    public void setOriginScheme(String scheme) {
        if (scheme == null) return;
        sp.edit().putString(KEY_ORIGIN, scheme).apply();
    }

    /** 记住密码（勾了才会把密码落盘）。 */
    public boolean rememberPwd() {
        return sp.getBoolean(KEY_REMEMBER, !pass().isEmpty());
    }

    public void setRememberPwd(boolean on) {
        sp.edit().putBoolean(KEY_REMEMBER, on).apply();
    }

    /** 自动登录：冷启动直接进加载页，登录成功后跳主界面。 */
    public boolean autoLogin() {
        return sp.getBoolean(KEY_AUTO_LOGIN, false);
    }

    public void setAutoLogin(boolean on) {
        sp.edit().putBoolean(KEY_AUTO_LOGIN, on).apply();
    }

    /** 清课表（保留登录与设置）。 */
    public void clearTimetable() {
        SharedPreferences.Editor e = sp.edit();
        for (String id : semesters()) e.remove(keyOf(id));
        e.remove(KEY_ACTIVE).apply();
    }

    // ---------- 教务功能页缓存（成绩 / 绩点 / 选课） ----------

    /**
     * 进页先显示本地快照，不联网；只有下拉刷新或第一次没快照才去教务取数。
     * 这样「单次页面只加载一次」，返回、重进、切页都不会重复打接口。
     */
    public void saveFeature(String type, String json) {
        sp.edit().putString("feature_" + type, json)
                .putLong("feature_" + type + "_at", System.currentTimeMillis()).apply();
    }

    public String feature(String type) {
        return sp.getString("feature_" + type, "");
    }

    public long featureAt(String type) {
        return sp.getLong("feature_" + type + "_at", 0L);
    }
}
