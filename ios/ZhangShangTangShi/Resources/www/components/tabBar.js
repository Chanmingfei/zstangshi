/**
 * tabBar.js —— 底部 4 Tab 导航
 *
 * 视觉/交互要求（PRD F-09、§8.4）：
 *   · 4 Tab：首页 / 课表 / 成绩 / 我的
 *   · 内联 SVG 图标 + 文字，**不引任何图标字体或 CDN**
 *   · 滑动指示器：250ms 位移 + 宽度（scaleX）同步插值，不跳变
 *   · 每个 Tab 热区 ≥ 44×44
 *   · 底部安全区用 env(safe-area-inset-bottom)，由 #tabbar 的 padding 承担
 *
 * 本文件只 import core/ —— 不碰 data。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { moveIndicator } from '../core/animate.js';

/** 四个 Tab 的定义（顺序即渲染顺序）。`icon` 是 core/icons.js 里的图标名。 */
export const TAB_ITEMS = [
  { key: 'home', label: '首页', path: '/home', icon: 'home' },
  { key: 'timetable', label: '课表', path: '/timetable', icon: 'timetable' },
  { key: 'grades', label: '成绩', path: '/grades', icon: 'grades' },
  { key: 'mine', label: '我的', path: '/mine', icon: 'mine' },
];

/**
 * 建底部 Tab 栏。
 *
 * @param {{active?: string, onSelect?: (key: string, path: string) => void}} [options]
 * @returns {{el: HTMLElement, setActive: (key: string, instant?: boolean) => void, destroy: () => void}}
 */
export function createTabBar(options = {}) {
  const { onSelect = null } = options;
  let activeKey = options.active || TAB_ITEMS[0].key;

  /** @type {Map<string, HTMLButtonElement>} */
  const buttons = new Map();

  const indicator = h('span', { className: 'c-tabbar__ind', 'aria-hidden': 'true' });
  const inner = h('div', { className: 'c-tabbar__inner', role: 'tablist', 'aria-label': '主导航' });

  for (const item of TAB_ITEMS) {
    const button = h(
      'button',
      {
        className: 'c-tabbar__item',
        type: 'button',
        role: 'tab',
        'data-tab': item.key,
        'aria-selected': 'false',
        'aria-label': item.label,
        // 必须写驼峰 onClick：h() 的 applyProps 把 onXxx 转成 addEventListener，
        // 早期写成小写 onclick 会被当普通属性 setAttribute 掉，底栏整个点不动。
        onClick: () => {
          if (item.key === activeKey) {
            // 点当前 Tab 也要有反馈：回到页顶。否则用户会以为「底栏点了没反应」。
            const main = document.getElementById('app-main');
            if (main) main.scrollTo({ top: 0, behavior: 'smooth' });
            return;
          }
          setActive(item.key);
          if (typeof onSelect === 'function') onSelect(item.key, item.path);
        },
      },
      [
        h('span', { className: 'c-tabbar__icon', html: icon(item.icon) }),
        h('span', { className: 'c-tabbar__label', text: item.label }),
      ]
    );
    buttons.set(item.key, button);
    inner.appendChild(button);
  }
  inner.appendChild(indicator);

  /**
   * 把指示器移到某个 Tab 下方（宽度取该 Tab 的 34%，视觉上像一颗短胶囊）。
   * @param {boolean} instant 首帧/窗口尺寸变化时不走动画
   */
  function placeIndicator(instant) {
    const target = buttons.get(activeKey);
    if (!target) return;
    moveIndicator(indicator, target, { widthRatio: 0.34, instant });
  }

  /**
   * 切换高亮。
   * @param {string} key
   * @param {boolean} [instant] 同步布局时跳过动画
   */
  function setActive(key, instant = false) {
    if (!buttons.has(key)) return;
    activeKey = key;
    for (const [tabKey, button] of buttons) {
      const selected = tabKey === key;
      if (button.getAttribute('aria-selected') === String(selected)) continue;
      button.setAttribute('aria-selected', String(selected));
      button.classList.toggle('is-active', selected);
    }
    placeIndicator(instant);
  }

  // 窗口尺寸变化（横竖屏切换 / 软键盘）后重新对位，不做动画
  const onResize = () => placeIndicator(true);
  window.addEventListener('resize', onResize);

  // 首帧对位：等元素进文档有宽度之后再量
  requestAnimationFrame(() => setActive(activeKey, true));

  return {
    el: inner,
    setActive,
    destroy() {
      window.removeEventListener('resize', onResize);
      inner.remove();
    },
  };
}
