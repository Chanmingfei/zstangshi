/**
 * bridge-shim.js —— H5 与 iOS 原生之间的桥接层（WKUserScript 注入，documentStart）
 *
 * 为什么需要它：H5 侧（web/core/transport.js、web/data/jwxtClient.js 等）只认
 * `window.NativeBridge`，这是 Android 用 @JavascriptInterface 挂上去的对象。
 * iOS 的 WKWebView 没有同步 JS 接口，只能用 messageHandlers 异步通信。
 * 这里在页面脚本执行前**先造一个同名同签名的 window.NativeBridge**，
 * 于是 web/ 下的代码一行都不用改，detectEnv() 自然判定为原生通道。
 *
 * 与 Android NativeBridge.java 的逐条对应（顺序、参数、返回类型必须一致）：
 *   1. isNative()                        → boolean true
 *   2. getAppVersion()                   → string
 *   3. httpRequest(reqId,url,method,headersJson,body) → 回调 window.__nativeHttpCallback
 *   4. rsaEncrypt(modulus,exponent,plain) → string（**同步**，纯 JS BigInt 实现）
 *   5. clearSession()                    → void
 *   6. saveCredential(json)              → boolean
 *   7. loadCredential()                  → string（空串表示无）
 *   8. clearCredential()                 → boolean
 *   9. openWebView(url)                  → void
 *  10. openWifiSettings()                → void
 *
 * 第 4 条为什么必须自己算：jwxtClient.js:200 是 `const value = bridge.rsaEncrypt(...)`，
 * 直接拿返回值用（同步）。WKWebView 的 messageHandlers 天生异步，无法返回值，
 * 所以 RSA PKCS#1 v1.5 在 JS 里用 BigInt 实现，零往返、零延迟，也不依赖 secure context。
 *
 * 本文件不 import 任何模块，可直接被 Node 单测加载（tools/test-bridge.mjs）。
 */
