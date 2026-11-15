/**
 * errorPage.js —— ErrorBoundary 的兜底 UI
 *
 * 由一个页面模块的 render/mount 抛异常时，整站不能跟着白屏：其它 Tab、顶栏、返回都还得能用。
 * 所以兜底页只占 #app-main 这一块，并且必须给出「重试」——重试就是让 router 重新渲染当前路由
 * （同一 hash 再 render 一次），不重新加载整页。
 *
 * 错误摘要默认折叠：普通用户不需要看堆栈，开发者验收时需要，`<details>` 一行就够，
 * 不自己造展开动画。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';

/**
 * @param {{path?: string, error?: Error|null, onRetry?: (() => void)|null}} info
 * @returns {HTMLElement}
 */
export function errorPage(info = {}) {
  const message = info.error && info.error.message ? info.error.message : '未知错误';
  const stack = info.error && info.error.stack ? String(info.error.stack) : message;

  const retry = h('button', { className: 'c-btn', type: 'button', text: '重试' });
  if (typeof info.onRetry === 'function') retry.addEventListener('click', info.onRetry);

  return h('div', { className: 'page p-error', role: 'alert' }, [
    h('div', { className: 'c-empty' }, [
      h('span', {
        className: 'c-empty__icon',
        'aria-hidden': 'true',
        html: icon('alertCircle', 40, { strokeWidth: 1.5 }),
        style: { backgroundColor: 'var(--danger-soft)', color: 'var(--danger)' },
      }),
      h('p', { className: 'c-empty__title', text: '这个页面出了点小问题' }),
      h('p', { className: 'c-empty__desc', text: '数据或界面没能正常渲染，可以重试一次；也可以切到别的 Tab。' }),
      h('div', { className: 'c-empty__actions' }, [
        retry,
        h('button', {
          className: 'c-btn c-btn--text',
          type: 'button',
          text: '回首页',
          onClick: () => {
            window.location.hash = '#/home';
          },
        }),
      ]),
    ]),
    h('details', { className: 'p-error__detail' }, [
      h('summary', { className: 'p-error__summary', text: `错误详情（${info.path || '未知路由'}）` }),
      h('pre', { className: 'p-error__stack', text: stack }),
    ]),
  ]);
}
