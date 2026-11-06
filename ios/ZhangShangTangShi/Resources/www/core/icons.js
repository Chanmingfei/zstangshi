/**
 * icons.js —— 内联 SVG 图标库（手写，无字体、无 CDN、无外链）
 *
 * 为什么不用图标字体/图标库：零依赖铁律 + 离线可用 + 子集化成本。
 * 全部图标 24×24 viewBox、`stroke="currentColor"`、无填充，颜色由 CSS 决定，
 * 因此**本文件不出现任何色值**（check-rules 守卫① 会盯）。
 *
 * 用法：
 *   import { icon } from '../core/icons.js';
 *   el.innerHTML = icon('home');                     // 24px
 *   el.innerHTML = icon('close', 16, { strokeWidth: 1.9 });
 *
 * 新增图标的规矩：只用 24 网格、只用 stroke（circle/rect/path 的 fill 保持 none）、
 * 线宽默认 1.75，视觉重心落在 12,12。
 */

/** 图标名 → SVG 内部路径串。key 用 camelCase，全项目统一。 */
const ICONS = {
  /* ── 底部 Tab（4 个，与 components/tabBar.js 一一对应）── */
  home:
    '<path d="M4 10.4 12 4l8 6.4V19a1.4 1.4 0 0 1-1.4 1.4H5.4A1.4 1.4 0 0 1 4 19z"/>' +
    '<path d="M9.6 20.4v-5.6h4.8v5.6"/>',
  timetable:
    '<rect x="3.6" y="5.2" width="16.8" height="15.2" rx="2.4"/>' +
    '<path d="M8.2 3.4v3.2M15.8 3.4v3.2M3.6 10.2h16.8M8.2 13.8h2.4M13.4 13.8h2.4"/>',
  grades:
    '<path d="M4.6 19.4V11M12 19.4V4.6M19.4 19.4v-5.2"/><path d="M3 19.8h18"/>',
  mine:
    '<circle cx="12" cy="8.4" r="3.6"/>' +
    '<path d="M4.8 20c1.1-3.6 3.9-5.5 7.2-5.5s6.1 1.9 7.2 5.5"/>',

  /* ── 导航与方向 ── */
  back: '<path d="M14.5 5.5 8 12l6.5 6.5"/>',
  forward: '<path d="M9.5 5.5 16 12l-6.5 6.5"/>',
  chevronRight: '<path d="m9.5 5.5 6.5 6.5-6.5 6.5"/>',
  chevronDown: '<path d="m6 9.5 6 6 6-6"/>',
  chevronUp: '<path d="m6 14.5 6-6 6 6"/>',
  close: '<path d="M7 7l10 10M17 7 7 17"/>',

  /* ── 通用操作 ── */
  search: '<circle cx="10.5" cy="10.5" r="6"/><path d="m15.2 15.2 4.3 4.3"/>',
  refresh: '<path d="M20 12a8 8 0 1 1-2.6-5.9"/><path d="M20.4 4.4v4.4h-4.4"/>',
  filter: '<path d="M4 6h16M7 12h10M10 18h4"/>',
  more: '<path d="M5 12h.01M12 12h.01M19 12h.01" stroke-width="2.4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  minus: '<path d="M5 12h14"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  checkCircle: '<circle cx="12" cy="12" r="8.4"/><path d="m8.4 12 2.6 2.6 4.6-4.6"/>',
  copy: '<rect x="8.5" y="8.5" width="11" height="11" rx="2"/>' +
    '<path d="M15.5 5.5A1.5 1.5 0 0 0 14 4H5.5A1.5 1.5 0 0 0 4 5.5V14a1.5 1.5 0 0 0 1.5 1.5"/>',
  download: '<path d="M12 4v10"/><path d="m7.5 10.5 4.5 4.5 4.5-4.5"/><path d="M4.5 19.5h15"/>',
  external:
    '<path d="M14 4h6v6"/><path d="M20 4 11.5 12.5"/>' +
    '<path d="M18.5 14v4.5A1.5 1.5 0 0 1 17 20H5.5A1.5 1.5 0 0 1 4 18.5V7a1.5 1.5 0 0 1 1.5-1.5H10"/>',
  settings:
    '<path d="M4 7h4M12 7h8M4 12h8M16 12h4M4 17h4M12 17h8"/>' +
    '<circle cx="10" cy="7" r="2"/><circle cx="14" cy="12" r="2"/><circle cx="10" cy="17" r="2"/>',

  /* ── 状态与提示 ── */
  alert: '<path d="M12 4.4 21 19.6H3z"/><path d="M12 10v4"/><path d="M12 16.6h.01"/>',
  alertCircle: '<circle cx="12" cy="12" r="8.4"/><path d="M12 8v5"/><path d="M12 16.2h.01"/>',
  info: '<circle cx="12" cy="12" r="8.4"/><path d="M12 11v5"/><path d="M12 8h.01"/>',
  wifiOff:
    '<path d="M3 8.6a15 15 0 0 1 18 0"/><path d="M6.4 12a10 10 0 0 1 11.2 0"/>' +
    '<path d="M9.6 15.4a5 5 0 0 1 4.8 0"/><path d="M12 19h.01"/>' +
    '<path d="M3.5 3.5l17 17"/>',
  lock:
    '<rect x="4.6" y="10.4" width="14.8" height="9.6" rx="2.4"/>' +
    '<path d="M8.4 10.4V8a3.6 3.6 0 0 1 7.2 0v2.4"/>',

  /* ── 主题 ── */
  moon: '<path d="M20 13.5A8.2 8.2 0 0 1 10.5 4a8.4 8.4 0 1 0 9.5 9.5z"/>',
  sun:
    '<circle cx="12" cy="12" r="4"/>' +
    '<path d="M12 2.6v2.4M12 19v2.4M2.6 12h2.4M19 12h2.4M5.4 5.4l1.7 1.7M16.9 16.9l1.7 1.7M18.6 5.4l-1.7 1.7M7.1 16.9l-1.7 1.7"/>',
  eye: '<path d="M2.6 12S6 6.4 12 6.4 21.4 12 21.4 12 18 17.6 12 17.6 2.6 12 2.6 12z"/>' +
    '<circle cx="12" cy="12" r="2.8"/>',
  eyeOff:
    '<path d="M2.6 12S6 6.4 12 6.4 21.4 12 21.4 12 18 17.6 12 17.6 2.6 12 2.6 12z"/>' +
    '<circle cx="12" cy="12" r="2.8"/><path d="M4 4l16 16"/>',

  /* ── 教务业务 ── */
  calendar:
    '<rect x="3.6" y="5.2" width="16.8" height="15.2" rx="2.4"/>' +
    '<path d="M8.2 3.4v3.2M15.8 3.4v3.2M3.6 10.2h16.8"/>',
  clock: '<circle cx="12" cy="12" r="8.4"/><path d="M12 7.6V12l3 1.8"/>',
  location:
    '<path d="M12 20.8s6.4-5.6 6.4-10.4a6.4 6.4 0 1 0-12.8 0C5.6 15.2 12 20.8 12 20.8z"/>' +
    '<circle cx="12" cy="10.4" r="2.4"/>',
  teacher:
    '<path d="M8.8 12.2a3.1 3.1 0 1 0 0-6.2 3.1 3.1 0 0 0 0 6.2z"/>' +
    '<path d="M2.6 19.6c.9-3.1 3.3-4.8 6.2-4.8s5.3 1.7 6.2 4.8"/>' +
    '<path d="m15.4 14.6 5.2-2.2-5.2-2.2"/>',
  book:
    '<path d="M4 5.4h5.2A2.8 2.8 0 0 1 12 8.2V20a2.4 2.4 0 0 0-2.4-2.4H4z"/>' +
    '<path d="M20 5.4h-5.2A2.8 2.8 0 0 0 12 8.2V20a2.4 2.4 0 0 1 2.4-2.4H20z"/>',
  award: '<circle cx="12" cy="9" r="5"/><path d="m8.6 13.4-1.6 6.4L12 17.6l5 2.2-1.6-6.4"/>',
  target:
    '<circle cx="12" cy="12" r="8.4"/><circle cx="12" cy="12" r="4.8"/>' +
    '<circle cx="12" cy="12" r="1.4"/>',
  chart: '<path d="m4 16.5 5-5.5 3.5 3.5L20 6.5"/><path d="M4 20h16"/>',
  list:
    '<path d="M8 6.5h12M8 12h12M8 17.5h12"/>' +
    '<path d="M4.2 6.5h.01M4.2 12h.01M4.2 17.5h.01" stroke-width="2.2"/>',
  grid:
    '<rect x="4" y="4" width="6.5" height="6.5" rx="1.4"/>' +
    '<rect x="13.5" y="4" width="6.5" height="6.5" rx="1.4"/>' +
    '<rect x="4" y="13.5" width="6.5" height="6.5" rx="1.4"/>' +
    '<rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.4"/>',
  user: '<circle cx="12" cy="8.4" r="3.6"/><path d="M4.8 20c1.1-3.6 3.9-5.5 7.2-5.5s6.1 1.9 7.2 5.5"/>',
  graduation:
    '<path d="M2.8 9.2 12 4.6l9.2 4.6L12 13.8z"/>' +
    '<path d="M6.4 11.2v4.4c0 1.4 2.5 2.6 5.6 2.6s5.6-1.2 5.6-2.6v-4.4"/>' +
    '<path d="M21.2 9.2v4.6"/>',
  star: '<path d="m12 4 2.5 5.1 5.6.8-4 3.9 1 5.6-5.1-2.7-5.1 2.7 1-5.6-4-3.9 5.6-.8z"/>',

  /* ── 危险操作 ── */
  trash:
    '<path d="M4.5 7.5h15"/><path d="M9 7.5V5.2A1.2 1.2 0 0 1 10.2 4h3.6A1.2 1.2 0 0 1 15 5.2v2.3"/>' +
    '<path d="M6.5 7.5 7.4 19a1.5 1.5 0 0 0 1.5 1.4h6.2a1.5 1.5 0 0 0 1.5-1.4l.9-11.5"/>' +
    '<path d="M10.5 11v5.5M13.5 11v5.5"/>',
  logout:
    '<path d="M15 8.5V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h7a2 2 0 0 0 2-2v-2.5"/>' +
    '<path d="M10.5 12H21"/><path d="m17.5 8.5 3.5 3.5-3.5 3.5"/>',
};

