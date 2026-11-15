// probe-jwxt.mjs -- probe the real Zhengfang (正方) login flow that the iOS app performs,
// over BOTH https and http, and report exactly where it fails.
//
// Usage: node scripts/probe-jwxt.mjs [studentNo] [password]
//
// This mirrors JwClient.swift step by step:
//   1. GET  /xtgl/login_slogin.html          -> csrftoken
//   2. GET  /xtgl/login_getPublicKey.html    -> modulus / exponent
//   3. RSA/PKCS1 encrypt password
//   4. POST /xtgl/login_slogin.html          -> JSESSIONID
//   5. GET  /xtgl/index_initMenu.html        -> contains gnmkdm ?

import crypto from 'node:crypto';

const HOST = 'jwxt.tstc.edu.cn';
const UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';

const PATH = {
  login: '/xtgl/login_slogin.html',
  publicKey: '/xtgl/login_getPublicKey.html',
  menu: '/xtgl/index_initMenu.html',
  kbPage: '/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default',
  kbPageAlt: '/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default',
  kbData: '/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151',
};

function rsaEncrypt(modulusB64, exponentB64, text) {
  const n = Buffer.from(modulusB64, 'base64');
  const e = Buffer.from(exponentB64, 'base64');
  const key = crypto.createPublicKey({
    key: {
      kty: 'RSA',
      n: n.toString('base64url'),
      e: e.toString('base64url'),
    },
    format: 'jwk',
  });
  const out = crypto.publicEncrypt(
    { key, padding: crypto.constants.RSA_PKCS1_PADDING },
    Buffer.from(text, 'utf8')
  );
  return out.toString('base64');
}

