/**
 * jwxt-proxy.mjs —— 本地代理（零依赖），给桌面预览提供「直连正方教务」的能力
 *
 * 为什么需要它：浏览器里 fetch 直连 jwxt.tstc.edu.cn 会被 CORS 拦，Android 端有
 * NativeBridge 代劳，桌面预览没有。所以由本地静态服务器开一个**同源**转发端点：
 *   /jwxt/<教务路径>      → https://jwxt.tstc.edu.cn/<教务路径>（GET/POST，跟随重定向）
 *   /__zsts/encrypt       → 用 Node crypto 做 RSAES-PKCS1-v1_5 加密，等价 Java
 *                           Cipher.getInstance("RSA/ECB/PKCS1Padding")，无字节反转
 *
 * 【安全边界，别放宽】
 *   · server.js 只绑 127.0.0.1，外部机器访问不到
 *   · 上游 host 是硬编码常量，绝不接受请求里传来的 host（防 SSRF）
 *   · 明文密码只在内存里流转：不写日志、不落盘、不进任何响应体
 *   · rejectUnauthorized:false 是校园网自签证书的必要妥协，仅限这一个固定 host
 *
 * 【Cookie Jar】正方多节点负载均衡靠 `route` Cookie 绑定后端（见 remote-api-map.md
 * §3.1b），登录页拿到的 csrftoken 必须和 POST 落到同一节点。所以代理要吸收每次响应的
 * Set-Cookie 并在后续请求里原样带上；Android 侧由 NativeBridge 的 CookieJar 做同一件事。
 */

import https from 'node:https';
import crypto from 'node:crypto';

const UPSTREAM_HOST = 'jwxt.tstc.edu.cn';
const UPSTREAM_ORIGIN = `https://${UPSTREAM_HOST}`;
const UPSTREAM_TIMEOUT = 15000;
const MAX_REDIRECTS = 5;

/** 进程内 Cookie 罐（桌面预览单用户，不需要持久化） */
const jar = new Map();

/** 判断是不是代理端点（server.js 在方法白名单之前调用） */
export function isProxyPath(pathname) {
  return pathname === '/__zsts/encrypt' || pathname.startsWith('/jwxt/');
}

function cookieHeader() {
  return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
}

function absorbCookies(headers) {
  const raw = headers['set-cookie'];
  if (!raw) return;
  for (const line of Array.isArray(raw) ? raw : [raw]) {
    const pair = line.split(';')[0];
    const at = pair.indexOf('=');
    if (at > 0) jar.set(pair.slice(0, at).trim(), pair.slice(at + 1).trim());
  }
}

/** 读请求体，上限 1 MiB（表单/JSON 都远小于此） */
function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size <= 1024 * 1024) chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', () => resolve(Buffer.alloc(0)));
  });
}

function reply(res, status, body, headers = {}) {
  const buf = Buffer.isBuffer(body) ? body : Buffer.from(String(body), 'utf8');
  res.writeHead(status, {
    'Content-Length': String(buf.byteLength),
    'Cache-Control': 'no-store',
    ...headers,
  });
  res.end(buf);
}

/**
 * RSA 加密：modulus 是 Base64（129 字节，首字节 0x00），exponent='AQAB'。
 * JWK 的 n 不应带前导零，先剥掉；再用标准 PKCS1 v1.5 padding 加密。
 * @returns {{ok: true, cipher: string}|{ok: false, message: string}}
 */
export function encryptPassword(modulus, exponent, plain) {
  try {
    let modBuf = Buffer.from(String(modulus), 'base64');
    if (modBuf.length === 129 && modBuf[0] === 0) modBuf = modBuf.subarray(1);
    const expBuf = Buffer.from(String(exponent || 'AQAB'), 'base64');
    const key = crypto.createPublicKey({
      key: {
        kty: 'RSA',
        n: modBuf.toString('base64url'),
        e: expBuf.toString('base64url'),
      },
      format: 'jwk',
    });
    const cipher = crypto.publicEncrypt(
      { key, padding: crypto.constants.RSA_PKCS1_PADDING },
      Buffer.from(String(plain), 'utf8')
    );
    return { ok: true, cipher: cipher.toString('base64') };
  } catch (err) {
    return { ok: false, message: String((err && err.message) || err) };
  }
}

/**
 * 单次上游请求。永不 throw，失败归一成 502/504。
 * @returns {Promise<{status: number, headers: object, body: Buffer}>}
 */
function once(targetPath, method, headers, body) {
  return new Promise((resolve) => {
    const req = https.request({
      host: UPSTREAM_HOST,
      port: 443,
      path: targetPath,
      method,
      headers: {
        Host: UPSTREAM_HOST,
        'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36',
        Accept: 'text/html,application/xhtml+xml,application/json,*/*',
        'Accept-Language': 'zh-CN,zh;q=0.9',
        Referer: `${UPSTREAM_ORIGIN}/xtgl/login_slogin.html`,
        ...(body && body.length ? { 'Content-Length': String(body.length) } : {}),
        ...headers,
      },
      rejectUnauthorized: false,
      timeout: UPSTREAM_TIMEOUT,
      agent: false, // 不复用连接：多节点绑定下 keep-alive 会让 csrftoken 串节点
    }, (upstream) => {
      absorbCookies(upstream.headers);
      const chunks = [];
      upstream.on('data', (c) => chunks.push(c));
      upstream.on('end', () => resolve({
        status: upstream.statusCode || 502,
        headers: upstream.headers,
        body: Buffer.concat(chunks),
      }));
    });
    req.on('timeout', () => {
      req.destroy();
      resolve({ status: 504, headers: {}, body: Buffer.from('教务系统响应超时') });
    });
    req.on('error', (err) => {
      resolve({ status: 502, headers: {}, body: Buffer.from(`连不上教务系统：${err.message}`) });
    });
    if (body && body.length) req.write(body);
    req.end();
  });
}

