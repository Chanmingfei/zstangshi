/**
 * dom.js —— 极简 DOM 工具（零依赖，无 polyfill）
 *
 * 只做三件事：
 *   1. `h()` 声明式建节点，替代手写 createElement + appendChild 的模板噪声
 *   2. `qs/qsa/on/delegate/bindActions()` 查询与事件绑定（全局走 data-action 委托）
 *   3. `setText/clear/escapeHtml` 三个高频小工具
 *
 * 约定：本文件不 import 任何其它模块 —— core 层最底层，谁都可以依赖它。
 */

/** 需要走 SVG 命名空间创建的标签 */
const SVG_TAGS = new Set([
  'svg',
  'g',
  'path',
  'circle',
  'ellipse',
  'rect',
  'line',
  'polyline',
  'polygon',
  'text',
  'defs',
  'stop',
  'lineargradient',
  'radialgradient',
]);

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * 把数组/字符串/节点拍平成节点数组。
 * @param {unknown} value
 * @param {Node[]} out
 * @returns {Node[]}
 */
function flatten(value, out) {
  if (value === null || value === undefined || value === false || value === true) return out;
  if (Array.isArray(value)) {
    for (const item of value) flatten(item, out);
    return out;
  }
  if (value instanceof Node) {
    out.push(value);
    return out;
  }
  out.push(document.createTextNode(String(value)));
  return out;
}

/**
 * 给元素套用属性集合。
 * @param {Element} el
 * @param {Record<string, unknown>} props
 */
function applyProps(el, props) {
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;

    if (key === 'className' || key === 'class') {
      el.setAttribute('class', String(value));
      continue;
    }
    if (key === 'text') {
      el.textContent = String(value);
      continue;
    }
    if (key === 'html') {
      // 仅用于内联 SVG 图标这类受控静态串，切勿拼用户输入
      el.innerHTML = String(value);
      continue;
    }
    if (key === 'dataset') {
      for (const [dataKey, dataValue] of Object.entries(value)) {
        if (dataValue === null || dataValue === undefined) continue;
        el.dataset[dataKey] = String(dataValue);
      }
      continue;
    }
    if (key === 'style') {
      for (const [styleKey, styleValue] of Object.entries(value)) {
        if (styleValue === null || styleValue === undefined) continue;
        if (styleKey.startsWith('--')) el.style.setProperty(styleKey, String(styleValue));
        else el.style[styleKey] = String(styleValue);
      }
      continue;
    }
    if (key === 'ref' && typeof value === 'function') {
      value(el);
      continue;
    }
    // onXxx → addEventListener('xxx')
    // 同时接受驼峰 `onClick` 与 HTML 习惯的小写 `onclick`：
    // 早期 tabBar / errorPage 写的是小写，只认 /^on[A-Z]/ 时会被落到下面的
    // setAttribute('onclick', String(fn))，函数源码变成内联脚本字符串，
    // 点击时只会创建一个不被调用的箭头函数 —— 表现就是「按钮点了没反应」。
    // 事件名统一小写化，两种写法都绑到同一个监听器上。
    if (/^on[A-Za-z]/.test(key) && typeof value === 'function') {
      el.addEventListener(key.slice(2).toLowerCase(), value);
      continue;
    }
    if (/^on[a-z]/i.test(key) && typeof value === 'string') {
      // 字符串形式的 onXxx 是误用，别让它变成可执行的内联脚本
      console.warn(`[zsts] h(): ${key} 应传函数而非字符串，已忽略`);
      continue;
    }
    if (value === true) {
      el.setAttribute(key, '');
      continue;
    }
    el.setAttribute(key, String(value));
  }
}

/**
 * 建一个真实 DOM 节点。
 *
 * @param {string} tag 标签名；svg 系标签自动走 SVG 命名空间
 * @param {Record<string, unknown>|null} [props] 属性表：className / dataset / style / text / html / onXxx / 任意 HTML 属性
 * @param {unknown} [children] 子节点：Node | string | 数组 | 嵌套数组，null/false 自动跳过
 * @returns {HTMLElement}
 *
 * @example
 *   h('button', { className: 'c-btn', dataset: { action: 'reload' } }, '刷新')
 *   h('ul', null, items.map((it) => h('li', { text: it.name })))
 */
