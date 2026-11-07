/**
 * transport.js —— 双通道 HTTP（web: fetch / android: 原生桥）
 *
 * 为什么要抽象：同一份数据层代码要同时跑在浏览器和 Android WebView 壳里。
 * 浏览器有 fetch；App 内的 WebView 跨域受限、且需要走系统的证书/明文策略，
 * 所以由 `@JavascriptInterface` 暴露的 `NativeBridge.httpRequest()` 代劳。
 *
 * 铁律：**任何失败都归一成 `{ok:false, error:{code,message,detail}}`，本模块永不 throw。**
 * 上层（provider）只判 `ok`，不需要 try/catch，也就不会因为一次网络抖动白屏。
 *
 * 桥协议（与 T14 的 MainActivity 约定）：
 *   JS → Native：NativeBridge.httpRequest(reqId, url, method, headersJson, body)
 *   Native → JS：window.__nativeHttpCallback({ reqId, status, headers, body }) /({reqId, error})
 *   reqId 由 JS 生成，Native 原样回传，用于把回调配对到对应的 Promise。
 *
 * 本文件不 import 任何模块 —— 保持零依赖，方便将来单独抽出去复用。
 */

/**
 * 默认超时 20s。原为 15s（PRD F-16），但 2026-11-07 实测校外移动数据下教务单次
 * 请求（含登录页）要 15-16 s：15 s 恰好卡在边界上，会把「慢」误判成「不通」。
 * 20 s 留出余量，仍远小于用户失去耐心的阈值。
 */
export const DEFAULT_TIMEOUT = 20000;

/** 错误码（与 data/types.js 的 ErrorCode 一致） */
export const ERR = Object.freeze({
  NETWORK: 'NETWORK',
  TIMEOUT: 'TIMEOUT',
  ABORT: 'ABORT',
  HTTP: 'HTTP',
  UNKNOWN: 'UNKNOWN',
});

/**
 * 构造一个归一化错误。
 * @param {string} code
 * @param {string} message
 * @param {string} [detail]
 * @param {number} [status]
 * @returns {{ok: false, error: {code: string, message: string, detail?: string, status?: number}}}
 */
function fail(code, message, detail, status) {
  const error = { code, message };
  if (detail) error.detail = detail;
  if (typeof status === 'number') error.status = status;
  return { ok: false, error };
}

/**
 * 判定运行环境。
 * @returns {'web'|'android'}
 */
export function detectEnv() {
  try {
    if (typeof window === 'undefined') return 'web';
    // 允许验收页强制指定，便于在浏览器里模拟 App 通道
    if (window.__zstsForceEnv === 'android' || window.__zstsForceEnv === 'web') {
      return window.__zstsForceEnv;
    }
    const bridge = window.NativeBridge;
    if (bridge && typeof bridge.httpRequest === 'function') return 'android';
  } catch {
    /* 忽略 */
  }
  return 'web';
}

/**
 * 取原生桥（不存在返回 null）。
 * @returns {object|null}
 */
function getBridge() {
  try {
    const bridge = typeof window !== 'undefined' ? window.NativeBridge : null;
    return bridge && typeof bridge.httpRequest === 'function' ? bridge : null;
  } catch {
    return null;
  }
}

/** 原生通道：reqId → {resolve, timer} */
const pendingNative = new Map();
/** 原生通道自增序号 */
let nativeSeq = 0;
/** 原生回调是否已安装 */
let callbackInstalled = false;

/**
 * 安装原生回调（幂等）。回调里只做配对，不做业务判断。
 */
function ensureNativeCallback() {
  if (callbackInstalled) return;
  if (typeof window === 'undefined') return;
  window.__nativeHttpCallback = (payload) => {
    let data = payload;
    if (typeof data === 'string') {
      try {
        data = JSON.parse(data);
      } catch {
        return; // 无法配对的回调直接丢弃，绝不抛错
      }
    }
    if (!data || !data.reqId) return;
    const entry = pendingNative.get(data.reqId);
    if (!entry) return; // 已经超时/取消过了
    pendingNative.delete(data.reqId);
    clearTimeout(entry.timer);

    if (data.error) {
      entry.resolve(fail(ERR.NETWORK, '网络请求失败，请检查网络后重试', String(data.error)));
      return;
    }
    const status = Number(data.status) || 0;
    const body = data.body === null || data.body === undefined ? '' : String(data.body);
    if (status >= 200 && status < 300) {
      entry.resolve({
        ok: true,
        data: {
          status,
          headers: normalizeHeaders(data.headers),
          body,
          url: String(data.url || entry.url || ''),
          elapsedMs: Date.now() - entry.startedAt,
        },
      });
      return;
    }
    entry.resolve(
      fail(ERR.HTTP, `教务系统返回异常状态（${status || '无响应'}）`, body.slice(0, 200), status)
    );
  };
  callbackInstalled = true;
}

/**
 * 把各种响应头形态归一到「全小写键的对象」。
 * @param {unknown} headers
 * @returns {Record<string, string>}
 */
function normalizeHeaders(headers) {
  const out = {};
  if (!headers) return out;
  try {
    if (typeof headers.forEach === 'function') {
      // Headers 实例
      headers.forEach((value, key) => {
        out[String(key).toLowerCase()] = String(value);
      });
      return out;
    }
    for (const [key, value] of Object.entries(headers)) {
      out[String(key).toLowerCase()] = String(value);
    }
  } catch {
    /* 忽略 */
  }
  return out;
}

/**
 * Android 通道：把一次请求交给原生桥，用 reqId 与回调配对。
 * @param {{method: string, url: string, headers: Record<string,string>, body: (string|null), timeout: number}} options
 * @returns {Promise<object>} ProviderResult<HttpResponse>
 */
