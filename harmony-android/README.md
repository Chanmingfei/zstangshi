# 掌上唐师 · 鸿蒙专版

> 给**装在 HarmonyOS / 鸿蒙系统上**的安卓版做的修复版。
>
> 功能一个没删（实时公交照旧可用），修掉了「一点登录就闪退」和「登录报
> `URLEncoder` 错误」两类问题。

---

## 📥 我该下哪个版本？

**先看你的手机是什么系统。**

| 你的手机 | 下载 | 说明 |
| --- | --- | --- |
| **鸿蒙 / HarmonyOS**<br>（华为、荣耀新机型） | **[⬇ 鸿蒙专版](../../releases/latest)**<br>`掌上唐师-鸿蒙版-x.x.x.apk` | 本仓库这版。修好了登录问题 |
| **普通安卓**<br>（小米、OPPO、vivo、三星等） | **[⬇ 标准版](../../releases)**<br>找 `v4.0.0` 那个 | 原版，没有鸿蒙适配代码 |
| **iPhone / iPad** | 暂无可用版本 | iOS 版功能缺失，暂不推荐 |

### 不确定自己是不是鸿蒙？

打开手机 **设置 → 关于手机**：

- 看到 **「HarmonyOS 版本」** 或 **「鸿蒙版本」** → 下**鸿蒙专版**
- 看到 **「Android 版本」** 或 **「MIUI / ColorOS / OriginOS」** → 下**标准版**

> 也可以在拨号盘输入 `*#*#2846579#*#*` 看系统信息（部分机型支持）。

---

## ⚠️ 安装前必读

### 版本标识

装好后在 App 里 **「我的」→ 最下面「关于」** 可以确认：

| | 标准版 | 鸿蒙专版 |
| --- | --- | --- |
| 桌面图标下的名字 | 掌上唐师 | **掌上唐师·鸿蒙版** |
| 关于 → 版本 | 4.0.0 | **4.0.0-harmony** |
| 关于 → 版本类型 | 无此行 | **鸿蒙专版 · HarmonyOS 适配** |

看到「鸿蒙专版」就说明下对了。

### 关于覆盖安装

两个版本**包名相同**（`com.tstc.zstangshi`），这是故意的：

- ✅ **好处**：登录态、课表、设置都能保留，不用重新弄
- ⚠️ **但是**：因为签名不同，**如果手机上装的是官方签名版，会提示「签名冲突」无法覆盖**

**遇到签名冲突怎么办**：

1. 先备份（可选）：截图课表，或记下账号密码
2. 卸载原来的掌上唐师
3. 安装鸿蒙专版
4. 重新登录一次（**卸载会清掉本地课表和登录态**）

---

## 🔧 修了什么

### 问题一：一点登录就闪退

**不是密码问题。** 全工程搜过，没有 `SecureRandom`、没有 `AndroidKeyStore`，
密码存的就是明文 `SharedPreferences`。

根因是**三处 Android 专有 API 在鸿蒙上不存在**，抛出的又是
`NoClassDefFoundError` / `NoSuchMethodError` —— 这些是 **Error 不是 Exception**，
`catch (e: Exception)` 根本抓不住，异常一路抛到 UI 线程顶层 → 进程被杀。

| # | 位置 | 原来怎么崩 |
| --- | --- | --- |
| ① | `LoginActivity` → `TodayWidget.refreshAll()` | 登录成功后**在 UI 线程同步**调 `AppWidgetManager.updateAppWidget`。鸿蒙没有 appwidget 服务，直接抛；这段**没有 try/catch** |
| ② | `BackGestures` 内部类 `Animated` | 继承 `OnBackAnimationCallback`（API 34 才有的类）。和 `BackGestures` 同文件 → ART 验证方法体时连父类一起解析 → 类加载即失败 |
| ③ | `MainActivity.applyInsets()` | `WindowInsets.Type.systemBars()` / `window.statusBarColor` 不是鸿蒙 API |

三处全在「点登录 → 进主页」路径上，所以启动页、课表、实时公交不碰它们，
那些功能是好的 —— 和用户反馈完全一致。

### 问题二：登录报 URLEncoder 错误

真机实测发现：

```
No static method encode(String;Charset;)String in class java.net.URLEncoder
```

`URLEncoder.encode(String, Charset)` 是 **Java 10** 才加的重载。
我们用 **JDK 17** 编译，编译期完全合法；但鸿蒙的 `core-oj.jar` 只有 Java 8 那批 API。

```kotlin
// ❌ 原来
URLEncoder.encode(key, StandardCharsets.UTF_8)   // 匹配 Charset 重载

// ✅ 现在
URLEncoder.encode(key, "UTF-8")                  // String 重载，Java 8 就有
```

**为什么只有登录坏、公交却正常**：全工程只有 `JwClient.encodeForm()` 用了 `Charset` 重载，
公交的 `ChelaileClient` / `MygolbsClient` 用的都是 `"UTF-8"` 字符串版。

---

## 🛡️ 加固清单

修完上面两条还不够 —— 鸿蒙兼容层到底缺什么没法穷举，所以又加了几层保险：

