# 正方教务实测报告 · 唐山师范学院

> **本文替换先前基于推测的 `remote-api-map.md` 内容，以实测为准。**
> 实测时间：2026-11-05 23:50 GMT+8（第一轮）、2026-11-06 00:36 GMT+8（第二轮：菜单点击 + 学业情况页）
> 实测环境：用户本机（已登录校园网），通过 Node 脚本 + 用户 Edge 浏览器双向验证
> ⚠️ 全文不含任何账号密码，凭据仅用于本次验证，未写入仓库。
>
> **第二轮最大收获**：菜单项可以通过 DOM click 展开并点击（原先判断「hover 触发、自动化工具做不到」是错的），因此拿到了两条真实页面地址与全套菜单树。
>
> **第三轮（2026-11-06 12:20 GMT+8）：本地代理直连全链路实测** —— 见 §6。程序化登录**已打通**，
> 根因是代理跟随重定向时丢了新会话 Cookie（不是密码、也不主要是多节点）；首页菜单里全部
> `gnmkdm`、成绩/课表/推荐课表的数据接口、正方学期码口径均已实测。§3.1 / §3.1b / §3.3 的
> 旧结论已被 §6 取代，保留原文仅作排查过程存档。

---

## 0. 关键更正

| 项 | 先前推测 | **实测事实** |
|---|---|---|
| 域名 | 旧记录：`jwxt` + 数字 3 后缀 | ❌ 该主机 **DNS 不存在**（校内 DNS 211.81.200.5 返回 Non-existent domain）。真实域名是 **`jwxt.tstc.edu.cn`** |
| 协议 | `http://` | **`https://`**（HTTP 也能连通，但浏览器会话在 HTTPS 上建立；用 HTTP 跑登录流程会被弹回登录页） |
| 系统 | 正方 jwglxt | ✅ 确认，`Tengine/3.1.0` + Shiro（`rememberMe=deleteMe` 特征） |
| 部署 | 单机 | 多节点负载均衡（响应头 `backendip: 172.29.1.172/173/175`，靠 `route` Cookie 固定后端） |

教务系统入口来源：教务处首页 `https://jwc.tstc.edu.cn/` 顶部导航「教务管理系统」→ `http://jwxt.tstc.edu.cn/`。

---

## 1. 已实测确认（可直接用于实现）

### 1.1 登录页

```
GET https://jwxt.tstc.edu.cn/            → 302 → /xtgl/login_slogin.html
GET https://jwxt.tstc.edu.cn/xtgl/login_slogin.html → 200，约 20.9 KB
```

- 表单：`<form action="/xtgl/login_slogin.html" method="post">`
- 关键字段：`yhm`（学号）、`mm`（密码，页面上有两个同名 input：`#hidMm` 与 `#mm`，提交时都带密文）、`csrftoken`、`language=zh_CN`
- `csrftoken` 格式：`<uuid>,<uuid去横线>`，例如 `fb7100d0-f9f5-458e-b29b-70918243f509,fb7100d0f9f5458eb29b70918243f509`（**完整值整体提交，两个部分都要**）
- 另有 15 个隐藏开关：`dlmmsfknt / ydappsfbldlzt / yzcskz / mmsfjm / mmsrddcshkzfs / dlsbsdsj / sfxswechatlogin / scene_id / authJwglxtLoginURL / rzzxxs / rzys / sfzywqh / xxdm / userPolicyOn / dxsyrz / ydType`，实测**值全为空串**
- **首次登录无验证码**（页面中验证码区块被注释为 `<!-- 0-弹出验证码-->`，`#myDiv` 内只有「忘记密码」链接）
- 登录前会 `POST /xtgl/login_logoutAccount.html`（参数 `csrfTokenLogout`）清除残留会话，随后 `document.forms[0].submit()`（见 `/js/globalweb/login/login.js`）

### 1.2 公钥

```
GET https://jwxt.tstc.edu.cn/xtgl/login_getPublicKey.html?time=<13位时间戳>
→ 200 {"modulus":"AJDhV/Pu…（172 字符 Base64）","exponent":"AQAB"}
```

- `modulus` Base64 解码为 129 字节（首字节 `0x00`），即 **1024 位 RSA**
- `exponent` = `AQAB` = **65537**

### 1.3 密码加密（已确认，无需字节反转）

`/js/globalweb/login/login.js` 中的原始逻辑：

```js
if ($("#mmsfjm").val() == '0') {
    $("#hidMm").val($("#mm").val());            // 明文
} else {
    var rsaKey = new RSAKey();
    rsaKey.setPublic(b64tohex(modulus), b64tohex(exponent));
    var enPassword = hex2b64(rsaKey.encrypt($("#mm").val()));
}
```

- 用的是 **jsbn（Tom Wu）** 的 RSA 实现（`/assets/plugins/crypto/rsa/{jsbn,prj4,rng,rsa,base64}.js`）
- `b64tohex` 是**标准 Base64→hex，无字节序反转**（此前担心的反转问题**不存在**）
- `rsaKey.encrypt` 采用 **RSAES-PKCS1-v1_5** padding
- ⇒ **等价于 Java 的 `Cipher.getInstance("RSA/ECB/PKCS1Padding")`**，原生侧方案成立
- `mmsfjm` 实测为空串，`'' == '0'` 为 false ⇒ 走加密分支

