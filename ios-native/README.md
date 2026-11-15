# 掌上唐师 · iOS 原生版（v4.0.0）

面向唐山师范学院在校生的校园 App —— **iOS 原生 SwiftUI 重写**。
与 Android v4.0.0（`native/`，Kotlin，build 58）**功能完整对齐**，代码零共享。

| 项目 | 值 |
| --- | --- |
| Bundle ID | `com.tstc.zstangshi`（小组件 `com.tstc.zstangshi.TodayWidget`） |
| App Group | `group.com.tstc.zstangshi` |
| 版本 | `4.0.0`（build 58） |
| 最低系统 | iOS 17.0 · 仅 iPhone 竖屏 |
| 编译工具链 | **Xcode 26 / iOS 26 SDK** |
| 工程描述 | `project.yml`（XcodeGen），不提交 `.xcodeproj` |
| 出包 | **Codemagic**（`codemagic.yaml`） |

---

## 一、核心设计：4 个主页面 + 自绘胶囊 Dock

安卓真实设计（`ui/CapsuleDock.kt:292`）是 **4 个**一级页面：

```
LABELS = ["首页", "课表", "成绩", "我的"]
```

「实时公交」是**首页宫格第 7 格**的功能页，不是一级 tab。

本工程按 `CapsuleDock.kt`（325 行自绘 View）逐值复刻：
64pt 高、22pt 边距、内部 pad 5pt、选中块 320ms + Overshoot(1.4) 回弹、
拖动跟手拉长 1.06、每过一格轻震、按着别的格浮出淡胶囊预告。

切页用「整条页面带平移」（`ui/PageDeck.kt`）：从首页点「我的」时，
课表、成绩会真的依次掠过屏幕。时长照抄安卓：290ms 基准 + 45ms/格，夹 160–400ms。

---

## 二、功能实现状态

> 对照依据：`native/.../_FULL_FEATURE_AUDIT.md`（1354 行全功能盘点）

### 四个主页面

| 功能 | 状态 |
| --- | --- |
| 首页：问候 / 今日课程焦点卡 / 今日课程列表 | ✅ |
| 首页：8 格宫格（课表/成绩/绩点/校历/选课/推荐课表/公交/更多） | ✅ |
| 首页：学工系统横条（外部网页） | ✅ |
| 首页：下拉刷新（只核对登录态，保底 2 秒） | ✅ |
| 课表：网格（节次栏 + 星期表头 + 日期 + 斑马纹） | ✅ |
| 课表：左右滑翻周（跟手 0.82 / 边界 0.28 / 阈值 max(60, 20%宽)） | ✅ |
| 课表：周次选择 5 列弹层 | ✅ |
| 课表：点课程看详情 / 「编辑这门课」 | ✅ |
| 课表：点空格直接在该格新建课程 | ✅ |
| 课表：回到本周 FAB | ✅ |
| 成绩：概览 / 学期筛选 / 课程行 / 下拉刷新 | ✅ |
| 成绩：三层缓存（内存 30min → 磁盘 → 会话闸门） | ✅ |
| 我的：账号卡 / 登录 / 同步 / 退出 | ✅ |
| 我的：学期与教学周设置 | ✅ |
| 我的：外观三态（浅色/深色/跟随系统） | ✅ |
| 我的：桌面小组件（刷新增加入口说明） | ✅ |
| 我的：关于（版本/数据来源/开发者/项目地址外链） | ✅ |

### 教务功能

