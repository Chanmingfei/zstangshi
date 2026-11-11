# 掌上唐师

面向唐山师范学院在校生的校园服务 App（个人开发，**非学校官方发布**）。把教务系统与学工系统里
最常用的几件事装进手机：课表、成绩、绩点、推荐课表、选课、校历、奖助贷与请假，
以及校门口的**实时公交**（到站预测、候车站、首站发车时刻表）。

**当前版本 v3.0.32（versionCode 51）· Android 原生重写** · 零第三方运行时依赖 · 纯 framework，
不引 androidx · `bash native/build.sh` 一条命令出 APK · iOS 为独立的 SwiftUI 工程

---

## 下载与安装

> **直接去 Releases 下载，不需要自己编译。**
>
> - 始终拿最新版：<https://github.com/Chanmingfei/zstangshi/releases/latest>
> - 本次版本：<https://github.com/Chanmingfei/zstangshi/releases/tag/v3.0.32>
> - APK 直链：<https://github.com/Chanmingfei/zstangshi/releases/download/v3.0.32/zstangshi-v3.0.32.apk>

| 项目 | 值 |
| --- | --- |
| 版本 | 3.0.32（versionCode 51） |
| 文件 | `zstangshi-v3.0.32.apk`，1,116,780 字节（约 1.07 MiB） |
| 系统要求 | Android 8.0（API 26）及以上，目标 Android 14（API 34） |
| 权限 | `INTERNET`、`ACCESS_NETWORK_STATE`；另加 `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`，**只在实时公交首页取一次坐标**，不做后台定位、不上传位置 |
| 签名 | 本地调试密钥（CN=ZSTS），与 v3.x 各版本一致，**可直接覆盖安装** |
| SHA-256 | `e9e374e545693f86d33a4d0c42ff08108bd87d0b1a35c1eade6e410f78958f8a` |

**安装步骤**

1. 从 Releases 下载 APK，在手机上点开。
2. 提示「未知来源」时，允许当前浏览器／文件管理器安装（调试签名、未上架商店，属正常现象）。
3. 已装 v3.x 旧版可直接覆盖，登录状态与本地课表保留；若提示「应用未安装」，卸载旧版后重装。

校验下载完整性（可选）：

```bash
certutil -hashfile zstangshi-v3.0.32.apk SHA256   # Windows
sha256sum zstangshi-v3.0.32.apk                   # macOS / Linux
```

