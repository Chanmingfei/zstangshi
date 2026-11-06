/**
 * animate.js —— 动效总线（ARCHITECTURE §4.7）
 *
 * 对外 API：
 *   pageSwap(prevEl, nextEl, kind)   转场：400ms 临界阻尼弹簧（8px 上移 + 淡入），可打断、
 *                                    从实时屏幕值起播，绝不做横向位移
 *   stagger(nodes, opts)             列表交错入场：300ms spring，40ms/个，第 9 个起不再叠加延迟
 *   countUp(el, to, opts)            数字滚动：300ms 缓出
 *   moveIndicator(el, target, opts)  指示器：400ms 位移 + 宽度（scaleX）同步插值
 *   prefersReducedMotion()           系统「减弱动态效果」
 *   durOf / easeOf / settle          内部工具，供拆分出去的 animateFeedback.js 共用
 *
 * 弹层 / 下拉 / 骨架屏等反馈动效在 core/animateFeedback.js（本文件超 400 行上限拆出）。
 *
 * Fluid Interfaces 铁律：① 只动 transform / opacity（外加骨架屏 background-position），
 * 不碰 layout；② 任何动画可打断、从当前值起播；③ prefers-reduced-motion 时位移动画
 * 退化为交叉淡化，不抛错、不阻塞点击。
 * 时长与缓动全部从 tokens.css 读，读不到才用兜底 —— 保证只有一处真源。
 */

/** 兜底时长（ms），与 tokens.css 的 --dur-* 一致（Fluid Interfaces 档位） */
const FALLBACK_DUR = { press: 100, pull: 200, page: 400, enter: 300, skeleton: 1200 };
/** 兜底缓动 */
const FALLBACK_EASE = {
  spring: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
  standard: 'cubic-bezier(0.2, 0, 0, 1)',
  linear: 'linear',
};
/** 交错入场：40ms/个，上限 8 个 */
const STAGGER_STEP = 40;
const STAGGER_MAX = 8;
/** 骨架屏扫光周期 */
const SKELETON_DURATION = 1200;
/** CSS 变量读取缓存 */
const cache = { dur: {}, ease: {} };

/**
 * 读 --dur-* 变量（'250ms'），失败用兜底值。
 * @param {'press'|'pull'|'page'|'enter'|'skeleton'} name
 * @returns {number} 毫秒
 */
export function durOf(name) {
  if (cache.dur[name] !== undefined) return cache.dur[name];
  let value = FALLBACK_DUR[name];
  try {
    const raw = getComputedStyle(document.documentElement).getPropertyValue(`--dur-${name}`).trim();
    const num = Number.parseFloat(raw);
    if (Number.isFinite(num) && num > 0) {
      value = raw.endsWith('ms') ? num : raw.endsWith('s') ? num * 1000 : num;
    }
  } catch { /* 取不到就用兜底 */ }
  cache.dur[name] = value;
  return value;
}

/**
 * 读 --ease-* 变量。
 * @param {'spring'|'standard'|'linear'} name
 * @returns {string}
 */
export function easeOf(name) {
  if (cache.ease[name]) return cache.ease[name];
  let value = FALLBACK_EASE[name];
  try {
    const raw = getComputedStyle(document.documentElement).getPropertyValue(`--ease-${name}`).trim();
    if (raw) value = raw;
  } catch { /* 兜底 */ }
  cache.ease[name] = value;
  return value;
}

/**
 * 系统是否开启「减弱动态效果」。
 * @returns {boolean}
 */
export function prefersReducedMotion() {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch {
    return false;
  }
}

/**
 * 跑一组 WAAPI 动画并等结束，然后撤销 fill 释放合成层；任何异常都吞掉。
 * @param {Animation[]} animations
 * @returns {Promise<void>}
 */
export async function settle(animations) {
  if (animations.length === 0) return;
  try {
    await Promise.all(animations.map((animation) => animation.finished.catch(() => {})));
  } catch { /* 忽略 */ }
  for (const animation of animations) {
    try {
      animation.cancel();
    } catch { /* 忽略 */ }
  }
}

/**
 * 停掉元素身上所有在跑的动画，并返回它此刻的实时 opacity / transform。
 * 转场可打断的关键：新动画一律从这份「当前屏幕值」起播，绝不跳回 0 重来。
 * @param {HTMLElement} el
 * @returns {{opacity: number, transform: string}}
 */
function stopAndSample(el) {
  const computed = getComputedStyle(el);
  const state = {
    opacity: Number.parseFloat(computed.opacity),
    transform: computed.transform,
  };
  for (const animation of el.getAnimations()) {
    try {
      animation.cancel();
    } catch { /* 忽略 */ }
  }
  return state;
}

