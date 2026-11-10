# 掌上唐师 · iOS 原生版（`ios-native/`）

v3 的 iOS 对应实现：**SwiftUI，纯原生，与 `native/`（Android）代码零共享、只对齐功能与视觉**。

和 `../ios/` 的区别：`ios/` 是 v2 时代的 WKWebView + H5 套壳（业务逻辑还在 JS 里），
`ios-native/` 是真正的原生重写 —— 页面、动画、教务请求全部是 Swift。

- Bundle ID：`com.tstc.zstangshi`
- 最低系统：iOS 16.0 · 仅 iPhone 竖屏
- 规模：28 个 Swift 文件、约 4,600 行
- 工程描述：`project.yml`（XcodeGen），**不提交 `.xcodeproj`**

---

## 构建

`.ipa` 里的可执行文件是 arm64 的 Mach-O，只能用 Apple 的 iOS SDK 编译，而 SDK 只在 Xcode 里、
Xcode 只在 macOS 上。Windows 上没有任何合法途径交叉编译 —— 这不是工具链没装齐，是平台限制。

**有 Mac：**

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
           -derivedDataPath build \
           CODE_SIGNING_ALLOWED=NO build

mkdir -p Payload
cp -R build/Build/Products/Release-iphoneos/*.app Payload/
zip -qry ZhangShangTangShi-unsigned.ipa Payload
```

拿到 ipa 后用 [Sideloadly](https://sideloadly.io) / AltStore 以自己的 Apple ID 重签安装
（免费账号 7 天有效期）。

**没有 Mac：** 用 `.github/workflows/ios-native-ipa.yml`
（Actions → Build iOS Native IPA → Run workflow），或把仓库导入 [Codemagic](https://codemagic.io)
按同一套命令跑。产物同样是未签名 ipa。

> **状态说明**：本工程在 Windows 上编写，**尚未经过 Xcode 编译**。
> 代码结构、类型引用、资源引用都做过静态交叉核对（无未定义符号、无悬空资源），
> 但 Swift 的类型推断与 SwiftUI 的泛型推导只有真编译器能最终确认。
> 第一次在 Mac / CI 上跑，可能需要修几个编译期小问题 —— 这是预期内的。

---

## 目录

```
ios-native/
├── project.yml                       XcodeGen 工程描述（唯一真源）
├── Resources/
│   ├── Info.plist                    ATS 放开、启动色、竖屏锁定
│   └── Assets.xcassets/              AppIcon / AccentColor / LaunchBackground / CampusArt
└── Sources/
    ├── App/
    │   ├── ZSTApp.swift              入口 + RootView（四页 + 胶囊底栏）
    │   ├── AppState.swift            全局状态：登录、课表、周次、主题、Toast
    │   └── Theme.swift               颜色 / 字号 / 圆角 / 间距（含深色）
    ├── Data/
    │   ├── Models.swift              Course / Term / Semester
    │   ├── WeekMath.swift            周次换算 + 内置校历
    │   ├── Schedule.swift            节次时间与当前课程判定
    │   ├── DayPlan.swift             「今日课程」聚合
    │   ├── WeekRange.swift           教学周区间
    │   └── Store.swift               UserDefaults 快照 + Keychain 存密码
    ├── Net/
    │   ├── Http.swift                URLSession 封装（Cookie 复用）
    │   ├── RSA.swift                 SecKeyCreateEncryptedData（PKCS#1 v1.5）
    │   ├── JwClient.swift            正方登录 / 课表
    │   ├── JwApi.swift               成绩 / 绩点
    │   └── KbParser.swift            课表 HTML 解析
    ├── Pages/
    │   ├── HomeView.swift            首页
    │   ├── TimetableView.swift       课表页 + 周次切换
    │   ├── TimetableGrid.swift       课表网格
    │   ├── GradesView.swift          成绩 / 绩点
    │   ├── MineView.swift            我的
    │   ├── LoginView.swift           登录
    │   └── Sheets.swift              底部弹层合集（周次、学期、课程详情）
    ├── UI/
    │   ├── PageDeck.swift            横向页面带（对齐 Android 的 PageDeck）
    │   ├── CapsuleDock.swift         悬浮胶囊底栏
    │   └── Components.swift          Card / SectionTitle / Segmented / Toast / 图标
    └── Web/WebScreen.swift           应用内浏览器（WKWebView）
```

---

## 与 Android 版的对应关系

| 能力 | Android（`native/`） | iOS（这里） |
| --- | --- | --- |
| 四页容器 | `ui/PageDeck`（自定义 ViewGroup + translationX） | `UI/PageDeck.swift`（`HStack` + `offset`） |
| 切页时长 | `290 + 45 × (格数-1)`，夹 `[160,400]` ms | 同一套数字，`PageDeck.duration(for:)` |
| 切页缓动 | `DecelerateInterpolator(1.2)` | `.timingCurve(0.05, 0.7, 0.1, 1.0)` |
| 底栏 | `ui/CapsuleDock` | `UI/CapsuleDock.swift` |
| 底部弹层 | `ui/Sheet`（Dialog + 自绘动画） | `Pages/Sheets.swift`（`presentationDetents`） |
| 下拉刷新 | `ui/PullRefreshLayout` | `.refreshable` |
| 返回手势 | `OnBackAnimationCallback` 跟手 | 系统边缘返回 + `interactiveDismissDisabled` 按需关 |
| 深色模式 | `applyOverrideConfiguration` 注入夜间位 | `.preferredColorScheme(app.preferredScheme)` |
| 密码加密 | javax.crypto RSA/PKCS#1 | `SecKeyCreateEncryptedData`（PKCS#1 v1.5） |
| 会话 | `JSESSIONID` + `CookieManager` | `HTTPCookieStorage` |
| 密码保存 | SharedPreferences | Keychain |
| 主题色 | `@color/brand` = `#0062CC` / 暗色 `#2E86E8` | `AccentColor` 同两色 |

---

## 两个刻意保留的差异

1. **不做 Android 那种「二级页圆角卡片」转场**。iOS 的导航转场是系统统一的水平推入，
   自定义成卡片反而破坏平台一致性，也拿不到系统的交互式返回。iOS 用 `NavigationStack`
   的默认转场 + 边缘右滑返回。
2. **课表横滑翻周**保留，但只在课表网格区域识别，避免和页面滚动打架
   （Android 侧当年为此专门加了 `requestDisallowInterceptTouchEvent`，iOS 靠手势优先级控制）。

---

## 已知限制

- 未在 Xcode 下编译验证（见上文状态说明）。
- App 图标目前直接复用 v2 的 1024 PNG，没有做 iOS 26 的 tinted / dark 变体。
- ATS 全放开（`NSAllowsArbitraryLoads`），因为校内教务仍是 http，与 Android 的
  `usesCleartextTraffic` 对齐；上架审核需在备注里说明。
- 桌面小组件（WidgetKit）尚未实现 —— Android 侧的「今日课程 4×2」在 iOS 上没有对应物。
