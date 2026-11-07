package com.tstc.zstangshi;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.util.Log;
import android.webkit.WebViewClient;
import android.widget.TextView;

/**
 * WebActivity —— 应用内浏览器，承载「学工系统」等外链（T14 补：原 openWebView 误降级为系统浏览器）。
 *
 * 为什么要有它：学工系统是独立站点，App 不解析 / 不缓存其数据，只负责把人送过去。
 * 但原实现把 openWebView 直接转成 ACTION_VIEW 外开，导致每次跳转都离开 App、丢失返回栈、
 * 且原生壳根本没接 WebView —— 与 web/pages/affairs.js 的「Android 端由原生 WebView 承载」描述不符。
 * 这里用一个带「返回 / 刷新 / 关闭」工具条的 WebView 在应用内承载，关闭即回到主页。
 *
 * 注意：本 Activity 只加载远程 http(s) 页面（如 http://sws.tstc.edu.cn/...），不涉及虚拟源；
 * 其明文访问由 network_security_config 对 tstc.edu.cn 子域放行（includeSubdomains=true）。
 *
 * 实现注：本类中所有内部类（WebViewClient / WebChromeClient / 三个 OnClickListener）都必须用
 * **static 嵌套类**而非非静态内部类 —— 本机 R8 8.2.2-dev 在 dexing 带 synthetic this$0 的
 * 非静态内部类时会 NPE（Cannot invoke "String.length()" because "<parameter1>" is null），
 * static 嵌套类不带 this$0，可正常 dexing。构造时把需要的 WebView / Activity 引用显式传入。
 */
public class WebActivity extends Activity {

    public static final String EXTRA_URL = "extra_url";

    private WebView webView;
    private TextView titleView;

    /**
     * 站内导航全部留在应用内，不跳系统浏览器（具名类规避 R8 匿名类 NPE）。
     *
     * 另外补一条协议兜底：教务站点对外只开 80 / 443，但两者可达性随网络环境变 ——
     * 校园网 443 正常，移动数据下 443 直接连不上、80 正常（2026-11-07 实测）。
     * 主框架加载失败时把 http/https 换一次重试，避免「浏览器能开、应用内一片空白」。
     */
    private static class AppWebViewClient extends WebViewClient {
        /** 只换一次，防止两边都失败时来回跳 */
        private boolean swapped = false;

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (swapped || request == null || !request.isForMainFrame()) return;
            String url = request.getUrl() == null ? "" : request.getUrl().toString();
            String alt = null;
            if (url.startsWith("https://")) alt = "http://" + url.substring("https://".length());
            else if (url.startsWith("http://")) alt = "https://" + url.substring("http://".length());
            if (alt == null || alt.isEmpty()) return;
            swapped = true;
            Log.d("zsts-cookie", "WebView 主框架加载失败，换协议重试：" + alt);
            view.loadUrl(alt);
        }
    }

    /** 同步页面标题到工具条（具名类，构造时传入 titleView） */
    private static class AppWebChromeClient extends WebChromeClient {
        private final TextView titleView;
        AppWebChromeClient(TextView titleView) { this.titleView = titleView; }
        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (title != null && !title.isEmpty()) titleView.setText(title);
        }
        // 临时诊断（用后即删）：转发 JS console 到 logcat，便于定位数据层降级原因
        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
            Log.d("zsts-console", message.message());
            return true;
        }
    }

    /** 返回：能后退则后退，否则关闭 Activity（static 嵌套类，规避 R8 非静态内部类 NPE） */
    private static class BackClickListener implements View.OnClickListener {
        private final WebActivity activity;
        private final WebView webView;
        BackClickListener(WebActivity activity, WebView webView) { this.activity = activity; this.webView = webView; }
        @Override public void onClick(View v) {
            if (webView != null && webView.canGoBack()) webView.goBack();
            else activity.finish();
        }
    }

    /** 刷新：重新加载当前页面 */
    private static class RefreshClickListener implements View.OnClickListener {
        private final WebView webView;
        RefreshClickListener(WebView webView) { this.webView = webView; }
        @Override public void onClick(View v) { if (webView != null) webView.reload(); }
    }

    /** 关闭：直接结束 Activity，回到主页 */
    private static class CloseClickListener implements View.OnClickListener {
        private final WebActivity activity;
        CloseClickListener(WebActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) { activity.finish(); }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web);

        webView = findViewById(R.id.web_webview);
        titleView = findViewById(R.id.web_title);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppWebChromeClient(titleView));

        TextView back = findViewById(R.id.web_back);
        TextView refresh = findViewById(R.id.web_refresh);
        TextView close = findViewById(R.id.web_close);
        back.setOnClickListener(new BackClickListener(this, webView));
        refresh.setOnClickListener(new RefreshClickListener(webView));
        close.setOnClickListener(new CloseClickListener(this));

        String url = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) url = "about:blank";
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        if (webView != null) webView.onResume();
        super.onResume();
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
