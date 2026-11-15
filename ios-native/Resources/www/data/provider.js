/**
 * provider.js —— 数据源契约与降级包装（对应 docs/ARCHITECTURE.md §4.2）
 *
 * 这一层是「数据 → 界面」的唯一通道。页面只认 ProviderResult，不关心数据来自演示集还是教务系统。
 *
 * 【契约以 §4.2 为准，与 types.js 的差异说明】
 *   types.js 里的 `ProviderResult` 是 UI 层的简化视图（Either 风格：`{ok:true,data}` 或
 *   `{ok:false,error}`）。provider 层按架构原文多带一个 `meta`，并把 `error` 补成
 *   `{code,message,retryable,hint?,detail?}`。两者字段兼容：UI 只读 `ok / data / error.code`，
 *   `meta` 与 `retryable` 是附加信息，不破坏任何调用方。
 *
 * 【五条硬性约定】
 *   1. 所有异步方法**永不 throw**，一律返回 ProviderResult（throw 视为实现缺陷）
 *   2. demoProvider 每次调用带 120–320ms 随机延迟，否则骨架屏与转场验收不到
 *   3. withFallback 降级后 `meta.degraded = true`、`meta.source = 'demo'`，页面无感
 *   4. `getSession()` 是唯一同步方法（§4.2 原文 `getSession(): Session | null`），无延迟
 *   5. **每个 async 方法对应教务系统的一个功能模块**。用户点进某个页面 →
 *      `loadSlot(name)` → remote getter → 抓该模块的教务页面 → 解析 → 映射成 App 模型。
 *      App 不缓存"编出来的"业务结论； Demo 只是抓取失败时的降级副本，不是默认数据源。
 *
 * 本文件零 DOM 依赖、零外链、不含颜色。
 */

import * as storage from '../core/storage.js';

/** 错误码 → 可直接上屏的中文文案。 */
export const ERROR_TEXT = Object.freeze({
  NETWORK: '网络不可用，请检查网络后重试',
  CORS: '浏览器跨域拦截，请改用安卓客户端或稍后重试',
  AUTH: '学号或密码不正确，请重新登录',
  CAPTCHA: '教务系统要求输入验证码，暂不支持自动登录',
  TIMEOUT: '请求超时，请稍后重试',
  PARSE: '教务系统页面结构变化，数据解析失败',
  UNAVAILABLE: '教务系统暂不可用，请稍后重试',
  NOT_IMPLEMENTED: '该能力在当前版本暂未实现',
  UNKNOWN: '出现未知错误，请稍后重试',
});

/** 可重试的错误码（其余重试也没用，UI 不该给「重试」按钮）。 */
const RETRYABLE = new Set(['NETWORK', 'TIMEOUT', 'UNAVAILABLE']);

/** 错误码 → 给 UI 的引导建议。 */
const HINT = Object.freeze({
  NETWORK: 'use-demo',
  CORS: 'use-demo',
  AUTH: 'check-credential',
  CAPTCHA: 'later',
  TIMEOUT: 'later',
  UNAVAILABLE: 'campus-network',
  NOT_IMPLEMENTED: 'later',
  PARSE: 'later',
  UNKNOWN: 'later',
});

/** 异步方法清单（withFallback 逐个包装）。`getSession` 不在其中 —— 它是同步只读。 */
export const ASYNC_METHODS = Object.freeze([
  'probe', 'login', 'logout',
  'getTermContext', 'getSemesters', 'getTodayCourses', 'getTimetable',
  'getGrades', 'getGradeById', 'getGPA',
  'getCourseSelection', 'toggleCourse', 'getRecommendedTimetable', 'getProfile', 'clearCache',
]);

/** 完整方法清单（含同步的 getSession），共 17 个。 */
export const PROVIDER_METHODS = Object.freeze(['getSession', ...ASYNC_METHODS]);

/**
 * 造一个归一化错误对象。
 * @param {string} code 错误码（不在表内则回落 UNKNOWN）
 * @param {{message?: string, detail?: string, retryable?: boolean, hint?: string}} [overrides]
 * @returns {{code: string, message: string, retryable: boolean, hint?: string, detail?: string}}
 */
