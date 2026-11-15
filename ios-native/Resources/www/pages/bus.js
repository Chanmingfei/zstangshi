/**
 * bus.js —— 实时公交（iOS 套壳新增，V1.2.0 web 版原无此页）
 *
 * 数据源：车来了（Chelaile）公开接口。协议已用 Node 实测打通（见仓库 scripts/probe-chelaile.mjs）：
 *   · 签名：按参数插入顺序拼 `"k"="v"&...` + 盐 `qwihrnbtmj`，MD5 小写，键名 cryptoSign
 *   · 响应：`**YGKJ{"jsonr":{"data":{"encryptResult":"..."}}}`，AES-256/ECB/PKCS7 解密
 *   · 密钥：`FF32AE65FBFD19414EAAFF6291A54B42`（微信小程序通道，ASCII 32 字节）
 *
 * 定位：优先 navigator.geolocation（WKWebView 里可用，iOS 壳已配定位权限），
 *  拿不到时回落到唐山市中心（39.630, 118.180），仍可搜索与查看线路。
 *
 * 本页不接 store 的 slots（公交不依赖教务登录），状态自管理。
 * 风格沿用 HIG + 本工程 tokens.css 的 CSS 变量，图标用 core/icons.js。
 */

import { h, qs, clear, setText, on, escapeHtml } from '../core/dom.js';
import { toast } from '../core/toast.js';
import { icon } from '../core/icons.js';
import { emptyState } from '../components/emptyState.js';
import { skeletonCard } from '../components/skeleton.js';

/* ═══════════════ 车来了协议（纯 JS 实现，零原生依赖） ═══════════════ */

const BASE = 'https://web.chelaile.net.cn';
const API = `${BASE}/api`;
const SALT = 'qwihrnbtmj';
const KEY_WX = 'FF32AE65FBFD19414EAAFF6291A54B42';
const KEY_H5 = '422556651C7F7B2B5C266EED06068230';

const UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148';
const REFERER = 'https://servicewechat.com/wx71d589ea01ce3321/814/page-frame.html';

/** 唐山默认坐标（定位失败时的兜底） */
const TSTC = { lat: 39.630, lng: 118.180, cityId: '024', name: '唐山' };

/** MD5（Web Crypto 不支持，手写 RFC 1321）。返回 32 位小写十六进制。 */
function md5Hex(str) {
  const bytes = new TextEncoder().encode(str);
  const n = bytes.length;
  const padded = new Uint8Array(((n + 8) >> 6 << 6) + 64);
  padded.set(bytes);
  padded[n] = 0x80;
  const words = new Uint32Array(padded.buffer);
  // 原始字节长度（按位），用两个 32 位拼 64 位大端写进最后 8 字节
  const bitLenLo = (n << 3) >>> 0;
  const bitLenHi = Math.floor(n / 536870912);
  words[words.length - 2] = bitLenLo;
  words[words.length - 1] = bitLenHi;

  let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;

  const S = [
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
  ];
  const K = new Uint32Array(64);
  for (let i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) >>> 0;

  const rotl = (x, c) => ((x << c) | (x >>> (32 - c))) >>> 0;

  for (let off = 0; off < words.length; off += 16) {
    const M = words.subarray(off, off + 16);
    let A = a0, B = b0, C = c0, D = d0;
    for (let i = 0; i < 64; i++) {
      let F, g;
      if (i < 16) { F = (B & C) | (~B & D); g = i; }
      else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
      else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
      else { F = C ^ (B | ~D); g = (7 * i) % 16; }
      const tmp = D;
      D = C; C = B;
      B = (B + rotl((A + F + K[i] + M[g]) >>> 0, S[i])) >>> 0;
      A = tmp;
    }
    a0 = (a0 + A) >>> 0; b0 = (b0 + B) >>> 0; c0 = (c0 + C) >>> 0; d0 = (d0 + D) >>> 0;
  }

  // MD5 规范：每个 32 位 state 按**小端字节序**输出（低字节在前）。
  const le = (x) => {
    const b0 = (x & 0xff).toString(16).padStart(2, '0');
    const b1 = ((x >>> 8) & 0xff).toString(16).padStart(2, '0');
    const b2 = ((x >>> 16) & 0xff).toString(16).padStart(2, '0');
    const b3 = ((x >>> 24) & 0xff).toString(16).padStart(2, '0');
    return b0 + b1 + b2 + b3;
  };
  return le(a0) + le(b0) + le(c0) + le(d0);
}

