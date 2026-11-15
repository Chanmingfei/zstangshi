/**
 * types.js —— 领域模型契约（JSDoc typedef，零运行时代码）
 *
 * 为什么要这个文件：本项目不用 TypeScript（零构建），但**契约必须可查、可被编辑器感知**。
 * 所有跨模块传递的对象形状都在这里定义一次，其它文件用 `@type` / `@param {Course}` 引用。
 *
 * 本文件只放**业务领域模型**（校历 / 课程 / 成绩 / 培养方案 / 校历事件）。
 * 运行时契约（数据槽 / 路由 / 页面模块 / App 根状态）见同目录 `typesApp.js`。
 * 两份文件与 `core/store.js` 顶部的 state 形状注释三方互为校验。
 *
 * 约定：
 *   · 所有日期时间一律 ISO 字符串（'2026-11-05' / '2026-11-05T22:10:00+08:00'）
 *   · 所有 `id` 字段全局唯一且稳定，跨学期不重复
 *   · 本文件不得出现任何可执行语句（除文件末尾的 `export {}`）
 */

/* ══════════════════════════════════════════════════════════════════════════
 * 一、错误与结果
 * ════════════════════════════════════════════════════════════════════════ */

/**
 * 归一化错误码。全项目只用这几类，便于 UI 做统一文案与降级策略。
 * - NETWORK  网络不可达 / DNS 失败 / 原生通道缺失
 * - TIMEOUT  超时（默认 15s）
 * - ABORT    调用方主动取消
 * - HTTP     服务端返回非 2xx
 * - AUTH     未登录 / 凭据失效
 * - CAPTCHA  需要验证码（真实教务登录，v1 不实现）
 * - PARSE    响应解析失败（HTML 结构变化等）
 * - NOT_IMPLEMENTED 该能力本版未实现
 * - UNKNOWN  兜底
 * @typedef {'NETWORK'|'TIMEOUT'|'ABORT'|'HTTP'|'AUTH'|'CAPTCHA'|'PARSE'|'NOT_IMPLEMENTED'|'UNKNOWN'} ErrorCode
 */

/**
 * 统一错误对象。**任何失败都必须归一成这个形状**，禁止把原始 Error 直接抛给 UI。
 * @typedef {object} ProviderError
 * @property {ErrorCode} code 错误码
 * @property {string} message 面向用户的中文短句（可直接展示）
 * @property {string} [detail] 面向开发者的细节（日志/调试面板用，不展示）
 * @property {number} [status] HTTP 状态码（code 为 HTTP 时）
 * @property {number} [elapsedMs] 耗时，便于排查慢请求
 */

/**
 * 统一结果类型（Either 风格）。调用方先判 `ok`，再取 `data` 或 `error`。
 * @template T
 * @typedef {{ok: true, data: T} | {ok: false, error: ProviderError}} ProviderResult
 */

/**
 * transport 层的原始响应体。
 * @typedef {object} HttpResponse
 * @property {number} status HTTP 状态码
 * @property {Record<string, string>} headers 响应头（键统一小写）
 * @property {string} body 原始文本（HTML / JSON 字符串 / 空串）
 * @property {string} url 最终 URL（跟随重定向后）
 * @property {number} elapsedMs 耗时（ms）
 */

/* ══════════════════════════════════════════════════════════════════════════
 * 二、学期与校历
 * ════════════════════════════════════════════════════════════════════════ */

/**
 * 学期（校历与教务系统统一口径）。
 * 注意：`web/data/school-calendar.json` 是教务处原件副本，**不含 id 字段**；
 * `id` 由 `calendar.js` 的 `normalizeSemester()` 派生为 `${academicYear}-${term}`。
 * @typedef {object} Semester
 * @property {string} id 形如 '2026-2027-1'（派生字段）
 * @property {string} academicYear 学年 '2026-2027'
 * @property {string} displayYear 顶栏展示用 '26~27'（波浪号 U+007E，不可换成 '-'）
 * @property {1|2} term 学期序号
 * @property {string} termLabel 中文数字 '第一学期' / '第二学期'
 * @property {string} semesterStart 第 1 教学周的周一 '2026-08-31'
 * @property {string} semesterEnd 学期最后一天 '2027-01-10'
 * @property {number} totalWeeks 总周数 19
 * @property {number} teachingWeeks 上课周数 17
 * @property {number[]} examWeeks 复习考试周 [18, 19]
 * @property {string} [registerDate] 报到起始日
 * @property {string} [registerEnd] 报到结束日
 * @property {number} [statusRegisterWeek] 学籍注册所在教学周
 * @property {string} [winterBreakStart] 寒假开始日
 * @property {SemesterMilestone[]} milestones 关键节点
 * @property {Array<{week: number, start: string, end: string}>} weeks 19 周的起止表
 */

