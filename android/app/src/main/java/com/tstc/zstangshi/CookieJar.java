package com.tstc.zstangshi;

import android.util.Log;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CookieJar —— 打通 WebView 与 HttpURLConnection 的会话（R2，T14 最容易踩的坑）。
 *
 * 背景：android.webkit.CookieManager 只服务于 WebView 网络栈，java.net.HttpURLConnection
 * 走 java.net.CookieHandler，两者默认互不相通；登录成功但后续请求全 302 回登录页就是这么来的。
 *
 * 本实现让 **WebView CookieManager 成为唯一存储**，并装一个默认 CookieHandler 做桥：
 *   · 请求侧：execute() 每次 hop 显式 getCookie(url) 注入 Cookie 头（WebView → Java）。
 *     CookieHandler.get() 故意返回空 Map，避免 HttpURLConnection 再加一条重复的 Cookie 头。
 *   · 响应侧：CookieHandler.put() 与 syncSetCookie() 双保险把每条 Set-Cookie 写回
 *     CookieManager 并 flush()（Java → WebView）。
 *
 * 为什么手动跟随 302：docs/remote-api-map.md §6.2 实测登录是两级 302，POST 响应会换发
 * JSESSIONID；如果复用一次构造好的 Cookie 头，第二次跳转带着旧会话，会被静默踢回登录页。
 * 这里 setInstanceFollowRedirects(false)，每个 hop 新建连接、重新取 Cookie，最多 5 跳同 host。
 */
public final class CookieJar {

    private static final String TAG = "zsts-cookie";
    /** 与 transport.js DEFAULT_TIMEOUT = 15000 对齐 */
    static final int TIMEOUT_MS = 15000;
    private static final int MAX_HOPS = 5;
    private static volatile boolean installed = false;

    private CookieJar() { }

    /** 安装默认 CookieHandler（幂等；MainActivity.onCreate 与每次请求前都会调）。 */
    public static synchronized void install() {
        if (installed) return;
        CookieHandler.setDefault(new WebViewCookieHandler());
        installed = true;
    }

    /** 退出登录：清 WebView Cookie 与凭据存储（§8 共享知识第 10 条）。 */
    public static void clear() {
        CookieManager.getInstance().removeAllCookies((ok) ->
                Log.d(TAG, "clear(): removeAllCookies done ok=" + ok));
        CookieManager.getInstance().flush();
        Log.d(TAG, "clear(): called");
    }

    /**
     * 执行一次请求，手动跟随重定向。
     *
     * @param url         目标 URL
     * @param method      GET / POST / ...
     * @param headers     请求头（JSON 对象，键大小写不敏感由 setRequestProperty 处理）
     * @param body        请求体字符串，空串表示无 body
     * @return Response   status / headers(JSON) / body / 最终 url
     */
    public static Response execute(String url, String method, JSONObject headers, String body) throws Exception {
        install();
        String current = url;
        String verb = normalizeMethod(method);
        String payload = body == null ? "" : body;

        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false); // 手动跟随：每个 hop 重新取最新 Cookie
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod(verb);
            applyHeaders(conn, headers);

            // ★ R2 方向一：把 WebView 里的最新 Cookie 注入本次请求
            String cookie = CookieManager.getInstance().getCookie(current);
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            Log.d(TAG, "execute " + verb + " " + current + " cookieLen="
                    + (cookie == null ? 0 : cookie.length()));