### 1.4 账号可正常登录（浏览器验证）

通过 Edge 浏览器手工填表登录，**成功**，跳转：

```
https://jwxt.tstc.edu.cn/xtgl/index_initMenu.html?jsdm=xs&_t=…&echarts=1
```

`jsdm=xs` 表示学生角色。登录后首页显示：

- 姓名：陈铭霏　角色：学生
- 院系班级：**文学院 23中文本科4班(公费师范)**

### 1.5 已实测的页面地址（可直接用于实现）

| 页面 | 地址 | 渲染方式 |
|---|---|---|
| 登录 | `https://jwxt.tstc.edu.cn/xtgl/login_slogin.html` | 服务端 HTML + JS 提交 |
| 系统首页 | `https://jwxt.tstc.edu.cn/xtgl/index_initMenu.html` | 服务端框架 + iframe 内容区 |
| **个人课表查询** | `https://jwxt.tstc.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default` | 页内 AJAX（`xskbcx_cxXsKb.html`） |
| **学生学业情况查询** | `https://jwxt.tstc.edu.cn/xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=N105515&layout=default` | **服务端渲染**，学分树直接写在 HTML 里 |
| **教学执行计划查看** | `https://jwxt.tstc.edu.cn/jxzxjhgl/jxzxjhck_cxJxzxjhckIndex.html?gnmkdm=N153540&layout=default` | **服务端渲染** 主表格 + 4 个 tab |

地址规律：`/<模块英文缩写>/<页面>_<动作>.html?gnmkdm=N<6位功能码>&layout=default`。
「我的应用」里的图标点击会**新开一个标签页**打开目标页面（不是在 iframe 里刷新），所以拿地址最省事的办法是点一下再看新标签的 URL。

两个页面都在「信息查询」二级菜单里，均可通过 `dom_cua_click` 点击菜单项进入——菜单是 `href="javascript:void(0)"`，点击后由 JS 把内容加载进 iframe，但**主框架 URL 不变**，需读 iframe 的 `src` 或直接按上表地址跳转。

**登录失败特征**：登录 POST 被拒后会回到 `login_slogin.html?time=<13 位毫秒时间戳>`；成功则跳 `index_initMenu.html`。这个 `?time=` 是判断登录成败最可靠的信号。

**注意**：登录页密码框初始 `type=text`，点「显示密码」后才变 `password`。看 DOM 快照时不要以为填错了。

### 1.6 「学生学业情况查询」页面内容（逐字实测）

该页面是 **GPA 与学分构成的唯一权威来源**，App 的学分绩点页直接读它，**不做任何本地加权计算**。

顶部原文：

```
陈铭霏 同学，您的课程修读情况（供参考）：（统计时间2026-11-06 00:36:27之前有效）当前所有课程 平均学分绩点（GPA）： 3.16 计划总课程 99 门 通过 82 门，未通过 0 门；未修 11 门；在读 6 门！计划外：通过 0 门，未通过 0 门
```

学分构成树（缩进=层级）：

```
主修
  必修课  要求学分:27.75  获得学分:123.0  未获得学分:0.0  并且
    必修课公共部分  要求学分:0.0  获得学分:117.5  未获得学分:0.0  要求门次:2  到达要求:.60  未达到要求:.0  共（89）门 通过（80）门
    必修课方向部分  要求学分:0.0  获得学分:5.5  未获得学分:0.0  要求门次:2  到达要求:.4  未达到要求:.0  缺省
      公费师范生方向  要求学分:0.0  获得学分:5.5  未获得学分:0.0  要求门次:2  到达要求:.4  未达到要求:.0
  限选课  要求学分:0.0  获得学分:5.5  未获得学分:0.0  并且
    限选课公共部分  要求学分:0.0  获得学分:5.5  未获得学分:0.0  要求门次:2  到达要求:.3  未达到要求:.0  共（4）门 通过（3）门
    限选课方向部分  要求学分:0.0  获得学分:0.0  未获得学分:0.0  要求门次:2  到达要求:.0  未达到要求:.2  或者
      公费师范生方向  要求学分:0.0  获得学分:0.0  未获得学分:0.0  要求门次:2  到达要求:.0  未达到要求:2  共（0）门 通过（0）门
  任选课  要求学分:21.0  获得学分:10.5  未获得学分:10.5  共（14）门 通过（7）门
  人文社会与科学素养选修课  要求学分:11.0  获得学分:16.0  未获得学分:0.0  并且
    人文社科类限选课  要求学分:8.0  获得学分:12.0  未获得学分:0.0
      外语技能与信息技术类  要求学分:3.0  获得学分:6.0  未获得学分:0.0  共（2）门 通过（2）门
      艺术修养类  要求学分:2.0  获得学分:3.0  未获得学分:0.0  共（1）门 通过（1）门
      交叉融合类  要求学分:2.0  获得学分:2.0  未获得学分:0.0  共（1）门 通过（1）门
      礼仪与沟通类  要求学分:1.0  获得学分:1.0  未获得学分:0.0  要求门次:1  到达要求:1  未达到要求:0  共（1）门 通过（1）门
    人文社素养任选课  要求学分:3.0  获得学分:4.0  未获得学分:0.0  共（2）门 通过（2）门
  教师教育非必修课  要求学分:1.0  获得学分:2.0  未获得学分:0.0  共（1）门 通过（1）门
资格证书信息
其他课程  共（0）门 通过（0）门
创新创业情况尚未数据信息
```

