<div align="center">

<img src="../docs/assets/school-logo.png" width="72" alt="唐山师范学院校徽" />

# 掌上唐师 · v2 WebView 套壳工程

**H5 + 手写 WebView 壳 · 零第三方依赖 · 已被 v3 原生版取代**

<p>
  <img src="https://img.shields.io/badge/Status-Archived-9E9E9E?style=flat" alt="Archived" />
  <img src="https://img.shields.io/badge/Version-2.x-9E9E9E?style=flat" alt="Version" />
  <img src="https://img.shields.io/badge/WebView-H5%20shell-4285F4?style=flat&logo=googlechrome&logoColor=white" alt="WebView" />
  <img src="https://img.shields.io/badge/Java-8%20%C2%B7%208%20%E6%96%87%E4%BB%B6%201%2C667%20%E8%A1%8C-B07219?style=flat" alt="Java" />
  <img src="https://img.shields.io/badge/License-GPL%203.0-E91E63?style=flat" alt="License" />
</p>

<p>
  <a href="../README.md">项目主页</a> · <a href="../README.en.md">English</a> · <a href="../native/">v3 标准版</a>
</p>

</div>

---

> **⚠️ 本目录已归档，不再维护。**
>
> v3 起改用纯原生实现（[`../native/`](../native/)），原因很直接：课表的横滑翻周、底栏切页的连续平移、
> 二级页的预测性返回跟手，这三件事在 WebView 里都只能用 JS 模拟手势，跟不上系统动画的节奏。
>
> 这里保留的是 v2 的 WebView 壳与它的踩坑记录 —— 想用 WebView 装本地 H5 的人，第四节那三个坑仍然有效。

---

## 📖 这是什么

一个**手写 WebView 壳**：Activity / WebView / CookieManager / AndroidKeyStore / HttpsURLConnection
全部来自 Android Framework，`dependencies {}` 为空，不用 androidx、不用 Capacitor。

H5 在 [`../web/`](../web/)，是唯一真源；构建时由 `build-manual.sh` 整体复制到
`app/src/main/assets/www/` 并重新生成，**改 `web/` 后重新出包即可，不要手改 assets**。

Java 侧只有 8 个文件、1,667 行；重量都在桥和 Cookie 上。

---

## 🚀 出包

### 路线 1：手动编译（推荐）

零第三方依赖意味着 `maven.google.com` 不是必须的 —— AGP 才需要它。只用 Android SDK
（`platforms;android-34` + `build-tools;34.0.0`）+ JDK 17：

```bash
bash android/build-manual.sh
```

依次执行：同步 `web/` → `assets/www` → `aapt2` 编译/链接资源并生成 `R.java` → `javac` →
`d8` 转 dex → `zipalign` 对齐 → 内置 debug keystore 签名。产物 `android/build/app-debug.apk`。

```bash
adb install -r android/build/app-debug.apk
# 或把 APK 拷到手机，允许「未知来源」后点开
```

前置：JDK 17 解压到 `C:/Android/jdk17`（脚本自动探测其下的 zulu 目录），SDK 装到 `C:/Android/sdk`。

### 路线 2：Android Studio + Gradle（需 maven 可达）

1. `local.properties.example` 复制为 `local.properties`，`sdk.dir` 指向本机 SDK。
2. Android Studio → `File > Open` → 选 `android/` 目录（不要选到项目根）。
3. `Build > Build APK(s)`，或 `cd android && ./gradlew assembleDebug`。
4. 产物：`android/app/build/outputs/apk/debug/app-debug.apk`。

> 仓库只有 `gradle-wrapper.properties` 没有二进制 `gradle-wrapper.jar`；Android Studio 打开时会补，
> 或在本机 `gradle wrapper --gradle-version 8.7` 生成。

### 只想看 H5

```bash
node server.js        # http://127.0.0.1:5173
npm run verify        # v2 单测 + 规则守卫
```

---

## 🧩 工程要点

