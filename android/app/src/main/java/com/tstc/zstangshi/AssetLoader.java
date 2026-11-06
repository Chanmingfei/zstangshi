package com.tstc.zstangshi;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AssetLoader —— 从 assets/www 供给虚拟源 http://app.local/ 的全部静态资源。
 *
 * 两条硬约束（R3）：
 *   1. 只处理 host == app.local，其它交给 WebView / 外部浏览器。
 *   2. 找不到文件必须返回一个 404 WebResourceResponse，**绝不 return null** ——
 *      return null 会让 WebView 真的向 app.local 发起网络请求，直接白屏。
 *
 * .js / .mjs 必须是 text/javascript：写成 application/javascript 或 text/plain 时，
 * Chrome 会以严格 MIME 检查拒绝 <script type="module">，整套 ESM 白屏。
 */
public class AssetLoader extends WebViewClient {

    private static final String APP_HOST = "app.local";
    private static final byte[] EMPTY = new byte[0];

    private final Context context;

    public AssetLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (uri == null || !APP_HOST.equals(uri.getHost())) return null;

        Map<String, String> headers = responseHeaders();
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";
        // 目录穿越：既拦字面 ..，也拦百分号编码（含大小写）
        if (path.contains("..") || path.contains("%2e") || path.contains("%2E")) {
            return error(403, "Forbidden", headers);
        }

        try {
            InputStream stream = context.getAssets().open("www" + path);
            String mime = mimeOf(path);
            // 注意：mimeOf 返回的 MIME 已含 "; charset=utf-8"，不要再额外传 encoding，
            // 否则 Content-Type 出现双重 charset，部分 WebView 会拒绝按 HTML 解析而把
            // 源码当纯文本显示。统一把 encoding 置 null，由 MIME 里的 charset 决定编码。
            return new WebResourceResponse(mime, null, 200, "OK", headers, stream);
        } catch (Exception e) {
            return error(404, "Not Found", headers);
        }
    }

    /** 非 app.local 的 http(s) / tel / mailto 一律用系统浏览器外开（学工页、教务处链接）。 */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return override(request == null ? null : request.getUrl());
    }

    /** 兼容旧签名（minSdk 26 用不到，留着防止 WebView 实现差异）。 */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return override(Uri.parse(url));
    }

    private boolean override(Uri uri) {
        if (uri == null) return false;
        if (APP_HOST.equals(uri.getHost())) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        String s = scheme.toLowerCase(Locale.ROOT);
        if (s.equals("http") || s.equals("https") || s.equals("tel") || s.equals("mailto")) {
            openExternal(uri.toString());
            return true;
        }
        return false;
    }

    private void openExternal(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
            // 没有可处理该 Intent 的 Activity：静默失败，不让 WebView 崩
        }
    }

    /** 开发期禁缓存；CORS 头让 ESM / fetch 在虚拟源上不被同源策略误伤。 */
    private static Map<String, String> responseHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.put("Access-Control-Allow-Origin", "*");
        return headers;
    }

    private static WebResourceResponse error(int status, String reason, Map<String, String> headers) {
        return new WebResourceResponse(
                "text/plain; charset=utf-8", "UTF-8", status, reason, headers,
                new ByteArrayInputStream(EMPTY));
    }

    /** 按扩展名注入 MIME（唯一真源，新增资源类型时只改这里）。 */
    private static String mimeOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot);
        switch (ext) {
            case ".html": case ".htm": return "text/html; charset=utf-8";
            case ".js": case ".mjs": return "text/javascript; charset=utf-8";
            case ".css": return "text/css; charset=utf-8";
            case ".json": return "application/json; charset=utf-8";
            case ".svg": return "image/svg+xml";
            case ".webmanifest": return "application/manifest+json";
            case ".png": return "image/png";
            case ".jpg": case ".jpeg": return "image/jpeg";
            case ".webp": return "image/webp";
            case ".gif": return "image/gif";
            case ".ico": return "image/x-icon";
            case ".txt": return "text/plain; charset=utf-8";
            default: return "application/octet-stream";
        }
    }
}