| 功能 | 状态 | 说明 |
| --- | --- | --- |
| 正方登录（CSRFToken + RSA + 302 跟跳） | ✅ | 修掉 Cookie 只取第一条、RSA 剥错符号位两个致命 bug |
| 课表拉取与解析 | ✅ | 兼容 kbList 在顶层/包在 data 里；同名同时段自动合并 |
| 成绩查询 | ✅ | 主表 + 明细双 POST，明细按 `jxb_id`/课名双键挂靠 |
| 学分绩点 | ✅ | 只读教务印的数字，不做本地换算 |
| 自主选课 | ✅ | 闸门判定 + 已选课程 + 课池（只读，与安卓一致） |
| **推荐课表（四级级联）** | ✅ | 年级/院系用页面字典；专业/班级从班级清单行提取 UUID；查询命 1 班直接出课表 |
| **课程编辑器** | ✅ | 8 字段全实现，**周次离散勾选**（每行 7 格 + 全选/单周/双周） |
| **调课 / 停课 / 补课 / 放假** | ✅ | **安卓没有 UI 入口**（模型与渲染完整但无入口），iOS 补全 |
| **作息设置**（节数/首节时间/每节时长课间） | ✅ | **安卓无入口且无法持久化**，iOS 补全并修掉持久化问题 |
| 内置校历（2026-2027 两学期 + 节假日） | ✅ | 自动推导开学日期 |
| 校历页（月历 + 教学周点 + 事件） | ✅ | |
| WebView（教务/学工，Cookie 共享） | ✅ | 会话共享，不用二次登录 |

### 实时公交（本次重点重写）

旧 iOS 版公交**完全不可用**：接口路径、参数是猜的，定位也失效。本次按真实协议重写：

| 功能 | 状态 |
| --- | --- |
| 车来了真实协议（`/bus/stop!encryptedHomePage.action` 等 7 个端点） | ✅ |
| 请求签名（按插入顺序 `"k"="v"&…` + 盐 + MD5 小写 → `cryptoSign`） | ✅ |
| 响应解密（`**YGKJ` 外壳 → Base64 → AES-256/ECB/PKCS5，双密钥） | ✅ |
| 掌上公交（`ApiData.do`，8 个 CMD 命令） | ✅ |
| 数据源切换 + 城市切换 | ✅ |
| 搜索（线路 + 站点） | ✅ |
| 附近站点（8 方位 × ring×700m 探针扩展） | ✅ |
| 站点详情（多站台切换 / 经过线路 / 到站） | ✅ |
| 线路详情三模式（到站 / 车辆 / 单车跟随） | ✅ |
| 候车站切换 + 记忆 | ✅ |
| 首站发车时刻表 | ✅ |
| 收藏线路 | ✅ |
| 20 秒轮询 + 离开页面取消 | ✅ |
| 乘车码（支付宝/云闪付三级降级） | ✅ |

**定位修复**（旧版「无法定位」的根因）：
1. `notDetermined` 时直接 `requestLocation()` 会立即失败 → 改成先请求授权、在 `didChangeAuthorization` 里续上定位；
2. 授权回调后没有重新发起定位 → 已补；
3. `CheckedContinuation` 可能重复 resume 崩溃 → `finish()` 幂等 + 防重入；
4. denied 时给出「去设置」引导。

### 桌面小组件

| 功能 | 状态 |
| --- | --- |
| 5 种状态（无课表 / 无开学日期 / 开学前 / 已结束 / 正常） | ✅ |
| small + medium 两种尺寸 | ✅ |
| 30 分钟兜底刷新 + 主 App 主动 reload | ✅ |
| 今日课程 / 正在上 / 下一节 | ✅ |

---

## 三、相对于旧 iOS 版修掉的致命 bug

旧版能编译出 IPA，但真机上功能全废、版式错位。逐项根因：

| # | 问题 | 根因 |
|---|---|---|
| 1 | 底栏 5 个 tab、公交错位成一级页 | 用了系统 `TabView` 并自行把公交提升为第 5 项；安卓是 4 项自绘胶囊 |
| 2 | 切页动画全丢 | 依赖系统 TabView 换页，没有「整条带平移」 |
| 3 | 教务登录必失败 | `value(forHTTPHeaderField: "Set-Cookie")` **只返回第一条**，`route` cookie 丢了被踢回登录页，却报「密码错误」 |
| 4 | 密码加密必失败 | `while bytes.first == 0 { removeFirst() }` 把 modulus 的符号位 0x00 剥没了 → `SecKeyCreateWithData` 返回 nil |
| 5 | 同步「假成功」 | `guard !semester.id.isEmpty else { return }` 静默丢数据，界面显示成功、重开就空 |
| 6 | 同步完首页不更新 | `bootstrap()` 一次性 `guard !didBootstrap`，`scenePhase` 反复调用都是空操作 |
| 7 | 同名课程重复排两列 | 合并逻辑要求周次有交集，「形势与政策」按周次拆分时交集为空 |
| 8 | 装不上 / 闪退 | `PRODUCT_NAME: "掌上唐师"` 中文可执行文件名，重签脚本拆路径 |
| 9 | **公交完全不可用** | 接口路径与参数是凭猜测写的；定位在 `notDetermined` 时直接失败 |

