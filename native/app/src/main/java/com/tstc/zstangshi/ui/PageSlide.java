package com.tstc.zstangshi.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * 二级页面的「圆角卡片」进出场：整页当成一张带投影的圆角矩形，从屏幕右侧滑进来。
 *
 * 为什么必须自己画：
 *   系统只在「返回」方向画圆角卡片（AOSP CrossActivityBackAnimation 用
 *   ScreenDecorationsUtils.getWindowCornerRadius() 取设备圆角，把正在关闭的窗口裁圆角再缩到
 *   90%），「进入」方向走的是窗口动画，framework 的窗口动画只有 alpha/translate/scale，
 *   没有圆角，也没有公开 API 能给应用窗口设圆角。于是同一个页面进来是直角、出去是圆角。
 *
 * 做法（纯 framework）：
 *   1. 主题 windowIsTranslucent=true + 窗口底色透明 —— 窗口面才有 alpha 通道，圆角之外
 *      透得出下面那一页（这也是系统做跨 Activity 卡片动画的前提）；
 *   2. 在 android.R.id.content 里插一层自己建的 FrameLayout 当「卡片」，把页面根视图挪进去。
 *      圆角靠 setClipToOutline + ViewOutlineProvider，投影靠 setElevation ——
 *      这两样都作用在普通 View 上才可靠，直接对 DecorView 用是无效的（上一版就是栽在这：
 *      页面既没有圆角也没有投影，看着就是一块没边界的矩形）。
 *   3. 卡片的位移 = p × 屏宽，圆角 = 系统圆角 × clamp(p / 0.28)，落位时圆角归 0，
 *      所以静止状态和以前逐像素一致，四角不会留缝。
 *
 * 系统窗口动画用 overrideActivityTransition(OPEN, 0, 0)（官方文档指定的、14 起替代
 * overridePendingTransition 的方式）关掉，否则系统动画会和这套位移叠在一起。
 */
public final class PageSlide {

    private static final int IN_MS = 300;
    private static final int OUT_MS = 230;
    /** 滑出 28% 屏宽时圆角就长满了，再往外只是平移。 */
    private static final float ROUND_AT = 0.28f;

    private final Activity act;
    /** 真正的「卡片」：页面根视图的父亲，圆角、投影、位移都加在它身上。 */
    private final FrameLayout card;
    private final float maxRadius;
    /** 卡片浮起的高度（px），也就是投影的强度。 */
    private final float shadowPx;

    /** 0 = 完全在位；1 = 完全滑出屏幕右侧。整页的外观全部是它的函数。 */
    private float p = 1f;
    private float radius;
    private ValueAnimator anim;
    private boolean entered;
    private boolean finishing;
    /** 返回手势回调的登记凭据（BackGestures 的 uninstall 要用）。 */
    private Object backToken;

    public PageSlide(Activity a, int pageColor) {
        act = a;
        Window win = a.getWindow();
        // 窗口底色透明：圆角之外才透得出下面那一页
        win.setBackgroundDrawable(new ColorDrawable(0));
        // 让 content 铺满整个窗口（含状态栏/导航栏那两条），卡片才顶得到屏幕四角。
        // 内容与系统栏之间的间距改由页面根视图自己的 inset padding 负责（各页已有 applyInsets）。
        fullBleed(win);

        float dens = a.getResources().getDisplayMetrics().density;
        maxRadius = windowCornerRadius(a);

        card = new FrameLayout(a);
        card.setBackgroundColor(pageColor);
        card.setOutlineProvider(new CardOutline(this));
        card.setClipToOutline(true);
        // 投影：卡片浮起来，被移开的那一侧边缘才会像系统那样有层次
        shadowPx = 10 * dens;
        card.setElevation(shadowPx);
        if (Build.VERSION.SDK_INT >= 28) {
            card.setOutlineSpotShadowColor(0x70000000);
            card.setOutlineAmbientShadowColor(0x3D000000);
        }
        wrapContent(win, card);

        // 系统窗口动画关掉（官方文档：14 起用 overrideActivityTransition 代替 overridePendingTransition）
        if (Build.VERSION.SDK_INT >= 34) {
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
        } else {
            a.overridePendingTransition(0, 0);
        }

        // 首帧之前就摆到屏幕外，别先闪一整页再滑入
        radius = maxRadius;
        card.setTranslationX(width());
        card.invalidateOutline();
    }

