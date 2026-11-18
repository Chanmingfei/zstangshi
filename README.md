# 掌上唐师

面向唐山师范学院在校生的校园服务 App（个人开发，**非学校官方发布**）。把教务系统与学工系统里
最常用的几件事装进手机：课表、成绩、绩点、推荐课表、选课、校历、奖助贷与请假，
以及校门口的**实时公交**（到站预测、候车站、首站发车时刻表、乘车码）。

**当前版本**：标准版 **v4.0.5**（versionCode 63）· 鸿蒙专版 **v4.1.0-harmony**（versionCode 64）
零第三方运行时依赖 · 不引 androidx、不引 Gradle · 纯 Android framework，
`bash native/build.sh` 一条命令直出可安装 APK · 采用 **GPL-3.0** 协议开源

> **v4.0.5 / 鸿蒙 4.1.0 新增「课程实况窗」**：把正在上 / 即将上的课推到
> 鸿蒙实况窗、ColorOS 流体云（超级岛）、小米焦点通知、Android 16 实况通知上 ——
> 收起态显示「课程名 · 地点」，上课后走秒倒计时，地点、节次、课间一目了然。
> 实现原理与「套用到自己项目」的六步清单见
> [技术原理 9 · 课程实况窗](#9-课程实况窗鸿蒙实况窗--android-实况通知-)。
>
> 4.0.0 起全量 Kotlin 重写（54 个文件、约 2.29 万行），并把**实时公交**按
> [追客 Trackr](https://github.com/5151561/Trackr)（MIT）的口径重新打磨。
> 完整修改记录见 [CHANGELOG.md](CHANGELOG.md)。

---

## 📥 下载哪个版本？

**先确认你的手机系统。**

| 你的设备 | 下载 | 版本标识 |
| --- | --- | --- |
| **鸿蒙 / HarmonyOS**<br>华为、荣耀新机型 | **[⬇ 鸿蒙专版（实况窗版）](https://github.com/Chanmingfei/zstangshi/releases/tag/harmony-v4.1.0)**<br>`zstangshi-harmony-4.1.0.apk` | 桌面名 **掌上唐师·鸿蒙版**<br>关于页有「版本类型：鸿蒙专版」 |
| **普通安卓**<br>小米 / OPPO / vivo / 三星 / 一加 等 | **[⬇ 标准版](https://github.com/Chanmingfei/zstangshi/releases/tag/v4.0.5)**<br>`zstangshi-v4.0.5.apk` | 桌面名 **掌上唐师**<br>关于页无「版本类型」行 |

> 始终拿最新版：<https://github.com/Chanmingfei/zstangshi/releases/latest>

### 不确定自己是不是鸿蒙？

打开手机 **设置 → 关于手机**：

- 看到 **「HarmonyOS 版本」** 或 **「鸿蒙版本」** → 下载**鸿蒙专版**
- 看到 **「Android 版本」** 或 **「MIUI / ColorOS / OriginOS / One UI」** → 下载**标准版**

### 两个版本有什么区别

**功能对齐，实现各自独立**。两版不再是「同一份代码打两个包」——
鸿蒙专版从 4.1.0 起按鸿蒙的能力边界改造，允许与标准版分叉演进（避免互相牵制）。

| | 标准版 | 鸿蒙专版 |
| --- | --- | --- |
| 功能 | 全部 | 全部 |
| 包名 | `com.tstc.zstangshi` | `com.tstc.zstangshi`（**故意相同**，为了保留课表与登录态） |
| 版本 | 4.0.5（63） | 4.1.0-harmony（64） |
| 桌面名 | 掌上唐师 | **掌上唐师·鸿蒙版** |
| 课程实况窗 | ✅ Android 实况通知协议 | ✅ **实况窗协议 + 常驻倒计时双档降级** |
| 鸿蒙登录兼容 | ❌ 会闪退 | ✅ 已修复 |
| 源码 | `native/` | `harmony-android/native/`（独立演进） |
| APK 体积 | ≈ 3.55 MiB | ≈ 1.66 MiB |

### 为什么鸿蒙需要单独一版

安卓版装在鸿蒙上踩了两个坑，都和「鸿蒙兼容层缺 Android 专有 API」有关：

1. **一点登录就闪退** —— 三处 Android 专有 API（`AppWidgetManager`、
   `OnBackAnimationCallback`、`WindowInsets.Type`）在鸿蒙上不存在，抛出
   `NoClassDefFoundError` / `NoSuchMethodError`。这是 **Error 不是 Exception**，
   `catch (Exception)` 抓不住，异常穿透到 UI 线程顶层就把进程杀了
2. **登录报 `URLEncoder` 错误** —— 代码用了 Java 10 才有的 `encode(String, Charset)`
   重载，鸿蒙只有 Java 8 那批 API

两处都已修复，详见 [harmony-android/README.md](harmony-android/README.md)。

从 **4.1.0** 起，鸿蒙专版不再与标准版共用同一份实现：
鸿蒙的兼容层决定了它必须用「能力探测 + 降级」的写法，而标准版可以直接假设 Android 16 的接口存在。
强行让两版共用同一份代码，只会让两边都写满对方不需要的判断 —— 所以两版**允许分叉**，
只在业务逻辑（课表、教务、公交）上保持同步。

---

## 标准版（安卓）

| 项目 | 值 |
| --- | --- |
| 版本 | 4.0.5（versionCode 63） |
| 文件 | `zstangshi-v4.0.5.apk`，3,718,270 字节（约 3.55 MiB） |
| 系统要求 | Android 8.0（API 26）及以上，目标 Android 14（API 34）<br>课程实况窗需 Android 16（API 36）及以上 |
| 权限 | `INTERNET`、`ACCESS_NETWORK_STATE`；`ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`（**只在实时公交首页取一次坐标**）；`POST_NOTIFICATIONS`、`POST_PROMOTED_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`（课程实况窗用） |
| 签名 | 本地调试密钥 `CN=ZSTS`，与 v3.x 各版本一致（证书 SHA-256 `5999bc84cd45f1e55b6afc711cfa0c53825b1bf40fa3530606d5e990cada11de`），**可直接覆盖安装** |
| SHA-256 | `c08038d44b896ee63b55051a1022a68b65e28cb93f94e27f21b57756dcd69665` |

APK 比 v3.1.1（1.10 MiB）大，主要是 Kotlin 标准库与编译产物，没有做 R8 裁剪。

**安装步骤**

1. 从 Releases 下载 APK，在手机上点开。
2. 提示「未知来源」时，允许当前浏览器／文件管理器安装（调试签名、未上架商店，属正常现象）。
3. 已装 v3.x 旧版可直接覆盖，登录状态与本地课表保留；若提示「应用未安装」，卸载旧版后重装。

校验下载完整性（可选）：

```bash
certutil -hashfile zstangshi-v4.0.5.apk SHA256   # Windows
sha256sum zstangshi-v4.0.5.apk                   # macOS / Linux
```

---

## 鸿蒙专版（HarmonyOS）

| 项目 | 值 |
| --- | --- |
| 版本 | 4.1.0-harmony（versionCode 64） |
| 文件 | `zstangshi-harmony-4.1.0.apk`，1,744,002 字节（约 1.66 MiB） |
| 应用名 | **掌上唐师·鸿蒙版** |
| 系统要求 | HarmonyOS（鸿蒙 4.x 走 Android 兼容层，同样适用） |
| 源码 | [harmony-android/](harmony-android/)（独立于标准版演进） |
| SHA-256 | `14bda4d3b8e7bd47d18b88332bbb3cbbc9e2257231764fa91b6888c5cf7db344` |

**这一版专门为「实况窗」做了适配**

| 你的鸿蒙版本 | 实况窗形态 | 说明 |
| --- | --- | --- |
| HarmonyOS 5.x（兼容层 API 36+） | **实况窗 · 协议直连** | 与原生 Android 16 同一条通道 |
| HarmonyOS 4.x / 3.x（API 29~33） | **常驻倒计时 · 等效实况窗** | 系统没有实况协议，用「常驻通知 + 系统计时器」等效呈现 |

判定逻辑写在 `notif/HarmonyLive.kt`，「我的 → 课程实况窗 → 显示形态」会直接告诉你当前是哪一档。

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
| 版本 | 4.0.5 | **4.1.0-harmony** |
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
| 我的 | 账号管理、自动登录、课表设置、作息时间、调课停课、外观（浅色/深色/跟随系统）、桌面小组件、**课程实况窗**、关于 |
| 课程实况窗 | 课前 N 分钟把课程推到实况窗 / 超级岛 / 焦点通知：收起态「课程名 · 地点」，上课后走秒倒计时，课间单独计时，划掉本节不再提醒 |
| 教务集成 | 正方教务系统登录、课表同步、成绩查询、学分绩点、自主选课、推荐课表 |

---

## 技术栈

### 一句话概括

**纯 Android framework API + Kotlin，不用 Gradle、不用 androidx、零第三方运行时依赖；
鸿蒙专版从 4.1.0 起按鸿蒙能力边界独立演进，不再与标准版强行共用同一份实现。**
鸿蒙专版是同一份代码的兼容性分支，额外加了一层崩溃兜底。

### 技术选型一览

| 层面 | 选择了什么 | 为什么 |
| --- | --- | --- |
| **语言** | Kotlin 1.9.24 | 空安全、扩展函数、`when` 表达式；比 Java 少约 30% 样板代码 |
| **UI 框架** | 纯 `android.view` + `Canvas` 自绘 | 不引 androidx / Material，完全控制每个像素；APK 小、启动快 |
| **异步** | `Thread` + `runOnUiThread` | 不引协程（coroutines 只用于 bus 模块内部）；逻辑简单可预测 |
| **网络** | `HttpURLConnection` | `OkHttp` 是第三方依赖；教务只需要「带 Cookie 的 GET/POST + 跟随 302」 |
| **存储** | `SharedPreferences` | 键值对够用；不引 Room/SQLite |
| **加密** | `javax.crypto`（framework 自带） | RSA 加密密码、AES 解密公交响应、MD5 签名 |
| **构建** | `aapt2` + `kotlinc` + `d8` + `apksigner` 手动串联 | 见下方「7. 不依赖 Gradle 的构建链」 |
| **鸿蒙适配** | 兼容性分支 + 全局兜底 | 见下方「8. 鸿蒙兼容层适配」 |

**运行时依赖计数：0。** 除 Kotlin 标准库外，没有任何第三方库。

### 代码规模

```
54 个 Kotlin 文件 · 22,909 行

jw/        3 文件  1,724 行   正方教务接口（登录 / 课表 / 成绩 / 绩点 / 选课）
bus/       7 文件  4,517 行   实时公交（车来了 + 掌上公交两套协议）
data/     14 文件  2,845 行   课表模型 / 校历 / 作息 / 周次 / 存储
pages/     6 文件  3,253 行   四个主页面 + 各类编辑器
ui/       13 文件  2,754 行   自绘控件（胶囊 Dock / 课表网格 / 页面带 / 弹层…）
widget/    3 文件    337 行   桌面小组件
根目录      8 文件  7,479 行   Activity 壳与生命周期
```

### 为什么不用 androidx / Gradle

这不是为了「秀技术」，是**构建环境的硬约束**：开发机访问不了 `maven.google.com`，
Gradle 拉不到 AGP 和 androidx 依赖。既然拉不到，就干脆全部用 framework API 重写。

意外的好处：

- **APK 只有 3.48 MiB**（用 androidx 的同类 App 通常 8–15 MiB）
- **构建 20 秒出包**，没有 Gradle daemon 冷启动
- **没有依赖冲突**，不用关心「某某库升级后 API 变了」

代价是所有控件都得自己画 —— 这正是 `ui/` 目录 2,754 行的由来。

---

## 技术原理

### 0. 整体架构

```
┌──────────────────────────────────────────────────────┐
│  Activity 层：Splash / Login / Main / Web / Feature…  │
│  （薄壳：只做生命周期与页面组装）                       │
└────────────────────┬─────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────┐
│  pages/  四个主页面（HomePage / TimetablePage /        │
│          GradesPage / MinePage）                      │
│          纯 View 树，由 MainActivity 的 PageDeck 承载   │
└────────────────────┬─────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────┐
│  ui/  自绘控件层（无业务逻辑）                          │
│      PageDeck / PageSlide / CapsuleDock /            │
│      TimetableGridView / Sheet / PullRefreshLayout…  │
└────────────────────┬─────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────┐
│  业务层：jw/（教务） bus/（公交）                        │
│  纯函数 + 无状态客户端，可直接单元测试                   │
└────────────────────┬─────────────────────────────────┘
                     │
┌────────────────────▼─────────────────────────────────┐
│  data/  数据模型 + 本地存储（SharedPreferences）        │
│  Timetable / Semester / WeekRange / WeekMath /        │
│  ScheduleSettings / AcademicCalendar / Store          │
└──────────────────────────────────────────────────────┘
```

**关键设计**：业务层（`jw/`、`bus/`）不依赖任何 View，UI 层不包含任何网络请求。
这样换 UI、或者把业务逻辑移植到其他平台，都不需要动业务代码。

---

### 1. 从 WebView 套壳换成原生

v2 是「一套 H5 + 两个原生壳」，v3 是彻底的原生重写。换的原因不是「原生更好听」，
而是三件事在 WebView 里做不到位：

| 需求 | WebView 里的结果 | 原生做法 |
| --- | --- | --- |
| 课表横滑翻周 | JS 模拟手势，跟手迟滞 | `WeekSwipe` + `requestDisallowInterceptTouchEvent` 锁父滚动 |
| 底栏切页 | 页面互相淡入淡出 | `PageDeck` 整条页面带平移，中间页真实掠过 |
| 二级页返回 | 无法参与系统动画 | `OnBackAnimationCallback` 拿到 progress，逐帧跟手 |

### 2. 切页：连续平移而不是渐变

**原理**：四个主页面**同时**排在一条横向带子上，切页时平移整条带子。
所以从首页点「我的」，课表、成绩会依次从眼前掠过，而不是两页对着擦。

**时长公式**：`290ms + 45ms × max(0, 剩余格数 − 1)`，夹在 `[160, 400]`
—— 首页→课表 290ms、首页→成绩 335ms、首页→我的 380ms，
三者差不到 0.1s，观感上就是同一套节奏；又不会因为完全固定时长，
让跨三格的那次快到看不清中间两页。

```kotlin
// MainActivity.kt 实际实现
val remain = Math.abs(i - content.position())
var dur = (PAGE_SLIDE_MS + Math.round(PAGE_SLIDE_STEP_MS * Math.max(0f, remain - 1f))).toLong()
dur = Math.min(PAGE_SLIDE_MAX_MS.toLong(), Math.max(PAGE_SLIDE_MIN_MS.toLong(), dur))
```

> 用**浮点**剩余距离是为了接住「返回手势松手时已经拖了一半」的情况。

**难点与解法**：容器只有一屏宽，直接平移它会被自身 `bounds` 裁掉、触摸也失效。
所以每个子页各自 `layout` 在 `[0, 0, w, h]`，用各自的 `translationX` 表达位置。

```kotlin
// ui/PageDeck.kt 核心思路
child.layout(0, 0, width, height)              // 所有子页都占满全屏
child.translationX = (i - position) * width    // 位置靠 translationX 表达
```

### 3. 二级页：圆角卡片转场

**问题**：系统只在**返回**方向画圆角卡片（AOSP `CrossActivityBackAnimation` 取设备圆角半径，
把正在关闭的窗口裁圆角再缩到 90%），**进入**方向走的是窗口动画，而窗口动画只有
alpha / translate / scale，没有圆角属性，也没有公开 API 能给应用窗口设圆角。
于是同一个页面进来直角、出去圆角。

**解法**：自己画整页。

```
windowIsTranslucent = true          // 窗口透明
背景填透明色
在 android.R.id.content 里插一层自建 FrameLayout 当「卡片」
圆角：ViewOutlineProvider + setClipToOutline
投影：setElevation
位移与圆角都随「滑出比例 p」变化，落位归 0
```

静止状态与普通页面**逐像素一致**，只在运动过程中出现圆角与投影。

> **踩坑记录**：圆角最初裁在 `DecorView` 上，`setClipToOutline` 对窗口根节点不生效，
> 结果进出都是「没有边界、没有投影的矩形」。必须换成普通 View 才裁得出来。

### 4. 预测性返回（跟手动画）

**原理**：manifest 开 `enableOnBackInvokedCallback`，系统会把返回手势的**实时进度**
（0.0 → 1.0）回调给应用，应用可以逐帧跟着手指做动画。

| 系统版本 | 用哪个 API | 效果 |
| --- | --- | --- |
| API 34+ | `OnBackAnimationCallback.onBackProgressed` | 全跟手：动画随手指移动 |
| API 33 | `OnBackInvokedDispatcher` | 只有「已触发」一个时机，无进度 |
| API 32 及以下 | `onBackPressed` + 自绘滑动 | 传统做法 |

一级页把进度喂给 `PageDeck.jumpTo(position × (1 − p))`，整条带子跟着手指回滑，
松手从当前位置收尾；二级页喂给 `PageSlide`。

```kotlin
// 核心：把 0→1 的进度映射成页面位置
fun onProgress(p: Float) {
    deck.jumpTo(currentIndex * (1 - p))
}
```

### 5. 数据层与本地存储

**教务登录协议**（正方教务系统，五步）：

```
1. GET  /xtgl/login_slogin.html        → 抓隐藏域 csrftoken
2. GET  /xtgl/login_getPublicKey.html  → {"modulus": "...", "exponent": "..."}  ← base64 的 BigInteger
3. RSA/ECB/PKCS1Padding 加密密码原文
4. POST /xtgl/login_slogin.html        → yhm / mm / hidMm / csrftoken / language / mmsfjm
5. GET  /xtgl/index_cxMenuList.html    → 必须含 gnmkdm，否则视为没登上
```

> **注意**：`mm` 和 `hidMm` 两个字段都要传；公钥来自**独立接口**而不是登录页 HTML。

**协议自适应**：先试 https，失败换 http 重试（校外 443 端口常被拦）。

**会话复用**：`JSESSIONID` 缓存 10 分钟，30 秒内不重复探测。

**课表解析**（`data/JwglxtFetcher.kt`）：

```
接口：POST /jwglxt/xsxk/zzxkyzb_cxZzxkYzbDisplay.html  或  /xtgl/index_initMenu.html
位置：kbList 数组，一条记录 = 一个时段（不是一门课）
聚合：同一门课的多条记录共享 jxb_id（教学班 ID）→ 用它聚合成 Course
字段：kcmc(课名) xm(教师) xqj(星期) jcor(节次,如"3-4") zcd(周次,如"5-12周")
      cdmc(教室) xqmc(校区) kcbj(教学班) xf(学分)
学期：xsxx 里的大写 XNM(2026) / XQM(3或12) / XNMC / XQMMC
```

**周次解析**（`data/WeekRange.kt`）支持这些写法：

```
5-12周              连续区间
1-5周,7-12周        多区间
1-16周(单)          单周
1-16周(双)          双周
1,3,5,7周           离散
1-5，7-9周          全角逗号
1-5、7-9周          顿号
```

**关键兜底**：解析失败时 `isActive()` 返回 `true`，**宁可多显示也不让课凭空消失**。

**本地存储**（`data/Store.kt`）：`SharedPreferences` 按学期分 key
（`tt_<semesterId>` / `startDate_<semesterId>`），换学期不串数据。

### 6. 深色模式

manifest 声明了 `configChanges="uiMode"`（切深浅色不重建 Activity），
所以系统下发的配置会覆盖 `attachBaseContext` 里设的夜间位。

**修复办法**：在 `applyOverrideConfiguration` 这个**唯一切口**里重新注入夜间位，
否则 `values-night/colors.xml` 永远不会生效。

```kotlin
override fun applyOverrideConfiguration(config: Configuration?) {
    if (config != null) {
        when (Store(this).themeMode()) {
            THEME_DARK  -> config.uiMode = (config.uiMode and MASK.inv()) or YES
            THEME_LIGHT -> config.uiMode = (config.uiMode and MASK.inv()) or NO
            // THEME_SYSTEM：保留系统下发的夜间位
        }
    }
    super.applyOverrideConfiguration(config)
}
```

### 7. 不依赖 Gradle 的构建链

**从未见过那样搭建的构建脚本？** 这里就是。因为用不了 Gradle，直接把 SDK 里的工具串起来：

```
aapt2 compile --dir res -o res.flata
aapt2 link   -I android.jar --manifest ... --java gen --min-sdk-version 26 ...
javac        编译 R.java
kotlinc      -no-stdlib -jvm-target 1.8 -classpath "android.jar;kotlin-stdlib.jar;classes"
d8           --lib android.jar --min-api 26 --output dex classes.jar kotlin-stdlib.jar
zipalign     -f -p 4
apksigner    sign --ks debug.keystore
```

**环境要求**：JDK 17、Android SDK（`build-tools;34.0.0` + `platforms;android-34`）、
Kotlin 编译器 1.9.24。

**不能用的版本**：Kotlin 2.x。build-tools 34 自带的 R8 8.2.2 读不懂 2.0 的
`kotlin.Metadata`，d8 阶段会直接抛 stacktrace。

**其他要点**：

- `bash native/typecheck.sh` 只跑 kotlinc 类型检查，用于快速迭代
- d8 不接受非静态内部类，所有自绘控件都用 `private class X(...) : View(c)` 这种静态嵌套写法
- 版本号只在 `native/version.properties` 一处维护

> ⚠️ **路径不能有中文** —— `aapt2` 会报 `failed to open directory`。
> 把工程放到纯 ASCII 路径下再编译。

### 8. 鸿蒙兼容层适配 ⭐

**这是鸿蒙专版的核心，也是这份仓库里最值得复用的部分。**

#### 问题本质

HarmonyOS 的 Android 兼容层**只实现了 Android framework 的一部分**。
缺失的 API 调用会抛 `NoClassDefFoundError` / `NoSuchMethodError` ——
**这些是 `Error` 不是 `Exception`**，`catch (e: Exception)` 抓不住，
异常穿透到 UI 线程顶层 → 进程被杀 → 用户看到「闪退」。

更隐蔽的是**类验证阶段**的问题：即使代码没执行，只要 ART 在验证方法体时
解析到了一个不存在的父类，就会在**进入 try 块之前**抛 `NoClassDefFoundError`。

#### 三类问题与对策

| 类型 | 典型场景 | 对策 |
| --- | --- | --- |
| **① 系统服务缺失** | `AppWidgetManager`（鸿蒙无 appwidget 服务） | 整条链路 `try/catch (Throwable)`，失败静默 |
| **② 类验证失败** | 内部类继承 API 34 的 `OnBackAnimationCallback` | **把危险类拆到独立文件**，只在运行时才加载 |
| **③ API 版本差异** | `URLEncoder.encode(String, Charset)` 是 Java 10 新增 | 降到 Java 8 就有的重载 |

#### 关键技术手法

**手法一：用 `Class.forName` 真探测，不要只信 `SDK_INT`**

鸿蒙可能上报 `SDK_INT = 34`，但类其实不存在。

```kotlin
private fun animatedAvailable(): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    return try {
        Class.forName("android.window.OnBackAnimationCallback")
        true
    } catch (t: Throwable) { false }   // 真加载失败就当不支持
}
```

**手法二：把「危险类」拆成独立文件**

```kotlin
// ❌ 错误：内部类和宿主同文件
object BackGestures {
    private class Animated : OnBackAnimationCallback { ... }   // 验证 BackGestures 时就会解析它
}

// ✅ 正确：独立文件，只有 new 的那一刻才加载
// ui/AnimatedBackCallback.kt
internal class AnimatedBackCallback(h: Handler) : OnBackAnimationCallback { ... }
```

**手法三：全面降级，任何一步失败都往下走**

```kotlin
private fun applyInsets(root: View, dockBox: View) {
    if (applyModernInsets(root, dockBox)) return   // ① API 30+ 的 WindowInsets
    try { root.setPadding(0, statusBarHeight(), 0, 0) }  // ② 资源查询兜底
    catch (t: Throwable) { root.setPadding(0, 0, 0, 0) } // ③ 0 内边距
    setStatusBarTransparent()                      // 失败也不影响功能
}
```

**手法四：穷举不完的，就上全局兜底**

前三种手法只能修「已知问题」。兼容层还缺什么没法穷举，所以加一层
`Thread.setDefaultUncaughtExceptionHandler`：

```kotlin
// harmony/CrashGuard.kt
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
    try {
        writeLog(app, throwable)   // 写 filesDir/crash/harmony-*.log，保留最近 10 个
        // 吞掉异常，不让进程死
    } catch (t: Throwable) {
        previous?.uncaughtException(thread, throwable)   // 连日志都写不出来才交回系统
    }
}
```

> 「吞异常」不是最优雅的做法，可能留下不一致的界面状态。
> 但对这个场景是对的：用户要的是「别闪退」，而不是「优雅地闪退」。

#### 给其他移植项目的检查清单

| 检查项 | 怎么查 |
| --- | --- |
| 系统服务 | `getSystemService(...)` 是否都判空 + try/catch |
| 新 API 类 | 是否用内部类继承了高版本 API 的类 → 拆到独立文件 |
| Java 版本 | 是否用了 Java 9+ 的方法重载（见下方工具） |
| 窗口/Insets | `WindowInsets.Type` / `statusBarColor` 是否包了 |
| 全局兜底 | 是否装了 `UncaughtExceptionHandler` |

**自动化工具**：`harmony-android/scripts/check-java-api.js` 直接解析 dex 的方法引用表，
扫出所有 Java 9+ 的 API 调用：

```bash
node scripts/check-java-api.js native/build/dex/classes.dex
```

输出示例：

```
dex 方法引用总数：13174
✓ 没有发现 Java 9+ 的 API 引用

URLEncoder / URLDecoder 引用：
  Ljava/net/URLEncoder;->encode(BB)Ljava/lang/String;
```

这类问题**编译期完全看不出来**（JDK 17 编译合法），只在真机上炸 —— 构建后跑一次能提前拦住。

### 9. 课程实况窗（鸿蒙实况窗 / Android 实况通知）⭐

**这是 v4.0.5 / 鸿蒙版 4.1.0 新增的功能，也是这份仓库里第二块值得整体复用的东西。**

#### 9.1 概念对照：叫法五花八门，协议只有一个

| 厂商 / 系统 | 用户看到的名字 | 开发者拿到的接口 |
| --- | --- | --- |
| Android 16 原生 | 实况通知（Live Updates） | `Notification.Builder#setShortCriticalText` + `requestPromotedOngoing` |
| 鸿蒙 HarmonyOS 5 | 实况窗 | 同名协议的 Android 兼容层实现 |
| OPPO ColorOS 16 | 流体云 / 超级岛 | 同上，系统侧映射 |
| 小米 HyperOS 3 | 焦点通知 | 同上 |
| 华为 HarmonyOS 4.x | 实况窗（**ArkUI 原生能力**） | ❌ APK 调不到 → 走降级形态（见 9.6） |

所以**不要为每家写一套**，只写 Android 的实况通知协议，系统自己决定长什么样。

#### 9.2 三件套：一条通知怎么变成实况窗

```kotlin
val builder = Notification.Builder(ctx, CHANNEL_ID)
    .setContentTitle(courseName)          // 实况窗第一行：课程名
    .setContentText(mainLine)             // 主行：距上课 25 分钟 · 东校区 3-518
    .setSubText(phaseLabel)               // 副文本：即将上课 / 上课中 / 课间
    .setSmallIcon(R.drawable.ic_emblem)   // 岛上的图标（单色 alpha 遮罩）
    .setLargeIcon(colorEmblem)            // 通知栏大图（彩色）
    .setOngoing(true)                     // 不可划掉
    .setOnlyAlertOnce(true)               // 反复重发不响铃
    .setShowWhen(true).setWhen(deadline)  // 终点时刻
    .setUsesChronometer(true)             // 系统计时器（倒计时）
    .setChronometerCountDown(true)
    .setTimeoutAfter(deadline - now)      // 到点自动消失

// ① 请求提升为实况通知（Android 16 QPR1 起的正式开关）
builder.addExtras(Bundle().apply {
    putBoolean("android.requestPromotedOngoing", true)
})

// ② 胶囊右侧的关键短文本（API 36 才有）
builder.setShortCriticalText("东校区 3-518")   // 反射调用

// ③ 兜底：Android 16 最初版本要求 colorized 才肯提升
builder.setColorized(true)
```

**编译 SDK 34 也能用**：本工程构建链锁在 android-34（不升级 Gradle/SDK），
API 36 的接口全部走反射，失败就静默降级成普通通知 —— 见 `notif/Api36.kt`：

```kotlin
fun setShortCriticalText(builder: Notification.Builder, text: CharSequence): Boolean {
    if (Build.VERSION.SDK_INT < 36) return false
    return try {
        Notification.Builder::class.java
            .getMethod("setShortCriticalText", CharSequence::class.java)
            .invoke(builder, text)
        true
    } catch (t: Throwable) { false }   // 低版本 / 精简 ROM 直接跳过
}
```

#### 9.3 字段落位表（踩坑最集中的地方）

同一个字段在不同 ROM 上的渲染位置不同，**这份表是实测出来的**：

| 想显示的内容 | 放哪个字段 | 实测表现 |
| --- | --- | --- |
| 课程名 | `setContentTitle` | 实况窗第一行（所有 ROM 都认） |
| **剩余时间 + 地点** | `setContentText`（同时是 `bigText` 首行） | 实况窗主行 |
| 阶段标签 | `setSubText` | 副文本行 |
| 节次 / 上下课时间 | `bigText` 第二行 | 详情行 |
| 胶囊短文本 | `setShortCriticalText` | 收起态右侧 |

> ⚠️ **地点只写一次**。我们踩过：地点同时写进 `contentText` 和 `subText`，
> 在「文本形态」下展开后出现两遍。现在固定只有主行带地点，`subText` 只放阶段标签。

#### 9.4 三个绕不过去的系统限制

**① `setUsesChronometer` 是全局开关，同时管两处**

它既管「展开态的大号倒计时」，也管「收起态胶囊显示计时器还是文字」。没有 API 能只关一处。

```kotlin
// 课前：关掉计时器 → 胶囊才能显示「课程名 · 地点」，代价是展开态大数字也没了
builder.setShowWhen(false); builder.setUsesChronometer(false)
builder.setShortCriticalText("$courseName · $place")

// 上课中 / 课间：开计时器 → 秒级走秒
builder.setShowWhen(true); builder.setWhen(deadline); builder.setUsesChronometer(true)
```

课前的「距上课 25 分钟」是**自己算的文本**，所以需要每分钟刷一次才不会停在旧数字上
（见 9.5 的 tick 闹钟）。

**② 胶囊只有一个文本槽**：「课程名 + 地点」和「倒计时」只能二选一，
做不出「左边课程名、右边倒计时」的并排布局。

**③ 通知必须有终点**：`setTimeoutAfter` 用剩余时间，`deadline` 变了要重发，
否则通知会在错误的时刻自己消失。

#### 9.5 调度：边界闹钟 + 每分钟 tick

课程状态只有三个，切换点都是**确定的时刻**，用 `AlarmManager` 在边界醒来重算即可，
不需要常驻服务：

```
课前 N 分钟 ──→ 上课 ──→ 下课（或课间）──→ 下一节上课 ──→ 下课
   UPCOMING      IN_CLASS      BREAK          IN_CLASS      （通知结束）
```

```kotlin
// 边界：课前 N 分钟 / 上课 / 下课 / 每一小节的上课下课
if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)      // 无精确权限 → 降级
} else {
    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // 准点
}
```

再叠三件事：

- **每分钟 tick**（只在课前阶段跑）：下一个整分钟 +2s 唤醒，重算「距上课 X 分钟」，
  进入上课自动取消，不长期耗电
- **划掉屏蔽**：`setDeleteIntent` 收到 `ACTION_DISMISS` → 记住「本节内不再提醒」，
  用 `lessonEnd` 做过期，到点自动失效
- **开机 / 改时间 / 应用更新**：`BOOT_COMPLETED`、`TIME_SET`、`MY_PACKAGE_REPLACED`
  重新对齐闹钟（`notif/FocusReceiver.kt`）

课程数据直接复用 `DayPlan.of()`，**调课、停课、补课、自定义时间都能正确参与**，
不用为通知单独算一遍课表。

#### 9.6 权限是两套，别混

| 权限 | protectionLevel | 怎么申请 | 没开的后果 |
| --- | --- | --- | --- |
| `POST_NOTIFICATIONS` | dangerous | **运行时弹窗**（Android 13+） | 通知根本发不出来 |
| `POST_PROMOTED_NOTIFICATIONS` | `normal\|appop` | ❌ 运行时申请弹不出框 | 通知能发，但**永远不会被提升**为实况窗 |
| `SCHEDULE_EXACT_ALARM` | appop | 跳系统授权页 | 闹钟退化为不精确，晚几分钟 |

第二条最容易搞错 —— 它**安装时就已授予**，真正的闸门是 AppOp：

```kotlin
// 官方 API 优先，AppOps 兜底（有的 ROM 上 canPostPromotedNotifications 恒 false）
fun canPostPromoted(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 36) return false
    val nm = ctx.getSystemService(NotificationManager::class.java)
    try {
        nm.javaClass.getMethod("canPostPromotedNotifications")
            .invoke(nm) as? Boolean
    } catch (t: Throwable) { null }
        ?: appOpAllowed(ctx, "android:post_promoted_notifications")
}
```

用户只能在系统设置里开，所以 UI 上要**分开显示两行状态**，未允许时点一下直达设置页
（`android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS` → `ACTION_APP_NOTIFICATION_SETTINGS`
→ 应用详情页三级降级）。从设置页返回时用 `onResume` 续上流程，别让用户再点一遍开关。

#### 9.7 鸿蒙：两档降级（`notif/HarmonyLive.kt`）

鸿蒙的 Android 兼容层版本跨度很大，**不能只看 `SDK_INT`**：

| 设备 | 兼容层 | 判定 | 呈现 |
| --- | --- | --- | --- |
| HarmonyOS 5.x | API 36+ | `MODE_LIVE` | 实况窗协议直连 |
| HarmonyOS 4.x / 3.x | API 29~33 | `MODE_PERSISTENT` | **常驻通知 + 系统计时器**，等效实况窗 |
| 通知被系统关闭 | — | `MODE_NONE` | 提示去开通知 |

```kotlin
fun mode(ctx: Context): Int {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        ?: return MODE_NONE
    if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return MODE_NONE
    return if (Api36.supported()) MODE_LIVE else MODE_PERSISTENT
}
```

鸿蒙原生「实况窗」是 ArkTS（LiveViewKit / 服务卡片）能力，**APK 创建不了**；
`MODE_PERSISTENT` 就是在这种设备上的等效做法：`setOngoing(true)` 不可划掉 +
`setUsesChronometer(true)` 交给系统走秒 + `setVisibility(VISIBILITY_PUBLIC)` 锁屏可见。

设置页会把当前形态写出来（`显示形态：常驻倒计时 · 等效实况窗`），
用户不会以为功能没生效。

#### 9.8 套用到自己的项目（30 分钟）

1. **拷 5 个文件**：`native/app/src/main/kotlin/com/tstc/zstangshi/notif/`
   （`Api36` / `FocusPlan` / `FocusNotification` / `FocusController` / `FocusReceiver`，
   鸿蒙版再加 `HarmonyLive`）
2. **改数据入口**：`FocusPlan.build()` 里把 `DayPlan.of(...)` 换成你自己的课表模型，
   输出 `Reminder{title, place, detail, phase, deadline}`
3. **Manifest**：4 个权限 + `.notif.FocusReceiver`（`BOOT_COMPLETED` 等 5 个 action）
4. **图标**：一张**单色带 alpha 的 PNG**（透明底、图形为纯白），
   生成 24/36/48/72/96px 五个密度做 `smallIcon`，系统会按深浅色自动染色；
   再留一张彩色版给 `largeIcon`
5. **设置页**：开关 + 提前分钟数 + 两行权限状态 + 「预览效果」（能立刻验形态，不用等真实课程）
6. **验证**：先点「预览效果」看形态对不对，再等一节真实课看边界切换

> 调试建议：把 `preview()` 做成可指定阶段（课前 / 课中），比改系统时间快得多。

---

## 网络协议速查

如果你要自己做类似的校园 App，这些协议事实可以直接复用。

### 正方教务系统

| 项 | 值 |
| --- | --- |
| 主机 | `jwxt.tstc.edu.cn`（**不是** `jwglxt`） |
| 登录入口 | `/xtgl/login_slogin.html` |
| 公钥接口 | `/xtgl/login_getPublicKey.html` → `{"modulus","exponent"}`（base64 的 BigInteger） |
| 密码加密 | `RSA/ECB/PKCS1Padding`，加密**密码原文**（不加时间戳） |
| 登录表单 | `yhm`（学号）/ `mm`（密文）/ `hidMm`（原文占位）/ `csrftoken` / `language` / `mmsfjm` |
| 成功判定 | 再拉 `/xtgl/index_cxMenuList.html`，**必须含 `gnmkdm`** |
| 课表接口 | `kbList` 数组，按 `jxb_id` 聚合 |
| 会话 | `JSESSIONID` Cookie，10 分钟有效 |

### 车来了（实时公交）

| 项 | 值 |
| --- | --- |
| 接口 | `https://web.chelaile.net.cn/api` |
| 签名 | 参数按**插入顺序**拼 `"k"="v"&...`，加盐 `qwihrnbtmj`，求 MD5 小写 → `cryptoSign` |
| 响应壳 | `**YGKJ{"jsonr":{"data":{"encryptResult":"..."}}}` |
| 解密 | Base64 → `AES-256/ECB/PKCS5Padding`，密钥是 **32 个 ASCII 字符** |
| 唐山 cityId | `056` |
| Referer | 需带 `servicewechat.com/...` 伪装小程序来源 |

### 掌上公交（备用数据源）

| 项 | 值 |
| --- | --- |
| 接口 | `POST https://h5.mygolbs.com/ApiData.do` |
| 必需头 | `Origin` / `Referer` |
| CMD 编码 | `101` 城市列表 / `103` 站表+首末班 / `104` 实时车辆 / `105` 站点线路 / `106` 附近站点 |

> 协议随时可能变。失效时把这些常量更新一下即可 —— 都集中在
> `jw/JwClient.kt`、`bus/ChelaileClient.kt`、`bus/MygolbsClient.kt` 的顶部常量区。

---

## 想复用到自己学校？

这份代码里**大部分逻辑是通用的**，换个学校主要改这几处。

### 必须改的（约 10 分钟）

| # | 文件 | 改什么 |
| --- | --- | --- |
| 1 | `jw/JwClient.kt` 顶部 | `HOST` 改成你学校教务的主机名 |
| 2 | `res/values/strings.xml` | `app_name`、学校名 |
| 3 | `native/version.properties` | `versionCode` / `versionName` |
| 4 | `res/mipmap-xxxhdpi/ic_launcher.png` | 图标 |
| 5 | `res/drawable-nodpi/login_badge.png` | 登录页校徽 |
| 6 | `data/AcademicCalendar.kt` | 学期开始日、考试周、节假日 |

### 可能要改的（看学校用的什么教务系统）

**如果也是正方教务（jwxt 系）** —— 大概率开箱即用：

- 登录五步流程一般是通用的
- 课表字段名（`kcmc` / `xqj` / `jcor` / `zcd`）是正方的标准字段
- 只需改 `HOST`

**如果是其他教务系统**（青果、强智、URP、金智等）：

- 重写 `jw/JwClient.kt` 的登录部分
- 重写 `data/JwglxtFetcher.kt` 的解析部分
- **不用动** `data/` 下的其他文件（课表模型、周次计算、作息、存储都是通用的）

### 通用的部分（不用改）

| 模块 | 为什么通用 |
| --- | --- |
| `data/Timetable.kt` | 课表数据结构（课程 / 时段 / 学期）与教务系统无关 |
| `data/WeekRange.kt` | 周次表达式解析（`5-12周`、`(单)`、`(双)`…） |
| `data/WeekMath.kt` | 教学周计算 |
| `data/ScheduleSettings.kt` | 作息节次（改一下 `DEFAULT_BREAKS` 就行） |
| `data/Store.kt` | 本地存储 |
| `ui/` 全部 | 自绘控件与业务无关 |
| `bus/` 全部 | 公交协议是全国通用的（车来了 / 掌上公交） |

### 公交部分

车来了和掌上公交是**全国性服务**，只要改 `DEFAULT_CITY_ID`：

```kotlin
// bus/ChelaileClient.kt
const val DEFAULT_CITY_ID: String = "056"   // 唐山；换成你所在城市的编号
```

城市编号可以从 `https://web.chelaile.net.cn/wwd/ncitylist` 查到（`ChelaileClient.cities()` 已经封装好）。

### 改完怎么验证

```bash
# 1. 类型检查（快，不打包）
bash native/typecheck.sh

# 2. 出 APK
bash native/build.sh

# 3. 鸿蒙专版额外自检
cd harmony-android
node scripts/check-java-api.js native/build/dex/classes.dex
```

### 建议的改动顺序

1. **先只改校名和图标**，编译能装上、界面正常显示
2. **再改 `HOST`**，试登录能不能成功（这一步最容易卡住）
3. 登录通了再看课表能不能解析出来
4. 最后改校历、作息

> 每改一步就编译一次，别一次改完再排查 —— 教务协议出问题时，
> 一次只改一件事，才能快速定位是哪儿的锅。

---

## 仓库结构

```
.
├── native/        ★ Android 标准版主线（纯 Kotlin、纯 framework，build.sh 出 APK）
├── harmony-android/ ★ 鸿蒙专版（实况窗适配 + 兼容层加固，**独立演进**，自带 build.sh）
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

### harmony-v4.1.0 · 鸿蒙专版「实况窗版」（2026-11-18 发布）

| 项 | 内容 |
| --- | --- |
| 新增 | **课程实况窗**：`notif/` 整包（`Api36` 反射、`FocusPlan` 计划、`FocusNotification` 构建、`FocusController` 调度、`FocusReceiver` 广播） |
| 鸿蒙特化 | 新增 `notif/HarmonyLive.kt`：实况窗协议 / 常驻倒计时双档判定，「我的」页直接显示当前形态 |
| 界面 | 「我的 → 课程实况窗」：开关、显示形态、通知权限、实况窗权限（有协议才显示）、精确提醒、提前提醒、预览效果 |
| 顺带 | 同步课表不再抹掉手工添加的课程（`data/TimetableMerge.kt`）；切换账号仍整体覆盖 |
| 定位 | 与标准版**代码分叉**，鸿蒙专版按兼容层能力独立实现，避免与标准版功能冲突 |

### v4.0.5 · 标准版（2026-11-18 发布）

| 项 | 内容 |
| --- | --- |
| 新增 | 课程实况窗（Android 16 实况通知 / ColorOS 超级岛 / 小米焦点通知） |
| 修复 | 「同步课表」会抹掉手工添加的课与手动停课调课；现按「教务为准、手工保留」合并 |
| 修复 | 实况窗展开态地点重复显示；主行改为「距上课 25 分钟 · 教学楼」 |
| 版本 | 4.0.5（versionCode 63） |

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
- 本仓库以 **GPL-3.0** 协议开源，详见 [LICENSE](LICENSE)。

---

## 相关文档

- [CHANGELOG.md](CHANGELOG.md) — 逐版本、逐条修补的完整更新日志
- [native/README.md](native/README.md) — Android 原生版：构建、目录、自绘控件实现与踩坑
- [harmony-android/README.md](harmony-android/README.md) — 鸿蒙专版：兼容性问题分析、加固清单、构建与自检
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 架构、数据结构与动效系统（v2 时期撰写，v3/v4 结构见各端 README）
- [docs/remote-api-map.md](docs/remote-api-map.md) — 教务接口实测记录（端点、字段、周次、协议可达性）
- [Releases](https://github.com/Chanmingfei/zstangshi/releases) — 各版本安装包与更新说明

---

## 许可证

本项目以 **GNU General Public License v3.0（GPL-3.0）** 开源，全文见 [LICENSE](LICENSE)。

简单说三件事（以 LICENSE 原文为准）：

| 你可以 | 你必须 | 你不能 |
| --- | --- | --- |
| 自由使用、修改、分发 | 保留版权声明与许可证 | 把修改后的版本闭源分发 |
| 用在商业场景 | 你分发的衍生作品同样以 GPL-3.0 开源 | 用作者名义为你的衍生品背书 |
| 私有地自己改着玩 | 说明修改内容与日期 | 附加额外限制条款 |

> 如果只是想**参考实现思路**（比如「课程实况窗」那套反射 + 降级写法），
> 拿去写自己的项目没问题，不必把整个 App 也开源 —— 但直接复用本仓库代码时请遵循 GPL-3.0。

---

## 关于

**开发者**：陈铭霏
**荣誉出品**：百度唐山师范学院贴吧吧务组

个人开发项目，**非学校官方发布**。
内置校历数据来源：唐山师范学院教务处（jwc.tstc.edu.cn）。
实时公交参考 [追客 Trackr](https://github.com/5151561/Trackr)（MIT License）。