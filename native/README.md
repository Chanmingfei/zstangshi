# 掌上唐师 · Android 原生版（`native/`）

v3 系列的全部代码。**纯 Android framework 实现，不引 androidx，不引任何第三方库**，
一个 `bash build.sh` 直出可安装 APK。

从 v2（WebView + H5 套壳）换成原生的原因很直接：课表的横滑翻周、底栏切页的连续平移、
二级页的预测性返回跟手，这三件事在 WebView 里都只能用 JS 模拟手势，跟不上系统动画的节奏，
也做不出「手指拖到哪、画面跟到哪」的原生手感。v2 的代码完整保留在 `../android/` + `../web/`
和 `legacy-webview-v2.0.1` 分支，与这里零共享。

- 版本：**3.0.33（versionCode 52）**
- 包名：`com.tstc.zstangshi`
- minSdk 26（Android 8.0）· targetSdk 34（Android 14）
- 权限：`INTERNET`、`ACCESS_NETWORK_STATE`，加一对定位权限 —— 只有实时公交首页用来取一次附近站点，
  拒绝授权也能用（手动搜索线路）
- 规模：48 个 Java 文件、约 17,200 行、35 个资源文件、APK 约 1.06 MB

---

## 构建

需要 JDK 17 与 Android SDK（`build-tools;34.0.0` + `platforms;android-34`）。

```bash
bash native/build.sh
```

脚本会依次执行 `aapt2 compile → aapt2 link → javac → d8 → zipalign → apksigner`，
产物固定落在 `android/build/app-debug.apk`（与本仓库 `android/` 目录同名，但里面是 v3 的产物）。

脚本里用到的几条环境变量都有默认值，按自己机器的路径改：

| 变量 | 默认 | 用途 |
| --- | --- | --- |
| `ANDROID_SDK_ROOT` | `C:/Android/sdk` | SDK 根目录 |
| `JDK` | `C:/Android/jdk17/...` | javac 所在 JDK |
| `KEYSTORE` / `KEY_ALIAS` | 本地调试密钥 | 签名（证书 CN=ZSTS） |

版本号只在一处维护 —— `native/version.properties`：

```properties
versionCode=52
versionName=3.0.33
```

改了就跑一遍 `build.sh`，`aapt2 link` 会把两个值同时写进 manifest 和包信息。

验签与版本核对：

```bash
BT=C:/Android/sdk/build-tools/34.0.0
"$BT/apksigner.bat" verify --print-certs android/build/app-debug.apk
"$BT/aapt2.exe" dump badging android/build/app-debug.apk | head -2
```

---

## 目录

```
native/
├── build.sh                     一条命令出 APK
├── version.properties           版本号唯一真源
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/tstc/zstangshi/
    │   ├── SplashActivity       启动页：开了自动登录就在这儿登，否则去登录页
    │   ├── LoginActivity        登录（教务 / 仅学工两种口径）
    │   ├── MainActivity         四页容器：PageDeck + 胶囊底栏 + 返回手势调度
    │   ├── FeatureActivity      二级功能页（成绩 / 绩点 / 推荐课表 / 选课…）
    │   ├── BusActivity          实时公交：首页 / 站点 / 线路三层共用这一个 Activity
    │   ├── CalendarActivity     校历
    │   ├── WebViewActivity      应用内浏览器（仓库链接、长尾教务功能）
    │   ├── BaseActivity         深色模式注入（applyOverrideConfiguration）
    │   ├── bus/                 公交数据层：BusClient（签名+接口+预测）/ BusModels / BusStore
    │   ├── data/                课表模型、周次换算、校历、Store 快照、教务抓取
    │   ├── jw/                  正方教务：Auth / JwClient / JwApi
    │   ├── pages/               四个主页面 + 课程编辑器
    │   ├── ui/                  自绘控件：PageDeck / PageSlide / CapsuleDock / Sheet …
    │   └── widget/              桌面小组件（今日课程 4×2）
    └── res/                     主题、颜色（含 values-night）、图标、校园插画
```

---

## 自绘控件：几个关键实现

这些都是 framework 没有现成 API、只能自己写的地方，也是从 v2 换原生的主要收益。

### `ui/PageDeck.java` —— 横向页面带

四个主页面按底栏顺序**同时**排在一条横向带子上（每页宽度 = 屏宽），整体靠
`translationX = (slot - position) × slotWidth` 定位，切页时平移整条带子。

为什么不直接平移一个装四个页面的容器：容器本身只有一屏宽，平移它会被自己的 bounds 裁掉，
触摸也失效。所以每个子页各自 `layout` 在 `[0, 0, w, h]`，用各自的 `translationX` 表达位置——
看起来是一条带子在动，实际是四个页面在同一个坐标系里排队。

时长公式（`MainActivity.show()`）：`290ms + 45ms × (剩余格数 - 1)`，夹在 `[160, 400]`。
首页→课表 290、→成绩 335、→我的 380，极差 90ms，是同一套节奏；同时因为跨三格时中间两页
是真的从眼前掠过，不会「一糊而过」。

### `ui/PageSlide.java` —— 二级页的圆角卡片转场

系统只在**返回**方向画圆角卡片（AOSP `CrossActivityBackAnimation` 取设备圆角半径，把正在关闭的
窗口裁圆角再缩到 90%），**进入**方向走的是窗口动画，framework 的窗口动画只有 alpha / translate /
scale，没有圆角属性，也没有任何公开 API 能给应用窗口设圆角。于是同一个页面进来是直角、出去是圆角。