| 位置 | 做了什么 |
| --- | --- |
| `widget/TodayWidget.kt` | `refreshAll` / `render` / `scheduleTick` / `cancelTick` 全链路 `catch (Throwable)`，失败静默 |
| `ui/AnimatedBackCallback.kt` | **新建**，把 `OnBackAnimationCallback` 隔离到独立文件，只有真正 `new` 时才加载 |
| `ui/BackGestures.kt` | 不再只看 `SDK_INT`，改用 `Class.forName` 真探测；装不上退化成普通返回 |
| `MainActivity.kt` | insets 三级降级：现代 API → 量状态栏高度 → 0 内边距 |
| `ui/PageSlide.kt` | `setDecorFitsSystemWindows` 失败退回老 flag |
| `ui/Sheet.kt` | `syncSystemBars` 整段兜底 |
| `LoginActivity.kt` | 登录流程逐段隔离，**保证 `goMain()` 一定执行** |
| `BaseActivity.kt` | `attachBaseContext` / `applyOverrideConfiguration` 兜底（生命周期咽喉） |
| `pages/MinePage.kt` | `pinWidget` 兜底 + 关于页加「版本类型」 |
| `harmony/CrashGuard.kt` | **新建**，全局 `UncaughtExceptionHandler`，漏网异常写日志后吞掉 |
| `harmony/ZstsApp.kt` | **新建**，`Application` 入口，只负责装 CrashGuard |

### 崩溃日志在哪

万一还是崩了，日志在：

```
Android/data/com.tstc.zstangshi/files/crash/harmony-*.log
```

里面记录了设备型号、系统和完整堆栈。发 Issue 时贴上来能快速定位。

---

## 🔨 自己编译

工程零第三方依赖，只用 Android framework API。

### 环境要求

- JDK 17
- Android SDK build-tools 34.0.0（`aapt2` / `d8` / `zipalign` / `apksigner`）
- Kotlin 编译器 1.9.24（`kotlinc19`，带 coroutines 1.7.3）
- bash（Windows 上可用 Git Bash）

### 编译

```bash
bash native/build.sh
```

产物：`android/build/app-debug.apk`

### ⚠️ 路径不能有中文

`aapt2` 会报 `failed to open directory`。把工程放到纯 ASCII 路径下再编译，例如：

```bash
cp -r . /c/build/zstangshi && cd /c/build/zstangshi && bash native/build.sh
```

### 构建后自检

这个脚本会解析 dex 的方法引用表，扫出所有 **Java 9+ 的 API**（就是第二个问题的成因）：

```bash
node scripts/check-java-api.js native/build/dex/classes.dex
```

期望输出：

```
dex 方法引用总数：13174
✓ 没有发现 Java 9+ 的 API 引用
```

---

## 📁 目录结构

```
harmony-android/
├── native/
│   ├── build.sh                    aapt2 + kotlinc + d8 + apksigner
│   ├── typecheck.sh
│   ├── version.properties          4.0.0-harmony (58)
│   └── app/src/main/
│       ├── AndroidManifest.xml     已声明 android:name=".harmony.ZstsApp"
│       ├── res/                    字符串/颜色/图标/布局
│       └── kotlin/com/tstc/zstangshi/
│           ├── LoginActivity.kt    ★ 登录流程逐段隔离
│           ├── MainActivity.kt     ★ insets 三级降级
│           ├── BaseActivity.kt     ★ 生命周期咽喉兜底
│           ├── pages/              四个主页面 + 编辑器
│           ├── widget/             ★ 小组件全链路吞异常
│           ├── ui/                 ★ BackGestures 拆类 / PageSlide / Sheet
│           ├── jw/                 ★ JwClient 修 URLEncoder
│           ├── bus/                实时公交（车来了 + 掌上公交）
│           ├── data/               课表/校历/作息/存储
│           └── harmony/            ★ 鸿蒙专版新增：CrashGuard + ZstsApp
├── scripts/
│   └── check-java-api.js           dex 方法引用扫描
└── README.md
```

★ = 鸿蒙专版的改动

---

## 📌 附：和标准版的关系

- 这版**包含 4.0 的全部功能**，没有删任何东西
- 只新增了防御代码和品牌标识，**没有改动业务逻辑**
- 安卓机上也能正常跑（所有 `try/catch` 在安卓上都不会触发）
- 标准版继续用原来的，互不影响（只是同包名不能共存）

---

## ❓ 常见问题

**Q：装了鸿蒙专版，还能收到标准版的更新吗？**
A：不能。这是独立的分支版本，更新要手动下载新 APK。

**Q：为什么桌面名字变了？**
A：为了让你一眼分清装的是哪个版本。包名没变，数据互通。

**Q：鸿蒙 4.x 能装吗？**
A：能。鸿蒙 4.x 走的就是 Android 兼容层，装 APK 这条路没变。

**Q：还是闪退/登录失败怎么办？**
A：发 Issue，附上 `Android/data/com.tstc.zstangshi/files/crash/` 里的日志。

---

个人开发项目，**非学校官方发布**。
内置校历数据来源：唐山师范学院教务处（jwc.tstc.edu.cn）。