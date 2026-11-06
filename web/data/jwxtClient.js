/**
 * jwxtClient.js —— 正方教务 HTTP 客户端（登录 + 请求 + 端点表）
 *
 * 从 remoteProvider.js 拆出来，只为两件事：守住单文件 400 行上限，以及让
 * 「地址怎么拼、密码怎么加密、失败怎么归一」与「哪个页面调哪个接口」互不干扰。
 *
 * 【双通道】core/transport.js：
 *   Android WebView → NativeBridge.httpRequest（本地代理直连，走系统证书策略）
 *   Web 预览        → 同源 /jwxt/ 正向代理（server.js），否则跨域必被拦
 * Cookie / JSESSIONID / route（多节点绑定）由通道侧维护，本文件不碰。
 *
 * 【不允许猜地址】docs/remote-api-map.md §3.3。已实测的写死，未实测的留 null，
 * 由 configureRemote({ endpoints }) 现场补，命中 null 的模块明确报 NOT_IMPLEMENTED。
 *
 * 【登录】§1.1/§1.2/§1.3：yhm + mm(RSA PKCS#1 v1.5) + csrftoken(uuid,uuid) + language。
 * §3.1b：多节点负载均衡会让同一凭据前两次误报「密码不正确」，所以重试放在 provider 层。
 */

import { httpRequest, detectEnv } from '../core/transport.js';
import { ok, fail, makeError } from './provider.js';

export const ORIGIN = 'https://jwxt.tstc.edu.cn';
export const SOURCE = 'remote';
const DEFAULT_PROXY = '/jwxt';

/**
 * 教务功能模块地址表。`null` = 尚未实测，等现场补 gnmkdm。
 * 规律（§1.5）：/<模块缩写>/<页面>_<动作>.html?gnmkdm=N<6位>&layout=default
 *
 * 2026-11-06 本地代理实测补齐（见 docs/remote-api-map.md §6）：首页
 * `/xtgl/index_initMenu.html` 的 clickMenu 与 `/xtgl/index_cxMenuList.html`
 * 给出全部 gnmkdm；「我的应用」别名来自 `/xtgl/index_cxWdyy.html`。
 * gnmkdm 后面的页面 ≠ 数据接口：成绩/课表/推荐课表都要再 POST 一次取 JSON。
 * @type {Record<string, string|null>}
 */
