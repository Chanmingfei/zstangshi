/**
 * pullRefresh.js —— 下拉刷新容器
 *
 * 三个必须做对的点：
 *   1. **阻尼**：手指位移 × 0.5 才是内容位移。1:1 跟手会让下拉显得「太轻」，0.3 又显得
 *      「拉不动」；0.5 是手指走 2px 内容走 1px，视觉上最像 iOS。
 *   2. **阈值 64px**（阻尼后），到阈值前松手一律回弹，不给「差一点就刷新」的错觉；
 *      到阈值后文案变「释放刷新」，指示器停转并高亮。
 *   3. 只在滚动容器**已经在顶部**时接管手势，否则和正常上滑浏览打架（这是下拉刷新最常见的 bug）。
 *
 * 刷新中会锁住再次下拉，避免连点产生多个并发请求。
 */

import { h } from '../core/dom.js';
import { pullSpin } from '../core/animateFeedback.js';
import { icon } from '../core/icons.js';

/** 阻尼系数：手指位移 × 0.5 = 内容位移 */
const DAMPING = 0.5;
/** 触发刷新的阈值（阻尼后的像素） */
export const PULL_THRESHOLD = 64;
/** 超拉上限：防止拉出一屏空白 */
const MAX_OVERPULL = PULL_THRESHOLD * 1.8;
/** 回弹时长（tokens 档位 --dur-pull） */
const SETTLE_DURATION = 200;

/**
 * 建下拉刷新容器。
 *
 * @param {{
 *   content?: unknown,
 *   onRefresh?: () => (Promise<unknown>|unknown),
 *   threshold?: number,
 *   scrollRoot?: HTMLElement|null,
 *   disabled?: boolean
 * }} [options]
 * @returns {{
 *   el: HTMLElement,
 *   body: HTMLElement,
 *   setContent: (nodes: unknown) => void,
 *   setEnabled: (enabled: boolean) => void,
 *   isRefreshing: () => boolean,
 *   destroy: () => void
 * }}
 */
export function createPullRefresh(options = {}) {
  const threshold = Number(options.threshold) > 0 ? Number(options.threshold) : PULL_THRESHOLD;
  const onRefresh = typeof options.onRefresh === 'function' ? options.onRefresh : null;

  let enabled = options.disabled !== true;
  let refreshing = false;
  let tracking = false;
  let startY = 0;
  let offset = 0;

  const spinner = h('span', { className: 'c-pull__spinner', html: icon('refresh', 20, { strokeWidth: 2 }) });
  const textEl = h('span', { className: 'c-pull__text', text: '下拉刷新' });
  const head = h('div', { className: 'c-pull__head', 'aria-hidden': 'true' }, [spinner, textEl]);
  const body = h('div', { className: 'c-pull__body' }, options.content);
  const el = h('div', { className: 'c-pull' }, [head, body]);

  /**
   * 找滚动容器：默认取最近的 #app-main（全站唯一滚动容器，见 base.css）。
   * @returns {HTMLElement|Window}
   */
  function scroller() {
    if (options.scrollRoot) return options.scrollRoot;
    return el.closest('#app-main') || document.scrollingElement || window;
  }

  /** 当前是否已在顶部 */
  function atTop() {
    const root = scroller();
    const top = root === window || root === document.scrollingElement
      ? window.scrollY || 0
      : root.scrollTop;
    return top <= 0;
  }

  /**
   * 把内容位移写成 transform（只动合成层，不触发布局）。
   * @param {number} y
   * @param {boolean} animated
   */
  function setOffset(y, animated) {
    offset = y;
    body.style.transition = animated
      ? `transform ${SETTLE_DURATION}ms var(--ease-standard)`
      : 'none';
    body.style.transform = y === 0 ? '' : `translate3d(0, ${y}px, 0)`;
    el.style.setProperty('--pull-y', `${y}px`);
    if (y === 0) el.classList.remove('is-pulling', 'is-ready');
  }

  /** 松手回弹 */
  function settleBack() {
    setOffset(0, true);
    pullSpin(spinner, 'settle');
    textEl.textContent = '下拉刷新';
  }

  /** 真正执行刷新 */
  async function trigger() {
    refreshing = true;
    el.classList.add('is-refreshing');
    el.classList.remove('is-ready');
    textEl.textContent = '正在刷新…';
    setOffset(threshold, false);
    pullSpin(spinner, 'loading');
    try {
      if (onRefresh) await onRefresh();
    } catch {
      /* 刷新失败由调用方自己 toast，这里只负责把动画收干净 */
    }
    refreshing = false;
    el.classList.remove('is-refreshing');
    pullSpin(spinner, 'idle');
    textEl.textContent = '下拉刷新';
    setOffset(0, true);
  }

  const onTouchStart = (event) => {
    if (!enabled || refreshing) return;
    if (!atTop()) return;
    const touch = event.touches[0];
    if (!touch) return;
    tracking = true;
    startY = touch.clientY;
    body.style.transition = 'none';
  };

  const onTouchMove = (event) => {
    if (!tracking) return;
    const touch = event.touches[0];
    if (!touch) return;
    const raw = touch.clientY - startY;
    if (raw <= 0) {
      // 往上滑：立刻交还给正常滚动
      if (offset !== 0) setOffset(0, false);
      tracking = false;
      return;
    }
    if (event.cancelable) event.preventDefault();
    const damped = Math.min(MAX_OVERPULL, raw * DAMPING);
    setOffset(damped, false);
    const ready = damped >= threshold;
    el.classList.toggle('is-ready', ready);
    el.classList.add('is-pulling');
    textEl.textContent = ready ? '释放刷新' : '下拉刷新';
    pullSpin(spinner, 'pulling', Math.min(1, damped / threshold));
  };

  const onTouchEnd = () => {
    if (!tracking) return;
    tracking = false;
    if (offset >= threshold) {
      trigger();
      return;
    }
    settleBack();
  };

  el.addEventListener('touchstart', onTouchStart, { passive: true });
  el.addEventListener('touchmove', onTouchMove, { passive: false });
  el.addEventListener('touchend', onTouchEnd);
  el.addEventListener('touchcancel', onTouchEnd);

  return {
    el,
    body,
    setContent(nodes) {
      body.replaceChildren();
      for (const node of Array.isArray(nodes) ? nodes : [nodes]) {
        if (node === null || node === undefined) continue;
        body.appendChild(node instanceof Node ? node : document.createTextNode(String(node)));
      }
    },
    /** 页面在 loading 期间可临时禁用，避免刷新叠刷新 */
    setEnabled(next) {
      enabled = next === true;
    },
    isRefreshing: () => refreshing,
    destroy() {
      el.removeEventListener('touchstart', onTouchStart);
      el.removeEventListener('touchmove', onTouchMove);
      el.removeEventListener('touchend', onTouchEnd);
      el.removeEventListener('touchcancel', onTouchEnd);
      el.remove();
    },
  };
}
