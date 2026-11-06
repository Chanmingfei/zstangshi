#!/usr/bin/env node
/**
 * test-shell-smoke.mjs —— 「H5 装进 iOS 壳里还活着吗」的真实渲染冒烟测试。
 *
 * 前面的 test-bridge.mjs 只证明桥的接口与算法对；这个脚本证明**装了桥之后页面还能启动**：
 *   · 用 Node 起一个静态服务（模拟 LocalHTTPServer 的角色）；
 *   · 把 bridge-shim.js 注入到 index.html 的 <head> 最前面（模拟 WKUserScript documentStart）；
 *   · 用 Edge/Chrome 的 CDP 真渲染一次，断言：
 *       - window.NativeBridge 存在且 httpRequest 可用（transport.js 的探测条件）
 *       - document.title / #app-bar 有内容（说明 app.js 这套 ESM 全链路跑通）
 *       - 启动过程中没有任何未捕获异常（ESM 加载失败会在这里暴露）
 *       - 主题上报已发出（op: theme）
 *
 * 为什么值得做：iOS 侧的报错比 Android 更"闷"——WKWebView 白屏时控制台往往只有一句
 * 含糊的 module load failed。把「shim 会不会毁掉 ESM」这个问题在 Windows 上就验掉，
 * 能省掉一轮「上 Mac 才发现白屏」。
 *
 * 用法: node ios/scripts/test-shell-smoke.mjs [--headful]
 * 找不到浏览器时自动跳过（退出码 0），不阻塞 CI。
 */

import { createServer } from 'node:http';
import { readFile, mkdtemp, rm, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const iosRoot = resolve(here, '..');
const repoRoot = resolve(iosRoot, '..');
const webRoot = existsSync(join(repoRoot, 'web'))
  ? join(repoRoot, 'web')
  : join(repoRoot, 'src_apk', 'web');
const shimPath = join(iosRoot, 'ZhangShangTangShi', 'Bridge', 'bridge-shim.js');

const headful = process.argv.includes('--headful');

const BROWSERS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
];

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.webmanifest': 'application/manifest+json',
  '.txt': 'text/plain; charset=utf-8',
};

/* ── 模拟原生壳：messageHandlers + 注入 shim 的 index.html ─────────────── */

const mockNative = `
window.__ZSTS_NATIVE = { appVersion: '1.2.0', credential: '', platform: 'ios' };
window.__MOCK_MESSAGES = [];
window.webkit = window.webkit || {};
window.webkit.messageHandlers = window.webkit.messageHandlers || {};
window.webkit.messageHandlers.zsts = {
  postMessage: function (payload) {
    window.__MOCK_MESSAGES.push(payload);
    if (payload && payload.op === 'http') {
      // 模拟原生异步回调：给一条正常的空响应，验证 __nativeHttpCallback 链路
      var reqId = payload.reqId;
      setTimeout(function () {
        var body = '{"ok":true}';
        var payloadJson = JSON.stringify({
          reqId: reqId, status: 200, headers: { 'content-type': 'application/json' },
          body: body, url: payload.url, finalUrl: payload.url,
        });
        if (window.__nativeHttpCallback) window.__nativeHttpCallback(payloadJson);
      }, 30);
    }
  },
};
`;

function buildIndexHtml(originalHtml, shimSource) {
  const injection = `<script>\n${mockNative}\n</script>\n<script>\n${shimSource}\n</script>\n`;
  if (originalHtml.includes('<head>')) {
    return originalHtml.replace('<head>', `<head>\n${injection}`);
  }
  return injection + originalHtml;
}

async function startServer(shimSource) {
  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://127.0.0.1');
      let path = decodeURIComponent(url.pathname);
      if (path === '/') path = '/index.html';
      if (path.includes('..')) {
        res.writeHead(403).end('forbidden');
        return;
      }
      const file = join(webRoot, normalize(path));
      const info = await stat(file).catch(() => null);
      if (!info || !info.isFile()) {
        res.writeHead(404, { 'content-type': 'text/plain' }).end('not found');
        return;
      }
      let data = await readFile(file);
      if (path === '/index.html') {
        data = Buffer.from(buildIndexHtml(data.toString('utf8'), shimSource), 'utf8');
      }
      res.writeHead(200, {
        'content-type': MIME[extname(file).toLowerCase()] || 'application/octet-stream',
        'cache-control': 'no-store',
      });
      res.end(data);
    } catch (error) {
      res.writeHead(500, { 'content-type': 'text/plain' }).end(String(error));
    }
  });

  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  return { server, port: server.address().port };
}