export const ENDPOINTS = Object.freeze({
  loginPage: '/xtgl/login_slogin.html',
  loginPost: '/xtgl/login_slogin.html',
  publicKey: '/xtgl/login_getPublicKey.html',
  logoutAccount: '/xtgl/login_logoutAccount.html',
  /** ✅ 首页：菜单功能码来源 */
  menuPage: '/xtgl/index_initMenu.html?jsdm=xs',
  menuList: '/xtgl/index_cxMenuList.html',
  /** ✅ 「我的应用」列表：推荐课表打印的别名在这里 */
  myApps: '/xtgl/index_cxWdyy.html?localeKey=zh_CN',
  /** ✅ 实测 N2151：信息查询 → 个人课表查询 */
  timetablePage: '/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default',
  /** ✅ 用户提供的 provider.js：页内 AJAX，POST xnm/xqm 返回 { kbList: [] } */
  timetableData: '/kbcx/xskbcx_cxXsKb.html',
  /** ✅ 实测 N105515：服务端渲染，GPA 与学分树的唯一权威来源 */
  gpa: '/xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=N105515&layout=default',
  /** ✅ 实测 N305005：信息查询 → 学生成绩查询（这是页面，不是数据） */
  grades: '/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005&layout=default',
  /** ✅ 学生角色真实数据接口；空 xnm/xqm = 全部学期，需 zd_fzdm=N305005-xs */
  gradesData: '/cjcx/cjcx_cxXsgrcj.html?doType=query',
  /**
   * ✅ N253512 自主选课链（2026-11-06 按唐师真实结构重写，铁证见 docs/diagnostics/：
   * Index 样本 + 教务官方 zzxkYzb.js + 真机 logcat）。与 zfn_api 通用契约的差异：
   * 无 role=tab 板块，闸门是 hidden input iskxk；教学班补全前缀是 zzxkyzbjk_；
   * 已选是 Choosed.html（无 Display 后缀、无参数）；提交只有 xkZzxkyzbQuickly。
   * 响应字段名与提交协议**待窗口开放真机实测**，解析失败一律 PARSE 降级，不编数据。
   */
  selection: '/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default',
  /** 旧键保留：课池本体 = PartDisplay 的 rst.tmpList（每行一个教学班） */
  selectionData: '/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html?gnmkdm=N253512',
  selectionDisplay: '/xsxk/zzxkyzb_cxZzxkYzbDisplay.html?gnmkdm=N253512',
  selectionPart: '/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html?gnmkdm=N253512',
  /** 注意前缀 zzxkyzbjk_：班级明细补全（yxzrs/jxbrl/sksj），zzxkYzb.js 560 行 */
  selectionJxb: '/xsxk/zzxkyzbjk_cxJxbWithKchZzxkYzb.html?gnmkdm=N253512',
  /** 已选课程：.load 进 #choosedBox 的 HTML 片段，无参数（zzxkYzb.js 81 行） */
  selectionChoosed: '/xsxk/zzxkyzb_cxZzxkYzbChoosed.html?gnmkdm=N253512',
  /** 提交：快速选课，body 仅 {xkkz_id}，响应 {flag:'0'|'1', msg}（zzxkYzb.js 470 行） */
  selectionSubmit: '/xsxk/zzxkyzb_xkZzxkyzbQuickly.html?gnmkdm=N253512',
  /** ✅ 实测 N100801：信息维护 → 查询个人信息 */
  profile: '/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=N100801&layout=default',
  /**
   * ✅ N214505 推荐课表打印（2026-11-06 PC 直连实测，样本见 docs/diagnostics/bjkbdy-*）。
   * 真实链路（官方 bjkbdy.js / ylBjkbdyIndex.js 为证）：
   *   ① recommendPage  GET   页面壳，读 <select id=xnm/xqm> 学期码
   *   ② recommendList  POST  body = paramMap（xnm/xqm 必带 + 筛选项空串）→ {items:[班级行]}
   *   ③ recommendSfkz  POST  同预览 body，响应 "true" = 该学期不可查看（软检查）
   *   ④ recommend      POST  预览壳页，body 按 jQuery $.param 嵌套序列化 map[key]=v
   *   ⑤ recommendKb    POST  课表数据本体，body = 预览 map【平铺】，返回 {kbList:[...]}
   * 预览壳页的 map 经 window.api.data 注入页面 JS，不是表单字段 —— 所以 ④⑤ 的
   * body 形状不同（④ map[key]、⑤ 平铺），实测 ④ 平铺会返回 911 空分页壳。
   */
  recommend: '/kbdy/bjkbdy_ylBjkbdyIndex.html?gnmkdm=N214505',
  /** 课表数据本体：POST 平铺 map 参数，返回 {kbList, zs, weekNum, kblx, ...} */
  recommendKb: '/kbdy/bjkbdy_cxBjKb.html?gnmkdm=N214505',
  /** 可查看性检查：响应 JSON 字符串 "true" = 不可查看（跨学期限制） */
  recommendSfkz: '/kbdy/bjkbdy_cxXnxqsfkz.html?gnmkdm=N214505',
  /** ✅ 实测 N214505：我的应用 → 推荐课表打印（主菜单里叫「班级课表查询」） */
  recommendPage: '/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=N214505&layout=default',
  /** ✅ 实测：POST paramMap（xnm/xqm 必带）返回班级课表清单（467 条，非课表本体） */
  recommendList: '/kbdy/bjkbdy_cxBjkbdyTjkbList.html',
});

/** 运行期配置。密码只留在内存，绝不落盘（PRD C2-a / CREDENTIAL_ALLOWLIST） */
const config = {
  proxyBase: '',
  endpoints: { ...ENDPOINTS },
  semester: { xnm: '', xqm: '' },
};