| 项 | 落点 |
| --- | --- |
| 虚拟源 | `MainActivity.APP_HOST = "http://app.local/index.html"`（用 http 而非 https，见下方「主文档加载坑」）；`AssetLoader` 只拦 `host == app.local`，子资源（CSS/JS）由此供给 |
| ESM MIME | `AssetLoader.mimeOf()`：`.js` / `.mjs` → `text/javascript`；写错整套 ESM 白屏 |
| 找不到资源 | 返回 404 `WebResourceResponse`，**绝不 `return null`**（否则会真发网络请求） |
| 资产同步 | `app/build.gradle`：`tasks.register('syncWebAssets', Copy)` + `tasks.named('preBuild') { dependsOn 'syncWebAssets' }` |
| 硬件返回 | `MainActivity.onBackPressed()` → `window.__appBack()`；返回 `true` 由页面消费，`false` 则 `finish()` |
| 外链 | `AssetLoader.shouldOverrideUrlLoading()`：非 `app.local` 的 http(s)/tel/mailto → `Intent.ACTION_VIEW` |
| 桥 | `addJavascriptInterface(new NativeBridge(...), "NativeBridge")`，11 个 `@JavascriptInterface` |
| 学工系统 | 首页「学工系统」行与「我的 → 学工系统网页」→ `NativeBridge.openWebView(url)` → `WebActivity`：应用内 WebView，顶部 返回/刷新/关闭 工具条、标题同步 title，无中间跳转页 |
| Cookie | `CookieJar`：WebView `CookieManager` ↔ `java.net.CookieHandler` 双写；手动跟随 302，每跳重建 Cookie |
| 凭据 | `SecurePrefs`：AndroidKeyStore AES-256/GCM → SharedPreferences(MODE_PRIVATE) |
| 证书 | `res/xml/network_security_config.xml`：仅 `tstc.edu.cn` 允许明文 + 信任 system & user CA |
| 混淆 | `app/proguard-rules.pro` 保留全部 `@JavascriptInterface` 方法名（含 `rsaEncrypt`） |

### NativeBridge 的 11 个 JS API

`isNative` / `getAppVersion` / `httpRequest` / `rsaEncrypt` / `clearSession` /
`saveCredential` / `loadCredential` / `clearCredential` / `openWebView` /
`pushWidget` / `addWidget`

后两个是桌面小组件「今日课程」用的（v1.6.0）：

- `pushWidget(json)` —— H5 把今天的课算好序列化后推进来，落 `SharedPreferences` 并让桌面重画。
  **必须落盘**：小组件在 App 进程之外也要能渲染（开机、零点、上课时刻），那时 WebView 不存在。
- `addWidget()` —— 请系统弹「添加至桌面」确认框（API 26+ pin 机制），
  桌面不支持时返回 `unsupported`，页面给降级文案。

两处**以代码为准、不要照抄文档**的偏差：

1. `httpRequest(reqId, url, method, headersJson, body)` —— **url 在 method 前**
   （`web/core/transport.js`）。`docs/ARCHITECTURE.md` §4.9 写反了。
   回调 `window.__nativeHttpCallback({reqId,status,headers,body,url})`，失败传 `{reqId,error}`。
2. 第 9 个 API `rsaEncrypt(modulus, exponent, plain)`。实现为 `RSA/ECB/PKCS1Padding` +
   `RSAPublicKeySpec`，129 字节且首字节 `0x00` 的 modulus 剥掉符号位，
   密文 `Base64.NO_WRAP`，**不做字节反转**。漏掉它真机登录会去找不存在的 `/__zsts/encrypt`。

---

## ☠️ 三个仍然值得抄的坑

### 一、主文档加载：`shouldInterceptRequest` 返回 HTML 会变源码视图

**现象**：`loadUrl("http://app.local/index.html")` 让 `shouldInterceptRequest` 拦截并返回主文档，
哪怕 MIME 是 `text/html; charset=utf-8`、logcat 也确认已 serve，Chrome 仍会把**主资源以纯文本
显示 HTML 源码**（黑底等宽字体的源码视图）。

**根因**：Chromium 147 对「主帧 `shouldInterceptRequest` 响应一个无法真实联网解析的 host」
存在该行为，与 MIME / encoding 无关（试过 `https`、`encoding=null` / `"UTF-8"` 均无效）。

**修法**：主文档改用

```java
webView.loadDataWithBaseURL("http://app.local/", html, "text/html", "UTF-8", "http://app.local/");
```

base 仍指向 `app.local`，使文档 origin 为 `app.local`（非 null，满足 ESM / localStorage /
`crypto.subtle` 的同源与 secure-context 要求）；CSS / JS 仍由 `shouldInterceptRequest` 供给。
`mimeOf()` 的 MIME 已含 `; charset=utf-8`，**不要再额外传 `encoding` 参数**（双重 charset 也会让
部分 WebView 拒绝按 HTML 解析）。虚拟源用 `http://` 而非 `https://`：`app.local` 属
「potentially trustworthy」源，仍是 secure-context，且没有真实证书门槛。

### 二、R8 dexing：非静态内部类触发 NPE

**现象**：d8 阶段报 `NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`，
定位到某个内部类（如 `WebActivity$1.class`）。

