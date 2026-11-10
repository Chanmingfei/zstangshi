package com.tstc.zstangshi.ui;

import android.content.Context;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * 横向排开的「页面带」：每个主页面占一个与屏幕等宽的槽位，槽位号就是底栏 tab 序号。
 *
 * 为什么不用 FrameLayout 叠着放：叠放只能让新旧两页各自平移，从首页直接点「我的」
 * 时中间两页根本不参演，看起来是两页擦过去。排成一条带之后，整体按
 * (槽位 - 当前页位置) × 屏宽 摆放，中途课表、成绩会真的依次掠过屏幕。
 *
 * 实现上所有子页都 layout 在同一块 [0,0,宽,高] 区域、只用 translationX 区分位置：
 * 容器的边界始终等于一屏，系统的裁剪与触摸命中都按真实位置换算，
 * 既不会被自己的边界裁掉，也不会把触摸派给屏幕外的页面。
 */
public class PageDeck extends ViewGroup {

    private final SparseArray<View> pages = new SparseArray<>();
    private int slotWidth;
    /** 当前「页位置」，可为小数（跟手拖动时介于两页之间）。 */
    private float position;

    public PageDeck(Context c) {
        super(c);
        setClipChildren(true);
    }

    /** 槽位宽度 = 屏幕宽；尺寸变了（横竖屏）由调用方重新设一次。 */
    public void setSlotWidth(int w) {
        if (w <= 0 || w == slotWidth) return;
        slotWidth = w;
        applyOffsets();
        requestLayout();
    }

    public int slotWidth() {
        return slotWidth;
    }

    /** 把页面放到指定槽位（可乱序调用，摆放只认槽位号）。 */
    public void put(int slot, View page) {
        View old = pages.get(slot);
        if (old == page) return;
        if (old != null) {
            pages.remove(slot);
            removeView(old);
        }
        pages.put(slot, page);
        if (page.getParent() == null) {
            addView(page);
        }
        applyOffsets();
        requestLayout();
    }

    public View pageAt(int slot) {
        return pages.get(slot);
    }

    /** 立即把整条带挪到某个位置（可小数，用于跟手拖动）。 */
    public void jumpTo(float pos) {
        position = pos;
        applyOffsets();
    }

    /** 平滑滚到某个位置（切 tab、手势结束时的收尾）。 */
    public void animateTo(float pos, long duration) {
        position = pos;
        for (int i = 0; i < pages.size(); i++) {
            int slot = pages.keyAt(i);
            View v = pages.valueAt(i);
            v.animate().cancel();
            v.animate().translationX((slot - pos) * (float) slotWidth)
                    .setDuration(duration)
                    .setInterpolator(new DecelerateInterpolator(1.1f))
                    .start();
        }
    }

    /** 打断正在跑的平移，原地停住。 */
    public void cancelAnimation() {
        for (int i = 0; i < pages.size(); i++) {
            pages.valueAt(i).animate().cancel();
        }
    }

    public float position() {
        return position;
    }

    private void applyOffsets() {
        for (int i = 0; i < pages.size(); i++) {
            int slot = pages.keyAt(i);
            View v = pages.valueAt(i);
            v.animate().cancel();
            v.setTranslationX((slot - position) * (float) slotWidth);
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int specW = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        if (slotWidth <= 0) slotWidth = specW;
        for (int i = 0; i < pages.size(); i++) {
            View v = pages.valueAt(i);
            v.measure(MeasureSpec.makeMeasureSpec(slotWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(specW, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int h = b - t;
        for (int i = 0; i < pages.size(); i++) {
            View v = pages.valueAt(i);
            // 全都叠在同一块区域，位置差由 translationX 表达（见 applyOffsets）
            v.layout(0, 0, slotWidth, h);
        }
        // 这里不重设 translationX：布局可能由页面内部内容变化触发，
        // 若在此打断动画，切页会突然跳到终点。
    }
}
