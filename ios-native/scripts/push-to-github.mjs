#!/usr/bin/env node
// push-to-github.mjs -- 在 git 传输协议不可用的环境里，用 GitHub REST API 提交文件
//
// 用法：node scripts/push-to-github.mjs
//
// 背景：本机到 GitHub 的 git-upload-pack 连接超时（网络限制），
// 但 REST API 正常。这里用 Trees + Commits API 一次性提交整个 ios-native/ 目录。
//
// 依赖：环境变量 GH_TOKEN（有 repo 权限）

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const OWNER = 'Chanmingfei';
const REPO = 'zstangshi';
const BRANCH = 'main';
const TARGET_DIR = 'ios-native';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC_ROOT = path.resolve(here, '..');

const token = process.env.GH_TOKEN;
if (!token) {
  console.error('缺少 GH_TOKEN 环境变量');
  process.exit(1);
}

const API = 'https://api.github.com';

async function api(method, endpoint, body) {
  const res = await fetch(`${API}${endpoint}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'User-Agent': 'zstangshi-ios-push',
      'X-GitHub-Api-Version': '2022-11-28',
      ...(body ? { 'Content-Type': 'application/json' } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* 非 JSON */ }
  if (!res.ok) {
    throw new Error(`${method} ${endpoint} -> ${res.status}: ${text.slice(0, 500)}`);
  }
  return json;
}

// ── 收集本地文件（排除不该提交的） ──
const SKIP_DIRS = new Set(['.git', 'build', 'DerivedData', '.swiftcheck', 'node_modules']);
const SKIP_FILES = new Set(['.DS_Store']);

function walk(dir, base = '') {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const rel = base ? `${base}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue;
      out.push(...walk(path.join(dir, entry.name), rel));
    } else {
      if (SKIP_FILES.has(entry.name)) continue;
      // 不提交生成的 ipa / 工程文件
      if (entry.name.endsWith('.ipa')) continue;
      if (entry.name.endsWith('.xcarchive')) continue;
      out.push(rel);
    }
  }
  return out;
}

async function main() {
  console.log(`仓库: ${OWNER}/${REPO}  分支: ${BRANCH}`);
  console.log(`本地源: ${SRC_ROOT}`);

  // 1. 取分支 HEAD
  const ref = await api('GET', `/repos/${OWNER}/${REPO}/git/ref/heads/${BRANCH}`);
  const baseSha = ref.object.sha;
  console.log(`base commit: ${baseSha}`);

  // 2. 取 base commit 的 tree
  const baseCommit = await api('GET', `/repos/${OWNER}/${REPO}/git/commits/${baseSha}`);
  const baseTree = baseCommit.tree.sha;
  console.log(`base tree: ${baseTree}`);

  // 3. 逐个上传 blob（先传内容，再组 tree）
  const files = walk(SRC_ROOT).sort();
  console.log(`待提交文件: ${files.length}`);

  const treeEntries = [];
  let uploaded = 0;
  for (const rel of files) {
    const abs = path.join(SRC_ROOT, rel);
    const content = fs.readFileSync(abs);
    // 大文件（图片）用 base64，文本用 utf-8 省流量
    const isBinary = /\.(png|jpg|jpeg|gif|webp|car|ttf|otf)$/i.test(rel);
    let blob;
    if (isBinary) {
      blob = await api('POST', `/repos/${OWNER}/${REPO}/git/blobs`, {
        content: content.toString('base64'),
        encoding: 'base64'
      });
    } else {
      blob = await api('POST', `/repos/${OWNER}/${REPO}/git/blobs`, {
        content: content.toString('utf8'),
        encoding: 'utf-8'
      });
    }
    treeEntries.push({
      path: `${TARGET_DIR}/${rel}`,
      mode: '100644',
      type: 'blob',
      sha: blob.sha
    });
    uploaded++;
    if (uploaded % 10 === 0) process.stdout.write(`  已上传 ${uploaded}/${files.length}\r`);
  }
  console.log(`  已上传 ${uploaded}/${files.length}`);

  // 4. 需要删除的旧文件：base tree 里 ios-native/ 下、但本地不存在的
  const baseTreeFull = await api('GET', `/repos/${OWNER}/${REPO}/git/trees/${baseTree}?recursive=1`);
  const localSet = new Set(treeEntries.map(e => e.path));
  const deletions = [];
  if (!baseTreeFull.truncated) {
    for (const item of baseTreeFull.tree) {
      if (item.type !== 'blob') continue;
      if (!item.path.startsWith(`${TARGET_DIR}/`)) continue;
      if (!localSet.has(item.path)) deletions.push(item.path);
    }
  }
  console.log(`待删除旧文件: ${deletions.length}`);
  for (const p of deletions) {
    treeEntries.push({ path: p, mode: '100644', type: 'blob', sha: null });
  }

  // 5. 建 tree
  const newTree = await api('POST', `/repos/${OWNER}/${REPO}/git/trees`, {
    base_tree: baseTree,
    tree: treeEntries
  });
  console.log(`new tree: ${newTree.sha}`);

  // 6. 建 commit
  const message = [
    'feat(ios): 原生版 v4.0.0 完整重写',
    '',
    '- 4 个主页面 + 自绘胶囊 Dock（对齐安卓 CapsuleDock/PageDeck）',
    '- 修掉旧 iOS 版 9 个致命 bug（Cookie 只取首条 / RSA 剥错符号位 / 同步静默丢数据 / 5 tab 错位等）',
    '- 公交模块按真实协议重写：车来了签名+AES 解密、定位修复、三模式线路详情',
    '- 补全课程编辑器、调课/停课/补课/放假、作息设置、推荐课表四级级联',
    '- 小组件 5 种状态；Codemagic 出包配置'
  ].join('\n');

  const commit = await api('POST', `/repos/${OWNER}/${REPO}/git/commits`, {
    message,
    tree: newTree.sha,
    parents: [baseSha]
  });
  console.log(`new commit: ${commit.sha}`);

  // 7. 更新分支引用
  await api('PATCH', `/repos/${OWNER}/${REPO}/git/refs/heads/${BRANCH}`, {
    sha: commit.sha,
    force: false
  });
  console.log(`\n✅ 已推送到 ${OWNER}/${REPO}@${BRANCH}`);
  console.log(`   https://github.com/${OWNER}/${REPO}/tree/${BRANCH}/${TARGET_DIR}`);
}

main().catch(err => {
  console.error('\n❌ 失败:', err.message);
  process.exit(1);
});