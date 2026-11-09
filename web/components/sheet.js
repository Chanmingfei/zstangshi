/**
 * sheet.js —— 底部弹层（课程详情 / 周次选择 / 选课确认说明共用）
 *
 * 关键决策：
 *   1. **手势关闭按「比例」判定而不是固定像素**：下滑超过面板高度的 40% 才关。固定像素
 *      （比如 100px）在小屏上会「轻轻一滑就没了」，在大屏上又「怎么滑都不关」。
 *   2. 拖动过程中面板 `transform` 跟手、遮罩 `opacity` 同比例变淡 —— 遮罩与面板必须同步，
 *      否则松手回弹时遮罩会「闪一下」。松手后从**当前位移**继续补间，不回到 0 再重来。
 *   3. 只动 transform / opacity；手指按下时才加 `will-change`，松手立刻摘掉，避免长期占合成层。
 *   4. 焦点管理：打开时记住触发元素并聚焦面板，关闭后还回去；Esc 关闭。读屏能正常进出。
 *
 * 手势只监听面板自身（不是 document），关闭时全部解绑 —— 页面 unmount 不需要额外清理。
 */

import { h } from '../core/dom.js';
import { prefersReducedMotion } from '../core/animate.js';
import { sheetIn, sheetOut } from '../core/animateFeedback.js';

/**
 * 遮罩的不透明度终值。注意这里写 1 而不是 0.4：
 * 「0.4 的黑」来自 tokens.css 的 `--bg-mask`（颜色只有一个来源），
 * JS 只负责把遮罩从 0 淡到 1，两者相乘正好是 PRD 要求的 0.4。
 */
const MASK_OPACITY = 1;
/** 下滑关闭阈值：面板高度的比例 */
const CLOSE_RATIO = 0.4;
/** 补间时长（tokens 档位，读不到 CSS 变量时用这两个兜底） */
const DUR_ENTER = 350;
const DUR_PAGE = 250;

/**
 * 建一个底部弹层（DOM 在首次 open 时才插入文档）。
 *
 * @param {{
 *   title?: string,
 *   content?: unknown,
 *   actions?: unknown,
 *   dismissible?: boolean,
 *   onClose?: ((reason: string) => void)|null
 * }} [options]
 * @returns {{
 *   el: HTMLElement,
 *   panel: HTMLElement,
 *   open: () => Promise<void>,
 *   close: (reason?: string) => Promise<void>,
 *   isOpen: () => boolean,
 *   setContent: (nodes: unknown) => void,
 *   setTitle: (text: string) => void,
 *   setActions: (nodes: unknown) => void,
 *   destroy: () => void
 * }}
 */
