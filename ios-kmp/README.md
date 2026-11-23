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

| 组 | 内容 | 数量级 |
|---|---|---|
| 日期内核 | epoch day ↔ 年月日往返 5 万天、闰年、非法日期、星期 | 50,004 天 |
| 周次 | 归一到周一、开学前后边界、20 周单调性、clamp | 20 周 × 2 端点 |
| 周次表达式 | 区间/离散/单双周/全角逗号/顿号/写反/无「周」字/空串 | 14 组 |
| 真实课表解析 | `docs/diagnostics/bjkbdy-kb-sample.json`（14 条记录 → 11 门课） | 全字段 |
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

## 下一关（P0 剩下的部分）

`probe-ios/` 是这一关：一个纯 SwiftUI 探针，只做「登录 + 打日志」。

- **ATS 放行 `http://jwxt.tstc.edu.cn`**：Info.plist 里给 `tstc.edu.cn` 开子域例外；
- **302 沿途 Set-Cookie 累积**：共享 `HTTPCookieStorage` + `.always` 接受策略；
- **`SecKeyCreateEncryptedData` 接受教务的 modulus/exponent**：`RSASPKI.swift` 手拼 SPKI。

其中第三项不用等云构建 —— `python ios-kmp/verify_rsa_der.py` 会用 openssl 现场生成密钥、
校验拼出来的 DER 能被解析，并跑一遍 PKCS#1 v1.5 加解密往返。

工程结构检查：`python ios-kmp/preflight.py`（括号平衡、plist 合法性、路径、ASCII 可执行名）。
它**不能**替代编译器 —— SwiftUI 的类型只能在云构建里验。

出包与装机的完整步骤见 [`probe-ios/README.md`](probe-ios/README.md)。

