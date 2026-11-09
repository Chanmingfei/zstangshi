/**
 * widget.js —— 「今日课程」桌面小组件的数据快照
 *
 * 桌面小组件是**原生渲染**的（RemoteViews），它不能跑 JS，也不能在桌面上去请求教务。
 * 所以分工是这样：
 *   · H5 负责「算」：从本地课表里筛出今天的课，算好时间与节次，序列化成一份快照；
 *   · 原生负责「画」：把快照存进 SharedPreferences，App 不在运行时也能照着画。
 *
 * 三个刻意的设计：
 *   1. 快照里**不带「现在讲到哪一节」这种状态**。状态随时间变，写死在快照里必然过期；
 *      原生每次渲染时按当前时钟自己算，因此只要在「上课 / 下课」这些边界上重画一次就够。
 *   2. 颜色不下发 hex，只下发 `courseType`。深色模式的色值由原生 `values-night`
 *      资源决定，H5 下发具体色值会锁死主题。
 *   3. 空数据分两种：`hasData=false`（从没同步过，应引导用户打开 App）与
 *      `hasData=true` 且 `courses=[]`（真的今天没课）。混在一起会让新生看到「今天没课」
 *      而以为自己课表丢了。
 *
 * 本文件不碰 DOM，`buildWidgetSnapshot` 是纯函数，可直接单测。
 */

import { PERIOD_TIMES } from './jwxtParse.js';
import { WEEKDAY_LABELS } from './calendar.js';
import { readLocal } from './localTimetable.js';

/** 快照结构版本：原生侧不认的版本会拒绝渲染（避免字段改了以后画出半截） */
export const WIDGET_VERSION = 1;

/** 小组件最多画几门课（原生布局固定 4 行，超出的折成「还有 N 门」） */
export const MAX_WIDGET_COURSES = 4;

/**
 * 节次 → 'HH:MM'。取第一节的开始与最后一节的结束。
 * @param {number[]} sections
 * @returns {{start: string, end: string}}
 */
export function timeOfSections(sections) {
  const list = (Array.isArray(sections) ? sections : [])
    .map(Number)
    .filter((n) => Number.isInteger(n) && n >= 1 && n <= PERIOD_TIMES.length)
    .sort((a, b) => a - b);
  if (list.length === 0) return { start: '--:--', end: '--:--' };
  const first = PERIOD_TIMES[list[0] - 1];
  const last = PERIOD_TIMES[list[list.length - 1] - 1];
  return { start: first.startTime, end: last.endTime };
}

/**
 * 节次文案：「第 1-2 节」「第 3,5 节」。
 * @param {number[]} sections
 * @returns {string}
 */
export function sectionsText(sections) {
  const list = (Array.isArray(sections) ? sections : []).map(Number).filter(Number.isInteger).sort((a, b) => a - b);
  if (list.length === 0) return '—';
  const contiguous = list.every((n, i) => i === 0 || n === list[i - 1] + 1);
  if (contiguous && list.length > 1) return `第 ${list[0]}-${list[list.length - 1]} 节`;
  if (list.length === 1) return `第 ${list[0]} 节`;
  return `第 ${list.join(',')} 节`;
}

/**
 * 筛出某一天要上的课（按开始时间排序，板块占位行不算课）。
 *
 * `rotating`（本周没课、隔周才有）不进来：小组件是「今天上什么」，
 * 把下周才上的课摆上去，学生会照着去教室。
 *
 * @param {object[]} courses 整张课表
 * @param {number} dayOfWeek 1..7
 * @param {number} weekNo
 * @returns {object[]}
 */
export function coursesOfDay(courses, dayOfWeek, weekNo) {
  const day = Math.min(7, Math.max(1, Number(dayOfWeek) || 1));
  const week = Number(weekNo) || 0;
  return (Array.isArray(courses) ? courses : [])
    .filter((course) => course && !course.isPlaceholder)
    .filter((course) => Number(course.dayOfWeek) === day)
    .filter((course) => Array.isArray(course.weeks) && course.weeks.includes(week))
    .filter((course) => Array.isArray(course.sections) && course.sections.length > 0)
    .map((course) => {
      const time = timeOfSections(course.sections);
      return {
        id: String(course.id || ''),
        name: String(course.name || '未命名课程'),
        location: String(course.location || '').trim(),
        teacher: String(course.teacher || '').trim(),
        // courseType 决定原生侧的颜色（必修蓝 / 选修浅蓝 / 公共绿 / 实践金）
        courseType: String(course.courseType || 'required'),
        sectionsText: sectionsText(course.sections),
        start: time.start,
        end: time.end,
        from: Math.min(...course.sections.map(Number)),
      };
    })
    .sort((a, b) => (a.start === b.start ? a.from - b.from : a.start.localeCompare(b.start)));
}

