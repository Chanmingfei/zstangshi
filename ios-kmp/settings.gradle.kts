// Kotlin Multiplatform 官方工程骨架。
// 只有 :shared 一个模块：iOS framework 由它产出，Android 侧暂时继续用 native/build.sh
// （aapt2 + kotlinc 1.9.24 + d8），因为两条链路目前还能共存 —— commonMain 里没有
// expect/actual，1.9 和 2.x 都能编。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "zstangshi-ios"
include(":shared")
