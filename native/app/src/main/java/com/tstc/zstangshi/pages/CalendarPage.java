package com.tstc.zstangshi.pages;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tstc.zstangshi.R;
import com.tstc.zstangshi.data.Dates;
import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.data.Timetable;
import com.tstc.zstangshi.data.WeekMath;

import java.util.Calendar;

/**
 * 校历（月历视图，旧版那张日历的还原）。
 *
 * 结构：学期信息卡 → 月份切换（‹ 2026年11月 ›）→ 星期表头 → 6×7 的日期格子 → 选中那天的一句说明。
 *
 * 格子里的信息只放「真的推得出来的」：
 *   · 教学周里的日子带一个小点，当前周更亮；
 *   · 今天描一圈品牌色；
 *   · 不在本学期范围内的日子灰掉。
 * 放假、调休这类细项没有可靠数据源，就不编。
 */
public class CalendarPage extends LinearLayout {

    private final Activity host;
    private final Store store;
    private final TextView monthLabel;
    private final MonthView grid;
    private final TextView dayInfo;

    private Timetable timetable;
    private String startDate = "";
    private int totalWeeks = 20;
    private int year, month;         // 当前显示的月份
    private String selected = "";    // yyyy-MM-dd

    public CalendarPage(Activity host, Store store) {
        super(host);
        this.host = host;
        this.store = store;
        setOrientation(VERTICAL);
        setBackgroundColor((store.darkNow() ? 0xFF0F1520 : host.getColor(R.color.bg_page)));
        setPadding(dp(14), dp(10), dp(14), dp(10));

        timetable = store.loadTimetable();
        if (timetable != null) {
            startDate = timetable.semester.startDate;
            totalWeeks = timetable.semester.totalWeeks <= 0 ? 20 : timetable.semester.totalWeeks;
        }

        Calendar now = Calendar.getInstance();
        year = now.get(Calendar.YEAR);
        month = now.get(Calendar.MONTH) + 1;
        selected = WeekMath.todayDateLabel();

        // ── 学期信息 ──
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(host.getDrawable(R.drawable.card_bg));

        TextView name = new TextView(host);
        name.setText(timetable == null || timetable.semester.name.isEmpty()
                ? "本学期" : timetable.semester.name);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(host.getColor(R.color.text_primary));
        card.addView(name);

        TextView sub = new TextView(host);
        sub.setTextSize(12);
        sub.setTextColor(host.getColor(R.color.text_tertiary));
        sub.setPadding(0, dp(5), 0, 0);
        if (!Dates.valid(startDate)) {
            sub.setText("还没设置开学日期，去「我的」补上才能算周次");
        } else {
            int w = WeekMath.currentWeek(startDate, totalWeeks);
            sub.setText("第 1 周从 " + startDate + " 开始 · 共 " + totalWeeks + " 周"
                    + (w >= 1 && w <= totalWeeks ? " · 现在第 " + w + " 周" : ""));
        }
        card.addView(sub);
        addView(card, lp(dp(10)));

        // ── 月份切换 ──
        LinearLayout nav = new LinearLayout(host);
        nav.setOrientation(HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);

        TextView prev = arrow("‹");
        prev.setOnClickListener(v -> shiftMonth(-1));
        nav.addView(prev, new LayoutParams(dp(44), dp(40)));

        monthLabel = new TextView(host);
        monthLabel.setTextSize(16);
        monthLabel.setTypeface(Typeface.DEFAULT_BOLD);
        monthLabel.setTextColor(host.getColor(R.color.text_primary));
        monthLabel.setGravity(Gravity.CENTER);
        nav.addView(monthLabel, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView next = arrow("›");
        next.setOnClickListener(v -> shiftMonth(1));
        nav.addView(next, new LayoutParams(dp(44), dp(40)));

        // 「回到今天」：翻走几个月之后不用一格格点回来
        TextView todayBtn = new TextView(host);
        todayBtn.setText("今天");
        todayBtn.setTextSize(12.5f);
        todayBtn.setGravity(Gravity.CENTER);
        todayBtn.setTextColor(host.getColor(R.color.brand));
        todayBtn.setBackground(host.getDrawable(R.drawable.btn_outline));
        todayBtn.setOnClickListener(v -> backToToday());
        LayoutParams tlp = new LayoutParams(dp(56), dp(30));
        tlp.leftMargin = dp(4);
        nav.addView(todayBtn, tlp);
        addView(nav);

        // ── 星期表头 ──
        LinearLayout week = new LinearLayout(host);
        week.setOrientation(HORIZONTAL);
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (String s : names) {
            TextView t = new TextView(host);
            t.setText(s);
            t.setTextSize(11.5f);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(host.getColor(R.color.text_tertiary));
            week.addView(t, new LayoutParams(0, dp(26), 1f));
        }
        addView(week);

        // ── 日期格子 ──
        grid = new MonthView(this);
        addView(grid, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6 * 52)));
        grid.setOnPick(day -> {
            selected = day;
            grid.invalidate();
            updateDayInfo();
        });

