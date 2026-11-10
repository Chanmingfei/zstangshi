package com.tstc.zstangshi.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.tstc.zstangshi.R;

import com.tstc.zstangshi.data.Dates;
import com.tstc.zstangshi.data.ScheduleSettings;
import com.tstc.zstangshi.data.Timetable;
import com.tstc.zstangshi.data.WeekMath;
import com.tstc.zstangshi.data.WeekOverlay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课表网格（自绘）。
 *
 * 为什么要自绘而不拼 View：一屏最多 7×16 = 112 个格子，用 View 会叠出上百个
 * onMeasure/onLayout；自绘一次 canvas 就完事，周次切换也不用重建视图树。
 *
 * 数据从 {@link Timetable} 直接取，按「星期 + 起止节次」聚合：
 *   - 同一格只有一门课 → 铺满整格
 *   - 同一格有多门课（真冲突）→ 横向平分，各自一个窄块，不互相遮挡
 *   - WeekOverlay 里标了停课 / 调走的时段不画
 *
 * 顶部星期行 + 左侧节次栏（节次号 + 起止时间）都画在同一张画布上，
 * 所以「两端铺满屏幕」这件事由 grid 自己保证，不依赖外层 padding。
 */
public class TimetableGridView extends View {

    public interface OnCourseClickListener {
        void onCourseClick(Timetable.Course course, Timetable.Session session, int day, int startSection);
    }

    public interface OnEmptyClickListener {
        void onEmptyClick(int dayOfWeek, int section);
    }

    private static final String[] DAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    private Timetable timetable;
    private ScheduleSettings schedule = ScheduleSettings.createDefault();
    private WeekOverlay overlay;
    private int week = 1;
    private boolean night;

    /** 画出来的块，用来做点击命中（绘制顺序即优先级，后画的在上） */
    private final List<Block> blocks = new ArrayList<>();
    private OnCourseClickListener courseListener;
    private OnEmptyClickListener emptyListener;
    private float downX, downY;

    private final float headH;
    private final float slotW;

    private static final class Block {
        RectF r = new RectF();
        Timetable.Course course;
        Timetable.Session session;
        int day, start;
    }

    public TimetableGridView(Context context) {
        this(context, null);
    }