/** 车来了参数签名：按插入顺序 `"k"="v"&` + 盐，MD5 小写。 */
function chelaileSign(params) {
  const s = params.map(([k, v]) => `"${k}"="${v}"`).join('&') + SALT;
  return md5Hex(s);
}

/** 基础参数（顺序即签名顺序，不可乱）。 */
function baseParams() {
  return [
    ['s', 'h5'], ['wxs', 'wx_app'], ['sign', '1'], ['h5RealData', '1'],
    ['v', '3.11.28'], ['src', 'weixinapp_cx'], ['ctm_mp', 'mp_wx'], ['vc', '2'],
    ['favoriteGray', '1'], ['gpstype', 'wgs'], ['geo_type', 'wgs'], ['scene', '1256'],
  ];
}

/* ═══════════════ AES-256-ECB 解密（纯 JS，紧凑实现） ═══════════════ */

const SBOX = (() => {
  let p = 1, q = 1, s = new Uint8Array(256);
  while (true) {
    p = (p ^ ((p << 1) & 0xff) ^ (p & 0x80 ? 0x1b : 0)) & 0xff;
    q ^= q << 1; q ^= q << 2; q ^= q << 4;
    q &= 0xff; if (q & 0x80) q ^= 0x09;
    const x = q ^ ((q << 1) & 0xff) ^ ((q << 2) & 0xff) ^ ((q << 3) & 0xff) ^ ((q << 4) & 0xff);
    s[p] = (x & 0xff) ^ 0x63;
    if (p === 1) break;
  }
  s[0] = 0x63;
  return s;
})();

const INV_SBOX = (() => {
  const inv = new Uint8Array(256);
  for (let i = 0; i < 256; i++) inv[SBOX[i]] = i;
  return inv;
})();

function xtime(x) { return ((x << 1) ^ (x & 0x80 ? 0x1b : 0)) & 0xff; }
function mul(a, b) {
  let r = 0;
  for (let i = 0; i < 8; i++) {
    if (b & 1) r ^= a;
    const hi = a & 0x80;
    a = ((a << 1) & 0xff);
    if (hi) a ^= 0x1b;
    b >>= 1;
  }
  return r;
}

/** 生成 256 位密钥的轮次键（11 × 16 字节）。 */
function expandKey256(key) {
  const Nk = 8, Nr = 14;
  const w = new Uint32Array(4 * (Nr + 1));
  for (let i = 0; i < Nk; i++) w[i] = (key[4 * i] << 24) | (key[4 * i + 1] << 16) | (key[4 * i + 2] << 8) | key[4 * i + 3];
  const rcon = [0x01000000, 0x02000000, 0x04000000, 0x08000000, 0x10000000, 0x20000000, 0x40000000, 0x80000000];
  let i = Nk;
  while (i < 4 * (Nr + 1)) {
    let temp = w[i - 1];
    if (i % Nk === 0) {
      temp = (SBOX[(temp >>> 16) & 0xff] << 24) | (SBOX[(temp >>> 8) & 0xff] << 16) | (SBOX[temp & 0xff] << 8) | SBOX[(temp >>> 24) & 0xff];
      temp ^= rcon[(i / Nk) - 1];
    } else if (i % Nk === 4) {
      temp = (SBOX[(temp >>> 24) & 0xff] << 24) | (SBOX[(temp >>> 16) & 0xff] << 16) | (SBOX[(temp >>> 8) & 0xff] << 8) | SBOX[temp & 0xff];
    }
    w[i] = w[i - Nk] ^ temp;
    i++;
  }
  return w;
}

