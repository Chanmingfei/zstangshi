# ios-kmp · P0 探针

> 目标不是「把 iOS 写出来」，而是先用一天时间回答一个问题：
> **把这批逻辑从 Android 搬到跨平台共享层，到底会不会改坏？**
> 现在这个问题的答案是可以量化的：`bash ios-kmp/run-probe.sh` → 80 项断言全绿。

## 为什么需要这一步

iOS 版没有 Mac 可用，只能靠云构建出包，一轮 10–20 分钟。
如果把逻辑和 UI 混在一起写，每改一行业务代码都要等一次云构建；
而共享层在 Windows 上就能编译、能跑、能用真实教务样本回归 —— 秒级。

所以原则是：**能放进共享层的逻辑一律不放 Swift**。
Swift 那边只留界面、系统能力（通知/定位/Keychain）和会话注入。

## 构建方式（已切到官方 KMP 插件）

不再是「手写脚本编 framework」，而是标准 Gradle 结构：

```
ios-kmp/
├── settings.gradle.kts      # 只 include :shared
├── build.gradle.kts         # Kotlin 2.4.21（apply false）
├── gradle.properties
└── shared/build.gradle.kts  # jvm + iosArm64 + iosSimulatorArm64，静态 framework "Shared"
```

版本按官方兼容表锁定，不用 latest：**Kotlin 2.4.21 ↔ Gradle 8.14.4 ↔ Xcode 26.4**。

云构建（`codemagic.yaml` 的 `ios-framework` 工作流）实测：

```
BUILD SUCCESSFUL in 49s
shared/build/bin/iosArm64/debugFramework/Shared.framework            20M
shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework   20M
```

这一条的意义：**commonMain 能被 Kotlin/Native 编过**，这是 Windows 上永远验不了的事。

## 纯云构建（没有 Mac）

Xcode 的任何图形操作都用不了，所以全部写进 `project.yml`：

- framework 依赖：`dependencies: - framework: …, embed: false`（静态，不嵌入、不参与签名）
- `ENABLE_USER_SCRIPT_SANDBOXING: NO` —— 官方 Direct integration 要在 Xcode 里手点关闭，这里预防性写死
- 工程由 XcodeGen 现场生成，`.xcodeproj` 不入库

工作流 `ios-app`（Codemagic）实测：

```
gradle :shared:linkReleaseFrameworkIosArm64   ->  Shared.framework
xcodegen generate                             ->  ZSTSApp.xcodeproj
xcodebuild (CODE_SIGNING_ALLOWED=NO)          ->  ZSTSApp.app
打包                                           ->  zstangshi-app-unsigned.ipa（500,857 B）
```

产物核对：App 二进制 1.78 MB，含 `Konan` / `kotlin` 运行时符号 4,599 处、`WeekMath` 42 处，
且**没有 Frameworks 目录**（静态链接，符合预期）。

## 目录

```
ios-kmp/
├── shared/src/commonMain/kotlin/com/tstc/zstangshi/shared/
│   ├── Json.kt               org.json 的语义兼容层（替代 org.json）
│   ├── Dates.kt              epoch day 日期内核（替代 java.util.Calendar）
│   ├── WeekRange.kt          周次表达式解析（逐字移植）
│   ├── WeekMath.kt           教学周/日期计算（移植，去掉 Calendar 补丁）
│   ├── Models.kt             课表模型最小集
│   ├── KbParser.kt           教务课表解析（移植）
│   └── CourseColorAssigner.kt 配色分配（移植）
├── probe/src/main/kotlin/ProbeMain.kt   断言脚本（不是 JUnit，给结论用）
└── run-probe.sh              编译 + 回归，退出码即结论
```

## 跑法

```bash
bash ios-kmp/run-probe.sh
```

依赖本机已有的 `C:/Android/jdk17` 与 `C:/Android/kotlinc19`（Android 主链同一套），
不需要 Gradle、不需要 macOS、不需要联网。

注意：Kotlin 1.9.24 的编译器解析不了 JDK 25 的版本号，脚本里写死用 JDK 17。

## 覆盖了什么

共享层断言 **230 项全绿**（`bash ios-kmp/run-probe.sh`）：