/**
 * 当前在飞的转场的终结函数。单飞（single-flight）保证：快速连切时，
 * 新转场先把上一场**立即终结**（停动画 + 摘掉旧页），DOM 上任何时刻
 * 最多只有一场转场的元素存在 —— 这是「新旧页重叠残留」的根修。
 * @type {null | (() => void)}
 */
let activeSwapCleanup = null;

/**
 * 页面/Tab 转场：400ms 临界阻尼弹簧（8px 上移 + 淡入）。
 *
 * Fluid Interfaces 三条实现要点：
 *   ① 单飞可打断 —— 新转场开始前先终结上一场（停动画、摘旧页、从实时值起播），
 *      绝不允许两场转场并行（并行的旧页会以定位层身份叠在新页上面）。
 *   ② 层序 —— 旧页钉成 position:absolute 后属于定位层，会画在 static 新页**之上**；
 *      显式压到 z-index:-1（#app-main 有 isolation 隔离，不会漏出容器外），
 *      让交叉淡化真正发生在「新页在上、旧页在下」。
 *   ③ 减弱动态效果时，位移动画一律退化为纯 opacity 的交叉淡化。
 *
 * @param {HTMLElement|null} prevEl 首次进入传 null
 * @param {HTMLElement|null} nextEl
 * @param {'fade'|'push'} [kind]
 * @returns {Promise<void>}
 */
export async function pageSwap(prevEl, nextEl, kind = 'fade') {
  if (!nextEl) return;

  // ① 终结上一场：停它的动画、摘掉它的旧页。它自己的 await settle() 会因为
  //    animation.cancel() 立刻落地，其后续 remove() 是幂等 noop。
  if (activeSwapCleanup) {
    const cleanup = activeSwapCleanup;
    activeSwapCleanup = null;
    cleanup();
  }

  const hasPrev = prevEl && prevEl !== nextEl;
  if (!hasPrev) return;

  const reduced = prefersReducedMotion();
  const prevState = stopAndSample(prevEl);
  const nextState = stopAndSample(nextEl);

  prevEl.style.pointerEvents = 'none';
  // 旧页脱离文档流并钉死在原处（含滚动补偿），否则新页会被挤到旧页下方（视觉跳一下）
  const host = prevEl.parentElement;
  if (host) {
    const prevRect = prevEl.getBoundingClientRect();
    const hostRect = host.getBoundingClientRect();
    prevEl.style.position = 'absolute';
    prevEl.style.top = `${prevRect.top - hostRect.top + host.scrollTop}px`;
    prevEl.style.left = '0';
    prevEl.style.right = '0';
    prevEl.style.width = `${prevRect.width}px`;
  }
  // ② 压层序：定位层默认盖在 static 内容上，必须显式压到新页之下
  prevEl.style.zIndex = '-1';

  const duration = durOf('page');
  const easing = easeOf('standard');
  const animations = [];

  try {
    if (reduced) {
      // 交叉淡化：只动 opacity，无任何位移
      animations.push(
        nextEl.animate(
          [{ opacity: Math.max(0, Math.min(1, nextState.opacity)) }, { opacity: 1 }],
          { duration, easing, fill: 'both' }
        )
      );
      animations.push(
        prevEl.animate([{ opacity: prevState.opacity }, { opacity: 0 }], { duration, easing, fill: 'both' })
      );
    } else {
      const nextFrom = nextState.transform === 'none' ? 'translate3d(0, 8px, 0)' : nextState.transform;
      const prevTo =
        kind === 'push' ? 'translate3d(0, -8px, 0)' : prevState.transform === 'none' ? 'none' : prevState.transform;
      animations.push(
        nextEl.animate(
          [
            { opacity: nextState.opacity, transform: nextFrom },
            { opacity: 1, transform: 'translate3d(0, 0, 0)' },
          ],
          { duration, easing, fill: 'both' }
        )
      );
      animations.push(
        prevEl.animate(
          [
            { opacity: prevState.opacity, transform: prevState.transform },
            { opacity: 0, transform: prevTo },
          ],
          { duration, easing, fill: 'both' }
        )
      );
    }
  } catch {
    if (prevEl.parentNode) prevEl.remove();
    return;
  }

  /** 本场转场的终结器：被下一场调用时立即落地（动画 cancel → settle 快速 resolve）。 */
  const finalize = () => {
    for (const animation of animations) {
      try {
        animation.cancel();
      } catch { /* 忽略 */ }
    }
    if (prevEl.parentNode) prevEl.remove();
  };
  activeSwapCleanup = finalize;

  await settle(animations);
  // 正常播完：只清「还是自己」的句柄，避免误清新转场的终结器
  if (activeSwapCleanup === finalize) activeSwapCleanup = null;
  if (prevEl.parentNode) prevEl.remove();
}

