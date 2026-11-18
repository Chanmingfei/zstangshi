<div align="center">

<img src="../../docs/assets/school-logo.png" width="72" alt="唐山师范学院校徽" />

# 掌上唐师 · 鸿蒙专版源码树

**`harmony-android/native/` —— 构建入口与版本真源都在这层**

<p>
  <img src="https://img.shields.io/badge/Version-4.1.0--harmony-CF0A2C?style=flat" alt="Version" />
  <img src="https://img.shields.io/badge/versionCode-64-CF0A2C?style=flat" alt="versionCode" />
  <img src="https://img.shields.io/badge/Kotlin%20files-64-7F52FF?style=flat&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Runtime%20deps-0-3DA639?style=flat" alt="Runtime dependencies" />
</p>

<p>
  <a href="../README.md">← 鸿蒙专版说明</a> · <a href="../../README.md">项目主页</a>
</p>

</div>

---

> 这层没有独立的功能说明。**为什么要有这个版本、实况窗怎么实现、改了什么、怎么装**，
> 都在上一级 [`harmony-android/README.md`](../README.md) 里。
> 这里只记构建与目录本身。

---

## 🔨 构建

```bash
cd harmony-android
bash native/build.sh
```

产物：`harmony-android/android/build/app-debug.apk`（往上一层的 `android/`，不是仓库根的 `android/`）。

需要 JDK 17 + Android SDK `build-tools;34.0.0` + `platforms;android-34` + Kotlin 编译器 1.9.24。

```bash
bash native/typecheck.sh     # 只做类型检查
```

> **⚠️ 路径不能含中文** —— `aapt2` 会报 `failed to open directory`。

## 🔢 版本号

唯一真源是 [`version.properties`](version.properties)：

```properties
versionCode=64
versionName=4.1.0-harmony
```

`build.sh` 里 `aapt2 link` 会把它同时写进 manifest 与包信息，不要在别处再改一份。

## ✅ 出包后自检

```bash
node ../scripts/check-java-api.js build/dex/classes.dex
```

扫 dex 方法引用里的 **Java 9+ API** —— 鸿蒙兼容层的 `core-oj.jar` 只到 Java 8，
用了 `URLEncoder.encode(String, Charset)` 这类重载就会在真机上报
`No static method …`。脚本输出 `✓ 没有发现 Java 9+ 的 API 引用` 才算过。

## 🏗️ 目录

```text
native/
├── build.sh                  aapt2 + kotlinc + d8 + apksigner
├── typecheck.sh              只做类型检查
├── version.properties        4.1.0-harmony (64)
└── app/src/main/
    ├── AndroidManifest.xml   android:name=".harmony.ZstsApp"
    ├── res/                  字符串 / 颜色 / 图标 / 布局（41 个文件）
    └── kotlin/com/tstc/zstangshi/   64 个 .kt，24,725 行
        ├── *.kt              Activity 壳（8 个）
        ├── harmony/          CrashGuard + ZstsApp（本版新增）
        ├── notif/            课程实况窗：HarmonyLive / Api36 / 计划 / 构建 / 调度
        ├── pages/ ui/ widget/  主页面、自绘控件、桌面小组件
        └── jw/ bus/ data/    教务、公交、课表与存储
```

模块级行数分布与自绘控件实现见标准版源码说明
[`../../native/README.md`](../../native/README.md) —— 两份业务代码同源。

---

本项目以 **GPL-3.0** 协议开源，全文见 [LICENSE](../../LICENSE)。
