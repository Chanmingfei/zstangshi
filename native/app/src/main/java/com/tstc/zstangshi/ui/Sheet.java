package com.tstc.zstangshi.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.tstc.zstangshi.R;

/**
 * 底部弹层（旧版 sheet 的原生复刻）。
 *
 * 旧版是 CSS 的 translate3d + 遮罩淡入，这边用同一套节奏：
 *   · 遮罩从 0 淡到 0.38，220ms；
 *   · 卡片从底下 40dp 处滑上来，上滑用 Decelerate（先快后停），300ms；
 *   · 关闭时反过来，等动画跑完再 dismiss，所以不会「啪」一下消失。
 *
 * 顶部那条小横杠既是最像原版的一处细节，也顺手当了可以下拉关闭的手势区。
 */
public final class Sheet {

    public static final class Handle {
        private final Dialog dialog;
        private final View card;
        private final View scrim;
        private final int height;
        private boolean closing;

        Handle(Dialog d, View card, View scrim, int height) {
            this.dialog = d;
            this.card = card;
            this.scrim = scrim;
            this.height = height;
        }

        public void dismiss() {
            if (closing) return;
            closing = true;
            card.animate()
                    .translationY(height)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .withEndAction(() -> {
                        try {
                            dialog.dismiss();
                        } catch (Exception ignored) {
                        }
                    })
                    .start();
            scrim.animate().alpha(0f).setDuration(180).start();
        }

        public boolean isShowing() {
            return dialog.isShowing();
        }
    }

    private Sheet() {}

    /**
     * @param content 已经填好内容的卡片（宽度会撑满，高度按内容）
     * @param height  卡片高度（px）。传 0 表示按内容自适应
     */
    public static Handle show(Activity host, View content, int height) {
        float d = host.getResources().getDisplayMetrics().density;
        int dp = Math.round(d);
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        final int cardH = height > 0 ? height : Math.min(screenH * 3 / 4, dp * 420);

        Dialog dlg = new Dialog(host, android.R.style.Theme_Translucent_NoTitleBar);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(host);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 遮罩：点一下关掉（旧版点空白处也是关）
        View scrim = new View(host);
        scrim.setBackgroundColor(0x00000000);
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(host.getDrawable(R.drawable.sheet_bg));
        card.setElevation(dp * 12);

        // 顶部小横杠
        FrameLayout barWrap = new FrameLayout(host);
        barWrap.setPadding(0, dp * 8, 0, dp * 6);
        View bar = new View(host);
        GradientDrawable g = new GradientDrawable();
        g.setColor(host.getColor(R.color.border_subtle));
        g.setCornerRadius(dp * 2);
        bar.setBackground(g);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp * 36, Math.max(3, dp * 3));
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        barWrap.addView(bar, blp);
        card.addView(barWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardH);
        clp.gravity = Gravity.BOTTOM;
        root.addView(card, clp);

        dlg.setContentView(root);
        // 卡片先自己拿住焦点：不这样的话，弹层里第一个 EditText 会抢焦点，
        // ScrollView 跟着滚一屏，用户看到的就不是「刚打开时那个位置」了
        card.setFocusableInTouchMode(true);
        card.requestFocus();
        // 卡片先自己拿住焦点：不这样的话，弹层里第一个 EditText 会抢焦点，
        // ScrollView 跟着滚一屏，用户看到的就不是「刚打开时那个位置」了
        card.setFocusableInTouchMode(true);
        card.requestFocus();
        Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0x00000000));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            win.setGravity(Gravity.BOTTOM);
            syncSystemBars(host, win);
        }

        final Handle handle = new Handle(dlg, card, scrim, cardH);
        // 遮罩和横杠都关弹层；卡片内部不吞事件
        scrim.setOnClickListener(v -> handle.dismiss());
        barWrap.setOnClickListener(v -> handle.dismiss());

        dlg.setOnShowListener(x -> {
            card.setTranslationY(cardH);
            card.animate()
                    .translationY(0)
                    .setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
            scrim.setAlpha(0f);
            scrim.animate().alpha(0.38f).setDuration(220).start();
        });

        // 下拉横杠也能关：手指往下拖过 90dp 就松手关闭
        final float[] downY = {0f};
        barWrap.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                downY[0] = e.getRawY();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                float dy = Math.max(0, e.getRawY() - downY[0]);
                card.setTranslationY(dy);
                scrim.setAlpha(Math.max(0f, 0.38f * (1 - dy / cardH)));
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float dy = e.getRawY() - downY[0];
                if (dy > dp * 90) {
                    handle.dismiss();
                } else {
                    card.animate().translationY(0).setDuration(180)
                            .setInterpolator(new DecelerateInterpolator(1.4f)).start();
                    scrim.animate().alpha(0.38f).setDuration(180).start();
                }
                return true;
            }
            return false;
        });

        dlg.show();
        return handle;
    }

    /**
     * 弹窗状态栏修复：Dialog 用的 Theme_Translucent_NoTitleBar 是全屏半透明主题，
     * 弹窗窗口会接管状态栏/导航栏的装饰，而该主题默认不带夜间图标位、状态栏底色是黑的
     * —— 表现为弹层一开，顶部状态栏整条变黑，关掉又恢复。
     * 这里把宿主 Activity 当前的状态栏/导航栏颜色与亮暗图标位原样抄给弹窗窗口，
     * 让弹窗期间的系统栏观感与弹层打开前完全一致。
     */
    private static void syncSystemBars(Activity host, Window win) {
        Window hostWin = host.getWindow();
        if (hostWin == null) return;
        // setStatusBarColor 生效的前提：窗口自己画系统栏背景
        win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        win.setStatusBarColor(hostWin.getStatusBarColor());
        win.setNavigationBarColor(hostWin.getNavigationBarColor());
        View hostDecor = hostWin.getDecorView();
        View decor = win.getDecorView();
        int lightMask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int vis = decor.getSystemUiVisibility();
        vis = (vis & ~lightMask) | (hostDecor.getSystemUiVisibility() & lightMask);
        decor.setSystemUiVisibility(vis);
    }
}
