plugins {
    kotlin("multiplatform")
}

kotlin {
    // 不写 jvmToolchain()：云机器上 JDK 由 Codemagic 的 `java: 17` 提供，
    // 写死会触发 toolchain 自动下载（需要 foojay resolver 插件），反而容易失败。

    // jvm 目标有两个用处：
    //   1) 在 Windows 上跑 run-probe.sh 的同一批断言（不依赖 macOS）；
    //   2) 把 commonMain 当作普通 Kotlin 库编一遍，快速发现语法/类型问题。
    jvm()

    // iOS 真机 + Apple Silicon 模拟器。静态 framework 更省事：
    // 不用管动态库嵌入与签名，Sideloadly 重签时少一个变量。
    val iosTargets = listOf(iosArm64(), iosSimulatorArm64())
    iosTargets.forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.get().dependencies {
            // 目前 shared 只用 Kotlin 标准库：不引 kotlinx、不引 Ktor。
            // 原因见 ios-kmp/README.md「三处移植决策」——JSON 用自带兼容层、
            // 日期用 epoch day、网络留给平台层，这样 Android 的手写构建链还能用。
        }
    }
}
