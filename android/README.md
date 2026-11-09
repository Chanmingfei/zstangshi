# 掌上唐师 · Android 封壳工程（T14）

> **改 `web/` 后重新出包即可**：`app/src/main/assets/www/` 由 `android/build-manual.sh`
> 在每次构建时从 `../web` **整体复制并重新生成**，无需手改。`web/` 是唯一真源。

本工程是手写 WebView 壳，**零第三方依赖**（`dependencies {}` 为空，不用 androidx / Capacitor）。
Activity / WebView / CookieManager / AndroidKeyStore / HttpsURLConnection 全部来自 Android Framework。

## 一、环境前提

- **零第三方依赖**意味着 `maven.google.com` 不是必须的——AGP 才需要它。因此本工程提供两条出包路线：
  1. **手动编译（推荐，已在本交付环境实际验证）**：仅用 Android SDK（`platforms;android-34` +
     `build-tools;34.0.0`）+ JDK 17，通过 `android/build-manual.sh` 调 `aapt2`/`javac`/`d8`/
     `apksigner` 直接出包，**完全不碰 maven.google.com**。
  2. Gradle + AGP 8.5.2（需联网下载 AGP 与 Gradle 8.7，约 200MB，仅当网络可达 maven.google.com 时可行）。
- 若 `maven.google.com` 被代理/防火墙拦截（本交付环境即如此），路线 2 会卡在
  `Could not resolve com.android...`，此时只能用路线 1。
- 本交付环境**已实际装好** JDK 17（Azul CDN）+ Android SDK，并按路线 1 产出了 `android/build/app-debug.apk`。

## 二、出包步骤

### 路线 1：手动编译（推荐，一条命令）
前置：JDK 17 解压到 `C:/Android/jdk17`（脚本自动探测其下的 zulu 目录）；Android SDK 装好
`platforms;android-34` 与 `build-tools;34.0.0` 到 `C:/Android/sdk`。在 **Git Bash** 里执行：

```bash
bash android/build-manual.sh
```

脚本会：同步 `web/`→`assets/www` → `aapt2` 编译/链接资源并生成 `R.java` → `javac` 编译 →
`d8` 转 dex → `zipalign` 对齐 → 用内置 debug keystore 签名。产物：`android/build/app-debug.apk`。

装到手机（二选一）：
```bash
# 用 SDK 的 adb（先 adb devices 确认已连上）
C:/Android/sdk/platform-tools/adb.exe install -r android/build/app-debug.apk
# 或直接把 app-debug.apk 拷到手机，允许「未知来源」后点开安装
```

### 路线 2：Android Studio + Gradle（需 maven 可达）
1. `local.properties.example` 复制为 `android/local.properties`，`sdk.dir` 指向本机 SDK。
2. Android Studio → `File > Open` → 选 `android/` 目录（不要选到项目根）。
3. Gradle Sync 完成无 `Could not resolve` 后：`Build > Build APK(s)`，或命令行
   `cd android && ./gradlew assembleDebug`（Windows 用 `gradlew.bat`）。
4. 产物：`android/app/build/outputs/apk/debug/app-debug.apk`。
   （注意：仓库只有 `gradle-wrapper.properties` 没有二进制 `gradle-wrapper.jar`；Android Studio 打开时会补，
   或在本机有 Gradle 的机器上 `gradle wrapper --gradle-version 8.7` 生成。）

## 三、工程要点（对照源码）

