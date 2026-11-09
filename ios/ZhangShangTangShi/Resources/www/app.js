/**
 * app.js —— 组合根（bootstrap）
 *
 * 全项目唯一允许跨层 import 的文件：把 core / components / data / pages 装到一起。
 * 次序：建 store → 装配 provider → 挂 appBar + tabBar → 建路由表 → 建 actions
 *      → 全局 data-action 委托 → boot（校历 + 会话恢复 + 启动路由）。
 *
 * 三条写在这里的硬约定：
 *   1. `window.__appBack()` 必须返回 boolean（true = App 已消费，false = 宿主该退出 App）。
 *      由 core/router.js 的 handleBack 提供，这是 T14 硬件返回键的唯一契约。
 *   2. 页面 render/mount 抛异常时由 ErrorBoundary（pages/errorPage.js）兜底，**绝不白屏**；
 *      实现方式是 router 的 onError 钩子，不另造一套异常机制。
 *   3. 演示会话写进 **sessionStorage**（见 appActions.js）：刷新保持登录态，冷启动回登录页。
 *
 * 拆出去的两块（既为守住 400 行上限，职责也更单一）：
 *   appState.js    初始 state 的字段清单
 *   appActions.js  语义化 action（状态怎么变）
 * 本文件只回答「谁在什么时候调用」。
 */

import { createStore } from './core/store.js';
import { createRouter } from './core/router.js';
import { bindActions } from './core/dom.js';
import { prefersReducedMotion } from './core/animate.js';
import * as storage from './core/storage.js';
import { toast } from './core/toast.js';
import { createAppBar } from './components/appBar.js';
import { createTabBar, TAB_ITEMS } from './components/tabBar.js';
import { withFallback } from './data/provider.js';
import { createRemoteProvider } from './data/remoteProvider.js';
import { demoProvider, DEMO_TODAY } from './data/demoProvider.js';
import {
  isoOfDayNumber,
  todayDayNumber,
} from './data/calendar.js';
import { createInitialState } from './appState.js';
import { createActions, readFlag, SS_BANNER, SS_DEMO, SS_IS_DEMO } from './appActions.js';
import { errorPage } from './pages/errorPage.js';
import { hasLocal } from './data/localTimetable.js';

const store = createStore(createInitialState());

/**
 * 数据源：**教务抓取为主，演示数据只做降级副本**。
 *
 * 用户点进任意功能模块 → loadSlot → remote getter → 抓教务对应页面 → 解析 → App UI。
 * 只有两种情况下走 demo：
 *   ① 用户显式点登录页的「先用演示数据看看」（session.status === 'demo'）
 *   ② remote 抓取失败，由 withFallback 自动降级，并写 source.effective='demo' 让顶栏提示
 *
 * 用动态代理而不是在启动时固定一个 provider，是因为会话状态会变（演示 ↔ 正式登录），
 * 而 provider 是注入给所有页面的单例，不能重建。
 */
const remoteProvider = createRemoteProvider();
const remoteWithFallback = withFallback(remoteProvider, demoProvider, store);
const provider = new Proxy({}, {
  get(_target, key) {
    const target = store.getState().session.status === 'demo' ? demoProvider : remoteWithFallback;
    const value = target[key];
    return typeof value === 'function' ? value.bind(target) : value;
  },
});

/** @type {ReturnType<typeof createRouter>|null} */
let router = null;
/** @type {ReturnType<typeof createTabBar>|null} */
let tabBar = null;

/* ── 顶栏 + Tab 栏 ─────────────────────────────────────────────────────── */

const mainEl = document.getElementById('app-main');
const appBarHost = document.getElementById('app-bar');
const tabBarHost = document.getElementById('tabbar');

const appBar = createAppBar({
  context: null,
  notice: false,
  scrollRoot: mainEl,
  onNoticeClose: () => actions.dismissBanner(),
});
if (appBarHost) appBarHost.appendChild(appBar.el);

tabBar = createTabBar({ active: 'home', onSelect: (_key, path) => router && router.go(path) });
if (tabBarHost) tabBarHost.appendChild(tabBar.el);

/* ── 路由表（§4.4）：全部页面均已交付，不再需要占位工厂 ─────────────── */

const routes = [
  { path: '/login', title: '登录', transition: 'fade', requiresAuth: false, load: () => import('./pages/login.js') },
  { path: '/home', tab: 'home', title: '首页', transition: 'fade', requiresAuth: true, load: () => import('./pages/home.js') },
  {
    path: '/timetable', tab: 'timetable', title: '我的课表', transition: 'fade', requiresAuth: false,
    load: () => import('./pages/timetable.js'),
  },
  {
    path: '/grades', tab: 'grades', title: '成绩', transition: 'fade', requiresAuth: true,
    load: () => import('./pages/grades.js'),
  },
  {
    path: '/grade/:id', title: '成绩详情', transition: 'push', requiresAuth: true,
    load: () => import('./pages/gradeDetail.js'),
  },
  {
    path: '/mine', tab: 'mine', title: '我的', transition: 'fade', requiresAuth: true,
    load: () => import('./pages/mine.js'),
  },
  // 「学生学业情况查看」按用户口径只展示 GPA，不展示学分树 / 门次统计
  { path: '/gpa', title: '学分绩点', transition: 'push', requiresAuth: true, load: () => import('./pages/gpa.js') },
  { path: '/selection', title: '选课', transition: 'push', requiresAuth: true, load: () => import('./pages/selection.js') },
  { path: '/recommend', title: '推荐课表', transition: 'push', requiresAuth: true, load: () => import('./pages/recommend.js') },
  { path: '/calendar', title: '校历', transition: 'push', requiresAuth: false, load: () => import('./pages/calendar.js') },
];