/** AES-256 解密单块（16 字节），反向轮次。 */
function decryptBlock(state, w) {
  let s = new Uint8Array(16);
  for (let i = 0; i < 4; i++) {
    const t = w[4 * 14 + i];
    s[4 * i] ^= (t >>> 24) & 0xff;
    s[4 * i + 1] ^= (t >>> 16) & 0xff;
    s[4 * i + 2] ^= (t >>> 8) & 0xff;
    s[4 * i + 3] ^= t & 0xff;
  }
  for (let round = 13; round >= 1; round--) {
    // InvShiftRows
    const tmp = new Uint8Array(16);
    tmp[0] = s[0]; tmp[4] = s[4]; tmp[8] = s[8]; tmp[12] = s[12];
    tmp[1] = s[13]; tmp[5] = s[1]; tmp[9] = s[5]; tmp[13] = s[9];
    tmp[2] = s[10]; tmp[6] = s[14]; tmp[10] = s[2]; tmp[14] = s[6];
    tmp[3] = s[7]; tmp[7] = s[11]; tmp[11] = s[15]; tmp[15] = s[3];
    s = tmp;
    // InvSubBytes
    for (let i = 0; i < 16; i++) s[i] = INV_SBOX[s[i]];
    // AddRoundKey
    for (let i = 0; i < 4; i++) {
      const t = w[4 * round + i];
      s[4 * i] ^= (t >>> 24) & 0xff;
      s[4 * i + 1] ^= (t >>> 16) & 0xff;
      s[4 * i + 2] ^= (t >>> 8) & 0xff;
      s[4 * i + 3] ^= t & 0xff;
    }
    // InvMixColumns
    for (let c = 0; c < 4; c++) {
      const a0 = s[4 * c], a1 = s[4 * c + 1], a2 = s[4 * c + 2], a3 = s[4 * c + 3];
      s[4 * c] = mul(a0, 0x0e) ^ mul(a1, 0x0b) ^ mul(a2, 0x0d) ^ mul(a3, 0x09);
      s[4 * c + 1] = mul(a1, 0x0e) ^ mul(a2, 0x0b) ^ mul(a3, 0x0d) ^ mul(a0, 0x09);
      s[4 * c + 2] = mul(a2, 0x0e) ^ mul(a3, 0x0b) ^ mul(a0, 0x0d) ^ mul(a1, 0x09);
      s[4 * c + 3] = mul(a3, 0x0e) ^ mul(a0, 0x0b) ^ mul(a1, 0x0d) ^ mul(a2, 0x09);
    }
  }
  // 最后一轮（无 MixColumns）
  const tmp = new Uint8Array(16);
  tmp[0] = s[0]; tmp[4] = s[4]; tmp[8] = s[8]; tmp[12] = s[12];
  tmp[1] = s[13]; tmp[5] = s[1]; tmp[9] = s[5]; tmp[13] = s[9];
  tmp[2] = s[10]; tmp[6] = s[14]; tmp[10] = s[2]; tmp[14] = s[6];
  tmp[3] = s[7]; tmp[7] = s[11]; tmp[11] = s[15]; tmp[15] = s[3];
  s = tmp;
  for (let i = 0; i < 16; i++) s[i] = INV_SBOX[s[i]];
  for (let i = 0; i < 4; i++) {
    const t = w[4 * 0 + i];
    s[4 * i] ^= (t >>> 24) & 0xff;
    s[4 * i + 1] ^= (t >>> 16) & 0xff;
    s[4 * i + 2] ^= (t >>> 8) & 0xff;
    s[4 * i + 3] ^= t & 0xff;
  }
  return s;
}

/** AES-256-ECB 解密（PKCS7 去填充）。keys 依次尝试，返回能解出以 { 或 [ 开头的明文。 */
function chelaileDecrypt(b64) {
  const normalized = b64.replace(/\s/g, '').replace(/-/g, '+').replace(/_/g, '/');
  let padded = normalized;
  const rem = padded.length % 4;
  if (rem === 2) padded += '==';
  else if (rem === 3) padded += '=';
  let cipher;
  try {
    const bin = atob(padded);
    cipher = Uint8Array.from(bin, (c) => c.charCodeAt(0));
  } catch { return null; }

  for (const keyStr of [KEY_WX, KEY_H5]) {
    const key = new TextEncoder().encode(keyStr);
    const w = expandKey256(key);
    const outLen = cipher.length;
    const blocks = new Uint8Array(Math.ceil(outLen / 16) * 16);
    blocks.set(cipher);
    let plain = new Uint8Array(0);
    for (let off = 0; off < blocks.length; off += 16) {
      const block = blocks.subarray(off, off + 16);
      const dec = decryptBlock(block, w);
      const merged = new Uint8Array(plain.length + 16);
      merged.set(plain); merged.set(dec, plain.length);
      plain = merged;
    }
    // PKCS7 去填充
    const last = plain[plain.length - 1];
    let len = plain.length;
    if (last > 0 && last <= 16) {
      let valid = true;
      for (let i = 0; i < last; i++) if (plain[plain.length - 1 - i] !== last) { valid = false; break; }
      if (valid) len -= last;
    }
    let text = '';
    try { text = new TextDecoder('utf-8').decode(plain.subarray(0, len)); } catch {}
    const t = text.trim();
    if (t.startsWith('{') || t.startsWith('[')) return t;
  }
  return null;
}

