/**
 * router.js —— hash 路由（ARCHITECTURE §4.4）
 *
 * 职责：路由表编译（只支持 `:param`）、按路由 `import()` 懒加载、转场调度、
 * 滚动位置恢复、硬件返回语义 `window.__appBack()`。
 *
 * 页面模块契约：{ name, title, tab, requiresAuth, render(ctx): HTMLElement, mount(el, ctx): cleanupFn, unmount() }
 * `render` 必须同步返回骨架（首屏不白屏），数据在 `mount` 里异步填充。
 *
 * 本文件不 import pages/components/data —— 页面通过路由表的 `load()` 注入。
 */

import { pageSwap } from './animate.js';

/**
 * 把 `'/grades/:id'` 编译成正则 + 参数名表。
 * @param {string} pattern
 * @returns {{keys: string[], regex: RegExp}}
 */
function compilePattern(pattern) {
  const keys = [];
  const source = pattern.replace(/\/:([A-Za-z0-9_]+)/g, (_, key) => {
    keys.push(key);
    return '/([^/]+)';
  });
  return { keys, regex: new RegExp(`^${source}/?$`) };
}

/**
 * 解析查询串。
 * @param {string} search 形如 'a=1&b=2'（不含 '?'）
 * @returns {Record<string, string>}
 */
function parseQuery(search) {
  const query = {};
  if (!search) return query;
  for (const pair of search.split('&')) {
    if (!pair) continue;
    const index = pair.indexOf('=');
    const key = index === -1 ? pair : pair.slice(0, index);
    const rawValue = index === -1 ? '' : pair.slice(index + 1);
    try {
      query[decodeURIComponent(key)] = decodeURIComponent(rawValue);
    } catch {
      query[key] = rawValue;
    }
  }
  return query;
}

