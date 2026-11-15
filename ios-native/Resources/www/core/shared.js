/**
 * shared.js —— 共享元素过渡（列表行 → 详情标题）
 *
 * 只做一件事：列表点击时记下那一行名字的位置，详情页挂载时把标题从那个位置「飞」到自己的位置。
 * 用 FLIP 的反向写法（先把目标元素摆到来源位置，再动画回原位），不造克隆节点：
 * 克隆节点要处理字体/换行/颜色一堆细节，而且会多出一份可读文本给读屏添乱。
 *
 * 只动 transform / opacity；系统开了「减弱动态效果」就直接不播。
 */

/** key → { text, rect } */
const pending = new Map();

/**
 * 记住来源元素的位置（点击瞬间调用，必须在路由跳转、DOM 被替换之前）。
 * @param {string} key
 * @param {Element|null} el
 */
export function rememberShared(key, el) {
  if (!key || !el) return;
  const rect = el.getBoundingClientRect();
  if (!rect.width || !rect.height) return;
  pending.set(key, {
    text: el.textContent || '',
    rect: { left: rect.left, top: rect.top, width: rect.width, height: rect.height },
  });
}

/**
 * 播放（消费一次记录：播完就删，避免返回列表时又飞一次）。
 * @param {string} key
 * @param {HTMLElement|null} target 详情页的标题元素
 */
export function playShared(key, target) {
  const from = pending.get(key);
  pending.delete(key);
  if (!from || !target || typeof target.animate !== 'function') return;
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

  const to = target.getBoundingClientRect();
  if (!to.width || !to.height) return;

  const dx = from.rect.left - to.left;
  const dy = from.rect.top - to.top;
  // 两边字号不同，按宽度比缩放；夹在 0.6~1.6 之间，避免长课名被压成一条
  const scale = Math.min(1.6, Math.max(0.6, from.rect.width / to.width));

  target.animate(
    [
      { transform: `translate3d(${dx}px, ${dy}px, 0) scale(${scale})`, opacity: 0.55 },
      { transform: 'translate3d(0, 0, 0) scale(1)', opacity: 1 },
    ],
    { duration: 250, easing: 'cubic-bezier(0.2, 0, 0, 1)', fill: 'none' }
  );
}
