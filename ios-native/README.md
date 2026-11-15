# 掌上唐师 · iOS 原生版（`ios-native/`）v4.0.0

SwiftUI 纯原生实现，与 `native/`（Android v4.0.0 / build 58）**代码零共享、只对齐功能与视觉**。
版本 `MARKETING_VERSION 4.0.0 / CURRENT_PROJECT_VERSION 58`，最低系统 iOS 17.0，用 Xcode 26 / iOS 26 SDK 编译。

和 `../ios/` 的区别：`ios/` 是 v2 时代的 WKWebView + H5 套壳（业务逻辑还在 JS 里），
`ios-native/` 是真正的原生重写 —— 页面、动画、教务请求、公交、小组件全部是 Swift。

- Bundle ID：`com.tstc.zstangshi`（小组件 `com.tstc.zstangshi.TodayWidget`）
- App Group：`group.com.tstc.zstangshi`
- 最低系统：iOS 17.0 · 仅 iPhone 竖屏
- 工程描述：`project.yml`（XcodeGen），**不提交 `.xcodeproj`**
- 编译工具链：Xcode 26 / iOS 26 SDK

---

## 与 Android v4 功能对齐清单

| 能力 | Android v4（`native/`） | iOS（这里） |
| --- | --- | --- |
| 五个主页面 | 首页 / 课表 / 实时公交 / 成绩 / 我的 | `RootView` 系统 `TabView`，公交居中 |
| 主导航 | `PageDeck` 自定义容器 | **系统 `TabView`**（iOS 26 自动 Liquid Glass） |
| 二级页 | 圆角卡片转场 | 每 tab 一根 `NavigationStack`，系统 push/pop + 边缘返回 |
| 弹层 | 自绘 BottomSheet | `.sheet` + `presentationDetents`（系统玻璃卡片） |
| 首页 | 今日课程 / 周次 | `HomeView` + `DayPlan` |
| 课表 | 网格 + 横滑翻周 | `TimetableView` / `TimetableGrid` + `PagingDrag` |
| 成绩 / 绩点 | GPA 页 | `GradesView` / `GradesHubView` |
| 实时公交 | 站点查询 / 收藏 / 到站 | `BusGateway` / `BusViewModel` / `BusLocation` + 附近站点定位 |
| 乘车码跳转 | `<queries>` 声明支付宝 / 银联 | `LSApplicationQueriesSchemes`（`alipay` / `alipayqr` / `uppaywallet` / `unionpay`） |
| 登录 / 教务 | 正方 RSA 登录 + 课表 HTML 解析 | `JwClient` + `RSA`（`SecKeyCreateEncryptedData`）+ `KbParser` |
| 密码保存 | SharedPreferences（加密） | Keychain + Face ID |
| 桌面小组件 | 「今日课程 4×2」 | **WidgetKit** `TodayWidgetExtension`（`.systemSmall` / `.systemMedium`） |
| 主题色 | `@color/brand` = `#0062CC` / 暗色 `#2E86E8` | `AccentColor` 同两色，8 色课表板一致 |
| 切页动效 | `ValueAnimator` + `PathInterpolator` | `Motion` 弹簧 + 动量投射（`Design/Motion.swift`） |
| 深色模式 | `applyOverrideConfiguration` 注入夜间位 | `.preferredColorScheme` + 动态色 `Color(uiColor:)` |
| 字号 | 关闭字体缩放、写死 sp | **Dynamic Type 语义字号**跟随系统 |

---

## iOS 原生适配

- **系统 `TabView` / `NavigationStack` / `.sheet`**：不再自绘胶囊底栏（那是 Android 习惯）。
  iOS 26 上系统标准组件自动获得真 Liquid Glass，系统负责材质、滚动收起、safe area 与无障碍。
- **Liquid Glass + 低版本回退**：`Design/Glass.swift` 里 `glassEffect` / `Glass` / `GlassEffectContainer`
  等 iOS 26 专有 API 全部用 `if #available(iOS 26.0, *)` 守卫；iOS 17–25 自动回退到
  `.ultraThinMaterial` + 亮顶边。内容层（卡片 / 列表 / 页面背景）一律不用玻璃，见 `Theme.zstCard`。
  **因为这些 API 只在 iOS 26 SDK 里存在，工程必须用 Xcode 26 编译**（见下）。