/* ── 极简 CDP 客户端 ──────────────────────────────────────────────────── */

function connect(url) {
  return new Promise((ok, no) => {
    const socket = new WebSocket(url);
    socket.addEventListener('open', () => ok(socket));
    socket.addEventListener('error', () => no(new Error('WebSocket 连接失败')));
  });
}

let nextId = 1;
function call(socket, method, params = {}) {
  const id = nextId++;
  return new Promise((ok, no) => {
    const listener = (event) => {
      const message = JSON.parse(event.data);
      if (message.id !== id) return;
      socket.removeEventListener('message', listener);
      if (message.error) no(new Error(`${method}: ${message.error.message}`));
      else ok(message.result);
    };
    socket.addEventListener('message', listener);
    socket.send(JSON.stringify({ id, method, params }));
    setTimeout(() => {
      socket.removeEventListener('message', listener);
      no(new Error(`${method} 超时`));
    }, 20000);
  });
}

async function waitForEndpoint(port) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (response.ok) return await response.json();
    } catch {
      /* 还没起来 */
    }
    await new Promise((ok) => setTimeout(ok, 200));
  }
  throw new Error('浏览器调试端口未就绪');
}

/* ── 主流程 ───────────────────────────────────────────────────────────── */

let passed = 0;
const failures = [];
function check(name, condition, detail) {
  if (condition) {
    passed += 1;
    console.log(`  ✓ ${name}`);
  } else {
    failures.push(detail ? `${name} —— ${detail}` : name);
    console.log(`  ✗ ${name}${detail ? ` —— ${detail}` : ''}`);
  }
}

const browserPath = BROWSERS.find((candidate) => existsSync(candidate));
if (!browserPath) {
  console.log('· 未找到 Edge/Chrome，跳过渲染冒烟测试（不影响其它校验）');
  process.exit(0);
}

const shimSource = await readFile(shimPath, 'utf8');
const { server, port } = await startServer(shimSource);
const userDataDir = await mkdtemp(join(tmpdir(), 'zsts-smoke-'));
const debugPort = 9333;

const args = [
  `--user-data-dir=${userDataDir}`,
  `--remote-debugging-port=${debugPort}`,
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-extensions',
  '--disable-background-networking',
  'about:blank',
];
if (!headful) args.unshift('--headless=new');

const child = spawn(browserPath, args, { stdio: 'ignore', detached: false });
let socket;

