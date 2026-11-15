# 掌上唐师

面向唐山师范学院在校生的校园服务 App（个人开发，**非学校官方发布**）。把教务系统与学工系统里
最常用的几件事装进手机：课表、成绩、绩点、推荐课表、选课、校历、奖助贷与请假，
以及校门口的**实时公交**（到站预测、候车站、首站发车时刻表、乘车码）。

零第三方运行时依赖 · 不引 androidx、不引 Gradle · 纯 Android framework，
`bash native/build.sh` 一条命令直出可安装 APK。

---

## 📥 下载哪个版本？

**先确认你的手机系统。**

| 你的设备 | 下载 | 版本标识 |
| --- | --- | --- |
| **鸿蒙 / HarmonyOS**<br>华为、荣耀新机型 | **[⬇ 鸿蒙专版](../../releases/tag/harmony-v4.0.0)**<br>`zstangshi-harmony-4.0.0.apk` | 桌面名 **掌上唐师·鸿蒙版**<br>关于页 → 版本类型「鸿蒙专版」 |
| **普通安卓**<br>小米 / OPPO / vivo / 三星 / 一加 等 | **[⬇ 标准版](../../releases/tag/v4.0.0)**<br>`zstangshi-v4.0.0.apk` | 桌面名 **掌上唐师**<br>关于页无「版本类型」行 |

### 不确定自己是不是鸿蒙？

打开手机 **设置 → 关于手机**：

- 看到 **「HarmonyOS 版本」** 或 **「鸿蒙版本」** → 下载**鸿蒙专版**
- 看到 **「Android 版本」** 或 **「MIUI / ColorOS / OriginOS / One UI」** → 下载**标准版**

### 两个版本的区别

**功能完全一样**，没有删减。区别只在兼容性处理：

| | 标准版 | 鸿蒙专版 |
| --- | --- | --- |
| 功能 | 全部 | 全部（**一字不差**） |
| 包名 | `com.tstc.zstangshi` | `com.tstc.zstangshi`（相同） |
| 版本 | 4.0.0（58） | 4.0.0-harmony（58） |
| 实时公交 | ✅ | ✅ |
| 鸿蒙登录兼容 | ❌ 会闪退 | ✅ 已修复 |
| APK 体积 | 3.48 MiB | 1.62 MiB |

> 鸿蒙专版为什么更小：标准版为了兼容老设备打包了完整 Kotlin 标准库且未做 R8 裁剪；
> 鸿蒙专版只针对较新机型，体积更紧凑。

### 为什么鸿蒙需要单独一版

安卓版装在鸿蒙上有两个问题，都和「鸿蒙兼容层缺 Android 专有 API」有关：

1. **一点登录就闪退** —— 三处 Android 专有 API 抛出 `NoClassDefFoundError` / `NoSuchMethodError`，
   这是 **Error 不是 Exception**，`catch (Exception)` 抓不住，异常穿透到 UI 线程顶层就杀进程
2. **登录报 `URLEncoder` 错误** —— 代码用了 Java 10 才有的 `encode(String, Charset)` 重载，
   鸿蒙只有 Java 8 那批 API

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

---

## 版本演进

| 版本 | 时间 | 变化 |
| --- | --- | --- |
| v1.5.x | — | 首个可用版本：课表 + 校历 |
| v2.x | — | 统一为一套 H5（`web/`）+ 两个原生壳 |
| v3.0.25 | — | Android 原生重写（Java，零依赖，手动构建脚本） |
| v3.1.1 | — | 收班线路文案统一 + 顶栏对齐 |
| **v4.0.0** | 2026-11-15 | **全量 Kotlin 重写**（54 文件 / 约 2.29 万行）+ 实时公交按 Trackr 口径重打磨 |
| **harmony-v4.0.0** | 2026-11-15 | **鸿蒙专版**：修复鸿蒙系统上的登录闪退与 URLEncoder 错误 |

