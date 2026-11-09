/**
 * toast.js —— 轻量提示 / 二次确认 / 按钮 loading
 *
 * 三个 API：
 *   toast(msg, type)              顶部滑入，2.4s 后自动消失（PRD P01/P09 都用它）
 *   confirm(title, msg, opts)     二次确认，返回 Promise<boolean>
 *   setBusy(btn, busy)            按钮级 loading（禁用 + 转圈，不改写按钮文案）
 *
 * 本文件只 import core/dom 与 core/animate，不碰 pages/components/data。
 */

import { h } from './dom.js';
import { prefersReducedMotion } from './animate.js';
import { dialogIn, dialogOut } from './animateFeedback.js';

/** 单条 toast 的停留时长（含入场） */
const TOAST_DURATION = 2400;
/** 同时最多堆叠几条，超出就挤掉最旧的 */
const TOAST_MAX = 3;

/** 挂载容器缓存 */
let rootEl = null;

/**
 * 取（或懒建）toast 容器。
 * @returns {HTMLElement}
 */
function getRoot() {
  if (rootEl && rootEl.isConnected) return rootEl;
  rootEl = document.getElementById('toast-root');
  if (!rootEl) {
    rootEl = h('div', { id: 'toast-root', 'aria-live': 'polite', 'aria-atomic': 'true' });
    document.body.appendChild(rootEl);
  }
  return rootEl;
}

/**
 * 顶部滑入提示。
 *
 * @param {string} message 提示文案
 * @param {'info'|'success'|'warning'|'danger'} [type] 语义类型
 * @param {{duration?: number}} [opts]
 * @returns {() => void} 手动关闭函数
 */
export function toast(message, type = 'info', opts = {}) {
  const root = getRoot();
  const node = h('div', {
    className: `c-toast c-toast--${type}`,
    role: type === 'danger' ? 'alert' : 'status',
    text: message,
  });
  root.appendChild(node);

  // 超出上限：移除最旧的
  while (root.children.length > TOAST_MAX) {
    const oldest = root.firstElementChild;
    if (oldest) oldest.remove();
  }

  let closed = false;
  /** 关闭并移除 */
  const close = () => {
    if (closed) return;
    closed = true;
    if (!prefersReducedMotion()) {
      try {
        node
          .animate(
            [
              { opacity: 1, transform: 'translate3d(0, 0, 0)' },
              { opacity: 0, transform: 'translate3d(0, -8px, 0)' },
            ],
            { duration: 150, easing: 'cubic-bezier(0.2, 0, 0, 1)', fill: 'both' }
          )
          .finished.catch(() => {})
          .then(() => node.remove());
        return;
      } catch {
        /* 走进下面的直接移除 */
      }
    }
    node.remove();
  };

  if (!prefersReducedMotion()) {
    try {
      node.animate(
        [
          { opacity: 0, transform: 'translate3d(0, -12px, 0)' },
          { opacity: 1, transform: 'translate3d(0, 0, 0)' },
        ],
        { duration: 250, easing: 'cubic-bezier(0.2, 0, 0, 1)', fill: 'both' }
      );
    } catch {
      /* 忽略 */
    }
  }

  const duration = opts.duration === undefined ? TOAST_DURATION : opts.duration;
  if (duration > 0) window.setTimeout(close, duration);
  return close;
}

/**
 * 二次确认弹窗。
 *
 * 动效用 `dialogIn/dialogOut`（缩放淡入）而不是底部弹层那套上滑：
 * 它是**居中**的卡片，从屏幕底部滑上来会先穿过整页内容，看着像走错了地方。
 *
 * 层级：`.c-confirm-mask` 用 `--z-dialog`（70），高于 `#sheet-root`（60）——
 * 它经常是从某个 sheet 里点出来的（「本周停课」就在课程详情里），低一层就会被盖住。
 *
 * @param {string} title 标题
 * @param {string} [message] 说明文案
 * @param {{confirmText?: string, cancelText?: string, danger?: boolean}} [opts]
 * @returns {Promise<boolean>} 点确认为 true，点取消/遮罩/Esc 为 false
 */
export function confirm(title, message = '', opts = {}) {
  return new Promise((resolve) => {
    let settled = false;

    const mask = h('div', { className: 'c-confirm-mask', role: 'presentation' });
    const panel = h(
      'div',
      {
        className: 'c-confirm',
        role: 'alertdialog',
        'aria-modal': 'true',
        'aria-label': title,
      },
      [
        h('p', { className: 'c-confirm__title', text: title }),
        message ? h('p', { className: 'c-confirm__message', text: message }) : null,
        h('div', { className: 'c-confirm__actions' }, [
          h('button', {
            className: 'c-btn c-btn--ghost',
            type: 'button',
            'data-role': 'cancel',
            text: opts.cancelText || '取消',
          }),
          h('button', {
            className: `c-btn${opts.danger ? ' c-btn--danger' : ''}`,
            type: 'button',
            'data-role': 'confirm',
            text: opts.confirmText || '确定',
          }),
        ]),
      ]
    );
    mask.appendChild(panel);

    /** 收尾：先做收起动画，再移除并 resolve */
    const finish = (value) => {
      if (settled) return;
      settled = true;
      document.removeEventListener('keydown', onKeydown);
      dialogOut(panel, mask).then(() => {
        mask.remove();
        resolve(value);
      });
    };

    /** Esc = 取消 */
    const onKeydown = (event) => {
      if (event.key === 'Escape') finish(false);
    };

    panel.querySelector('[data-role="cancel"]').addEventListener('click', () => finish(false));
    panel.querySelector('[data-role="confirm"]').addEventListener('click', () => finish(true));
    // 点遮罩空白处 = 取消；点面板内部不冒泡处理
    mask.addEventListener('click', (event) => {
      if (event.target === mask) finish(false);
    });
    document.addEventListener('keydown', onKeydown);

    document.body.appendChild(mask);
    dialogIn(panel, mask);
    const confirmBtn = panel.querySelector('[data-role="confirm"]');
    if (confirmBtn) confirmBtn.focus();
  });
}

/**
 * 按钮级 loading：禁用 + 转圈，不改写按钮原有文案（避免恢复时丢内容）。
 * @param {HTMLButtonElement|null} btn
 * @param {boolean} busy
 */
export function setBusy(btn, busy) {
  if (!btn) return;
  const isBusy = busy === true;
  btn.classList.toggle('c-btn--busy', isBusy);
  btn.disabled = isBusy;
  btn.setAttribute('aria-busy', isBusy ? 'true' : 'false');
}
