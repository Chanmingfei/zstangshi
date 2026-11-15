/**
 * demoProvider.js —— 演示数据源（Provider 契约的 demo 实现）
 *
 * 契约见 `web/data/provider.js`（对应 docs/ARCHITECTURE.md §4.2）。三个不可妥协的点：
 *   1. **永不 throw**：任何分支都返回 ProviderResult（`ok:false` + `error`）。
 *   2. **120–320ms 随机延迟**：否则骨架屏、下拉刷新、转场动画全都验收不出来。
 *   3. **数据只来自 `demo/index.js`**：本文件不内联任何业务数据，只做投影、过滤、派生。
 *
 * 关于 `getGPA`：**直接取教务权威值，零计算**。教务「学生学业情况查询」页面自己印出
 * 「平均学分绩点（GPA）： 3.16」，我们就照抄 3.16 —— 本地按 grades 加权只有 3.07，
 * 差 0.09（公选课不计入总绩点、重修取首次成绩、五级制单独折算，三个口径都不一样）。
 * 学生看到两个数字会直接质疑可信度，所以这里**禁止任何加权算法**。
 * 该页面是累计快照，没有学期维度，因此 `getGPA` 无参数。
 *
 * 失败注入：`?fail=grades`、`?fail=timetable`、`?fail=toggle`、`?fail=grades,gpa`、
 * `?fail=all`（或 `?fail=1`）都会让对应方法返回 `NETWORK`。用于验收「缓存 + 上次同步」态。
 *
 * 依赖：`demo/index.js`、`calendar.js`（教学周纯函数）、`provider.js`（结果构造）、
 * `core/storage.js`（仅供 clearCache 统计释放字节）。**不 import 任何 DOM / 组件**。
 */

import { DEMO } from './demo/index.js';
import { pickSemester, buildTermHeader, dayNumberOfIso, weekdayOfDayNumber, weekOfDate } from './calendar.js';
import { ok, fail, makeError } from './provider.js';
import * as storage from '../core/storage.js';

/** 演示数据的来源标记，贯穿所有 meta。 */
const SOURCE = 'demo';

/**
 * demo 的「今天」锚点（'2026-11-05'，周四，第 10 教学周）。
 *
 * 为什么把它导出：演示数据只为这一天排满了课（4 门）。如果组合根用系统真实日期去问
 * 「今天有什么课」，同一份演示数据每天看到的都不一样，验收就没法复现了。所以 demo 模式下
 * 「今天」一律取这个锚点；切到 remote 后自然改回系统日期（见 app.js 的 bootTerm）。
 */
export const DEMO_TODAY = DEMO.today;

