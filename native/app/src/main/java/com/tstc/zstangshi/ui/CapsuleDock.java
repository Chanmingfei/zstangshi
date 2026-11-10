package com.tstc.zstangshi.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import com.tstc.zstangshi.R;

/**
 * 悬浮胶囊 Dock —— 直接取自烫梨（github.com/augustlies/tangli, GPL-3.0）的 CapsuleDock，
 * 按本项目改成 4 个入口、图标在上文字在下。
 *
 * 交互与烫梨完全一致：
 *   点一下 —— 选中块以 320ms + OvershootInterpolator(1.4) 回弹滑过去；
 *   按住横拖 —— 选中块跟手、横向拉长到 1.06，每过一个入口轻震一次，松手吸附；
 *   按着别的格 —— 先在那里浮出一颗淡胶囊预告落点。
 *
 * 与原版的差异只有三处，其余逐行保留：
 *   1. 入口 3 → 4 个（烫梨是 课表/拦截/设置），标签与图标全部换成自己的；
 *   2. 文字从「图标在左」改为「图标在上」（4 项时横排会挤，竖向堆叠才留得住 44dp 热区）；
 *   3. 去掉了 androidx 依赖（本项目零第三方库，只用 framework API），配色改成读资源，
 *      浅色/深色由 View 自己按 Configuration 判断，不再依赖烫梨的 G 全局状态类。
 */
public class CapsuleDock extends View {

    public interface OnTabSelectedListener {
        void onTabSelected(int index);
    }

    private static final String[] LABELS = {"首页", "课表", "成绩", "我的"};
    private static final int[] ICONS = {
            R.drawable.ic_nav_home,
            R.drawable.ic_nav_timetable,
            R.drawable.ic_nav_grades,
            R.drawable.ic_nav_mine,
    };

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable[] icons = new Drawable[ICONS.length];
    private final RectF tmp = new RectF();
    private final float density;

    private int selected = 0;
    /** 选中块中心所在位置，单位「格」（0..n-1，可以是小数）。 */
    private float pos = 0f;
    private ValueAnimator anim;
    private OnTabSelectedListener listener;
    private OnTabSelectedListener reselect;

    private boolean dragging;
    private float downX, downY;
    /** 按下时预览要切去的那一格（-1 = 没按着），松手才真正切。 */
    private int pressedSlot = -1;
    private int lastHoverTab = -1;

    public CapsuleDock(Context context) {
        this(context, null);
    }

