/**
 * chip.js —— 横向滚动 chip 选择器（学期筛选 / 选课筛选共用）
 *
 * 交互要点：
 *   · 横向可滚，滚动条隐藏（base.css 已全局处理），左右边缘有渐隐提示
 *   · 选中态用 backgroundColor / color 插值（150ms），不做位移，避免一行 chip 整体抖动
 *   · 每个 chip 高度 ≥44px（PRD：可点区域 ≥44×44；视觉高度靠 padding 收在 36px 左右）
 *   · 键盘左右方向键可切换（roving tabindex），读屏用 tablist/tab 语义
 */

import { h } from '../core/dom.js';

/**
 * 建一组 chip。
 *
 * @param {{
 *   items: Array<{id: string, label: string, badge?: string}>,
 *   active?: string,
 *   ariaLabel?: string,
 *   onChange?: (id: string, item: object) => void
 * }} options
 * @returns {{
 *   el: HTMLElement,
 *   setActive: (id: string, silent?: boolean) => void,
 *   getActive: () => string,
 *   destroy: () => void
 * }}
 */
export function createChipGroup(options = {}) {
  const items = Array.isArray(options.items) ? options.items : [];
  const onChange = typeof options.onChange === 'function' ? options.onChange : null;
  let activeId = options.active || (items[0] && items[0].id) || '';

  /** @type {Map<string, HTMLButtonElement>} */
  const buttons = new Map();

  const track = h('div', {
    className: 'c-chipbar__track',
    role: 'tablist',
    'aria-label': options.ariaLabel || '筛选',
  });

  for (const item of items) {
    const button = h(
      'button',
      {
        className: 'c-chip',
        type: 'button',
        role: 'tab',
        'data-id': item.id,
        'aria-selected': 'false',
        tabindex: '-1',
      },
      [
        h('span', { className: 'c-chip__label', text: item.label }),
        item.badge ? h('span', { className: 'c-chip__badge', text: item.badge }) : null,
      ]
    );
    buttons.set(item.id, button);
    track.appendChild(button);
  }

  /**
   * 切到某个 chip。
   * @param {string} id
   * @param {boolean} [silent] 不触发 onChange（父级自己已经知道状态时用）
   */
  function setActive(id, silent = false) {
    if (!buttons.has(id)) return;
    const changed = id !== activeId;
    activeId = id;
    for (const [key, button] of buttons) {
      const selected = key === id;
      button.classList.toggle('is-active', selected);
      button.setAttribute('aria-selected', String(selected));
      button.tabIndex = selected ? 0 : -1;
      if (selected) button.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }
    if (changed && !silent && onChange) {
      const item = items.find((it) => it.id === id);
      onChange(id, item || { id });
    }
  }

  const onClick = (event) => {
    const button = event.target instanceof Element ? event.target.closest('.c-chip') : null;
    if (!button || !track.contains(button)) return;
    setActive(button.dataset.id);
  };

  /** 键盘：左右方向键在 chip 间移动并立即选中 */
  const onKeydown = (event) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    const list = items.map((it) => it.id);
    const index = list.indexOf(activeId);
    if (index === -1) return;
    event.preventDefault();
    const delta = event.key === 'ArrowRight' ? 1 : -1;
    const next = (index + delta + list.length) % list.length;
    setActive(list[next]);
    const button = buttons.get(list[next]);
    if (button) button.focus();
  };

  track.addEventListener('click', onClick);
  track.addEventListener('keydown', onKeydown);

  const el = h('div', { className: 'c-chipbar' }, track);
  setActive(activeId, true);

  return {
    el,
    setActive,
    getActive: () => activeId,
    destroy() {
      track.removeEventListener('click', onClick);
      track.removeEventListener('keydown', onKeydown);
      el.remove();
    },
  };
}
