#!/usr/bin/env node
/**
 * 掌上唐师 · 零依赖静态服务器
 *
 * 设计约束（见 docs/ARCHITECTURE.md §3）：
 *  1. 只用 node 内置模块，不引入任何第三方依赖
 *  2. 端口取 `process.env.PORT`，缺省 5173；只绑 127.0.0.1（不暴露到局域网）
 *  3. `.js` / `.mjs` 必须返回 `text/javascript; charset=utf-8`
 *     —— 类型写错浏览器会直接抛 "Failed to load module script"，整套 ESM 白屏
 *  4. 未知后缀返回 404（不做 octet-stream 下发，避免被当成脚本执行）
 *  5. 无后缀路径与 404 走 SPA 回退返回 index.html
 *  6. `path.resolve()` 之后必须仍在 WEB_ROOT 内，否则 403（含 `..%2f` 编码穿越）
 *  7. 默认 `Cache-Control: no-store`；带 `?cache=1` 才允许 1 小时缓存
 *  8. 每条请求打一行日志：`GET /path 200 3ms`
 *  9. 静态资源非 GET/HEAD 返回 405；端口占用打印友好提示
 *     （例外：/jwxt/* 与 /__zsts/encrypt 是本地教务代理，允许 POST，见 tools/jwxt-proxy.mjs）
 *
 * 用法：
 *   node server.js
 *   PORT=8080 node server.js
 */

import { createServer } from 'node:http';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isProxyPath, handleProxy } from './tools/jwxt-proxy.mjs';

/** 项目根目录（本文件所在目录） */
const ROOT = path.dirname(fileURLToPath(import.meta.url));
/** 静态资源根目录，所有请求都被限制在这个前缀之下 */
const WEB_ROOT = path.join(ROOT, 'web');
/** 监听地址：仅本机 */
const HOST = '127.0.0.1';
/** 监听端口 */
const PORT = Number.parseInt(process.env.PORT || '', 10) || 5173;

/** 后缀 → Content-Type 映射。charset 一律显式声明，避免中文乱码。 */
const MIME = new Map([
  ['.html', 'text/html; charset=utf-8'],
  ['.css', 'text/css; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.mjs', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.map', 'application/json; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
  ['.png', 'image/png'],
  ['.jpg', 'image/jpeg'],
  ['.jpeg', 'image/jpeg'],
  ['.webp', 'image/webp'],
  ['.gif', 'image/gif'],
  ['.ico', 'image/x-icon'],
  ['.webmanifest', 'application/manifest+json; charset=utf-8'],
  ['.woff', 'font/woff'],
  ['.woff2', 'font/woff2'],
  ['.ttf', 'font/ttf'],
  ['.txt', 'text/plain; charset=utf-8'],
  ['.md', 'text/markdown; charset=utf-8'],
]);

/**
 * 单行请求日志。格式固定为 `METHOD /path STATUS Nms`，便于 grep 与肉眼扫。
 * @param {string} method HTTP 方法
 * @param {string} target 请求路径（含查询串）
 * @param {number} status 响应状态码
 * @param {number} started 请求开始的高精度时间戳（ms）
 */
function writeLog(method, target, status, started) {
  const ms = Math.max(0, Math.round(Date.now() - started));
  process.stdout.write(`${method} ${target} ${status} ${ms}ms\n`);
}

/**
 * 发送一段纯文本响应（错误兜底用）。
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {string} text
 * @param {Record<string, string>} [extraHeaders]
 */
function sendText(res, status, text, extraHeaders = {}) {
  const body = Buffer.from(text, 'utf8');
  res.writeHead(status, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Content-Length': String(body.byteLength),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    ...extraHeaders,
  });
  res.end(body);
}

/**
 * 判断绝对路径是否仍然落在 WEB_ROOT 之内。
 * 注意：WEB_ROOT 自身（也就是 `/` 请求被改写后的 index.html）要单独放行。
 * @param {string} absPath
 * @returns {boolean}
 */
function isInsideWebRoot(absPath) {
  if (absPath === WEB_ROOT) return true;
  return absPath.startsWith(WEB_ROOT + path.sep);
}

/**
 * 拆分请求目标为「已解码路径」+「查询串」。
 *
 * 注意：这里刻意不用 `new URL(req.url, ...)` 取 pathname —— URL 解析器会按 RFC 3986
 * 把 `/../server.js` 就地规范化成 `/server.js`，越界判断再也发现不了穿越尝试。
 * 所以必须先拿原始字符串 decode 一遍，自己检测 `..` 段。
 *
 * @param {string} rawTarget 原始 req.url（形如 `/a/b.js?x=1`）
 * @returns {{pathname: string, search: string}|null} 解码失败（非法百分号编码）返回 null
 */
