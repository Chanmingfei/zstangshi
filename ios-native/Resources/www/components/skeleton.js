/**
 * skeleton.js —— 骨架屏三形态（列表行 / 卡片 / 图表）
 *
 * 为什么三种而不是一个「万能骨架」：列表、卡片、图表的占位形状差别很大，用同一套矩形
 * 拼出来的骨架会让页面在数据到达时「跳一下」，反而更廉价。所以按真实内容的骨架比例画。
 *
 * 微光：1200ms linear 循环，由 core/animate.js 的 `skeletonPulse()` 驱动 WAAPI，
 * 不写 CSS keyframes —— 一是「减弱动态效果」只需在一处判断，二是 background-position
 * 动画不触发重排，滚动时不掉帧。纯灰块闪烁是明确禁止的（PRD §8.4）。
 */

import { h } from '../core/dom.js';
import { skeletonPulse } from '../core/animateFeedback.js';

/** 微光块：所有形态都由它拼出来 */
function block(className, style) {
  const el = h('span', { className: `c-skeleton ${className}`, 'aria-hidden': 'true', style });
  skeletonPulse(el, true);
  return el;
}

/**
 * 列表行骨架（成绩列表 / 选课列表）。
 * @param {{rows?: number, avatar?: boolean, withTrailing?: boolean}} [options]
 * @returns {HTMLElement}
 */
export function skeletonList(options = {}) {
  const rows = Math.max(1, Number(options.rows) || 3);
  const items = [];
  for (let i = 0; i < rows; i += 1) {
    items.push(
      h('div', { className: 'c-skel-row' }, [
        options.avatar ? block('c-skeleton--avatar') : null,
        h('div', { className: 'c-skel-row__main' }, [
          block('c-skeleton--line', { width: `${72 - (i % 3) * 8}%` }),
          block('c-skeleton--line c-skeleton--sm', { width: `${48 + (i % 2) * 10}%` }),
        ]),
        options.withTrailing === false ? null : block('c-skeleton--pill'),
      ])
    );
  }
  return h('div', { className: 'c-skel c-skel--list', role: 'status', 'aria-label': '加载中' }, items);
}

/**
 * 卡片骨架（首页今日课程卡 / 各类页头卡片）。
 * @param {{lines?: number, showHeader?: boolean}} [options]
 * @returns {HTMLElement}
 */
export function skeletonCard(options = {}) {
  const lines = Math.max(1, Number(options.lines) || 3);
  const body = [];
  if (options.showHeader !== false) body.push(block('c-skeleton--title', { width: '46%' }));
  for (let i = 0; i < lines; i += 1) {
    body.push(block('c-skeleton--line', { width: `${92 - i * 12}%` }));
  }
  return h(
    'div',
    { className: 'c-skel c-skel--card', role: 'status', 'aria-label': '加载中' },
    h('div', { className: 'c-skel-card__inner' }, body)
  );
}

/**
 * 图表骨架（GPA 折线 / 学分环）。
 * @param {{type?: 'line'|'ring'}} [options]
 * @returns {HTMLElement}
 */
export function skeletonChart(options = {}) {
  const type = options.type === 'ring' ? 'ring' : 'line';
  if (type === 'ring') {
    return h('div', { className: 'c-skel c-skel--ring', role: 'status', 'aria-label': '加载中' }, [
      block('c-skeleton--ring'),
      block('c-skeleton--line c-skeleton--sm', { width: '40%' }),
    ]);
  }
  // 折线：用 5 根高低不同的竖条模拟趋势，比一个灰方块更像「数据要来了」
  const bars = [0.42, 0.62, 0.5, 0.78, 0.66];
  return h(
    'div',
    { className: 'c-skel c-skel--chart', role: 'status', 'aria-label': '加载中' },
    [
      block('c-skeleton--plot'),
      h(
        'div',
        { className: 'c-skel-chart__bars' },
        bars.map((ratio) => block('c-skeleton--bar', { height: `${Math.round(ratio * 100)}%` }))
      ),
    ]
  );
}

/**
 * 按名字取骨架（页面用 `slot.status` 直接映射到形态）。
 * @param {'list'|'card'|'chart'|'ring'} kind
 * @param {object} [options]
 * @returns {HTMLElement}
 */
export function skeleton(kind, options = {}) {
  if (kind === 'card') return skeletonCard(options);
  if (kind === 'chart') return skeletonChart({ type: 'line' });
  if (kind === 'ring') return skeletonChart({ type: 'ring' });
  return skeletonList(options);
}

/**
 * 停掉某棵子树里的所有微光（替换成真实内容前必须调用，否则 WAAPI 动画会一直挂在该节点上）。
 * @param {HTMLElement|null} root
 */
export function stopSkeleton(root) {
  if (!root) return;
  const nodes = root.matches('.c-skeleton') ? [root] : [];
  for (const node of root.querySelectorAll('.c-skeleton')) nodes.push(node);
  for (const node of nodes) skeletonPulse(node, false);
}