(function () {
  'use strict';

  var NATIVE = typeof window.__ZSTS_NATIVE === 'object' && window.__ZSTS_NATIVE ? window.__ZSTS_NATIVE : {};
  var APP_VERSION = String(NATIVE.appVersion || '1.2.0');

  /** 内存态凭据：冷启动时由原生从 Keychain 读出、随 UserScript 一起注入 */
  var credential = String(NATIVE.credential || '');

  function handler(name) {
    try {
      var h = window.webkit && window.webkit.messageHandlers;
      return h && h[name] ? h[name] : null;
    } catch (e) {
      return null;
    }
  }

  function post(payload) {
    var h = handler('zsts');
    if (!h) return false;
    try {
      h.postMessage(payload);
      return true;
    } catch (e) {
      return false;
    }
  }

  /* ── Base64 / BigInt 工具（RSA 用，不依赖外部库） ────────────────────── */

  function b64ToBytes(input) {
    var s = String(input == null ? '' : input)
      .replace(/[\s\r\n]+/g, '')
      .replace(/-/g, '+')
      .replace(/_/g, '/');
    while (s.length % 4 !== 0) s += '=';
    var bin = atob(s);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i += 1) out[i] = bin.charCodeAt(i);
    return out;
  }

  function bytesToB64(bytes) {
    var bin = '';
    for (var i = 0; i < bytes.length; i += 1) bin += String.fromCharCode(bytes[i]);
    return btoa(bin);
  }

  function bytesToBigInt(bytes) {
    var hex = '';
    for (var i = 0; i < bytes.length; i += 1) hex += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
    if (!hex) return BigInt(0);
    return BigInt('0x' + hex);
  }

  function bigIntToBytes(value, length) {
    var hex = value.toString(16);
    if (hex.length % 2 === 1) hex = '0' + hex;
    while (hex.length < length * 2) hex = '00' + hex;
    var out = new Uint8Array(hex.length / 2);
    for (var i = 0; i < out.length; i += 1) out[i] = parseInt(hex.substr(i * 2, 2), 16);
    return out;
  }

  /** 模幂：平方-乘，教务系统的公钥指数固定 65537，64 轮以内 */
  function modPow(base, exponent, modulus) {
    var result = BigInt(1);
    var b = base % modulus;
    var e = exponent;
    var zero = BigInt(0);
    var one = BigInt(1);
    while (e > zero) {
      if (e & one) result = (result * b) % modulus;
      e >>= one;
      b = (b * b) % modulus;
    }
    return result;
  }

  function randomNonZeroBytes(count) {
    var out = new Uint8Array(count);
    var filled = 0;
    while (filled < count) {
      var buf = new Uint8Array(count - filled);
      if (typeof crypto !== 'undefined' && crypto.getRandomValues) crypto.getRandomValues(buf);
      else for (var i = 0; i < buf.length; i += 1) buf[i] = Math.floor(Math.random() * 256);
      for (var j = 0; j < buf.length; j += 1) {
        // PKCS#1 v1.5 的填充串不能含 0x00
        if (buf[j] !== 0) out[filled++] = buf[j];
      }
    }
    return out;
  }

  /**
   * RSAES-PKCS1-v1_5 加密，等价 Android 的 Cipher("RSA/ECB/PKCS1Padding")。
   * 失败返回空串 —— jwxtClient 会明确报错，绝不明文提交密码。
   */
  function rsaEncrypt(modulus, exponent, plain) {
    try {
      var mod = b64ToBytes(modulus);
      // 公钥 modulus 偶尔带一个 ASN.1 符号位（129 字节且首字节 0x00），剥掉
      if (mod.length === 129 && mod[0] === 0) mod = mod.subarray(1);
      var exp = b64ToBytes(exponent);
      var n = bytesToBigInt(mod);
      var e = bytesToBigInt(exp);
      if (n === BigInt(0) || e === BigInt(0)) return '';

      var message = new TextEncoder().encode(String(plain == null ? '' : plain));
      var k = mod.length;
      if (message.length > k - 11) return '';

      var padding = randomNonZeroBytes(k - 3 - message.length);
      var block = new Uint8Array(k);
      block[0] = 0x00;
      block[1] = 0x02;
      block.set(padding, 2);
      block[2 + padding.length] = 0x00;
      block.set(message, 3 + padding.length);

      var cipher = modPow(bytesToBigInt(block), e, n);
      return bytesToB64(bigIntToBytes(cipher, k));
    } catch (err) {
      return '';
    }
  }

  /* ── 9 个原生接口 ─────────────────────────────────────────────────── */

  var bridge = {
    isNative: function () {
      return handler('zsts') ? true : false;
    },

    getAppVersion: function () {
      return APP_VERSION;
    },

    /**
     * 异步 HTTP。参数顺序与 Android 一致：url 在 method 之前。
     * 原生回调用 window.__nativeHttpCallback 触发，transport.js 负责配对 reqId。
     */
    httpRequest: function (reqId, url, method, headersJson, body) {
      post({
        op: 'http',
        reqId: String(reqId == null ? '' : reqId),
        url: String(url == null ? '' : url),
        method: String(method == null ? 'GET' : method),
        headers: String(headersJson == null ? '{}' : headersJson),
        body: body == null ? '' : String(body),
      });
    },

    rsaEncrypt: rsaEncrypt,

    clearSession: function () {
      credential = '';
      window.__ZSTS_NATIVE_CREDENTIAL = '';
      post({ op: 'clearSession' });
    },

    saveCredential: function (json) {
      var value = String(json == null ? '' : json);
      if (!value) return false;
      credential = value;
      window.__ZSTS_NATIVE_CREDENTIAL = value;
      post({ op: 'saveCredential', value: value });
      return true;
    },

    loadCredential: function () {
      return credential || '';
    },

    clearCredential: function () {
      credential = '';
      window.__ZSTS_NATIVE_CREDENTIAL = '';
      post({ op: 'clearCredential' });
      return true;
    },

    openWebView: function (url) {
      post({ op: 'openWebView', url: String(url == null ? '' : url) });
    },

    openWifiSettings: function () {
      post({ op: 'openWifiSettings' });
    },
  };

  try {
    Object.defineProperty(window, 'NativeBridge', {
      value: bridge,
      writable: false,
      configurable: false,
      enumerable: true,
    });
  } catch (err) {
    window.NativeBridge = bridge;
  }

  /* ── 状态栏样式跟随 App 主题（iOS 独有，Android 由 themes.xml 处理） ── */

  function reportTheme() {
    var theme = document.documentElement.getAttribute('data-theme');
    post({ op: 'theme', value: theme === 'dark' ? 'dark' : 'light' });
  }

  function observeTheme() {
    reportTheme();
    try {
      var observer = new MutationObserver(function (records) {
        for (var i = 0; i < records.length; i += 1) {
          if (records[i].attributeName === 'data-theme') {
            reportTheme();
            return;
          }
        }
      });
      observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    } catch (err) {
      /* MutationObserver 不可用：状态栏保持初始样式，不影响功能 */
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', observeTheme, { once: true });
  } else {
    observeTheme();
  }

  window.__ZSTS_IOS_BRIDGE__ = { rsaEncrypt: rsaEncrypt, version: APP_VERSION };
})();
