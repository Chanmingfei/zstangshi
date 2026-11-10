package com.tstc.zstangshi.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import com.tstc.zstangshi.R;

/**
 * 三联段控（浅色 / 深色 / 跟随系统）。
 *
 * 与底栏那套胶囊是一路的：点哪一段，胶囊就带着一点点过冲滑过去，文字颜色
 * 跟着位置插值 —— 所以中间那一下不会出现「字先变色、块后到」的割裂感。
 *
 * 外框保持原来的圆角矩形（不是纯胶囊），胶囊按 R-3dp 收缩，四角才不会顶出来。
 */
public class SegmentedControl extends View {

    public interface OnChange {
        void onChanged(int index);
    }

    private final String[] labels;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    /** 建的时候记下深浅，动画重绘时不再去读 Store —— 否则点了深色，胶囊滑动的那几帧
     *  就已经按新配色画了，看起来比整页先变色 */
    private final boolean night;
    private final float density;
    private final float radius;
    private final float inset;

    /** 胶囊当前停在第几段（带小数，动画中） */
    private float pos;
    private int selected;
    private OnChange onChange;
    private ValueAnimator anim;

    public SegmentedControl(Context c, String[] labels, int selected) {
        super(c);
        this.labels = labels;
        this.selected = Math.max(0, Math.min(labels.length - 1, selected));
        this.pos = this.selected;
        density = c.getResources().getDisplayMetrics().density;
        night = new com.tstc.zstangshi.data.Store(c).darkNow();
        radius = 13 * density;
        inset = 3 * density;

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, density * 0.8f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(13 * c.getResources().getDisplayMetrics().scaledDensity);
        setClickable(true);
    }

    public void setOnChange(OnChange l) {
        this.onChange = l;
    }

    public void setSelected(int i, boolean animate) {
        int target = Math.max(0, Math.min(labels.length - 1, i));
        if (target == selected && animate) return;
        selected = target;
        if (!animate) {
            pos = target;
            invalidate();
            return;
        }
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(300);
        anim.setInterpolator(new OvershootInterpolator(1.35f));
        anim.addUpdateListener(a -> {
            pos = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = Math.round(40 * density);
        setMeasuredDimension(w, resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float r = Math.min(radius, h / 2f);

        boolean night = isNight();

        // 轨道
        rect.set(0, 0, w, h);
        trackPaint.setColor(night ? 0xFF1A2230 : 0xFFEDEFF4);
        canvas.drawRoundRect(rect, r, r, trackPaint);
        borderPaint.setColor(night ? 0x1FFFFFFF : 0x1C14203A);
        float hs = borderPaint.getStrokeWidth() / 2f;
        rect.set(hs, hs, w - hs, h - hs);
        canvas.drawRoundRect(rect, r, r, borderPaint);

        // 滑动的胶囊
        float seg = w / (float) labels.length;
        float left = pos * seg + inset;
        float right = (pos + 1) * seg - inset;
        rect.set(left, inset, right, h - inset);
        pillPaint.setColor(getResources().getColor(R.color.brand, null));
        float pr = Math.min(r - inset, (h - inset * 2) / 2f);
        canvas.drawRoundRect(rect, pr, pr, pillPaint);

        // 文字：离胶囊越近越白，越远越是次级灰
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float cy = h / 2f - (fm.ascent + fm.descent) / 2f;
        int on = 0xFFFFFFFF;
        int off = getResources().getColor(R.color.text_secondary, null);
        for (int i = 0; i < labels.length; i++) {
            float d = Math.abs(i - pos);
            float near = Math.max(0f, 1f - Math.min(1f, d));
            textPaint.setColor(mix(off, on, near));
            textPaint.setTypeface(near > 0.5f ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            canvas.drawText(labels[i], seg * (i + 0.5f), cy, textPaint);
        }
    }

    private boolean isNight() {
        return night;
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            int i = (int) (e.getX() / (getWidth() / (float) labels.length));
            i = Math.max(0, Math.min(labels.length - 1, i));
            if (i != selected) {
                setSelected(i, true);
                if (onChange != null) onChange.onChanged(i);
            }
            performClick();
            return true;
        }
        return e.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