            boolean hasBody = !payload.isEmpty()
                    && (verb.equals("POST") || verb.equals("PUT") || verb.equals("PATCH"));
            if (hasBody) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload.getBytes(StandardCharsets.UTF_8));
                }
            }

            int status = conn.getResponseCode();
            Map<String, List<String>> fields = conn.getHeaderFields();
            // ★ R2 方向二：响应里的 Set-Cookie 逐条回写 WebView（CookieHandler.put 之外再兜一层）
            syncSetCookie(current, fields);

            if (isRedirect(status)) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) throw new IOException("重定向缺少 Location 头");
                current = new URL(new URL(current), location).toString();
                // 浏览器语义：301/302 的 POST 与 303 一律改成 GET，307/308 保留原方法与 body
                if (status == 303 || ((status == 301 || status == 302) && verb.equals("POST"))) {
                    verb = "GET";
                    payload = "";
                }
                if (hop == MAX_HOPS) throw new IOException("重定向超过 " + MAX_HOPS + " 次");
                continue;
            }

            String responseBody = readBody(conn);
            JSONObject responseHeaders = headerJson(fields);
            conn.disconnect();
            Log.d(TAG, "respond " + status + " " + current + " bodyLen=" + responseBody.length());
            return new Response(status, responseHeaders, responseBody, current);
        }
        throw new IOException("重定向循环");
    }

    private static void applyHeaders(HttpURLConnection conn, JSONObject headers) {
        if (headers == null) return;
        java.util.Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = headers.optString(key, "");
            if (key == null || key.isEmpty()) continue;
            conn.setRequestProperty(key, value);
        }
    }

    private static String normalizeMethod(String method) {
        String upper = String.valueOf(method).toUpperCase(Locale.ROOT);
        switch (upper) {
            case "GET": case "POST": case "PUT": case "PATCH":
            case "DELETE": case "HEAD": case "OPTIONS":
                return upper;
            default:
                return "GET";
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /** 逐条 setCookie + flush；同一个 name/domain 的重复写入由 CookieManager 自行覆盖。 */
    static void syncSetCookie(String url, Map<String, List<String>> fields) {
        if (fields == null) return;
        CookieManager manager = CookieManager.getInstance();
        boolean changed = false;
        for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
            String key = entry.getKey();
            if (key == null || (!key.equalsIgnoreCase("Set-Cookie") && !key.equalsIgnoreCase("Set-Cookie2"))) continue;
            List<String> values = entry.getValue();
            if (values == null) continue;
            for (String value : values) {
                if (value == null || value.isEmpty()) continue;
                manager.setCookie(url, value);
                changed = true;
            }
        }
        if (changed) manager.flush();
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        InputStream in;
        try {
            in = conn.getInputStream();
        } catch (IOException e) {
            // 4xx/5xx 时 getInputStream() 会直接抛，错误体在 getErrorStream()
            in = conn.getErrorStream();
        }
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
            byte[] bytes = out.toByteArray();
            Charset charset = charsetOf(conn.getContentType());
            return new String(bytes, charset);
        }
    }

    /** Content-Type 里带 charset 就按它解码，否则 UTF-8（教务页面实测 UTF-8）。 */
    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            int at = lower.indexOf("charset=");
            if (at >= 0) {
                String name = lower.substring(at + 8).replaceAll("[;\\s\"'].*$", "").trim();
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) { /* 落到 UTF-8 */ }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 响应头归一成全小写键的 JSON 对象（transport.js 的 normalizeHeaders 会再转一层）。 */
    private static JSONObject headerJson(Map<String, List<String>> fields) {
        JSONObject out = new JSONObject();
        if (fields == null) return out;
        for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (key == null || values == null || values.isEmpty()) continue;
            try {
                out.put(key.toLowerCase(Locale.ROOT), join(values));
            } catch (Exception ignored) { /* 非法键跳过 */ }
        }
        return out;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(value);
        }
        return sb.toString();
    }

    /** 一次请求的结果（NativeBridge 据此拼回调 JSON）。 */
    static final class Response {
        final int status;
        final JSONObject headers;
        final String body;
        final String url;

        Response(int status, JSONObject headers, String body, String url) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.url = url;
        }
    }

    /**
     * 默认 CookieHandler。get() 返回空 Map（请求侧由 execute() 显式注入，防止重复 Cookie 头）；
     * put() 把响应 Set-Cookie 回写到 WebView CookieManager，完成「Java → WebView」方向的双写。
     */
    static final class WebViewCookieHandler extends CookieHandler {
        @Override
        public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
            return Collections.emptyMap();
        }

        @Override
        public void put(URI uri, Map<String, List<String>> responseHeaders) {
            if (uri == null) return;
            syncSetCookie(uri.toString(), responseHeaders);
        }
    }
}