/**
 * 现场补端点 / 代理 / 学期码。登录页与设置页调用。
 * @param {Partial<{proxyBase: string, endpoints: Record<string,string>, semester: {xnm:string,xqm:string}}>} partial
 */
export function configureRemote(partial = {}) {
  if (typeof partial.proxyBase === 'string') config.proxyBase = partial.proxyBase;
  if (partial.endpoints && typeof partial.endpoints === 'object') {
    for (const [key, value] of Object.entries(partial.endpoints)) {
      if (key in config.endpoints && typeof value === 'string') config.endpoints[key] = value;
    }
  }
  if (partial.semester && typeof partial.semester === 'object') {
    config.semester = { xnm: String(partial.semester.xnm || ''), xqm: String(partial.semester.xqm || '') };
  }
}

/** @returns {Record<string, string|null>} 当前生效的端点表（只读引用，改它请用 configureRemote） */
export function getEndpoints() { return config.endpoints; }

/** @returns {{xnm: string, xqm: string}} 手工指定的学期码（空串表示从课表页 #ajaxForm 现读） */
export function getSemesterCode() { return { ...config.semester }; }

/** 列出还没实测、会导致对应模块降级的端点 */
export function missingEndpoints() {
  return Object.keys(config.endpoints).filter((key) => !config.endpoints[key]);
}

/** 拼最终 URL：Web 走同源代理，Android 直达教务 */
function urlOf(path) {
  if (config.proxyBase) return `${config.proxyBase.replace(/\/$/, '')}${path}`;
  if (typeof window !== 'undefined' && detectEnv() === 'web') return `${DEFAULT_PROXY}${path}`;
  return `${ORIGIN}${path}`;
}

function transportFail(result) {
  const code = result && result.error ? result.error.code : 'NETWORK';
  return fail(makeError(code === 'TIMEOUT' ? 'TIMEOUT' : 'NETWORK', {
    message: code === 'TIMEOUT' ? '教务系统响应超时，请稍后再试' : '连不上教务系统，请检查校园网后重试',
    detail: result && result.error ? result.error.detail || result.error.message : '',
  }), { source: SOURCE });
}

/**
 * 发一次教务请求。
 * @param {string|null} path null = 端点未实测，直接给可诊断的 NOT_IMPLEMENTED
 * @param {{method?: string, body?: string|object, headers?: object, timeout?: number}} [options]
 * @returns {Promise<{ok: true, text: string, status: number}|{ok: false, error: object, meta: object}>}
 */
