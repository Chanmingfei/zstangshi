package com.tstc.zstangshi;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.Keyframe;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tstc.zstangshi.data.Store;
import com.tstc.zstangshi.jw.Auth;
import com.tstc.zstangshi.widget.TodayWidget;

/**
 * 冷启动的第一屏（加载页）。
 *
 * 版式照旧版 index.html 的 #boot-splash：校徽横版图（缓慢呼吸）+ 掌上唐师 +
 * 标语 + 三颗错开呼吸的圆点 —— 它和登录页品牌区同源，从加载页切到登录页不会「跳一下」。
 *
 * 两条路：
 *   · 勾了自动登录、本地又有账号密码 —— 停在这里显示「自动登录中」，后台登教务，
 *     成功了直接进主界面，课表过期就顺手同步一次；
 *   · 没勾自动登录 —— 亮一下就去登录页（带 350ms 过渡，避免闪一下就跳）。
 *
 * 这里同时也是「点桌面图标」的唯一入口；桌面小组件是另一条线，它直接跳
 * MainActivity，不经过登录 —— 断网时点小组件也得能看课表。
 */
public class SplashActivity extends BaseActivity {

    /** 自动登录最长等这么久，超时就去登录页，不让人对着转圈干等 */
    private static final long TIMEOUT_MS = 25_000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    /** 自动登录期间的快捷入口：不等教务，先去实时公交。 */
    private TextView skipBtn;
    private boolean left;
    /** 用户点了「直接进入实时公交」：登录留在后台，回加载页时再接去主界面。 */
    private boolean busJumped;
    /** 后台登录是否已结束（busJumped 时用它决定回到加载页后怎么走）。 */
    private boolean loginDone;
    private boolean loginOk;
    /** 加载页当前是否在前台（busJumped 后回来时要不要立刻进主界面）。 */
    private boolean resumed;
    private Store store;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(pageColor());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        box.setPadding(pad, pad, pad, pad);