/**
 * 校历关键节点。
 * @typedef {object} SemesterMilestone
 * @property {string} date 'YYYY-MM-DD'
 * @property {string} title '秋季运动会'
 * @property {'holiday'|'event'|'enroll'|'teaching'|'vacation'|'exam'} type 节点类型（决定色点颜色）
 */

/**
 * 校历 JSON 根结构（教务处原件）。
 * @typedef {object} SchoolCalendar
 * @property {string} [_source] 来源说明
 * @property {string} [_verified_at] 核对日期
 * @property {Semester[]} semesters 学期数组（本项目当前只有 1 个）
 * @property {string} [holidayPolicy] 节假日调休政策说明
 * @property {Record<string, string>} [sources] 各系统入口地址
 */

/**
 * 顶栏上下文（由系统时间 + 校历实时计算，是首页顶栏的唯一数据源）。
 * @typedef {object} TermHeaderContext
 * @property {string} displayYear '26~27'
 * @property {string} termLabel '第一学期'
 * @property {number|null} week 当前教学周；不在学期内为 null
 * @property {'inTerm'|'beforeTerm'|'vacation'} phase 学期阶段
 * @property {string} left 左段 '26~27学年第一学期'
 * @property {string} text 右段 '第 10 教学周' / '假期中' / '距开学 3 天'
 * @property {string} full 完整串 '26~27学年第一学期 第 10 教学周'
 * @property {string} semesterId '2026-2027-1'
 * @property {number|null} [dayOfWeek] 今天星期几（1=周一 … 7=周日）
 */

/**
 * 学期下拉/筛选选项（成绩页、GPA 页共用）。
 * @typedef {object} SemesterOption
 * @property {string} id '2025-2026-1'
 * @property {string} label '2025-2026 学年第一学期'
 * @property {boolean} isCurrent 是否当前学期
 */

/* ══════════════════════════════════════════════════════════════════════════
 * 三、课程与课表
 * ════════════════════════════════════════════════════════════════════════ */

/**
 * 课程类型：必修 / 选修 / 公共 / 实践。决定课程卡配色与统计口径。
 * @typedef {'required'|'elective'|'public'|'practice'} CourseType
 */

/**
 * 考核方式：考试 / 考查。
 * @typedef {'exam'|'check'} Assessment
 */

/**
 * 课程（教学班维度，一次开课一条）。
 *
 * ⚠️ 关于 `weeks`（重要，见 docs/parser-adapter-spec.md §2.3）：
 *   真实正方课表的周次是**任意集合**（`1-8,10-16周`、`1-15周(单)`、`2-4,7,9-12周`），
 *   拆成「区间 + 单双周」会丢信息。因此：
 *   · `weeks` 是唯一真源（升序去重的完整周次集合）
 *   · 课表渲染判定一律用 `course.weeks.includes(weekNo)`
 *   · `weekRange` / `weekParity` 是**派生字段**，只用于列表文案「1-16周」「单周」，
 *     由 `weeks` 计算得出，不得反向写入
 *
 * @typedef {object} Course
 * @property {string} id 教学班 ID '2026-1-0831042-01'
 * @property {string} code 课程号 '0831042'
 * @property {string} name 课程名 '高等数学 A(上)'
 * @property {string} teacher 任课教师
 * @property {string} [teacherTitle] 职称
 * @property {number} credits 学分 4.5
 * @property {number} hours 总学时 72
 * @property {CourseType} courseType 课程类型
 * @property {Assessment} assessment 考核方式
 * @property {string} [campus] 校区
 * @property {string} location 上课地点 '博学楼 A302'（空串时由解析器置 '未安排'）
 * @property {string} [classNo] 教学班号 '01'
 * @property {number} capacity 容量（选课用）
 * @property {number} enrolled 已选人数（选课用）
 * @property {number[]} weeks 完整周次集合（唯一真源，升序去重）
 * @property {[number, number]} weekRange **派生**：`[min(weeks), max(weeks)]`，仅用于文案
 * @property {'all'|'odd'|'even'} weekParity **派生**：全奇数→'odd'、全偶数→'even'、否则 'all'
 * @property {1|2|3|4|5|6|7} dayOfWeek 星期（1=周一）
 * @property {number[]} sections 节次 [1,2] 表示第 1、2 节
 * @property {string} startTime '08:00'（查不到时由解析器置 '--:--'，不得抛错）
 * @property {string} endTime '09:40'
 * @property {boolean} isSelected 是否已选（选课页状态）
 */