页脚原文：`提示：此页面信息仅做学业修读情况参考。`
图例：已修 / 在读 / 未修 / 未过 / 学分已满 / 学分超出 / 学分未满 / 课程替代 / 节点未过

**该页面没有的东西（不要凭空设计）**：逐学期 GPA、专业排名、总学分合计。

**为什么不能本地算**：实测按成绩加权得 **3.07**，教务权威值是 **3.16**，差 0.09。学校口径对公选课不计入总绩点、重修取首次成绩、五级制成绩单独折算。⇒ 一律以教务为准。

### 1.7 「教学执行计划查看」页面内容（逐字实测）

**主表格（大类专业信息 tab）**，筛选条件 `年级=2023 / 学院=文学院 / 专业=全部`，`计划任务学年: 2026-2027 学期: 1`：

| 年级 | 专业代码 | 专业 | 大类标识 | 任务标记 | 计划人数 | 校区 | 课程数 | 专业方向个数 | 班级个数 | 最低毕业学分 |
|---|---|---|---|---|---|---|---|---|---|---|
| 2023 | 1301 | 汉语言文学 | 专业 | 班级 | 154 | 大学道北校区 | 97 | 2 | 4 | **100** |

> ⚠️ **最低毕业学分是 100，不是 160。** 演示数据里编的「160 学分 / 6 模块」是错的。

页面有 4 个 tab：`大类专业信息` / `修读要求` / `课程信息` / `执行计划预览`。
后三个 tab **必须先勾选主表格里的那条记录**，否则弹「请选定一条记录！」。

**修读要求 tab（培养方案树）**，表头 `年级: 2023 专业: 汉语言文学`：

```
主修
  必修课  要求最低学分:27.75  课程总学分:149   并且
    必修课公共部分  要求最低学分:0  课程总学分:139.5  最低门数:2    [课程详情（69门）]
    必修课方向部分  要求最低学分:0  课程总学分:9.5    最低门数:2    [或者]
      普通师范生方向  要求最低学分:0  课程总学分:4     最低门数:2   [课程详情（3门）]
      公费师范生方向  要求最低学分:0  课程总学分:5.5   最低门数:2   [课程详情（4门）]
  限选课  要求最低学分:0  课程总学分:6.5   并且
    限选课公共部分  要求最低学分:0  课程总学分:6.5  最低门数:2   [课程详情（4门）]
    限选课方向部分  要求最低学分:0  课程总学分:0    最低门数:2   [或者]
      普通师范生部分  要求最低学分:0  课程总学分:0  最低门数:2   [课程详情（0门）]
      公费师范生方向  要求最低学分:0  课程总学分:0  最低门数:2   [课程详情（0门）]
  任选课  要求最低学分:21  课程总学分:21   [课程详情（14门）]
  人文社会与科学素养选修课  要求最低学分:11  课程总学分:14   并且
    人文社科类限选课  要求最低学分:8  课程总学分:11
      （其下 4 个子类与学业情况页一致：外语技能与信息技术类 3 / 艺术修养类 2 / 交叉融合类 2 / 礼仪与沟通类 1）
    人文社素养任选课  要求最低学分:3
  教师教育非必修课  要求最低学分:1
```

> 标注「课程详情（N门）」的节点可展开看课程清单，本 App v1 **不展开**（只展示要求/获得/门数）。

### 1.8 关键结论：两棵树同构

「修读要求」和「学生学业情况查询」用的是**同一棵培养方案树**，节点名称、层级、「要求学分」全部一致，只是数值列不同：

| 页面 | 数值列含义 |
|---|---|
| 教学执行计划查看 · 修读要求 | `课程总学分` = 方案**开设**的总学分 |
| 学生学业情况查询 | `获得学分` = 学生**实际拿到**的学分 |

⇒ **P05 与 P06 应该共用同一个节点树模型**，只切换数值列与进度口径，而不是各造一套。这样两张页面的节点名、层级、"要求学分"天然对得上，不会出现"同一个模块在两张页面显示不同要求"的自相矛盾。

---

## 2. 功能菜单（登录后实测，App 功能映射依据）

顶部导航 **7 组** + 我的应用 6 个（第二轮实测补全）：

| 组 | 子功能 |
|---|---|
| 报名申请 | 考级项目报名 等 |
| 信息维护 | 个人信息维护 等 |
| 选课 | **自主选课**、选课情况 等 |
| **信息查询** | 查询个人信息、学籍预警查询、毕业审核结果核查、**班级课表查询**、**个人课表查询**、查询空闲教室、学生选课情况确认、选课名单查询、重修课程查询、**学生成绩查询**、**学生成绩明细查询**、考试信息查询、创新创业报名、创新创业获得学分、结业换证考试查询、教材明细查询、教材费用查询、学业预警处理查询、学生证书打印、学生学业情况查询、实验课表查询、实验成绩查询 |
| 教学评价 | **学生评价**（评教） |
| 毕业设计(论文) | — |
| 实践系统 | — |
| 我的应用 | 自主选课、考级项目报名、**推荐课表打印**、学生评价、学生成绩明细查询、**教学执行计划查看** |

**App 功能 → 教务功能 对照**：