export function h(tag, props, children) {
  const el = SVG_TAGS.has(tag.toLowerCase())
    ? document.createElementNS(SVG_NS, tag)
    : document.createElement(tag);
  if (props && typeof props === 'object' && !(props instanceof Node) && !Array.isArray(props)) {
    applyProps(el, props);
  } else if (props !== null && props !== undefined) {
    // 允许 h('div', '文本') / h('div', [node]) 这种省略 props 的写法
    children = children === undefined ? props : children;
  }
  if (children !== undefined && children !== null) {
    for (const node of flatten(children, [])) el.appendChild(node);
  }
  return el;
}

/**
 * 文档片段（批量挂载时减少回流）。
 * @param {unknown} children
 * @returns {DocumentFragment}
 */
export function frag(children) {
  const f = document.createDocumentFragment();
  for (const node of flatten(children, [])) f.appendChild(node);
  return f;
}

/**
 * querySelector 简写。
 * @param {string} selector
 * @param {ParentNode} [root] 缺省 document
 * @returns {Element|null}
 */
export function qs(selector, root = document) {
  return root.querySelector(selector);
}

/**
 * querySelectorAll 简写，返回真数组（可 map/filter）。
 * @param {string} selector
 * @param {ParentNode} [root]
 * @returns {Element[]}
 */
export function qsa(selector, root = document) {
  return Array.from(root.querySelectorAll(selector));
}

/**
 * 绑定事件，返回解绑函数。
 * @param {EventTarget} target
 * @param {string} type
 * @param {(event: Event) => void} handler
 * @param {AddEventListenerOptions|boolean} [options]
 * @returns {() => void}
 */
export function on(target, type, handler, options) {
  if (!target || typeof target.addEventListener !== 'function') return () => {};
  target.addEventListener(type, handler, options);
  return () => target.removeEventListener(type, handler, options);
}

/**
 * 事件委托：只在 root 上挂一个监听，命中 selector 才回调。
 * @param {EventTarget} root
 * @param {string} type
 * @param {string} selector
 * @param {(event: Event, matched: Element) => void} handler
 * @returns {() => void}
 */
export function delegate(root, type, selector, handler) {
  return on(root, type, (event) => {
    const target = event.target;
    if (!(target instanceof Element)) return;
    const matched = target.closest(selector);
    if (!matched || !root.contains(matched)) return;
    handler(event, matched);
  });
}

/**
 * 注册全局 data-action 委托。页面只写 `data-action="xxx"`，不绑定零散 listener。
 *
 * @param {EventTarget} root 通常是 document
 * @param {Record<string, (event: Event, el: Element) => void>} handlers action 名 → 处理函数
 * @param {string} [type] 事件类型，缺省 click
 * @returns {() => void} 解绑函数
 *
 * @example
 *   bindActions(document, { reload: () => loadSlot('grades', true) })
 */
export function bindActions(root, handlers, type = 'click') {
  return delegate(root, type, '[data-action]', (event, el) => {
    const action = el.getAttribute('data-action');
    if (!action) return;
    // data-action="select:C001" 形式：冒号前是动作名，冒号后是参数
    const sep = action.indexOf(':');
    const name = sep === -1 ? action : action.slice(0, sep);
    const payload = sep === -1 ? '' : action.slice(sep + 1);
    const fn = handlers[name];
    if (typeof fn !== 'function') return;
    fn(event, el, payload);
  });
}

/**
 * 仅在文本真的变化时写 DOM（避免无谓的样式重算与闪烁）。
 * @param {Element|null} el
 * @param {unknown} text
 */
export function setText(el, text) {
  if (!el) return;
  const next = text === null || text === undefined ? '' : String(text);
  if (el.textContent !== next) el.textContent = next;
}

/**
 * 清空子节点。
 * @param {Element|null} el
 */
export function clear(el) {
  if (!el) return;
  while (el.firstChild) el.removeChild(el.firstChild);
}

/**
 * HTML 转义：只有确实要拼 innerHTML 时才用。
 * @param {unknown} value
 * @returns {string}
 */
export function escapeHtml(value) {
  return String(value === null || value === undefined ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * 切换 class（true 加、false 去，值没变就不碰 DOM）。
 * @param {Element|null} el
 * @param {string} name
 * @param {boolean} force
 */
export function toggleClass(el, name, force) {
  if (!el) return;
  if (el.classList.contains(name) === force) return;
  el.classList.toggle(name, force);
}