| 组 | 内容 | 数量级 |
|---|---|---|
| 日期内核 | epoch day ↔ 年月日往返 5 万天、闰年、非法日期、星期 | 50,004 天 |
| 周次 | 归一到周一、开学前后边界、20 周单调性、clamp | 20 周 × 2 端点 |
| 周次表达式 | 区间/离散/单双周/全角逗号/顿号/写反/无「周」字/空串 | 14 组 |
| 校历 | 学期节点、月历网格、教学周角标 | 全量 |
| 真实课表解析 | `docs/diagnostics/bjkbdy-kb-sample.json`（14 条记录 → 11 门课） | 全字段 |
| 成绩解析 | 真实样本 + 文本成绩（优秀/合格/缓考） | 全字段 |
| 选课 / 推荐课表 | 选课窗口解析、四级级联（`jg_id`/`jgdm` 兼容）、课表修补、JSON 嵌套保留 | 真实样本 |
| 公交 | 车来了签名 / 解密 / 拆壳、掌上公交解析、站点 / 线路 / 时刻表 / 地理 | 159 项相关 |
| 配色 | 11 门课拿到 11 种不同颜色，索引在 24 色板内 | — |
| 平台无关性 | `String.hashCode` 与 JVM 一致（配色初值依赖它） | — |

## 三条移植决策（和原实现不同，但等价）

1. **JSON 不换 kotlinx.serialization，改做兼容层。**
   教务返回的是字段动态、类型混杂的脏 JSON。换 `@Serializable` 意味着把 4,700 行
   解析代码全部重写；写个 300 行的 `Js` 兼容层，解析代码一行不动。
2. **日期不用 Calendar/kotlinx-datetime，改 epoch day 整数运算。**
   Android 那边要靠 `Math.round(diff / 86400000.0)` 消除时区/夏令时的 ±1 小时；
   直接算「差多少天」就没有这个问题。代价是 `SimpleDateFormat` 得自己实现两个格式化。
3. **今天的时间由外面注入**（`WeekMath.todayProvider`），共享层不碰系统时钟。
   Android 侧给 `java.time.LocalDate.now()`，iOS 侧给 `Date()`，测试里给固定日期。

## iOS App 现状（已与安卓功能对齐）

`app-ios/` 是完整 App，不是探针。SwiftUI 负责界面，Kotlin 共享层负责协议 / 解析 / 算法：

- **底部导航**：原生 `TabView` 四 tab（首页 / 课表 / 成绩 / 我的），对齐安卓 MainActivity。
- **教务**：RSA 登录、`http`/`https` 自动探测（无手动开关）、首次登录自动同步、
  之后只在我的页手动同步、http 失败全链路降级。
- **课表 / 成绩**：周网格 + 8 色编辑、停调课、隐藏教务课、回到本周；成绩学期筛选、绩点汇总。
- **校历 / 选课 / 推荐课表**：月历教学周、选课窗口提示、四级级联推荐课表。
- **实时公交**：车来了 + 掌上公交双数据源，附近 / 搜索 / 站详 / 线详 / 时刻表 / 收藏 /
  乘车码 / 单车跟随 / 呼吸光晕，全部对齐安卓。
- **App 内嵌网页**：`WKWebView` 打开教务 / 学工 / GitHub / Gitee / 版本发布页，
  Cookie 与 URLSession 双向共享、https→http 降级、网页后退。

**版本号**在 `ios-kmp/app-ios/project.yml` 的 `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` 维护，
与安卓 `native/version.properties` 对齐（当前 4.0.7）。

## 云构建出包（没有 Mac）

`codemagic.yaml` 的 `ios-app` 工作流：Gradle 编 `Shared.framework` → XcodeGen 生成工程 →
xcodebuild 不签名 → 打包未签名 ipa。用 `python _tools/codemagic.py run ios-app main` 触发，
`watch <buildId>` 等结果，产物在 Codemagic 的 `build.artefacts`。

ipa 未签名，装机需自行重签（TestFlight / 企业分发 / AltStore 等），Bundle ID `com.tstc.zstangshi.app`。

本地可跑的检查：

- `bash ios-kmp/run-probe.sh` —— 230 项共享层断言；
- `python ios-kmp/verify_rsa_der.py` —— RSA SPKI 拼装与加解密往返；
- `python ios-kmp/preflight.py` —— 工程结构（括号 / plist / 路径）。它**不能**替代编译器，
  SwiftUI 的类型只能在云构建里验。