/**
 * 节次时间表（课表左侧时间轴 / 解析器补全 startTime 用）。
 * @typedef {object} SectionTime
 * @property {number} section 节次序号 1-12
 * @property {string} startTime '08:00'
 * @property {string} endTime '08:45'
 */

/**
 * 首页「今日课程卡」的单条（从 Course 投影出来的展示用结构）。
 * @typedef {object} TodayCourse
 * @property {string} courseId 关联 Course.id
 * @property {string} name 课程名
 * @property {string} teacher 教师
 * @property {string} location 地点
 * @property {number[]} sections 节次
 * @property {string} timeText '08:00-09:40'
 * @property {CourseType} courseType 类型（决定配色）
 */

/* ══════════════════════════════════════════════════════════════════════════
 * 四、成绩与绩点
 * ════════════════════════════════════════════════════════════════════════ */

/**
 * 成绩单条记录。
 * `score` 为 null 表示「尚未录入」；`gradePoint` 为 null 表示缓考/缺考（不计入 GPA）。
 * @typedef {object} Grade
 * @property {string} id 成绩记录 ID
 * @property {string} courseId 关联 Course.id
 * @property {string} courseCode 课程号
 * @property {string} courseName 课程名
 * @property {number} credits 学分
 * @property {number|null} score 百分制成绩；null=未录入
 * @property {string} [scoreText] 文字成绩 '优秀' / '合格'
 * @property {number|null} gradePoint 绩点；缓考/缺考为 null
 * @property {CourseType} courseType 课程类型
 * @property {Assessment} assessment 考核方式
 * @property {string} semesterId 所属学期
 * @property {'normal'|'retake'|'makeup'} examType 正考 / 重修 / 补考
 * @property {boolean} isRetake 是否重修（影响绩点计算口径）
 * @property {string} [remark] '缓考' / '缺考' / '重修'
 * @property {{usual?: number, midterm?: number, final?: number}} [scoreDetail] 成绩构成
 */

/**
 * 学分构成树节点（教务「学生学业情况查询」原样输出）。
 * 扁平数组 + depth 表达层级：主修 depth=0，其子项 depth=1，依此类推。
 * 不做嵌套，是因为教务的容器标记不可靠、而缩进才是页面上真实的层级语义。
 * @typedef {object} GpaModule
 * @property {string} name '必修课公共部分'
 * @property {number} depth 缩进层级（0 = 主修）
 * @property {number} requireCredits 要求学分
 * @property {number} earnedCredits 获得学分
 * @property {number} missingCredits 未获得学分
 * @property {number} [requiredCount] 要求门次
 * @property {string} [reachedRaw] 到达要求，原样字符串（教务会给 '.60'，别转 0.6 显示）
 * @property {string} [unreachedRaw] 未达到要求，原样字符串
 * @property {number} [courseTotal] 共（N）门
 * @property {number} [coursePassed] 通过（N）门
 * @property {'并且'|'或者'|'缺省'} [logic] 节点规则标记
 */

