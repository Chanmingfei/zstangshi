/**
 * theme.js —— 主题选择与应用（core 层，全 App 生命周期可用）
 *
 * 为什么放 core：路由页面都是懒加载（app.js 的 load: () => import(...)），
 * 主题监听若挂在 mine.js 模块里，用户不进「我的」页监听器就永远不会注册 ——
 * 系统切深色 App 不跟随（2026-11-06 用户实测）。本模块被 app.js 启动即加载，
 * matchMedia 监听在这里注册一次，全生命周期有效。
 *
 * 主题三态存 localStorage（键 zsts.theme）：'light' / 'dark' / 未存=跟随系统。
 * WebView 的 prefers-color-scheme 反映壳 App 的日夜模式（values-v29 DayNight 主题），
 * 所以系统切换时 matchMedia 的 change 事件会真正触发。
 */

const THEME_KEY = 'zsts.theme';

/** 读当前主题选择（没存过就是跟随系统） */
export function readThemeChoice() {
  try {
    const saved = localStorage.getItem(THEME_KEY);
    return saved === 'light' || saved === 'dark' ? saved : 'system';
  } catch {
    return 'system';
  }
}

/** 应用主题：写选择 + 立刻改 data-theme 与状态栏颜色，刷新时由内联脚本接管 */
export function applyTheme(choice) {
  try {
    if (choice === 'system') localStorage.removeItem(THEME_KEY);
    else localStorage.setItem(THEME_KEY, choice);
  } catch { /* 隐私模式下只改当前页 */ }
  const systemDark = typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-color-scheme: dark)').matches;
  const actual = choice === 'system' ? (systemDark ? 'dark' : 'light') : choice;
  document.documentElement.setAttribute('data-theme', actual);
  const meta = document.querySelector('meta[name="theme-color"]');
  const palette = document.querySelector(`meta[name="theme-color-${actual}"]`);
  if (meta && palette) meta.setAttribute('content', palette.getAttribute('content'));
  return actual;
}

/* 跟随系统：系统深浅色切换时实时应用，不用重启 App。
 * addListener 为旧版 WebView 兼容；window 守卫保证 Node 侧测试可 import。 */
if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
  const darkQuery = window.matchMedia('(prefers-color-scheme: dark)');
  const onSchemeChange = () => {
    if (readThemeChoice() === 'system') applyTheme('system');
  };
  if (typeof darkQuery.addEventListener === 'function') {
    darkQuery.addEventListener('change', onSchemeChange);
  } else if (typeof darkQuery.addListener === 'function') {
    darkQuery.addListener(onSchemeChange);
  }
}