- **Dynamic Type**：`App/Theme.swift` 的 `F` 常量全部是语义字号（`.headline` / `.body` / `.footnote` …），
  跟随系统无障碍字号，不写死点数。
- **弹簧动效**：`Design/Motion.swift` 用 `Animation.spring` 实现「按下即反馈、任意时刻可打断、
  从实时屏幕值起播、松手继承手指速度」；只动画 transform / opacity；跟随「减弱动态效果」。
- **WidgetKit 桌面小组件**：`Widget/TodayWidget.swift` 是独立 app-extension target，自带 `@main`，
  自带极简 `Codable` 结构读 App Group JSON，**不 import 主 App 模型**。提供 `.systemSmall`
  （下一节 / 正在上的课 + 时间地点）与 `.systemMedium`（今日课程列表最多 3 条）；
  `TimelineProvider` 30 分钟刷新；无数据占位「还没有课表 / 到 App 里同步一次」。
  颜色用系统语义色 + 8 色板（浅 / 深各一套），深浅色都可读，不用任何 iOS 26 专有 API。

---

## 目录结构

```
ios-native/
├── project.yml                       XcodeGen 工程描述（唯一真源，含 App + TodayWidgetExtension 两个 target）
├── README.md
├── Resources/
│   ├── Info.plist                    ATS / FaceID / 启动色 / 竖屏 / 定位 / 查询 schemes / 加密声明
│   ├── App.entitlements              App Group 授权（App 与 Widget 共用）
│   └── Assets.xcassets/              AppIcon / AccentColor / LaunchBackground / CampusArt
├── Widget/                           ★ 独立 app-extension target，不进主 App 的 Compile Sources
│   ├── Info.plist                    NSExtensionPointIdentifier = com.apple.widgetkit-extension
│   └── TodayWidget.swift             @main Widget + Provider + 两种尺寸视图（自包含模型/颜色）
└── Sources/                          仅主 App target
    ├── App/
    │   ├── ZSTApp.swift              @main 入口 + RootView（系统 TabView + NavigationStack）
    │   ├── AppState.swift            全局状态：登录、课表、周次、主题、Toast
    │   └── Theme.swift               颜色 / 语义字号 / 圆角 / 间距（含深色）
    ├── Data/
    │   ├── Models.swift / WeekMath.swift / Schedule.swift
    │   ├── DayPlan.swift             「今日课程」聚合
    │   ├── WeekRange.swift / Store.swift
    ├── Net/
    │   ├── Http.swift / RSA.swift / JwClient.swift / JwApi.swift / KbParser.swift
    ├── Bus/
    │   ├── BusGateway.swift / BusViewModel.swift / BusLocation.swift
    │   ├── BusModels.swift
    │   └── BusStore.swift            App Group 读写 + WidgetCourse / WidgetBridge.publish()
    ├── Pages/
    │   ├── HomeView.swift / TimetableView.swift / TimetableGrid.swift
    │   ├── GradesView.swift / MineView.swift / LoginView.swift / Sheets.swift
    ├── Design/
    │   ├── Glass.swift               Liquid Glass 适配层 + iOS 17–25 材质回退
    │   └── Motion.swift              弹簧 / 动量投射 / Reduce Motion
    ├── UI/
    │   └── Components.swift          Card / 图标 / Toast 等
    └── Web/WebScreen.swift           应用内浏览器（WKWebView）
```

> `Sources/` 与 `Widget/` 是两个 target 各自的白名单源码目录（见 `project.yml` 的 `sources`）。
> `Widget/` 绝不进主 App target —— 它自带 `@main`，混进去会和 `ZSTApp.swift` 的 `@main` 冲突。

---

## 构建（xcodegen + Xcode 26）

`.ipa` 里的可执行文件是 arm64 的 Mach-O，只能用 Apple 的 iOS SDK 编译，而 SDK 只在 Xcode 里、
Xcode 只在 macOS 上。**编译必须用 Xcode 26 / iOS 26 SDK**（`Glass.swift` 的 iOS 26 专有 API），
`project.yml` 的最低部署版本是 iOS 17.0。