try {
  await waitForEndpoint(debugPort);

  const created = await (async () => {
    const response = await fetch(`http://127.0.0.1:${debugPort}/json/new?about:blank`, { method: 'PUT' });
    if (response.ok) return await response.json();
    const list = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json();
    return list.find((target) => target.type === 'page');
  })();

  socket = await connect(created.webSocketDebuggerUrl);
  await call(socket, 'Runtime.enable');
  await call(socket, 'Page.enable');
  // 按 iPhone 14 的视口渲染，截图才能反映真实 iOS 布局（含安全区 CSS 的生效情况）
  await call(socket, 'Emulation.setDeviceMetricsOverride', {
    width: 390, height: 844, deviceScaleFactor: 2, mobile: true,
  });

  const consoleErrors = [];
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    if (message.method === 'Runtime.exceptionThrown') {
      consoleErrors.push(message.params.exceptionDetails?.text || '未捕获异常');
    }
    if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
      consoleErrors.push(message.params.args.map((a) => a.value ?? a.description).join(' '));
    }
  });

  console.log(`\n[渲染冒烟] ${webRoot}`);
  await call(socket, 'Page.navigate', { url: `http://127.0.0.1:${port}/index.html` });

  // 等首屏渲染：ESM 全链路（约 30 个模块）在桌面浏览器里通常 < 2s
  let rendered = false;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    await new Promise((ok) => setTimeout(ok, 250));
    const probe = await call(socket, 'Runtime.evaluate', {
      expression: `(function(){
        var bar = document.getElementById('app-bar');
        return JSON.stringify({
          ready: document.readyState,
          bridge: !!window.NativeBridge,
          http: !!(window.NativeBridge && window.NativeBridge.httpRequest),
          barChildren: bar ? bar.children.length : -1,
          title: document.title,
          hash: location.hash,
          messages: (window.__MOCK_MESSAGES || []).map(function(m){return m.op;})
        });
      })()`,
      returnByValue: true,
    });
    const state = JSON.parse(probe.result.value);
    if (state.ready === 'complete' && state.barChildren > 0) {
      rendered = true;
      check('NativeBridge 已注入且 httpRequest 可用', state.bridge && state.http);
      check('isNative() 返回 true（transport 会走原生通道）',
        (await call(socket, 'Runtime.evaluate', {
          expression: 'window.NativeBridge.isNative()', returnByValue: true,
        })).result.value === true);
      check('页头已渲染（app.js 与全部 ESM 模块加载成功）', state.barChildren > 0,
        `app-bar 子节点 ${state.barChildren}`);
      // 路由会把标题改写成「登录 · 掌上唐师」，只断言品牌名在标题里
      check('document.title 含品牌名', state.title.includes('掌上唐师'), state.title);
      check('主题已上报原生（op: theme）', state.messages.includes('theme'),
        JSON.stringify(state.messages));
      check('启动过程中无未捕获异常', consoleErrors.length === 0,
        consoleErrors.slice(0, 3).join(' | '));
      break;
    }
  }

  if (!rendered) {
    check('页面在 10s 内完成首屏渲染', false, '疑似白屏');
    if (consoleErrors.length) {
      console.log('  控制台错误：');
      for (const error of consoleErrors.slice(0, 5)) console.log(`    · ${error}`);
    }
  }

  const fsp = await import('node:fs/promises');
  const shotDir = join(iosRoot, 'build');
  await fsp.mkdir(shotDir, { recursive: true });

  const shoot = async (name) => {
    const shot = await call(socket, 'Page.captureScreenshot', { format: 'png' });
    await fsp.writeFile(join(shotDir, name), Buffer.from(shot.data, 'base64'));
    console.log(`· 截图: build/${name}`);
  };

  const snapshot = async () => {
    const result = await call(socket, 'Runtime.evaluate', {
      expression: `JSON.stringify({
        main: (document.getElementById('app-main') || {}).children
          ? document.getElementById('app-main').children.length : -1,
        tabbar: (document.getElementById('tabbar') || {}).children
          ? document.getElementById('tabbar').children.length : -1,
        hash: location.hash
      })`,
      returnByValue: true,
    });
    return JSON.parse(result.result.value);
  };

  await shoot('smoke-login.png');

  /* ── 主界面：走真实的演示模式 + 路由，逐个 Tab 触发懒加载 ────────────── */
  console.log('\n[主界面]');
  const entered = await call(socket, 'Runtime.evaluate', {
    expression: 'window.__zsts && window.__zsts.actions ? (window.__zsts.actions.enterDemo(), true) : false',
    returnByValue: true,
  });
  check('演示模式入口可用（__zsts.actions.enterDemo）', entered.result.value === true);

  const routes = [
    ['/home', 'smoke-home.png'],
    ['/timetable', 'smoke-timetable.png'],
    ['/grades', 'smoke-grades.png'],
    ['/mine', 'smoke-mine.png'],
  ];
  for (const [route, file] of routes) {
    await call(socket, 'Runtime.evaluate', {
      expression: `window.__zsts.router.go(${JSON.stringify(route)}); true`,
      returnByValue: true,
    });
    // 懒加载页面模块 + 渲染 + 过渡动画
    await new Promise((ok) => setTimeout(ok, 900));
    const state = await snapshot();
    check(`${route} 渲染出内容`, state.main > 0, `app-main 子节点 ${state.main}`);
    check(`${route} 底栏就绪`, state.tabbar > 0, `tabbar 子节点 ${state.tabbar}`);
    await shoot(file);
  }

  check('整个过程无新增未捕获异常', consoleErrors.length === 0,
    consoleErrors.slice(0, 3).join(' | '));
} catch (error) {
  check('渲染冒烟测试执行完成', false, String(error.message || error));
} finally {
  try { socket?.close(); } catch { /* 忽略 */ }
  try { child.kill(); } catch { /* 忽略 */ }
  server.close();
  await rm(userDataDir, { recursive: true, force: true }).catch(() => {});
}

console.log('');
if (failures.length === 0) {
  console.log(`✅ 渲染冒烟通过（${passed} 项）`);
  process.exit(0);
}
console.error(`❌ ${failures.length} 项失败：（通过 ${passed} 项）`);
for (const failure of failures) console.error(`  - ${failure}`);
process.exit(1);
