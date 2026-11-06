/**
 * appBar.js —— 两段式顶栏（复刻教务处口径）
 *
 * 最高优先级验收（PRD F-02 / §4 顶栏文本格式）：
 *   左段 `26~27学年第一学期`   右段 `第 10 教学周`
 *   两段都用 caption 13px / 600 字重；390px 宽下**单行、不换行、不截断、不溢出**
 *
 * 因此这里刻意用**两个独立 span** 而不是拼一整串文本：
 *   · 左段 flex:0 0 auto + nowrap —— 内容固定长度，不需要省略号（截断即违约）
 *   · 右段 margin-left:auto + nowrap —— 永远靠右且永不被压缩
 *   · 外层 overflow-x:clip 兜底：万一字号被系统放大到 130%，宁可溢出被裁也不换行
 *
 * 另外包含演示提示条（36px，可关闭，本批只做静态 UI，状态由 app.js 决定）。
 *
 * 本文件只 import core/ —— 不碰 data（教学周上下文由 app.js 从 calendar.js 算好后传进来）。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';

/** 演示提示条文案（PRD C2-a，注意「·」两侧各一个半角空格） */
export const DEMO_NOTICE_TEXT = '演示数据 · 非真实教务信息';

/** 滚动超过这个距离就切紧凑态（64 → 56px） */
const COMPACT_THRESHOLD = 8;

/** 返回箭头与关闭叉号（图标定义统一在 core/icons.js，这里只引用） */
const BACK_ICON = icon('back', 22, { strokeWidth: 1.9 });
const CLOSE_ICON = icon('close', 16, { strokeWidth: 1.9 });

/**
 * 从 TermHeaderContext 里取左右两段文本（做了容错，缺字段也不崩）。
 * @param {object|null} context
 * @returns {{left: string, right: string, full: string}}
 */
function splitContext(context) {
  if (!context) return { left: '—', right: '', full: '—' };
  const left = context.left || `${context.displayYear || ''}学年${context.termLabel || ''}` || '—';
  let right = context.text;
  if (!right) {
    right = context.phase === 'inTerm' && context.week ? `第 ${context.week} 教学周` : '假期中';
  }
  const full = context.full || `${left} ${right}`;
  return { left, right, full };
}

/**
 * 建顶栏。
 *
 * @param {{
 *   context?: object|null,
 *   notice?: boolean,
 *   onNoticeClose?: (() => void)|null,
 *   scrollRoot?: HTMLElement|null,
 *   compactOnScroll?: boolean
 * }} [options]
 * @returns {{
 *   el: HTMLElement,
 *   setContext: (context: object|null) => void,
 *   setNotice: (visible: boolean) => void,
 *   setCompact: (compact: boolean) => void,
 *   setBack: (handler: (() => void)|null) => void,
 *   setActions: (nodes: Node|Node[]|null) => void,
 *   destroy: () => void
 * }}
 */
export function createAppBar(options = {}) {
  const { onNoticeClose = null, compactOnScroll = true } = options;
  const scrollRoot = options.scrollRoot || document.getElementById('app-main');

  const backButton = h('button', {
    className: 'c-appbar__back',
    type: 'button',
    'aria-label': '返回',
    hidden: true,
    html: BACK_ICON,
  });

  const termEl = h('span', { className: 'c-appbar__term' });
  const weekEl = h('span', { className: 'c-appbar__week' });
  const actionsEl = h('div', { className: 'c-appbar__actions' });

  const bar = h('div', { className: 'c-appbar__bar' }, [backButton, termEl, weekEl, actionsEl]);

  const noticeText = h('span', { className: 'c-appbar__notice-text', text: DEMO_NOTICE_TEXT });
  const noticeClose = h('button', {
    className: 'c-appbar__notice-close',
    type: 'button',
    'data-action': 'dismiss-banner',
    'aria-label': '关闭演示提示',
    html: CLOSE_ICON,
  });
  const noticeEl = h('div', { className: 'c-appbar__notice', role: 'status' }, [
    noticeText,
    noticeClose,
  ]);

  const el = h('header', { className: 'c-appbar' }, [bar, noticeEl]);

  /**
   * 写顶栏文案。这里只改 textContent，不动结构 —— 切页时不产生布局抖动。
   * @param {object|null} context
   */
  function setContext(context) {
    const { left, right, full } = splitContext(context);
    if (termEl.textContent !== left) termEl.textContent = left;
    if (weekEl.textContent !== right) weekEl.textContent = right;
    el.setAttribute('aria-label', full);
    el.setAttribute('title', full);
  }

  /**
   * 显示/隐藏演示提示条。
   * @param {boolean} visible
   */
  function setNotice(visible) {
    if (noticeEl.hidden === !visible) return;
    noticeEl.hidden = !visible;
  }

  /**
   * 紧凑态（滚动时 64 → 56px）。
   * @param {boolean} compact
   */
  function setCompact(compact) {
    const next = Boolean(compact);
    if (el.classList.contains('is-compact') === next) return;
    el.classList.toggle('is-compact', next);
  }

  /**
   * 显示返回按钮（次级页）。
   * @param {(() => void)|null} handler 传 null 隐藏
   */
  function setBack(handler) {
    if (typeof handler === 'function') {
      backButton.hidden = false;
      backButton.onclick = handler;
      return;
    }
    backButton.hidden = true;
    backButton.onclick = null;
  }

  /**
   * 右侧操作区（首页放刷新按钮等）。
   * @param {Node|Node[]|null} nodes
   */
  function setActions(nodes) {
    actionsEl.replaceChildren();
    if (!nodes) return;
    for (const node of Array.isArray(nodes) ? nodes : [nodes]) {
      if (node) actionsEl.appendChild(node);
    }
  }

  // 关演示提示条
  noticeClose.addEventListener('click', () => {
    setNotice(false);
    if (typeof onNoticeClose === 'function') onNoticeClose();
  });

  // 滚动收缩
  let onScroll = null;
  if (compactOnScroll && scrollRoot) {
    onScroll = () => setCompact(scrollRoot.scrollTop > COMPACT_THRESHOLD);
    scrollRoot.addEventListener('scroll', onScroll, { passive: true });
  }

  // 初始状态
  setContext(options.context || null);
  setNotice(options.notice !== false);

  return {
    el,
    setContext,
    setNotice,
    setCompact,
    setBack,
    setActions,
    destroy() {
      if (onScroll && scrollRoot) scrollRoot.removeEventListener('scroll', onScroll);
      el.remove();
    },
  };
}