| 项 | 落点 |
|---|---|
| 虚拟源 | `MainActivity.APP_HOST = "http://app.local/index.html"`（用 http 而非 https，见下方「主文档加载坑」）；`AssetLoader` 只拦 `host == app.local`，子资源（CSS/JS）由此供给 |
| ESM MIME | `AssetLoader.mimeOf()`：`.js` / `.mjs` → `text/javascript`；写错整套 ESM 白屏 |
| 找不到资源 | 返回 404 `WebResourceResponse`，**绝不 `return null`**（否则会真发网络请求，R3） |
| 资产同步 | `app/build.gradle`：`tasks.register('syncWebAssets', Copy)` + `tasks.named('preBuild') { dependsOn 'syncWebAssets' }` |
| 硬件返回 | `MainActivity.onBackPressed()` → `window.__appBack()`；返回 `true` 由页面消费，`false` 则 `finish()` |
| 外链 | `AssetLoader.shouldOverrideUrlLoading()`：非 `app.local` 的 http(s)/tel/mailto → `Intent.ACTION_VIEW` |
| 桥 | `addJavascriptInterface(new NativeBridge(...), "NativeBridge")`，11 个 `@JavascriptInterface`（见下） |
| 学工系统（应用内直开） | 首页「学工系统」行与「我的 → 学工系统网页」→ `NativeBridge.openWebView(url)` → `WebActivity`：应用内 WebView，顶部 返回/刷新/关闭 工具条、标题同步页面 title，无中间跳转页；无原生壳时降级 `window.open` |
| Cookie | `CookieJar`：WebView `CookieManager` ↔ `java.net.CookieHandler` 双写；手动跟随 302，每跳重建 Cookie（R2 / §6.2） |
| 凭据 | `SecurePrefs`：AndroidKeyStore AES-256/GCM → SharedPreferences(MODE_PRIVATE) |
| 证书 | `res/xml/network_security_config.xml`：仅 `tstc.edu.cn` 允许明文 + 信任 system & user CA（R4） |
| 混淆 | `app/proguard-rules.pro` 保留全部 `@JavascriptInterface` 方法名（含 `rsaEncrypt`） |

### NativeBridge 的 11 个 JS API

`isNative` / `getAppVersion` / `httpRequest` / `rsaEncrypt` / `clearSession` /
`saveCredential` / `loadCredential` / `clearCredential` / `openWebView` /
`pushWidget` / `addWidget`

后两个是桌面小组件「今日课程」用的（v1.6.0）：

- `pushWidget(json)` —— H5 把今天的课算好序列化后推进来，落 `SharedPreferences`
  并让桌面重画。**必须落盘**：小组件在 App 进程之外也要能渲染（开机、零点、上课时刻），
  那时 WebView 不存在。
- `addWidget()` —— 请系统弹「添加至桌面」确认框（API 26+ pin 机制），
  「我的 → 桌面小组件 → 添加到桌面」调它；桌面不支持时返回 `unsupported`，页面给降级文案。

```
ClassWidget.java   AppWidgetProvider：RemoteViews 渲染 + 四条刷新路径
WidgetData.java    快照的存取与版本校验（版本不认就整条作废，不画半截）
res/layout/widget_today.xml          真实布局（固定 4 行，靠 setViewVisibility 控制）
res/layout/widget_today_preview.xml  initialLayout + 桌面选择器预览（静态示例）
res/xml/widget_today_info.xml        4×3、可缩放、updatePeriodMillis=30min
res/values{,-night}/widget_colors.xml 亮/深色两套色（与 tokens.css 同值）
```

四条刷新路径，都不依赖精确闹钟权限：系统 onUpdate / H5 推完立刻重画 /
`updatePeriodMillis` 30 分钟兜底 / `AlarmManager.set`（**非精确**）在下一个
「上课・下课・零点」边界重画。布局里只出现「在某区间内恒成立」的文案（「正在上」
「下一节 14:00」），刻意不做「还有 12 分钟」这种一刷新就过期的倒计时 ——
这也是能容忍闹钟晚一两分钟的原因。

两处**以代码为准、不要照抄文档**的偏差：

1. `httpRequest(reqId, url, method, headersJson, body)` —— **url 在 method 前**
   （`web/core/transport.js:185-191`）。`docs/ARCHITECTURE.md` §4.9 写反了。
   `headers` 是 JSON 字符串；回调 `window.__nativeHttpCallback({reqId,status,headers,body,url})`，
   失败传 `{reqId,error}`；`finalUrl` 也一并给出兼容文档，但 transport.js 实际读 `url`。
2. 第 9 个 API `rsaEncrypt(modulus, exponent, plain)`（`web/data/jwxtClient.js:175-176`）。
   实现为 `RSA/ECB/PKCS1Padding` + `RSAPublicKeySpec`，129 字节且首字节 `0x00` 的 modulus 剥掉符号位，
   密文 `Base64.NO_WRAP`，**不做字节反转**。漏掉它真机登录会去找不存在的 `/__zsts/encrypt`。

