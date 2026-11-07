# 课表解析器适配规格（用户提供代码 → `web/data/parse.js`）

> 输入源：`reference/wakeup-parser-tstc.js`（用户本人为唐山师范学院写给**小米小爱课程表**的学校适配脚本，277 行）
> 说明：项目同时参考了 WakeUp 课程表与小米小爱课程表的数据；本文分析的是后者中的适配脚本。
> 文件名沿用早期 `wakeup-` 前缀，容易误解 —— 它**不是** WakeUp 课程表的解析器。
> 目标：并入 `web/data/parse.js`，作为 `remoteProvider` 的课表解析实现
> 结论：**解析逻辑可整体复用，只需替换 DOM 适配层 + 修 3 处缺陷 + 补 1 个数据字段**

---

## 1. 源码分析（已逐行读毕）

### 1.1 它是什么

这是 **小米小爱课程表**的「学校解析器」规范实现（源码第 268 行注释指向小爱开放平台的 parser 文档）。入口函数签名：

```js
scheduleHtmlParser(html) → { courseInfos, sectionTimes }
```

`html` 参数是一个 **JSON 字符串**，形如 `{"html": "<课表页HTML或JSON>", "sj": "<节次时间>"}`（源码 L164 `JSON.parse(html)` 后取 `jss.html` / `jss.sj`）。

### 1.2 它覆盖了正方（ZF）教务的三种课表格式

| 分支 | 判定条件 | 含义 |
|---|---|---|
| `table1` | `div.tab-pane.fade.active.in` 的 `id === 'table1'` | 表格型课表（`tr > .td_wrap > div`，单元格 `id` 首字符为星期几） |
| `table2` | 同上，`id === 'table2'` | 列表型课表（一天一个 `<tbody id="xq_N">`，每行一门课） |
| else | 其它 | `jss.html` 本身即 JSON 数组，字段为 **正方标准字段名**：`kcmc`(课程名称) `cdmc`(场地名称) `xqj`(星期几) `zcd`(周次段) `jc`(节次) `xm`(教师姓名) |

**第三种分支最有价值**——它暴露了正方教务的数据接口字段名，是后续接真实接口的直接线索。

### 1.3 四个纯函数（无需改动，直接搬）

| 函数 | 作用 | 是否可原样复用 |
|---|---|---|
| `getWeeks(str)` | 把 `1-16周(单)` / `1-8,10-16周` / `第1-16周` 解析成周次数组 `[1,3,5…]` | ✅ 纯字符串运算 |
| `getSections(str)` | 把 `1-2节,5-6节` 解析成 `[{section:1},{section:2},…]` | ✅ 纯字符串运算 |
| `resolveCourseConflicts(result)` | 拆单节 → 去重 → 合并同格冲突课（用 `\|` 连接课名/教师/地点）→ 合并连续节次 → 合并周次 | ✅ 纯数据运算 |
| `pdSection(or, inn)` | 内嵌辅助：比较两个节次数组是否相同 | ✅ |

### 1.4 唯一的外部依赖

```js
let $ = cheerio.load(jss.html, { decodeEntities: false })
```

`cheerio` 是小爱课程表解析器运行时注入的。**我们的零依赖环境没有它**，这是唯一的移植障碍。

---

## 2. 移植方案

### 2.1 DOM 适配：写一个 60 行的迷你 jQuery shim，而不是重写业务逻辑

源码里 jQuery 用法只有 6 个：`$(sel)`、`$(this).find(sel)`、`.attr(name)`、`.text()`、`.children(sel)`、`.each(fn)`、`.length`、`.eq(n)`。

**不要**把 277 行的解析逻辑改写成 `querySelectorAll`——那样改动面扩大 3 倍且极易引入回归。正确做法是在 `parse.js` 内部实现一个薄适配层：

```js
// 内部私有，不导出
function createQuery(htmlString) {
  const doc = new DOMParser().parseFromString(htmlString, 'text/html');
  function wrap(node) {
    const api = {
      length: node ? 1 : 0,
      attr: (n) => (node ? node.getAttribute(n) : undefined),
      text: () => (node ? node.textContent : ''),
      find: (sel) => wrapAll(node ? [...node.querySelectorAll(sel)] : []),
      children: (sel) => wrapAll(node ? [...node.children].filter(c => c.matches(sel)) : []),
      eq: (i) => wrap(node ? node.querySelectorAll('*')[i] : null),
      each: (fn) => { if (node) fn.call(node, 0, node); return api; },
    };
    return api;
  }
  function wrapAll(nodes) {
    const api = {
      length: nodes.length,
      attr: (n) => (nodes[0] ? nodes[0].getAttribute(n) : undefined),
      text: () => (nodes[0] ? nodes[0].textContent : ''),
      find: (sel) => wrapAll(nodes.flatMap(n => [...n.querySelectorAll(sel)])),
      children: (sel) => wrapAll(nodes.flatMap(n => [...n.children].filter(c => c.matches(sel)))),
      eq: (i) => wrap(nodes[i] || null),
      each: (fn) => { nodes.forEach((n, i) => fn.call(n, i, n)); return api; },
    };
    return api;
  }
  return (sel) => wrapAll([...doc.querySelectorAll(sel)]);
}
```

