/**
 * placeholder.js —— 未实现页面的占位模块（T08~T12 逐个替换成真页面）
 *
 * 为什么要有它：首页宫格 7 个入口必须「点了有反应」。如果路由指向一个还不存在的页面，
 * 用户看到的就是白屏 —— 那是最糟的失败态。占位页保证任何入口点进去都有明确的
 * 「这个功能在做了」+ 一条回首页的路。
 *
 * 有意做成工厂而不是单例：每个路由要显示自己的名字，共用一份实例会让标题互相覆盖。
 */

import { h } from '../core/dom.js';
import { emptyState } from '../components/emptyState.js';

/**
 * 建一个占位页面模块（满足 router 的页面契约）。
 *
 * @param {{title: string, name?: string, note?: string, requiresAuth?: boolean}} options
 * @returns {{name: string, title: string, requiresAuth: boolean, render: Function, mount: Function}}
 */
export function placeholderPage(options = {}) {
  const title = options.title || '功能开发中';
  const note = options.note || '这个页面还在开发中，先回首页看看别的';

  return {
    name: options.name || 'placeholder',
    title,
    requiresAuth: options.requiresAuth === true,

    /**
     * 同步返回骨架。
     * @returns {HTMLElement}
     */
    render() {
      return h('div', { className: 'page p-placeholder' }, [
        h('h1', { className: 'page__title', text: title }),
        emptyState({
          kind: 'empty',
          title: '功能开发中',
          description: note,
          actionLabel: '回首页',
          onAction: () => {
            window.location.hash = '#/home';
          },
        }),
      ]);
    },

    /** 占位页没有数据，返回空 cleanup 保持契约不漂移。 */
    mount() {
      return () => {};
    },
  };
}