async function probe(scheme, user, pass) {
  const origin = `${scheme}://${HOST}`;
  const jar = new Map();
  const log = [];

  function cookieHeader() {
    if (jar.size === 0) return '';
    return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
  }
  function absorb(res) {
    const raw = res.headers.getSetCookie?.() ?? [];
    for (const line of raw) {
      const first = line.split(';')[0];
      const eq = first.indexOf('=');
      if (eq <= 0) continue;
      const k = first.slice(0, eq).trim();
      const v = first.slice(eq + 1).trim();
      if (!k) continue;
      if (!v || v === 'deleted') jar.delete(k);
      else jar.set(k, v);
    }
  }

  async function req(method, path, body) {
    const url = origin + path;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 10000);
    const headers = {
      'User-Agent': UA,
      'Accept-Language': 'zh-CN,zh;q=0.9',
      Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    };
    const ch = cookieHeader();
    if (ch) headers.Cookie = ch;
    if (body) headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
    const t0 = Date.now();
    try {
      const res = await fetch(url, {
        method, headers, body,
        redirect: 'manual',
        signal: ctrl.signal,
      });
      clearTimeout(timer);
      absorb(res);
      const text = await res.text();
      return { ok: true, status: res.status, text, ms: Date.now() - t0, location: res.headers.get('location') };
    } catch (e) {
      clearTimeout(timer);
      return { ok: false, err: e.name === 'AbortError' ? 'TIMEOUT(10s)' : `${e.name}: ${e.message}`, ms: Date.now() - t0 };
    }
  }

  log.push(`\n===== ${scheme.toUpperCase()} 通道 =====`);

  // --- step 1
  const page = await req('GET', PATH.login);
  if (!page.ok) {
    log.push(`1. 登录页 FAIL (${page.ms}ms) ${page.err}`);
    return { scheme, failedAt: 1, log };
  }
  log.push(`1. 登录页 ${page.status} ${page.ms}ms len=${page.text.length}`);
  const csrf = page.text.match(/id="csrftoken"[^>]*value="([^"]*)"/)?.[1]
            ?? page.text.match(/name="csrftoken"[^>]*value="([^"]*)"/)?.[1] ?? '';
  if (!csrf) { log.push('   ✗ 找不到 csrftoken'); return { scheme, failedAt: 1, log }; }
  log.push(`   csrftoken = ${csrf.slice(0, 16)}…`);

  // --- step 2
  const keyRes = await req('GET', `${PATH.publicKey}?time=${Date.now()}`);
  if (!keyRes.ok) { log.push(`2. 公钥 FAIL ${keyRes.err}`); return { scheme, failedAt: 2, log }; }
  log.push(`2. 公钥 ${keyRes.status} len=${keyRes.text.length}`);
  let mod = '', exp = '';
  try {
    const j = JSON.parse(keyRes.text);
    mod = j.modulus ?? '';
    exp = j.exponent ?? '';
  } catch { /* not json */ }
  if (!mod || !exp) { log.push(`   ✗ 公钥为空: ${keyRes.text.slice(0, 200)}`); return { scheme, failedAt: 2, log }; }

  // --- step 3
  let enc;
  try { enc = rsaEncrypt(mod, exp, pass); }
  catch (e) { log.push(`3. RSA 加密失败: ${e.message}`); return { scheme, failedAt: 3, log }; }
  log.push(`3. RSA 加密 OK len=${enc.length}`);

  // --- step 4
  const form = new URLSearchParams({
    csrftoken: csrf, yhm: user, mm: enc, hidMm: enc, language: 'zh_CN', mmsfjm: '1',
  }).toString();
  const post = await req('POST', PATH.login, form);
  if (!post.ok) { log.push(`4. 登录 POST FAIL ${post.err}`); return { scheme, failedAt: 4, log }; }
  log.push(`4. 登录 POST ${post.status} ${post.ms}ms len=${post.text.length} location=${post.location ?? '(none)'}`);
  log.push(`   cookies: ${[...jar.keys()].join(', ') || '(none)'}`);
  if (post.text.includes('用户名或密码不正确')) {
    log.push('   ✗ 服务端明确回复「用户名或密码不正确」');
    return { scheme, failedAt: '4-pwd', log };
  }

  // --- step 5
  const menu = await req('GET', PATH.menu);
  if (!menu.ok) { log.push(`5. 菜单 FAIL ${menu.err}`); return { scheme, failedAt: 5, log }; }
  const kicked = menu.text.includes('login_slogin');
  const hasGnmk = menu.text.includes('gnmkdm');
  log.push(`5. 菜单 ${menu.status} len=${menu.text.length} 含gnmkdm=${hasGnmk} 被踢回登录=${kicked}`);
  if (kicked) { log.push('   ✗ 被踢回登录页（会话没建立）'); return { scheme, failedAt: 5, log }; }
  if (!hasGnmk) { log.push('   ✗ 菜单页没有 gnmkdm，无法确认登录'); return { scheme, failedAt: 5, log }; }

  log.push('   ✓ 登录成功');

  // --- step 6: 课表页
  for (const p of [PATH.kbPage, PATH.kbPageAlt]) {
    const kb = await req('GET', p);
    if (!kb.ok) { log.push(`6. 课表页 ${p} FAIL ${kb.err}`); continue; }
    const xnm = kb.text.match(/<select[^>]*(?:id|name)=["']xnm["'][\s\S]*?<\/select>/)?.[0];
    const hasXnm = !!xnm;
    log.push(`6. 课表页 ${kb.status} len=${kb.text.length} 有xnm下拉=${hasXnm} 被踢=${kb.text.includes('login_slogin')}`);
    if (hasXnm) { log.push('   ✓ 课表页可用'); break; }
  }

  return { scheme, failedAt: null, log };
}

async function main() {
  const user = process.argv[2] ?? '';
  const pass = process.argv[3] ?? '';

  console.log('探测目标:', HOST);
  console.log('学号:', user ? user.slice(0, 4) + '****' : '(未提供，只测连通性)');

  // 先测 TCP 连通
  for (const scheme of ['https', 'http']) {
    const port = scheme === 'https' ? 443 : 80;
    const t0 = Date.now();
    try {
      const r = await fetch(`${scheme}://${HOST}/`, { method: 'HEAD', redirect: 'manual' })
        .catch(e => { throw e; });
      console.log(`  ${scheme}:${port} -> HTTP ${r.status} (${Date.now() - t0}ms)`);
    } catch (e) {
      console.log(`  ${scheme}:${port} -> 失败 ${e.name} (${Date.now() - t0}ms)`);
    }
  }

  if (!user || !pass) {
    console.log('\n未提供账号密码，只测到连通性为止。');
    console.log('用法: node scripts/probe-jwxt.mjs <学号> <密码>');
    return;
  }

  // 优先 https，失败再 http（与 App 行为一致）
  for (const scheme of ['https', 'http']) {
    const r = await probe(scheme, user, pass);
    console.log(r.log.join('\n'));
    if (r.failedAt === null) {
      console.log(`\n结论：${scheme.toUpperCase()} 通道完全可用。`);
      return;
    }
    console.log(`\n结论：${scheme.toUpperCase()} 通道在某一步失败（${r.failedAt}）。`);
    if (scheme === 'https') console.log('尝试 HTTP 通道…');
  }
}

main().catch(e => { console.error('ERR', e); process.exit(1); });