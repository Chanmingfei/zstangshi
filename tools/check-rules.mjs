#!/usr/bin/env node
/**
 * check-rules.mjs —— 项目硬约束守卫
 *
 * 四条守卫（任一失败即 exit 1，并打印 `文件:行号`）：
 *   ① 颜色字面量只能出现在 web/styles/tokens.css（其它文件一律 var(--token)，
 *      白名单见下方 COLOR_ALLOW / COLOR_ALLOW_DIRS）。
 *   ② web/** 不得引用任何外部 http(s) 资源（字体 / CDN / 图标库 / 远程图片）。
 *      白名单只允许「作为字符串展示或接口常量」的文件，见 EXTERNAL_ALLOW。
 *      另外无论白名单如何，`src=` / `href=` / `url()` / `@import` 指向 http(s) 一律失败。
 *   ③ 单文件行数上限：默认 400 行；web/data/demo/*.js 放宽到 500 行。
 *   ④ web/data/school-calendar.json 必须与 docs/school-calendar.json 深度相等
 *      （校历是权威事实，任何一边被改都必须同步）。
 *   ⑤ 对比度：tokens.css 里登记的「前景 token × 背景 token」配对，亮/暗两套实际色值
 *      的 WCAG 比值必须 ≥ 4.5，否则 exit(1) 并打印是哪一对、当前比值、差多少。
 *      —— 把「凭感觉调色」变成「CI 拦得住」，见 docs/a11y-contrast.md §四。
 *
 * 运行：node tools/check-rules.mjs
 */

import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const WEB = path.join(ROOT, 'web');

/** 守卫① 白名单：允许出现颜色字面量的文件（相对 ROOT，posix 分隔符） */
const COLOR_ALLOW = new Set([
  'web/styles/tokens.css', // token 的唯一来源
  'web/index.html', // 内联防闪烁脚本需要 meta[theme-color] 的兜底色值
]);
/** 守卫① 白名单：允许出现颜色字面量的目录前缀 */
const COLOR_ALLOW_DIRS = ['web/assets/']; // 离线存档 SVG 必须自带色值

/** 守卫② 白名单：允许出现 http(s) 字符串的文件 */
const EXTERNAL_ALLOW = new Set([
  'web/data/remoteProvider.js', // 教务接口地址常量
  'web/data/jwxtClient.js', // 教务 origin / 端点表（拆分后 URL 常量搬到这里）
  'web/data/externalLinks.js', // 外链入口地址唯一来源（学工 / 教务），页面一律引用不写死
  'web/data/jwxtOrigin.js', // 教务 origin 候选（https / http），按网络环境探测择一
  'web/pages/mine.js', // 关于页：教务处 / 校历来源链接
  'web/data/school-calendar.json', // 校历来源元数据（与 docs/ 严格同源）
]);

/** 守卫② 允许出现的「伪 URL」：只是命名空间标识符，不会被浏览器发起请求 */
const EXTERNAL_IGNORE = [
  /http:\/\/www\.w3\.org\/2000\/svg/g,
  /http:\/\/www\.w3\.org\/1999\/xlink/g,
];

