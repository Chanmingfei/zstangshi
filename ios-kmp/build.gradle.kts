// 根工程只声明插件版本，不应用；各模块自己 plugins {} 应用。
//
// 版本选择依据（官方兼容表 kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide）：
//   2.4.20–2.4.21 ↔ Gradle 7.6.3–9.7.0 ↔ Xcode 26.4
// 云构建固定 Xcode 26.4 / Gradle 8.14，不使用「latest」，避免哪天 runner 升级把链路搞挂。
plugins {
    kotlin("multiplatform") version "2.4.21" apply false
}