/** 安全 decode 一个路径段（畸形编码原样返回，别让一个 % 炸掉整个路由）。 */
function safeDecode(segment) {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

/**
 * 创建路由器。
 *
 * @param {{
 *   routes: Array<{path: string, tab?: string, transition?: 'fade'|'push', title?: string, load: () => Promise<any>}>,
 *   container: HTMLElement,
 *   defaultPath?: string,
 *   guard?: ((match: object) => boolean) | null,
 *   onNavigate?: ((match: object) => void) | null,
 *   context?: (() => object) | null,
 *   onError?: ((info: {path: string, error: Error}) => Node|null) | null
 * }} options
 *   - `context`：组合根往页面 ctx 里注入 store / actions / provider，router 自己不认识它们
 *   - `onError`：页面加载或 render 抛错时由组合根产出兜底 UI（ErrorBoundary），
 *     返回 null 则退回本文件的极简文本兜底 —— 无论哪种都**不允许白屏**
 */
export function createRouter({
  routes,
  container,
  defaultPath = '/home',
  guard = null,
  onNavigate = null,
  context = null,
  onError = null,
}) {
  const compiled = routes.map((route) => ({ route, ...compilePattern(route.path) }));
  /** @type {Map<string, number>} routeKey → scrollTop */
  const scrollPositions = new Map();
  /** @type {null|{route: object, path: string, key: string, params: object, query: object, el: HTMLElement, page: object, cleanup: Function|null}} */
  let current = null;
  /** 渲染令牌：并发导航时只有最后一次生效 */
  let renderToken = 0;
  /** 弹层拦截器（由 app.js 注入：有弹层时返回 true 表示已消费返回事件） */
  let backInterceptor = null;
  let started = false;

  /**
   * 解析 hash → 匹配结果。
   * @param {string} [hash] 缺省读 location.hash
   * @returns {{route: object, path: string, params: object, query: object, key: string}|null}
   */
  function resolve(hash = window.location.hash) {
    const raw = String(hash || '').replace(/^#/, '') || defaultPath;
    const mark = raw.indexOf('?');
    const path = mark === -1 ? raw : raw.slice(0, mark);
    const query = parseQuery(mark === -1 ? '' : raw.slice(mark + 1));
    const normalizedPath = path === '' || path === '/' ? defaultPath : path;

    for (const entry of compiled) {
      const matched = entry.regex.exec(normalizedPath);
      if (!matched) continue;
      const params = {};
      entry.keys.forEach((key, index) => {
        params[key] = safeDecode(matched[index + 1]);
      });
      return {
        route: entry.route,
        path: normalizedPath,
        params,
        query,
        key: `${entry.route.path}|${JSON.stringify(params)}`,
      };
    }
    return null;
  }

  /** 保存当前页滚动位置。 */
  function saveScroll() {
    if (!current) return;
    scrollPositions.set(current.key, container.scrollTop);
  }

  /** 恢复滚动位置：两帧之后写，等新页完成首次布局，否则会被浏览器夹到 0。 */
  function restoreScroll(key) {
    const saved = scrollPositions.get(key);
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        container.scrollTop = saved === undefined ? 0 : saved;
      });
    });
  }

  /** 拆掉当前页（destroy 用）。 */
  function teardownCurrent() {
    const dying = current;
    current = null;
    teardownCurrentPage(dying);
  }

  /**
   * 调一次组合根注入的 context() 拿 store / actions / provider。
   * 抛错不能让整页挂掉：返回空对象，页面自己会因为缺 ctx 走兜底。
   * @returns {object}
   */
  function injectedContext() {
    if (typeof context !== 'function') return {};
    try {
      return context() || {};
    } catch (err) {
      console.error('[zsts] router context() 抛错', err);
      return {};
    }
  }

  /**
   * 错误页兜底：优先交给组合根注入的 onError 产出富 UI（重试 + 可折叠错误摘要）。
   * @param {string} path
   * @param {Error} err
   */
  function renderFailure(path, err) {
    console.error('[zsts] 页面异常', path, err);
    let node = null;
    if (typeof onError === 'function') {
      try {
        node = onError({ path, error: err });
      } catch (nested) {
        console.error('[zsts] onError 自身抛错，退回极简兜底', nested);
      }
    }
    if (!node) {
      // 组合根没提供兜底 UI 时的极简版：一行标题 + 一行原因，够定位、绝不留白
      node = document.createElement('div');
      node.className = 'page';
      node.innerHTML = '<p class="t-h2">页面加载失败</p><p class="t-caption"></p>';
      node.lastElementChild.textContent = `${path} · ${(err && err.message) || '未知错误'}`;
    }
    container.replaceChildren(node);
    current = null;
  }

  /** 执行一次导航渲染。 */
  async function render() {
    const match = resolve();
    if (!match) {
      if (window.location.hash !== `#${defaultPath}`) replaceHash(defaultPath);
      return;
    }
    if (typeof guard === 'function' && guard(match) === false) return;

    const token = ++renderToken;
    saveScroll();

    let module;
    try {
      module = await match.route.load();
    } catch (err) {
      if (token !== renderToken) return;
      renderFailure(match.path, err);
      return;
    }
    if (token !== renderToken) return; // 期间已经切走，丢弃这次渲染

    const page = (module && module.default) || module;
    if (!page || typeof page.render !== 'function') {
      renderFailure(match.path, new Error('页面模块缺少 render()'));
      return;
    }

    const prevEl = current ? current.el : null;
    const ctx = {
      path: match.path,
      params: match.params,
      query: match.query,
      route: match.route,
      prevPath: current ? current.path : null,
      router: api,
      // 组合根注入的 store / actions / provider；router 自己不认识它们，只负责往下传
      ...injectedContext(),
    };

    let el;
    try {
      el = page.render(ctx); // 必须同步返回骨架
    } catch (err) {
      renderFailure(match.path, err);
      return;
    }
    if (!el) {
      renderFailure(match.path, new Error('render() 未返回节点'));
      return;
    }

    el.classList.add('c-page');
    container.appendChild(el);
    // 先同步落一次滚动位置：新页刚进文档即已布局，此时写入可避免「先看到页中段再跳回」的两帧闪烁
    const savedTop = scrollPositions.get(match.key);
    container.scrollTop = savedTop === undefined ? 0 : savedTop;

    const previous = current;
    current = { ...match, el, page, cleanup: null };

    // 旧页先停订阅，避免转场这 250ms 内鼠标/触控事件误打到已失效的页面逻辑；
    // DOM 留到 pageSwap 结束后再摘（转场需要新旧同时存在）。
    teardownCurrentPage(previous);

    // 新页骨架已在 render 里同步给出，mount 里只做异步数据填充
    if (typeof page.mount === 'function') {
      try {
        current.cleanup = page.mount(el, ctx) || null;
      } catch (err) {
        // mount 抛错同样不能白屏：整页换成兜底 UI（页面自己的 render 已经给过骨架，但它不可信了）
        console.error('[zsts] mount 抛错', err);
        renderFailure(match.path, err);
        return;
      }
    }

    await pageSwap(prevEl, el, match.route.transition || 'fade');
    if (token !== renderToken) return; // 转场期间又切走了，滚动交给后一次导航
    restoreScroll(match.key);

    if (typeof onNavigate === 'function') {
      try {
        onNavigate({ ...match, page, el });
      } catch (err) {
        console.error('[zsts] onNavigate 抛错', err);
      }
    }
    if (page.title) document.title = `${page.title} · 掌上唐师`;
  }

  /**
   * 拆上一个页面（current 已指向新页，这里只负责旧页的清理与摘除）。
   * @param {object|null} previous
   */
  function teardownCurrentPage(previous) {
    if (!previous) return;
    if (typeof previous.cleanup === 'function') {
      try {
        previous.cleanup();
      } catch (err) {
        console.error('[zsts] 页面 cleanup 抛错', err);
      }
    }
    if (previous.page && typeof previous.page.unmount === 'function') {
      try {
        previous.page.unmount();
      } catch (err) {
        console.error('[zsts] 页面 unmount 抛错', err);
      }
    }
  }

  /**
   * 用 replaceState 改 hash（不产生历史记录、不触发 hashchange），然后手动渲染。
   * @param {string} path
   */
  function replaceHash(path) {
    const next = `#${path}`;
    if (window.location.hash === next) {
      render();
      return;
    }
    window.history.replaceState(null, '', next);
    render();
  }

  /** 跳转：写 hash → 触发 hashchange → 渲染。path 形如 '/grades/2026-1-g01' */
  function go(path) {
    const next = `#${path}`;
    if (window.location.hash === next) return;
    window.location.hash = next;
  }

  /** 后退。 */
  function back() {
    if (window.history.length > 1) window.history.back();
    else go(defaultPath);
  }

  /** 硬件返回语义：true = App 已自行处理；false = 交给宿主（Android 退出 / 浏览器后退）。 */
  function handleBack() {
    if (typeof backInterceptor === 'function') {
      try {
        if (backInterceptor() === true) return true;
      } catch (err) {
        console.error('[zsts] backInterceptor 抛错', err);
      }
    }
    if (!current) return false;
    // Tab 根页：非首页先回首页，首页则交还宿主退出
    if (current.route.tab) {
      if (current.path !== defaultPath) {
        go(defaultPath);
        return true;
      }
      return false;
    }
    // 次级页：正常后退
    if (window.history.length > 1) {
      window.history.back();
      return true;
    }
    go(defaultPath);
    return true;
  }

  /** 注册弹层拦截器（关弹层优先于路由后退）。 */
  function setBackInterceptor(fn) {
    backInterceptor = typeof fn === 'function' ? fn : null;
  }

  /** 启动：挂 hashchange + 渲染当前地址。 */
  function start() {
    if (started) return;
    started = true;
    window.addEventListener('hashchange', render);
    window.__appBack = handleBack;
    if (!window.location.hash) {
      window.history.replaceState(null, '', `#${defaultPath}`);
    }
    render();
  }

  /** 停止：解绑并清空，主要给临时验收页用。 */
  function destroy() {
    window.removeEventListener('hashchange', render);
    if (window.__appBack === handleBack) delete window.__appBack;
    teardownCurrent();
    started = false;
  }

  const api = {
    start,
    go,
    back,
    replace: replaceHash,
    resolve,
    render,
    setBackInterceptor,
    destroy,
    current: () => current,
    isRootTab: () => Boolean(current && current.route.tab && current.path === defaultPath),
    scrollPositions,
  };

  return api;
}