**有 Mac（Xcode 26）：**

```bash
brew install xcodegen
cd ios-native
xcodegen generate            # 由 project.yml 生成 ZhangShangTangShi.xcodeproj
open ZhangShangTangShi.xcodeproj
```

命令行出未签名 ipa：

```bash
xcodebuild -project ZhangShangTangShi.xcodeproj \
           -scheme ZhangShangTangShi \
           -configuration Release \
           -sdk iphoneos \
           -destination 'generic/platform=iOS' \
           -derivedDataPath build \
           CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
           build

mkdir -p Payload
cp -R build/Build/Products/Release-iphoneos/*.app Payload/
zip -qry ZhangShangTangShi-unsigned.ipa Payload
```

编译主 App scheme 会连 `TodayWidgetExtension` 一起构建并嵌入 `.app/PlugIns/`。
拿到 ipa 后用 [Sideloadly](https://sideloadly.io) / AltStore 以自己的 Apple ID 重签安装
（免费账号 7 天有效期）。

**没有 Mac：** 用 `.github/workflows/ios-native-ipa.yml`
（Actions → Build iOS Native IPA → Run workflow）。

---

## CI

`.github/workflows/ios-native-ipa.yml` 手动触发（`workflow_dispatch`），在 **`macos-26`** runner 上：

1. 选 Xcode 26 并校验 `xcrun --sdk iphoneos --show-sdk-version` 为 `26.*`；
2. `brew install xcodegen` → `xcodegen generate`；
3. `xcodebuild` 未签名出 `.app` → zip 成 `.ipa` → `actions/upload-artifact` 上传。

> 为什么必须 `macos-26`：只有该镜像带 Xcode 26 / iOS 26 SDK，`Glass.swift` 的
> `glassEffect` / `Glass` 等 API 才编得过；`macos-15`（Xcode 16 / iOS 18 SDK）不行。
>
> tag `v*` 自动出包是另一个旧 workflow `.github/workflows/ios-ipa.yml`
> （v2 WKWebView 套壳版），与本工程无关，不要动它。

---

## 数据源与隐私

- **教务**：正方教务系统，仍是 `http` 明文（校外移动数据下 443 不可达），
  与 Android `usesCleartextTraffic` 对齐；`NSAppTransportSecurity` 放开并在审核备注说明。
  登录密码经 RSA（`SecKeyCreateEncryptedData`，PKCS#1 v1.5）加密后存 Keychain，Face ID 解锁。
- **实时公交**：车来了 / 我 GPS 两套数据源，站点 / 到站信息走网络请求；
  「附近站点」用 `NSLocationWhenInUseUsageDescription`，仅使用期间定位，不后台定位。
- **乘车码**：`LSApplicationQueriesSchemes` 声明支付宝 / 银联 scheme，用于 `canOpenURL` / `openURL`
  真跳转；不内置任何支付凭证。
- **桌面小组件**：主 App 的 `WidgetBridge.publish()` 把「今日课程」写成 JSON 字符串
  （字段 `name / time / place / colorIndex / done`），通过 **App Group `group.com.tstc.zstangshi`**
  共享给小组件；`widget_today_updated` 记时间戳。小组件只读这同一份极简 JSON，
  不经过网络、不读 Keychain、不访问教务。
- **加密合规声明**：`ITSAppUsesNonExemptEncryption = false`。

---

## 已知限制

- **Windows 无法本地编译**：iOS SDK / Xcode 只能在 macOS 上，属平台限制而非工具链缺失。
  本仓库的配置与代码由 CI（`macos-26` + Xcode 26）做最终编译验证。
- ATS 全放开（`NSAllowsArbitraryLoads`），因校内教务仍是 http；上架审核需在备注里说明。
- App 图标直接复用 1024 PNG，暂未做 iOS 26 的 tinted / dark 图标变体。
- 未签名 ipa 需自行用 Sideloadly / AltStore 重签安装（免费账号 7 天有效期）。