        // ── 校徽 + 校名（呼吸：缩放 1 → 0.995，透明度 1 → 0.86，2.6s 一循环） ──
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.login_badge);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(logo, new LinearLayout.LayoutParams(dp(200),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        breathe(logo);

        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextSize(22);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getColor(R.color.text_primary));
        name.setGravity(Gravity.CENTER);
        name.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(16);
        box.addView(name, nlp);

        TextView slogan = new TextView(this);
        slogan.setText("唐山师范学院 · 教务数据随身看");
        slogan.setTextSize(12.5f);
        slogan.setTextColor(getColor(R.color.text_secondary));
        slogan.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        box.addView(slogan, slp);

        // ── 三颗呼吸圆点（错开 160ms，比转圈轻，不跟校徽抢注意力） ──
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            View dot = new View(this);
            GradientDrawable c = new GradientDrawable();
            c.setShape(GradientDrawable.OVAL);
            c.setColor(getColor(R.color.brand));
            dot.setBackground(c);
            dot.setAlpha(0.24f);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6));
            if (i > 0) dlp.leftMargin = dp(8);
            dots.addView(dot, dlp);
            pulse(dot, i * 160L);
        }
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(32);
        box.addView(dots, dlp);

        status = new TextView(this);
        status.setTextSize(11.5f);
        status.setTextColor(getColor(R.color.text_tertiary));
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(14);
        box.addView(status, stlp);

        // 教务自动登录有时要等很久：给个横条按钮，不想等的人先进实时公交，
        // 登录在后台接着跑，回到这页时如果已经登好就直接送进主界面。
        // 位置放在加载动画正下方 —— 大拇指自然悬停的高度，不用伸到底部够；
        // 样式和 APP 其他主按钮一致：圆角矩形（12dp）
        box.setMinimumWidth(dp(280));
        skipBtn = new TextView(this);
        skipBtn.setText("直接进入实时公交");
        skipBtn.setTextSize(14.5f);
        skipBtn.setTypeface(Typeface.DEFAULT_BOLD);
        skipBtn.setTextColor(0xFFFFFFFF);
        skipBtn.setGravity(Gravity.CENTER);
        GradientDrawable skipBg = new GradientDrawable();
        skipBg.setColor(getColor(R.color.brand));
        skipBg.setCornerRadius(dp(12));
        skipBtn.setBackground(skipBg);
        skipBtn.setOnClickListener(v -> goBus());
        LinearLayout.LayoutParams skipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        skipLp.topMargin = dp(24);
        box.addView(skipBtn, skipLp);

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.CENTER;
        root.addView(box, boxLp);
        setContentView(root);

        if (!Auth.canAutoLogin(store)) {
            // 没开自动登录：亮一下就去登录页（旧版这里只有圆点，不写文案）
            status.setText("");
            skipBtn.setVisibility(View.GONE);
            ui.postDelayed(this::goLogin, 350);
            return;
        }

        status.setText("自动登录中");

        ui.postDelayed(() -> {
            if (left || busJumped) return;   // 已经进实时公交的就别再踢去登录页了
            goLogin("自动登录超时了，请手动登录一次");
        }, TIMEOUT_MS);

        new Thread(() -> {
            Auth.Outcome o = Auth.loginAndMaybeSync(store.user(), store.pass(), store, false);
            ui.post(() -> {
                if (left) return;
                loginDone = true;
                loginOk = o.ok;
                if (busJumped) {
                    // 人已经在实时公交里：不打扰，只把结果记下，等回到这页再接走
                    if (o.ok) {
                        TodayWidget.refreshAll(this);
                        status.setText("教务登录完成，返回后自动进入主界面");
                        if (resumed) goMain();
                    } else {
                        status.setText(o.message);
                        skipBtn.setText("去手动登录");
                        skipBtn.setOnClickListener(v -> goLogin(o.message));
                    }
                    return;
                }
                if (o.ok) {
                    TodayWidget.refreshAll(this);
                    goMain();
                } else {
                    goLogin(o.message);
                }
            });
        }).start();
    }

    /**
     * 「直接进入实时公交」：加载页留在任务栈里，登录继续在后台跑。
     * 可以反复点 —— 从实时公交退回来（登录还没好）再点一次要照样能进。
     */
    private void goBus() {
        if (left) return;
        busJumped = true;
        status.setText("教务登录已转到后台，正在进入实时公交…");
        startActivity(new Intent(this, BusActivity.class));
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // 从实时公交退回来时，后台登录多半已经完成 —— 别让人再对着加载页等
        if (busJumped && loginDone && loginOk && !left) goMain();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    /** 校徽呼吸：透明度 1 ↔ 0.86，缩放 1 ↔ 0.995，2.6s 一轮。 */
    private void breathe(View v) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.86f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.995f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.995f));
        a.setDuration(1300);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.start();
    }

    /**
     * 圆点呼吸：0.24/0.86 起，40% 到 1/1，再回 0.24/0.86，1.2s 一轮。
     * 用 Keyframe 而不是 REVERSE —— 原版峰值在 40% 处，不是正中。
     */
    private void pulse(View dot, long delayMs) {
        PropertyValuesHolder alpha = PropertyValuesHolder.ofKeyframe(View.ALPHA,
                Keyframe.ofFloat(0f, 0.24f),
                Keyframe.ofFloat(0.4f, 1f),
                Keyframe.ofFloat(1f, 0.24f));
        PropertyValuesHolder sx = PropertyValuesHolder.ofKeyframe(View.SCALE_X,
                Keyframe.ofFloat(0f, 0.86f),
                Keyframe.ofFloat(0.4f, 1f),
                Keyframe.ofFloat(1f, 0.86f));
        PropertyValuesHolder sy = PropertyValuesHolder.ofKeyframe(View.SCALE_Y,
                Keyframe.ofFloat(0f, 0.86f),
                Keyframe.ofFloat(0.4f, 1f),
                Keyframe.ofFloat(1f, 0.86f));
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(dot, alpha, sx, sy);
        a.setDuration(1200);
        a.setStartDelay(delayMs);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void goLogin() {
        goLogin("");
    }

    private void goLogin(String message) {
        Intent i = new Intent(this, LoginActivity.class);
        i.putExtra(LoginActivity.EXTRA_MESSAGE, message == null ? "" : message);
        startActivity(i);
        left = true;
        finish();
        overridePendingTransition(0, 0);
    }

    private void goMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        left = true;
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