        // ── 选中那天 ──
        dayInfo = new TextView(host);
        dayInfo.setTextSize(12.5f);
        dayInfo.setTextColor(host.getColor(R.color.text_secondary));
        dayInfo.setLineSpacing(dp(3), 1f);
        dayInfo.setPadding(dp(16), dp(12), dp(16), dp(12));
        dayInfo.setBackground(host.getDrawable(R.drawable.card_bg));
        LayoutParams ilp = lp(dp(10));
        ilp.topMargin = dp(10);
        addView(dayInfo, ilp);
        dayInfo.setLayoutParams(ilp);

        render();
    }

    /** 不管翻到哪个月，一键回到本月并把今天选中。 */
    private void backToToday() {
        Calendar now = Calendar.getInstance();
        year = now.get(Calendar.YEAR);
        month = now.get(Calendar.MONTH) + 1;
        selected = WeekMath.todayDateLabel();
        render();
    }

    private void shiftMonth(int delta) {
        month += delta;
        if (month < 1) {
            month = 12;
            year--;
        } else if (month > 12) {
            month = 1;
            year++;
        }
        grid.invalidate();
        monthLabel.setText(year + " 年 " + month + " 月");
        syncGridHeight();
    }

    private void render() {
        monthLabel.setText(year + " 年 " + month + " 月");
        updateDayInfo();
        grid.invalidate();
        syncGridHeight();
    }

    /** 当月占 5 行就别留第 6 行的空白（旧版也是按行数撑满的）。 */
    private void syncGridHeight() {
        Calendar c = Calendar.getInstance();
        c.set(year, month - 1, 1);
        int offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        int rows = (int) Math.ceil((offset + days) / 7.0);
        ViewGroup.LayoutParams lp = grid.getLayoutParams();
        int h = dp(Math.max(4, rows) * 52);
        if (lp != null && lp.height == h) return;
        if (lp != null) {
            lp.height = h;
            grid.setLayoutParams(lp);
        }
    }

    private void updateDayInfo() {
        if (!Dates.valid(selected) || !Dates.valid(startDate)) {
            dayInfo.setText("选一天看看是第几周");
            return;
        }
        int week = Dates.weekOf(startDate, selected);
        String wd = "一二三四五六日".charAt(Math.max(0, Math.min(6, Dates.dayOfWeek(selected) - 1))) + "";
        if (week < 1) {
            dayInfo.setText(selected + " · 周" + wd + "\n开学前，第 1 周从 " + startDate + " 开始");
        } else if (week > totalWeeks) {
            dayInfo.setText(selected + " · 周" + wd + "\n本学期（共 " + totalWeeks + " 周）已经结束");
        } else {
            dayInfo.setText(selected + " · 周" + wd + " · 第 " + week + " 周");
        }
    }

    private TextView arrow(String s) {
        TextView v = new TextView(host);
        v.setText(s);
        v.setTextSize(20);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(host.getColor(R.color.text_primary));
        return v;
    }

    private LayoutParams lp(int bottom) {
        LayoutParams p = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = bottom;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ================= 月历格子 =================

    /**
     * 必须是 static：当前这套 d8 只要碰到带 this$0 的非静态内部类就内部报错
     * （`NullPointerException: Cannot invoke "String.length()"`），
     * 所以外部实例改用 page 字段显式持有。
     */
    private static final class MonthView extends View {

        interface OnPick {
            void onPick(String ymd);
        }

        private final CalendarPage page;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;
        private OnPick onPick;

        MonthView(CalendarPage page) {
            super(page.getContext());
            this.page = page;
            this.density = getResources().getDisplayMetrics().density;
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(13.5f * getResources().getDisplayMetrics().scaledDensity);
        }

        void setOnPick(OnPick l) {
            onPick = l;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean night = page.store.darkNow();
            int ink = page.host.getColor(R.color.text_primary);
            int dim = page.host.getColor(R.color.text_tertiary);
            int brand = page.host.getColor(R.color.brand);

            Calendar c = Calendar.getInstance();
            c.set(page.year, page.month - 1, 1);
            int firstDow = c.get(Calendar.DAY_OF_WEEK);        // 1=周日
            int offset = (firstDow + 5) % 7;                    // 周一为 0
            int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);

            float cw = getWidth() / 7f;
            float ch = getHeight() / 6f;
            String today = WeekMath.todayDateLabel();

            Paint.FontMetrics fm = text.getFontMetrics();
            for (int i = 0; i < 42; i++) {
                int dayNo = i - offset + 1;
                float left = (i % 7) * cw;
                float top = (i / 7) * ch;
                if (dayNo < 1 || dayNo > days) continue;

                String ymd = String.format("%04d-%02d-%02d", page.year, page.month, dayNo);
                boolean isToday = ymd.equals(today);
                boolean isPicked = ymd.equals(page.selected);

                // 选中 / 今天：一块圆角底。竖向只占 0.14~0.76，
                // 底下 0.86 那颗教学周小点才不会被压在圆角边上（之前是重叠的）
                if (isPicked || isToday) {
                    rect.set(left + cw * 0.16f, top + ch * 0.14f,
                            left + cw * 0.84f, top + ch * 0.74f);
                    p.setColor(isPicked ? brand : (night ? 0x2EFFFFFF : 0x1A0062CC));
                    canvas.drawRoundRect(rect, 13 * density, 13 * density, p);
                }

                text.setColor(isPicked ? 0xFFFFFFFF : ink);
                text.setTypeface(isToday ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                float baseline = top + ch * 0.44f - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(String.valueOf(dayNo), left + cw / 2f, baseline, text);

                // 教学周里的小点（在数字下方，跟选中底分开）
                if (Dates.valid(page.startDate)) {
                    int w = Dates.weekOf(page.startDate, ymd);
                    if (w >= 1 && w <= page.totalWeeks) {
                        int now = WeekMath.currentWeek(page.startDate, page.totalWeeks);
                        p.setColor(isPicked ? brand : (w == now ? brand : dim));
                        canvas.drawCircle(left + cw / 2f, top + ch * 0.88f, 2.2f * density, p);
                    }
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float cw = getWidth() / 7f;
                float ch = getHeight() / 6f;
                int col = (int) (e.getX() / cw);
                int row = (int) (e.getY() / ch);
                Calendar c = Calendar.getInstance();
                c.set(page.year, page.month - 1, 1);
                int offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
                int dayNo = row * 7 + col - offset + 1;
                int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
                if (dayNo >= 1 && dayNo <= days) {
                    String ymd = String.format("%04d-%02d-%02d", page.year, page.month, dayNo);
                    if (onPick != null) onPick.onPick(ymd);
                }
                return true;
            }
            return e.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(e);
        }
    }
}