> `DOMParser` 在浏览器与 Android WebView 均原生可用，零依赖。注意 `decodeEntities`——`DOMParser` 默认按 HTML 实体解码，与 `decodeEntities:false` 语义一致（源码之所以关掉，是因为要拿原始字符做正则）。**用 `.textContent` 而非 `.innerHTML`** 即可，无需特殊处理。

**注意 `$(this)` 语义**：源码里 `trs.each(function(index, em){ $(this)… })`，`this` 是 DOM 节点。shim 的 `.each()` 已用 `fn.call(node, i, node)` 保持 `this` 绑定，可直接沿用。

### 2.2 必须修的 3 处缺陷

| # | 位置 | 问题 | 修法 |
|---|---|---|---|
| **P1** | L103、L140、L156、L189、L232、L264 共 6 处 `console.log` | 生产环境刷屏，且 L232 在循环内打印整条课程对象，一学期 26 门课 × 19 周会产生大量输出 | 全部删除；需要排查时用 `if (DEBUG_PARSE) console.debug(...)` |
| **P2** | L260-263 | 解析失败时**伪造一条课程**返回（`name:"遇到错误,可联系开发者qq…"`、`teacher:"开发者-陈铭霏"`），会把假数据当成真课表渲染进课表页 | 改为返回 `{ ok:false, error:{code:'PARSE', message, detail} }`，由调用方走 `emptyState`。开发者联系方式移到 `detail`，不进入课程列表 |
| **P3** | L167 `$('div[class = "tab-pane fade active in"]')` | 属性选择器里带空格，是精确匹配 `class` 字符串，一旦教务在 class 里多一个空格/多一个类名就匹配失败 | 改为 `div.tab-pane.active.in`（选择器语义等价，但容错更强）；`.attr('id')` 为空时进入 else 分支前先判断 `jss.html` 是否以 `[` 或 `{` 开头，避免 `JSON.parse` 抛错 |

### 2.3 架构修正：`Course` 必须补 `weeks: number[]`

架构文档 §4.2 的 `Course` 只有 `weekRange:[number,number]` + `weekParity:'all'|'odd'|'even'`。**这无法表达正方课表的真实形态**——真实周次是任意集合（如 `1-8,10-16周`、`1-15周(单)`、`2-4,7,9-12周`），拆成「区间 + 单双周」会丢信息。

**修正**：`Course` 增加字段 `weeks: number[]`（完整周次集合，升序去重），`weekRange`/`weekParity` 降级为「由 `weeks` 派生的展示用字段」。

- 课表页渲染判定改为 `course.weeks.includes(weekNo)`，不再用区间/奇偶判断
- `weekRange` 保留（= `[Math.min(...weeks), Math.max(...weeks)]`），用于列表文案「1-16周」
- `weekParity` 保留，由 `weeks` 全奇数/全偶数推导，用于文案「单周」

演示数据 `web/data/demo/courses.js` 也必须同步给出 `weeks` 数组（可由 `weekRange`+`weekParity` 生成后缓存，但**必须与真实解析结果同形**）。

---

## 3. 目标接口（写进 `web/data/parse.js`）

```js
/**
 * 解析教务课表。纯函数，不触摸 DOM 之外的全局状态，可直接 node 单测（需注入 DOMParser）。
 * @param {string} raw  transport 拿到的原始响应文本（可能是 JSON 包裹，也可能是 HTML）
 * @param {object} [opts] { sectionTimeMap?: Object }
 * @returns {{ ok:true, data:{ courses: Course[], sectionTimes: SectionTime[] } }
 *          |{ ok:false, error:{ code:'PARSE', message:string, detail?:string } }}
 */
export function parseTimetable(raw, opts) { … }
```

**映射规则（小爱课程表输出格式 → 本项目 `Course`）**：