/** 会话是否已建立（演示态也算已登录） */
function isAuthed() {
  const status = store.getState().session.status;
  return status === 'authed' || status === 'demo';
}

router = createRouter({
  routes,
  container: mainEl,
  defaultPath: '/home',
  /** 组合根往页面 ctx 注入 store / actions / provider（router 自己不认识它们） */
  context: () => ({ store, actions, provider }),
  /**
   * 路由守卫：要会话的页面未登录时回登录页；已登录再进登录页直接去首页。
   * @returns {boolean}
   */
  guard(match) {
    if (match.route.requiresAuth && !isAuthed()) {
      router.go('/login');
      return false;
    }
    if (match.path === '/login' && isAuthed()) {
      router.go('/home');
      return false;
    }
    return true;
  },
  /** ErrorBoundary：页面抛错只在 #app-main 内部兜底，顶栏与 Tab 仍可用 */
  onError: ({ path, error }) => {
    // 兜底页也要让底部 Tab 高亮跟着路由走，否则出错时会停在上一页的 Tab 上
    const failed = routes.find((item) => item.path === path);
    if (tabBar && failed && failed.tab) tabBar.setActive(failed.tab);
    return errorPage({ path, error, onRetry: () => router.render() });
  },
  onNavigate: (match) => {
    const ui = store.getState().ui;
    store.patch({
      ui: { ...ui, route: match.path, params: match.params, activeTab: match.route.tab || ui.activeTab },
    });
    // 登录页没有 Tab 可切（点任何 Tab 都会被 guard 弹回来），整块外壳让位给品牌区
    document.body.dataset.shell = match.path === '/login' ? 'auth' : 'app';
    if (tabBar && match.route.tab) tabBar.setActive(match.route.tab);
    // 次级页（无 Tab 的 push 页）才有返回箭头，Tab 根页靠底部导航切走
    if (match.route.tab) appBar.setBack(null);
    else appBar.setBack(() => router && router.back());
  },
});

/* ── Actions（state 怎么变集中在这里，页面只调 action）──────────────────── */

const actions = createActions({ store, provider, getRouter: () => router });

/**
 * 外链统一在应用内 WebView 承载（2026-11-06 口径）：学工系统与首页「更多功能」（教务系统）
 * 共用这一条通道，都不跳系统浏览器。原生壳不存在时（桌面浏览器预览）才降级新标签页。
 * @param {string} url
 */
function openExternal(url) {
  if (!url) return;
  const bridge = window.NativeBridge;
  if (bridge && typeof bridge.openWebView === 'function') bridge.openWebView(url);
  else window.open(url, '_blank', 'noopener');
}

/* ── 全局事件委托（页面只写 data-action）────────────────────────────────── */

bindActions(document, {
  'dismiss-banner': () => actions.dismissBanner(),
  navigate: (event, el, payload) => {
    event.preventDefault();
    actions.navigate(payload || el.getAttribute('data-path') || '/home');
  },
  'retry-today': () => actions.loadSlot('todayCourses', { force: true }),
  course: (_event, _el, payload) => {
    // 课程详情页属于 T08；现在点课程卡先落到课表页，不做「点了没反应」的按钮
    actions.navigate('/timetable');
    if (payload) toast('课程详情在 T08 上线', 'info');
  },
  logout: () => actions.logout(),
  // 学工系统 / 教务系统「更多功能」：直接跳应用内 WebView，无原生壳才降级系统浏览器
  'open-external': (_event, _el, payload) => openExternal(payload),
  'open-xuegong': (_event, _el, payload) => openExternal(payload),
});

/* ── 订阅：顶栏文案 & 演示提示条 ───────────────────────────────────────── */

store.subscribe(
  (s) => s.term.context,
  (context) => appBar.setContext(context)
);
store.subscribe(
  (s) => ({ effective: s.source.effective, dismissed: s.source.bannerDismissed, authed: isAuthed() }),
  (value) => appBar.setNotice(value.authed && value.effective === 'demo' && !value.dismissed)
);

/* ── 启动 ─────────────────────────────────────────────────────────────── */

/**
 * 读校历、算顶栏上下文。
 *
 * 「今天」取哪个日期：demo 模式用 DEMO_TODAY（2026-11-05 周四，演示数据只为这天排满了课），
 * 真实模式用系统日期 —— 否则同一份演示数据每天看起来都不一样，验收不可复现。
 * @returns {Promise<void>}
 */
