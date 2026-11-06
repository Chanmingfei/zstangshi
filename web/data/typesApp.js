/**
 * typesApp.js —— 运行时契约（JSDoc typedef，零运行时代码）
 *
 * `types.js` 管**业务领域模型**（校历 / 课程 / 成绩 / 培养方案 / 校历事件）；
 * 本文件管**应用运行时**（用户与数据源 / 数据槽 / 路由与页面 / App 根状态）。
 * 拆成两份只为满足「单文件 ≤ 400 行」硬规，语义上二者是同一套契约。
 *
 * 引用方式（跨文件）：
 *   `@param {import('./types.js').Course} course`
 *   `@param {import('./typesApp.js').SlotStatus} status`
 *
 * 约定：
 *   · 所有日期时间一律 ISO 字符串
 *   · 本文件不得出现任何可执行语句（除文件末尾的 `export {}`）
 */

/* ══════════════════════════════════════════════════════════════════════════
 * 七、用户与数据源
 * ════════════════════════════════════════════════════════════════════════ */

/**
 * 学生档案。
 * @typedef {object} StudentProfile
 * @property {string} name 姓名
 * @property {string} studentNo 学号
 * @property {string} college 院系
 * @property {string} className 班级
 * @property {string} major 专业
 * @property {string} grade 年级 '2024'
 * @property {string} avatarText 头像文字（姓名末字）
 */

/**
 * 数据源模式：演示数据 / 真实教务。
 * @typedef {'demo'|'remote'} DataSourceMode
 */

/**
 * 运行环境：浏览器 / Android WebView 壳。
 * @typedef {'web'|'android'} AppEnv
 */

/**
 * 会话状态。
 * @typedef {'guest'|'pending'|'authed'|'demo'|'failed'} SessionStatus
 */

/**
 * 数据源探测结果。
 * @typedef {object} SourceProbe
 * @property {boolean} reachable 是否可达
 * @property {number|null} latencyMs 探测耗时
 * @property {string|null} reason 不可达原因
 */

/* ══════════════════════════════════════════════════════════════════════════
 * 八、数据槽与页面契约
 * ════════════════════════════════════════════════════════════════════════ */

/**
 * 数据槽状态：
 * - idle    未加载
 * - loading 加载中（UI 出骨架屏）
 * - ready   就绪
 * - stale   有缓存但本次刷新失败（展示缓存 + 「上次同步 …」）
 * - error   失败且无缓存
 * @typedef {'idle'|'loading'|'ready'|'stale'|'error'} SlotStatus
 */

/**
 * 统一数据槽。**8 个槽形状完全一致**，页面只读 `status / data / error`。
 * @template T
 * @typedef {object} DataSlot
 * @property {SlotStatus} status 状态
 * @property {T|null} data 数据
 * @property {import('./types.js').ProviderError|null} error 错误
 * @property {number|null} updatedAt 上次成功时间戳（ms）
 * @property {boolean} fromCache 是否来自本地缓存
 * @property {boolean} degraded 是否已降级（remote 失败走 demo 数据）
 */

/**
 * 八个数据槽的集合。
 * @typedef {object} Slots
 * @property {DataSlot<StudentProfile>} profile 学生档案
 * @property {DataSlot<import('./types.js').TodayCourse[]>} todayCourses 今日课程
 * @property {DataSlot<import('./types.js').Course[]>} timetable 全学期课表
 * @property {DataSlot<{bySemester: Record<string, import('./types.js').Grade[]>}>} grades 成绩（按学期分组）
 * @property {DataSlot<import('./types.js').GPA>} gpa 学分绩点
 * @property {DataSlot<import('./types.js').CourseSelection>} selection 选课
 * @property {DataSlot<import('./types.js').RecommendedTimetable>} recommend 推荐课表（教务「推荐课表打印」的一张周课表）
 */

/**
 * 路由匹配结果。
 * @typedef {object} RouteMatch
 * @property {string} path 归一化路径 '/grades/2026-1-g01'
 * @property {Record<string, string>} params 路径参数
 * @property {Record<string, string>} query 查询参数
 * @property {object} route 命中的路由表项
 * @property {string} key 滚动位置的缓存键
 */

/**
 * 页面模块契约（router 通过 `load()` 懒加载得到）。
 * `render` 必须**同步**返回骨架（首屏不白屏），数据在 `mount` 里异步填充。
 * @typedef {object} PageModule
 * @property {string} [name] 页面标识
 * @property {string} [title] 写进 document.title
 * @property {string} [tab] 归属 Tab
 * @property {boolean} [requiresAuth] 是否需要登录
 * @property {(ctx: RouteMatch) => HTMLElement} render 同步返回页面根节点
 * @property {(el: HTMLElement, ctx: RouteMatch) => (void|Function)} [mount] 返回 cleanup
 * @property {() => void} [unmount] 卸载钩子
 */

/* ══════════════════════════════════════════════════════════════════════════
 * 九、App 根状态（与 core/store.js 顶部注释一致）
 * ════════════════════════════════════════════════════════════════════════ */

/**
 * 应用外壳状态。
 * @typedef {object} AppShellState
 * @property {boolean} booted 启动流程完成
 * @property {AppEnv} env 运行环境
 * @property {string} version 版本号
 * @property {'light'|'dark'} theme 生效主题
 * @property {'system'|'user'} themeSource 主题来源
 * @property {boolean} offline 是否离线
 * @property {boolean} reducedMotion 是否减弱动效
 */

/**
 * 会话状态。
 * @typedef {object} SessionState
 * @property {SessionStatus} status 状态
 * @property {string} studentNo 学号（仅内存，禁落盘）
 * @property {boolean} rememberId 是否记住学号
 * @property {string} name 姓名
 * @property {import('./types.js').ProviderError|null} error 错误
 */

/**
 * 数据源状态。
 * @typedef {object} SourceState
 * @property {DataSourceMode} preferred 用户选择
 * @property {DataSourceMode} effective 实际生效
 * @property {boolean} probing 探测中
 * @property {SourceProbe} probe 探测结果
 * @property {boolean} bannerDismissed 演示提示条是否已关
 * @property {number|null} lastFallbackAt 上次降级时间
 * @property {import('./types.js').ProviderError|null} lastError 上次错误
 */

/**
 * UI 局部状态（不入缓存）。
 * @typedef {object} UiState
 * @property {string} route 当前路由
 * @property {Record<string, string>} params 路由参数
 * @property {string} activeTab 当前 Tab
 * @property {number} weekNo 课表当前周
 * @property {string} semesterFilter 成绩页筛选的学期
 * @property {string} gradesQuery 成绩搜索词
 * @property {{type: string, dept: string, onlyAvailable: boolean}} selectionFilter 选课筛选
 * @property {string[]} selectionIntent 本地意向课程 ID
 * @property {string} calendarMonth 'YYYY-MM'
 * @property {'all'|'recent4'} gpaRange GPA 统计范围
 * @property {string[]} expandedModules 教学计划展开的模块
 * @property {{id: string, payload: object}|null} sheet 当前弹层
 * @property {Record<string, boolean>} busy 按钮级 loading
 * @property {string[]} toasts 提示队列
 */

export {};