| App 页面 | 教务对应 |
|---|---|
| 我的课表 | 信息查询 → 个人课表查询 |
| 成绩查询 | 信息查询 → 学生成绩查询 / 学生成绩明细查询 |
| 推荐课表 | 我的应用 → **推荐课表打印**（返回的就是一张周课表，页面与「个人课表查询」基本一样） |
| 选课 | 选课 → 自主选课 |
| ~~教学计划~~ | ~~我的应用 → 教学执行计划查看~~ **已下线（2026-11-06 用户决定），不再实现** |
| 学分绩点 | 信息查询 → **学生学业情况查询**（权威值，不本地计算）；**页面只展示 GPA 一个数字**，门次统计与学分树不上屏 |
| 校历 | 教务处 `https://jwc.tstc.edu.cn/xlcx.htm`（独立站点，非教务系统） |
| 学工系统 | `http://sws.tstc.edu.cn/XGTSTC/Phone/#/login`（WebView，与教务解耦） |

> 菜单全部是 `href="javascript:void(0)"` + JS 动态加载，点击时把内容加载进 iframe，**主框架 URL 不变**。第二轮实测证明可以用 DOM click 展开并点击二级菜单；进入后直接按 §1.5 的地址跳转即可，不必依赖菜单。

---

## 3. 未打通的问题（需继续排查）

### 3.1 程序化登录失败

用 Node 完整复现「①取 csrftoken → ②取公钥 → ③RSA 加密 → ④POST 登录」，四种变体全部失败：

| 变体 | 结果 |
|---|---|
| HTTP + 4 个字段 | 302 回 `login_slogin.html`，`rememberMe=deleteMe` |
| HTTP + 全部 20 个字段 | 同上 |
| HTTPS + 4 个字段 | 同上 |
| HTTPS + 全部 20 个字段 | 同上 |

失败响应特征：`content-length: 0`、`rememberMe=deleteMe; Max-Age=0`、`location: /xtgl/login_slogin.html`（无 `err=` 参数）。这是 Shiro 认证失败的典型表现。

**已排除**：密码错误（浏览器同一密码登录成功）、协议（HTTP/HTTPS 都试过）、字段缺失（20 个字段全带）、MIME/编码（`+ / =` 均正确 URL 编码）。

**剩余怀疑点（按可能性排序）**：
1. **`csrftoken` 与 `JSESSIONID` 的绑定关系**——脚本在取公钥后 JSESSIONID 是否被更换
2. **RSA 密文的填充/长度细节**——jsbn 的 `pkcs1pad2` 对首字节的处理与 Node `crypto` 是否有差异（jsbn 在 `ba[--n] = 0` 前置 0；Node 的 PKCS1 填充是标准实现，理论上一致）
3. **`yhdl()` 前置的 `POST /xtgl/login_logoutAccount.html` 是否为必需步骤**
4. TLS 指纹 / WAF 侧对非浏览器客户端的识别（Tengine + 多节点，可能性较低）

**下一步建议**：在校园网内用浏览器 DevTools → Network 抓一次真实登录的 POST 请求体（`yhm/mm/csrftoken` 三个值），与脚本构造的请求逐字节比对，即可定位。

### 3.1b 重要：浏览器登录本身也是间歇性失败的

> ⚠️ **2026-11-06 12:20 更正**：下面「多节点 csrftoken 绑定」的归因**不是主因**。
> 用本地代理完整复现后定位到真正原因：登录成功是**两级 302**，代理跟随第二次跳转时
> 复用了 POST 之前构造的旧 `Cookie`，登录换发的 `JSESSIONID` 当场丢失，于是被踢回登录页。
> 修复（每次 hop 重建 Cookie）后**单次提交即成功**，连测三种提交变体都通过。详见 §6.2。
> 3 次重试仍保留，但只作为多节点异常的兜底，不再是主链路依赖。

2026-11-06 00:58 复测，**同一个学号、同一个密码、同一种输入方式，连续三次提交，第 1、2 次返回「用户名或密码不正确，请重新输入！」，第 3 次成功进入 `index_initMenu.html?jsdm=xs`。**

已排除的干扰项：输入内容本身没问题（把密码打到用户名框里肉眼核对，`170013@tssf` 一字不差）；两次提交之间页面已 reload，字段是干净的。

**最可能的原因**：系统是多节点负载均衡（响应头 `backendip: 172.29.1.172/173/175`，靠 `route` Cookie 固定后端）。登录页拿到的 `csrftoken` 与 POST 落到的后端节点不是同一台时，Shiro 侧校验不过，就报成"密码不正确"。

**对实现的影响**：
1. `remoteProvider.login()` **必须支持重试**（建议 3 次，间隔 800ms），不能一次失败就告诉用户密码错——那会让用户去改密码。
2. 失败文案不能直接说「用户名或密码不正确」，应区分「教务系统繁忙，正在重试」与「登录失败」。
3. 这也可以解释为什么 Node 脚本四种变体全失败——脚本每次都是全新 `JSESSIONID`，恰好都没撞上同一节点。

**验证方法**：登录失败后立刻重试，若同一凭据几次内能过，即可确认是节点/会话绑定问题而非密码问题。

### 3.2 课表页路径 —— ✅ 已解决（第二轮）

- 正确地址是 `/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default`，**没有 `/jwglxt` 前缀**（此前加前缀所以落到「系统维护页面」）
- 菜单可以点开：先 `dom_cua_click` 顶部「信息查询」，再点「个人课表查询」，菜单是 `href="javascript:void(0)"` 但点击有效