---

## 四、本地验证（不需要 Mac）

```powershell
# 1) 语法 + 类型检查
powershell -ExecutionPolicy Bypass -File scripts/typecheck.ps1

# 2) 纯逻辑单元测试（可真实运行）
powershell -ExecutionPolicy Bypass -File scripts/test-logic.ps1

# 3) 工程结构 / 工作流 / Info.plist 检查
node scripts/check-yaml.mjs
```

### 当前结果

```
=== Strict typecheck ===
  OK   [Data layer] 9 files
  OK   [Net parser (Foundation only)] 6 files
=== Parse check ===
  passed 48 / 48
ALL PASSED

=== 逻辑单元测试 ===
通过 138 · 失败 0
```

逻辑测试覆盖：日期/纪元日、周次表达式（单双周/全角逗号/写反区间）、
作息时间（唐山师范学院 12 节逐节校验）、周次计算、课表颜色分配、
完整课表 JSON 解析（含同名课程合并）、内置校历。
另用 36 项测试验证 RSA 的 SPKI/DER 拼装。

**代码规模**：主 App 48 个 Swift 文件 / 约 10,500 行 + 小组件 1 个文件。

> SwiftUI/UIKit/WidgetKit 的类型在 Windows 上无法检查（没有 SDK），只做了语法检查。
> 已人工审查并修掉 1 处必然编译失败（`refreshable` 缺 `@Sendable`）等 4 个问题。
> 若云编译仍报 UI 类型错误，把 `error:` 行贴回来即可快速修掉。

---

## 五、出 IPA（Codemagic）

> **GitHub Actions 当前不可用**：账号因计费问题被锁，所有作业启动即失败
> （历史每次 run 都是 4 秒失败）。改用 Codemagic 免费 M2 额度。

### 步骤