/**
 * 列表交错入场：位移 12px + 透明度 0→1，40ms/个，第 9 个起不再叠加延迟。
 * @param {Iterable<Element>} nodes
 * @param {{step?: number, max?: number, distance?: number, duration?: number}} [opts]
 */
export function stagger(nodes, opts = {}) {
  const list = Array.from(nodes || []).filter((el) => el instanceof Element);
  if (list.length === 0 || prefersReducedMotion()) return;

  const step = opts.step === undefined ? STAGGER_STEP : opts.step;
  const max = opts.max === undefined ? STAGGER_MAX : opts.max;
  const distance = opts.distance === undefined ? 12 : opts.distance;
  const duration = opts.duration === undefined ? durOf('enter') : opts.duration;
  const easing = easeOf('spring');

  list.forEach((el, index) => {
    const delay = Math.min(index, max) * step;
    try {
      el.animate(
        [
          { opacity: 0, transform: `translate3d(0, ${distance}px, 0)` },
          { opacity: 1, transform: 'translate3d(0, 0, 0)' },
        ],
        { duration, delay, easing, fill: 'both' }
      );
    } catch {
      /* 单个元素失败不影响其它 */
    }
  });
}

/**
 * 数字滚动到目标值（缓出，等宽数字已由 .u-num 保证不抖）。
 * @param {HTMLElement|null} el
 * @param {number} to
 * @param {{from?: number, duration?: number, decimals?: number, format?: (v: number) => string}} [opts]
 */
export function countUp(el, to, opts = {}) {
  if (!el) return;
  const target = Number(to);
  const decimals = opts.decimals === undefined ? 0 : opts.decimals;
  const format =
    typeof opts.format === 'function' ? opts.format : (value) => value.toFixed(decimals);

  if (!Number.isFinite(target)) {
    el.textContent = '--';
    return;
  }
  // 减弱动效：直接落终值
  if (prefersReducedMotion()) {
    el.textContent = format(target);
    return;
  }

  if (el.__zstsCountUpRaf) cancelAnimationFrame(el.__zstsCountUpRaf);
  const from = Number.isFinite(Number(opts.from)) ? Number(opts.from) : 0;
  const duration = opts.duration === undefined ? durOf('enter') : opts.duration;
  const startedAt = performance.now();

  const tick = (now) => {
    const progress = Math.min(1, (now - startedAt) / duration);
    const eased = 1 - Math.pow(1 - progress, 3); // easeOutCubic
    if (progress < 1) {
      el.textContent = format(from + (target - from) * eased);
      el.__zstsCountUpRaf = requestAnimationFrame(tick);
    } else {
      el.textContent = format(target);
      el.__zstsCountUpRaf = 0;
    }
  };
  el.textContent = format(from);
  el.__zstsCountUpRaf = requestAnimationFrame(tick);
}

/**
 * 把指示器移到目标元素下方：CSS 宽 1px + transform-origin 左端，
 * 用 `translate3d(x) scaleX(w)` 一次插值同时完成位移与宽度，不触发布局。
 * @param {HTMLElement|null} indicatorEl
 * @param {HTMLElement|null} targetEl
 * @param {{widthRatio?: number, duration?: number, instant?: boolean}} [opts]
 */
export function moveIndicator(indicatorEl, targetEl, opts = {}) {
  if (!indicatorEl || !targetEl) return;
  const host = indicatorEl.offsetParent || indicatorEl.parentElement;
  if (!host) return;

  // 用 offsetLeft/offsetWidth 而非 getBoundingClientRect：转场动画期间祖先带
  // transform，rect 会量到动画中间态（指示条因此错位）；offset 系列不受影响，
  // 且 targetEl 与 indicator 的 offsetParent 同为 host，坐标系天然一致。
  const ratio = opts.widthRatio === undefined ? 1 : opts.widthRatio;
  const width = Math.max(0, targetEl.offsetWidth * ratio);
  const x = targetEl.offsetLeft + (targetEl.offsetWidth - width) / 2;

  const instant = opts.instant === true || prefersReducedMotion();
  indicatorEl.style.transition = instant
    ? 'none'
    : `transform ${opts.duration === undefined ? durOf('page') : opts.duration}ms ${easeOf('standard')}`;
  indicatorEl.style.transform = `translate3d(${x}px, 0, 0) scaleX(${width})`;
}