> 学工系统入口（`web/pages/home.js` / `web/pages/mine.js`）实际调用的是 `openWebView`
> （文档写 `openExternal`），所以第 9 个 API 用 `openWebView` 命名；Java 侧另保留
> `openExternal()` 供内部/外链共用。原 `affairs.js` 跳转中间页已按用户口径**整页移除**：
> 首页按钮直接走 `open-xuegong:<url>` 全局 action 进应用内 WebView。

### 主文档加载坑（实测 Chromium 147 踩过，已修）

**现象**：若用 `webView.loadUrl("http://app.local/index.html")` 让 `AssetLoader.shouldInterceptRequest`
拦截并返回主文档，哪怕 MIME 是 `text/html; charset=utf-8`、logcat 也确认已 serve，Chrome 仍会把
**主资源以纯文本显示 HTML 源码**而非提交为文档（黑底等宽字体的源码视图）。

**根因**：Chromium 147 对「主帧 `shouldInterceptRequest` 响应一个无法真实联网解析的 host」存在该行为，
与 MIME / encoding 无关（曾试过 `https`、`encoding=null` / `"UTF-8"` 均无效）。

**修法**：主文档改用 `webView.loadDataWithBaseURL("http://app.local/", html, "text/html", "UTF-8", "http://app.local/")`
直接灌入（`MainActivity.loadApp()`），base 仍指向 `app.local`，使文档 origin 为 `app.local`（非 null，
满足 ESM / localStorage / `crypto.subtle` 同源与 secure-context 要求）；CSS / JS 等子资源仍由
`AssetLoader.shouldInterceptRequest` 从 `assets/www` 供给。`AssetLoader.mimeOf()` 返回的 MIME 已含
`; charset=utf-8`，**不要再额外传 `encoding` 参数**（双重 charset 也会让部分 WebView 拒绝按 HTML 解析）。
虚拟源用 `http://` 而非 `https://`：`app.local` 属「potentially trustworthy」源，仍为 secure-context，
且 `http` 没有真实证书门槛，避免 https 自定义域的证书问题。

### R8 dexing 坑（本机 R8 8.2.2-dev 踩过，已修）

**现象**：d8 阶段报 `NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`，错误定位到某个内部类（如 `WebActivity$1.class`）。

**根因**：build-tools 34.0.0 自带的 R8 8.2.2-dev 在 dexing **非静态内部类**（带 synthetic `this$0` 与合成构造参数）时触发该 NPE；静态嵌套类不受影响。匿名还是具名不是关键——**是否持有外部 `this` 引用才是**。

**修法**：`WebActivity` 里的 WebViewClient / WebChromeClient / 三个 OnClickListener 一律写成 `static class`，需要的 WebView / Activity 引用构造时显式传入。另外 `build-manual.sh` 在 javac 前**必须清空 `$OUT/classes`**：javac 只覆盖它重新生成的 .class，上一轮的旧内部类残留会被一起打进 classes.jar，让 d8 在残留文件上继续炸。

## 四、T14 六条验收项 · 真机自查方法

