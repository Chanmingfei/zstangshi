# 掌上唐师

面向唐山师范学院在校生的校园服务 App（个人开发，**非学校官方发布**）。把教务系统与学工系统里
最常用的几件事装进手机：课表、成绩、绩点、推荐课表、选课、校历、奖助贷与请假，
以及校门口的**实时公交**（到站预测、候车站、首站发车时刻表、乘车码）。

**当前版本 v4.0.0（versionCode 58）· 2026-11-15 发布 · 全量 Kotlin 重写**
零第三方运行时依赖 · 不引 androidx、不引 Gradle · 纯 Android framework，
`bash native/build.sh` 一条命令直出可安装 APK

> 4.0.0 把 54 个源码文件（约 2.29 万行）从 Java 全量改写为 Kotlin，
> 功能、界面、文案、尺寸、动画与 v3.1.1 保持一致；在此基础上把**实时公交**
> 按 [追客 Trackr](https://github.com/5151561/Trackr)（MIT）的口径重新打磨了一遍：
> 主卡三态、到站文案统一、数据同源、乘车码悬浮按钮、单车跟随。
> 完整修改记录见 [CHANGELOG.md](CHANGELOG.md)。

---

## 📥 下载哪个版本？

**先确认你的手机系统。**

| 你的设备 | 下载 | 版本标识 |
| --- | --- | --- |
| **鸿蒙 / HarmonyOS**<br>华为、荣耀新机型 | **[⬇ 鸿蒙专版](https://github.com/Chanmingfei/zstangshi/releases/tag/harmony-v4.0.0)**<br>`zstangshi-harmony-4.0.0.apk` | 桌面名 **掌上唐师·鸿蒙版**<br>关于页有「版本类型：鸿蒙专版」 |
| **普通安卓**<br>小米 / OPPO / vivo / 三星 / 一加 等 | **[⬇ 标准版](https://github.com/Chanmingfei/zstangshi/releases/tag/v4.0.0)**<br>`zstangshi-v4.0.0.apk` | 桌面名 **掌上唐师**<br>关于页无「版本类型」行 |

> 始终拿最新版：<https://github.com/Chanmingfei/zstangshi/releases/latest>

### 不确定自己是不是鸿蒙？

打开手机 **设置 → 关于手机**：

- 看到 **「HarmonyOS 版本」** 或 **「鸿蒙版本」** → 下载**鸿蒙专版**
- 看到 **「Android 版本」** 或 **「MIUI / ColorOS / OriginOS / One UI」** → 下载**标准版**

### 两个版本有什么区别

**功能完全一样**，没有删减。区别只在鸿蒙兼容性处理：

| | 标准版 | 鸿蒙专版 |
| --- | --- | --- |
| 功能 | 全部 | 全部（**一字不差**） |
| 包名 | `com.tstc.zstangshi` | `com.tstc.zstangshi`（相同） |
| 版本 | 4.0.0（58） | 4.0.0-harmony（58） |
| 实时公交 | ✅ | ✅ |
| 鸿蒙登录兼容 | ❌ 会闪退 | ✅ 已修复 |
| APK 体积 | 3.48 MiB | 1.62 MiB |

### 为什么鸿蒙需要单独一版

安卓版装在鸿蒙上踩了两个坑，都和「鸿蒙兼容层缺 Android 专有 API」有关：

1. **一点登录就闪退** —— 三处 Android 专有 API（`AppWidgetManager`、
   `OnBackAnimationCallback`、`WindowInsets.Type`）在鸿蒙上不存在，抛出
   `NoClassDefFoundError` / `NoSuchMethodError`。这是 **Error 不是 Exception**，
   `catch (Exception)` 抓不住，异常穿透到 UI 线程顶层就把进程杀了
2. **登录报 `URLEncoder` 错误** —— 代码用了 Java 10 才有的 `encode(String, Charset)`
   重载，鸿蒙只有 Java 8 那批 API

两处都已修复，详见 [harmony-android/README.md](harmony-android/README.md)。

---

## 标准版（安卓）

| 项目 | 值 |
| --- | --- |
| 版本 | 4.0.0（versionCode 58） |
| 文件 | `zstangshi-v4.0.0.apk`，3,652,204 字节（约 3.48 MiB） |
| 系统要求 | Android 8.0（API 26）及以上，目标 Android 14（API 34） |
| 权限 | `INTERNET`、`ACCESS_NETWORK_STATE`；另加 `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`，**只在实时公交首页取一次坐标**，不做后台定位、不上传位置 |
| 签名 | 本地调试密钥 `CN=ZSTS`，与 v3.x 各版本一致（证书 SHA-256 `5999bc84cd45f1e55b6afc711cfa0c53825b1bf40fa3530606d5e990cada11de`），**可直接覆盖安装** |
| SHA-256 | `d420c75ce349d274bfc491d23c105aed350bad7fc8cb818046aef2e2532bc598` |

APK 比 v3.1.1（1.10 MiB）大，主要是 Kotlin 标准库与编译产物，没有做 R8 裁剪。

**安装步骤**

1. 从 Releases 下载 APK，在手机上点开。
2. 提示「未知来源」时，允许当前浏览器／文件管理器安装（调试签名、未上架商店，属正常现象）。
3. 已装 v3.x 旧版可直接覆盖，登录状态与本地课表保留；若提示「应用未安装」，卸载旧版后重装。

校验下载完整性（可选）：

```bash
certutil -hashfile zstangshi-v4.0.0.apk SHA256   # Windows
sha256sum zstangshi-v4.0.0.apk                   # macOS / Linux
```

---

## 鸿蒙专版（HarmonyOS）

| 项目 | 值 |
| --- | --- |
| 版本 | 4.0.0-harmony（versionCode 58） |
| 文件 | `zstangshi-harmony-4.0.0.apk`，1,702,514 字节（约 1.62 MiB） |
| 应用名 | **掌上唐师·鸿蒙版** |
| 系统要求 | HarmonyOS（鸿蒙 4.x 走 Android 兼容层，同样适用） |
| 源码 | [harmony-android/](harmony-android/) |

**⚠️ 可能遇到「签名冲突」**

两个版本**包名相同**（这是故意的，为了保留课表和登录态），但签名不同：

- 如果手机上装的是**官方签名版** → 系统会拒绝覆盖安装
- **解决办法**：先卸载原版 → 装鸿蒙专版 → 重新登录一次
  （**卸载会清掉本地课表和登录态**）

**确认装对了**

装好后打开 **「我的」→ 最下面「关于」**：

| | 标准版 | 鸿蒙专版 |
| --- | --- | --- |
| 桌面名字 | 掌上唐师 | **掌上唐师·鸿蒙版** |
| 版本 | 4.0.0 | **4.0.0-harmony** |
| 版本类型 | 无此行 | **鸿蒙专版 · HarmonyOS 适配** |

**还崩怎么办**

日志在 `Android/data/com.tstc.zstangshi/files/crash/harmony-*.log`，
里面有设备型号、系统版本和完整堆栈。发 Issue 时贴上来能快速定位。

---

## 这个 App 解决什么问题

教务系统的网页版在手机上必须「横屏 + 缩小 + 反复缩放」，查一次课表要滑三层菜单；
最后一节课记不住教室；明天有没有课要翻教学周历对着算是第几周；
门口的公交到哪了只能打开地图 App 再搜一遍站名。

这个 App 把这些直接摊平在首页上：一早解锁就能看到「今天第 3 门课在 3-518，还有 25 分钟下课」，
通勤路上点开就能看到「特1 路还有 3 站，7 分钟到」。

### 功能清单

| 模块 | 能力 |
| --- | --- |
| 首页 | 问候语、今日课程时间轴（正在上的课高亮 + 倒计时）、8 格功能宫格、学工系统入口 |
| 课表 | 四色课程块、单双周、冲突并排、点击编辑、翻周、左右滑动切换周次 |
| 成绩 | 学期筛选、成绩列表、绩点换算、明细分项（平时/期末） |
| 校历 | 月视图、法定节假日、调休补课、教学周次、事件提醒 |
| 实时公交 | 附近站点、线路查询、到站预测、首站发车时刻表、收藏、乘车码入口、线路详情 |
| 我的 | 账号管理、自动登录、课表设置、作息时间、调课停课、外观（浅色/深色/跟随系统）、桌面小组件、关于 |
| 教务集成 | 正方教务系统登录、课表同步、成绩查询、学分绩点、自主选课、推荐课表 |

---

## 技术逻辑

### 1. 从 WebView 套壳换成原生

v2 是「一套 H5 + 两个原生壳」，v3 是彻底的原生重写（`native/` 与 legacy 的 `web/`、`android/`
零共享）。换的原因不是「原生更好听」，而是三件事在 WebView 里做不到位：

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

> 鸿蒙专版把带进度的回调拆成了独立文件 `ui/AnimatedBackCallback.kt`，
> 因为 `OnBackAnimationCallback` 在鸿蒙上不存在，同文件会让类验证阶段直接失败。

### 5. 数据层

- 密码用教务页面下发的 RSA 公钥加密后再 POST；`JSESSIONID` 复用，超时自动重登。
- `Store` 存本地快照，按登录时刻判断新鲜度；姓名按学号隔离，换账号不会串。
- 成绩 / 绩点统一解析成「标题 + 副标题 + 数值 + 备注」的行模型，界面只写一套渲染。
- 刷新策略：首页刷新只重新登录、不动课表；成绩与绩点进页面才加载，下拉才拉取。
- 公交侧 `BusText` / `BusModels` 统一文案与选车口径，`ChelaileClient` 与 `MygolbsClient` 各管一个数据源，
  界面只认同一套 `Arrival` / `LineDetail`，所以首页和详情页不会各说各话。

### 6. 深色模式

manifest 声明了 `configChanges="uiMode"`（切深浅色不重建 Activity），所以系统下发的配置会覆盖
`attachBaseContext` 里设的夜间位。修复办法是在 `applyOverrideConfiguration` 这个唯一切口里重新注入，
否则 `values-night/colors.xml` 永远不会生效。

### 7. 不依赖 Gradle 的构建链

4.0.0 仍是纯 framework，但编译语言换成 Kotlin，构建脚本直接串起 SDK 里的工具：

```
aapt2 compile → aapt2 link（生成 R.java）→ javac(R.java) → kotlinc → d8 → zipalign → apksigner
```

- 需要 JDK 17、Android SDK（`build-tools;34.0.0` + `platforms;android-34`）、Kotlin 编译器 1.9.24。
  **不能用 Kotlin 2.x**：build-tools 34 自带的 R8 8.2.2 读不懂 2.0 的 `kotlin.Metadata`，d8 阶段会直接抛 stacktrace。
- `bash native/typecheck.sh` 只跑 kotlinc 类型检查，用于快速迭代；`bash native/build.sh` 出完整 APK。
- d8 不接受非静态内部类，所有自绘控件都用 `private class X(...) : View(c)` 这种静态嵌套写法。
- 版本号只在 `native/version.properties` 一处维护，脚本会同时写进 manifest 与包信息。

---

## 仓库结构

```
.
├── native/        ★ Android 主线（v4.0.0 起纯 Kotlin，纯 framework，build.sh 出 APK）
├── harmony-android/ ★ 鸿蒙专版（基于 native/，修复鸿蒙兼容问题，独立 build.sh）
│
├── web/           legacy · v2 的 H5 业务代码（v3 不再使用）
├── android/       legacy · v2 的 WebView 壳工程（现也作为本机 APK 输出目录）
├── docs/          架构设计、PRD、接口实测记录、验收口径
├── tools/         legacy · v2 的纯函数单测与规则守卫
├── reference/     小爱课程表学校适配脚本（设计参考，不参与构建）
└── server.js      legacy · v2 的本地预览服务器 + /jwxt/ 正向代理
```

v2 的完整代码另存于 `legacy-webview-v2.0.1` 分支与 `v2.0.1` tag，可随时取回。

---

## 本地构建

```bash
# Android 标准版：需要 JDK 17 + Android SDK（build-tools 34.0.0 + platform android-34）
#                 + Kotlin 编译器 1.9.24
bash native/typecheck.sh              # 只做类型检查，快
bash native/build.sh                  # → android/build/app-debug.apk
```

版本号只在 `native/version.properties` 一处维护（`versionCode=58` / `versionName=4.0.0`）。

```bash
# 鸿蒙专版
cd harmony-android
bash native/build.sh                  # → android/build/app-debug.apk
node scripts/check-java-api.js native/build/dex/classes.dex   # 扫 Java 9+ API
```

> ⚠️ **路径不能有中文** —— `aapt2` 会报 `failed to open directory`。
> 两个版本都要把工程放到纯 ASCII 路径下再编译。

legacy（v2）仍可运行：

```bash
node server.js                                 # 本地预览 http://127.0.0.1:5173
npm run verify                                 # v2 单测 + 规则守卫
bash android/build-manual.sh                   # v2 的 WebView 壳 APK
```

---

## 版本脉络

### harmony-v4.0.0 · 鸿蒙专版（2026-11-15 发布）

| 修补 | 解决的事 |
| --- | --- |
| 1 | 一点登录就闪退 —— `TodayWidget.refreshAll()` 在 UI 线程同步调 `AppWidgetManager`，鸿蒙没有 appwidget 服务，且无 try/catch |
| 2 | 同一个闪退的第二因 —— `BackGestures` 内部类继承 `OnBackAnimationCallback`（API 34），同文件导致类验证阶段就失败 |
| 3 | 第三个崩点 —— `MainActivity` 用 `WindowInsets.Type.systemBars()` / `window.statusBarColor` |
| 4 | 登录报 `URLEncoder` NoSuchMethodError —— `encode(String, Charset)` 是 Java 10 重载，鸿蒙只有 Java 8 API |
| 5 | 全面加固：小组件链路吞异常、insets 三级降级、登录流程逐段隔离、`BaseActivity` 生命周期兜底 |
| 6 | 新增全局兜底 `CrashGuard`，漏网异常写日志后吞掉，不再杀进程 |
| 7 | 新增 `scripts/check-java-api.js`，扫 dex 里的 Java 9+ API 引用，提前拦住「编译过、真机炸」 |

### v4.0.0 · 全量 Kotlin 重写 + 实时公交打磨（2026-11-15 发布）

| 修补 | 解决的事 |
| --- | --- |
| 1 | 乘车码三个入口都跳不动（Android 11+ 缺 `<queries>`）；主卡取消单独置顶与阴影、QR 图标重绘 |
| 2 | 线路信息（方向 / 运营时间 / 票价）回位；换向按钮加重；换站只重填、页面不再跳 |
| 3 | 微信乘车码实测被官方拒后下线并写明原因；详情页顶部瘦身 |
| 4 | 返回改为逐级退（详情 → 车辆列表 → 站点卡）；候车站圆点呼吸光晕；首站发车卡恢复 |
| 5 | 行程流改回 240dp 可滑内嵌窗口；时刻表按最新可用数据源取并标注来源；勾选框样式统一 |
| 6 | 下线临时演示线路，过滤残留 `demo-*` 收藏 |
| 7 | 「到站了 · 距本站 11.7 公里」：大字与小字锁同一辆车，不再拿后车距离顶替；不再误用到终点距离 |
| 8 | 乘车码改成追客同款右下角悬浮 FAB，展开／收起动画与滚动联动 |
| 9 | 首页也挂 FAB；线路行右端改成「蓝色车况 / 灰色下辆」；「下辆」与大字同源、只在真有第二辆车时显示 |
| 10 | 单车跟随「到终点预测」正名为「到候车站预测」；车辆位置绿点加同色呼吸光晕 |

v4.0.0 之前是 v3.1.1（versionCode 56）。v3 系列从 3.0.8 一路打磨到 3.0.33，
几乎每个小版本都对应一个真机测出来的具体问题：

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
| 3.0.32 | 搜索能搜站点、结果下滑翻页；进首页必重新定位（换地方后不再停在旧坐标）；定位感知提速；首屏只等附近站点 |
| 3.0.33 | 修站序：上游「虚拟站组」把一串真实站点塞进一条 `order=999` 记录，组后 order 还会回退，导致站序出现「13-1…」；站序与预测 `targetOrder` 统一取站表位置 |

逐条细节见 [CHANGELOG.md](CHANGELOG.md)。

---

## 第三方来源与致谢

**本项目没有 fork、复制或 vendoring 任何 GitHub 仓库的代码**，没有 git submodule，运行时零第三方依赖。
课表数据与交互**同时参考了 WakeUp 课程表与小米小爱课程表**——这是两个不同产品：前者是独立第三方
课程表 App，后者是小米的课程表服务；底部导航与本地课表的产品设计另参考了烫梨（tangli）。

| 来源 | 关系 | 说明 |
| --- | --- | --- |
| **WakeUp 课程表** · [官网](http://www.wakeup.fun/) | 数据与交互参考 | 独立的第三方课程表 App，**不是小米产品**。课程卡配色思路、课程详情层、复制课表文案的字段顺序参考了它的交互手感。 |
| **烫梨（tangli）** · [GitHub](https://github.com/augustlies/tangli)（GPL-3.0） | 交互与视觉参考 | 本地课表、桌面小组件、免登录查看这几项能力的**产品思路**来自它。底部悬浮胶囊导航（`native/.../ui/CapsuleDock.kt`）照它 `CapsuleDock` 的交互与视觉语言用 Kotlin 重写。**没有复制其源码**；若后续引入其任何源码，需按其 GPL-3.0 处理许可。 |
| **小爱课程表**（小米）· [解析器格式文档](https://open-schedule-prod.ai.xiaomi.com/docs/#/help/?id=parser) | 解析器规范参考 | `reference/wakeup-parser-tstc.js` 是**本项目作者本人**为唐山师范学院写给小爱课程表的学校适配脚本，遵循小爱 `scheduleHtmlParser(html)` 规范。**文件名沿用早期 `wakeup-` 前缀，容易误解：它不是 WakeUp 课程表的解析器。** 该文件只作设计参考，不参与构建。 |
| **正方教务系统** `jwxt.tstc.edu.cn` | 页面样本与接口分析素材 | `docs/diagnostics/` 下的页面 JS 是从教务站点抓取的原文样本，仅用于逆向确认端点与字段口径，**不参与构建或运行**，版权归原系统所有。 |
| **唐山师范学院公开资料** | 数据／素材来源 | 校历来自教务处 [校历查询](https://jwc.tstc.edu.cn/xlcx.htm)，作息时间来自 [教务处公告](https://jwc.tstc.edu.cn/info/1247/2699.htm)，校徽与校园照为学校公开素材。 |
| **学工系统** `sws.tstc.edu.cn` | 外链入口 | 仅在应用内 WebView 打开原页面，不解析、不复制其代码。 |
| **车来了 / 掌上公交** | 实时公交数据来源 | 「实时公交」的数据来自车来了与掌上公交的公开查询接口。站点、线路、到站时间版权归原数据提供方，App 内只实时查看，不做本地留存与外传。 |
| **追客（Trackr）** · [GitHub](https://github.com/5151561/Trackr)（**MIT**） | 4.0.0 交互与文案口径参考 | 线路详情主卡三态、到站文案（`ArrivalText` / `ArrivalRules`）、乘车码悬浮按钮、站线光晕等的产品口径与交互参照它，在纯 framework 的 View 体系里**自行用 Kotlin 重写**（`native/.../bus/BusText.kt`、`BusModels.kt`、`ChelaileClient.kt` 等）。**没有复制其源码、没有引入其依赖**（对方是 Compose Multiplatform + Koin + Room）；协议事实本身不受著作权保护。 |
| **华为 HarmonyOS 开发者工具链** | 鸿蒙专版构建 | `ohsdkmanager`（SDK 管理器）、API 9 / 3.2.7.5 SDK、`app_packing_tool`、`hap-sign-tool`。鸿蒙专版的兼容性分析参考了官方 API 文档。 |
| **工具链** | 构建／分发工具 | Android SDK build-tools（`aapt2` / `d8` / `zipalign` / `apksigner`）、Kotlin 编译器、GitHub Actions。 |

「掌上唐师」及校徽为说明性使用，本项目与学校、正方软件、WakeUp 课程表、小米（小爱课程表）均无官方关联。

---

## 隐私与安全提示

- **本仓库是公开仓库**，`docs/` 与 `reference/` 中含演示用的姓名、学号、班级、成绩统计与联系方式样本。
  若要大范围对外分发，请先脱敏或把相关资料移出公开仓库。
- Release 中的 APK 使用本地调试密钥签名（与 v3.x 一致，故可直接覆盖升级），**未上架应用商店**。
  请只从本仓库的 Releases 下载，不要从第三方渠道转传的包安装。
- 登录密码以 RSA 加密后提交给教务，本地可选择保存（存 SharedPreferences），
  不上传任何第三方服务器；所有请求只到学校域名。
- **实时公交** 是唯一会对外发请求的功能：只把定位坐标发给公交数据服务商用于查附近站点，
  不携带学号、姓名、账号等任何身份信息；可拒绝定位授权，改用手动搜索线路。
- 演示数据与成绩统计仅用于界面和算法验收，**不作为学业认定依据**；公交到站时间来自数据服务商预测，
  仅供参考，以实际车辆为准。
- 仓库尚未添加开源许可证（LICENSE），默认保留所有权利；如需开源授权请单独声明。

---

## 相关文档

- [CHANGELOG.md](CHANGELOG.md) — 逐版本、逐条修补的完整更新日志
- [native/README.md](native/README.md) — Android 原生版：构建、目录、自绘控件实现与踩坑
- [harmony-android/README.md](harmony-android/README.md) — 鸿蒙专版：兼容性问题分析、加固清单、构建与自检
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 架构、数据结构与动效系统（v2 时期撰写，v3/v4 结构见各端 README）
- [docs/remote-api-map.md](docs/remote-api-map.md) — 教务接口实测记录（端点、字段、周次、协议可达性）
- [Releases](https://github.com/Chanmingfei/zstangshi/releases) — 各版本安装包与更新说明

---

## 关于

**开发者**：陈铭霏
**荣誉出品**：百度唐山师范学院贴吧吧务组

个人开发项目，**非学校官方发布**。
内置校历数据来源：唐山师范学院教务处（jwc.tstc.edu.cn）。
实时公交参考 [追客 Trackr](https://github.com/5151561/Trackr)（MIT License）。