做法：主题 `windowIsTranslucent` + 窗口底色透明，在 `android.R.id.content` 里插一层自建
`FrameLayout` 当卡片，把页面根视图搬进去；圆角靠 `setClipToOutline` + `ViewOutlineProvider`，
投影靠 `setElevation`。位移 = `p × 屏宽`，圆角 = 系统圆角 × `clamp(p / 0.28)`，落位归 0，
静止状态与普通页面逐像素一致。系统窗口动画用 `overrideActivityTransition(OPEN, 0, 0)` 关掉。

> 踩过的坑：圆角一开始裁在 **DecorView** 上，`setClipToOutline` 对窗口根节点不生效，
> 结果进出都是没有边界、没有投影的矩形。必须是普通 View 才裁得出来。

### `ui/BackGestures.java` —— 预测性返回

manifest 开 `enableOnBackInvokedCallback`，API 33+ 走 `OnBackInvokedDispatcher`，
API 34+ 用 `OnBackAnimationCallback` 的 `onBackProgressed` 做跟手。一级页把返回进度喂给
`PageDeck.jumpTo(position × (1 - progress))`，整条带子跟着手指回滑；二级页喂给 `PageSlide`。
API 32 及以下没有这套机制，退回 `onBackPressed` + 自绘滑动。

### `ui/Sheet.java` —— 底部弹层

`Dialog` + `Theme_Translucent_NoTitleBar`，进场/退场自己用 `ValueAnimator` 做。
一个容易漏的点：Dialog 的窗口是独立的，不继承宿主的状态栏颜色，必须在 `onStart` 里把宿主的
`statusBarColor` / `navigationBarColor` / `LIGHT_STATUS_BAR` 抄过去，否则弹窗一出来状态栏就变黑。

### 其他

- `PullRefreshLayout`：下拉刷新，`scrollY == 0` 时才拦截纵向手势。
- `CapsuleDock`：悬浮胶囊底栏，选中块内缩小胶囊 + 回弹滑移。
- `TimetableGridView`：课表网格，按周次渲染、课程块自适应高度、长按进入编辑。
- 深色模式：manifest 声明了 `configChanges="uiMode"`，系统不会重建 Activity，所以在
  `applyOverrideConfiguration` 这个切口里重新注入夜间位（见 `BaseActivity`），否则切主题时
  `values-night/colors.xml` 不会生效。

---

## 数据层

- `data/Store.java`：本地快照。`feature(type)` / `featureAt(type)` 读缓存，按登录时刻
  `KEY_LOGIN_AT` 判断新鲜度，`studentName(owner)` 按学号隔离姓名（换账号不会串）。
- `jw/JwClient.java`：正方登录。密码用教务页面下发的 RSA 公钥加密后再 POST；
  会话 `JSESSIONID` 复用，超时自动重登。
- `jw/JwApi.java`：成绩 / 绩点 / 推荐课表 / 选课。成绩走两个 POST（主表 + 分项），
  绩点解析学业情况页 HTML，最后统一成「标题 + 副标题 + 数值 + 备注」的行模型，界面只写一套渲染。
- `data/WeekMath.java`：周次换算。起始日兜底链为「教务下发 → 本地课表起始日 → 内置校历 → 今天所在周的周一」，
  这是 v3.0.13 修掉「把本周当第 1 周」的关键。
- 刷新策略：首页下拉只重新登录（`Auth.loginOnly`）并更新登录态，**不覆盖本地课表**；
  成绩 / 绩点进页面才加载，下拉才真正拉取。
- `bus/BusClient.java`：实时公交。参数按插入顺序拼 `k=v&` + 固定盐 → MD5 → `cryptoSign`，
  响应剥 `**YGKJ{…}` 壳后 AES/ECB 解密。到站预测只有一个口径 `predict(cityId, lineId, order, …)`，
  首页 / 站点详情 / 线路详情全走它 —— 上游「附近站点」接口只对最近几站算实时，直接用会得到
  「首页说没车、点进去有车」。批量补数据限 3 并发、每条间隔 70ms，否则会被上游掐连接。
  虚拟站组（上游把一段路拆成多站、`order` 全为 999，真实站序藏在 `sId` 的 `999$30$1` 里）
  由 `orderLabel` / `effectiveOrder` 还原。
- 公交刷新策略：进页面即拉一次，之后每 20 秒静默轮询，只换行内文字（`etaBinders` 行级绑定），
  不动滚动位置和已选候车站；首页下拉重新定位并换一批站点，线路详情下拉只更新车辆位置。

---

## 已知限制

- 二级页返回时，系统那套「下层页面从 90% 放大回 100%」是**对 opening activity 的
  SurfaceControl 变换**，应用拿不到对方窗口句柄，自绘不出来。当前是卡片右移 + 圆角 + 投影，
  下层保持原尺寸。
- APK 用本地调试密钥签名，仅供自用与测试；正式分发需要换签名。
- 只在 Android 14 的 ColorOS 上做过真机点测，其他 ROM 的状态栏行为（尤其透明窗口 + 全屏布局）
  可能需要微调。
- 实时公交没有官方 API：走的是车来了的公开查询接口，字段或签名随上游改动可能失效；
  「附近站点」接口不返回经纬度，继续下滑加载更远站点时得逐个查站点详情补坐标（已加内存缓存）。