function splitTarget(rawTarget) {
  const q = rawTarget.indexOf('?');
  const rawPath = q === -1 ? rawTarget : rawTarget.slice(0, q);
  const search = q === -1 ? '' : rawTarget.slice(q);
  let pathname;
  try {
    pathname = decodeURIComponent(rawPath);
  } catch {
    return null;
  }
  // Windows 上 `..\` 同样是穿越手法，统一成正斜杠再判断
  pathname = pathname.replace(/\\/g, '/');
  if (!pathname.startsWith('/')) pathname = `/${pathname}`;
  return { pathname, search };
}

/**
 * 路径里是否含 `..` 段（目录穿越）。
 * @param {string} pathname 已解码的路径
 * @returns {boolean}
 */
function hasParentSegment(pathname) {
  return pathname.split('/').some((segment) => segment === '..');
}

/**
 * 最后一段是否「看起来是文件」（带扩展名）。
 *
 *   `/app.js`    → true   （静态资源，不存在就 404）
 *   `/x.exe`     → true   （不存在就 404，绝不回退 index.html）
 *   `/a/b/c`     → false  （SPA 前端路由，回退 index.html）
 *   `/grades/12` → false  （同上）
 *   `/`          → false
 *
 * @param {string} pathname 已解码的路径
 * @returns {boolean}
 */
function looksLikeFile(pathname) {
  const last = pathname.slice(pathname.lastIndexOf('/') + 1);
  return /\.[A-Za-z0-9]+$/.test(last);
}

/**
 * 主请求处理器。
 * @param {import('node:http').IncomingMessage} req
 * @param {import('node:http').ServerResponse} res
 */
async function handle(req, res) {
  const started = Date.now();
  const method = req.method || 'GET';
  const rawTarget = req.url || '/';

  // —— 解析请求目标（保留原始路径语义，不交给 URL 解析器规范化）——
  const parsedTarget = splitTarget(rawTarget);
  if (!parsedTarget) {
    sendText(res, 400, '400 Bad Request: 非法的请求路径（百分号编码错误）');
    writeLog(method, rawTarget, 400, started);
    return;
  }
  const { pathname, search } = parsedTarget;

  // 空字节直接拒绝：文件系统调用会抛错，且是典型的穿越探测手法
  if (pathname.includes('\0')) {
    sendText(res, 403, '403 Forbidden');
    writeLog(method, rawTarget, 403, started);
    return;
  }

  // —— 6. 目录穿越防护第一层：原始路径里出现 `..` 段即拒绝 ——
  if (hasParentSegment(pathname)) {
    sendText(res, 403, '403 Forbidden: 路径中不允许出现 .. 段');
    writeLog(method, rawTarget, 403, started);
    return;
  }

  // —— 0. 本地教务代理（先于方法白名单：登录表单与 RSA 加密需要 POST）——
  // 只放行同源固定前缀，上游 host 在 tools/jwxt-proxy.mjs 里硬编码，不接受请求指定。
  // 日志只记路径，绝不记请求体（里面有密码）。
  if (isProxyPath(pathname)) {
    let status = 502;
    try {
      status = await handleProxy(req, res, pathname, search, method);
    } catch (err) {
      if (!res.headersSent) sendText(res, 502, `502 Bad Gateway: ${String(err && err.message)}`);
      status = 502;
    }
    writeLog(method, rawTarget, status, started);
    return;
  }

  // —— 9. 方法白名单（静态资源只允许 GET/HEAD）——
  if (method !== 'GET' && method !== 'HEAD') {
    sendText(res, 405, `405 Method Not Allowed: ${method}`, { Allow: 'GET, HEAD' });
    writeLog(method, rawTarget, 405, started);
    return;
  }

  // —— 1. 区分「静态资源」与「SPA 前端路由」——
  const isAsset = looksLikeFile(pathname);
  const targetPath = isAsset ? pathname.replace(/\/{2,}/g, '/') : '/index.html';
  const absPath = path.resolve(WEB_ROOT, `.${targetPath}`);

  // —— 6. 目录穿越防护第二层：resolve 之后仍必须落在 WEB_ROOT 内 ——
  if (!isInsideWebRoot(absPath)) {
    sendText(res, 403, '403 Forbidden: 禁止访问 web 根目录之外的文件');
    writeLog(method, rawTarget, 403, started);
    return;
  }

  // 静态资源：不存在就是 404，绝不回退 index.html（否则 /x.exe 也会吐 HTML）
  if (isAsset) {
    let info;
    try {
      info = await stat(absPath);
    } catch {
      sendText(res, 404, `404 Not Found: ${pathname}`);
      writeLog(method, rawTarget, 404, started);
      return;
    }
    if (!info.isFile()) {
      sendText(res, 404, `404 Not Found: ${pathname} 不是文件`);
      writeLog(method, rawTarget, 404, started);
      return;
    }
    const ext = path.extname(absPath).toLowerCase();
    if (!MIME.has(ext)) {
      sendText(res, 404, `404 Not Found: 不支持的文件类型 ${ext || '(无后缀)'}`);
      writeLog(method, rawTarget, 404, started);
      return;
    }
    serveFile(req, res, absPath, info, search, 200, started, rawTarget, method);
    return;
  }

  // SPA 前端路由 / 根路径 → index.html
  let indexInfo;
  try {
    indexInfo = await stat(absPath);
  } catch {
    sendText(res, 404, '404 Not Found: web/index.html 缺失');
    writeLog(method, rawTarget, 404, started);
    return;
  }
  serveFile(req, res, absPath, indexInfo, search, 200, started, rawTarget, method);
}