export function makeError(code, overrides = {}) {
  const key = ERROR_TEXT[code] ? code : 'UNKNOWN';
  const error = {
    code: key,
    message: ERROR_TEXT[key],
    retryable: RETRYABLE.has(key),
    hint: HINT[key],
  };
  if (overrides.message) error.message = overrides.message;
  if (typeof overrides.retryable === 'boolean') error.retryable = overrides.retryable;
  if (overrides.hint) error.hint = overrides.hint;
  if (overrides.detail) error.detail = String(overrides.detail);
  return error;
}

/**
 * 造 meta。
 * @param {import('./types.js').DataSourceMode|string} source
 * @param {Partial<{fromCache: boolean, degraded: boolean, fetchedAt: string}>} [extra]
 * @returns {{source: string, fetchedAt: string, fromCache: boolean, degraded?: boolean}}
 */
export function makeMeta(source, extra = {}) {
  const meta = {
    source: source === 'remote' ? 'remote' : 'demo',
    fetchedAt: extra.fetchedAt || new Date().toISOString(),
    fromCache: Boolean(extra.fromCache),
  };
  if (extra.degraded) meta.degraded = true;
  return meta;
}

/**
 * 成功结果。
 * @template T
 * @param {T} data
 * @param {{source: string, fromCache?: boolean, degraded?: boolean, fetchedAt?: string}} meta
 * @returns {{ok: true, data: T, meta: object}}
 */
export function ok(data, meta) {
  return { ok: true, data, meta: makeMeta(meta.source, meta) };
}

/**
 * 失败结果（`data` 恒为 null，调用方只需判 `ok`）。
 * @param {object} error makeError() 的返回值
 * @param {{source: string, fromCache?: boolean}} meta
 * @returns {{ok: false, data: null, error: object, meta: object}}
 */
export function fail(error, meta) {
  return { ok: false, data: null, error: error || makeError('UNKNOWN'), meta: makeMeta(meta.source, meta) };
}

/**
 * 占位 provider：remote 侧尚未接入（T05b）时用它兜底，保证调用永不 throw。
 * @param {string} [reason]
 * @returns {object}
 */
export function createUnavailableProvider(reason = 'NOT_IMPLEMENTED') {
  const source = 'remote';
  const provider = { mode: source };
  for (const name of ASYNC_METHODS) {
    provider[name] = async () => fail(makeError(reason), { source });
  }
  provider.getSession = () => null;
  return provider;
}

/**
 * 按模式取 provider。
 * @param {import('./types.js').DataSourceMode} mode
 * @param {{demo?: object, remote?: object}} [registry]
 * @returns {object}
 */
export function createProvider(mode, registry = {}) {
  if (mode === 'remote') return registry.remote || createUnavailableProvider();
  return registry.demo || createUnavailableProvider('UNKNOWN');
}

/**
 * 把降级事实写进 store（`source.effective` + `lastFallbackAt` + `lastError`）。
 * store 缺失（Node 测试 / 早期启动）时静默跳过，绝不因此抛错。
 * @param {object|null} store
 * @param {object} error
 */
function recordFallback(store, error) {
  if (!store || typeof store.patch !== 'function' || typeof store.getState !== 'function') return;
  try {
    const prev = store.getState() || {};
    store.patch({
      source: {
        ...(prev.source || {}),
        effective: 'demo',
        lastFallbackAt: Date.now(),
        lastError: error || null,
      },
    });
  } catch (err) {
    console.error('[zsts] 降级状态写入 store 失败', err);
  }
}

/**
 * 绝不允许降级到 demo 的方法。这是 2026-11-06「随便输密码都能登录」事故的根因：
 * login 曾被 withFallback 包住 —— remote 拒绝错误密码后，demoProvider.login
 * （本地假登录，接受任何凭据）会静默接盘返回 ok，App 于是弹「登录成功」并
 * 展示演示数据。**认证必须失败得明明白白**：login / logout / probe 直接透传 primary。
 * @type {Set<string>}
 */
const AUTH_METHODS = new Set(['login', 'logout', 'probe']);

/**
 * 降级包装：primary 任一调用失败 → 记 lastError → 取 fallback 数据 → 标 degraded。
 * **页面永不感知降级**，只有 appBar 多一条提示。
 * （认证方法见 AUTH_METHODS：永不降级。）
 *
 * @param {object} primary 通常是 remoteProvider
 * @param {object} fallback 通常是 demoProvider
 * @param {object|null} [store] 传入则写入 source.effective / lastFallbackAt / lastError
 * @returns {object} 同形状的 provider
 */