### 3.3 仍未确认的页面地址 —— ✅ 2026-11-06 已全部实测（见 §6）

> 原表格里的三项都已从首页 `clickMenu(...)` / 菜单 JSON / 「我的应用」接口直接取到，
> **不是猜的**。结论：学生成绩查询 `N305005`、自主选课 `N253512`、查询个人信息 `N100801`、
> 推荐课表打印 `N214505`。完整地址与数据接口见 §6.3 / §6.4。
> 仍未实测的只剩两项：**自主选课的课池 AJAX 地址**、**推荐课表预览网格的解析**
> （页面和清单接口本身已实测），代码里保持 `NOT_IMPLEMENTED` 诚实降级。

---

## 4. 仍然可信的部分（来自用户提供代码，与实测不冲突）

### 4.1 课表数据接口（来自用户 `provider.js`）

```js
POST /jwglxt/kbcx/xskbcx_cxXsKb.html
body: xnm=<学年>&xqm=<学期>
resp: JSON，取 .kbList
```

- 学期码**不要猜**，从课表页 `#ajaxForm` 读 `xnm.value` / `xqm.value`（用户代码的做法，最稳）
- `kbList[]` 字段：`kcmc / cdmc / xqj / zcd / jc / xm / xqjmc / jcs` —— 与 `parser.js` 第三分支完全对应

#### 4.1.1 周次字段（2026-11-07 真机实测，单双周必修读这一节）

`kbList[]` 同一行里其实有**三份周次信息**，之前只解析了中文文案 `zcd`：

| 字段 | 形态 | 出现位置 | 例子 |
|---|---|---|---|
| `zcd` | 中文文案 | 两个接口都有 | `1-17周` / `10-16周(双)` / `11-15周(单)` / `第9周` / `1-6周,8周` |
| `zcds` | 精确周次列表 | 推荐课表打印页 `cxBjKb` | `10,12,14,16` / `11,13,15` |
| `oldzc` | 周次位掩码（第 w 周 = 第 w-1 位） | 个人课表 `xskbcx_cxXsKb` 及打印页 | `255`→1-8 周、`64512`→11-16 周、`43520`→10/12/14/16 周 |

`web/data/jwxtWeekParse.js` 的 `weeksFromRow()` 优先级为 **zcds → oldzc → zcd 文案**。
2026-11-07 在真机抓了 10 个班 / 128 行逐一比对：`parseWeeks(zcd)` 与 `zcds` 完全一致（0 处不符），
仍以精确字段为准是为了防止教务改写文案后单双周退化 —— 一旦 parity 丢失，单周课与双周课
会在同一周同时命中同一格，课表页就会误报「时间冲突」。

`web/pages/timetableUtils.js` 的 `coursesInCell()` 进一步把同格课程分成
`active`（本周上课）与 `rotating`（周次完全互补、隔周才上的另一半）；只有两门以上
**真实课**同周重叠才判 `conflict`，单双周轮换为 `alternating`。教务把一门课按周次拆成
多行时，`parseKbList()` 会先由 `mergeSplitRows()` 合并（周次有交集的重复行不合并）。

展示上不另造角标：**真冲突 / 单双周轮换 / 板块占位行共用原有冲突课的右下角三角**，
点开课程卡翻看这一格的两门课。三角由 `courseCard.js` 渲染成 `.c-course__corner`，
是课程卡自己的子节点（不是格子上的伪元素），所以会跟着 `stagger()` 入场动画一起淡入；
早先画在 `.p-tt__gridcard::after` 上的圆形「单/双」角标因动画与卡片脱节已移除。
详情层的切换条按当前课程标「1 / 2 · 双周」「2 / 2 · 单周」，不写「时间冲突」，
也不加解释性说明；轮换到本周不上的那门时补一句「本周无课」。

还有一种**不是冲突但看起来像冲突**的行：推荐课表打印页会把「板块课」当占位信息输出，
真机实测 `25人工智能本科1班` 的 `大学体育2025(板块)` 为 `pkbj = -1`、`cdmc = 未排地点`、
`xf` 为空（0 学分）、`kch` 与 `kcmc` 同名，且一周重复出现在周一/周二/周四第 6-9 节，
与 `Java编程开发`（第 6-7 节）时间重叠。它只是「这个时段有板块，模块由学生自选」的提示，
不是排定的课。`kbRowToCourse()` 依 `pkbj === -1` 打上 `isPlaceholder`，
`coursesInCell()` 把「真实课 + 占位行」判为 `placeholder` 而非 `conflict`，
网格主显真实课、仍显示可翻页三角，详情层翻到占位行时标「板块课」。

### 4.2 真实作息时间（12 节，不分冬夏）

```js
1  08:00-08:45   2  08:50-09:35   3  09:55-10:40   4  10:45-11:30
5  14:00-14:45   6  14:50-15:35   7  15:55-16:40   8  16:45-17:30
9  17:35-18:20   10 19:00-19:45   11 19:50-20:35   12 20:40-21:25
```

上午 4 节 / 下午 5 节 / 晚间 3 节。**注**：源文件中的 `totalWeek: 20` 与 `startSemester: '1725206400000'`（2024-09-02）是旧学年遗留，**必须按校历取 19 周、2026-08-31**。