/** 车来了响应解密：优先走原生桥（CommonCrypto 可靠），无桥时退回纯 JS AES。 */
async function decryptResult(b64) {
  // 优先原生桥
  try {
    if (typeof window !== 'undefined' && window.NativeBridge
      && typeof window.NativeBridge.aesDecrypt === 'function') {
      const v = await window.NativeBridge.aesDecrypt(b64);
      if (v && (v.startsWith('{') || v.startsWith('['))) return v;
    }
  } catch { /* 回落纯 JS */ }
  return chelaileDecrypt(b64); // 纯 JS 兜底（浏览器预览用）
}

/* ═══════════════ 车来了接口封装 ═══════════════ */

async function chelaileGet(path, extraParams) {
  const params = baseParams().concat(extraParams || []);
  const sign = chelaileSign(params);
  const q = params.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&') + `&cryptoSign=${sign}`;
  const res = await fetch(`${API}${path}?${q}`, {
    headers: { 'User-Agent': UA, Referer: REFERER, xweb_xhr: '1', Accept: '*/*' },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const raw = await res.text();
  const start = raw.indexOf('{');
  if (start < 0) throw new Error('响应不是 JSON');
  let depth = 0, inStr = false, esc = false, end = start;
  for (let i = start; i < raw.length; i++) {
    const c = raw[i];
    if (inStr) { if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') inStr = false; continue; }
    if (c === '"') inStr = true;
    else if (c === '{') depth++;
    else if (c === '}') { depth--; if (depth === 0) { end = i + 1; break; } }
  }
  const env = JSON.parse(raw.slice(start, end));
  const jsonr = env.jsonr || {};
  const data = jsonr.data !== undefined ? jsonr.data : env.data;
  if (data && typeof data === 'object' && data.encryptResult) {
    const dec = await decryptResult(data.encryptResult);
    if (dec) return JSON.parse(dec);
  }
  if (data && typeof data === 'string' && data) {
    const dec = await decryptResult(data);
    if (dec) return JSON.parse(dec);
  }
  if (data && typeof data === 'object') return data;
  return jsonr || env;
}

/* ═══════════════ 定位 ═══════════════ */

function locate() {
  return new Promise((resolve) => {
    if (typeof navigator !== 'undefined' && navigator.geolocation) {
      const timer = setTimeout(() => resolve(null), 6000);
      navigator.geolocation.getCurrentPosition(
        (pos) => { clearTimeout(timer); resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude }); },
        () => { clearTimeout(timer); resolve(null); },
        { enableHighAccuracy: false, timeout: 5500, maximumAge: 30000 }
      );
    } else {
      resolve(null);
    }
  });
}

/* ═══════════════ 页面 ═══════════════ */

export default {
  name: 'bus',
  title: '实时公交',
  requiresAuth: false,

  render() {
    const searchInput = h('input', {
      className: 'p-bus__search-input',
      type: 'search',
      placeholder: '搜索线路 / 站点',
      enterkeyhint: 'search',
    });
    const status = h('p', { className: 'p-bus__status', text: '' });
    const list = h('div', { className: 'p-bus__list' }, skeletonCard({ lines: 3 }));

    const el = h('div', { className: 'page p-bus' }, [
      h('div', { className: 'p-bus__search' }, [
        icon('search', 18, { className: 'p-bus__search-icon' }) && h('span', { className: 'p-bus__search-icon', html: icon('search', 18) }),
        searchInput,
      ]),
      status,
      list,
    ]);
    el.__view = { searchInput, status, list };
    return el;
  },

  mount(el) {
    const view = el.__view;
    let cancelled = false;

    async function loadNearby() {
      setText(view.status, '正在定位并查找附近站点…');
      const pos = (await locate()) || TSTC;
      const located = pos !== TSTC;
      setText(view.status, `${located ? '当前位置' : '默认位置（未授权定位）'} · 正在加载附近公交…`);

      try {
        const data = await chelaileGet('/bus/stop!encryptedHomePage.action', [
          ['cityId', TSTC.cityId], ['localCityId', 'undefined'],
          ['lat', String(pos.lat)], ['lng', String(pos.lng)],
        ]);
        if (cancelled) return;
        const stations = (data && data.nearSts) || [];
        if (!stations.length) {
          view.list.replaceChildren(emptyState({
            kind: 'empty',
            title: '附近没有查到公交站点',
            description: located ? '可以试试搜索框直接搜线路名' : '可下拉或点搜索查询具体线路',
            actionLabel: '重新定位',
            onAction: () => loadNearby(),
          }));
          setText(view.status, located ? '附近暂无可显示站点' : '未获取到定位，已用默认位置');
          return;
        }
        setText(view.status, `${located ? '附近' : '默认位置附近'} ${stations.length} 个站点`);
        view.list.replaceChildren(
          ...stations.slice(0, 12).map((st) => renderStation(st))
        );
      } catch (err) {
        if (cancelled) return;
        view.list.replaceChildren(emptyState({
          kind: 'error',
          title: '公交数据加载失败',
          description: String((err && err.message) || err),
          actionLabel: '重试',
          onAction: () => loadNearby(),
        }));
        setText(view.status, '加载失败');
      }
    }

    async function search(keyword) {
      const kw = String(keyword || '').trim();
      if (!kw) { await loadNearby(); return; }
      setText(view.status, `搜索“${kw}”…`);
      try {
        const data = await chelaileGet('/bus/query!nSearch.action', [
          ['cityId', TSTC.cityId], ['key', kw],
        ]);
        if (cancelled) return;
        // 搜索结果结构：lines[] 与 stations[]
        const lines = (data && (data.lines || data.nsList || [])) || [];
        const stations = (data && (data.stations || data.nsStationList || data.nStations || [])) || [];
        const hits = [...lines, ...stations];
        if (!hits.length) {
          view.list.replaceChildren(emptyState({ kind: 'empty', title: '没有匹配结果', description: '换个关键词试试（如“K1”“唐山学院”）' }));
          setText(view.status, `“${kw}”无结果`);
          return;
        }
        setText(view.status, `“${kw}” ${hits.length} 条结果`);
        view.list.replaceChildren(...hits.slice(0, 20).map((item) => renderSearchHit(item)));
      } catch (err) {
        if (cancelled) return;
        view.list.replaceChildren(emptyState({ kind: 'error', title: '搜索失败', description: String((err && err.message) || err), actionLabel: '重试', onAction: () => search(kw) }));
      }
    }

    const onSearch = () => search(view.searchInput.value);
    const unbind = on(view.searchInput, 'keydown', (e) => { if (e.key === 'Enter') onSearch(); });
    const searchBtn = h('button', { className: 'c-btn c-btn--primary p-bus__go', onClick: onSearch }, '搜索');
    view.searchInput.parentNode.appendChild(searchBtn);

    loadNearby();

    return () => {
      cancelled = true;
      unbind();
    };
  },
};

/* ═══════════════ 渲染辅助 ═══════════════ */

function renderStation(st) {
  const name = st.stationName || st.name || st.sn || '站点';
  const lines = (st.lines || st.nsLines || []).slice(0, 3);
  const lineText = lines.length
    ? lines.map((l) => l.lineName || l.name || l.ln || '').filter(Boolean).join(' · ')
    : '';
  return h('div', { className: 'p-bus__card' }, [
    h('div', { className: 'p-bus__card-title' }, [
      icon('location', 18),
      h('span', { text: name }),
    ]),
    lineText ? h('p', { className: 'p-bus__card-sub', text: lineText }) : null,
  ]);
}

function renderSearchHit(item) {
  // 线路
  if (item.lineName || item.lineId || item.ln) {
    const name = item.lineName || item.name || item.ln || '线路';
    const sub = [item.fromStationName, item.toStationName, item.startSn, item.endSn]
      .filter(Boolean).join(' → ');
    return h('div', { className: 'p-bus__card' }, [
      h('div', { className: 'p-bus__card-title' }, [
        icon('bus', 18),
        h('span', { text: name }),
        h('span', { className: 'p-bus__tag', text: '线路' }),
      ]),
      sub ? h('p', { className: 'p-bus__card-sub', text: sub }) : null,
    ]);
  }
  // 站点
  const name = item.stationName || item.name || item.sn || '站点';
  const lines = (item.lines || item.nsLines || []).slice(0, 3);
  const lineText = lines.map((l) => l.lineName || l.name || l.ln || '').filter(Boolean).join(' · ');
  return h('div', { className: 'p-bus__card' }, [
    h('div', { className: 'p-bus__card-title' }, [
      icon('location', 18),
      h('span', { text: name }),
      h('span', { className: 'p-bus__tag', text: '站点' }),
    ]),
    lineText ? h('p', { className: 'p-bus__card-sub', text: lineText }) : null,
  ]);
}