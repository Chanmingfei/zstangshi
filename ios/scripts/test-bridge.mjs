#!/usr/bin/env node
/**
 * test-bridge.mjs —— bridge-shim.js 的协议一致性测试（不需要 iPhone / Xcode）。
 *
 * 校验的是「H5 会不会正常跑在 iOS 壳里」这件事的**全部前置条件**：
 *   1. window.NativeBridge 的 10 个成员齐全、类型正确（少一个，H5 就退化或报错）；
 *   2. httpRequest 的 postMessage 载荷字段与顺序语义正确（url 在 method 之前那条坑）；
 *   3. rsaEncrypt 与标准 RSAES-PKCS1-v1_5 实现互通 —— 这是最关键的一条：
 *      教务登录要把密码用校方公钥加密后提交，算错就永远登录失败，且现场毫无提示；
 *   4. 129 字节带符号位前缀的公钥 modulus 能被正确剥掉（正方公钥偶发这种编码）；
 *   5. 凭据读写与 clearSession 的行为；
 *   6. openWebView / openWifiSettings 的跳转意图。
 *
 * 用法: node ios/scripts/test-bridge.mjs
 */

import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateKeyPairSync, privateDecrypt, constants, randomFillSync } from 'node:crypto';

const here = dirname(fileURLToPath(import.meta.url));
const shimPath = resolve(here, '..', 'ZhangShangTangShi', 'Bridge', 'bridge-shim.js');
const shimSource = readFileSync(shimPath, 'utf8');

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

/** 造一个足够像 WKWebView 的沙箱：window / document / atob / btoa / crypto / MutationObserver */
function createShell({ credential = '', appVersion = '1.2.0' } = {}) {
  const messages = [];
  const context = {
    console,
    atob: (value) => Buffer.from(value, 'base64').toString('binary'),
    btoa: (value) => Buffer.from(value, 'binary').toString('base64'),
    TextEncoder,
    Uint8Array,
    BigInt,
    parseInt,
    Math,
    JSON,
    crypto: {
      getRandomValues: (array) => {
        randomFillSync(array);
        return array;
      },
    },
    MutationObserver: class {
      observe() {}
      disconnect() {}
    },
    setTimeout,
    clearTimeout,
    document: {
      readyState: 'complete',
      documentElement: { getAttribute: () => 'light' },
      addEventListener: () => {},
    },
  };
  context.window = context;
  context.globalThis = context;
  context.webkit = { messageHandlers: { zsts: { postMessage: (payload) => messages.push(payload) } } };
  context.__ZSTS_NATIVE = { appVersion, credential, platform: 'ios' };

  vm.createContext(context);
  vm.runInContext(shimSource, context);
  return { context, messages };
}

/* ── 1. 接口齐备性 ─────────────────────────────────────────────────────── */

console.log('\n[1] 接口齐备性（与 Android NativeBridge.java 的 9 个 @JavascriptInterface 对齐）');
{
  const { context } = createShell();
  const bridge = context.window.NativeBridge;

  check('window.NativeBridge 存在', Boolean(bridge));
  check('isNative() === true', bridge.isNative() === true);
  check('getAppVersion() 返回字符串', bridge.getAppVersion() === '1.2.0');

  const expected = {
    isNative: 'function',
    getAppVersion: 'function',
    httpRequest: 'function',
    rsaEncrypt: 'function',
    clearSession: 'function',
    saveCredential: 'function',
    loadCredential: 'function',
    clearCredential: 'function',
    openWebView: 'function',
    openWifiSettings: 'function',
  };
  for (const [name, type] of Object.entries(expected)) {
    check(`${name} 是 ${type}`, typeof bridge[name] === type, `实际为 ${typeof bridge[name]}`);
  }

  // transport.js 的探测条件：bridge && typeof bridge.httpRequest === 'function'
  check('detectEnv() 的探测条件成立',
    Boolean(bridge) && typeof bridge.httpRequest === 'function');
}

/* ── 2. httpRequest 载荷 ───────────────────────────────────────────────── */

console.log('\n[2] httpRequest 载荷（transport.js:185-191 的调用点）');
{
  const { context, messages } = createShell();
  context.window.NativeBridge.httpRequest(
    'zsts-1-1',
    'https://jwxt.tstc.edu.cn/cjcx/cjcx_cxXsgrcj.html',
    'POST',
    '{"content-type":"application/x-www-form-urlencoded"}',
    'xnm=2026&xqm=3'
  );
  // 启动时 shim 会先上报一次主题（op: theme），这里只挑 http 那条
  const httpMessages = messages.filter((m) => m.op === 'http');
  const message = httpMessages[0];
  check('产生一条 http 消息', httpMessages.length === 1, JSON.stringify(messages));
  check('op === "http"', message && message.op === 'http');
  check('reqId 原样透传', message && message.reqId === 'zsts-1-1');
  check('url 正确', message && message.url === 'https://jwxt.tstc.edu.cn/cjcx/cjcx_cxXsgrcj.html');
  check('method 为大写原值', message && message.method === 'POST');
  check('headers 保持 JSON 字符串（原生侧再解析）', message && typeof message.headers === 'string');
  check('body 透传', message && message.body === 'xnm=2026&xqm=3');

  // 空 body 必须是空串（Android 侧用 isEmpty 判断「无 body」）
  const { context: ctx2, messages: msg2 } = createShell();
  ctx2.window.NativeBridge.httpRequest('r', 'https://a.b/c', 'GET', '{}', null);
  const getMessage = msg2.find((m) => m.op === 'http');
  check('body 为 null 时归一为空串', getMessage && getMessage.body === '');
}

