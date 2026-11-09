/**
 * appState.js —— 应用初始 state 的形状与默认值
 *
 * 从 app.js 拆出来只为一件事：app.js 要在 400 行以内（check-rules 守卫③），而组合根
 * 里最占篇幅、最不需要「读流程」的部分就是这段字段清单。形状的唯一真源仍是
 * core/store.js 顶部的注释（ARCHITECTURE §4.3），本文件与它逐字段对应。
 *
 * 只导出工厂函数，不持有任何单例 —— 每次调用都返回全新对象，测试里可以随便造。
 */

import { isoOfDayNumber, todayDayNumber } from './data/calendar.js';

/** 应用版本号（「我的」页与关于页展示） */
export const APP_VERSION = '1.6.0';

/** 8 个数据槽的初始形状（status ∈ idle/loading/ready/stale/error） */
function emptySlot() {
  return { status: 'idle', data: null, error: null, updatedAt: null, fromCache: false, degraded: false };
}

/**
 * 建初始 state。
 * @returns {object}
 */
export function createInitialState() {
  return {
    /* ── 应用外壳 ── */
    app: {
      booted: false,
      env: 'web', // 'web' | 'android'
      version: APP_VERSION,
      theme: 'light',
      themeSource: 'system',
      offline: false,
      reducedMotion: false,
    },

    /* ── 会话 ── */
    session: { status: 'guest', studentNo: '', rememberId: false, name: '', error: null },

    /* ── 数据源 ── */
    source: {
      preferred: 'remote',
      effective: 'remote',
      probing: false,
      probe: { reachable: false, latencyMs: null, reason: null },
      bannerDismissed: false,
      lastFallbackAt: null,
      lastError: null,
    },

    /* ── 学期与时间（顶栏唯一来源）── */
    term: {
      calendar: null,
      context: null,
      todayIso: isoOfDayNumber(todayDayNumber()),
      semesters: [],
    },

    /* ── 数据槽 ── */
    slots: {
      profile: emptySlot(),
      todayCourses: emptySlot(),
      timetable: emptySlot(),
      grades: emptySlot(),
      /** 单条成绩详情（自带 id：直刷也能知道「当前看的是哪一条」） */
      gradeDetail: { ...emptySlot(), id: '' },
      gpa: emptySlot(),
      selection: emptySlot(),
      recommend: emptySlot(),
    },

    /* ── UI 局部状态（不入缓存）── */
    ui: {
      route: '/login',
      params: {},
      activeTab: 'home',
      weekNo: 1,
      semesterFilter: '',
      gradesQuery: '',
      selectionFilter: { type: 'all', dept: 'all', onlyAvailable: false },
      selectionIntent: [],
      calendarMonth: isoOfDayNumber(todayDayNumber()).slice(0, 7),
      sheet: null,
      busy: {},
      toasts: [],
    },

    /* ── 缓存统计 ── */
    cache: { totalBytes: 0, lastClearedAt: null },
  };
}
