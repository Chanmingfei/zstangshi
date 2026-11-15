/**
 * clipboard.js —— 写剪贴板 + 长按手势（core 层，只依赖 dom.js）
 *
 * 为什么抽到 core：成绩行与课表详情层都要「长按 500ms 复制一段文本」。
 * 两处各写一份的结果是 —— 手势参数（500ms / 8px 容差）越改越不一致，
 * 而 `navigator.clipboard` 的兜底只在一处修，另一处永远踩同一个坑。
 *
 * 关于兜底：**必须带 `document.execCommand('copy')` + 临时 textarea**。
 * Android WebView 里 `navigator.clipboard` 常常整个不存在（非 secure context），
 * 只判 `navigator.clipboard` 会直接抛 TypeError 或静默失败，用户以为复制成功了。
 *
 * 长按为什么用 pointer 事件而不是 touch：桌面预览时鼠标长按也要能用，一套代码全覆盖。
 * 长按成功后必须让调用方「吃掉随后的 click」，否则复制完立刻跳进详情页。
 */

import { h } from './dom.js';

/** 长按判定时长（与 WakeUp 的手感对齐，500ms 不会误触） */
export const LONG_PRESS_MS = 500;
/** 手指/指针移动超过这个距离就认为不是长按（是在滚动列表） */
export const LONG_PRESS_TOLERANCE = 8;

/**
 * 写剪贴板。优先异步 API，失败时退回隐藏 textarea（老 WebView 与 http 页面常见）。
 * @param {string} text
 * @returns {Promise<boolean>} 是否确实写入成功
 */
export async function writeClipboard(text) {
  const value = String(text === null || text === undefined ? '' : text);
  try {
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      await navigator.clipboard.writeText(value);
      return true;
    }
  } catch {
    /* 权限被拒 / 非 secure context：落到下面的兜底 */
  }
  try {
    const area = h('textarea', { className: 'u-sr-only', 'aria-hidden': 'true' });
    area.value = value;
    document.body.appendChild(area);
    area.select();
    // 老 API，但它是 Android WebView 里唯一能用的路径
    const ok = document.execCommand('copy');
    area.remove();
    return ok;
  } catch {
    return false;
  }
}

/**
 * 绑定长按。调用方拿到返回值后，在 click 里调 `consume()` 判断「这次点击要不要吃掉」。
 *
 * @param {HTMLElement} target 长按热区（一般是列表行 / 弹层内容区）
 * @param {(event: PointerEvent) => void} onLongPress 触发回调（已经是第 500ms 之后）
 * @param {{ms?: number, tolerance?: number, vibrate?: boolean}} [options]
 * @returns {{consume: () => boolean, destroy: () => void}}
 */
export function bindLongPress(target, onLongPress, options = {}) {
  const ms = Number(options.ms) > 0 ? Number(options.ms) : LONG_PRESS_MS;
  const tolerance = Number(options.tolerance) > 0 ? Number(options.tolerance) : LONG_PRESS_TOLERANCE;
  const wantVibrate = options.vibrate !== false;

  let timer = 0;
  let startX = 0;
  let startY = 0;
  let pressed = false;
  /** 长按已触发：随后的 click 必须被吃掉 */
  let fired = false;

  const clearTimer = () => {
    if (timer) window.clearTimeout(timer);
    timer = 0;
  };

  const onPointerDown = (event) => {
    if (event.button !== undefined && event.button !== 0) return;
    // 复位上一轮的长按标记：长按时手指划出行外再松手，浏览器不会给这一行派 click，
    // `fired` 会一直挂着，把用户下一次正常点击也吃掉（表现为「点一下没反应」）。
    // 新手势开始前清掉，因为同一个手势里 pointerdown 一定早于它的 click。
    fired = false;
    pressed = true;
    startX = event.clientX;
    startY = event.clientY;
    clearTimer();
    timer = window.setTimeout(() => {
      if (!pressed) return;
      fired = true;
      // 轻微震动反馈（不支持的浏览器静默忽略）
      if (wantVibrate && navigator.vibrate) {
        try {
          navigator.vibrate(12);
        } catch {
          /* 忽略 */
        }
      }
      onLongPress(event);
    }, ms);
  };

  const onPointerMove = (event) => {
    if (!pressed) return;
    if (Math.abs(event.clientX - startX) > tolerance || Math.abs(event.clientY - startY) > tolerance) {
      // 用户在滚页面，不是在长按
      pressed = false;
      clearTimer();
    }
  };

  const onPointerUp = () => {
    pressed = false;
    clearTimer();
  };

  // 长按会触发系统文本选择菜单，必须拦掉，否则和「复制」抢焦点
  const onContextMenu = (event) => {
    if (pressed || fired) event.preventDefault();
  };

  target.addEventListener('pointerdown', onPointerDown);
  target.addEventListener('pointermove', onPointerMove);
  target.addEventListener('pointerup', onPointerUp);
  target.addEventListener('pointercancel', onPointerUp);
  target.addEventListener('pointerleave', onPointerUp);
  target.addEventListener('contextmenu', onContextMenu);

  return {
    /** 读一次并复位「刚刚长按过」标记 */
    consume() {
      const value = fired;
      fired = false;
      return value;
    },
    destroy() {
      clearTimer();
      pressed = false;
      fired = false;
      target.removeEventListener('pointerdown', onPointerDown);
      target.removeEventListener('pointermove', onPointerMove);
      target.removeEventListener('pointerup', onPointerUp);
      target.removeEventListener('pointercancel', onPointerUp);
      target.removeEventListener('pointerleave', onPointerUp);
      target.removeEventListener('contextmenu', onContextMenu);
    },
  };
}