export async function request(path, options = {}) {
  if (!path) return fail(makeError('NOT_IMPLEMENTED', {
    message: '该教务模块的地址尚未实测，暂用演示数据',
    detail: '拿到 gnmkdm 后调用 configureRemote({ endpoints }) 补齐',
  }), { source: SOURCE });

  const body = options.body && typeof options.body === 'object'
    ? new URLSearchParams(options.body).toString()
    : options.body || null;
  const res = await httpRequest({
    url: urlOf(path),
    method: options.method || 'GET',
    headers: {
      Accept: 'text/html,application/json,*/*',
      'X-Requested-With': 'XMLHttpRequest',
      ...(body ? { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' } : {}),
      ...(options.headers || {}),
    },
    body,
    timeout: options.timeout || 15000,
  });
  if (!res.ok) return transportFail(res);
  const status = Number(res.data && res.data.status) || 200;
  if (status >= 400) {
    return fail(makeError(status === 401 || status === 403 ? 'AUTH' : 'UNAVAILABLE', {
      detail: `HTTP ${status} ${path}`,
    }), { source: SOURCE });
  }
  return { ok: true, text: String((res.data && res.data.body) || ''), status };
}

/** 从登录页 HTML 抠 csrftoken（两段 uuid 都要，§1.1） */
function readCsrf(html) {
  const match = /name=["']csrftoken["'][^>]*value=["']([^"']+)["']/i.exec(html)
    || /value=["']([^"']+,[a-f0-9]{32})["'][^>]*name=["']csrftoken["']/i.exec(html);
  return match ? match[1] : '';
}

/**
 * 密码 RSA 加密：RSAES-PKCS1-v1_5（等价 Java RSA/ECB/PKCS1Padding），无字节反转。
 * Android 走 NativeBridge；Web 预览走本地代理 /__zsts/encrypt（Node crypto）。
 * 两端都拿不到就明确报错，**绝不明文提交密码**。
 */
async function encryptPassword(modulus, exponent, plain) {
  try {
    if (typeof window !== 'undefined' && window.NativeBridge
      && typeof window.NativeBridge.rsaEncrypt === 'function') {
      const value = window.NativeBridge.rsaEncrypt(modulus, exponent, plain);
      if (value) return { ok: true, value: String(value) };
    }
  } catch { /* 落到代理通道 */ }
  try {
    const res = await fetch('/__zsts/encrypt', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ modulus, exponent, plain }),
    });
    if (res.ok) {
      const data = await res.json();
      if (data && data.cipher) return { ok: true, value: String(data.cipher) };
    }
  } catch { /* 代理不存在 */ }
  return fail(makeError('UNAVAILABLE', {
    message: '当前环境无法完成密码加密，请在安卓客户端内登录',
    detail: 'NativeBridge.rsaEncrypt 与本地代理加密端点均不可用',
  }), { source: SOURCE });
}

/**
 * 登录一次（不含重试）。成功判定看响应体，不依赖会话 URL。
 * @param {string} studentNo
 * @param {string} password
 */
export async function loginOnce(studentNo, password) {
  // 2026-11-06 修复「随便输密码都能登录」的真正根因：WebView 里残留着上一次
  // 成功登录的 JSESSIONID（CookieManager 持久化、App 重启不清）。错误密码虽然
  // 被教务拒绝，但登录后的每一步校验/抓取都带着旧会话 Cookie 一路绿灯 ——
  // 等于拿旧会话冒充新登录，换账号看到的还是上一个账号的数据。
  // 所以每次登录必须从零 Cookie 开始；桥不可用（web 预览）时跳过，由浏览器会话管理。
  try {
    if (typeof window !== 'undefined' && window.NativeBridge
      && typeof window.NativeBridge.clearSession === 'function') {
      window.NativeBridge.clearSession();
    }
  } catch { /* 桥不可用，继续 */ }

  const page = await request(config.endpoints.loginPage, { method: 'GET' });
  if (!page.ok) return page;
  const csrf = readCsrf(page.text);
  if (!csrf) return fail(makeError('PARSE', { detail: '登录页找不到 csrftoken' }), { source: SOURCE });

  const keyRes = await request(`${config.endpoints.publicKey}?time=${Date.now()}`, { method: 'GET' });
  if (!keyRes.ok) return keyRes;
  let pub;
  try { pub = JSON.parse(keyRes.text); } catch {
    return fail(makeError('PARSE', { detail: '公钥接口返回的不是 JSON' }), { source: SOURCE });
  }
  if (!pub.modulus || !pub.exponent) {
    return fail(makeError('PARSE', { detail: '公钥缺少 modulus / exponent' }), { source: SOURCE });
  }

  const enc = await encryptPassword(pub.modulus, pub.exponent, password);
  if (!enc.ok) return enc;

  // 15 个隐藏开关实测全为空串，但要带上，缺项会被 Shiro 判为异常提交
  const form = {
    csrftoken: csrf, yhm: studentNo, mm: enc.value, hidMm: enc.value,
    language: 'zh_CN', mmsfjm: '1',
  };
  const post = await request(config.endpoints.loginPost, { method: 'POST', body: form });
  if (!post.ok) return post;
  // 教务明确回了「用户名或密码不正确」：这是确定性失败，直接给准确文案并标记
  // definitive —— provider 不再重试 3 次（重试只该用于多节点/网络抖动，不该浪费在错密码上）。
  if (/用户名或密码不正确/.test(post.text)) {
    return fail(makeError('AUTH', {
      message: '学号或密码不正确，请重新登录',
      definitive: true,
    }), { source: SOURCE });
  }
  // 判定原则：看「是不是被打回登录页」，而不是看「有没有 302」。
  // 实测（§6）：登录成功会连跳两级 302（login_slogin.html → index_initMenu.html），
  // 代理跟随到最终页；Android 通道可能只回 302 空体，所以不能要求响应里必须含
  // 首页特征，否则会把成功误判成失败。登录表单 / 用户名输入框才是可靠负向证据。
  const stillLogin = /action=["']\/xtgl\/login_slogin\.html/i.test(post.text)
    || /placeholder=["']用户名/i.test(post.text);
  if (stillLogin || /用户名或密码不正确|验证码错误|账号不存在/.test(post.text)
    || /login_slogin\.html\?time=/.test(post.text)) {
    return fail(makeError('AUTH', { message: '教务系统繁忙，正在重试' }), { source: SOURCE });
  }
  // 2026-11-06 修复「随便输个密码也提示登录成功」：Android 通道的 POST 302 常是空体，
  // 「响应里没有登录表单」区分不了成功与失败 —— 空体既是成功的样子也是失败的样子。
  // 追加一次会话实锤：GET 一个必须登录才能看的页面，被打回登录页 = 凭据没通过。
  const verify = await request(config.endpoints.menuList, { method: 'GET' });
  if (!verify.ok) {
    return fail(makeError(verify.error && verify.error.code === 'TIMEOUT' ? 'TIMEOUT' : 'NETWORK', {
      message: '登录后核对会话失败，请检查校园网后重试',
      detail: verify.error && (verify.error.detail || verify.error.message),
    }), { source: SOURCE });
  }
  if (/login_slogin/i.test(verify.text)) {
    return fail(makeError('AUTH', { message: '学号或密码不正确，请重新登录' }), { source: SOURCE });
  }
  // 正向证据：已登录的菜单页必然含 gnmkdm（功能码）。空响应 / 无特征 = 会话没建立
  //（多节点干扰、302 未跟随等），宁可拒绝也不能放行 —— 登录态绝不允许「猜」。
  if (!verify.text || !/gnmkdm/i.test(verify.text)) {
    return fail(makeError('AUTH', {
      message: '无法确认登录状态，请重新登录',
      detail: 'menuList 未返回已登录特征（gnmkdm）',
    }), { source: SOURCE });
  }
  return ok({ studentNo, name: '' }, { source: SOURCE });
}

/** 打印页常见的内嵌 JSON（页内脚本变量 / data-* 属性） */
export function readEmbeddedJson(html) {
  const match = /kbList\s*[:=]\s*(\[[\s\S]*?\])/.exec(String(html || ''));
  if (!match) return [];
  try { return JSON.parse(match[1]); } catch { return []; }
}

/**
 * 抓学生姓名（登录/会话恢复后调用一次）。唐师实测（真机 BODY 抓包 66KB 原文）：
 *   <label class="control-label" for="" > 姓名： </label>
 *   <div class="col-sm-8" id="col_xm"> <p class="form-control-static"> 张三 </p> </div>
 * label 与值之间隔两层标签，且页面上还有「英文姓名」label（(?<!英) 排除）。
 * 兜底：①col_xm 容器 ②表单 input[name=xm]。抓不到返回空串，绝不影响登录。
 */
export async function fetchStudentName() {
  try {
    const res = await request(config.endpoints.profile, { method: 'GET' });
    if (!res.ok) return '';
    const text = String(res.text || '');
    const m = /(?<!英)姓名\s*:?\s*<\/label>[\s\S]{0,300}?form-control-static[^>]*>\s*([^<]{1,24})\s*</.exec(text)
      || /id=["']col_xm["'][\s\S]{0,300}?form-control-static[^>]*>\s*([^<]{1,24})\s*</.exec(text)
      || /name=["']xm["'][^>]*value=["']([^"']{1,24})["']/.exec(text);
    return m ? m[1].trim() : '';
  } catch {
    return '';
  }
}
