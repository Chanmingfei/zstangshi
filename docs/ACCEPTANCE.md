# 验收标准（ACCEPTANCE）

> 对照 `docs/PRD.md` 的 F-01…F-17 与 11 个页面。自动化覆盖项见 `npm run verify`
> （教学周 214 / GPA 53 / 教务解析 84 / 页面契约 35 / 五条规则守卫）。
> 标注「需真机」的条目因本环境无 Android SDK / 无浏览器端到端，由用户在真机或桌面浏览器自查。

## 0. 工程级（已在本环境自动化验证）

| # | 验收点 | 怎么验 | 状态 |
|---|---|---|---|
| 0.1 | 11 个路由文件 + 18 个样式 link 全部存在、import 图零断链 | `node tools/test-pages.mjs`（35 断言） | ✅ 通过 |
| 0.2 | 零颜色字面量 / 零外链 / 单文件 ≤400 行 / 校历副本 deepEqual / 对比度 ≥4.5 | `node tools/check-rules.mjs`（5 条守卫） | ✅ 通过 |
| 0.3 | 教学周算法（15 对表 / 40 日期 / 文案 / 42 格 / 边界） | `node tools/test-week.mjs`（214 项） | ✅ 通过 |
| 0.4 | GPA 3.16 / 学分树 21 节点 / 计划 99 门通过 82 门 | `node tools/test-gpa.mjs`（53 项） | ✅ 通过 |
| 0.5 | 教务端点解析（kbList/成绩/GPA/学期码/路由） | `node tools/test-jwxt-parse.mjs`（84 项） | ✅ 通过 |

## 1. 页面级

| 页面 | 路由 | 关键验收点 | 覆盖方式 |
|---|---|---|---|
| 登录 | `/login` | 学号+密码表单；未登录访问受保护页跳转登录；登录失败有 toast；演示态可直接进 | 逻辑同 jwxtClient 解析测试 |
| 首页 | `/`(tab) | 顶栏显示「学年/学期/第 N 教学周」；6 入口宫格（成绩/课表/选课/推荐课表/校历/学工）；stagger 入场 | 需真机/浏览器 |
| 课表 | `/timetable`(tab) | 周视图网格；冲突角标；进行中课程高亮；周次切换；详情层左右滑动 | 需真机/浏览器 |
| 成绩 | `/grades`(tab) | 学期切换；列表展示；点进详情 | 需真机/浏览器 |
| 成绩详情 | `/grade/:id` | 成绩构成无数据不渲染；备注空不占行；来源标注「教务未提供」 | 代码已落盘（P1②③②） |
| 推荐课表 | `/recommend` | 周课表视图（P08 口径：教务「推荐课表打印」） | 需真机/浏览器 |
| 选课 | `/selection` | 轮次卡+倒计时；已选/学分统计；搜索+筛选；余量三档；冲突角标；选退走 `toggleSelection` 失败 toast | 需真机/浏览器 |
| 校历 | `/calendar` | 月历 42 格+周次角标+今日描边+考试周；19 周列表；关键节点；来源纯文本 | 算法同 test-week |
| 学工系统 | `/affairs` | 说明+地址纯文本+复制+系统浏览器打开；明确不假嵌 WebView | 需真机/浏览器 |
| 我的 | `/mine` | 数据源状态；主题切换；清缓存；退出登录；常用入口 | 需真机/浏览器 |
| 错误页 | `*` | 任意未匹配路由有兜底页，不白屏 | 需真机/浏览器 |

## 2. Android 封壳（T14）—— 需真机自查

| # | 验收项 | 怎么查 | 本环境状态 |
|---|---|---|---|
| ① | 包能编出来 | `bash android/build-manual.sh` 产出 `android/build/app-debug.apk` | ✅ 已产包（aapt2/javac/d8/apksigner 全绿） |
| ② | 冷启动无白屏 | 装机冷启动看首帧（splash 品牌色）；`chrome://inspect` 看 `https://app.local/index.html` 的 `.js` 为 `text/javascript`、无 CORS/MIME 报错 | 需真机 |
| ③ | `isNative()===true`；`httpRequest` 对教务返回结构化结果或超时错误（不崩） | DevTools 执行 `window.NativeBridge.isNative()`；切教务触发登录看 Network/Console | 需真机 |
| ④ | 登录两级 302 Cookie 维护 | 登录后连续两次 GET 教务，第二次不应 302 回 `login_slogin.html` | 需真机 |
| ⑤ | 硬件返回键 | 次级页返回列表、Tab 根页退出 App（`window.__appBack()` 返回 false 才退出） | 需真机 |
| ⑥ | 断网打开学工 15s 兜底 | 关网→进学工→应显示「网络不可用或需校园网」兜底+重试，不白屏 | 需真机 |

## 3. 诚实边界

- 自动测试覆盖的是**算法与静态契约**（教学周、GPA、解析、import 图、规则守卫）。
- 交互/视觉/真机行为（课表渲染、登录 302、RSA 往返、硬件返回、断网兜底）**未在本环境实测**，
  依赖用户在真机或桌面浏览器（`node server.js` 起本地静态服务）自查。
- 选课课池（`ENDPOINTS.selectionData`）与推荐课表预览网格解析仍属**待实测**，App 会诚实降级到演示数据，
  不伪造地址。
