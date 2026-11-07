/**
 * jwxtOrigin.js —— 教务站点的「协议自适应」地址解析
 *
 * 为什么需要它（2026-11-07 真机实测，移动数据环境）：
 *   教务对外只开 80 / 443，但两者可达性随网络环境变化 ——
 *     · 校园网：443 正常，https 可用；
 *     · 移动数据：443 的 TCP SYN 30 s 都无人应答（`failed to connect to 60.2.228.76 port 443`），
 *       80 却在 0.05 s 内握手成功，且服务端不下发 https 跳转。
 *   写死 https 的后果正是「浏览器能打开、App 一律登录失败」。所以地址必须探测后择一。
 *
 * 探测策略（只探 https，不探 http）：
 *   https 连得通 → 用 https；连不通 → 直接降到 http。
 *   理由是「http 一定在」：校外入口 80 实测可用，校内同样放行 80；万一 http 也不通，
 *   真正的业务请求自然会报错，不必用探针多等一轮。单个探针 + 3 s 上限，成本可控。
 *   （早期版本并发探两个协议并用登录页当探针，实测登录页 19 KB 要 16 s 才吐完，
 *     必然把可用通道判死，故改为 favicon 这种静态小文件：404 也算「连得通」。）
 *
 * 结果缓存 3 天；全失败后冷却 60 s，断网时不反复探。
 *
 * 本模块只管「协议 + 域名」，不碰 Cookie / 登录 / 端点表 —— 与 jwxtClient 单向依赖，
 * 避免循环 import，也让 externalLinks 能单独取一个地址交给原生 WebView。
 */

import { httpRequest, detectEnv } from '../core/transport.js';
import * as storage from '../core/storage.js';

export const HOST = 'jwxt.tstc.edu.cn';
export const ORIGIN_HTTPS = `https://${HOST}`;
export const ORIGIN_HTTP = `http://${HOST}`;

/** 探针路径：静态小文件，无需会话，未登录也回 404（<500 即视为通） */
const PROBE_PATH = '/favicon.ico';
/** https 探测上限：正常网络（含校园网 LAN）都远小于它；校外则是 443 直接被丢弃 */
const PROBE_TIMEOUT_MS = 3000;

/** 缓存键（storage 会加 zsts. 前缀）；值就是 origin 字符串 */
const CACHE_KEY = 'remoteOrigin';
const CACHE_TTL_MS = 3 * 24 * 60 * 60 * 1000;
/** 探测失败后的冷却期，避免断网时每个请求都白等一轮 */
const RETRY_COOLDOWN_MS = 60 * 1000;

let active = '';
let inflight = null;
let lastProbeFailAt = 0;

/**
 * 同步取当前 origin（带缓存兜底，**不会**发起网络请求）。
 * 页面渲染、拼接外链地址这类场景用它；真实请求前请先 await resolveOrigin()。
 * @returns {string}
 */
export function getActiveOrigin() {
  if (active) return active;
  const cached = storage.get(CACHE_KEY, '');
  if (typeof cached === 'string' && (cached === ORIGIN_HTTPS || cached === ORIGIN_HTTP)) {
    active = cached;
    return active;
  }
  return ORIGIN_HTTPS;
}

/** 当前是否降级到了明文 http（校外网络）。用于决定是否给用户一句提示。 */
export function isInsecureOrigin() {
  return getActiveOrigin() === ORIGIN_HTTP;
}

/** 忘掉已解析/缓存的结果（换网络、退出登录、排障时用）。 */
export function forgetOrigin() {
  active = '';
  inflight = null;
  storage.remove(CACHE_KEY);
}

/**
 * https 连得通吗？只看「有没有拿到 HTTP 响应」，5xx 视为不通。
 * @returns {Promise<boolean>}
 */
async function httpsReachable() {
  const res = await httpRequest({
    url: `${ORIGIN_HTTPS}${PROBE_PATH}`,
    method: 'GET',
    headers: { Accept: '*/*' },
    body: null,
    timeout: PROBE_TIMEOUT_MS,
  });
  const status = res && res.ok ? Number(res.data && res.data.status) : 0;
  return status > 0 && status < 500;
}

/**
 * 选协议：https 优先，连不通就 http。结果写缓存。
 * 冷却期内直接返回当前值（不重复探测）。
 * @returns {Promise<string>}
 */
export async function probeOrigin() {
  if (Date.now() - lastProbeFailAt < RETRY_COOLDOWN_MS) return getActiveOrigin();
  const ok = await httpsReachable().catch(() => false);
  const chosen = ok ? ORIGIN_HTTPS : ORIGIN_HTTP;
  active = chosen;
  lastProbeFailAt = ok ? 0 : Date.now();
  storage.set(CACHE_KEY, chosen, CACHE_TTL_MS);
  return chosen;
}

/**
 * 取一个可用于发请求的 origin（缓存优先，缺失才探测）。
 * web 预览走 server.js 的同源代理，不需要选协议，直接给 https 默认值。
 * @returns {Promise<string>}
 */
export async function resolveOrigin() {
  if (typeof window === 'undefined') return ORIGIN_HTTPS;
  if (detectEnv() !== 'android') return ORIGIN_HTTPS;
  if (active) return active;
  const cached = storage.get(CACHE_KEY, '');
  if (typeof cached === 'string' && (cached === ORIGIN_HTTPS || cached === ORIGIN_HTTP)) {
    active = cached;
    return active;
  }
  if (inflight) return inflight;
  inflight = (async () => {
    try {
      return await probeOrigin();
    } finally {
      inflight = null;
    }
  })();
  return inflight;
}
