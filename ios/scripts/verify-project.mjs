#!/usr/bin/env node
/**
 * verify-project.mjs —— 在没有 macOS / Xcode 的机器上校验 .xcodeproj 的完整性。
 *
 * 它模拟 Xcode 打开工程时会做的事，把「只有到 Mac 上才暴露」的错提前抓出来：
 *   1. 每个被引用的 24 位对象 ID 都必须有定义（漏一个 Xcode 直接报工程损坏）；
 *   2. 每个 PBXFileReference 解析出的磁盘路径必须真实存在（改名/漏拷）；
 *   3. 每个 .swift 必须出现在 Sources 阶段（漏加就静默不编译，AppDelegate 找不到入口）；
 *   4. bridge-shim.js / Assets.xcassets / www 必须出现在 Resources 阶段
 *      （漏加 = 运行期白屏，且编译期毫无提示）；
 *   5. Info.plist 必须存在且能被解析，版本号与 Android 侧一致；
 *   6. 目录内不应有未被工程引用的 .swift（防止「写了没接上」）。
 *
 * 用法：node ios/scripts/verify-project.mjs
 */

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const iosRoot = resolve(here, '..');
const projectPath = join(iosRoot, 'ZhangShangTangShi.xcodeproj', 'project.pbxproj');

const problems = [];
const notes = [];

/** 统一成正斜杠，避免 Windows 下 relative() 返回反斜杠导致路径比对失败 */
const rel = (absolute) => relative(iosRoot, absolute).split(sep).join('/');

function fail(message) {
  problems.push(message);
}

/* ── 1. 解析 pbxproj 对象表 ─────────────────────────────────────────────── */

const source = readFileSync(projectPath, 'utf8');

/** 去掉注释，避免注释里的中文与 ID 干扰解析 */
function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '');
}

const clean = stripComments(source);
const objectsStart = clean.indexOf('objects = {');
if (objectsStart < 0) fail('找不到 objects 段，工程文件结构异常');