/** 守卫② 中「真的是在加载远程资源」的写法，命中即失败，不看白名单 */
const RESOURCE_LOAD_PATTERNS = [
  { re: /(?:src|href)\s*=\s*["']https?:/i, hint: 'src/href 指向外部资源' },
  { re: /url\(\s*["']?https?:/i, hint: 'CSS url() 指向外部资源' },
  { re: /@import\s+(?:url\()?\s*["']?https?:/i, hint: '@import 外部样式表' },
];

/** 守卫① 颜色字面量正则 */
const COLOR_RE = /#[0-9a-fA-F]{3,8}\b|\brgba?\s*\(|\bhsla?\s*\(/;

/** 守卫⑤ 对比度下限（WCAG 2.1 AA 正文级） */
const CONTRAST_MIN = 4.5;

/**
 * 守卫⑤ 登记的配对表：{ theme, fg, bg, note }
 * 只登记「承载信息的文字 / 图标」的组合；纯图形、禁用态、装饰色不进这张表。
 * 取值依据：docs/a11y-contrast.md 第一节通过项 + 第二节修正后的值。
 */
const CONTRAST_PAIRS = [
  // —— 亮色 ——
  { theme: 'light', fg: '--text-primary', bg: '--bg-page', note: '正文 on 页面底' },
  { theme: 'light', fg: '--text-primary', bg: '--bg-surface', note: '正文 on 卡片' },
  { theme: 'light', fg: '--text-secondary', bg: '--bg-page', note: '次级文字 on 页面底' },
  { theme: 'light', fg: '--text-secondary', bg: '--bg-surface', note: '次级文字 on 卡片' },
  { theme: 'light', fg: '--brand-primary', bg: '--bg-surface', note: '主色文字 on 卡片' },
  { theme: 'light', fg: '--brand-primary', bg: '--brand-primary-soft', note: '主色文字 on 浅蓝底' },
  { theme: 'light', fg: '--brand-primary-on-soft', bg: '--brand-primary-soft', note: 'chip 选中态文字' },
  { theme: 'light', fg: '--brand-on-primary', bg: '--brand-primary', note: '主按钮文字' },
  { theme: 'light', fg: '--brand-accent-text', bg: '--bg-surface', note: '暖金文字 on 卡片' },
  { theme: 'light', fg: '--brand-accent-text', bg: '--bg-page', note: '暖金文字 on 页面底' },
  { theme: 'light', fg: '--brand-on-accent', bg: '--brand-accent-soft', note: '演示提示条' },
  { theme: 'light', fg: '--success', bg: '--bg-surface', note: '成功色文字' },
  { theme: 'light', fg: '--info', bg: '--bg-surface', note: '链接 / 提示色文字' },
  { theme: 'light', fg: '--text-inverse', bg: '--success', note: '成功底上的反白文字' },
  { theme: 'light', fg: '--text-tertiary', bg: '--bg-surface', note: '三级文字（HIG 重设计后已达标）' },
  { theme: 'light', fg: '--danger', bg: '--bg-surface', note: '危险色文字（iOS 红 #D70015 已达标）' },
  { theme: 'light', fg: '--text-inverse', bg: '--danger', note: '危险底上的反白文字' },
  // —— 暗色 ——
  { theme: 'dark', fg: '--text-primary', bg: '--bg-page', note: '正文 on 页面底' },
  { theme: 'dark', fg: '--text-primary', bg: '--bg-surface', note: '正文 on 卡片' },
  { theme: 'dark', fg: '--text-secondary', bg: '--bg-page', note: '次级文字 on 页面底' },
  { theme: 'dark', fg: '--text-secondary', bg: '--bg-surface', note: '次级文字 on 卡片' },
  { theme: 'dark', fg: '--text-tertiary', bg: '--bg-surface', note: '三级文字（暗色已达标）' },
  { theme: 'dark', fg: '--brand-primary', bg: '--bg-surface', note: '主色文字 on 卡片' },
  { theme: 'dark', fg: '--brand-primary-on-soft', bg: '--brand-primary-soft', note: 'chip 选中态文字（专用浅色，替代 --brand-primary）' },
  { theme: 'dark', fg: '--brand-on-primary', bg: '--brand-primary', note: '主按钮文字' },
  { theme: 'dark', fg: '--brand-accent-text', bg: '--bg-surface', note: '暖金文字 on 卡片' },
  { theme: 'dark', fg: '--brand-accent-text', bg: '--bg-page', note: '暖金文字 on 页面底' },
  { theme: 'dark', fg: '--brand-on-accent', bg: '--brand-accent-soft', note: '演示提示条' },
  { theme: 'dark', fg: '--success', bg: '--bg-surface', note: '成功色文字' },
  { theme: 'dark', fg: '--danger', bg: '--bg-surface', note: '危险色文字' },
  { theme: 'dark', fg: '--info', bg: '--bg-surface', note: '链接 / 提示色文字' },
  { theme: 'dark', fg: '--text-inverse', bg: '--danger', note: '危险底上的反白文字' },
];

/**
 * 已知未达标、但被明确豁免的组合 —— 不参与守卫，只在末尾打一行提示，避免被当成熟视无睹。
 * 每一条都要写清「为什么可以豁免」。
 * 2026-11-06 Apple HIG 重设计后原三条豁免全部转正（#D70015 红 / #737379 灰均 ≥4.5），
 * 已并入 CONTRAST_PAIRS，此表暂空 —— 新增豁免前先试试能不能把色值调到达标。
 */
const CONTRAST_KNOWN_GAPS = [];
/** 守卫③ 行数上限 */
const MAX_LINES = 400;
const MAX_LINES_DEMO = 500;

/** 需要扫描文本的文件后缀 */
const TEXT_EXT = new Set(['.html', '.css', '.js', '.mjs', '.json', '.svg', '.xml', '.java']);

/** @type {string[]} 失败明细 */
const problems = [];
/** @type {string[]} 守卫通过摘要 */
const passes = [];

/**
 * 递归收集目录下所有文件（绝对路径）。
 * @param {string} dir
 * @returns {Promise<string[]>}
 */
async function walk(dir) {
  /** @type {string[]} */
  const out = [];
  let entries;
  try {
    entries = await readdir(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const entry of entries) {
    const abs = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...(await walk(abs)));
    } else if (entry.isFile()) {
      out.push(abs);
    }
  }
  return out;
}

/**
 * 绝对路径 → 相对 ROOT 的 posix 路径。
 * @param {string} abs
 * @returns {string}
 */
function rel(abs) {
  return path.relative(ROOT, abs).split(path.sep).join('/');
}

/**
 * 按行扫描，收集命中正则的行号。
 * @param {string} content
 * @param {RegExp} re 不带 g 的正则（内部逐行 test）
 * @returns {Array<{line: number, text: string}>}
 */
function findLines(content, re) {
  const hits = [];
  const lines = content.split('\n');
  for (let i = 0; i < lines.length; i += 1) {
    if (re.test(lines[i])) {
      hits.push({ line: i + 1, text: lines[i].trim().slice(0, 120) });
    }
  }
  return hits;
}

/**
 * 守卫①：颜色字面量只能来自 tokens.css。
 * @param {string} relPath
 * @param {string} content
 */
function guardColor(relPath, content) {
  if (COLOR_ALLOW.has(relPath)) return;
  if (COLOR_ALLOW_DIRS.some((dir) => relPath.startsWith(dir))) return;
  for (const hit of findLines(content, COLOR_RE)) {
    problems.push(
      `[①颜色] ${relPath}:${hit.line} 出现颜色字面量 → 请改用 var(--token)\n        ${hit.text}`
    );
  }
}

/**
 * 守卫②：web/** 不得引用外部 http(s) 资源。
 * @param {string} relPath
 * @param {string} content
 */
function guardExternal(relPath, content) {
  const lines = content.split('\n');
  const allowAll = EXTERNAL_ALLOW.has(relPath);
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    const brief = line.trim().slice(0, 120);
    for (const { re, hint } of RESOURCE_LOAD_PATTERNS) {
      if (re.test(line)) {
        problems.push(`[②外链] ${relPath}:${i + 1} ${hint}（任何情况下均禁止）\n        ${brief}`);
      }
    }
    if (allowAll) continue;
    // 先抹掉 SVG 命名空间等「伪 URL」，再看这一行还有没有真的 http(s)
    let probe = line;
    for (const re of EXTERNAL_IGNORE) probe = probe.replace(re, '');
    if (!/https?:\/\//i.test(probe)) continue;
    problems.push(`[②外链] ${relPath}:${i + 1} 出现外部 URL（不在白名单内）\n        ${brief}`);
  }
}

/**
 * 守卫③：单文件行数上限。
 * @param {string} relPath
 * @param {string} content
 */
function guardLineCount(relPath, content) {
  if (!relPath.startsWith('web/')) return;
  const lines = content.split('\n').length;
  const limit = relPath.startsWith('web/data/demo/') && relPath.endsWith('.js')
    ? MAX_LINES_DEMO
    : MAX_LINES;
  if (lines > limit) {
    problems.push(`[③行数] ${relPath} 共 ${lines} 行，超过上限 ${limit} 行 → 请拆分模块`);
  }
}

/* ═══ 守卫⑤ 对比度 ═══════════════════════════════════════════════════════ */

/**
 * 解析 CSS 颜色为 [r, g, b]；不支持的值（env()、rgba 带 alpha 等）返回 null。
 * @param {string} value
 * @returns {[number, number, number]|null}
 */
function parseColor(value) {
  const raw = String(value || '').trim();
  let match = /^#([0-9a-f]{3})$/i.exec(raw);
  if (match) return match[1].split('').map((c) => Number.parseInt(c + c, 16));
  match = /^#([0-9a-f]{6})$/i.exec(raw);
  if (match) {
    const n = Number.parseInt(match[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  match = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)\s*(?:[,/]\s*([\d.]+)\s*)?\)$/i.exec(raw);
  if (match) {
    const alpha = match[4] === undefined ? 1 : Number.parseFloat(match[4]);
    if (alpha < 1) return null; // 半透明色需要合成背景，本守卫不处理
    return [Number.parseFloat(match[1]), Number.parseFloat(match[2]), Number.parseFloat(match[3])];
  }
  return null;
}

/**
 * WCAG 2.1 相对亮度。
 * @param {[number, number, number]} rgb 0-255
 * @returns {number}
 */
function relativeLuminance(rgb) {
  const [r, g, b] = rgb.map((channel) => {
    const v = channel / 255;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * 两色的对比度比值。
 * @param {[number, number, number]} a
 * @param {[number, number, number]} b
 * @returns {number}
 */
function contrastRatio(a, b) {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  const lighter = Math.max(la, lb);
  const darker = Math.min(la, lb);
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * 从 tokens.css 里抽出某个块的变量表。
 * @param {string} css
 * @param {RegExp} blockRe 捕获组 1 为块内文本
 * @returns {Record<string, string>}
 */
function readTokenBlock(css, blockRe) {
  const block = blockRe.exec(css);
  const out = {};
  if (!block) return out;
  const declRe = /(--[\w-]+)\s*:\s*([^;]+);/g;
  let match;
  while ((match = declRe.exec(block[1])) !== null) {
    out[match[1]] = match[2].trim();
  }
  return out;
}

/**
 * 守卫⑤：逐对校验 WCAG 对比度。
 * @returns {Promise<void>}
 */
async function guardContrast() {
  let css;
  try {
    css = await readFile(path.join(ROOT, 'web/styles/tokens.css'), 'utf8');
  } catch {
    problems.push('[⑤对比度] 读不到 web/styles/tokens.css');
    return;
  }
  const themes = {
    light: readTokenBlock(css, /:root\s*\{([\s\S]*?)\n\}/),
    dark: readTokenBlock(css, /\[data-theme='dark'\]\s*\{([\s\S]*?)\n\}/),
  };
  if (Object.keys(themes.light).length === 0 || Object.keys(themes.dark).length === 0) {
    problems.push('[⑤对比度] tokens.css 里找不到 :root 或 [data-theme="dark"] 块');
    return;
  }

  let checked = 0;
  let worst = { ratio: Infinity, label: '' };
  for (const pair of CONTRAST_PAIRS) {
    const table = themes[pair.theme];
    const fgRaw = table[pair.fg];
    const bgRaw = table[pair.bg];
    const fg = parseColor(fgRaw);
    const bg = parseColor(bgRaw);
    if (!fg || !bg) {
      problems.push(
        `[⑤对比度] ${pair.theme} 主题缺色值：${pair.fg}=${fgRaw || '(未定义)'} / ${pair.bg}=${bgRaw || '(未定义)'}`
      );
      continue;
    }
    const ratio = contrastRatio(fg, bg);
    checked += 1;
    if (ratio < worst.ratio) worst = { ratio, label: `${pair.theme} ${pair.fg} on ${pair.bg}` };
    if (ratio < CONTRAST_MIN) {
      problems.push(
        `[⑤对比度] ${pair.theme} · ${pair.fg}(${fgRaw}) on ${pair.bg}(${bgRaw}) = ${ratio.toFixed(2)} < ${CONTRAST_MIN}` +
          `（差 ${(CONTRAST_MIN - ratio).toFixed(2)}，${pair.note}）`
      );
    }
  }
  if (checked > 0) {
    passes.push(
      `⑤对比度：${checked} 对全部 ≥ ${CONTRAST_MIN}，最低 ${worst.ratio.toFixed(2)}（${worst.label}）`
    );
  }
  const gaps = CONTRAST_KNOWN_GAPS.map((gap) => {
    const table = themes[gap.theme];
    const fg = parseColor(table[gap.fg]);
    const bg = parseColor(table[gap.bg]);
    if (!fg || !bg) return null;
    const ratio = contrastRatio(fg, bg).toFixed(2);
    return `${gap.theme} ${gap.fg} on ${gap.bg}=${ratio}`;
  }).filter(Boolean);
  if (gaps.length > 0) {
    passes.push(`⑤对比度豁免项（已知不达标，见 CONTRAST_KNOWN_GAPS）：${gaps.join('；')}`);
  }
}

/**
 * 守卫④：校历副本必须与 docs 原文深度相等。
 * @returns {Promise<void>}
 */
async function guardCalendarSync() {
  const canonicalPath = 'docs/school-calendar.json';
  const copyPath = 'web/data/school-calendar.json';
  let canonical;
  let copy;
  try {
    canonical = await readFile(path.join(ROOT, canonicalPath), 'utf8');
  } catch {
    problems.push(`[④校历] 找不到权威校历 ${canonicalPath}`);
    return;
  }
  try {
    copy = await readFile(path.join(ROOT, copyPath), 'utf8');
  } catch {
    problems.push(`[④校历] 缺少副本 ${copyPath} → 请从 ${canonicalPath} 复制一份`);
    return;
  }
  let a;
  let b;
  try {
    a = JSON.parse(canonical);
    b = JSON.parse(copy);
  } catch (err) {
    problems.push(`[④校历] JSON 解析失败：${err.message}`);
    return;
  }
  if (JSON.stringify(a) !== JSON.stringify(b)) {
    problems.push(`[④校历] ${copyPath} 与 ${canonicalPath} 内容不一致 → 请整文件重新复制，禁止手工改副本`);
    return;
  }
  passes.push(`④校历副本与 docs 原文 deepEqual（${(copy.length / 1024).toFixed(1)} KB）`);
}

async function main() {
  const files = await walk(WEB);
  if (files.length === 0) {
    problems.push('[①颜色] web/ 目录为空或不存在 → 请检查是否漏了 web/index.html');
  }

  let scanned = 0;
  let colorGuardCount = 0;
  let externalGuardCount = 0;
  let lineGuardCount = 0;

  for (const abs of files) {
    const relPath = rel(abs);
    const ext = path.extname(abs).toLowerCase();
    if (!TEXT_EXT.has(ext)) continue;

    let content;
    try {
      content = await readFile(abs, 'utf8');
    } catch {
      problems.push(`[读取] ${relPath} 读取失败`);
      continue;
    }
    scanned += 1;

    if (ext === '.css' || ext === '.html' || ext === '.js' || ext === '.mjs' || ext === '.svg') {
      guardColor(relPath, content);
      colorGuardCount += 1;
    }
    if (ext !== '.svg') {
      guardExternal(relPath, content);
      externalGuardCount += 1;
    }
    guardLineCount(relPath, content);
    lineGuardCount += 1;
  }

  await guardCalendarSync();
  await guardContrast();

  if (problems.length > 0) {
    process.stderr.write(`\n  [FAIL] check-rules 发现 ${problems.length} 处违规\n\n`);
    for (const item of problems) {
      process.stderr.write(`    ✗ ${item}\n`);
    }
    process.stderr.write('\n');
    // 用 exitCode 而不是 process.exit()：后者会立刻终止进程，
    // Windows 上管道里的 stderr 可能还没 flush 就被截断，看起来像「没有输出」。
    process.exitCode = 1;
    return;
  }

  process.stdout.write('\n  [PASS] check-rules 五条守卫全部通过\n');
  process.stdout.write(`         ① 颜色字面量：扫描 ${colorGuardCount} 个文件，仅 tokens.css / index.html 含色值\n`);
  process.stdout.write(`         ② 外部资源：扫描 ${externalGuardCount} 个文件，零外链\n`);
  process.stdout.write(`         ③ 单文件行数：检查 ${lineGuardCount} 个文件，均 ≤ 上限（demo 例外 500）\n`);
  for (const p of passes) {
    process.stdout.write(`         ${p}\n`);
  }
  process.stdout.write(`         共扫描 ${scanned} 个文本文件\n\n`);
  process.exitCode = 0;
}

main().catch((err) => {
  process.stderr.write(`\n  [ERROR] 守卫脚本自身异常：${err && err.stack ? err.stack : err}\n\n`);
  process.exitCode = 1;
});