---

## 5. 对实现的直接影响

1. `remoteProvider` 的 base URL 用 **`https://jwxt.tstc.edu.cn`**，并允许自签/校园证书（Android `network_security_config` 已规划）。
2. RSA 加密交 `NativeBridge.rsaEncrypt`（Java `RSA/ECB/PKCS1Padding`），**不做字节反转**。
3. 登录流程按 §1.1 的 4 字段提交，`csrftoken` 用完整值；登录前先调 `logoutAccount`（待验证是否必需）。
4. **所有业务数据以教务抓取为主链路**（用户 2026-11-06 明确）：登录成功后点进任一功能模块 → 后台抓该模块教务页面 → 解析 → App UI 渲染。演示数据只在「显式演示会话」或「抓取失败降级」时出现，且 UI 必须标注。`web/data/remoteProvider.js` + `jwxtClient.js` + `jwxtParse.js` 已落地这条链路；**§3.1 程序化登录已于第三轮打通**（见 §6.2），课表/成绩/GPA/个人信息均已能从真实教务抓取，仅推荐课表预览与自主选课课池仍诚实降级。
5. **GPA 走 §1.5 的「学生学业情况查询」，服务端渲染，用 DOMParser 取文本再解析即可**，不需要 AJAX 抓 JSON，也不需要本地加权。生产解析器在 `web/data/jwxtParse.js` 的 `parseGpaPage()`，回归测试见 `tools/test-gpa.mjs`（53 条）与 `tools/test-jwxt-parse.mjs`（84 条）。页面只消费 `gpa` 与 `statsAt`。
6. **教学执行计划查看整条下线**（用户 2026-11-06 决定）：§1.7 / §1.8 的实测记录保留作调研存档，但不再对应任何页面、路由、数据槽或 provider 方法。
7. 学工系统 `http://sws.tstc.edu.cn/XGTSTC/Phone/#/login` 走 WebView，与教务解耦。

---

## 6. 第三轮：本地代理直连全链路实测（2026-11-06 12:20 GMT+8）

> 环境：用户本机校园网内，零依赖静态服务器 `server.js` + `tools/jwxt-proxy.mjs`。
> 浏览器同源请求 `/jwxt/<路径>` 由代理固定转发到 `https://jwxt.tstc.edu.cn/<路径>`，
> `/__zsts/encrypt` 用 Node `crypto.publicEncrypt` 做 RSAES-PKCS1-v1_5 加密。
> 上游 host 硬编码、`route`/`JSESSIONID` 由进程内 Cookie Jar 吸收、最多跟随 5 次同 host 重定向。
> **本节所有地址均来自实际响应，不是拼接猜出来的。**

### 6.1 代理端点验证

| 检查 | 结果 |
|---|---|
| `POST /__zsts/encrypt` 用临时 RSA 密钥对往返 | ✅ 200 + 密文，私钥解出原文一致；等价 Java `RSA/ECB/PKCS1Padding` |
| `GET /jwxt/xtgl/login_slogin.html` | ✅ 200 / 20933 字节，含 `csrftoken` 与「用户名」表单 |
| `GET /jwxt/xtgl/login_getPublicKey.html?time=<13位>` | ✅ 200 / 205 字节，`{ modulus, exponent: "AQAB" }` |
| 静态资源仍拒绝 POST / 加密端点仅允许 POST / 非法 JSON 400 | ✅ 6/6 通过 |

### 6.2 登录链路：两级 302 + 会话 Cookie 丢失（根因）

登录 POST 的真实响应链（由代理 `X-Zsts-Chain` 诊断头记录）：

```
POST /xtgl/login_slogin.html
  → 302 /xtgl/login_slogin.html
  → 302 /xtgl/index_initMenu.html?jsdm=xs&_t=...&echarts=1
  → 200 index_initMenu.html（77 KB，含「我的应用」容器 index_wdyy）
```

**此前九次登录全部失败的真正原因**：`forward()` 在跟随重定向时复用了 POST 之前
一次性构造好的 `Cookie` 请求头。登录成功会在 POST 响应里换发新的 `JSESSIONID`，
第二次跳转带着旧会话过去，会话当场失效，被静默踢回登录页——页面里**没有任何
错误文案**，所以看起来就像「密码不正确 / 多节点随机失败」。

修复：每一次 hop 都用 `{ ...reqHeaders, Cookie: cookieHeader() }` 重建 Cookie。
修复后**单次提交即成功**，另测三种提交变体（带 `X-Requested-With`、模拟浏览器普通表单、
带全部隐藏字段）都能拿到 `index_initMenu.html`。

附带结论：
- `mmsfjm=1` 是密码加密开关；提交字段 `csrftoken / yhm / mm / hidMm / language / mmsfjm`
  即可，**不需要**把页面上十几项隐藏开关全带上。
- 成功判定改用「是否被打回登录页」的负向证据（`action="/xtgl/login_slogin.html"`
  或 `placeholder="用户名"`），不要求响应里必须含首页特征——Android 通道可能只回 302 空体。
- §3.1b 的 3 次重试保留，但降级为多节点异常的兜底，不再是主链路依赖。

### 6.3 菜单与功能码（`gnmkdm`）

三个来源，均实测可达：