    public CapsuleDock(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(10.5f * getResources().getDisplayMetrics().scaledDensity);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1f, density * 0.8f));
        for (int i = 0; i < ICONS.length; i++) {
            Drawable d = context.getDrawable(ICONS[i]);
            icons[i] = d == null ? null : d.mutate();
        }
        setClickable(true);
        setElevation(8f * density);
        setOutlineProvider(new CapsuleOutline());
        updateDescription();
    }

    public void setOnTabSelectedListener(OnTabSelectedListener l) {
        this.listener = l;
    }

    /** 点已经选中的那一项（比如在课表页再点「课表」= 回到本周）。 */
    public void setOnTabReselectedListener(OnTabSelectedListener l) {
        this.reselect = l;
    }

    /** 外部切页时同步选中块（animate=false 或还没布局时直接就位）。 */
    public void setSelected(int index, boolean animate) {
        index = Math.max(0, Math.min(LABELS.length - 1, index));
        selected = index;
        updateDescription();
        if (!animate || getWidth() == 0) {
            if (anim != null) anim.cancel();
            pos = index;
            invalidate();
            return;
        }
        animateTo(index);
    }

    private void updateDescription() {
        setContentDescription("底部导航，当前：" + LABELS[selected] + "。左右拖动或点按切换：首页、课表、成绩、我的");
    }

    public int selectedTab() {
        return selected;
    }

    private void animateTo(int index) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(pos, index);
        anim.setDuration(320);
        anim.setInterpolator(new OvershootInterpolator(1.4f));
        anim.addUpdateListener(a -> {
            pos = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private float pad() {
        return 5f * density;
    }

    private float cellWidth() {
        return (getWidth() - pad() * 2f) / LABELS.length;
    }

    /** 手指 x → 格位置（小数，夹在 0..n-1）。 */
    static float slotAt(float x, float left, float cell, int n) {
        if (cell <= 0) return 0f;
        float p = (x - left) / cell - 0.5f;
        return Math.max(0f, Math.min(n - 1, p));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float cell = cellWidth();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                lastHoverTab = selected;
                pressedSlot = Math.round(slotAt(e.getX(), pad(), cell, LABELS.length));
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                // 明显是横着拖（横向 > 12dp 且比纵向大）才接管；轻微抖动仍算点按
                if (!dragging && dx > 12f * density && dx > dy * 1.5f) {
                    dragging = true;
                    pressedSlot = -1;
                    if (anim != null) anim.cancel();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    pos = slotAt(e.getX(), pad(), cell, LABELS.length);
                    int hover = Math.round(pos);
                    if (hover != lastHoverTab) {
                        lastHoverTab = hover;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                boolean wasDrag = dragging;
                int target = wasDrag ? Math.round(pos)
                        : (pressedSlot >= 0 ? pressedSlot : Math.round(slotAt(e.getX(), pad(), cell, LABELS.length)));
                dragging = false;
                pressedSlot = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                animateTo(target);
                if (target != selected) {
                    selected = target;
                    updateDescription();
                    if (!wasDrag) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (listener != null) listener.onTabSelected(target);
                } else {
                    performClick();
                    if (!wasDrag && reselect != null) reselect.onTabSelected(target);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                pressedSlot = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                animateTo(selected);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** 胶囊轮廓：阴影跟着圆角走。写成静态内部类而不是匿名类 —— d8 对
     *  「同一文件里的匿名内部类 + JDK17 nest 属性」会崩（编译期 NPE），静态类没有这个问题。 */
    private static final class CapsuleOutline extends android.view.ViewOutlineProvider {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = h / 2f;

        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        int ink = getColor(night ? R.color.text_primary : R.color.text_primary);
        int sub = getColor(R.color.text_tertiary);
        int card = getColor(R.color.bg_surface);
        int pillColor;

        if (night) {
            bgPaint.setColor(0xF21C222E);
            strokePaint.setColor(0x2EFFFFFF);
            pillColor = 0x1FFFFFFF;
        } else {
            bgPaint.setColor(0xFAFFFFFF);
            strokePaint.setColor(0x1C14203A);
            pillColor = 0x0F14203A;
        }

        tmp.set(0, 0, w, h);
        canvas.drawRoundRect(tmp, r, r, bgPaint);
        float hs = strokePaint.getStrokeWidth() / 2f;
        tmp.set(hs, hs, w - hs, h - hs);
        canvas.drawRoundRect(tmp, r, r, strokePaint);

        float cell = cellWidth();
        float p = pad();
        // 选中块：拖动时略微拉长，像被手指拽着
        float stretch = dragging ? 1.06f : 1f;
        float cx = p + cell * (pos + 0.5f);
        float halfW = cell / 2f * stretch;
        tmp.set(cx - halfW, p, cx + halfW, h - p);
        pillPaint.setColor(pillColor);
        float pr = tmp.height() / 2f;
        canvas.drawRoundRect(tmp, pr, pr, pillPaint);
        if (pressedSlot >= 0 && pressedSlot != Math.round(pos)) {
            // 按着别的格：先在那里浮出一颗淡胶囊，告诉你松手会去哪
            float pcx = p + cell * (pressedSlot + 0.5f);
            tmp.set(pcx - cell / 2f, p, pcx + cell / 2f, h - p);
            pillPaint.setColor(night ? 0x14FFFFFF : 0x0A14203A);
            canvas.drawRoundRect(tmp, pr, pr, pillPaint);
        }

        // 图标在上、文字在下：4 项横排时若「左图标右文字」会把每格挤到 60dp 以下
        float iconSize = 20f * density;
        float gap = 2f * density;
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textH = fm.descent - fm.ascent;
        float block = iconSize + gap + textH;
        float top = h / 2f - block / 2f;
        int offColor = sub;

        for (int i = 0; i < LABELS.length; i++) {
            float icx = p + cell * (i + 0.5f);
            // 离选中块越近越接近「选中色」，拖动过程中颜色平滑过渡
            float near = Math.max(0f, 1f - Math.abs(pos - i));
            int color = mixColor(offColor, night ? 0xFFF2F5FA : ink, near);
            textPaint.setColor(color);
            textPaint.setTypeface(near > 0.5f ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

            Drawable d = icons[i];
            if (d != null) {
                int left = Math.round(icx - iconSize / 2f);
                int t = Math.round(top);
                d.setBounds(left, t, Math.round(left + iconSize), Math.round(t + iconSize));
                d.setTint(color);
                d.draw(canvas);
            }
            canvas.drawText(LABELS[i], icx, top + iconSize + gap - fm.ascent, textPaint);
        }
    }

    private int getColor(int id) {
        return getResources().getColor(id, null);
    }

    private static int mixColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = Math.round(aa + (ba - aa) * t);
        int rr = Math.round(ar + (br - ar) * t);
        int rg = Math.round(ag + (bg - ag) * t);
        int rb = Math.round(ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }
}
