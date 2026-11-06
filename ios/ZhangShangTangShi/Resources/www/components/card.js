/**
 * card.js —— 卡片 / 分区标题 / 键值行 / 进度条（原子分子复用件）
 *
 * 为什么这四个放一个文件：它们是所有页面都会用到的「最小积木」，一个卡片内部往往就是
 * 标题 + 几行键值 + 一条进度条。拆成四个文件反而让每个页面写四行 import。
 *
 * 约定：
 *   · 只收 props，不读 store、不 fetch（L2 组件层不得碰 data/）
 *   · 不产生任何颜色字面量，配色全部由 components.css 里的 .c-card / .c-progress 提供
 *   · 进度条用 `--fill` 自定义属性 + CSS `scaleX`，不用 width —— 宽度动画会触发布局
 */

import { h } from '../core/dom.js';

/**
 * 通用卡片。
 *
 * @param {{
 *   title?: string,
 *   subtitle?: string,
 *   extra?: Node|string|null,
 *   children?: unknown,
 *   flush?: boolean,
 *   className?: string,
 *   dataset?: Record<string, string>
 * }} [options]
 * @param {unknown} [children] 第二个参数也可传内容（`card(props, children)` 更好读）
 * @returns {HTMLElement}
 */
export function card(options = {}, children) {
  const { title, subtitle, extra = null, flush = false, className = '', dataset } = options;
  const body = options.children === undefined ? children : options.children;

  const classes = ['c-card'];
  if (flush) classes.push('c-card--flush');
  if (className) classes.push(className);

  const nodes = [];
  if (title || subtitle || extra) {
    nodes.push(
      h('div', { className: 'c-card__header' }, [
        h('div', { className: 'c-card__heading' }, [
          title ? h('h2', { className: 'c-card__title', text: title }) : null,
          subtitle ? h('p', { className: 'c-card__subtitle', text: subtitle }) : null,
        ]),
        extra ? h('div', { className: 'c-card__extra' }, extra) : null,
      ])
    );
  }
  if (body !== undefined && body !== null) {
    nodes.push(h('div', { className: 'c-card__body' }, body));
  }

  return h('section', { className: classes.join(' '), dataset }, nodes);
}

/**
 * 分区标题（卡片之上的一行小标题 + 右侧可选操作）。
 *
 * @param {string} text
 * @param {{action?: Node|null, hint?: string}} [options]
 * @returns {HTMLElement}
 */
export function sectionTitle(text, options = {}) {
  return h('div', { className: 'c-section' }, [
    h('h2', { className: 'c-section__title', text }),
    options.hint ? h('span', { className: 'c-section__hint', text: options.hint }) : null,
    options.action ? h('div', { className: 'c-section__action' }, options.action) : null,
  ]);
}

/**
 * 键值行。`value` 允许传节点（比如带单位的小字、状态胶囊）。
 *
 * @param {string} label
 * @param {Node|string|number|null} value
 * @param {{mono?: boolean, hint?: string, className?: string}} [options]
 * @returns {HTMLElement}
 */
export function kvRow(label, value, options = {}) {
  const classes = ['c-kv'];
  if (options.mono) classes.push('c-kv--num');
  if (options.className) classes.push(options.className);

  const valueNode =
    value instanceof Node ? value : h('span', { className: 'c-kv__text', text: value === null || value === undefined ? '—' : String(value) });

  return h('div', { className: classes.join(' ') }, [
    h('span', { className: 'c-kv__key', text: label }),
    h('span', { className: 'c-kv__value' }, valueNode),
    options.hint ? h('span', { className: 'c-kv__hint', text: options.hint }) : null,
  ]);
}

/**
 * 一组键值行（自动套 c-kv-list 的分割线）。
 * @param {Array<[string, Node|string|number, object?]>} rows
 * @returns {HTMLElement}
 */
export function kvList(rows) {
  return h(
    'div',
    { className: 'c-kv-list' },
    (rows || []).map(([label, value, options]) => kvRow(label, value, options))
  );
}

/**
 * 进度条。`value / max` 自动夹到 0~1，渲染时用 scaleX 表达长度。
 *
 * @param {{
 *   value: number,
 *   max?: number,
 *   tone?: 'brand'|'accent'|'success'|'danger',
 *   label?: string,
 *   height?: number,
 *   showTrack?: boolean
 * }} options
 * @returns {HTMLElement}
 */
export function progressBar(options = {}) {
  const max = Number(options.max) > 0 ? Number(options.max) : 1;
  const raw = Number(options.value);
  const ratio = Number.isFinite(raw) ? Math.min(1, Math.max(0, raw / max)) : 0;
  const tone = options.tone || 'brand';

  const bar = h('div', {
    className: `c-progress c-progress--${tone}`,
    role: 'progressbar',
    'aria-valuemin': '0',
    'aria-valuemax': String(max),
    'aria-valuenow': String(Number.isFinite(raw) ? raw : 0),
    'aria-label': options.label || '进度',
    style: { '--fill': String(ratio) },
  });

  if (options.height) bar.style.setProperty('--progress-h', `${options.height}px`);
  if (options.showTrack === false) bar.classList.add('c-progress--bare');
  bar.appendChild(h('span', { className: 'c-progress__fill' }));
  return bar;
}