/**
 * 下发一个已确认存在的静态文件。
 * @param {import('node:http').IncomingMessage} req
 * @param {import('node:http').ServerResponse} res
 * @param {string} absPath 文件的绝对路径
 * @param {import('node:fs').Stats} info 文件信息
 * @param {string} search 查询串（用于识别 `?cache=1`）
 * @param {number} status 状态码
 * @param {number} started 请求开始时间
 * @param {string} rawTarget 原始请求路径（日志用）
 * @param {string} method HTTP 方法
 */
function serveFile(req, res, absPath, info, search, status, started, rawTarget, method) {
  const ext = path.extname(absPath).toLowerCase();
  const type = MIME.get(ext) || 'text/plain; charset=utf-8';
  // —— 7. 缓存策略：默认禁用，便于改完刷新即生效 ——
  const cacheControl = /(?:^|[?&])cache=1(?:&|$)/.test(search)
    ? 'public, max-age=3600'
    : 'no-store';

  const headers = {
    'Content-Type': type,
    'Content-Length': String(info.size),
    'Cache-Control': cacheControl,
    'X-Content-Type-Options': 'nosniff',
    'Last-Modified': info.mtime.toUTCString(),
  };

  if (method === 'HEAD') {
    res.writeHead(status, headers);
    res.end();
    writeLog(method, rawTarget, status, started);
    return;
  }

  res.writeHead(status, headers);
  const stream = createReadStream(absPath);
  stream.on('error', () => {
    // 头已发出，只能断开连接
    res.destroy();
  });
  res.on('close', () => {
    writeLog(method, rawTarget, status, started);
  });
  stream.pipe(res);
}

const server = createServer((req, res) => {
  handle(req, res).catch((err) => {
    if (!res.headersSent) {
      sendText(res, 500, `500 Internal Server Error: ${err && err.message ? err.message : '未知错误'}`);
    } else {
      res.destroy();
    }
  });
});

// —— 9. 监听错误友好提示 ——
server.on('error', (err) => {
  if (err && err.code === 'EADDRINUSE') {
    process.stderr.write(
      [
        '',
        `  ✗ 端口 ${PORT} 已被占用。`,
        '    可能是上一次的 server.js 还在运行，或别的程序占用了该端口。',
        '',
        '    可选处理：',
        `      1) 结束占用进程：Windows  netstat -ano | findstr :${PORT}   然后 taskkill /PID <pid> /F`,
        `                         macOS/Linux  lsof -i :${PORT}`,
        `      2) 换一个端口启动：PORT=${PORT + 1} node server.js`,
        '',
      ].join('\n')
    );
    process.exit(1);
  }
  if (err && err.code === 'EACCES') {
    process.stderr.write(`\n  ✗ 没有权限绑定 ${HOST}:${PORT}。请改用高位端口（1024 以上）。\n\n`);
    process.exit(1);
  }
  process.stderr.write(`\n  ✗ 服务器启动失败：${err && err.message ? err.message : String(err)}\n\n`);
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  process.stdout.write(
    [
      '',
      '  掌上唐师 · 静态服务器已启动（零依赖 / 零构建）',
      '',
      `    本地预览   http://${HOST}:${PORT}/`,
      `    静态根目录 ${WEB_ROOT}`,
      '',
      '    Ctrl+C 停止',
      '',
    ].join('\n')
  );
});

// 优雅退出：让端口立刻释放，避免连续重启时踩 EADDRINUSE
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    server.close(() => process.exit(0));
    // 兜底：连接迟迟不断开时强制退出
    setTimeout(() => process.exit(0), 800).unref();
  });
}