export function withFallback(primary, fallback, store = null) {
  /** @type {Record<string, Function>} */
  // mode 表示「主数据源是谁」，不是「本次调用走了谁」。
  // app.js 靠它决定用 DEMO_TODAY 还是系统日期：教务为主时日历必须跟真实时间走。
  // 单次调用是否降级看 result.meta.degraded / store.source.effective。
  const wrapped = { mode: primary && primary.mode ? primary.mode : 'demo', fallbackMode: fallback && fallback.mode };

  for (const name of ASYNC_METHODS) {
    if (AUTH_METHODS.has(name)) {
      wrapped[name] = async (...args) => {
        try {
          if (primary && typeof primary[name] === 'function') return await primary[name](...args);
        } catch (err) {
          return fail(makeError('UNKNOWN', { detail: err && err.message }), { source: 'remote' });
        }
        return fail(makeError('NOT_IMPLEMENTED'), { source: 'remote' });
      };
      continue;
    }
    wrapped[name] = async (...args) => {
      let result = null;
      try {
        if (primary && typeof primary[name] === 'function') result = await primary[name](...args);
      } catch (err) {
        result = fail(makeError('UNKNOWN', { detail: err && err.message }), { source: 'remote' });
      }
      if (result && result.ok) return result;

      const error = (result && result.error) || makeError('UNKNOWN');
      recordFallback(store, error);

      let fb = null;
      try {
        fb = fallback && typeof fallback[name] === 'function'
          ? await fallback[name](...args)
          : fail(makeError('UNKNOWN'), { source: 'demo' });
      } catch (err) {
        fb = fail(makeError('UNKNOWN', { detail: err && err.message }), { source: 'demo' });
      }
      // 兜底也失败：原样返回兜底的错误（不再套 degraded，避免「降级到错误」的怪状态）
      if (!fb || !fb.ok) return fb || fail(error, { source: 'demo' });
      return {
        ...fb,
        meta: { ...fb.meta, source: 'demo', degraded: true, degradedFrom: primary && primary.mode ? primary.mode : 'remote' },
      };
    };
  }

  // 同步只读：优先有会话的一方
  wrapped.getSession = () => {
    try {
      const fromPrimary = primary && typeof primary.getSession === 'function' ? primary.getSession() : null;
      if (fromPrimary) return fromPrimary;
      return fallback && typeof fallback.getSession === 'function' ? fallback.getSession() : null;
    } catch {
      return null;
    }
  };

  return wrapped;
}

/**
 * 解析首次启动的数据源（§4.2 的三步顺序）。
 *
 *   ① storage `zsts.source` 有值 → 直接用，不做探测
 *   ② `navigator.onLine === false` → demo
 *   ③ `remoteProvider.probe()` 3 秒超时：可达 → remote，否则 demo
 *
 * @param {object|null} [remoteProvider] remote 侧实现；T05b 接入前传 null，直接返回 demo
 * @param {{timeoutMs?: number}} [options]
 * @returns {Promise<'demo'|'remote'>}
 */
export async function resolveInitialSource(remoteProvider = null, options = {}) {
  const timeoutMs = Number(options.timeoutMs) || 3000;
  try {
    const saved = storage.get('source');
    if (saved === 'demo' || saved === 'remote') return saved;
  } catch {
    /* storage 不可用按未选择处理 */
  }

  if (typeof navigator !== 'undefined' && navigator.onLine === false) return 'demo';
  if (!remoteProvider || typeof remoteProvider.probe !== 'function') return 'demo';

  let timer = 0;
  try {
    const timeout = new Promise((resolve) => {
      timer = setTimeout(() => resolve(null), timeoutMs);
    });
    const probe = Promise.resolve(remoteProvider.probe()).catch(() => null);
    const result = await Promise.race([probe, timeout]);
    if (!result || !result.ok || !result.data) return 'demo';
    return result.data.reachable ? 'remote' : 'demo';
  } catch {
    return 'demo';
  } finally {
    if (timer) clearTimeout(timer);
  }
}