| 源字段 | 目标字段 | 说明 |
|---|---|---|
| `name` | `name` | 冲突课可能是 `"高等数学\|线性代数"`，需按 `\|` 拆成多门课（各自 `sections`/`position` 按索引对齐），或保留为一条并置 `isConflict:true`。**选前者**——课表按门显示才正确 |
| `teacher` | `teacher` | 同上，`\|` / `,` 分隔 |
| `position` | `location` | 空串时置 `'未安排'` |
| `day` | `dayOfWeek` | 1–7 |
| `weeks[]` | `weeks` + `weekRange` + `weekParity` | 见 §2.3 |
| `sections[{section}]` | `sections:number[]` | 取 `.section` 组成数组 |
| （由 `sections` 查 `sectionTimes`） | `startTime` / `endTime` | 查不到时置 `'--:--'`，不抛错 |
| — | `credits` / `courseType` / `code` | 课表页**不提供**，置 `0` / `'required'` / `''`；真实学分从成绩页或教学计划页补全 |

`sectionTimes` 来自 `jss.sj`，小爱课程表规范为 `[{section:1,startTime:'08:00',endTime:'08:45'}, …]`。**格式未验证**（无真实样本），实现时做容错：数组元素可能是 `{section,startTime,endTime}` 或 `{n,s,e}` 或纯字符串，统一归一化。

---

## 4. 需要留的 fixture（`web/data/__fixtures__/`）

无真实抓包，`parse.js` 必须有可执行的单测样本，否则等于没写：

| fixture | 内容构造要求 |
|---|---|
| `timetable-table1.html` | 复刻 `table1` 结构：`div.tab-pane.fade.active.in#table1 > table > tbody > tr`，前 2 行表头，其后每行 7 个 `.td_wrap`（`id="1-1"…"7-12"`），格内有 `span.title`、`p > span[title="节/周"]`（文本形如 `(1-2节)1-16周`）、`p > span[title="上课地点"]`、`p > span[title="教师"]` |
| `timetable-table2.html` | 复刻 `table2`：7 个 `<tbody id="xq_1"…>`，每行 `td` 含 `span.title`、`font > span.glyphicon-calendar`（文本 `周数：1-16周`）、`glyphicon-map-marker`、`glyphicon-user` |
| `timetable-json.json` | `{"html":"[{\"kcmc\":\"高等数学\",\"cdmc\":\"博学楼A302\",\"xqj\":\"4\",\"zcd\":\"1-16周\",\"jc\":\"1-2节\",\"xm\":\"张伟\"}]","sj":[...]}` |
| `timetable-malformed.html` | 空 `#table1`（无行）→ 必须返回 `ok:false` 且 `code:'PARSE'`，**不得**返回伪造课程 |

单测脚本 `tools/test-parse.mjs`：node 环境需先 `global.DOMParser = <轻量实现>`；若引入实现成本高，改为**浏览器内自测页** `web/dev/parse-test.html`（T15 验收后删除），用 4 个 fixture 断言。二选一，但必须可执行。

---

## 5. 与架构的衔接

- `parse.js` 属 L4 数据层，**禁止 import 任何 DOM 相关模块**——但 `DOMParser` 是宿主全局对象，不是 import，架构约束不冲突。为可测性，可在 `parse.js` 顶部写 `const DP = globalThis.DOMParser`，单测时注入。
- `remoteProvider.getTimetable()` 的调用链：`transport.httpRequest(课表页URL)` → `parseTimetable(raw)` → 成功则写 `slots.timetable`，失败按 `PARSE` 错误走降级（`withFallback` 兜 demo 数据 + 提示条）。
- **不实现** `remoteProvider.login()` 的验证码/加密逻辑（PRD Q1 未定），保持 `CAPTCHA`/`NOT_IMPLEMENTED` 错误语义即可。

---

## 6. 交付自检清单

- [ ] `parse.js` 不出现 `cheerio` 字样
- [ ] 4 个 fixture 全部有对应断言，且 malformed 用例返回 `ok:false`
- [ ] `getWeeks('1-16周(单)')` → `[1,3,5,7,9,11,13,15]`
- [ ] `getWeeks('1-8,10-16周')` → `[1..8,10..16]` 共 15 项
- [ ] `getSections('1-2节,5-6节')` → `[{section:1},{section:2},{section:5},{section:6}]`
- [ ] 冲突课 `"高数|线代"` 拆成 2 门，各自 `weeks/sections` 正确
- [ ] 无任何 `console.log`
- [ ] `Course.weeks` 与真实解析结果同形，演示数据也提供该字段