/** 120–320ms 随机延迟（含两端）。 */
function wait() {
  const ms = 120 + Math.floor(Math.random() * 201);
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 解析 `?fail=` 注入目标。每次调用现读 location，便于测试里改写 `globalThis.location`。
 * @returns {Set<string>}
 */
function failTargets() {
  const search = typeof location !== 'undefined' && location && location.search ? location.search : '';
  const raw = new URLSearchParams(search).get('fail') || '';
  const set = new Set(raw.split(',').map((s) => s.trim().toLowerCase()).filter(Boolean));
  if (set.has('1') || set.has('all')) set.add('__all__');
  return set;
}

/**
 * 该方法是否被注入为失败。
 * @param {string} name 方法名去掉 get 前缀后的短名，如 'grades'
 * @returns {boolean}
 */
function hits(name) {
  const set = failTargets();
  return set.has('__all__') || set.has(name);
}

/** 深拷贝（演示数据要可变，不能污染常量）。 */
function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

/** 可变的演示态：会话 + 选课池（toggleCourse 是唯一写操作）。 */
const state = {
  /** @type {object|null} */
  session: null,
  selection: clone(DEMO.selection),
};

/** 取当前学期对象（DEMO 里 isCurrent 的那个）。 */
function currentSemester() {
  return DEMO.semesters.find((s) => s.isCurrent) || DEMO.semesters[DEMO.semesters.length - 1];
}

export const demoProvider = {
  mode: SOURCE,

  /* ── 会话 ─────────────────────────────────────────────────────────────── */

  /** demo 视为「可达」，延迟按真实探测的体感给一个固定值。 */
  async probe() {
    await wait();
    if (hits('probe')) return fail(makeError('NETWORK'), { source: SOURCE });
    return ok({ reachable: true, latencyMs: 12 }, { source: SOURCE });
  },

  /**
   * demo 模式：任意学号 + 任意非空密码都成功，但格式要合规。
   * @param {string} studentNo 10-12 位数字
   * @param {string} password 非空
   */
  async login(studentNo, password) {
    await wait();
    if (hits('login')) return fail(makeError('NETWORK'), { source: SOURCE });
    const no = String(studentNo || '').trim();
    const pwd = String(password || '');
    if (!/^\d{10,12}$/.test(no)) {
      return fail(makeError('AUTH', { message: '学号应为 10-12 位数字' }), { source: SOURCE });
    }
    if (pwd.length === 0) {
      return fail(makeError('AUTH', { message: '请输入密码' }), { source: SOURCE });
    }
    const now = Date.now();
    state.session = {
      studentNo: no,
      name: DEMO.profile.name,
      mode: SOURCE,
      acquiredAt: new Date(now).toISOString(),
      expiresAt: new Date(now + 7 * 24 * 3600 * 1000).toISOString(),
    };
    return ok(clone(state.session), { source: SOURCE });
  },

  /** 退出登录：清会话 + 清缓存（保留主题与记住学号）。 */
  async logout() {
    await wait();
    state.session = null;
    try {
      storage.clearAll({ keep: ['theme', 'rememberId', 'source'] });
    } catch {
      /* 存储不可用时忽略 */
    }
  },

  /**
   * **同步**方法（§4.2 原文 `getSession(): Session | null`），无延迟、不返回 Result。
   * @returns {object|null}
   */
  getSession() {
    return state.session ? clone(state.session) : null;
  },

  /* ── 校历与顶栏 ───────────────────────────────────────────────────────── */

  /**
   * 顶栏上下文（首页唯一数据源）。
   * @param {Date} [now] 缺省取当前时间；演示模式可传 new Date('2026-11-05T09:00:00+08:00')
   */
  async getTermContext(now = new Date()) {
    await wait();
    if (hits('term')) return fail(makeError('NETWORK'), { source: SOURCE });
    const semester = pickSemester(now, { semesters: DEMO.semesters });
    return ok(buildTermHeader(now, semester), { source: SOURCE });
  },

  /** 学期下拉选项（成绩页 / GPA 页共用）。 */
  async getSemesters() {
    await wait();
    if (hits('semesters')) return fail(makeError('NETWORK'), { source: SOURCE });
    const list = DEMO.semesters.map((s) => ({
      semesterId: s.id,
      label: `${s.academicYear} 学年${s.termLabel}`,
      isCurrent: Boolean(s.isCurrent),
    }));
    return ok(list, { source: SOURCE });
  },

  /* ── 课表 ─────────────────────────────────────────────────────────────── */

  /**
   * 某天要上的课，按第一节次升序。
   * @param {string} dateIso 'YYYY-MM-DD'
   * @param {number} [week] 缺省由 dateIso 推算
   */
  async getTodayCourses(dateIso, week) {
    await wait();
    if (hits('today') || hits('courses')) return fail(makeError('NETWORK'), { source: SOURCE });
    const semester = currentSemester();
    const dayOfWeek = weekdayOfDayNumber(dayNumberOfIso(dateIso));
    const weekNo = Number(week) || weekOfDate(dateIso, semester);
    if (!dayOfWeek || !weekNo) return ok([], { source: SOURCE });
    const list = DEMO.timetable
      .filter((c) => c.dayOfWeek === dayOfWeek && c.weeks.includes(weekNo))
      .sort((a, b) => a.sections[0] - b.sections[0]);
    return ok(clone(list), { source: SOURCE });
  },

  /**
   * 整学期课表。`weeks` 是唯一真源，`weekRange` / `weekParity` 由调用方派生。
   * @param {string} [semesterId]
   */
  async getTimetable(semesterId) {
    await wait();
    if (hits('timetable')) return fail(makeError('NETWORK'), { source: SOURCE });
    const semester = DEMO.semesters.find((s) => s.id === semesterId) || currentSemester();
    const weeks = [];
    for (let w = 1; w <= semester.totalWeeks; w += 1) weeks.push(w);
    return ok({ semesterId: semester.id, weeks, courses: clone(DEMO.timetable) }, { source: SOURCE });
  },

  /* ── 成绩与绩点 ───────────────────────────────────────────────────────── */

  /**
   * 成绩列表。
   * @param {string} [semesterId] 'all' 或具体学期
   */
  async getGrades(semesterId = 'all') {
    await wait();
    if (hits('grades')) return fail(makeError('NETWORK'), { source: SOURCE });
    const list = !semesterId || semesterId === 'all'
      ? DEMO.grades.slice()
      : DEMO.grades.filter((g) => g.semesterId === semesterId);
    return ok(clone(list), { source: SOURCE });
  },

  /**
   * 单条成绩详情。查不到返回 `ok(null)`（页面自己显示「记录已失效」），不是错误。
   * @param {string} id
   */
  async getGradeById(id) {
    await wait();
    if (hits('gradedetail') || hits('grades')) return fail(makeError('NETWORK'), { source: SOURCE });
    const found = DEMO.grades.find((g) => g.id === id) || null;
    return ok(found ? clone(found) : null, { source: SOURCE });
  },

  /**
   * 学分绩点。**直接返回教务「学生学业情况查询」的权威值**，不做任何加权计算。
   * 无参数：该页面是累计快照，没有学期维度。
   */
  async getGPA() {
    await wait();
    if (hits('gpa')) return fail(makeError('NETWORK'), { source: SOURCE });
    return ok(clone(DEMO.gpa), { source: SOURCE });
  },

  /* ── 选课与推荐 ───────────────────────────────────────────────────────── */

  /** 选课池（含 toggleCourse 之后的实时 isSelected / enrolled）。 */
  async getCourseSelection() {
    await wait();
    if (hits('selection')) return fail(makeError('NETWORK'), { source: SOURCE });
    return ok(clone(state.selection), { source: SOURCE });
  },

  /**
   * **唯一的写操作**：纯内存改动 `isSelected` 与 `enrolled`（±1）。
   * 失败注入命中时**先回滚再返回 ok:false**，保证调用方看到的始终是自洽状态。
   * @param {string} courseId
   * @param {boolean} selected 目标状态
   */
  async toggleCourse(courseId, selected) {
    await wait();
    const course = state.selection.courses.find((c) => c.id === courseId);
    if (!course) {
      return fail(makeError('UNKNOWN', { message: '课程不存在或已下架', retryable: false }), { source: SOURCE });
    }
    const want = Boolean(selected);
    const before = { isSelected: course.isSelected, enrolled: course.enrolled };
    if (course.isSelected !== want) {
      course.isSelected = want;
      const next = course.enrolled + (want ? 1 : -1);
      course.enrolled = Math.max(0, Math.min(course.capacity, next));
    }
    if (hits('toggle') || hits('selection')) {
      // 注入失败：回滚到调用前的值
      course.isSelected = before.isSelected;
      course.enrolled = before.enrolled;
      return fail(makeError('NETWORK'), { source: SOURCE });
    }
    return ok({ courseId, isSelected: course.isSelected, enrolled: course.enrolled }, { source: SOURCE });
  },

  /**
   * 推荐课表：教务「我的应用 → 推荐课表打印」的一张周课表，与个人课表同构。
   * 不是多套方案，页面直接复用同一套 buildGrid 网格。
   */
  async getRecommendedTimetable() {
    await wait();
    if (hits('recommend')) return fail(makeError('NETWORK'), { source: SOURCE });
    return ok(clone(DEMO.recommend), { source: SOURCE });
  },

  /* ── 档案与缓存 ───────────────────────────────────────────────────────── */

  /** 学生档案。 */
  async getProfile() {
    await wait();
    if (hits('profile')) return fail(makeError('NETWORK'), { source: SOURCE });
    return ok(clone(DEMO.profile), { source: SOURCE });
  },

  /** 清缓存，返回实际释放的字节数（保留主题、记住学号、数据源选择）。 */
  async clearCache() {
    await wait();
    if (hits('cache') || hits('clear')) return fail(makeError('NETWORK'), { source: SOURCE });
    let before = 0;
    let after = 0;
    try {
      before = storage.sizeBytes();
      storage.clearAll({ keep: ['theme', 'rememberId', 'source'] });
      after = storage.sizeBytes();
    } catch {
      /* 存储不可用时 freedBytes 记 0 */
    }
    return ok({ freedBytes: Math.max(0, before - after) }, { source: SOURCE });
  },
};

export default demoProvider;