async function bootTerm() {
  try {
    const response = await fetch('./data/school-calendar.json', { cache: 'no-store' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const calendar = await response.json();

    const anchorIso = provider.mode === 'demo' ? DEMO_TODAY : isoOfDayNumber(todayDayNumber());
    // 学期上下文统一走 actions.recomputeTerm（校历 + 用户自定义覆盖 + 学期码下发）
    actions.recomputeTerm(calendar, anchorIso);
  } catch (err) {
    // 校历挂了顶栏保持占位，页面照常可用：宁可显示「—」也不伪造教学周
    console.warn('[zsts] 校历加载失败，顶栏保持占位', err);
  }
}

/** 加载页最短展示时长：没有会话可恢复时 boot 只要几十毫秒，
 *  若立刻淡出会变成「闪一下」的白光，比直接看到登录页更难受。 */
const MIN_SPLASH_MS = 420;
/** 模块开始执行的时刻（≈ 首帧后不久），用于计算加载页已显示了多久 */
const bootStartedAt = typeof performance !== 'undefined' ? performance.now() : Date.now();

/**
 * 收起冷启动加载页（index.html 里的 #boot-splash）。
 * 先淡出再移除：直接删节点会从「品牌页」硬切到「登录页」，观感像闪了一下。
 * 减少动态 / 找不到节点时立即移除，保证任何情况下都不会残留遮罩。
 * @returns {void}
 */
function dismissSplash() {
  const splash = document.getElementById('boot-splash');
  if (!splash) return;
  const now = typeof performance !== 'undefined' ? performance.now() : Date.now();
  const wait = Math.max(0, MIN_SPLASH_MS - (now - bootStartedAt));
  const hide = () => {
    if (prefersReducedMotion()) {
      splash.remove();
      return;
    }
    splash.classList.add('is-done');
    setTimeout(() => splash.remove(), 280);
  };
  if (wait > 0) setTimeout(hide, wait);
  else hide();
}

/**
 * 启动流程。
 * @returns {Promise<void>}
 */
async function boot() {
  const state = store.getState();
  const theme = document.documentElement.getAttribute('data-theme');

  // 会话恢复：区分「有会话」与「是不是演示态」，正式登录刷新后仍走教务抓取
  const resumed = readFlag(SS_DEMO);
  const isDemoSession = readFlag(SS_IS_DEMO);

  // Cookie 会话恢复（2026-11-06）：进程被杀后 sessionStorage 全丢，但 WebView
  // CookieManager 里的 JSESSIONID 还在磁盘上、教务会话往往未过期。有「记住学号」
  // 时先探测一次，通了直接回到已登录态 —— 重开 App 不必每次重输密码。
  let resumedRemote = false;
  let resumeName = '';
  if (!resumed && !isDemoSession && typeof window.NativeBridge !== 'undefined') {
    const rememberedNo = String(storage.get('rememberId') || '');
    if (rememberedNo) {
      try {
        const r = await remoteProvider.resumeSession(rememberedNo);
        resumedRemote = Boolean(r && r.ok);
        if (resumedRemote && r.data) resumeName = String(r.data.name || '');
      } catch { /* 探测失败走登录页，不阻塞启动 */ }
    }
  }

  store.patch({
    app: {
      ...state.app,
      env: typeof window.NativeBridge !== 'undefined' ? 'android' : 'web',
      theme: theme === 'dark' ? 'dark' : 'light',
      reducedMotion: prefersReducedMotion(),
    },
    session: resumed
      ? { ...state.session, status: isDemoSession ? 'demo' : 'authed' }
      : resumedRemote
        ? { ...state.session, status: 'authed', studentNo: String(storage.get('rememberId') || ''), name: resumeName, rememberId: true, error: null }
        : state.session,
    source: {
      ...state.source,
      effective: isDemoSession ? 'demo' : 'remote',
      bannerDismissed: isDemoSession ? readFlag(SS_BANNER) : false,
    },
  });

  await bootTerm();

  if (!window.location.hash) {
    // 免登录查看：本地存过课表就直接进课表页，不必先登录；
    // 连本地数据都没有才回登录页（否则新用户会看到一个空课表，比登录页更费解）。
    const semesterId = store.getState().term.context && store.getState().term.context.semesterId;
    const start = resumed || resumedRemote ? '#/home' : hasLocal(semesterId) ? '#/timetable' : '#/login';
    window.history.replaceState(null, '', start);
  }
  // 首次渲染前就把外壳形态定下来，避免登录页先闪一下底部 Tab
  document.body.dataset.shell = window.location.hash.startsWith('#/login') ? 'auth' : 'app';

  router.start();

  const booted = store.getState().app;
  store.patch({ app: { ...booted, booted: true } });

  // 调试/验收钩子（T15 后删）
  window.__zsts = { store, router, actions, provider, appBar, tabBar };
  console.log('[zsts] booted', store.getState().term.context);
}

// 加载页必须无条件收起：boot 抛错时交给 ErrorBoundary（router 的 onError）兜底，
// 而不是把用户永远留在品牌页上。
boot()
  .catch((err) => {
    console.error('[zsts] boot failed', err);
  })
  .finally(dismissSplash);

export { store, actions, router, provider, TAB_ITEMS };
