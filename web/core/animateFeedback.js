/**
 * animateFeedback.js —— 反馈类动效（从 animate.js 拆出，守单文件 ≤400 行）
 *
 * 对外 API：
 *   sheetIn / sheetOut(panel, mask)  底部弹层：300ms spring 上滑 + 遮罩 0→1
 *   pullSpin(el, mode, progress)     下拉刷新旋转 / 加载态无限旋转
 *   skeletonPulse(el, on)            骨架屏 1200ms 循环微光
 *
 * 与 animate.js 的分工：转场/入场/数字滚动在 animate.js（页面级），
 * 这里收「组件级反馈」。两条铁律不变：只动 transform/opacity；
 * prefers-reduced-motion 时位移动画退化、循环动画不起。
 */

import { durOf, easeOf, settle, prefersReducedMotion } from './animate.js';

/** 骨架屏扫光周期 */
const SKELETON_DURATION = 1200;

/**
 * 底部弹层的共用实现：面板纵向位移 + 遮罩透明度。
 * @param {HTMLElement} panelEl
 * @param {HTMLElement|null} maskEl
 * @param {string} fromY 起始 translateY
 * @param {string} toY 结束 translateY
 * @param {number} maskTo 遮罩终态透明度
 * @param {number} duration 面板时长
 * @param {string} easing 面板缓动
 * @returns {Promise<void>}
 */
async function slideSheet(panelEl, maskEl, fromY, toY, maskTo, duration, easing) {
  if (prefersReducedMotion()) {
    if (maskEl) maskEl.style.opacity = String(maskTo);
    return;
  }
  const animations = [];
  try {
    animations.push(
      panelEl.animate([{ transform: `translate3d(0, ${fromY}, 0)` }, { transform: `translate3d(0, ${toY}, 0)` }], {
        duration,
        easing,
        fill: 'both',
      })
    );
    if (maskEl) {
      animations.push(
        maskEl.animate([{ opacity: maskTo === 1 ? 0 : 1 }, { opacity: maskTo }], {
          duration: durOf('page'),
          easing: easeOf('standard'),
          fill: 'both',
        })
      );
    }
  } catch {
    return;
  }
  await settle(animations);
}

/**
 * 底部弹层入场：面板上滑（spring 300ms，HIG 抽屉档 response≈0.3）+ 遮罩 0→1。
 * @param {HTMLElement|null} panelEl
 * @param {HTMLElement|null} [maskEl]
 * @returns {Promise<void>}
 */
export async function sheetIn(panelEl, maskEl) {
  if (!panelEl) return;
  await slideSheet(panelEl, maskEl, '100%', '0', 1, durOf('enter'), easeOf('spring'));
}

/**
 * 底部弹层收起（sheetIn 的配对）。
 * @param {HTMLElement|null} panelEl
 * @param {HTMLElement|null} [maskEl]
 * @returns {Promise<void>}
 */
export async function sheetOut(panelEl, maskEl) {
  if (!panelEl) return;
  await slideSheet(panelEl, maskEl, '0', '100%', 0, durOf('page'), easeOf('standard'));
}

/**
 * 下拉刷新指示器。
 * @param {HTMLElement|null} el
 * @param {'idle'|'pulling'|'settle'|'loading'} mode
 * @param {number} [progress] pulling 时的下拉进度 0~1
 */
export function pullSpin(el, mode, progress = 0) {
  if (!el) return;

  if (mode === 'loading') {
    if (el.__zstsSpin) return; // 已在转，避免重复起动画
    el.style.transform = '';
    try {
      el.__zstsSpin = el.animate([{ transform: 'rotate(0deg)' }, { transform: 'rotate(360deg)' }], {
        duration: 900,
        easing: 'linear',
        iterations: Infinity,
      });
    } catch { /* 忽略 */ }
    return;
  }

  if (el.__zstsSpin) {
    try {
      el.__zstsSpin.cancel();
    } catch { /* 忽略 */ }
    el.__zstsSpin = null;
  }

  if (mode === 'pulling') {
    const deg = Math.max(0, Math.min(180, progress * 180));
    el.style.transform = `rotate(${deg}deg)`;
    return;
  }

  if (mode === 'settle') {
    if (prefersReducedMotion()) {
      el.style.transform = '';
      return;
    }
    const from = el.style.transform || 'rotate(0deg)';
    try {
      el.animate([{ transform: from }, { transform: 'rotate(180deg)' }], {
        duration: durOf('pull'),
        easing: easeOf('standard'),
        fill: 'both',
      });
    } catch { /* 忽略 */ }
    el.style.transform = '';
    return;
  }

  el.style.transform = ''; // idle
}

/**
 * 骨架屏微光（1200ms 线性循环）。基线样式由 `.c-skeleton` 提供。
 * @param {HTMLElement|null} el
 * @param {boolean} [on]
 */
export function skeletonPulse(el, on = true) {
  if (!el) return;
  if (el.__zstsSkeleton) {
    try {
      el.__zstsSkeleton.cancel();
    } catch { /* 忽略 */ }
    el.__zstsSkeleton = null;
  }
  if (!on || prefersReducedMotion()) return;
  try {
    el.__zstsSkeleton = el.animate(
      [{ backgroundPosition: '-120% 0' }, { backgroundPosition: '220% 0' }],
      { duration: SKELETON_DURATION, easing: 'linear', iterations: Infinity }
    );
  } catch { /* 忽略 */ }
}