/**
 * 学分绩点（P05）。全部字段取教务「学生学业情况查询」原样输出，客户端零计算。
 * 页面原文已声明「此页面信息仅做学业修读情况参考」。
 * 实测：本地加权 3.07 vs 教务 3.16，差 0.09 —— 故禁止任何本地加权算法。
 * @typedef {object} GPA
 * @property {number} gpa 平均学分绩点（3.16）
 * @property {'official'} gpaOrigin
 * @property {string} statsAt 教务标注的统计时间 '2026-11-06 00:36:27'
 * @property {{planned:number,passed:number,failed:number,notTaken:number,inProgress:number,extraPassed:number,extraFailed:number}} courseCount
 * @property {GpaModule[]} modules 学分构成树
 * @property {string} disclaimer '此页面信息仅做学业修读情况参考。'
 * @property {string} fetchedAt 抓取时刻 ISO 8601
 */

/* ══════════════════════════════════════════════════════════════════════════
 * 五、选课 / 推荐课表
 *
 * 「教学执行计划查看」按用户口径整条下线（2026-11-06）：原 TeachingPlan /
 * TeachingPlanModule 两个 typedef 已删除，页面、路由、数据槽与 provider 方法一并移除。
 * ════════════════════════════════════════════════════════════════════════ */

/**
 * 选课轮次。
 * @typedef {object} SelectionRound
 * @property {string} label '第一轮选课'
 * @property {string} openAt 开放时间 ISO
 * @property {string} closeAt 截止时间 ISO
 * @property {'pending'|'open'|'closed'} status 轮次状态
 */

/**
 * 选课页数据（P09）。
 * @typedef {object} CourseSelection
 * @property {string} semesterId 学期 ID
 * @property {SelectionRound} round 当前轮次
 * @property {Course[]} courses 可选课程池（含 capacity / enrolled / isSelected）
 */

/**
 * 推荐课表（P08）—— 教务「我的应用 → 推荐课表打印」的抓取结果。
 *
 * **不是 App 生成的选课方案**：教务系统本来就按培养方案给出一张推荐周课表，
 * 页面形态与「信息查询 → 个人课表查询」几乎一样。所以本模型与 `getTimetable()`
 * 的返回值同构，页面直接复用同一套 `buildGrid` 网格渲染。
 *
 * App 在这里只做三件事：抓取、映射成 Course、画成自己的网格。
 * **不做**推荐算法、**不算**匹配度、**不提供**「一键参照此课表选课」——
 * 那些是 v1 早期的错误设计，教务没有对应数据，写了就是在编。
 *
 * @typedef {object} RecommendedTimetable
 * @property {string} semesterId 学期 ID
 * @property {number[]} weeks 覆盖周次（与 getTimetable 一致）
 * @property {Course[]} courses 推荐课程（字段与 Course 完全一致，可直接交给 buildGrid）
 * @property {string} sourceNote 教务来源说明，页面上照抄，不允许改写
 * @property {string} fetchedAt 抓取时间 ISO
 */

/* ══════════════════════════════════════════════════════════════════════════
 * 六、校历页面
 * ════════════════════════════════════════════════════════════════════════ */

/**
 * 校历事件（月历色点 / 事件浮层）。
 * @typedef {object} CalendarEvent
 * @property {string} id 事件 ID
 * @property {string} date 起始日期 'YYYY-MM-DD'
 * @property {string} [endDate] 结束日期（跨天事件）
 * @property {string} title '秋季运动会'
 * @property {'holiday'|'event'|'enroll'|'teaching'|'vacation'|'exam'} type 类型
 * @property {number} [week] 所属教学周
 * @property {string} [note] 备注
 */

/**
 * 月历网格的一格（由 `calendarGrid.buildMonthGrid()` 生成）。
 * @typedef {object} MonthCell
 * @property {string} date 'YYYY-MM-DD'
 * @property {number} day 当月第几天
 * @property {boolean} inMonth 是否属于当前月（42 格里有跨月补位）
 * @property {boolean} isToday 是否今天
 * @property {number|null} weekNo 教学周角标；非学期内为 null
 * @property {Array<{title: string, type: string}>} events 当日节点
 */

/* 运行时契约（数据槽 / 路由 / 页面模块 / App 根状态）→ ./typesApp.js */

export {};