**iOS 没有预编译包。** `.ipa` 必须由 Apple 的 SDK 编译，而 SDK 只在 Xcode 里、Xcode 只在 macOS 上。
`ios-native/` 是一份结构完整的 SwiftUI 工程，配了 XcodeGen 描述；有 Mac 就 `xcodegen generate`
一条命令打开，没 Mac 用 `.github/workflows/ios-native-ipa.yml` 或 [Codemagic](https://codemagic.io)
产出未签名 ipa，再用 Sideloadly 以免费 Apple ID 重签（7 天有效期）。
详见 [ios-native/README.md](ios-native/README.md)。

---

## 这个 App 解决什么问题

学校教务（正方）和学工系统都是为 PC 浏览器设计的页面：手机上要缩放、反复登录、课表成绩散在多个菜单。
这个 App 做三件事：

1. **把页面变成接口。** 原生层代发 HTTP 请求，把教务返回的页面解析成结构化数据，按手机端的信息层级
   重新呈现 —— 而不是把网页塞进一个小窗口。
2. **把多系统合成一个入口。** 首页宫格直达常用功能；登录页可「仅登录学工系统」，只想办奖助贷、请假时
   不必登录教务；「更多功能」在应用内 WebView 打开原系统，覆盖尚未适配的长尾功能。
3. **处理移动端特有的网络问题。** 校外移动数据下教务的 443 端口常被阻断而 80 可用，登录时自动探测
   可用协议，避免「浏览器能开、App 登不上」。

### 功能清单

**首页** —— 问候与当前周次、今日课程卡片、「常用功能」宫格（我的课表 / 成绩 / 学分绩点 / 校历 /
选课 / 推荐课表 / 实时公交 / 更多功能）、校园线稿插画。下拉刷新只核对登录态，不覆盖本地课表；若教务会话已超时，
刷新时自动重登，动画保底转满 2 秒，不弹文字提示。

**课表** —— 周次胶囊（‹ 第 N 周 ›，点开是周次网格弹层，也可左右滑动切周）、课程网格按配色渲染、
单击查看详情、长按编辑、「回到本周」悬浮按钮在翻离本周时浮出。周次起点由
「教务下发 → 本地课表起始日 → 内置校历 → 本周周一」四级兜底。

**成绩 / 学分绩点** —— 进入页面才加载（不拖慢登录），下拉才真正拉取；支持按学期筛选；
成绩保留教务原文口径（等文字成绩不改写），绩点单独一页。数据来自本地快照，界面上如实标注读取时间。

**我的** —— 账号与重新登录、同步课表、自动登录开关、课表设置（学期与教学周）、
外观（浅色 / 深色 / 跟随系统）、桌面小组件（今日课程一键放到桌面）、
关于（版本、数据来源、开发者 GitHub / Gitee 主页、项目地址）。

**桌面小组件** —— 4×2 的「今日课程」，只显示「正在上」「下一节 14:00」这类在区间内恒成立的文案，
没有会过期的倒计时，所以闹钟晚一两分钟也不会显示错。

**实时公交** —— **不需要登录**，首页按用户位置由近及远列站点（继续下滑按圈往外扩，每圈 700m），
一个站有多个站台就把线路全摊开（超过 3 条显示「展开全部」）；点站名进站点详情，按站台分组列线路；
点线路进线路详情：横排站线左滑看全线、圆点上标车辆真实位置（不在站上就画在两站之间）、
点任意站名**原地**换候车站（已选中的再点一次才弹窗问「是否跳转该站点详情」）、右上角换向也原地完成。
下层是「首站发车时刻表」：下一班发车时刻、今日预测分时段班次、昨日实际班次，点「完整时刻表」看全量。

数据口径上做了两件事：① 首页、站点详情、线路详情**统一走同一套到站预测**（上游「附近站点」接口只对最近
几站算实时，直接用会出现「首页说没车、点进去有车」）；② 进页面即拉一次、之后每 20 秒静默轮询，
只替换那一行文字，不动滚动位置和已选候车站。

---

## 技术逻辑

### 1. 从 WebView 套壳换成原生

v2 是「一套 H5 + 两个原生壳」，v3 是彻底的原生重写（`native/` 与 `ios-native/` 完全独立，
与 legacy 的 `web/`、`android/`、`ios/` 零共享）。换的原因不是「原生更好听」，而是三件事在 WebView 里
做不到位：

| 需求 | WebView 里的结果 | 原生做法 |
| --- | --- | --- |
| 课表横滑翻周 | JS 模拟手势，跟手迟滞 | `WeekSwipe` + `requestDisallowInterceptTouchEvent` 锁父滚动 |
| 底栏切页 | 页面互相淡入淡出 | `PageDeck` 整条页面带平移，中间页真实掠过 |
| 二级页返回 | 无法参与系统动画 | `OnBackAnimationCallback` 拿到 progress，逐帧跟手 |

### 2. 切页：连续平移而不是渐变

四个主页面**同时**排在一条横向带子上，切页时平移整条带子。所以从首页点「我的」，课表、成绩会依次
从眼前掠过，而不是两页对着擦。时长 `290ms + 45ms × (剩余格数 − 1)`，夹在 `[160, 400]` ——
跨三格 380ms、跨一格 290ms，极差 90ms，是同一套节奏，也不会让中间两页一糊而过。

容器只有一屏宽，直接平移它会被自身 bounds 裁掉、触摸也失效，所以每个子页各自 `layout` 在
`[0, 0, w, h]`，用各自的 `translationX` 表达位置。

### 3. 二级页：圆角卡片转场

系统只在**返回**方向画圆角卡片（AOSP `CrossActivityBackAnimation` 取设备圆角半径，把正在关闭的窗口
裁圆角再缩到 90%），**进入**方向走的是窗口动画，而窗口动画只有 alpha / translate / scale，
没有圆角属性，也没有公开 API 能给应用窗口设圆角。于是同一个页面进来直角、出去圆角。

解法是自己画整页：`windowIsTranslucent` + 透明窗口底色，在 `android.R.id.content` 里插一层自建
`FrameLayout` 当卡片，圆角用 `ViewOutlineProvider` + `setClipToOutline`，投影用 `setElevation`，
位移与圆角都随「滑出比例 p」变化，落位归 0 —— 静止状态与普通页面逐像素一致。

> 这一版踩过的坑：圆角最初裁在 `DecorView` 上，`setClipToOutline` 对窗口根节点不生效，
> 结果进出都是「没有边界、没有投影的矩形」。必须换成普通 View 才裁得出来。

### 4. 预测性返回

manifest 开 `enableOnBackInvokedCallback`。API 34+ 用 `OnBackAnimationCallback` 的
`onBackProgressed` 做跟手，API 33 用 `OnBackInvokedDispatcher`，API 32 及以下退回
`onBackPressed` + 自绘滑动。一级页把进度喂给 `PageDeck.jumpTo(position × (1 − p))`，整条带子跟着
手指回滑，松手从当前位置收尾；二级页喂给 `PageSlide`。

### 5. 数据层

- 密码用教务页面下发的 RSA 公钥加密后再 POST；`JSESSIONID` 复用，超时自动重登。
- `Store` 存本地快照，按登录时刻判断新鲜度；姓名按学号隔离，换账号不会串。
- 成绩 / 绩点统一解析成「标题 + 副标题 + 数值 + 备注」的行模型，界面只写一套渲染。
- 刷新策略：首页刷新只重新登录、不动课表；成绩与绩点进页面才加载，下拉才拉取。

### 6. 深色模式

manifest 声明了 `configChanges="uiMode"`（切深浅色不重建 Activity），所以系统下发的配置会覆盖
`attachBaseContext` 里设的夜间位。修复办法是在 `applyOverrideConfiguration` 这个唯一切口里重新注入，
否则 `values-night/colors.xml` 永远不会生效。

---

## 仓库结构

```
.
├── native/        ★ Android 原生版（v3 主线，纯 framework，build.sh 出 APK）
├── ios-native/    ★ iOS 原生版（SwiftUI + XcodeGen，与 Android 代码零共享）
│
├── web/           legacy · v2 的 H5 业务代码（v3 不再使用）
├── android/       legacy · v2 的 WebView 壳工程
├── ios/           legacy · v2 的 WKWebView 壳工程（含旧的 ipa 云构建链路）
├── docs/          架构设计、PRD、接口实测记录、验收口径
├── tools/         legacy · v2 的纯函数单测与规则守卫
├── reference/     小爱课程表学校适配脚本（设计参考，不参与构建）
├── server.js      legacy · v2 的本地预览服务器 + /jwxt/ 正向代理
├── codemagic.yaml legacy · v2 的 Codemagic 出包配置
└── .github/       GitHub Actions（v2 ipa 链路 + v3 iOS 原生 ipa 链路）
```

v2 的完整代码另存于 `legacy-webview-v2.0.1` 分支与 `v2.0.1` tag，可随时取回。

---

## 本地构建

```bash
# Android：需要 JDK 17 + Android SDK（build-tools 34.0.0 + platform android-34）
bash native/build.sh
# → android/build/app-debug.apk
```

版本号只在 `native/version.properties` 一处维护，脚本会同时写进 manifest 与包信息。

```bash
# iOS：需要 macOS + Xcode
brew install xcodegen
cd ios-native && xcodegen generate && open ZhangShangTangShi.xcodeproj
```

legacy（v2）仍可运行：

```bash
node server.js                                 # 本地预览 http://127.0.0.1:5173
npm run verify                                 # v2 单测 + 规则守卫
bash android/build-manual.sh                   # v2 的 WebView 壳 APK
```

---

## 版本脉络（v3）

原生版从 3.0.8 一路打磨到 3.0.32，几乎每个小版本都对应一个真机测出来的具体问题：

| 版本 | 关键修复 |
| --- | --- |
| 3.0.9 | 课表横滑不灵敏 —— 真因是父 ScrollView 抢事件，补 `requestDisallowInterceptTouchEvent` |
| 3.0.12 | 课表顶栏三种控件规格混排 + `‹›` 字形天然偏上 → 统一高度、字号，关 `font padding` |
| 3.0.13 | 推荐课表把「本周」当第 1 周 → 补全起始日四级兜底链 |
| 3.0.17–18 | 密码显隐失效（位运算判错 + `setSingleLine` 重置 `inputType`），改为显式布尔 + 调整调用顺序 |
| 3.0.19 | 切页是渐变不是平移 → 去 alpha，改纯 `translationX` |
| 3.0.21 | 切页不连续 → 重写为 `PageDeck` 整条页面带 |
| 3.0.22 | 不同底栏项切换用时差异过大（260–780ms）→ 收敛到 290–380ms |
| 3.0.23–25 | 二级页进出场圆角统一；圆角裁在 DecorView 上不生效 → 换成自建卡片层 + 投影 |
| 3.0.26 | 接入实时公交：附近站点 / 站点详情 / 线路详情 / 搜索 |
| 3.0.27 | 站线连成一条横线、车辆按两站比例落点；候车站原地切换、换向原地完成；弹窗统一为底部 Sheet |
| 3.0.28 | 首页与线路详情**口径不一致**（首页写「已到站」、详情写「1 分钟」）→ 三处统一走同一套预测；进页面即刷 + 20 秒轮询 |
| 3.0.29 | 「继续下滑加载更远站点」失效 —— 附近接口不给经纬度，旧逻辑把探回来的站全丢了 → 改按站点详情补坐标 + 坐标缓存 |
| 3.0.30 | 点「展开全部」后新线路一直停在「…」→ 展开时对新增行立刻补一次预测，并摘掉收起行的旧绑定 |
| 3.0.31 | 接入首站发车时刻表（`preStartTimetableNew`）：下一班发车、今日预测分时段、昨日实际班次 |
| 3.0.32 | 搜索能搜站点、结果下滑翻页；进首页必重新定位（换地方后不再停在旧坐标）；定位感知提速（先按系统缓存点铺一屏再校正）；首屏只等附近站点、详情随后补进卡片 |

---

## 第三方来源与致谢

**本项目没有 fork、复制或 vendoring 任何 GitHub 仓库的代码**，没有 git submodule，运行时零第三方依赖。
课表数据与交互**同时参考了 WakeUp 课程表与小米小爱课程表**——这是两个不同产品：前者是独立第三方
课程表 App，后者是小米的课程表服务；底部导航与本地课表的产品设计另参考了烫梨（tangli）。

| 来源 | 关系 | 说明 |
| --- | --- | --- |
| **WakeUp 课程表** · [官网](http://www.wakeup.fun/) | 数据与交互参考 | 独立的第三方课程表 App，**不是小米产品**。v3 的课程卡配色思路、课程详情层、复制课表文案的字段顺序参考了它的交互手感。 |
| **烫梨（tangli）** · [GitHub](https://github.com/augustlies/tangli)（GPL-3.0） | 交互与视觉参考 | 本地课表、桌面小组件、免登录查看这几项能力的**产品思路**来自它。底部悬浮胶囊导航（`native/.../ui/CapsuleDock.java`）照它 `CapsuleDock` 的交互与视觉语言用 Java 重写。**没有复制其源码**（对方是 Android 原生 Java，本项目按 4 项、图标在上文字在下重新设计）；若后续引入其任何源码，需按其 GPL-3.0 处理许可。 |
| **小爱课程表**（小米）· [解析器格式文档](https://open-schedule-prod.ai.xiaomi.com/docs/#/help/?id=parser) | 解析器规范参考 | `reference/wakeup-parser-tstc.js` 是**本项目作者本人**为唐山师范学院写给小爱课程表的学校适配脚本，遵循小爱 `scheduleHtmlParser(html)` 规范。**文件名沿用早期 `wakeup-` 前缀，容易误解：它不是 WakeUp 课程表的解析器。** 该文件只作设计参考，不参与构建。 |
| **正方教务系统** `jwxt.tstc.edu.cn` | 页面样本与接口分析素材 | `docs/diagnostics/` 下的页面 JS 是从教务站点抓取的原文样本，仅用于逆向确认端点与字段口径，**不参与构建或运行**，版权归原系统所有。 |
| **唐山师范学院公开资料** | 数据／素材来源 | 校历来自教务处 [校历查询](https://jwc.tstc.edu.cn/xlcx.htm)，作息时间来自 [教务处公告](https://jwc.tstc.edu.cn/info/1247/2699.htm)，校徽与校园照为学校公开素材。 |
| **学工系统** `sws.tstc.edu.cn` | 外链入口 | 仅在应用内 WebView 打开原页面，不解析、不复制其代码。 |
| **车来了 / 追客（Trackr）** | 实时公交数据来源与协议参考 | 「实时公交」的数据来自车来了的公开查询接口（唐山站 `cityId=056`）。请求签名算法与字段口径参照开源客户端 Trackr（Kotlin）的实现**自行用 Java 重写**（`native/.../bus/BusClient.java`，零依赖），**没有复制其源码、没有引入其依赖**；协议事实本身不受著作权保护，但若后续要搬运其任何代码，需按其 GPL-3.0 处理。站点、线路、到站时间版权归原数据提供方，App 内只实时查看，不做本地留存与外传。 |
| **工具链** | 构建／分发工具 | Android SDK build-tools（`aapt2` / `d8` / `zipalign` / `apksigner`）、Xcode、XcodeGen、Codemagic、Sideloadly、GitHub Actions。 |

「掌上唐师」及校徽为说明性使用，本项目与学校、正方软件、WakeUp 课程表、小米（小爱课程表）均无官方关联。

---

## 隐私与安全提示

- **本仓库是公开仓库**，`docs/` 与 `reference/` 中含演示用的真实姓名、学号、班级、成绩统计与联系方式。
  若要对外分发，请先脱敏或把仓库转为私有。
- Release 中的 APK 使用本地调试密钥签名，**仅供自用／测试**，不要当作官方包传播；正式发布需更换签名。
- 登录密码以 RSA 加密后提交给教务，本地可选择保存（Android 存 SharedPreferences，iOS 存 Keychain），
  不上传任何第三方服务器；所有请求只到学校域名。
- **实时公交** 是唯一会对外发请求的功能：只把定位坐标发给公交数据服务商用于查附近站点，
  不携带学号、姓名、账号等任何身份信息；可拒绝定位授权，改用手动搜索线路。
- 演示数据与成绩统计仅用于界面和算法验收，**不作为学业认定依据**；公交到站时间来自数据服务商预测，
  仅供参考，以实际车辆为准。
- 仓库尚未添加开源许可证（LICENSE），默认保留所有权利；如需开源授权请单独声明。

## 相关文档

- [native/README.md](native/README.md) — Android 原生版：构建、目录、自绘控件实现与踩坑
- [ios-native/README.md](ios-native/README.md) — iOS 原生版：XcodeGen、目录、与 Android 的对应关系
- [ios/README.md](ios/README.md) — legacy v2 iOS 壳工程（仅作存档）
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 架构、数据结构与动效系统（v2 时期撰写，v3 结构见各端 README）
- [docs/remote-api-map.md](docs/remote-api-map.md) — 教务接口实测记录（端点、字段、周次、协议可达性）
- [Releases](https://github.com/Chanmingfei/zstangshi/releases) — 各版本安装包与更新说明
