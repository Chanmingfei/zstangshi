#!/usr/bin/env node
// push-root-config.mjs -- 把 codemagic.yaml 推到仓库根目录（Codemagic 只读根目录的那份）
//
// 背景：仓库根目录原来有一份旧的 codemagic.yaml（workflow 名 ios-native-build），
// Codemagic 会优先读它。新配置必须覆盖到根目录，Codemagic 才能识别 ios-ipa workflow。
//
// 用法：GH_TOKEN=xxx node scripts/push-root-config.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const OWNER = 'Chanmingfei';
const REPO = 'zstangshi';
const BRANCH = 'main';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC_ROOT = path.resolve(here, '..');
const cfg = path.join(SRC_ROOT, 'codemagic.yaml');

const token = process.env.GH_TOKEN;
if (!token) { console.error('缺少 GH_TOKEN'); process.exit(1); }

const API = 'https://api.github.com';

async function api(method, endpoint, body) {
  const res = await fetch(`${API}${endpoint}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'User-Agent': 'zstangshi-push',
      'X-GitHub-Api-Version': '2022-11-28',
      ...(body ? { 'Content-Type': 'application/json' } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch {}
  if (!res.ok) throw new Error(`${method} ${endpoint} -> ${res.status}: ${text.slice(0, 400)}`);
  return json;
}

async function main() {
  const content = fs.readFileSync(cfg, 'utf8');
  console.log('本地 codemagic.yaml 行数:', content.split('\n').length);

  const ref = await api('GET', `/repos/${OWNER}/${REPO}/git/ref/heads/${BRANCH}`);
  const baseSha = ref.object.sha;
  const baseCommit = await api('GET', `/repos/${OWNER}/${REPO}/git/commits/${baseSha}`);
  console.log('base:', baseSha.slice(0, 8));

  // 上传 blob 到根目录
  const blob = await api('POST', `/repos/${OWNER}/${REPO}/git/blobs`, {
    content,
    encoding: 'utf-8'
  });

  // 只用 base_tree + 覆盖一个文件：这样不会动其它任何内容
  const tree = await api('POST', `/repos/${OWNER}/${REPO}/git/trees`, {
    base_tree: baseCommit.tree.sha,
    tree: [{ path: 'codemagic.yaml', mode: '100644', type: 'blob', sha: blob.sha }]
  });

  const commit = await api('POST', `/repos/${OWNER}/${REPO}/git/commits`, {
    message: 'build(ci): 用新的 ios-ipa 工作流替换根目录 codemagic.yaml\n\n' +
      '根目录那份会被 Codemagic 优先读取，之前一直是旧的 ios-native-build。\n' +
      '新工作流：working_directory 切到 ios-native，Xcode 26 校验 + xcodegen + 未签名 IPA。',
    tree: tree.sha,
    parents: [baseSha]
  });
  console.log('commit:', commit.sha.slice(0, 8));

  await api('PATCH', `/repos/${OWNER}/${REPO}/git/refs/heads/${BRANCH}`, {
    sha: commit.sha,
    force: false
  });
  console.log('✅ 根目录 codemagic.yaml 已更新');
}

main().catch(e => { console.error('❌', e.message); process.exit(1); });