| # | 验收项 | 怎么查 |
|---|---|---|
| ① | Android Studio 同步无 `Could not resolve` | Sync 后看 Build 窗口；`dependencies {}` 为空，唯一需下载的是 Gradle 8.7 + AGP 8.5.2。若卡在下载，检查网络或改成本地 Gradle |
| ② | `assembleDebug` 产出 `app-debug.apk` | 看 `app/build/outputs/apk/debug/app-debug.apk` 是否存在；Build 窗口出现 `BUILD SUCCESSFUL`。**本机无 SDK，此项由用户在 Android Studio 验证** |
| ③ | 冷启动无白屏，`assets/www/index.html` 经虚拟源加载，控制台无 `Failed to load module script` | 装机冷启动观察首帧（splash 品牌色）；手机连电脑，Chrome 打开 `chrome://inspect` → 选中 WebView → Console/Network：地址栏应为 `http://app.local/`，`.js` 响应 `Content-Type: text/javascript`，无 CORS/MIME 报错。**本项已在真机（OnePlus PKG110，WebView 147.0.7727.137）实测：App 正常渲染登录页 / 我的页，主文档不再显示源码** |
| ④ | `window.NativeBridge.isNative() === true`；`httpRequest` 对 `https://jwxt.tstc.edu.cn/` 返回非 0 status 或结构化超时错误（不崩） | DevTools Console 执行 `window.NativeBridge.isNative()`；再在 App 里把数据源切到「教务」触发登录，观察 Network/Console。校园网外应看到 `error: "TIMEOUT\|..."` 之类结构化错误而不是白屏。**重点看登录两级 302**：`CookieJar` 每跳重建 Cookie，成功时第二个响应不应再是 302 |
| ⑤ | 硬件返回键：次级页返回列表、Tab 根页退出 App | 从首页进「成绩详情」按返回 → 回列表；在首页按返回 → App 退出（或回桌面）。`window.__appBack()` 返回 `false` 时宿主才退出 |
| ⑥ | 学工系统应用内直开（无中间页） | 首页滑到底点「学工系统」行 → 应**直接**进入应用内 WebView（顶部 返回/刷新/关闭），不经过任何说明页。**已在真机（OnePlus PKG110）实测**：窗口焦点变为 WebActivity，学工系统登录页（唐山师范学院）正常渲染 |
| ⑦ | 我的页学工入口同样应用内打开 | 「我的 → 学工系统网页」→ 应用内 WebView；浏览器预览无原生壳，降级 `window.open` |

### 补两条与桥相关的自查（真机）

- **RSA 往返**：DevTools 里用一组已知 RSA 公钥调用
  `window.NativeBridge.rsaEncrypt(modulusB64, 'AQAB', '123456')`，把结果拿到 Node 侧用
  `crypto.privateDecrypt({padding: RSA_PKCS1_PADDING})` 解，应还原 `123456`。文档 §6.1 已用
  Node crypto 做过等价往返（等价 `RSA/ECB/PKCS1Padding`）。
- **Cookie 双写**：登录成功后连续两次 GET 教务页面，第二次响应不应 302 回
  `login_slogin.html`；`chrome://inspect` 的 Application → Cookies 能看到 `JSESSIONID` / `route`。

## 五、诚实边界（已验证 / 仍未验证）

- **真机已实测（OnePlus PKG110，WebView 147）**：App 正常渲染登录页/首页/我的页；首页「学工系统」
  直开应用内 WebView（WebActivity），学工系统登录页真实加载。`aapt2`/`javac`/`d8`/`apksigner` 全绿，
  包名 `com.tstc.zstangshi`、minSdk 26、签名含 v1/v2/v3。
  **仍未实测**：登录两级 302 的 Cookie 传递、RSA 往返、硬件返回 —— 需要校园网 + 真实学号密码
  （教务系统仅校园网可达；App 抓取失败时如实降级演示数据并显示「演示数据」横幅，属诚实降级而非缺陷）。
- **正方 API 参考（github.com/openschoolcn/zfn_api）**：Python 服务端实现（MPL-2.0），状态码
  1000 成功 / 1001 需验证码 / 1002 密码错误 / 1006 Cookie 失效。本工程 `jwxtClient.js` 已按同一契约
  实现登录（csrftoken + RSA PKCS#1 v1.5 + Shiro 表单）与各 gnmkdm 端点。**已知缺口**：若学校开启
  验证码，当前登录报 CAPTCHA（文案已在 `ERROR_TEXT`），需参照 zfn_api 的 `login_with_kaptcha` 补验证码输入 UI。
- 由于我们绕过了 AGP，`proguard-rules.pro` 的混淆**未生效**：本交付的 debug 包未混淆。
  若要 release 混淆包，需走路线 2 或手写混淆流程，本交付未做。
- `java.net.CookieHandler` 在 Android 各 WebView/HttpsURLConnection 实现上的调用时机存在差异，
  所以 `CookieJar` 做了「显式注入 + `put()` 回写 + `syncSetCookie()` 兜底」三层；仍需真机按 ④ 验证。
- `docs/remote-api-map.md` §6.7 明确记着：Android `NativeBridge.httpRequest` 通道的 302 跟随与
  Cookie 维护行为**尚未实测**，只能靠上述真机自查确认。
- `ENDPOINTS.selectionData`（选课课池）与 `ENDPOINTS.recommend` 仍未实测，App 会诚实降级到演示数据，
  这不是封壳层的问题，不要在这里伪造地址。
