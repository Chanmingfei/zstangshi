/**
 * emptyState.js —— 统一空态 / 错误态 / 离线态 / 需登录态
 *
 * 为什么要四态而不是一个「暂无数据」：用户看到「暂无数据」时无法判断是自己没选课、
 * 还是网络断了、还是没登录 —— 三种情况的下一步动作完全不同（去选课 / 重试 / 去登录）。
 * 所以图标、主文案、次文案、按钮四件套都按状态区分，默认文案集中在 EMPTY_PRESETS，
 * 页面只需传 kind，需要时再覆盖文案。
 *
 * 组件层不读 store：`offline` 与否由页面判断后传 kind。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';

/**
 * 四种状态的默认文案与图标。
 * @type {Record<string, {icon: string, title: string, description: string}>}
 */
export const EMPTY_PRESETS = {
  empty: {
    icon: 'book',
    title: '暂无数据',
    description: '这里还没有内容，稍后再来看看',
  },
  error: {
    icon: 'alertCircle',
    title: '加载失败',
    description: '数据没能取回来，请稍后重试',
  },
  offline: {
    icon: 'wifiOff',
    title: '网络不可用',
    description: '请检查网络连接，或先查看已同步的内容',
  },
  auth: {
    icon: 'lock',
    title: '需要登录',
    description: '登录教务账号后可查看成绩、课表与绩点',
  },
};

/**
 * 建一个状态占位块。
 *
 * @param {{
 *   kind?: 'empty'|'error'|'offline'|'auth',
 *   title?: string,
 *   description?: string,
 *   actionLabel?: string,
 *   onAction?: (() => void)|null,
 *   secondaryLabel?: string,
 *   onSecondary?: (() => void)|null,
 *   compact?: boolean
 * }} [options]
 * @returns {HTMLElement}
 */
export function emptyState(options = {}) {
  const kind = EMPTY_PRESETS[options.kind] ? options.kind : 'empty';
  const preset = EMPTY_PRESETS[kind];
  const title = options.title || preset.title;
  const description = options.description || preset.description;

  const actions = [];
  if (options.actionLabel) {
    const primary = h('button', {
      className: 'c-btn',
      type: 'button',
      text: options.actionLabel,
    });
    if (typeof options.onAction === 'function') primary.addEventListener('click', options.onAction);
    actions.push(primary);
  }
  if (options.secondaryLabel) {
    const secondary = h('button', {
      className: 'c-btn c-btn--text',
      type: 'button',
      text: options.secondaryLabel,
    });
    if (typeof options.onSecondary === 'function') secondary.addEventListener('click', options.onSecondary);
    actions.push(secondary);
  }

  const classes = ['c-empty', `c-empty--${kind}`];
  if (options.compact) classes.push('c-empty--compact');

  return h(
    'div',
    {
      className: classes.join(' '),
      // 错误与离线是需要立刻被读屏播报的状态；空态只是一句说明，用 status 足够
      role: kind === 'error' || kind === 'offline' ? 'alert' : 'status',
    },
    [
      h('span', { className: 'c-empty__icon', 'aria-hidden': 'true', html: icon(preset.icon, 40, { strokeWidth: 1.5 }) }),
      h('p', { className: 'c-empty__title', text: title }),
      description ? h('p', { className: 'c-empty__desc', text: description }) : null,
      actions.length > 0 ? h('div', { className: 'c-empty__actions' }, actions) : null,
    ]
  );
}