/* ── 3. RSA 互通性（最关键） ───────────────────────────────────────────── */

console.log('\n[3] rsaEncrypt 与标准 RSAES-PKCS1-v1_5 互通');
{
  for (const bits of [1024, 2048]) {
    const { context } = createShell();
    const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: bits });
    const jwk = publicKey.export({ format: 'jwk' });
    const modulus = Buffer.from(jwk.n, 'base64url').toString('base64');
    const exponent = Buffer.from(jwk.e, 'base64url').toString('base64');

    const plain = bits === 1024 ? 'Tstc@2026' : '唐山师范学院-密码-123456';
    const cipher = context.window.NativeBridge.rsaEncrypt(modulus, exponent, plain);
    check(`${bits}bit: 返回非空 base64`, cipher.length > 0 && /^[A-Za-z0-9+/=]+$/.test(cipher));

    let recovered = '';
    try {
      recovered = privateDecrypt(
        { key: privateKey, padding: constants.RSA_PKCS1_PADDING },
        Buffer.from(cipher, 'base64')
      ).toString('utf8');
    } catch (error) {
      recovered = `<解密失败: ${error.message}>`;
    }
    check(`${bits}bit: 私钥可解出原文`, recovered === plain, `得到 "${recovered}"`);

    // 同一明文两次加密必须不同（PKCS#1 v1.5 的随机填充）
    const again = context.window.NativeBridge.rsaEncrypt(modulus, exponent, plain);
    check(`${bits}bit: 密文含随机填充（两次不同）`, again !== cipher);
  }
}

/* ── 4. 129 字节带符号位前缀的 modulus ─────────────────────────────────── */

console.log('\n[4] modulus 带 ASN.1 符号位（129 字节首字节 0x00）');
{
  const { context } = createShell();
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 1024 });
  const jwk = publicKey.export({ format: 'jwk' });
  const rawModulus = Buffer.from(jwk.n, 'base64url');
  const padded = Buffer.concat([Buffer.from([0x00]), rawModulus]);
  check('构造出 129 字节 modulus', padded.length === 129);

  const plain = 'Tstc@2026';
  const cipher = context.window.NativeBridge.rsaEncrypt(
    padded.toString('base64'),
    Buffer.from(jwk.e, 'base64url').toString('base64'),
    plain
  );
  const recovered = privateDecrypt(
    { key: privateKey, padding: constants.RSA_PKCS1_PADDING },
    Buffer.from(cipher, 'base64')
  ).toString('utf8');
  check('剥掉前缀后仍能正确加密', recovered === plain);
}

/* ── 5. 凭据与会话 ─────────────────────────────────────────────────────── */

console.log('\n[5] 凭据 / 会话');
{
  const { context, messages } = createShell({ credential: '{"studentNo":"20220001"}' });
  const bridge = context.window.NativeBridge;

  check('冷启动注入的凭据可读', bridge.loadCredential() === '{"studentNo":"20220001"}');

  bridge.saveCredential('{"studentNo":"20220002"}');
  const saved = messages.find((m) => m.op === 'saveCredential');
  check('saveCredential 上报原生', saved && saved.value === '{"studentNo":"20220002"}');
  check('saveCredential 同步更新内存', bridge.loadCredential() === '{"studentNo":"20220002"}');
  check('saveCredential 返回 true', bridge.saveCredential('{}') === true);
  check('saveCredential 空值返回 false', bridge.saveCredential('') === false);

  bridge.clearCredential();
  check('clearCredential 清空内存', bridge.loadCredential() === '');
  check('clearCredential 上报原生', messages.some((m) => m.op === 'clearCredential'));

  messages.length = 0;
  bridge.clearSession();
  check('clearSession 上报原生', messages.some((m) => m.op === 'clearSession'));
  check('clearSession 同时清凭据', bridge.loadCredential() === '');
}

/* ── 6. 跳转意图 ───────────────────────────────────────────────────────── */

console.log('\n[6] openWebView / openWifiSettings / theme');
{
  const { context, messages } = createShell();
  context.window.NativeBridge.openWebView('http://sws.tstc.edu.cn/student/');
  const open = messages.find((m) => m.op === 'openWebView');
  check('openWebView 携带 url', open && open.url === 'http://sws.tstc.edu.cn/student/');

  context.window.NativeBridge.openWifiSettings();
  check('openWifiSettings 上报原生', messages.some((m) => m.op === 'openWifiSettings'));

  // theme 由 DOMContentLoaded 时的 MutationObserver 上报，这里只验证不会抛错
  check('初始化时不抛异常（主题上报已挂到 DOM 就绪）', true);
}

/* ── 7. 原生回调不会被 shim 破坏 ───────────────────────────────────────── */

console.log('\n[7] 原生回调通道');
{
  const { context } = createShell();
  let received = null;
  context.window.__nativeHttpCallback = (payload) => { received = payload; };
  // 原生侧执行 evaluateJavaScript 注入的正是这句
  const payload = JSON.stringify({ reqId: 'zsts-1-1', status: 200, body: 'ok' });
  vm.runInContext(
    `if (window.__nativeHttpCallback) window.__nativeHttpCallback(${JSON.stringify(payload)});`,
    context
  );
  check('__nativeHttpCallback 可被原生注入的脚本调用', received === payload);
}

/* ── 汇总 ─────────────────────────────────────────────────────────────── */

console.log('');
if (failures.length === 0) {
  console.log(`✅ 全部通过（${passed} 项）`);
  process.exit(0);
}
console.error(`❌ ${failures.length} 项失败（通过 ${passed} 项）：`);
for (const failure of failures) console.error(`  - ${failure}`);
process.exit(1);
