/**
 * pages/mineTheme.js —— 「外观」三联段控（跟随系统 / 亮色 / 深色）
 *
 * 形状保持原样（圆角矩形外框 + 三等分），只把选中态从「整格换底色」换成
 * 与底栏同一套语言的**滑动小胶囊**：
 *
 *   · 位移与宽度交给 core/slideTo.js（WAAPI，起点取当前渲染位置、按距离缩时长），
 *     所以连点三下也是从半路直接换向，不会排完上一段再走 —— 与 Dock 一致。
 *   · 圆角要按外框做同心适配：外框 16px（--r-md）+ 4px 内边距，
 *     内块取 12px（--r-sm）才是同心的；直接用 --r-full 会和外框弧线打架。
 *   · 首帧与尺寸变化（横竖屏、字体放大）直接落位，不做动画。
 *
 * 这里只造 DOM 与选中态，点击语义仍由 mine.js 的委托处理（它会调 applyTheme）。
 */

import { h } from '../core/dom.js';
import { slideTo, forgetSlide } from '../core/slideTo.js';

/**
 * @param {{items: Array<{key: string, label: string}>, label: string}} options
 * @returns {{el: HTMLElement, buttons: HTMLButtonElement[], setActive: (key: string) => void, destroy: () => void}}
 */
export function createThemeSwitch(options) {
  const { items, label } = options;

  const pill = h('span', { className: 'p-mine__seg-pill', 'aria-hidden': 'true' });
  const buttons = items.map((item) => h('button', {
    className: 'p-mine__segment-btn',
    type: 'button',
    text: item.label,
    dataset: { theme: item.key },
    'aria-pressed': 'false',
  }));

  const el = h('div', { className: 'p-mine__segment', role: 'group', 'aria-label': label }, [pill, ...buttons]);

  /** 当前选中 key，只在有值时才能对位 */
  let active = null;

  /** 把胶囊对到当前选中的那一格 */
  function place(instant) {
    const index = items.findIndex((item) => item.key === active);
    const button = buttons[index];
    if (!button || !el.isConnected) return;
    const outer = el.getBoundingClientRect();
    const box = button.getBoundingClientRect();
    if (!box.width) return;
    // 坐标相对段控的左内边距盒：外框无边框，直接相减即可
    slideTo(pill, { x: box.left - outer.left, width: box.width }, { instant, unit: box.width });
  }

  function setActive(key) {
    if (!items.some((item) => item.key === key)) return;
    const first = active === null;
    active = key;
    for (const button of buttons) {
      const on = button.dataset.theme === key;
      if (button.classList.contains('is-active') === on) continue;
      button.classList.toggle('is-active', on);
      button.setAttribute('aria-pressed', String(on));
    }
    place(first);
  }

  // 尺寸变化（横竖屏 / 系统字体放大）后重新对位，不做动画
  const observer = typeof ResizeObserver === 'function'
    ? new ResizeObserver(() => place(true))
    : null;
  if (observer) observer.observe(el);

  return {
    el,
    buttons,
    setActive,
    destroy() {
      if (observer) observer.disconnect();
      forgetSlide(pill);
    },
  };
}
