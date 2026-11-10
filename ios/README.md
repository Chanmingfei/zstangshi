# 掌上唐师 · iOS 版（legacy v2 · WKWebView 套壳）

> ## ⚠️ 这份文档与工程已归档，不再是 iOS 主线
>
> v3 起 iOS 端改为**原生 SwiftUI 重写**，代码在 [`../ios-native/`](../ios-native/README.md)，
> 与本文档描述的 WKWebView + H5 套壳方案**没有任何共用代码**。
>
> 本目录保留只为两件事：① v2 的 ipa 云构建链路（`scripts/build-ipa.sh`、Codemagic、GitHub Actions）
> 仍可复现 v2 产物；② 存档对照。**新功能只会进 `ios-native/`。**
>
> v2 的完整代码另存于 `legacy-webview-v2.0.1` 分支与 `v2.0.1` tag。

Android 源码（`android/` + `web/`）到 iOS 的完整移植。**H5 业务代码一行未改**，
原生侧按 1:1 复刻了 Android 的壳能力，所以功能与设计完全一致。

---

## 先说结论：ipa 不能在这台机器上直接产出

`.ipa` 里的可执行文件是 arm64 的 Mach-O，只能用 Apple 的 iOS SDK 编译链接，
而 SDK 只随 Xcode 发布、Xcode 只有 macOS 版。Windows 上没有任何合法途径交叉编译出
可安装的 ipa —— 这不是工具链没装齐，是平台限制。

所以这里交付的是**一份在 Mac 上一条命令就能出 ipa 的完整 Xcode 工程**，
外加一条不需要你有 Mac 的云端出包链路。三条路，按你手边的条件选：

| 你的条件 | 怎么做 | 产出 |
| --- | --- | --- |
| 有一台 Mac | `bash ios/scripts/build-ipa.sh` | `ios/build/ZhangShangTangShi-1.5.2-unsigned.ipa` |
| 有 Mac + 开发者账号 | `build-ipa.sh --team <TEAM_ID>` | 已签名 ipa，可直接装到登记过的设备 |
| 没有 Mac，有 GitHub 账号 | 推仓库 → Actions 里点 `Build iOS IPA` | 从 Actions 页面下载 ipa |
| 只想看界面 | 用浏览器打开 `Resources/www/index.html` | 无（数据层会走 web 通道，教务接口被 CORS 拦） |

