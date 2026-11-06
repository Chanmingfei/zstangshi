# 掌上唐师

唐山师范学院教务系统移动端。同一套 H5 业务代码，两个原生壳：Android（原始工程）与 iOS（本仓库新增）。

```
.
├── web/           H5 业务代码（唯一真源，两端共用，一行未改）
├── android/       原 Android 工程（WebView 壳 + 9 个 @JavascriptInterface）
├── ios/           iOS 工程（WKWebView 壳 + 等价桥接层）
├── docs/          架构、接口、验收文档
├── tools/         解析器测试与规则守卫脚本
└── .github/       GitHub Actions：在 macOS runner 上编译 ios
```

## 拿到 iOS 安装包（不需要 Mac）

`.ipa` 里的可执行文件是 arm64 Mach-O，只能由 Apple 的 iOS SDK 编译，而 SDK 只随 Xcode 发布、Xcode 只有 macOS 版。所以本仓库用 GitHub 的 macOS runner 代编译：

1. 仓库 → **Actions** → 左侧 **Build iOS IPA** → 右上 **Run workflow**
2. 等 5–10 分钟，进入该次运行，页面底部 **Artifacts** 下载 `ZhangShangTangShi-unsigned-ipa`
3. 解压得到 `ZhangShangTangShi-1.2.0-unsigned.ipa`

> **如果 Actions 起不来**，报 `The job was not started because your account is locked due to a billing issue`：
> 这是 GitHub 账号的计费锁（连免费的 ubuntu runner 都会被拒），与本仓库无关。
> 两种解法：
> - 去 [github.com/settings/billing](https://github.com/settings/billing) 处理付款信息（公开仓库的 macOS 分钟数本身是免费的，只是需要有有效的付款方式）
> - 或者完全绕开 GitHub Actions：用 [codemagic.io](https://codemagic.io)（GitHub 登录，免费档 500 分钟/月 macOS，不需信用卡）导入本仓库，它会自动识别根目录的 `codemagic.yaml`，点 Start new build 即可，产物同样在构建页下载


## 装到 iPhone 上

下载的 ipa 是**未签名**的，iOS 不允许安装未签名可执行文件，需要用你自己的 Apple ID 重签一次。Windows 上做这件事：

1. 装 [Sideloadly](https://sideloadly.io/)（Windows 版）；它依赖 iTunes 的驱动，没装 iTunes 就装 [Apple 设备驱动](https://support.apple.com/zh-cn/106372)
2. iPhone 用数据线连电脑，首次连接在手机上点「信任」
3. Sideloadly 里把 ipa 拖进去，Apple ID 填你的，点 Start，输入密码（如开了双重认证，用 App 专用密码）
4. 手机上 **设置 → 通用 → VPN 与设备管理** → 信任该开发者
5. 免费 Apple ID 签的包 **7 天到期**，到期重签一次即可；开发者账号（￥688/年）可签 1 年

> 只有 7 天是因为免费账号的限制，与本 App 无关。想长期用可以考虑 AltStore（自动续签）。

## 本地构建（有 Mac 时）

```bash
bash ios/scripts/build-ipa.sh                          # 未签名 ipa
bash ios/scripts/build-ipa.sh --team <TEAM_ID>         # 已签名 ipa（需开发者账号）
bash ios/scripts/build-ipa.sh --simulator              # 只看效果，不产 ipa
```

## 校验

```bash
bash ios/scripts/sync-web.sh --check   # Resources/www 与 web/ 是否一致
node ios/scripts/verify-project.mjs    # Xcode 工程结构：对象引用 / 编译阶段 / 磁盘路径
node ios/scripts/test-bridge.mjs       # 桥协议 43 项（含 RSA 与 node:crypto 互通）
node ios/scripts/test-shell-smoke.mjs  # 真实浏览器按 iPhone 视口渲染，逐 Tab 断言
```

## 两点必须知道的实现约束

- **本地服务端口固定 18765**。`localStorage` 按「scheme + host + port」隔离，端口一变，主题、记住的学号、课表缓存全丢。
- **HTTP 请求手动跟随重定向**，不能交给 URLSession。正方教务登录是两级 302、POST 会换发新 `JSESSIONID`，自动跟随会沿用旧 Cookie 被静默踢回登录页。

更多 iOS 侧细节见 [`ios/README.md`](ios/README.md)。