/** 从 startIndex 开始做括号配对，返回块结束位置 */
function matchBrace(text, openIndex) {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = openIndex; i < text.length; i += 1) {
    const ch = text[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') { inString = true; continue; }
    if (ch === '{') depth += 1;
    else if (ch === '}') {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return -1;
}

const objects = new Map();
{
  const definitionPattern = /^\s*([0-9A-Fa-f]{24})\s*=\s*\{/gm;
  let match;
  while ((match = definitionPattern.exec(clean)) !== null) {
    const openIndex = clean.indexOf('{', match.index);
    const closeIndex = matchBrace(clean, openIndex);
    if (closeIndex < 0) {
      fail(`对象 ${match[1]} 的花括号不闭合`);
      continue;
    }
    const body = clean.slice(openIndex + 1, closeIndex);
    const isa = /isa\s*=\s*([A-Za-z]+)\s*;/.exec(body);
    objects.set(match[1], {
      id: match[1],
      isa: isa ? isa[1] : '(unknown)',
      body,
    });
    definitionPattern.lastIndex = closeIndex;
  }
}

notes.push(`解析到 ${objects.size} 个对象`);

/* ── 2. 所有被引用的 ID 必须有定义 ──────────────────────────────────────── */

{
  const referenced = new Set();
  const idPattern = /\b([0-9A-Fa-f]{24})\b/g;
  let match;
  while ((match = idPattern.exec(clean)) !== null) referenced.add(match[1]);

  for (const id of referenced) {
    if (!objects.has(id)) fail(`引用了不存在的对象 ${id}`);
  }
  const rootMatch = /rootObject\s*=\s*([0-9A-Fa-f]{24})/.exec(clean);
  if (!rootMatch) fail('缺少 rootObject');
  else if (!objects.has(rootMatch[1])) fail('rootObject 指向不存在的对象');
  notes.push(`校验 ${referenced.size} 个被引用 ID`);
}

/* ── 3. 递归计算每个文件引用在磁盘上的路径 ──────────────────────────────── */

function field(body, key) {
  const pattern = new RegExp(`\\b${key}\\s*=\\s*"?([^";\\n]+)"?\\s*;`);
  const match = pattern.exec(body);
  return match ? match[1].trim() : null;
}

function listField(body, key) {
  const pattern = new RegExp(`\\b${key}\\s*=\\s*\\(([\\s\\S]*?)\\);`);
  const match = pattern.exec(body);
  if (!match) return [];
  return [...match[1].matchAll(/([0-9A-Fa-f]{24})/g)].map((m) => m[1]);
}

/** id → { path, isa, children } */
const filePaths = new Map();
const groupRoot = (() => {
  for (const [id, object] of objects) {
    if (object.isa === 'PBXProject') {
      const mainGroupId = field(object.body, 'mainGroup');
      return mainGroupId;
    }
  }
  return null;
})();

function walk(groupId, prefix) {
  const group = objects.get(groupId);
  if (!group) return;
  const groupPath = field(group.body, 'path');
  const base = groupPath ? join(prefix, groupPath) : prefix;

  if (group.isa === 'PBXFileReference') {
    const path = field(group.body, 'path');
    if (path) filePaths.set(groupId, path.startsWith('/') ? path : join(prefix, path));
    return;
  }
  for (const childId of listField(group.body, 'children')) {
    const child = objects.get(childId);
    if (!child) continue;
    if (child.isa === 'PBXFileReference') {
      const path = field(child.body, 'path');
      const sourceTree = field(child.body, 'sourceTree');
      if (!path) continue;
      if (sourceTree === 'BUILT_PRODUCTS_DIR') continue;
      filePaths.set(childId, join(base, path));
    } else {
      walk(childId, base);
    }
  }
}

if (groupRoot) walk(groupRoot, iosRoot);

for (const [id, path] of filePaths) {
  if (!existsSync(path)) fail(`文件不存在：${rel(path)}（对象 ${id}）`);
}
notes.push(`校验 ${filePaths.size} 个文件引用路径`);

/* ── 4. Sources / Resources 阶段 ────────────────────────────────────────── */

const phaseFiles = { Sources: [], Resources: [] };
for (const [id, object] of objects) {
  if (object.isa !== 'PBXSourcesBuildPhase' && object.isa !== 'PBXResourcesBuildPhase') continue;
  const kind = object.isa === 'PBXSourcesBuildPhase' ? 'Sources' : 'Resources';
  for (const buildFileId of listField(object.body, 'files')) {
    const buildFile = objects.get(buildFileId);
    if (!buildFile) {
      fail(`${kind} 阶段引用了不存在的构建文件 ${buildFileId}`);
      continue;
    }
    const fileRefId = field(buildFile.body, 'fileRef');
    const path = fileRefId ? filePaths.get(fileRefId) : null;
    if (!fileRefId || !objects.has(fileRefId)) {
      fail(`${kind} 阶段的 ${buildFileId} 缺少有效 fileRef`);
      continue;
    }
    phaseFiles[kind].push(path ? rel(path) : fileRefId);
  }
}

const swiftOnDisk = [];
(function collect(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name.endsWith('.xcodeproj') || entry.name.includes('xcassets')) continue;
      collect(full);
    } else if (entry.name.endsWith('.swift')) {
      swiftOnDisk.push(rel(full));
    }
  }
})(join(iosRoot, 'ZhangShangTangShi'));

for (const swift of swiftOnDisk) {
  if (!phaseFiles.Sources.includes(swift)) fail(`未加入编译阶段：${swift}`);
}
for (const compiled of phaseFiles.Sources) {
  if (!swiftOnDisk.includes(compiled)) fail(`编译阶段引用了不存在的源文件：${compiled}`);
}
notes.push(`编译源文件 ${phaseFiles.Sources.length} 个，磁盘上 ${swiftOnDisk.length} 个`);

const requiredResources = [
  'ZhangShangTangShi/Bridge/bridge-shim.js',
  'ZhangShangTangShi/Resources/Assets.xcassets',
  'ZhangShangTangShi/Resources/www',
];
for (const required of requiredResources) {
  if (!phaseFiles.Resources.includes(required)) fail(`资源未加入拷贝阶段：${required}`);
}
notes.push(`资源条目 ${phaseFiles.Resources.length} 个`);

