/**
 * core/slideTo.js —— 绝对定位指示块的滑移动画（可中途打断）
 *
 * 为什么不用 CSS transition（底栏第一版就是这么写的，用户实测「连点不跟手」）：
 *   1. transition 被打断时只能从当前渲染位置重新跑一遍完整时长，读不出「还剩多少」。
 *      连点两下，第二下要等第一下的 320ms 跑完才开始收敛，看起来像胶囊在追手指。
 *   2. 位移与宽度同时 transition 会每帧触发布局，低端机上是肉眼可见的卡顿。
 *
 * 这里换成 Web Animations API，做法对齐烫梨的 ViewPropertyAnimator：
 *   · 起点取**当前渲染矩阵**（动画进行中 getComputedStyle 返回的是插值结果），
 *     所以任何时候被打断都能从肉眼看到的位置接着走，不会跳一帧。
 *   · 旧的动画先 cancel 再起新的，不排队、不叠加。
 *   · 时长按剩余距离等比缩短（走半格就 160ms），保证「点到哪，胶囊已经在路上」。
 *   · 被打断的那一程不再过冲：回弹曲线只在从静止起步时用，否则连续打断会抖。
 *   · 终态直接写进行内样式（fill: none），动画结束或取消都不会回弹到旧位置。
 *
 * 本文件只依赖浏览器标准 API，不 import 任何模块。
 */

/** 每个元素当前在跑的那段动画（用来判断是否是「打断」） */
const running = new WeakMap();

/** 默认时长：与 --dur-dock 一致（烫梨是 320ms） */
const DEFAULT_DURATION = 320;
/** 被打断时的最短时长：再短就变成瞬移，看不出移动方向 */
const MIN_DURATION = 140;
/** 回弹（过冲）与打断后用的收敛曲线 */
const EASE_SPRING = 'cubic-bezier(0.34, 1.56, 0.64, 1)';
const EASE_SETTLE = 'cubic-bezier(0.2, 0, 0, 1)';

/** 'none' / '' / 矩阵 三种形态都归一成一个数 */
function parseTranslate(transform) {
  if (!transform || transform === 'none') return 0;
  if (typeof DOMMatrixReadOnly === 'function') {
    return new DOMMatrixReadOnly(transform).m41;
  }
  const match = /matrix3?d?\(([^)]+)\)/.exec(transform);
  if (!match) return 0;
  const parts = match[1].split(',').map((value) => parseFloat(value));
  return parts.length === 16 ? parts[12] : parts[4];
}

function px(value) {
  const n = parseFloat(value);
  return Number.isFinite(n) ? n : 0;
}

/**
 * 把指示块滑到目标位置。
 *
 * @param {HTMLElement} el 需要是 `position: absolute` 且以 translate 定位
 * @param {{x: number, width?: number}} target x 是 translateX（px），width 省略则不动宽度
 * @param {{instant?: boolean, unit?: number, duration?: number}} [options]
 *   instant 直接落位（首帧 / 尺寸变化）；unit 是「一格」的距离，用来等比缩时长
 * @returns {Animation|null} 没有产生动画（已到位 / 直接落位）时为 null
 */
export function slideTo(el, target, options = {}) {
  if (!el || !el.isConnected) return null;

  const to = Number(target.x) || 0;
  const toWidth = target.width == null ? null : Number(target.width);

  const previous = running.get(el);
  const interrupted = Boolean(previous && previous.playState === 'running');
  // 必须在 cancel 之前读：cancel 会把计算样式回退成行内终态
  const from = parseTranslate(getComputedStyle(el).transform);
  const fromWidth = toWidth == null ? null : px(getComputedStyle(el).width);

  if (previous) previous.cancel();

  // 终态先写行内样式：动画 fill 为 none，结束后由它接管，取消也不会弹回
  el.style.transform = `translate3d(${to.toFixed(2)}px, 0, 0)`;
  if (toWidth != null) el.style.width = `${toWidth.toFixed(2)}px`;

  const shift = Math.abs(to - from);
  const resize = fromWidth == null ? 0 : Math.abs(toWidth - fromWidth);
  if (options.instant || (shift < 0.5 && resize < 0.5)) return null;
  if (typeof el.animate !== 'function') return null;
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return null;

  const base = Number(options.duration) || DEFAULT_DURATION;
  const unit = Math.max(1, Number(options.unit) || shift);
  // 走一格用满时长，走半格就一半；平移与缩放取较大的那个比例，避免宽高变化被拖慢
  const ratio = Math.min(1, Math.max(shift / unit, resize / Math.max(1, unit)));
  const duration = Math.round(Math.min(base, Math.max(MIN_DURATION, base * ratio)));

  // 宽度不变就**不要写进关键帧**：只要关键帧里带 width，Chrome 就不肯把这段动画
  // 交给合成器，主线程一忙（比如同时在做页面转场）就会被拖顿。只动 transform 时
  // 整段动画跑在合成器上，再怎么连点也稳。
  const animateWidth = toWidth != null && Math.abs(toWidth - fromWidth) > 0.5;

  const first = { transform: `translate3d(${from.toFixed(2)}px, 0, 0)` };
  const last = { transform: `translate3d(${to.toFixed(2)}px, 0, 0)` };
  if (animateWidth) {
    first.width = `${fromWidth.toFixed(2)}px`;
    last.width = `${toWidth.toFixed(2)}px`;
  }

  const animation = el.animate([first, last], {
    duration,
    // 从静止起步才有回弹；打断时用收敛曲线，连续快点不会抖
    easing: interrupted ? EASE_SETTLE : EASE_SPRING,
    fill: 'none',
  });
  running.set(el, animation);
  animation.onfinish = () => {
    if (running.get(el) === animation) running.delete(el);
  };
  return animation;
}

/** 页面卸载或元素重建时清掉记录，避免 WeakMap 里留着已取消的动画 */
export function forgetSlide(el) {
  const animation = running.get(el);
  if (animation) animation.cancel();
  running.delete(el);
}
