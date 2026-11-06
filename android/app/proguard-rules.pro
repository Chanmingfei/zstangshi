# proguard-rules.pro —— release 混淆保护
#
# JS 侧按「方法名字符串」调用 NativeBridge，混淆后 WebView 找不到对应方法会整体失效。
# 下面这条覆盖全部 @JavascriptInterface 方法，包括第 9 个 API rsaEncrypt（漏了真机登录不可用）。
-keep class com.tstc.zstangshi.NativeBridge { *; }
-keepclassmembers class com.tstc.zstangshi.NativeBridge {
    @android.webkit.JavascriptInterface public *;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*

# 9 个 @JavascriptInterface 方法名（显式列一遍，便于人工核对）：
#   isNative / getAppVersion / httpRequest / rsaEncrypt / clearSession
#   / saveCredential / loadCredential / clearCredential / openWebView

# WebView 内部回调与资源类
-keepclassmembers class * extends android.webkit.WebViewClient { public *; }
-keep class com.tstc.zstangshi.AssetLoader { *; }
-keep class com.tstc.zstangshi.CookieJar { *; }
-keep class com.tstc.zstangshi.CookieJar$WebViewCookieHandler { *; }