**根因**：build-tools 34.0.0 自带的 R8 8.2.2-dev 在 dexing **非静态内部类**
（带 synthetic `this$0` 与合成构造参数）时触发该 NPE；静态嵌套类不受影响。
匿名还是具名不是关键 —— **是否持有外部 `this` 引用才是**。

**修法**：WebViewClient / WebChromeClient / OnClickListener 一律写成 `static class`，
需要的引用构造时显式传入。另外 `build-manual.sh` 在 javac 前**必须清空 `$OUT/classes`**：
javac 只覆盖它重新生成的 `.class`，上一轮的旧内部类残留会被一起打进 classes.jar，让 d8 继续炸。

### 三、`httpRequest` 的 302 与 Cookie

`java.net.CookieHandler` 在 Android 各 WebView / HttpsURLConnection 实现上的调用时机存在差异，
所以 `CookieJar` 做了「显式注入 + `put()` 回写 + `syncSetCookie()` 兜底」三层。
登录有两级 302，每跳都要重建 Cookie，否则第二次请求会 302 回 `login_slogin.html`。

---

## ✅ 自查清单

| # | 项目 | 怎么查 |
| --- | --- | --- |
| ① | 同步无 `Could not resolve` | `dependencies {}` 为空，唯一需下载的是 Gradle 8.7 + AGP 8.5.2 |
| ② | 产出 `app-debug.apk` | 看产物路径是否存在，Build 窗口 `BUILD SUCCESSFUL` |
| ③ | 冷启动无白屏、`assets/www/index.html` 经虚拟源加载 | 冷启动观察首帧；`chrome://inspect` 看 Console/Network：地址栏为 `http://app.local/`，`.js` 响应 `text/javascript`，无 CORS/MIME 报错 |
| ④ | `NativeBridge.isNative() === true`；`httpRequest` 对教务返回非 0 status 或结构化超时 | DevTools Console 执行 `isNative()`；切数据源到「教务」触发登录，校园网外应看到结构化 `error` 而非白屏 |
| ⑤ | 硬件返回：次级页回列表、根页退出 | 首页进「成绩详情」按返回 → 回列表；首页按返回 → 退出 |
| ⑥ | 学工系统应用内直开、无中间页 | 首页滑到底点「学工系统」→ 直接进 WebActivity（顶部 返回/刷新/关闭） |

与桥相关的两条：

- **RSA 往返**：DevTools 里用已知公钥调
  `window.NativeBridge.rsaEncrypt(modulusB64, 'AQAB', '123456')`，
  结果拿到 Node 侧用 `crypto.privateDecrypt({padding: RSA_PKCS1_PADDING})` 解，应还原 `123456`。
- **Cookie 双写**：登录成功后连续两次 GET 教务页面，第二次不应 302 回 `login_slogin.html`；
  `chrome://inspect` → Application → Cookies 能看到 `JSESSIONID` / `route`。

---

## 🧾 诚实边界

- **真机已实测**（OnePlus PKG110，WebView 147）：登录页 / 首页 / 我的页正常渲染；
  首页「学工系统」直开应用内 WebView，学工系统登录页真实加载。
  `aapt2` / `javac` / `d8` / `apksigner` 全绿，包名 `com.tstc.zstangshi`、minSdk 26、签名含 v1/v2/v3。
  **仍未实测**：登录两级 302 的 Cookie 传递、RSA 往返、硬件返回 —— 需要校园网 + 真实账号。
- 由于绕过了 AGP，`proguard-rules.pro` 的混淆**未生效**：本交付的 debug 包未混淆。
- `ENDPOINTS.selectionData`（选课课池）与 `ENDPOINTS.recommend` 未实测，
  App 会诚实降级到演示数据并显示「演示数据」横幅，这不是封壳层的问题。
- 正方 API 参考 [openschoolcn/zfn_api](https://github.com/openschoolcn/zfn_api)（MPL-2.0）：
  状态码 1000 成功 / 1001 需验证码 / 1002 密码错误 / 1006 Cookie 失效。
  **已知缺口**：若学校开启验证码，当前登录报 CAPTCHA，需补验证码输入 UI。

---

## 🔗 相关文档

| 文档 | 内容 |
| --- | --- |
| [主 README](../README.md) | 当前版本（v3 原生）的功能、下载与构建 |
| [native/](../native/) | v3 标准版源码（现行主线） |
| [web/](../web/) | 本版 H5 源码（唯一真源） |
| [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) | v2 架构文档（§4.9 的 `httpRequest` 参数顺序以代码为准） |
| [CHANGELOG.md](../CHANGELOG.md) | 逐版本改动 |

---

本项目以 **GPL-3.0** 协议开源，全文见 [LICENSE](../LICENSE)。