/**
 * 转发并跟随重定向。登录 POST 成功后会 302 到 index_initMenu.html，
 * 失败会 302 到 login_slogin.html?time=… —— 这两种**必须**跟到最终页，
 * 否则调用方拿到的只是空 302，判不出登录成败。
 * 最终 URL 通过 X-Zsts-Final-Url 回吐给前端做诊断。
 */
async function forward(targetPath, method, reqHeaders, body) {
  let path = targetPath;
  let currentMethod = method;
  let currentBody = body;
  let result = await once(path, currentMethod, reqHeaders, currentBody);
  const chain = [`${result.status} ${path}`];

  for (let hop = 0; hop < MAX_REDIRECTS; hop += 1) {
    if (result.status < 300 || result.status >= 400 || !result.headers.location) break;
    const location = String(result.headers.location);
    let next;
    try {
      const url = new URL(location, UPSTREAM_ORIGIN);
      // 只跟同一个 host，绝不跳到外部地址
      if (url.host !== UPSTREAM_HOST) { chain.push(`BLOCKED ${location}`); break; }
      next = `${url.pathname}${url.search}`;
    } catch { break; }
    chain.push(`${result.status}->${location}`);
    currentMethod = 'GET'; // 302 一律按 GET 跟随（浏览器行为）
    currentBody = null;
    // 关键：302 之后必须重建 Cookie。登录成功会在 POST 响应里换发 JSESSIONID，
    // 复用 POST 前算好的旧 Cookie 会让会话立刻失效、被踢回登录页。
    result = await once(next, currentMethod, { ...reqHeaders, Cookie: cookieHeader() }, currentBody);
    path = next;
    chain.push(`${result.status} ${path}`);
  }
  // 诊断头只允许 ASCII，避免非 ASCII 字符触发 writeHead 抛错
  const asciiChain = chain.join(' | ').replace(/[^\x20-\x7E]/g, '?');
  return { ...result, finalUrl: `${UPSTREAM_ORIGIN}${path}`, chain: asciiChain };
}

/**
 * 代理入口。
 * @param {import('node:http').IncomingMessage} req
 * @param {import('node:http').ServerResponse} res
 * @param {string} pathname
 * @param {string} search
 * @param {string} method
 * @returns {Promise<number>} 回吐的 HTTP 状态码（供 server.js 打日志）
 */
export async function handleProxy(req, res, pathname, search, method) {
  const body = method === 'POST' ? await readBody(req) : null;

  if (pathname === '/__zsts/encrypt') {
    if (method !== 'POST') {
      reply(res, 405, '405 Method Not Allowed', { Allow: 'POST' });
      return 405;
    }
    let payload;
    try { payload = JSON.parse(body.toString('utf8')); } catch {
      reply(res, 400, '{"error":"请求体不是合法 JSON"}', { 'Content-Type': 'application/json' });
      return 400;
    }
    const result = encryptPassword(payload.modulus, payload.exponent, payload.plain);
    if (!result.ok) {
      reply(res, 400, JSON.stringify({ error: result.message }), { 'Content-Type': 'application/json' });
      return 400;
    }
    reply(res, 200, JSON.stringify({ cipher: result.cipher }), { 'Content-Type': 'application/json' });
    return 200;
  }

  if (!pathname.startsWith('/jwxt/')) {
    reply(res, 404, '404 Not Found');
    return 404;
  }
  if (method !== 'GET' && method !== 'HEAD' && method !== 'POST') {
    reply(res, 405, '405 Method Not Allowed', { Allow: 'GET, HEAD, POST' });
    return 405;
  }

  const upstreamPath = `${pathname.slice('/jwxt'.length)}${search || ''}`;
  const headers = {
    Accept: String(req.headers.accept || 'text/html,application/json,*/*'),
    'Content-Type': String(req.headers['content-type'] || 'application/x-www-form-urlencoded; charset=UTF-8'),
    'X-Requested-With': String(req.headers['x-requested-with'] || 'XMLHttpRequest'),
    Cookie: cookieHeader(),
  };
  const result = await forward(upstreamPath, method, headers, body);
  reply(res, result.status, result.body, {
    'Content-Type': String(result.headers['content-type'] || 'text/html; charset=utf-8'),
    'X-Zsts-Final-Url': result.finalUrl,
    'X-Zsts-Chain': result.chain,
  });
  return result.status;
}

/** 清 Cookie（退出登录时调用，避免下一次拿到旧会话） */
export function clearProxyCookies() { jar.clear(); }