1. 打开 [codemagic.io](https://codemagic.io)，用 GitHub 登录
2. **Add application** → 选 `Chanmingfei/zstangshi`
3. 它会自动识别仓库根目录的 `codemagic.yaml`（本工程放在 `ios-native/`，
   配置文件里的 `APP_DIR: ios-native` 已对应好）
4. **Start new build** → 选 `ios-ipa`
5. 约 10–20 分钟后，在 Artifacts 下载 `zstangshi-ios-unsigned.ipa`
6. 用 [Sideloadly](https://sideloadly.io/) 以你的 Apple ID 重签安装

### 如果 Codemagic 也不可用

有 Mac 的话一条命令即可：

```bash
cd ios-native
brew install xcodegen
xcodegen generate
xcodebuild -project ZhangShangTangShi.xcodeproj -scheme ZhangShangTangShi \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
# 然后 Payload/ 打包成 ipa
```

---

## 六、已知限制

1. **推荐课表不导入本地**：与安卓一致，只用于查看（安卓也没有导入功能）。
2. **选课只读**：与安卓一致，note 明写「本页只读，抢课请到教务网页完成」。
3. **小组件依赖 App Group**：未签名包重签后若 App Group 能力不可用，小组件可能读不到数据
   （App 本身不受影响，会自动回退到 standard defaults）。
4. **ATS**：教务是 http 明文，需要 `NSAllowsArbitraryLoads` + `tstc.edu.cn` 例外。
5. **App 图标**：复用安卓 1024 图标，未做 iOS 26 tinted / dark 变体。
6. **公交依赖第三方接口**：车来了 / 掌上公交的协议可能变更或被限流。

---

## 七、许可与声明
个人开发项目，**非学校官方发布**。
内置校历数据来源：唐山师范学院教务处（jwc.tstc.edu.cn）。
胶囊 Dock 交互参考「烫梨」(github.com/augustlies/tangli, GPL-3.0)，本工程为独立实现。
车来了协议移植自 Trackr (github.com/5151561/Trackr, MIT)。

---

## 八、工程结构

```
ios-native/
├── project.yml                     XcodeGen 工程描述（唯一真源）
├── codemagic.yaml                  Codemagic 出包配置
├── README.md
├── Resources/
│   ├── Info.plist                  ATS / 定位 / 查询 schemes / 竖屏 / 启动色
│   ├── App.entitlements            App Group
│   └── Assets.xcassets/            AppIcon / AccentColor / LaunchBackground / CampusArt
├── Widget/                         ★ 独立 app-extension target
│   ├── Info.plist
│   └── TodayWidget.swift           5 种状态 + small/medium
├── Sources/
│   ├── App/
│   │   ├── ZSTApp.swift            @main + RootView + PageDeck
│   │   └── AppState.swift          全局状态 + MainTab(4 项)
│   ├── Design/
│   │   ├── Theme.swift             颜色/字号/圆角（与 colors.xml 逐值对齐）
│   │   ├── Motion.swift            动效常量（与安卓时长逐值对齐）
│   │   └── Glass.swift             Liquid Glass + iOS 17–25 回退
│   ├── Data/                       Dates / WeekMath / WeekRange / ScheduleSettings
│   │                               Models / CourseColorAssigner / Holidays / WeekOverlay
│   │                               DayPlan / AcademicCalendar / Store
│   ├── Net/
│   │   ├── Http.swift              带 Cookie 的手动重定向
│   │   ├── RSA.swift               PKCS#1 v1.5 + SPKI DER
│   │   ├── JwClient.swift          登录 + 取课表（actor）
│   │   ├── KbParser.swift          课表 JSON 解析
│   │   └── JwApi.swift             成绩/绩点/选课/推荐课表（actor）
│   ├── Bus/
│   │   ├── BusModels.swift         统一模型 + 文案换算
│   │   ├── ChelaileClient.swift    车来了（真实协议 + 签名 + AES）
│   │   ├── MygolbsClient.swift     掌上公交
│   │   ├── BusGateway.swift        网关协议 + 两个实现
│   │   ├── BusStore.swift          收藏 / 城市 / 候车站记忆
│   │   ├── BusLocation.swift       ★ 定位（修掉旧版无法定位）
│   │   ├── BusViewModel.swift      状态机 + 轮询
│   │   ├── BusRootPage.swift       公交首页
│   │   ├── LineDetailPage.swift    线路详情（到站/车辆/跟随）
│   │   ├── StationDetailPage.swift 站点详情
│   │   └── TransitCode.swift       乘车码跳转
│   ├── Pages/
│   │   ├── SplashPage.swift        启动页（呼吸动画 + 自动登录分支）
│   │   ├── LoginPage.swift         登录
│   │   ├── HomePage.swift          首页（今日课程 + 8 格宫格）
│   │   ├── TimetablePage.swift     课表 + 网格 + 周次 + 详情
│   │   ├── CourseEditor.swift      ★ 课程编辑器（8 字段）
│   │   ├── AdjustManagerPage.swift ★ 调课/停课/补课/放假
│   │   ├── ScheduleSettingsPage.swift ★ 作息设置
│   │   ├── GradesPage.swift        成绩
│   │   ├── FeaturePage.swift       成绩/绩点/选课
│   │   ├── RecommendPage.swift     ★ 推荐课表四级级联
│   │   ├── CalendarPage.swift      校历
│   │   └── MinePage.swift          我的
│   ├── UI/
│   │   ├── CapsuleDock.swift       ★ 4 项胶囊 Dock
│   │   └── Components.swift        卡片/空态/按钮/分段/Toast
│   └── Web/WebPage.swift           WKWebView + Cookie 注入
└── scripts/
    ├── typecheck.ps1               Windows 本地语法+类型检查
    ├── test-logic.ps1              逻辑单元测试（138 项）
    ├── main.swift                  测试用例
    └── check-yaml.mjs              工程结构校验
```