function nativeRequest(options) {
  const bridge = getBridge();
  if (!bridge) {
    // 本批只写骨架：拿不到桥就温和失败，不抛异常
    return Promise.resolve(
      fail(ERR.NETWORK, '当前环境不支持直接访问教务系统', 'NativeBridge.httpRequest 不存在')
    );
  }
  ensureNativeCallback();

  const reqId = `zsts-${Date.now()}-${(nativeSeq += 1)}`;
  const startedAt = Date.now();
  const timeout = options.timeout > 0 ? options.timeout : DEFAULT_TIMEOUT;

  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      pendingNative.delete(reqId);
      resolve(fail(ERR.TIMEOUT, '请求超时，请稍后重试', `超过 ${timeout}ms 未响应`));
    }, timeout);

    pendingNative.set(reqId, { resolve, timer, startedAt, url: options.url });

    try {
      bridge.httpRequest(
        reqId,
        String(options.url),
        String(options.method || 'GET'),
        JSON.stringify(options.headers || {}),
        options.body === null || options.body === undefined ? '' : String(options.body)
      );
    } catch (err) {
      clearTimeout(timer);
      pendingNative.delete(reqId);
      resolve(fail(ERR.NETWORK, '网络请求失败，请检查网络后重试', String(err && err.message)));
    }
  });
}

/**
 * Web 通道：fetch + AbortController。
 * @param {{method: string, url: string, headers: Record<string,string>, body: (string|null), timeout: number, signal?: AbortSignal, credentials?: string}} options
 * @returns {Promise<object>} ProviderResult<HttpResponse>
 */
async function webRequest(options) {
  if (typeof fetch !== 'function') {
    return fail(ERR.NETWORK, '当前浏览器不支持网络请求', 'fetch 不存在');
  }
  const startedAt = Date.now();
  const controller = new AbortController();
  const timeout = options.timeout > 0 ? options.timeout : DEFAULT_TIMEOUT;
  let timedOut = false;

  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeout);

  const external = options.signal;
  const onExternalAbort = () => controller.abort();
  if (external) {
    if (external.aborted) controller.abort();
    else external.addEventListener('abort', onExternalAbort, { once: true });
  }

  try {
    const response = await fetch(options.url, {
      method: options.method,
      headers: options.headers,
      body: options.body === null ? undefined : options.body,
      signal: controller.signal,
      credentials: options.credentials || 'same-origin',
      cache: 'no-store',
      redirect: 'follow',
    });
    const body = await response.text();
    const result = {
      status: response.status,
      headers: normalizeHeaders(response.headers),
      body,
      url: response.url || options.url,
      elapsedMs: Date.now() - startedAt,
    };
    if (!response.ok) {
      return fail(
        ERR.HTTP,
        `教务系统返回异常状态（${response.status}）`,
        body.slice(0, 200),
        response.status
      );
    }
    return { ok: true, data: result };
  } catch (err) {
    const detail = String((err && err.message) || err);
    if (timedOut) return fail(ERR.TIMEOUT, '请求超时，请稍后重试', `超过 ${timeout}ms 未响应`);
    if (err && (err.name === 'AbortError' || /abort/i.test(detail))) {
      return fail(ERR.ABORT, '请求已取消', detail);
    }
    return fail(ERR.NETWORK, '网络不可用，请检查网络后重试', detail);
  } finally {
    clearTimeout(timer);
    if (external) external.removeEventListener('abort', onExternalAbort);
  }
}

/**
 * 发一次 HTTP 请求（自动选择通道）。
 *
 * @param {{
 *   method?: string,
 *   url: string,
 *   headers?: Record<string, string>,
 *   body?: (string|null|undefined),
 *   timeout?: number,
 *   signal?: AbortSignal,
 *   credentials?: string
 * }} options
 * @returns {Promise<object>} ProviderResult<HttpResponse>，永不 reject
 */
export async function httpRequest(options = {}) {
  const url = options.url ? String(options.url) : '';
  if (!url) {
    return fail(ERR.NETWORK, '请求地址为空', 'httpRequest 缺少 url');
  }
  const normalized = {
    method: String(options.method || 'GET').toUpperCase(),
    url,
    headers: normalizeHeaders(options.headers),
    body: options.body === undefined ? null : options.body,
    timeout: Number(options.timeout) > 0 ? Number(options.timeout) : DEFAULT_TIMEOUT,
    signal: options.signal,
    credentials: options.credentials,
  };

  try {
    if (detectEnv() === 'android') return await nativeRequest(normalized);
    return await webRequest(normalized);
  } catch (err) {
    // 兜底：任何漏网的异常都归一化，绝不让 reject 冒到上层的 await
    return fail(ERR.UNKNOWN, '请求失败，请稍后重试', String((err && err.message) || err));
  }
}

/**
 * 便捷方法：GET 并尝试 JSON.parse。
 * @param {string} url
 * @param {object} [options]
 * @returns {Promise<object>} ProviderResult<unknown>
 */
export async function httpGetJson(url, options = {}) {
  const result = await httpRequest({ ...options, url, method: 'GET' });
  if (!result.ok) return result;
  try {
    return { ok: true, data: JSON.parse(result.data.body) };
  } catch (err) {
    return fail(ERR.UNKNOWN, '响应不是合法的 JSON', String((err && err.message) || err));
  }
}

/**
 * 取消所有挂起的原生请求（页面卸载 / 退出登录时调用）。
 */
export function abortAllPending() {
  for (const [reqId, entry] of pendingNative) {
    clearTimeout(entry.timer);
    entry.resolve(fail(ERR.ABORT, '请求已取消', 'abortAllPending'));
    pendingNative.delete(reqId);
  }
}
