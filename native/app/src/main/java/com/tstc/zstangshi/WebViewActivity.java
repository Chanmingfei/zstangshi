package com.tstc.zstangshi;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 内置浏览器（教务 / 学工那些没有原生接口的页面）。
 *
 * 为什么不跳系统浏览器：教务系统要登录态，跳到浏览器等于换了一个会话，
 * 用户还得再登一次；这里和 App 共享 Cookie，登录过就是登录状态。
 *
 * 也顺手接管了返回键：能后退就后退，退到头才关页面 —— 比直接把用户弹回桌面友好。
 */
public class WebViewActivity extends BaseActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private WebView web;
    private ProgressBar bar;
    /** 整页的圆角卡片进出场（见 ui/PageSlide）。 */
    private com.tstc.zstangshi.ui.PageSlide slide;

    public static void open(android.app.Activity host, String url, String title) {
        Intent i = new Intent(host, WebViewActivity.class);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_TITLE, title);
        host.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String url = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TITLE);
        if (url == null || url.isEmpty()) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pageColor());

        // ── 顶栏：统一 HeaderBar，返回箭头与标题对齐 ──
        LinearLayout head = com.tstc.zstangshi.ui.HeaderBar.create(this,
                title == null || title.isEmpty() ? "教务系统" : title,
                v -> onBackPressed());
        head.addView(com.tstc.zstangshi.ui.HeaderBar
                .action(this, "刷新", v -> web.reload()));
        root.addView(head);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " ZSTS/3.0");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        // 具名内部类而不是匿名类：d8 遇到「匿名类 + @Override 框架方法」会内部报错
        web.setWebViewClient(new PlainClient(this));
        web.setWebChromeClient(new ProgressClient(bar));

        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applyInsets(root);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);

        // 整页圆角卡片进场：与系统预测性返回动画同一个圆角半径
        slide = new com.tstc.zstangshi.ui.PageSlide(this, pageColor());
        slide.enter();

        backRoot = root;
        syncBack();
        web.loadUrl(url);
    }

    // ---------- 预测性返回 ----------
    //
    // 分两种情况，两者都用 framework 的 OnBackInvokedCallback：
    //   · 网页还能后退 → 网页自己跟手右移，松手 goBack；
    //   · 网页已到栈底（要真正关掉这个页面）→ 把返回交给 PageSlide，整张圆角卡片
    //     跟手滑出、松手 finish，和从一级页进这个页面的动画是同一套。

    private Object backToken;
    private View backRoot;

    /** 有历史时接管给网页，退到栈底再交给整页滑动出场。 */
    void syncBack() {
        if (!com.tstc.zstangshi.ui.BackGestures.supported()) return;
        boolean want = web != null && web.canGoBack();
        try {
            if (want && backToken == null) {
                // 网页要自己用返回键，整页出场先让开
                if (slide != null) slide.detachBack();
                backToken = com.tstc.zstangshi.ui.BackGestures
                        .install(this, new BackHandler(this));
            } else if (!want && backToken != null) {
                com.tstc.zstangshi.ui.BackGestures.uninstall(this, backToken);
                backToken = null;
            }
            if (!want && slide != null) slide.attachBack();
        } catch (Throwable ignored) {
        }
    }

    void backStart() {
        if (backRoot != null) {
            backRoot.animate().cancel();
            backRoot.setTranslationX(0f);
            backRoot.setAlpha(1f);
        }
    }

    /** 跟手：整页随手指右移（纯位移，不改变不透明度）。 */
    void backProgress(float p) {
        if (backRoot == null) return;
        int w = backRoot.getWidth();
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
        backRoot.setTranslationX(p * w * 0.3f);
    }

    void backCommit() {
        View root = backRoot;
        if (root == null) {
            if (web != null && web.canGoBack()) web.goBack();
            return;
        }
        // 能注册到这里，说明网页一定有历史：退回上一屏，页面滑回原位
        root.animate().cancel();
        if (web != null && web.canGoBack()) web.goBack();
        root.animate().translationX(0f)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    void backCancel() {
        if (backRoot == null) return;
        backRoot.animate().cancel();
        backRoot.animate().translationX(0f)
                .setDuration(190)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** 静态嵌套类（d8 不允许非静态内部类）。 */
    private static final class BackHandler
            implements com.tstc.zstangshi.ui.BackGestures.Handler {
        private final WebViewActivity host;

        BackHandler(WebViewActivity h) {
            host = h;
        }

        @Override
        public void onStart() {
            host.backStart();
        }

        @Override
        public void onProgress(float progress) {
            host.backProgress(progress);
        }

        @Override
        public void onCommit() {
            host.backCommit();
        }

        @Override
        public void onCancel() {
            host.backCancel();
        }
    }

    private void applyInsets(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                // 窗口是半透明的，adjustResize 不一定可靠：这里自己把输入法高度让出来，
                // 免得页面里的搜索框被键盘盖住
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
                return insets;
            });
        } else {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            root.setPadding(0, id > 0 ? getResources().getDimensionPixelSize(id) : 0, 0, 0);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        // 13 起由系统的预测性返回负责退场，别叠我们自己的滑动
        if (slide != null && !com.tstc.zstangshi.ui.BackGestures.supported()) {
            slide.finish();
            return;
        }
        super.onBackPressed();
    }

    /**
     * 主框架加载失败时，https 自动换 http 再试一次。
     *
     * 唐师的教务在移动数据下 443 不通、只有 80 —— 旧版就是靠这个探测活下来的，
     * 这里保留：不弹错误页，静默降级。
     */
    private static final class PlainClient extends WebViewClient {
        private final WebViewActivity host;
        private boolean retried;

        PlainClient(WebViewActivity h) {
            host = h;
        }

        /** 每翻一页都重新判断「还能不能后退」，手势接管与否跟着变。 */
        @Override
        public void doUpdateVisitedHistory(WebView v, String u, boolean isReload) {
            host.syncBack();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, String u) {
            return false;
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
            if (req == null || !req.isForMainFrame() || retried) return;
            String u = String.valueOf(req.getUrl());
            if (!u.startsWith("https://")) return;
            retried = true;
            v.loadUrl("http://" + u.substring("https://".length()));
        }
    }

    private static final class ProgressClient extends WebChromeClient {
        private final ProgressBar bar;

        ProgressClient(ProgressBar bar) {
            this.bar = bar;
        }

        @Override
        public void onProgressChanged(WebView v, int p) {
            bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            bar.setProgress(p);
        }
    }
}