/* ── 5. Info.plist 与版本号 ─────────────────────────────────────────────── */

const plistPath = join(iosRoot, 'ZhangShangTangShi', 'Resources', 'Info.plist');
if (!existsSync(plistPath)) {
  fail('缺少 Info.plist');
} else {
  const plist = readFileSync(plistPath, 'utf8');
  const version = /<key>CFBundleShortVersionString<\/key>\s*<string>([^<]+)<\/string>/.exec(plist);
  const build = /<key>CFBundleVersion<\/key>\s*<string>([^<]+)<\/string>/.exec(plist);
  if (!version) fail('Info.plist 缺少 CFBundleShortVersionString');
  if (!build) fail('Info.plist 缺少 CFBundleVersion');
  // 双端版本号应一致：Android 的 versionName / versionCode 是唯一基准，
  // 这里动态读取，避免把期望值写死在脚本里（升级版本就误报）。
  const gradlePath = join(iosRoot, '..', 'android', 'app', 'build.gradle');
  if (existsSync(gradlePath)) {
    const gradle = readFileSync(gradlePath, 'utf8');
    const androidName = /versionName\s+'([^']+)'/.exec(gradle);
    const androidCode = /versionCode\s+(\d+)/.exec(gradle);
    if (androidName && version && version[1] !== androidName[1]) {
      fail(`版本号不一致：iOS ${version[1]} / Android ${androidName[1]}`);
    }
    if (androidCode && build && build[1] !== androidCode[1]) {
      fail(`构建号不一致：iOS ${build[1]} / Android ${androidCode[1]}`);
    }
    if (androidName && version) notes.push(`版本 ${version[1]}(${build ? build[1] : '?'}) 与 Android 一致`);
  } else {
    notes.push('未找到 android/app/build.gradle，跳过双端版本号比对');
  }
  if (!plist.includes('UILaunchScreen')) fail('Info.plist 缺少 UILaunchScreen（冷启动会白屏）');
  if (!plist.includes('NSAppTransportSecurity')) fail('Info.plist 缺少 ATS 配置（学工系统 http 外链会被拦）');
}

/* ── 6. 资源内容抽查 ────────────────────────────────────────────────────── */

const wwwIndex = join(iosRoot, 'ZhangShangTangShi', 'Resources', 'www', 'index.html');
if (!existsSync(wwwIndex)) fail('www/index.html 缺失');

const shimPath = join(iosRoot, 'ZhangShangTangShi', 'Bridge', 'bridge-shim.js');
if (existsSync(shimPath)) {
  const shim = readFileSync(shimPath, 'utf8');
  const requiredApi = [
    'isNative', 'getAppVersion', 'httpRequest', 'rsaEncrypt', 'clearSession',
    'saveCredential', 'loadCredential', 'clearCredential', 'openWebView', 'openWifiSettings',
  ];
  for (const api of requiredApi) {
    if (!shim.includes(`${api}:`)) fail(`bridge-shim.js 缺少接口 ${api}`);
  }
}

const iconPath = join(iosRoot, 'ZhangShangTangShi', 'Resources', 'Assets.xcassets',
  'AppIcon.appiconset', 'AppIcon-1024.png');
if (!existsSync(iconPath)) fail('缺少 AppIcon-1024.png');
else {
  const header = readFileSync(iconPath).subarray(0, 24);
  const width = header.readUInt32BE(16);
  const height = header.readUInt32BE(20);
  if (width !== 1024 || height !== 1024) fail(`AppIcon 尺寸为 ${width}x${height}，必须 1024x1024`);
}

/* ── 输出 ───────────────────────────────────────────────────────────────── */

for (const note of notes) console.log(`· ${note}`);
if (problems.length === 0) {
  console.log('\n✅ 工程结构校验通过');
  process.exit(0);
}
console.error(`\n❌ 发现 ${problems.length} 个问题：`);
for (const problem of problems) console.error(`  - ${problem}`);
process.exit(1);