    /** 进入动画：从右往左落到原位，圆角随手收掉。 */
    public void enter() {
        if (entered) return;
        entered = true;
        whenLaidOut(new Enter(this));
    }

    /** 顶栏返回箭头 / 页面自己要关的时候走这个：滑出屏幕再 finish。 */
    public void finish() {
        if (finishing) return;
        finishing = true;
        stop();
        int ms = Math.max(120, (int) (OUT_MS * Math.max(0.3f, 1f - p)));
        slideTo(1f, ms, new Finisher(act));
    }

    /**
     * 接管「二级页 → 一级页」的预测性返回手势。
     *
     * 手势和进场共用同一个 p：手指拖多少，整张圆角卡片就往右移多少、圆角就长多少，
     * 松手滑完再 finish。
     */
    public void attachBack() {
        if (backToken != null || finishing || !BackGestures.supported()) return;
        backToken = BackGestures.install(act, new ExitHandler(this));
    }

    /** 交还返回手势（WebView 还有历史可退时，返回要归网页翻页用）。 */
    public void detachBack() {
        if (backToken == null) return;
        BackGestures.uninstall(act, backToken);
        backToken = null;
    }

    // ---------- 内部 ----------

    void gestureStart() {
        stop();
    }

    void gestureProgress(float progress) {
        apply(Math.max(0f, Math.min(1f, progress)));
    }

    void gestureCommit() {
        if (finishing) return;
        finishing = true;
        stop();
        int ms = Math.max(110, (int) (OUT_MS * Math.max(0.25f, 1f - p)));
        slideTo(1f, ms, new Finisher(act));
    }

    void gestureCancel() {
        slideTo(0f, 200);
    }

