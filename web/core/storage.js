/**
 * storage.js —— localStorage 封装（命名空间 + TTL + JSON 容错 + 体积统计）
 *
 * 为什么不让上层直接碰 localStorage：
 *   1. 隐私模式 / WebView 早期版本里 localStorage 可能抛错或不可写，必须能降级
 *   2. 手写 JSON.parse 到处 try/catch 极易漏，坏数据会让整页白屏
 *   3. TTL 逻辑分散在各处必然写歪，成绩缓存的「上次同步」依赖它
 *   4. **凭据禁止落盘**：只允许记住学号（zsts.rememberId）与主题（zsts.theme），
 *      密码 / token / 会话一律拦在入口
 *
 * 存储格式：`zsts.<key>` → `{"v":<值>,"t":<写入时间戳>,"x":<过期时间戳|0>}`
 *
 * 本文件不 import 任何模块（core 层最底层之一），也不依赖 DOM API 之外的东西。
 */

/** 命名空间前缀：所有键都以它开头，便于整体清理与体积统计 */
export const NS = 'zsts.';

/** 允许持久化的凭据类键名（白名单）。其余含凭据语义的键一律拒绝写入。 */
export const CREDENTIAL_ALLOWLIST = Object.freeze(['rememberId']);

/**
 * **学生自己产出的数据**（键名前缀）。
 *
 * 「清除缓存」「退出登录」都走 `clearAll`，但本地课表不是缓存 —— 它装着老师调课后
 * 学生手改的结果、以及教务压根没同步的课，删掉就再也找不回来。所以这两个入口默认
 * 对它网开一面；真要清（重装式重置）只有 `clearEverything()` 这一条路。
 */
export const USER_DATA_PREFIXES = Object.freeze(['timetable.']);

/** 键名里出现这些片段即视为凭据，直接拒绝写入 */
const FORBIDDEN_KEY_PATTERN = /(pass(word)?|pwd|token|secret|credential|session|cookie)/i;

/** localStorage 不可用时的内存兜底（本次会话内仍然可用，只是不持久化） */
const memoryStore = new Map();
/** 是否已确认 localStorage 可用（null = 尚未探测） */
let persistent = null;

/**
 * 探测 localStorage 是否真正可写（Safari 隐私模式下 getItem 存在但 setItem 抛错）。
 * @returns {boolean}
 */
export function isAvailable() {
  if (persistent !== null) return persistent;
  try {
    const probeKey = `${NS}__probe__`;
    window.localStorage.setItem(probeKey, '1');
    window.localStorage.removeItem(probeKey);
    persistent = true;
  } catch {
    persistent = false;
  }
  return persistent;
}

/**
 * 取底层存储（不可用时回落到内存 Map，接口保持一致）。
 * @returns {{getItem: (k: string) => (string|null), setItem: (k: string, v: string) => void, removeItem: (k: string) => void, key: (i: number) => (string|null), get length: () => number}}
 */
function backend() {
  if (isAvailable()) return window.localStorage;
  return {
    getItem: (key) => (memoryStore.has(key) ? memoryStore.get(key) : null),
    setItem: (key, value) => memoryStore.set(key, value),
    removeItem: (key) => memoryStore.delete(key),
    key: (index) => Array.from(memoryStore.keys())[index] || null,
    get length() {
      return memoryStore.size;
    },
  };
}

/**
 * 补全命名空间。
 * @param {string} key
 * @returns {string}
 */
function fullKey(key) {
  const name = String(key || '');
  return name.startsWith(NS) ? name : `${NS}${name}`;
}

/**
 * 去掉命名空间（给 keys() 用）。
 * @param {string} key
 * @returns {string}
 */
function shortKey(key) {
  return key.startsWith(NS) ? key.slice(NS.length) : key;
}

/**
 * 读出信封（含过期判断）。坏数据就地删除并返回 null。
 * @param {string} key 短键名
 * @returns {{v: unknown, t: number, x: number}|null}
 */
function readEnvelope(key) {
  const store = backend();
  const raw = store.getItem(fullKey(key));
  if (raw === null || raw === undefined) return null;
  let envelope;
  try {
    envelope = JSON.parse(raw);
  } catch {
    // 坏 JSON：清掉，别让它反复污染后续读取
    store.removeItem(fullKey(key));
    return null;
  }
  if (!envelope || typeof envelope !== 'object' || !('v' in envelope)) {
    store.removeItem(fullKey(key));
    return null;
  }
  const expiresAt = Number(envelope.x) || 0;
  if (expiresAt > 0 && Date.now() > expiresAt) {
    store.removeItem(fullKey(key));
    return null;
  }
  return { v: envelope.v, t: Number(envelope.t) || 0, x: expiresAt };
}

/**
 * 读一个值。任何异常（坏 JSON / 不可用 / 已过期）都返回 fallback，**绝不抛错**。
 * @template T
 * @param {string} key 短键名（不带 `zsts.`）
 * @param {T} [fallback] 默认值
 * @returns {T}
 */
