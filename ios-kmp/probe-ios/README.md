# iOS 探针（P0 后半关）

一个**只做一件事**的 App：拿真学号密码去登教务，然后把每一步的真实结果打在屏幕上。

它存在的理由：iOS 上有三件事在 Windows 上验证不了，而它们任一失败都会表现为「登录不上」，
现象完全一样，不查日志就只能瞎猜。

| 要验证的 | 失败时的典型表现 | 探针怎么暴露它 |
|---|---|---|
| ATS 放行 `http://jwxt.tstc.edu.cn` | 报「网络错误」或超时 | 单独发一次 http 请求，直接把 HTTP 状态码/错误码打出来（-1022 会翻译成中文） |
| 登录 302 沿途的 `Set-Cookie` 有没有留住 | 报「学号或密码不正确」 | 打 Cookie 名称清单 + 菜单页是否含 `gnmkdm` |
| `SecKeyCreateWithData` 接受我们拼的 DER 公钥 | 报「密码加密失败」 | 打 modulus 位数、密文字节数（应等于模长） |

## 为什么不用 KMP 起手

这三件事全是 iOS 平台行为，跟 Kotlin 无关。用纯 Swift 做，工程里只有 4 个源文件、
不引 Gradle、不引 KMP 插件、不需要 App Group —— 一次云构建就能出结论。
等这一关过了，再把结论搬进 `shared/iosMain`，那时出问题只可能是搬的过程，好定位。

顺带说明：`shared` 里那套纯逻辑已经用 `run-probe.sh` 在本机验过 80 项，
RSA 的 DER 拼装也用 `verify_rsa_der.py` 借 openssl 验过了 —— 所以这次云构建
如果失败，范围已经缩得很小。

## 出包（没有 Mac）

1. 打开 [codemagic.io](https://codemagic.io)，用 GitHub 登录；
2. **Add application** → 选 `Chanmingfei/zstangshi`；
3. 它会自动读到仓库根目录的 `codemagic.yaml` → **Start new build** → 选 `ios-probe`；
4. 约 10 分钟，在 Artifacts 下载 `zstangshi-probe-unsigned.ipa`。

## 装机（免费 Apple ID）

1. Windows 装 [Sideloadly](https://sideloadly.io/)（需要 iTunes 的驱动组件）；
2. 拖入 ipa，`Apple account` 填你的 Apple ID，点 Start；
3. 手机上 **设置 → 通用 → VPN与设备管理**，信任你的开发者证书；
4. 打开「唐师探针」。

> 免费账号：**7 天后失效**，需要重签；每台设备最多 3 个自签 App。
> 这个包不需要任何 capability，所以重签不会因为 entitlement 失败。

## 怎么看结果

成功时大致长这样：

```
✓ http 直连 → 200，21876 字节
✓ 登录页 → 200，21876 字节
✓ csrftoken：a1b2c3d4e5f6…
✓ 公钥 → 200，modulus 344 字符 / exponent 010001
✓ modulus 去符号位后 256 字节 → 2048 位
✓ 密文 256 字节（应等于模长 256），base64 344 字符
✓ 响应 → 302，0 字节
✓ 页内含「用户名或密码不正确」：false
✓ 菜单页 → 200，xxxxx 字节，含 gnmkdm=true，被踢回登录=false
✓ 课表页 … → 200，xnm=2026，xqm=3
✓ 课表接口 → 200，xxxxx 字节
✓ kbList 记录数 ≈ 14；前几门：网络零售与贸易、商务智能、大数据分析
```

对照下面这张表判断卡在哪：

| 现象 | 结论 | 下一步 |
|---|---|---|
| `http 直连失败：ATS 拒绝了明文 HTTP（-1022）` | Info.plist 的例外没生效 | 检查 `NSExceptionDomains` 是否覆盖 `tstc.edu.cn`（含子域） |
| `https 失败：超时(-1001)` + `http 直连 → 200` | 校外 443 不通，属预期 | 说明 http 兜底是必需功能，不能砍 |
| `csrftoken：未找到` | 页面结构变了，或拿到的其实是错误页 | 日志会附前 200 字原文，看是不是跳转页 |
| `SecKeyCreateWithData / 加密失败` | DER 拼装问题 | 本机 `verify_rsa_der.py` 已经验过，那多半是 modulus 不是标准 base64 |
| `密文 xxx 字节` 与模长不等 | 密钥长度判断错 | 看「modulus 去符号位后」那一行 |
| 登录响应含「用户名或密码不正确」 | 密码/学号真错，或加密结果被服务端判非法 | 换正确密码再试一次 |
| 菜单页 `含 gnmkdm=false` 或 `被踢回登录=true` | **Cookie 跨 302 丢失** | 这是 KMP 移植时最需要小心的一处，见下 |

最后一条是这次探针最重要的产出：如果它在纯 Swift + 共享 Cookie 存储下都丢了，
那换成 Ktor/NSURLSession 之后更要注意，得显式保存跳转链上的 Set-Cookie。

## 隐私

- 密码只在内存里，不落盘、不进日志；
- 日志只打 Cookie 的**名字**，不打值；
- 不上传任何东西，全部请求直连学校教务。

## 已知限制

- 只验「能不能登上、能不能取到课表」，不验 UI、不验通知、不验小组件；
- 学号密码是明文输入框（探针专用，正式版要进 Keychain）；
- 没有做错误重试，一次失败就是一次失败的日志。