    private int width() {
        int w = card.getWidth();
        if (w > 0) return w;
        return act.getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * 唯一的外观函数：位移 = p × 屏宽，圆角 = maxRadius × clamp(p / ROUND_AT)。
     * 圆角每帧都 invalidateOutline 一次 —— 半径变化必须让 RenderNode 重新裁，
     * 只在「数值不等」时才刷会漏掉「布局前 outline 为空」的那一帧。
     */
    private void apply(float val) {
        p = val;
        card.setTranslationX(width() * val);
        radius = val <= 0.001f ? 0f : maxRadius * Math.min(1f, val / ROUND_AT);
        // 投影跟着位移淡入：落位时 elevation 归 0，静止状态不会在上下边缘留一条阴影线
        card.setElevation(shadowPx * Math.min(1f, val * 3f));
        card.invalidateOutline();
    }

    private void slideTo(float target, int ms) {
        slideTo(target, ms, null);
    }

    private void slideTo(float target, int ms, Runnable done) {
        stop();
        ValueAnimator v = ValueAnimator.ofFloat(p, target);
        v.setDuration(ms);
        v.setInterpolator(new DecelerateInterpolator(1.2f));
        v.addUpdateListener(a -> apply(((Float) a.getAnimatedValue()).floatValue()));
        if (done != null) v.addListener(new End(this, done));
        anim = v;
        v.start();
    }

    private void stop() {
        ValueAnimator v = anim;
        anim = null;
        if (v != null) {
            v.removeAllUpdateListeners();
            v.removeAllListeners();
            v.cancel();
        }
    }

    /** 卡片宽高就绪后再跑（onCreate 里 setContentView 之后还没 measure，宽是 0）。 */
    private void whenLaidOut(Runnable r) {
        if (card.getWidth() > 0 && card.getHeight() > 0) {
            r.run();
            return;
        }
        card.addOnLayoutChangeListener(new LaidOut(this, r));
    }

    /**
     * 把窗口切成「内容铺满全屏」：API 30+ 走官方 setDecorFitsSystemWindows(false)，
     * 再往下用 LAYOUT_FULLSCREEN / LAYOUT_HIDE_NAVIGATION 两套老 flag 等效实现。
     */
    private static void fullBleed(Window win) {
        if (Build.VERSION.SDK_INT >= 30) {
            win.setDecorFitsSystemWindows(false);
        } else {
            win.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /**
     * 把 content 里已有的页面根视图搬进自建卡片，再把卡片放回 content。
     * 直接对 DecorView 设 outline/裁剪不生效，必须换成普通 View 才裁得出圆角。
     */
    private static void wrapContent(Window win, FrameLayout card) {
        View root = win.findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) root;
        while (host.getChildCount() > 0) {
            View child = host.getChildAt(0);
            host.removeViewAt(0);
            card.addView(child, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        host.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * 系统把「窗口圆角」放在 android 包的 rounded_corner_radius 维度（与系统返回动画同源）；
     * 取不到（老系统 / 改了资源的 ROM）就退回 24dp，观感和主流机型基本一致。
     */
    private static float windowCornerRadius(Activity a) {
        try {
            int id = a.getResources().getIdentifier(
                    "rounded_corner_radius", "dimen", "android");
            if (id > 0) {
                float r = a.getResources().getDimensionPixelSize(id);
                if (r > 0) return r;
            }
        } catch (Throwable ignored) {
        }
        return Math.max(24f * a.getResources().getDisplayMetrics().density, 1f);
    }

    /** 圆角只认「当前半径」，所以每帧改半径后 invalidateOutline 即刻生效。 */
    private static final class CardOutline extends ViewOutlineProvider {
        private final PageSlide host;

        CardOutline(PageSlide h) {
            host = h;
        }

        @Override
        public void getOutline(View v, Outline o) {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) {
                o.setEmpty();
                return;
            }
            float r = Math.min(host.radius, Math.min(w, h) / 2f);
            if (r <= 0.5f) {
                o.setRect(0, 0, w, h);
            } else {
                o.setRoundRect(0, 0, w, h, r);
            }
        }
    }

    private static final class LaidOut implements View.OnLayoutChangeListener {
        private final PageSlide host;
        private final Runnable after;

        LaidOut(PageSlide h, Runnable r) {
            host = h;
            after = r;
        }

        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b,
                                   int ol, int ot, int or, int ob) {
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
            v.removeOnLayoutChangeListener(this);
            after.run();
        }
    }

    private static final class Enter implements Runnable {
        private final PageSlide host;

        Enter(PageSlide h) {
            host = h;
        }

        @Override
        public void run() {
            host.slideTo(0f, IN_MS);
        }
    }

    private static final class End extends AnimatorListenerAdapter {
        private final PageSlide host;
        private final Runnable after;

        End(PageSlide h, Runnable r) {
            host = h;
            after = r;
        }

        @Override
        public void onAnimationEnd(Animator a) {
            if (host.anim != a) return;
            host.anim = null;
            after.run();
        }
    }

    /** 返回手势 → 整页跟手右移；松手滑完再 finish，取消则滑回原位。 */
    private static final class ExitHandler implements BackGestures.Handler {
        private final PageSlide host;

        ExitHandler(PageSlide h) {
            host = h;
        }

        @Override
        public void onStart() {
            host.gestureStart();
        }

        @Override
        public void onProgress(float progress) {
            host.gestureProgress(progress);
        }

        @Override
        public void onCommit() {
            host.gestureCommit();
        }

        @Override
        public void onCancel() {
            host.gestureCancel();
        }
    }

    private static final class Finisher implements Runnable {
        private final Activity act;

        Finisher(Activity a) {
            act = a;
        }

        @Override
        public void run() {
            if (act.isFinishing() || act.isDestroyed()) return;
            act.finish();
            act.overridePendingTransition(0, 0);
        }
    }
}
