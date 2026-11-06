#!/usr/bin/env node
/**
 * test-pages.mjs —— 页面模块契约 + import 图静态回归（零依赖、无需浏览器）
 *
 * 为什么需要它：CDP 走查依赖本地静态服务，服务停掉时就没法证明「页面能打开」。
 * 但白屏最常见的两个根因其实纯静态就能查出来：
 *   ① app.js / index.html 指向的文件不存在（404 → 懒加载失败）
 *   ② 某个 ESM 的 `from './xxx.js'` 路径拼错（Failed to resolve module specifier）
 * 再加一层页面导出契约检查（name/title/requiresAuth/render/mount/unmount），
 * 就能在没有浏览器的环境里挡住绝大多数单页崩溃。
 *
 * 运行：node tools/test-pages.mjs
 */

import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const WEB = path.join(ROOT, 'web');

const failures = [];
let passed = 0;

/** @param {boolean} ok @param {string} label @param {string} [detail] */
function check(ok, label, detail = '') {
  if (ok) { passed += 1; return; }
  failures.push(`${label}${detail ? ` — ${detail}` : ''}`);
}

const read = (rel) => readFile(path.join(ROOT, rel), 'utf8');
const exists = async (abs) => {
  try { await readFile(abs); return true; } catch { return false; }
};

/** 递归收集 web/ 下所有 .js / .mjs */
async function walk(dir, out = []) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const abs = path.join(dir, entry.name);
    if (entry.isDirectory()) await walk(abs, out);
    else if (/\.(js|mjs)$/.test(entry.name)) out.push(abs);
  }
  return out;
}

/** 先剥掉注释，避免把文档里的示例 `import { DEMO } from './demo/index.js'` 当成真实引用 */
function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');
}

/** 抓一个文件里所有相对 import / 动态 import 的目标字符串 */
function importTargets(source) {
  const text = stripComments(source);
  const out = [];
  const patterns = [
    /(?:from|import)\s*\(?\s*['"](\.[^'"]+)['"]/g,
    /import\s*\(\s*['"](\.[^'"]+)['"]\s*\)/g,
  ];
  for (const re of patterns) {
    let match;
    while ((match = re.exec(text)) !== null) out.push(match[1]);
  }
  return [...new Set(out)];
}

/* ── A. 路由表与样式 link 的文件完整性 ────────────────────────────────── */
const appSrc = await read('web/app.js');
const htmlSrc = await read('web/index.html');

const routePaths = [...new Set(
  [...appSrc.matchAll(/import\(\s*['"](\.\/pages\/[^'"]+)['"]\s*\)/g)].map((m) => m[1])
)];
/* 2026-11-06 学工跳转中间页按用户口径移除：路由 11→10、样式 link 18→17 */
check(routePaths.length >= 10, 'A1 路由表至少 10 个懒加载页面', `实际 ${routePaths.length}`);
for (const rel of routePaths) {
  check(await exists(path.join(WEB, rel.slice(2))), `A2 路由文件存在 ${rel}`);
}
check(!/placeholder\s*\(/.test(appSrc), 'A3 路由表不再残留占位页工厂');

const stylePaths = [...new Set(
  [...htmlSrc.matchAll(/href="(\.\/styles\/[^"]+)"/g)].map((m) => m[1])
)];
check(stylePaths.length >= 17, 'A4 样式 link 至少 17 个', `实际 ${stylePaths.length}`);
for (const rel of stylePaths) {
  check(await exists(path.join(WEB, rel.slice(2))), `A5 样式文件存在 ${rel}`);
}

/* ── B. 整张 ESM import 图没有断链 ────────────────────────────────────── */
const jsFiles = await walk(WEB);
let broken = 0;
for (const abs of jsFiles) {
  const src = await readFile(abs, 'utf8');
  for (const target of importTargets(src)) {
    const resolved = path.resolve(path.dirname(abs), target);
    if (!(await exists(resolved))) {
      broken += 1;
      failures.push(`B import 断链 ${path.relative(ROOT, abs)} → ${target}`);
    }
  }
}
check(broken === 0, 'B1 web/ 内所有相对 import 目标都存在', `断链 ${broken} 处`);
check(jsFiles.length >= 50, 'B2 ESM 文件规模符合预期', `实际 ${jsFiles.length}`);

/* ── C. 每个页面模块的导出契约 ────────────────────────────────────────── */
const CONTRACT = ['name', 'title', 'requiresAuth', 'render', 'mount', 'unmount'];
let contractOk = 0;
let needsDom = 0;
for (const rel of routePaths) {
  const abs = path.join(WEB, rel.slice(2));
  const src = await readFile(abs, 'utf8');
  let mod = null;
  try {
    mod = await import(pathToFileURL(abs).href);
  } catch (err) {
    // 顶层依赖 document/window 的模块在 Node 里必然抛错，退化为静态契约检查
    const msg = String(err && err.message);
    if (/document|window|navigator|localStorage|DOMParser|ReferenceError/i.test(msg)) {
      needsDom += 1;
    } else {
      failures.push(`C 模块加载失败 ${rel} — ${msg}`);
      continue;
    }
  }
  const page = mod && mod.default;
  const authMatch = /\brequiresAuth:\s*(true|false)/.exec(src);
  const shape = page || {
    name: (/\bname:\s*['"][^'"]+['"]/.test(src) && 'ok'),
    title: (/\btitle:\s*['"][^'"]+['"]/.test(src) && 'ok'),
    requiresAuth: authMatch ? authMatch[1] === 'true' : undefined,
    render: (/\brender\s*\(/.test(src) && 'ok'),
    mount: (/\bmount\s*\(/.test(src) && 'ok'),
    unmount: (/\bunmount\s*\(/.test(src) && 'ok'),
  };
  const missing = CONTRACT.filter((key) => {
    const value = shape[key];
    // requiresAuth: false 是合法值（校历 / 学工 / 登录页免登录），不能当成缺字段
    if (key === 'requiresAuth') return typeof value !== 'boolean';
    return value === undefined || value === null || value === false || value === '';
  });
  if (missing.length === 0) contractOk += 1;
  else failures.push(`C 契约缺字段 ${rel} — ${missing.join(', ')}`);
}
check(contractOk === routePaths.length, 'C1 全部页面导出契约齐全',
  `${contractOk}/${routePaths.length}，其中 ${needsDom} 个模块在 Node 下退化为静态检查`);

/* ── 结论 ─────────────────────────────────────────────────────────────── */
if (failures.length) {
  process.stderr.write(`\n  [FAIL] 页面契约 ${failures.length} 项未通过\n\n`);
  for (const item of failures) process.stderr.write(`    ✗ ${item}\n`);
  process.stderr.write('\n');
  process.exitCode = 1;
} else {
  process.stdout.write(
    `\n  [PASS] 页面契约 ${passed} 条断言全部通过` +
    `（${routePaths.length} 页 / ${stylePaths.length} 样式 / ${jsFiles.length} 个 ESM，import 图零断链）\n\n`
  );
  process.exitCode = 0;
}