| 来源 | 地址 | 收获 |
|---|---|---|
| 首页内嵌 `clickMenu('码','路径','名称',…)` | `/xtgl/index_initMenu.html?jsdm=xs` | 74 个功能项 |
| 菜单树 JSON | `POST /xtgl/index_cxMenuList.html` | 75 个功能项 |
| 「我的应用」列表 JSON | `/xtgl/index_cxWdyy.html?localeKey=zh_CN` | 应用别名，加载逻辑在 `jwglxt_index.js` |

App 用到的关键项（**页面地址**）：

| 功能 | gnmkdm | 页面 |
|---|---|---|
| 个人课表查询 | `N2151` / `N253508` | `/kbcx/xskbcx_cxXskbcxIndex.html` |
| 学生学业情况查询 | `N105515` | `/xsxy/xsxyqk_cxXsxyqkIndex.html?echarts=1` |
| 学生成绩查询 | `N305005` | `/cjcx/cjcx_cxDgXscj.html` |
| 学生成绩明细查询 | `N305007` | `/cjcx/cjcx_cxDgXsxmcj.html` |
| 自主选课 | `N253512` | `/xsxk/zzxkyzb_cxZzxkYzbIndex.html` |
| 推荐选课 | `N253511` | `/xsxk/tjxkyzb_cxTjxkYzbIndex.html` |
| 查询个人信息 | `N100801` | `/xsxxxggl/xsgrxxwh_cxXsgrxx.html` |
| **推荐课表打印** | `N214505` | `/kbdy/bjkbdy_cxBjkbdyIndex.html` |
| ~~教学执行计划查看~~ | ~~`N153540`~~ | **已下线，不实现** |

> 「推荐课表打印」只存在于「我的应用」列表里（`gnmkmc` 字段），主菜单中同一
> `N214505` 显示为「班级课表查询」。这解释了为什么第二轮翻主菜单找不到它。
> 用户所说「推荐课表页面和课表查询基本一样」与此一致。

### 6.4 页面 ≠ 数据接口（本轮最重要的实现约束）

带 `gnmkdm` 的 URL 只是**页面壳**，业务数据要再 POST 一次页内 AJAX 才拿得到：

| 功能 | 页面 | 数据接口 | 实测参数 | 返回 |
|---|---|---|---|---|
| 个人课表 | `N2151` 页面 | `POST /kbcx/xskbcx_cxXsKb.html` | `xnm=2026&xqm=3` | ✅ 10692 字节，`kbList` 5 条 + `xsxx` 学生信息 |
| 学生成绩 | `N305005` 页面 | `POST /cjcx/cjcx_cxXsgrcj.html?doType=query` | `xnm=&xqm=&zd_fzdm=N305005-xs` + `queryModel.showCount/limit=200` | ✅ `totalResult=82`，`items` 82 条 |
| **成绩分项明细** | `N305007` 页面 | `POST /cjcx/cjcx_cxXsKccjList.html` | `xnm=&xqm=` + `queryModel.showCount/limit=1000`（**不带** `zd_fzdm`） | ✅ `totalResult=216`，`items` 216 条（76 门课） |
| 推荐课表清单 | `N214505` 页面 | `POST /kbdy/bjkbdy_cxBjkbdyTjkbList.html` | `xnm=2026&xqm=3` | ✅ 467 个班级的推荐课表**目录**（不是课表本体） |
| 自主选课 | `N253512` 页面 | ⛔ 课池 AJAX 未实测 | — | 保持 `NOT_IMPLEMENTED` |

细节：
- 成绩接口**学生角色**走 `cjcx_cxXsgrcj.html`，不是管理员用的 `cjcx_cxDgXscj.html`；
  两者带同样参数时都能返回数据，但角色参数 `zd_fzdm=N305005-xs` 必须带。
- 成绩默认只给 10 条；`queryModel.showCount=200` + `limit=200` 才能一次取全 82 条。
- 成绩字段：`kch / kcmc / cj / jd / xf / kcxzmc / kclbmc / khfsmc / ksxz / cjsfzf /
  xnmmc / xqmmc / jxb_id / key`。`cj` 可能是文字成绩（优秀/缓考/缺考），映射时
  **不伪装成数字**；`semesterId` 用 `xnmmc-xqmmc` 原文拼，不自己换算。
- **成绩分项明细**（`N305007`，2026-11-07 真机实测）：一行 = 一门课的一个分项，
  字段 `jxb_id / kch / kcmc / xf / xmblmc / xmcj / xnm / xqm`。`xmblmc` 形如
  `平时(40%)` / `期末(60%)` / `总评`，`xmcj` 是对应得分（也可能给成「良好」）。
  - `总评` 行与成绩查询的 `cj` **逐条一致**（76/76），所以解析时丢弃，避免同一数字两处来源；
  - 该接口**比成绩查询少 6 门课**（全是「网络学习」类纯总分课，本来就没有分项），
    所以它只能**附加**在成绩查询之上，不能替代 —— 否则 82 条会掉成 76 条；
  - 它没有 `jd / kcxzmc / khfsmc / ksxz`，绩点与课程性质仍以 `N305005` 为准；
  - 默认只回 10 条，`queryModel.showCount=1000` 才能一次拿全 216 行（≈180 KB，可与成绩查询并行）。
- 推荐课表清单再往下还有「按 `tjkbzdm` 预览 → `bjkbdy_ylBjkbdyIndex.html` → 周课表网格」
  一段未实测，所以 `ENDPOINTS.recommend` **仍保持 `null`**，不把 467 条班级目录冒充成 `Course[]`。

