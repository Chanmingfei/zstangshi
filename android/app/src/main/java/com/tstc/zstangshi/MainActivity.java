package com.tstc.zstangshi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * MainActivity —— 手写 WebView 壳（零第三方依赖，不用 AppCompatActivity / WebViewAssetLoader）。
 *
 * 为什么用虚拟源 https://app.local/（架构偏差 D2、风险 R3）：
 *   file:// 下 origin 为 null，<script type="module"> 会被 CORS 拒绝，整套 ESM 白屏；
 *   且部分 WebView 版本在 file:// 下 localStorage 不可写。虚拟 https 源一次解决
 *   ESM / Storage / secure-context 三件事，静态资源由 AssetLoader.shouldInterceptRequest 供给。
 *
 * 为什么不用 BuildConfig.DEBUG：app/build.gradle 关掉了 buildConfig，所以 WebView 调试
 * 直接用常量开关（debug 与 release 都开，便于用户 chrome://inspect 自查验收项 ③）。
 */
public class MainActivity extends Activity {

    /** 虚拟静态源：host 必须与 AssetLoader 的判定一致。
     *  用 http:// 而非 https://：自定义 https 域在被 shouldInterceptRequest 拦截时，
     *  Chrome 仍会因「缺少真实证书」拒绝把主文档提交为 HTML，转而以纯文本显示源码；
     *  http:// 无此证书门槛。app.local/localhost 属「potentially trustworthy」源，
     *  仍为 secure-context，crypto.subtle / localStorage 等照常可用。 */
    static final String APP_HOST = "http://app.local/index.html";

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView.setWebContentsDebuggingEnabled(true);

        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.bg_page));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(false);
        // 系统字号放大会把 320px 布局撑破，固定 100%
        settings.setTextZoom(100);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // 静态资源走 assets + shouldInterceptRequest，不需要 WebView 自己读文件
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new AssetLoader(this));
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new NativeBridge(this, webView), "NativeBridge");

        // JS → Native 的 9 个 API 都挂在 window.NativeBridge 上；CookieJar 在首次请求时安装
        CookieJar.install();
        loadApp();
    }

    /**
     * 加载主文档。
     *
     * 关键坑（实测 Chromium 147）：若用 webView.loadUrl("http://app.local/index.html")
     * 让 shouldInterceptRequest 拦截并返回主文档，部分 WebView 会把主资源「以纯文本显示源码」
     * 而非提交为 HTML 文档（即便 MIME 是 text/html 也照旧）。解决办法是主文档用
     * loadDataWithBaseURL 直接灌入，base 仍指向 http://app.local，使文档 origin 为
     * app.local（非 null），满足 ESM / localStorage / crypto.subtle 的同源与 secure-context
     * 要求；CSS / JS 等子资源仍由 AssetLoader.shouldInterceptRequest 从 assets/www 供给。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void loadApp() {
        try {
            InputStream is = getAssets().open("www/index.html");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();
            String html = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            webView.loadDataWithBaseURL("http://app.local/", html, "text/html", "UTF-8", "http://app.local/");
        } catch (IOException e) {
            // 兜底：极个别机型 loadDataWithBaseURL 异常时退回虚拟源直连
            webView.loadUrl(APP_HOST);
        }
    }

    /**
     * 硬件返回键：先问 Web 侧的路由（router.js 暴露 window.__appBack()）。
     * true = 已被次级页/弹层消费；false = 已在 Tab 根页，交给宿主退出 App。
     */
    @Override
    public void onBackPressed() {
        if (webView == null) {
            finish();
            return;
        }
        webView.evaluateJavascript(
                "window.__appBack ? window.__appBack() : false",
                value -> {
                    if (!"true".equals(value)) finish();
                });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