export function get(key, fallback = null) {
  try {
    const envelope = readEnvelope(key);
    return envelope === null ? fallback : /** @type {T} */ (envelope.v);
  } catch {
    return fallback;
  }
}

/**
 * 读元信息（「上次同步 11-05 22:10」这类文案要用）。
 * @param {string} key 短键名
 * @returns {{savedAt: number, expiresAt: number}|null}
 */
export function getMeta(key) {
  const envelope = readEnvelope(key);
  if (!envelope) return null;
  return { savedAt: envelope.t, expiresAt: envelope.x };
}

/**
 * 写一个值。
 * @param {string} key 短键名
 * @param {unknown} value 任意可 JSON 序列化的值
 * @param {number} [ttlMs] 存活毫秒数；0（默认）表示永不过期
 * @returns {boolean} 是否写入成功
 */
export function set(key, value, ttlMs = 0) {
  const name = shortKey(String(key || ''));
  if (!name) {
    console.error('[zsts] storage.set 需要一个非空 key');
    return false;
  }
  if (FORBIDDEN_KEY_PATTERN.test(name) && !CREDENTIAL_ALLOWLIST.includes(name)) {
    // 凭据只允许「记住学号」，密码 / token 一律不许落盘（PRD §10 存储与安全）
    console.error(`[zsts] storage.set 拒绝写入凭据类键名：${name}`);
    return false;
  }
  const now = Date.now();
  const envelope = {
    v: value,
    t: now,
    x: ttlMs > 0 ? now + Number(ttlMs) : 0,
  };
  let raw;
  try {
    raw = JSON.stringify(envelope);
  } catch {
    console.error(`[zsts] storage.set 序列化失败：${name}`);
    return false;
  }
  try {
    backend().setItem(fullKey(name), raw);
    return true;
  } catch {
    // 配额写满等：降级到内存，保证本次会话不炸
    memoryStore.set(fullKey(name), raw);
    persistent = false;
    return false;
  }
}

/**
 * 删除一个键。
 * @param {string} key 短键名
 */
export function remove(key) {
  try {
    backend().removeItem(fullKey(key));
  } catch {
    /* 忽略 */
  }
  memoryStore.delete(fullKey(key));
}

/**
 * 判断键是否存在且未过期。
 * @param {string} key 短键名
 * @returns {boolean}
 */
export function has(key) {
  return readEnvelope(key) !== null;
}

/**
 * 列出所有业务键（短键名，不含命名空间）。
 * @returns {string[]}
 */
export function keys() {
  const out = [];
  try {
    const store = backend();
    for (let i = 0; i < store.length; i += 1) {
      const rawKey = store.key(i);
      if (rawKey && rawKey.startsWith(NS)) out.push(shortKey(rawKey));
    }
  } catch {
    /* 忽略 */
  }
  for (const rawKey of memoryStore.keys()) {
    const name = shortKey(rawKey);
    if (!out.includes(name)) out.push(name);
  }
  return out;
}

/**
 * 统计占用体积（字节，按 UTF-16 每字符 2 字节估算）。
 * 只统计本命名空间下的键，避免把别的站点数据算进来。
 * @returns {number}
 */
export function sizeBytes() {
  let total = 0;
  try {
    const store = backend();
    for (let i = 0; i < store.length; i += 1) {
      const rawKey = store.key(i);
      if (!rawKey || !rawKey.startsWith(NS)) continue;
      const raw = store.getItem(rawKey) || '';
      total += (rawKey.length + raw.length) * 2;
    }
  } catch {
    /* 忽略 */
  }
  for (const [rawKey, raw] of memoryStore) {
    total += (rawKey.length + raw.length) * 2;
  }
  return total;
}

/**
 * 清空业务键。
 * @param {{keep?: string[], keepPrefixes?: string[]}} [options]
 *   `keep` 精确保留的短键名（默认保留主题与记住学号）；
 *   `keepPrefixes` 前缀保留（默认保留本地课表这类学生自产数据）。
 * @returns {number} 实际删除的键数量
 */
export function clearAll(options = {}) {
  const keep = Array.isArray(options.keep) ? options.keep : ['theme', 'rememberId'];
  const keepPrefixes = Array.isArray(options.keepPrefixes) ? options.keepPrefixes : USER_DATA_PREFIXES;
  let removed = 0;
  for (const name of keys()) {
    if (keep.includes(name)) continue;
    if (keepPrefixes.some((prefix) => name.startsWith(prefix))) continue;
    remove(name);
    removed += 1;
  }
  return removed;
}

/**
 * 清空**全部**业务键（连主题、记住学号、本地课表一起清）。
 * 只给「彻底重置」用；退出登录与清缓存一律走 `clearAll`，别在这里图省事。
 * @returns {number}
 */
export function clearEverything() {
  return clearAll({ keep: [], keepPrefixes: [] });
}