未签名的 ipa **不能双击安装**，iOS 要求可执行文件有签名。用
[Sideloadly](https://sideloadly.io/) 或 AltStore 拖进去、以你的 Apple ID 重签即可
（免费 Apple ID 签的包 7 天到期，需重签；开发者账号 1 年）。

---

## 目录结构

```
ios/
├── ZhangShangTangShi.xcodeproj/         # 手写最小工程（含共享 scheme，CI 可直接用）
├── ZhangShangTangShi/
│   ├── App/AppDelegate.swift            # 入口，单窗口，不用 Scene 生命周期
│   ├── Web/
│   │   ├── WebViewController.swift      # 壳：WKWebView + 安全区 + 边缘返回手势
│   │   └── LocalHTTPServer.swift        # 内置静态服务，只监听 127.0.0.1
│   ├── Bridge/
│   │   ├── bridge-shim.js               # ★ 造出 window.NativeBridge，H5 零改动的前提
│   │   ├── NativeBridge.swift           # 桥的 native 侧（WKScriptMessageHandler）
│   │   ├── HTTPClient.swift             # 手动跟随 302 + 每跳重取 Cookie
│   │   ├── CookieStore.swift            # WKHTTPCookieStore ↔ URLSession 双向打通
│   │   ├── CredentialStore.swift        # Keychain 存凭据（对应 SecurePrefs）
│   │   └── InAppBrowser.swift           # 应用内浏览器（对应 WebActivity）
│   └── Resources/
│       ├── Info.plist                   # 版本 1.5.2(11)、ATS、启动页、方向
│       ├── Assets.xcassets/             # AppIcon（1024，按 Android 矢量重绘）
│       └── www/                         # H5 资源，由 sync-web.sh 生成，不要手改
└── scripts/
    ├── build-ipa.sh                     # 出包（未签名 / 签名 / 模拟器 三种模式）
    ├── sync-web.sh                      # 从 web/ 同步 H5 资源
    ├── make-icons.py                    # 从 Android 矢量生成 1024 图标
    ├── verify-project.mjs               # 工程完整性校验（对象引用、编译阶段、磁盘路径）
    └── test-bridge.mjs                  # 桥协议单测（含 RSA 与标准实现互通）
```

> **`Resources/www` 是产物，不是源码。** 唯一真源是仓库根目录的 `web/`
> （Android 的 `syncWebAssets` 任务取的也是这个目录）。改 H5 请改 `web/`，
> 然后跑 `bash ios/scripts/sync-web.sh`，再用 `--check` 确认没有漂移。

---

## 与 Android 的功能对照

| Android | iOS | 说明 |
| --- | --- | --- |
| `http://app.local` + `shouldInterceptRequest` | 内置 HTTP 服务 `http://127.0.0.1:18765` | iOS 不允许自定义 https 域；`file://` 下 ESM 会白屏 |
| `@JavascriptInterface` 的 9 个方法 | `bridge-shim.js` 同名同签名 + `postMessage` | H5 侧的 `detectEnv()` 照常判定为原生通道 |
| `httpRequest` 异步回调 `__nativeHttpCallback` | 同左，`evaluateJavaScript` 注入 | 载荷字段完全一致（含 `url` 与 `finalUrl` 两个键） |
| `rsaEncrypt`（Java `Cipher`） | 纯 JS BigInt 实现 | iOS 的消息通道无法返回值，必须同步算；已与标准 PKCS#1 v1.5 互通验证 |
| `CookieManager` ↔ `HttpURLConnection` 手工桥接 | `WKHTTPCookieStore` ↔ `URLSession` 手工桥接 | 两个平台都是两套 Cookie 存储，都按「每跳重取、每跳回写」处理 |
| `SecurePrefs`（AES-GCM + Keystore） | `CredentialStore`（Keychain） | 都用系统级加密存储，换机后读不到 → 重新登录 |
| `WebActivity`（应用内浏览器） | `InAppBrowserViewController` | 同样共享 Cookie，关闭即回主页 |
| `onBackPressed` → `window.__appBack()` | 左边缘右滑 → `window.__appBack()` | 见「平台差异」 |
| `splash_bg` 品牌深蓝 | `UILaunchScreen` + `LaunchBackground` 色 | 冷启动全程无白屏 |
| `versionName 1.5.2 / versionCode 11` | `CFBundleShortVersionString 1.5.2 / CFBundleVersion 11` | 双端版本号对齐（`verify-project.mjs` 会动态比对，不一致直接失败）|
| 自适应图标（深蓝 + 白书） | 1024 图标，同一矢量重绘 | 按 iOS 遮罩特性换算可见占比 |

---

## 四个关键决策（改之前请先读）

**1. 端口必须固定。** `localStorage` / `sessionStorage` 按 `scheme + host + port` 隔离。
端口随机分配会让用户的主题选择、记住的学号、课表缓存每次冷启动全部失效。
所以固定 `18765`，被占用时顺延 `18766…18769`（宁可丢一次本地数据，也不能打不开）。

**2. `.js` 的 MIME 必须是 `text/javascript`。** 写成 `application/javascript` 或
`text/plain`，Safari 会因严格 MIME 检查拒绝 `<script type="module">`，整套 ESM 白屏，
而且控制台只报一句含糊的 "module load failed"。

**3. 不能自动跟随重定向。** 正方教务登录是两级 302，POST 响应会换发新的 `JSESSIONID`。
如果让 `URLSession` 自动跟随，每一跳都会沿用旧 Cookie 头，第二次跳转被静默踢回登录页
（页面没有任何错误文案，看起来像密码错）。所以 `HTTPClient` 关掉自动重定向，
手动跟最多 5 跳，每跳重新取 Cookie。

**4. `scrollView.contentInsetAdjustmentBehavior = .never`。**
H5 用 `env(safe-area-inset-*)` 自己处理安全区，WebView 再自动内缩一次会出现双倍留白。

---

## 自检（不需要 Mac，现在就能跑）

```bash
node ios/scripts/verify-project.mjs     # 工程完整性：对象引用 / 编译阶段 / 磁盘路径 / plist
node ios/scripts/test-bridge.mjs        # 桥协议：43 项，含 RSA 与 node:crypto 互通
node ios/scripts/test-shell-smoke.mjs   # 真渲染冒烟：注入 shim 后逐页加载，出截图
bash ios/scripts/sync-web.sh --check    # www 是否与 web/ 一致
```

`test-shell-smoke.mjs` 会用本机 Edge/Chrome 以 iPhone 视口真渲染一遍：
登录页 → 演示模式 → 首页 / 课表 / 成绩 / 我的，逐页断言「有内容 + 无未捕获异常」，
并把截图写到 `ios/build/smoke-*.png`。它验的正是 iOS 上最难排查的那类问题：
WKWebView 白屏时控制台只有一句含糊的 `module load failed`。
（找不到浏览器时自动跳过，不阻塞 CI。）

`verify-project.mjs` 会抓出「只有到 Mac 上才暴露」的错，例如：漏把某个 `.swift` 加进
编译阶段（静默不编译）、`www` 没进拷贝阶段（运行期白屏、编译期无提示）、
引用了不存在的对象 ID（Xcode 直接报工程损坏）。

---

## 平台差异（这几处做不到 1:1，都已降级处理且不报错）

- **`openWifiSettings`**：iOS 不允许 App 直达 Wi-Fi 设置页（`App-Prefs:` 私有 scheme
  会被审核拒）。改为打开本 App 的设置页。登录页的引导文案不变，功能降级。
- **返回键**：iPhone 没有硬件返回键，改为左边缘右滑触发 `window.__appBack()`。
  返回 `false`（已在 Tab 根页）时不做任何事 —— iOS 没有「手势退出 App」的范式。
- **状态栏配色**：Android 由 `themes.xml` 静态着色，iOS 改为跟随 App 主题
  （`bridge-shim.js` 监听 `data-theme` 变化并上报，原生改 `preferredStatusBarStyle`）。
- **无障碍字号**：iOS 的 Dynamic Type 不自动放大网页文字。Android 侧固定 100% 的原因
  是字号放大会撑破 320px 布局，这里沿用同一策略（未开启 `textAutosizing`）。

---

## 上架前需要注意

1. **Guideline 4.2（Minimum Functionality）**：纯网页套壳容易被拒。
   本 App 有原生网络栈、Keychain 凭据、应用内浏览器等实质集成，风险不高，
   但二进制里 `www/` 体量较大时建议在审核备注里说明「校内教务系统的移动客户端」。
2. **ATS 例外**：`NSAllowsArbitraryLoads = true` 是因为学工系统仍是 http 明文，
   与 Android 的 `usesCleartextTraffic` 一致。审核时按需在备注里说明。
3. **隐私清单（PrivacyInfo.xcprivacy）**：2024 年 5 月起 App Store 要求声明
   `UserDefaults` 等 API 的使用理由。本工程用的是 `localStorage`（WebKit 内部），
   不涉及自有 `UserDefaults` 调用，暂未加入；若被拒再补。

---

## 可选：用 XcodeGen 重新生成工程

`project.pbxproj` 是手写的最小对象图（42 个对象），已通过完整性校验。
如果你更习惯声明式工程描述：

```bash
brew install xcodegen
cd ios && xcodegen generate    # 读取 project.yml，覆盖生成 .xcodeproj
```

`project.yml` 与手写工程等价；**当前仓库里提交的 `project.pbxproj` 才是权威**，
改工程结构时请两边同步，或直接只用其中一种。

---

## 云端构建已打通（Codemagic）

2026-11-06 首次跑通，产物 `ZhangShangTangShi-1.5.0-unsigned.ipa`（507 KB，arm64）；2026-11-07 升到 v1.5.2 后产物为 `ZhangShangTangShi-1.5.2-unsigned.ipa`（515 KB，arm64）。
前三次失败都是**脚本层**问题，与 Swift 代码无关，记在这里免得下次重踩：

| 报错 | 根因 | 修法 |
|---|---|---|
| `sync-web.sh: Permission denied` | Windows 上 `chmod +x` 是空操作，脚本进仓库是 644 | 脚本间调用改 `bash "$SCRIPT_DIR/xxx.sh"`，并把可执行位写进索引（100755） |
| `WKHTTPCookieStore has no member 'deleteAllCookies'` | 那是 `WKWebsiteDataStore` 的 API | 改 `getAllCookies` + 逐条 `delete(_:completionHandler:)` |
| `zip error: Could not create output file (out/...)` | 产物路径是相对目录，而 zip 在 `(cd "$STAGE" && ...)` 子 shell 里执行 | 参数解析后把 `OUT_DIR` 转绝对路径 |

另外 `macos-14` 在 GitHub 上曾报 `configuration ... is not supported`，工作流已改用 `macos-15`。

---

## v1.5.2 的双端对齐要点（2026-11-07）

v1.5.2 的核心是「教务地址协议自适应」：校外移动数据下 443 端口不可达、80 正常，
所以 H5 新增 `data/jwxtOrigin.js` 探测后再拼 URL。iOS 侧有两处**必须跟**，否则新功能形同失效：

| 位置 | 改动 | 不做的后果 |
|---|---|---|
| `Bridge/HTTPClient.swift` | 超时 15s → 20s（对齐 `CookieJar.TIMEOUT_MS = 20000`） | `transport.js` 在 JS 层也挂了 20s 定时器，原生却先在第 15 秒失败；校外那种 15–16 s 才返回的成功请求会被原生掐断，登录仍然失败 |
| `Bridge/InAppBrowser.swift` | 补 `didFailProvisionalNavigation` / `didFail`，把 http↔https 换一次重试 | 对应 Android `WebActivity.onReceivedError`。`jwxtUrl()` 可能给出 http，探测时刻与点开时刻网络不同时会白屏 |

`detectEnv()` 只判断 `window.NativeBridge.httpRequest` 是否存在，所以 iOS 壳里同样返回
`'android'` —— 协议自适应、原生通道分支都会自动生效，H5 无需为 iOS 分支。
`Info.plist` 的 `NSAllowsArbitraryLoads = true` 保证 http 明文请求在 iOS 上也能发出。