> v4.0.0 把 54 个源码文件从 Java 全量改写为 Kotlin，功能、界面、文案、尺寸、动画与 v3.1.1 保持一致；
> 在此基础上把**实时公交**按 [追客 Trackr](https://github.com/5151561/Trackr)（MIT）的口径重新打磨：
> 主卡三态、到站文案统一、数据同源、乘车码悬浮按钮、单车跟随。
> 完整修改记录见 [CHANGELOG.md](CHANGELOG.md)。

> 曾尝试做过 iOS 版（SwiftUI 重写），多次构建后装到真机上功能不可用，**已放弃并移除**。
> 本仓库现在只维护 Android 标准版与鸿蒙专版。

---

## 功能一览

**首页** —— 问候语、今日课程时间轴（正在上的课高亮 + 倒计时）、8 格功能宫格、学工系统入口

**课表** —— 四色课程块、单双周、冲突并排、点击编辑、翻周、左右滑动切换周次

**成绩** —— 学期筛选、成绩列表、绩点换算、明细分项（平时/期末）

**校历** —— 月视图、法定节假日、调休补课、教学周次、事件提醒

**实时公交** —— 附近站点、线路查询、到站预测、首站发车时刻表、收藏、乘车码入口、
追客 Trackr 口径的线路详情

**我的** —— 账号管理、自动登录、课表设置、作息时间、调课停课、外观（浅色/深色/跟随系统）、
桌面小组件、关于

**教务集成** —— 正方教务系统登录、课表同步、成绩查询、学分绩点、自主选课、推荐课表

---

## 技术特点

**零第三方依赖。** 不用 androidx，不用 Gradle，只用 Android framework API。
体积小、构建快、没有被上游库拖累的风险。

**自己画的 UI。** 胶囊 Dock、课表网格、页面切换动画、弹层、下拉刷新全部是自定义 View + Canvas，
没有 Material Components。

**手写构建脚本。** `native/build.sh` 走 aapt2 → javac(R.java) → kotlinc → d8 → zipalign → apksigner，
不依赖 Android Gradle Plugin。

### 目录结构

```
zstangshi/
├── native/              ★ Android 标准版（Kotlin，v4.0.0）
│   ├── build.sh             一条命令构建
│   ├── version.properties
│   └── app/src/main/
│       ├── kotlin/com/tstc/zstangshi/
│       │   ├── jw/              正方教务接口（登录/课表/成绩/绩点/选课）
│       │   ├── bus/             实时公交（车来了 + 掌上公交两套协议）
│       │   ├── data/            课表/校历/作息/存储
│       │   ├── pages/           四个主页面 + 各类编辑器
│       │   ├── ui/              自绘组件
│       │   └── widget/          桌面小组件
│       └── res/                 资源
├── harmony-android/     ★ 鸿蒙专版（基于 native/，修复鸿蒙兼容问题）
│   ├── native/              源码
│   ├── scripts/             dex 的 Java API 兼容性扫描
│   └── README.md            鸿蒙适配说明
├── docs/                架构文档、PRD、教务接口映射、诊断样本
├── tools/               构建与测试脚本
├── web/                 v2 遗留的 H5 版本（存档）
├── android/             v2 遗留的 Android 壳（存档）
├── reference/           参考项目（Trackr 等）
├── CHANGELOG.md         完整修改记录
└── release-notes-*.md   各版本发布说明
```

---

## 构建

### Android 标准版

需要 JDK 17、Android SDK build-tools 34.0.0、Kotlin 编译器 1.9.24。

```bash
bash native/build.sh
# 产物：android/build/app-debug.apk
```

> ⚠️ **路径不能有中文** —— `aapt2` 会报 `failed to open directory`。
> 把工程放到纯 ASCII 路径下再编译。

### 鸿蒙专版

```bash
cd harmony-android
bash native/build.sh
# 产物：android/build/app-debug.apk

# 顺带自检：扫 dex 里有没有 Java 9+ 的 API
node scripts/check-java-api.js native/build/dex/classes.dex
```

详见 [harmony-android/README.md](harmony-android/README.md)。

---

## 数据与隐私

- 登录密码以 RSA 加密后提交给教务，本地可选择保存（Android 存 SharedPreferences）
- 课表、成绩等数据只存在本机，不上传任何服务器
- 实时公交只在首页取一次粗定位，用于「附近站点」，不做后台定位
- 无统计 SDK、无广告、无崩溃上报

---

## 已知限制

- **鸿蒙专版未做大范围真机验证** —— 只确认了登录问题已修复，其他功能理论上与原版一致
- 桌面小组件在鸿蒙上不可用（鸿蒙没有 appwidget 服务），会自动跳过不报错
- 教务系统的接口随时可能变，失效时需要更新解析逻辑

---

## 许可

个人开发项目，**非学校官方发布**。
内置校历数据来源：唐山师范学院教务处（jwc.tstc.edu.cn）。
实时公交参考 [追客 Trackr](https://github.com/5151561/Trackr)（MIT License）。