/** 全部可用图标名（按定义顺序）。 */
export const ICON_NAMES = Object.keys(ICONS);

/**
 * 是否已实现该图标。
 * @param {string} name
 * @returns {boolean}
 */
export function hasIcon(name) {
  return Object.prototype.hasOwnProperty.call(ICONS, name);
}

/**
 * 生成内联 SVG 字符串。
 *
 * @param {string} name 图标名（见 ICON_NAMES）
 * @param {number} [size] 边长 px，默认 24
 * @param {{strokeWidth?: number, className?: string}} [opts]
 * @returns {string} SVG 字符串；名字不存在时返回空串（不抛错，避免一个图标毁掉整页）
 */
export function icon(name, size = 24, opts = {}) {
  const body = ICONS[name];
  if (!body) {
    console.warn(`[zsts] 未定义的图标：${name}`);
    return '';
  }
  const px = Number.isFinite(Number(size)) && Number(size) > 0 ? Number(size) : 24;
  const strokeWidth = opts.strokeWidth === undefined ? 1.75 : Number(opts.strokeWidth);
  const className = typeof opts.className === 'string' && /^[\w -]+$/.test(opts.className)
    ? ` class="${opts.className}"`
    : '';
  return (
    `<svg viewBox="0 0 24 24" width="${px}" height="${px}" fill="none" ` +
    `stroke="currentColor" stroke-width="${strokeWidth}" stroke-linecap="round" ` +
    `stroke-linejoin="round" aria-hidden="true" focusable="false"${className}>${body}</svg>`
  );
}
