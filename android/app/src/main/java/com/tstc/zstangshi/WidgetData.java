package com.tstc.zstangshi;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * WidgetData —— 桌面小组件的数据仓（SharedPreferences 里的一条 JSON）
 *
 * 数据由 H5 侧 `web/data/widget.js` 算好、经 `NativeBridge.pushWidget(json)` 推进来。
 * 为什么要在原生再存一份：桌面小组件在 App 进程之外的渲染时机（开机、零点、上课铃响）
 * 拿不到 WebView 里的 localStorage，只能读磁盘。
 *
 * 三条硬约定：
 *   · 版本不认就整条作废（返回 null），宁可显示「打开 App 同步」也不画半截旧数据；
 *   · 解析全程 try/catch：桌面上抛异常会变成系统级「小组件已停止」，比不显示更糟；
 *   · 这里不做任何时间判断。哪节课「正在上」随时钟变，交给渲染时现算。
 */
final class WidgetData {

    private static final String TAG = "zsts-widget";
    private static final String PREFS = "zsts_widget";
    private static final String KEY = "today";

    /** 与 web/data/widget.js 的 WIDGET_VERSION 对齐 */
    static final int VERSION = 1;

    private WidgetData() {
    }

    /** 一门课（都是渲染直接要用的字段，不做二次加工） */
    static final class Course {
        String name = "";
        String location = "";
        String start = "--:--";
        String end = "--:--";
        String type = "required";
        String sectionsText = "";
        int from = 1;
    }

    /** 当天快照 */
    static final class Snapshot {
        boolean hasData;
        int weekNo;
        int dayOfWeek = 1;
        String weekday = "";
        String dateText = "";
        String updatedAt = "";
        final List<Course> courses = new ArrayList<>();
    }

    /** H5 推上来时调用。解析失败返回 false（不写坏数据，保留上一份能用的）。 */
    static boolean save(Context context, String json) {
        Snapshot parsed = parse(json);
        if (parsed == null) return false;
        try {
            prefs(context).edit().putString(KEY, String.valueOf(json)).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "快照写入失败", e);
            return false;
        }
    }

    /** 读快照；没有 / 坏数据 / 版本不符都返回 null。 */
    static Snapshot load(Context context) {
        try {
            String json = prefs(context).getString(KEY, null);
            return json == null ? null : parse(json);
        } catch (Exception e) {
            Log.w(TAG, "快照读取失败", e);
            return null;
        }
    }

    /** 清空（退出登录时没必要清，课表是本地数据；留给调试用） */
    static void clear(Context context) {
        try {
            prefs(context).edit().remove(KEY).apply();
        } catch (Exception ignored) {
            // 清不掉不影响使用
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 解析快照。任何字段缺失都退化到默认值，但版本不符一律作废。
     * @param json
     * @return null 表示这份数据不可用
     */
    static Snapshot parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("v", 0) != VERSION) {
                Log.w(TAG, "快照版本不匹配，忽略：" + root.optInt("v", 0));
                return null;
            }
            Snapshot out = new Snapshot();
            out.hasData = root.optBoolean("hasData", false);
            out.weekNo = root.optInt("weekNo", 0);
            out.dayOfWeek = Math.min(7, Math.max(1, root.optInt("dayOfWeek", 1)));
            out.weekday = root.optString("weekday", "");
            out.dateText = root.optString("dateText", "");
            out.updatedAt = root.optString("updatedAt", "");

            JSONArray array = root.optJSONArray("courses");
            if (array != null) {
                for (int i = 0; i < array.length(); i += 1) {
                    JSONObject row = array.optJSONObject(i);
                    if (row == null) continue;
                    Course course = new Course();
                    course.name = row.optString("name", "未命名课程");
                    course.location = row.optString("location", "");
                    course.start = normalizeTime(row.optString("start", ""), "--:--");
                    course.end = normalizeTime(row.optString("end", ""), "--:--");
                    // 字段名与 web/data/widget.js 的 courseType 一致；只下发类型名不下发色值，
                    // 深色模式的颜色由 values-night 的资源决定
                    course.type = row.optString("courseType", "required");
                    course.sectionsText = row.optString("sectionsText", "");
                    course.from = row.optInt("from", 1);
                    out.courses.add(course);
                }
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "快照解析失败", e);
            return null;
        }
    }

    /** 'HH:MM' 之外的一律当未知，避免渲染时 toMinutes 拿到 NaN 把整天算错 */
    private static String normalizeTime(String value, String fallback) {
        if (value == null) return fallback;
        String text = value.trim();
        if (text.length() != 5 || text.charAt(2) != ':') return fallback;
        try {
            int hh = Integer.parseInt(text.substring(0, 2));
            int mm = Integer.parseInt(text.substring(3, 5));
            if (hh < 0 || hh > 23 || mm < 0 || mm > 59) return fallback;
            return text;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