/**
 * 构建小组件快照（纯函数）。
 *
 * @param {object[]} courses 整张课表（本地课表优先）
 * @param {object} options
 * @param {number} options.weekNo 教学周
 * @param {number} options.dayOfWeek 1..7
 * @param {string} options.todayIso 'YYYY-MM-DD'
 * @param {string} [options.semesterId]
 * @param {boolean} [options.hasData] 是否有课表数据（false 时原生显示「去 App 同步」）
 * @returns {object} 可直接 JSON.stringify 的快照
 */
export function buildWidgetSnapshot(courses, options = {}) {
  const weekNo = Number(options.weekNo) || 0;
  const dayOfWeek = Math.min(7, Math.max(1, Number(options.dayOfWeek) || 1));
  const hasData = options.hasData !== false && Array.isArray(courses) && courses.length > 0;
  const today = coursesOfDay(courses, dayOfWeek, weekNo);
  const [, month, day] = String(options.todayIso || '').split('-');

  return {
    v: WIDGET_VERSION,
    updatedAt: new Date().toISOString(),
    semesterId: String(options.semesterId || ''),
    weekNo,
    dayOfWeek,
    weekday: WEEKDAY_LABELS[dayOfWeek - 1] || '',
    // 原生不拼中文日期（Java 侧做中文拼接要处理「11 月 5 日」不补零的细节），H5 拼好给
    dateText: month && day ? `${Number(month)} 月 ${Number(day)} 日` : '',
    hasData,
    total: today.length,
    courses: today,
  };
}

/**
 * 把快照推给原生（非原生环境静默跳过）。
 *
 * 桥方法名 `pushWidget` 与 `NativeBridge.java` 的 @JavascriptInterface 必须一字不差；
 * 老版本 APK 没有这个方法，`typeof` 判断保证降级成「什么都不做」而不是抛异常。
 *
 * @param {object} snapshot
 * @returns {boolean} 是否真的推给了原生
 */
export function pushWidget(snapshot) {
  const bridge = typeof window !== 'undefined' ? window.NativeBridge : null;
  if (!bridge || typeof bridge.pushWidget !== 'function') return false;
  try {
    bridge.pushWidget(JSON.stringify(snapshot));
    return true;
  } catch (err) {
    // 桥异常不能影响课表页本身：宁可桌面小组件不更新，也不能让页面崩掉
    console.warn('[zsts] 小组件快照下发失败', err);
    return false;
  }
}

/**
 * 从应用 state 里取本地课表 → 构建快照 → 下发。
 *
 * @param {object} state 应用 state（需要 term.context / term.todayIso）
 * @param {object} [override] 调用方直接给课程（课表页正在展示的那份，可能来自教务槽位）
 * @param {string[]} [override.courses]
 * @returns {object} 下发的快照（原生不存在时也返回，便于测试）
 */
export function syncWidget(state, override = {}) {
  const term = (state && state.term) || {};
  const context = term.context || {};
  const semesterId = context.semesterId;
  const local = readLocal(semesterId);
  const fallback = Array.isArray(override.courses) ? override.courses : [];
  const courses = local.courses.length ? local.courses : fallback;

  const dayOfWeek = weekdayOfIso(term.todayIso);
  const snapshot = buildWidgetSnapshot(courses, {
    weekNo: context.week || 1,
    dayOfWeek,
    todayIso: term.todayIso,
    semesterId,
    hasData: courses.length > 0,
  });
  pushWidget(snapshot);
  return snapshot;
}

/**
 * 'YYYY-MM-DD' → 1..7（周日归 7）。
 * @param {string} iso
 * @returns {number}
 */
export function weekdayOfIso(iso) {
  if (!iso) return 1;
  const date = new Date(`${iso}T12:00:00`);
  const day = date.getDay();
  return Number.isFinite(day) && day >= 0 ? (day === 0 ? 7 : day) : 1;
}
