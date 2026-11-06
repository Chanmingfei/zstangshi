package com.tstc.zstangshi;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.math.BigInteger;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.net.ssl.SSLException;

/**
 * NativeBridge —— window.NativeBridge 背后的 9 个 JS 接口方法。
 *
 * 协议以 **代码实际调用** 为准，docs/ARCHITECTURE.md §4.9 只是设计稿，两处偏差必须记住：
 *   1. httpRequest(reqId, url, method, headersJson, body) —— **url 在 method 前**
 *      （web/core/transport.js:185-191）。§4.9 写成 (reqId, method, url, ...) 是反的。
 *      回调 window.__nativeHttpCallback({reqId,status,headers,body,url})；失败传 {reqId,error}。
 *      transport.js 读的是 data.url（不是文档里的 finalUrl），所以两个键都给上。
 *   2. 第 9 个 API 是 rsaEncrypt(modulus, exponent, plain)（web/data/jwxtClient.js:175-176）。
 *      漏了它，真机登录会去请求 Android 上不存在的 /__zsts/encrypt，登录彻底不可用。
 *
 * JS 接口方法运行在 JavaBridge 线程，不在 UI 线程：网络走 ExecutorService，
 * 回调与 Activity 跳转一律 webView.post() 回主线程。
 */
public class NativeBridge {

    private final Context context;
    private final WebView webView;
    private final SecurePrefs prefs;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public NativeBridge(Context context, WebView webView) {
        this.context = context.getApplicationContext();
        this.webView = webView;
        this.prefs = new SecurePrefs(this.context);
    }

    /* ═══ 9 个 JS 接口方法 ═════════════════════════════════════════════════ */

    @JavascriptInterface
    public boolean isNative() {
        return true;
    }

    @JavascriptInterface
    public String getAppVersion() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "1.0.0" : info.versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    /** 异步 HTTP。参数顺序见文件头；headersJson 是 JSON 字符串，body 为空串表示无 body。 */
    @JavascriptInterface
    public void httpRequest(String reqId, String url, String method, String headersJson, String body) {
        final String id = String.valueOf(reqId);
        executor.execute(() -> {
            String payload;
            try {
                JSONObject headers = headersJson == null || headersJson.isEmpty()
                        ? new JSONObject() : new JSONObject(headersJson);
                CookieJar.Response res = CookieJar.execute(url, method, headers, body);
                payload = successPayload(id, res);
            } catch (Exception e) {
                payload = errorPayload(id, e);
            }
            postCallback(payload);
        });
    }

    /**
     * 密码 RSAES-PKCS1-v1_5（等价 RSA/ECB/PKCS1Padding），密文 Base64 字符串返回。
     * 不做字节反转（docs/remote-api-map.md §6.1 已用 Node crypto 往返验证）。
     * 失败返回空串 —— jwxtClient 会明确报错，绝不明文提交密码。
     */
    @JavascriptInterface
    public String rsaEncrypt(String modulus, String exponent, String plain) {
        try {
            byte[] mod = Base64.decode(String.valueOf(modulus).trim(), Base64.DEFAULT);
            // 公钥 modulus 的 base64 偶尔带一个 ASN.1 符号位（129 字节且首字节 0x00），剥掉
            if (mod.length == 129 && mod[0] == 0) mod = Arrays.copyOfRange(mod, 1, mod.length);
            byte[] exp = Base64.decode(String.valueOf(exponent).trim(), Base64.DEFAULT);
            BigInteger m = new BigInteger(1, mod);
            BigInteger e = new BigInteger(1, exp);
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(m, e));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] out = cipher.doFinal(String.valueOf(plain).getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** 退出登录：清 WebView Cookie + 清加密凭据（§8 共享知识第 10 条）。 */
    @JavascriptInterface
    public void clearSession() {
        android.util.Log.d("zsts-cookie", "clearSession() invoked from JS");
        CookieJar.clear();
        prefs.clear();
    }

    @JavascriptInterface
    public boolean saveCredential(String json) {
        return json != null && prefs.save(json);
    }

    @JavascriptInterface
    public String loadCredential() {
        String value = prefs.load();
        return value == null ? "" : value;
    }

    @JavascriptInterface
    public boolean clearCredential() {
        return prefs.clear();
    }

    /**
     * web/pages/affairs.js:117 实际调用名是 openWebView（文档 §4.9 写的是 openExternal）。
     * 在应用内 WebView 承载外链（学工系统等），关闭后回到主页；不再跳系统浏览器。
     */
    @JavascriptInterface
    public void openWebView(String url) {
        try {
            Intent intent = new Intent(context, WebActivity.class);
            intent.putExtra(WebActivity.EXTRA_URL, String.valueOf(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
            // 极端情况下（无可用 Activity）静默失败，页面自身有兜底
        }
    }

    /** 登录页「连不上教务系统」弹窗：跳转系统 Wi-Fi 设置页（校园网环境要求）。 */
    @JavascriptInterface
    public void openWifiSettings() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
            // 极端情况下无可用设置页：静默失败，页面仍停留在登录页
        }
    }

    /* ═══ 内部实现 ═══════════════════════════════════════════════════════ */

    /** 文档里的 openExternal：非 app.local 的 http(s) 用 ACTION_VIEW 外开，Java 侧共用。 */
    public void openExternal(String url) {
        webView.post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.valueOf(url)));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {
                // 没有可处理的 Activity：静默失败
            }
        });
    }

    /** 回调载荷作为「JSON 字符串」传入，transport.js 会先 JSON.parse 再按对象处理。 */
    private void postCallback(String payload) {
        webView.post(() -> {
            try {
                String arg = JSONObject.quote(payload);
                webView.evaluateJavascript(
                        "if (window.__nativeHttpCallback) window.__nativeHttpCallback(" + arg + ");",
                        null);
            } catch (Exception ignored) {
                // WebView 已销毁：回调丢弃，transport.js 的 15s 超时会兜底
            }
        });
    }

    private static String successPayload(String reqId, CookieJar.Response res) throws Exception {
        JSONObject out = new JSONObject();
        out.put("reqId", reqId);
        out.put("status", res.status);
        out.put("headers", res.headers);
        out.put("body", res.body == null ? "" : res.body);
        out.put("url", res.url);
        out.put("finalUrl", res.url); // 兼容 §4.9 文档字段名，transport.js 实际读 url
        return out.toString();
    }

    private static String errorPayload(String reqId, Exception error) {
        String code = "IO";
        if (error instanceof SocketTimeoutException) code = "TIMEOUT";
        else if (error instanceof SSLException) code = "SSL";
        else if (error instanceof java.io.IOException) code = "NETWORK";
        String detail = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        try {
            JSONObject out = new JSONObject();
            out.put("reqId", reqId);
            out.put("error", code + "|" + detail);
            return out.toString();
        } catch (Exception ignored) {
            return "{\"reqId\":\"" + reqId + "\",\"error\":\"" + code + "\"}";
        }
    }
}