export function createSheet(options = {}) {
  const dismissible = options.dismissible !== false;
  const onClose = typeof options.onClose === 'function' ? options.onClose : null;

  let opened = false;
  let closing = false;
  /** 拖动位移（px），松手补间与关闭动画都从这里起算 */
  let dragOffset = 0;
  /** @type {Element|null} */
  let lastFocus = null;

  const titleEl = h('h2', { className: 'c-sheet__title', text: options.title || '' });
  const handle = h('span', { className: 'c-sheet__handle', 'aria-hidden': 'true' });
  const bodyEl = h('div', { className: 'c-sheet__body' }, options.content);
  const actionsEl = h('div', { className: 'c-sheet__actions' }, options.actions);
  const closeBtn = h('button', {
    className: 'c-sheet__close',
    type: 'button',
    'aria-label': '关闭',
    html: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true"><path d="M7 7l10 10M17 7 7 17"/></svg>',
  });

  const panel = h(
    'div',
    {
      className: 'c-sheet__panel',
      role: 'dialog',
      'aria-modal': 'true',
      'aria-label': options.title || '详情',
      tabindex: '-1',
    },
    [
      h('div', { className: 'c-sheet__grabber' }, handle),
      h('div', { className: 'c-sheet__header' }, [titleEl, closeBtn]),
      bodyEl,
      actionsEl,
    ]
  );

  const mask = h('div', { className: 'c-sheet', role: 'presentation' }, panel);
  const el = mask;

  /** 面板像素高度（动画用绝对位移，百分比在 WAAPI 里不通用） */
  function panelHeight() {
    return panel.getBoundingClientRect().height || 320;
  }

  /**
   * 从当前位移补间到目标位移，遮罩同步。
   * @param {number} toY 目标位移 px
   * @param {number} maskTo 目标遮罩透明度
   * @param {number} duration
   * @param {string} easing
   * @returns {Promise<void>}
   */
  async function settle(toY, maskTo, duration, easing) {
    const from = dragOffset;
    const maskFrom = mask.style.opacity === '' ? String(MASK_OPACITY) : mask.style.opacity;
    if (prefersReducedMotion()) {
      panel.style.transform = `translate3d(0, ${toY}px, 0)`;
      mask.style.opacity = String(maskTo);
      dragOffset = toY;
      return;
    }
    try {
      const animations = [
        panel.animate(
          [
            { transform: `translate3d(0, ${from}px, 0)` },
            { transform: `translate3d(0, ${toY}px, 0)` },
          ],
          { duration, easing, fill: 'both' }
        ),
        mask.animate([{ opacity: maskFrom }, { opacity: maskTo }], {
          duration,
          easing: 'cubic-bezier(0.2, 0, 0, 1)',
          fill: 'both',
        }),
      ];
      await Promise.all(animations.map((a) => a.finished.catch(() => {})));
      for (const a of animations) a.cancel();
    } catch {
      /* 动画失败也必须落到终态，否则面板会卡在半空 */
    }
    panel.style.transform = `translate3d(0, ${toY}px, 0)`;
    mask.style.opacity = String(maskTo);
    dragOffset = toY;
  }

  /* ── 手势 ───────────────────────────────────────────────────────────── */

  let startY = 0;
  let startOffset = 0;
  let tracking = false;

  const onTouchStart = (event) => {
    if (!dismissible || closing) return;
    const touch = event.touches[0];
    if (!touch) return;
    // 内容已向下滚动时不接管手势，否则用户没法看长内容
    if (bodyEl.scrollTop > 0) {
      tracking = false;
      return;
    }
    tracking = true;
    startY = touch.clientY;
    startOffset = dragOffset;
    panel.style.willChange = 'transform';
  };

  const onTouchMove = (event) => {
    if (!tracking || closing) return;
    const touch = event.touches[0];
    if (!touch) return;
    const dy = touch.clientY - startY + startOffset;
    if (dy <= 0) {
      dragOffset = 0;
      panel.style.transform = 'translate3d(0, 0, 0)';
      mask.style.opacity = String(MASK_OPACITY);
      return;
    }
    // 往下拖必须吃掉默认行为，否则弹层跟着页面一起滚，手势就废了
    if (event.cancelable) event.preventDefault();
    dragOffset = dy;
    panel.style.transform = `translate3d(0, ${dy}px, 0)`;
    const ratio = Math.min(1, dy / panelHeight());
    mask.style.opacity = String(MASK_OPACITY * (1 - ratio));
  };

  const onTouchEnd = () => {
    if (!tracking) return;
    tracking = false;
    panel.style.willChange = '';
    if (dragOffset > panelHeight() * CLOSE_RATIO) {
      close('gesture');
      return;
    }
    settle(0, MASK_OPACITY, DUR_ENTER, 'cubic-bezier(0.34, 1.56, 0.64, 1)');
  };

  panel.addEventListener('touchstart', onTouchStart, { passive: true });
  panel.addEventListener('touchmove', onTouchMove, { passive: false });
  panel.addEventListener('touchend', onTouchEnd);
  panel.addEventListener('touchcancel', onTouchEnd);

  closeBtn.addEventListener('click', () => close('button'));
  mask.addEventListener('click', (event) => {
    if (dismissible && event.target === mask) close('mask');
  });

  const onKeydown = (event) => {
    if (event.key === 'Escape' && dismissible) close('esc');
  };

  /* ── 开关 ───────────────────────────────────────────────────────────── */

  /** @returns {Promise<void>} */
  async function open() {
    if (opened) return;
    opened = true;
    closing = false;
    dragOffset = 0;
    lastFocus = document.activeElement;
    const host = document.getElementById('sheet-root') || document.body;
    host.appendChild(mask);
    document.addEventListener('keydown', onKeydown);
    panel.style.transform = 'translate3d(0, 100%, 0)';
    mask.style.opacity = '0';
    await sheetIn(panel, mask);
    panel.style.transform = 'translate3d(0, 0, 0)';
    mask.style.opacity = String(MASK_OPACITY);
    panel.focus({ preventScroll: true });
  }

  /**
   * 关闭。
   * @param {string} [reason] 'gesture' | 'mask' | 'esc' | 'button' | 'api'
   * @returns {Promise<void>}
   */
  async function close(reason = 'api') {
    if (!opened || closing) return;
    closing = true;
    document.removeEventListener('keydown', onKeydown);
    if (dragOffset > 0) {
      // 从手指当前位置继续滑出，不先弹回 0 —— 弹回去再滑出会有一次明显折返
      await settle(panelHeight(), 0, DUR_PAGE, 'cubic-bezier(0.2, 0, 0, 1)');
    } else {
      await sheetOut(panel, mask);
    }
    panel.style.transform = '';
    mask.style.opacity = '';
    mask.remove();
    opened = false;
    closing = false;
    dragOffset = 0;
    if (lastFocus && typeof lastFocus.focus === 'function' && document.contains(lastFocus)) {
      lastFocus.focus({ preventScroll: true });
    }
    lastFocus = null;
    if (onClose) onClose(reason);
  }

  /** 换内容（同一个弹层复用给不同课程时用，避免反复建 DOM） */
  function setContent(nodes) {
    bodyEl.replaceChildren();
    for (const node of Array.isArray(nodes) ? nodes : [nodes]) {
      if (node === null || node === undefined) continue;
      bodyEl.appendChild(node instanceof Node ? node : document.createTextNode(String(node)));
    }
  }

  function setTitle(text) {
    titleEl.textContent = text || '';
    panel.setAttribute('aria-label', text || '详情');
  }

  /**
   * 设置底部操作区（不随内容滚动）。
   *
   * 建弹层时没传 `actions` 会把这一块摘掉；之后再调用这里可以把它挂回去 ——
   * 表单类弹层的主按钮必须待在滚动区之外，否则按钮要么跟着内容滚走，
   * 要么 sticky 在滚动区底部盖住最后一行内容。
   * @param {unknown} nodes
   */
  function setActions(nodes) {
    const list = (Array.isArray(nodes) ? nodes : [nodes]).filter((node) => node !== null && node !== undefined);
    actionsEl.replaceChildren(...list.map((node) => (node instanceof Node ? node : document.createTextNode(String(node)))));
    if (list.length && !actionsEl.isConnected) panel.appendChild(actionsEl);
  }

  if (!options.actions) actionsEl.remove();

  return {
    el,
    panel,
    open,
    close,
    isOpen: () => opened,
    setContent,
    setTitle,
    setActions,
    destroy() {
      document.removeEventListener('keydown', onKeydown);
      mask.remove();
      opened = false;
    },
  };
}