### 6.5 正方学期码口径

课表页 / 班级课表页的下拉实测：

```
<select name="xnm"> … <option value="2026" selected="selected">2026-2027</option> …
<select name="xqm"> <option value="3" selected>1</option>
                    <option value="12">2</option>
                    <option value="16">3</option> …
```

- **`xqm=3` 是第一学期，`12` 是第二学期，`16` 是第三学期**，不是 1/2/3。
- 学年/学期是 `<select><option selected>`，**不是** `<input value>`。旧版 `fetchKb()`
  用 `name="xnm" … value="..."` 正则永远读不到 → POST 空参 → 教务只回 `null`
  → 课表必然降级。已改为 `readSelectValue()` 读选中项。
- 传 `xnm=2026&xqm=1` 或 `12` 查成绩会返回 0 条（当前学期尚无成绩），
  成绩接口要用**空** `xnm/xqm` 查全部学期。

### 6.6 落地到代码

| 文件 | 改动 |
|---|---|
| `tools/jwxt-proxy.mjs` | 新建本地代理；修复重定向后 Cookie 重建；新增 `X-Zsts-Chain` 诊断头 |
| `server.js` | 在方法白名单之前接入代理分支，允许 `/jwxt/*` 与 `/__zsts/encrypt` 的 POST |
| `web/data/jwxtClient.js` | `ENDPOINTS` 补齐 11 个实测端点；登录成功判定改为负向证据 |
| `web/data/jwxtParse.js` | 新增 `readSelectValue` / `normalizeTerm` / `gradeRowToGrade` / `parseGradeList` |
| `web/data/remoteProvider.js` | 修 `fetchKb` 学期码；`getGrades` 改走真实数据接口；未实测项明确降级 |
| `tools/test-jwxt-parse.mjs` | 50 → 84 条断言（学期码 `<select>`、成绩字段映射、端点边界） |

### 6.7 仍未实测的诚实边界

1. 自主选课的课池 AJAX 地址与选课提交接口 → `ENDPOINTS.selectionData = null`，
   `toggleCourse()` 明确返回 `NOT_IMPLEMENTED`，避免让用户以为选上了但教务没记录。
2. 推荐课表「预览页 → 周课表网格」的解析 → `ENDPOINTS.recommend = null`，降级时
   UI 标注「演示数据」。页面 `recommendPage` 与清单 `recommendList` 已实测并写入端点表。
3. Android `NativeBridge.httpRequest` 通道的行为（是否跟随 302、Cookie 是否由 Java 侧维护）
   尚未在真机验证；桌面预览走的是同源代理通道。

> 依据 §3.3 的「不允许猜地址」，以上三项不会先写一个看起来合理的路径再假装成功。

## 7. 校外访问：协议可达性实测（2026-11-07 13:30 GMT+8，中国移动 5G）

**结论：教务对外只放 80，443 在校外不可达。App 原先写死 `https://`，所以「浏览器能开、
App 必登录失败」。**

| 探测 | 结果 |
| --- | --- |
| `ping jwxt.tstc.edu.cn` | 解析到 `60.2.228.76`（IPv4）；ICMP 全丢（学校侧抑制，正常） |
| `nc -z 60.2.228.76 443`（30 s） | 超时，SYN 无人应答 —— 端口不可达，不是握手失败 |
| `nc -z 60.2.228.76 80`（30 s） | `0.05 s` 建连成功 |
| `GET https://jwxt.tstc.edu.cn/xtgl/login_slogin.html` | `SocketTimeoutException: failed to connect to 60.2.228.76 (port 443) after 15000ms` |
| `GET http://jwxt.tstc.edu.cn/xtgl/login_slogin.html` | `200`，19768 B，登录页正常，且**不下发 https 跳转** |

补充事实（决定了超时值）：

1. 该校外入口**慢且抖**：同一台手机上单次请求实测 0.05 s ～ 18 s 不等（菜单页 8962 B
   用了 17.6 s，favicon 也有 16 s 的样本）。原 `DEFAULT_TIMEOUT = 15000` 正好卡在边界上，
   会把「慢」误判成「不通」。
2. 探针不能用登录页：19 KB 页面校外可能 16 s 才吐完，拿它探测必然把可用通道判死。
   改用 `/favicon.ico`（47 ms 返回 404，`<500` 即视为通）。

落地（`web/data/jwxtOrigin.js`）：

- https 优先、连不通自动降 http；探测只探 https（http 在校外入口稳定存在），3 s 上限，
  结果按天缓存、失败冷却 60 s。
- `transport.DEFAULT_TIMEOUT` 与 `CookieJar.TIMEOUT_MS` 同步放宽到 20 s。
- 真正的请求若在网络层失败（换了网络环境），会忘记缓存重探一次再重试；已在 http 上超时
  则不重试（那是服务端慢，换协议没用）。
- 原生 `WebActivity` 补了主框架加载失败时 http/https 互换重试一次，「更多功能」入口同理。

> 安全提示：降级是**校外唯一可用**的选择，不是优化。密码始终 RSA 加密后提交（`mmsfjm=1`），
> 但会话 Cookie 在校外是明文传输 —— 校内仍优先 https，缓存里也记着哪个协议当时可用。