    public TimetableGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        headH = 34f * density;
        slotW = 46f * density;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT);
    }

    public void setOnCourseClickListener(OnCourseClickListener l) {
        courseListener = l;
    }

    public void setOnEmptyClickListener(OnEmptyClickListener l) {
        emptyListener = l;
    }

    public void setSchedule(ScheduleSettings s) {
        if (s != null) schedule = s;
        invalidate();
    }

    public void setData(Timetable t, WeekOverlay o, int week) {
        this.timetable = t;
        this.overlay = o;
        this.week = week;
        invalidate();
    }

    public int week() {
        return week;
    }

    public void setNight(boolean night) {
        this.night = night;
        invalidate();
    }

    // ---------- 几何 ----------

    private float gridTop() {
        return headH;
    }

    private float cellW() {
        return (getWidth() - slotW) / 7f;
    }

    private float cellH() {
        int n = Math.max(1, schedule.sectionCount());
        return (getHeight() - gridTop()) / (float) n;
    }

    /** 某天某节的矩形（day 1..7，section 1..n） */
    private void cellRect(int day, int startSection, int endSection, RectF out) {
        float cw = cellW(), ch = cellH();
        float left = slotW + (day - 1) * cw;
        float top = gridTop() + (startSection - 1) * ch;
        out.set(left, top, left + cw, top + (endSection - startSection + 1) * ch);
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        blocks.clear();

        int bgPage = night ? 0xFF0F1520 : getResources().getColor(R.color.bg_page, null);
        int surface = night ? 0xFF1A2230 : getResources().getColor(R.color.bg_surface, null);
        int line = night ? 0x1FFFFFFF : getResources().getColor(R.color.border_subtle, null);
        int tMain = night ? 0xFFF2F5FA : getResources().getColor(R.color.text_primary, null);
        int tSub = night ? 0xFF8B93A5 : getResources().getColor(R.color.text_tertiary, null);

        canvas.drawColor(bgPage);

        float cw = cellW(), ch = cellH();
        int n = Math.max(1, schedule.sectionCount());

        // 星期头（滚动区之上不滚动，直接跟着画）
        p.setColor(surface);
        rect.set(0, 0, w, headH);
        canvas.drawRect(rect, p);
        text.setColor(tMain);
        text.setTextSize(12f * density);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        int todayDow = Dates.dayOfWeek(WeekMath.todayDateLabel());
        for (int i = 1; i <= 7; i++) {
            float cx = slotW + (i - 0.5f) * cw;
            boolean isToday = i == todayDow;
            text.setColor(isToday ? getResources().getColor(R.color.brand, null) : tMain);
            canvas.drawText("周" + DAY_NAMES[i - 1], cx, headH * 0.52f, text);
            text.setTextSize(9.5f * density);
            text.setColor(isToday ? getResources().getColor(R.color.brand, null) : tSub);
            String d = weekDates != null && weekDates.length >= i ? weekDates[i - 1] : "";
            if (!d.isEmpty()) canvas.drawText(d, cx, headH * 0.86f, text);
            text.setTextSize(12f * density);
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }

        // 斑马纹：偶数节次铺一层极淡的底（旧版就是这样，读起来不会串行）
        p.setColor(night ? 0x0DFFFFFF : 0x08000000);
        for (int i = 0; i < n; i++) {
            if (i % 2 == 1) continue;
            float top = gridTop() + i * ch;
            canvas.drawRect(slotW, top, w, top + ch, p);
        }

        // 网格线
        p.setColor(line);
        p.setStrokeWidth(Math.max(1f, density * 0.6f));
        for (int i = 0; i <= n; i++) {
            float y = gridTop() + i * ch;
            canvas.drawLine(0, y, w, y, p);
        }
        for (int i = 0; i <= 7; i++) {
            float x = slotW + i * cw;
            canvas.drawLine(x, gridTop(), x, h, p);
        }

        // 节次栏
        text.setTypeface(Typeface.DEFAULT);
        for (int s = 1; s <= n; s++) {
            float top = gridTop() + (s - 1) * ch;
            text.setTextSize(11f * density);
            text.setColor(tMain);
            canvas.drawText(String.valueOf(s), slotW / 2f, top + ch * 0.42f, text);
            text.setTextSize(7.5f * density);
            text.setColor(tSub);
            canvas.drawText(schedule.startTime(s), slotW / 2f, top + ch * 0.68f, text);
            canvas.drawText(schedule.endTime(s), slotW / 2f, top + ch * 0.86f, text);
        }

        // 课程块
        if (timetable != null) drawCourses(canvas);

        // 空课：不画任何东西（旧版会画一个「+」，用户明确说过无课时不显示卡片）
    }

    private String[] weekDates;

    public void setWeekDates(String[] dates) {
        this.weekDates = dates;
    }

    private void drawCourses(Canvas canvas) {
        // 按「星期 + 起止」聚合
        Map<String, List<Object[]>> grouped = new LinkedHashMap<>();
        for (Timetable.Course c : timetable.courses) {
            for (Timetable.Session s : c.sessions) {
                if (!s.isActive(week)) continue;
                if (overlay != null) {
                    WeekOverlay.Mark m = overlay.cancelled.get(s);
                    if (m != null) continue;
                }
                String key = s.dayOfWeek + ":" + s.startSection + "-" + s.endSection;
                List<Object[]> list = grouped.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    grouped.put(key, list);
                }
                list.add(new Object[]{c, s});
            }
        }

        float cw = cellW();
        float inset = 1.5f * density;
        float radius = 9f * density;

        for (Map.Entry<String, List<Object[]>> e : grouped.entrySet()) {
            List<Object[]> list = e.getValue();
            int count = list.size();
            for (int i = 0; i < count; i++) {
                Timetable.Course c = (Timetable.Course) list.get(i)[0];
                Timetable.Session s = (Timetable.Session) list.get(i)[1];
                int idx = Math.abs(c.colorIndex) % 8;

                RectF r = new RectF();
                cellRect(s.dayOfWeek, s.startSection, s.endSection, r);
                float partW = r.width() / count;
                r.left += i * partW + inset;
                r.right = r.left + partW - inset * 2f;
                r.top += inset;
                r.bottom -= inset;

                int soft = getResources().getColor(courseSoft(idx), null);
                p.setColor(night ? withAlpha(soft, 0x2E) : soft);
                canvas.drawRoundRect(r, radius, radius, p);
                // 左侧色条
                p.setColor(getResources().getColor(courseColor(idx), null));
                rect.set(r.left, r.top, r.left + 2.5f * density, r.bottom);
                canvas.drawRoundRect(rect, 1.5f * density, 1.5f * density, p);

                // 课程名用课程色（与色条同色），比深墨色更容易一眼认出是哪门课
                text.setTextAlign(Paint.Align.LEFT);
                text.setTypeface(Typeface.DEFAULT_BOLD);
                text.setTextSize(10f * density);
                text.setColor(getResources().getColor(courseColor(idx), null));
                float tx = r.left + 6f * density;
                float maxW = r.width() - 9f * density;
                List<String> lines = wrap(c.name, maxW, 2);
                float ty = r.top + 12f * density;
                for (String ln : lines) {
                    canvas.drawText(ln, tx, ty, text);
                    ty += 11f * density;
                }

                // 教室（块高够才画）
                if (r.height() > 34f * density && !s.location.isEmpty()) {
                    text.setTypeface(Typeface.DEFAULT);
                    text.setTextSize(8.5f * density);
                    text.setColor(getResources().getColor(R.color.text_secondary, null));
                    List<String> loc = wrap(s.location, maxW, 1);
                    if (!loc.isEmpty()) canvas.drawText(loc.get(0), tx, ty + 1f * density, text);
                }
                text.setTextAlign(Paint.Align.CENTER);

                Block b = new Block();
                b.r.set(r);
                b.course = c;
                b.session = s;
                b.day = s.dayOfWeek;
                b.start = s.startSection;
                blocks.add(b);
            }
        }
    }

    /** 按宽度折行，最多 max 行（超出用省略号）。中文按字符宽估算即可。 */
    private List<String> wrap(String s, float maxW, int max) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty() || maxW <= 0) return out;
        StringBuilder cur = new StringBuilder();
        float acc = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            float cwid = text.measureText(String.valueOf(ch));
            if (acc + cwid > maxW && cur.length() > 0) {
                out.add(cur.toString());
                if (out.size() == max) {
                    String last = out.get(max - 1);
                    out.set(max - 1, last.substring(0, Math.max(1, last.length() - 1)) + "…");
                    return out;
                }
                cur.setLength(0);
                acc = 0;
            }
            cur.append(ch);
            acc += cwid;
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 取源色 RGB、套上目标透明度（深色下课程块用半透明底，避免刺眼） */
    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private int courseColor(int idx) {
        int[] ids = {R.color.course_0, R.color.course_1, R.color.course_2, R.color.course_3,
                R.color.course_4, R.color.course_5, R.color.course_6, R.color.course_7};
        return ids[idx % 8];
    }

    private int courseSoft(int idx) {
        int[] ids = {R.color.course_0_soft, R.color.course_1_soft, R.color.course_2_soft, R.color.course_3_soft,
                R.color.course_4_soft, R.color.course_5_soft, R.color.course_6_soft, R.color.course_7_soft};
        return ids[idx % 8];
    }

    // ---------- 点击 ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (Math.abs(e.getX() - downX) > 12 * density || Math.abs(e.getY() - downY) > 12 * density) {
                    return true;
                }
                handleTap(e.getX(), e.getY());
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void handleTap(float x, float y) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block b = blocks.get(i);
            if (b.r.contains(x, y)) {
                if (courseListener != null) courseListener.onCourseClick(b.course, b.session, b.day, b.start);
                return;
            }
        }
        if (x < slotW || y < gridTop()) return;
        int day = (int) ((x - slotW) / cellW()) + 1;
        int section = (int) ((y - gridTop()) / cellH()) + 1;
        if (day < 1 || day > 7 || section < 1 || section > schedule.sectionCount()) return;
        if (emptyListener != null) emptyListener.onEmptyClick(day, section);
    }
}
