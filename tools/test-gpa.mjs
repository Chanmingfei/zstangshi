#!/usr/bin/env node
/**
 * test-gpa.mjs —— 学分绩点解析回归测试
 *
 * 背景：学校口径对公选课不计入总绩点、重修取首次成绩、五级制单独折算，本地加权只能算出
 * 3.07，而教务「学生学业情况查询」印的是 3.16。所以 App 的 GPA 一律取教务原样输出，
 * 客户端零计算。本测试把**真实教务页面的原文**固化成 fixture，验证解析器能一字不差地
 * 抠出那些数字 —— 将来写 remoteProvider 时直接搬这两个函数即可。
 *
 * 覆盖：
 *   A. 顶部摘要：GPA / 统计时间 / 计划·通过·未通过·未修·在读 / 计划外
 *   B. 学分树：21 个节点、缩进层级、学分三件套、门次达成度、共 N 门 / 通过 N 门、并且|或者|缺省
 *   C. 全角（）与半角() 括号都能匹配（教务不同节点模板不完全一致）
 *   D. `web/data/demo/grades.js` 的 OFFICIAL_GPA 必须与解析结果完全一致（防止两边漂移）
 *
 * 退出码即结论：0 = 全绿，1 = 有差异（并打印逐条差异）。
 *
 * 运行：node tools/test-gpa.mjs
 */

import { OFFICIAL_GPA } from '../web/data/demo/grades.js';

/* ══════════════════════════════════════════════════════════════════════════
 * 一、fixture：2026-11-06 00:36 从 jwxt.tstc.edu.cn 抓下来的原文，一字不改
 * ════════════════════════════════════════════════════════════════════════ */

/** 页面顶部摘要（逐字，含空格与标点） */
export const FIXTURE_HEADER =
  '陈铭霏 同学，您的课程修读情况（供参考）：（统计时间2026-11-06 00:36:27之前有效）' +
  '当前所有课程 平均学分绩点（GPA）： 3.16 计划总课程 99 门 通过 82 门，未通过 0 门；' +
  '未修 11 门；在读 6 门！计划外：通过 0 门，未通过 0 门';

/**
 * 学分树原文（行首空格数 = 缩进层级，每 2 空格一级）。
 * 刻意用数组而不是模板字符串：模板字符串的缩进会被编辑器/格式化工具吃掉，
 * 而这个 fixture 的价值恰恰在于「缩进即层级」，必须逐字锁死。
 */
export const FIXTURE_TREE_LINES = [
  '主修',
  '  必修课  要求学分:27.75  获得学分:123.0  未获得学分:0.0  并且',
  '    必修课公共部分  要求学分:0.0  获得学分:117.5  未获得学分:0.0  要求门次:2  到达要求:.60  未达到要求:.0  共（89）门 通过（80）门',
  '    必修课方向部分  要求学分:0.0  获得学分:5.5  未获得学分:0.0  要求门次:2  到达要求:.4  未达到要求:.0  缺省',
  '      公费师范生方向  要求学分:0.0  获得学分:5.5  未获得学分:0.0  要求门次:2  到达要求:.4  未达到要求:.0',
  '  限选课  要求学分:0.0  获得学分:5.5  未获得学分:0.0  并且',
  '    限选课公共部分  要求学分:0.0  获得学分:5.5  未获得学分:0.0  要求门次:2  到达要求:.3  未达到要求:.0  共（4）门 通过（3）门',
  '    限选课方向部分  要求学分:0.0  获得学分:0.0  未获得学分:0.0  要求门次:2  到达要求:.0  未达到要求:.2  或者',
  '      公费师范生方向  要求学分:0.0  获得学分:0.0  未获得学分:0.0  要求门次:2  到达要求:.0  未达到要求:2  共（0）门 通过（0）门',
  '  任选课  要求学分:21.0  获得学分:10.5  未获得学分:10.5  共（14）门 通过（7）门',
  '  人文社会与科学素养选修课  要求学分:11.0  获得学分:16.0  未获得学分:0.0  并且',
  '    人文社科类限选课  要求学分:8.0  获得学分:12.0  未获得学分:0.0',
  '      外语技能与信息技术类  要求学分:3.0  获得学分:6.0  未获得学分:0.0  共（2）门 通过（2）门',
  '      艺术修养类  要求学分:2.0  获得学分:3.0  未获得学分:0.0  共（1）门 通过（1）门',
  '      交叉融合类  要求学分:2.0  获得学分:2.0  未获得学分:0.0  共（1）门 通过（1）门',
  '      礼仪与沟通类  要求学分:1.0  获得学分:1.0  未获得学分:0.0  要求门次:1  到达要求:1  未达到要求:0  共（1）门 通过（1）门',
  '    人文社素养任选课  要求学分:3.0  获得学分:4.0  未获得学分:0.0  共（2）门 通过（2）门',
  '  教师教育非必修课  要求学分:1.0  获得学分:2.0  未获得学分:0.0  共（1）门 通过（1）门',
  '资格证书信息',
  '其他课程  共（0）门 通过（0）门',
  '创新创业情况尚未数据信息',
];

/** 拼成整段文本（解析函数吃字符串，和将来抓到的 innerText 同形） */
export const FIXTURE_TREE = FIXTURE_TREE_LINES.join('\n');

/* ══════════════════════════════════════════════════════════════════════════
 * 二、解析器（纯函数，无 DOM / 无正则状态残留）
 * ════════════════════════════════════════════════════════════════════════ */

/**
 * 规范教务的小数写法：'.60' → '0.60'、'.4' → '0.4'。
 * 只补前导 0，不改位数 —— '0.60' 和 '0.6' 在页面上是两个写法，保真优先。
 * @param {string|undefined} raw
 * @returns {string|undefined}
 */
function normDecimal(raw) {
  if (raw === undefined) return undefined;
  return raw.startsWith('.') ? `0${raw}` : raw;
}

/**
 * 抠「共（N）门 / 通过（N）门」这类计数，全角半角括号都认。
 * @param {string} text
 * @param {string} keyword '共' | '通过'
 * @returns {number|undefined}
 */
function pickParenCount(text, keyword) {
  const re = new RegExp(`${keyword}[（(](\\d+)[）)]门`);
  const match = re.exec(text);
  return match ? Number(match[1]) : undefined;
}

/**
 * 抠「通过 82 门」这类不带括号的计数。
 * 「未通过 0 门」里的「通过」必须排除，否则会把 0 当成通过数。
 * @param {string} text
 * @param {string} keyword '通过' | '未通过' | '未修' | '在读'
 * @returns {number|null}
 */
function pickPlainCount(text, keyword) {
  const re = new RegExp(`${keyword}\\s*(\\d+)\\s*门`, 'g');
  let match;
  while ((match = re.exec(text)) !== null) {
    // 「未通过」开头的那一处不算「通过」
    if (keyword === '通过' && text[match.index - 1] === '未') continue;
    return Number(match[1]);
  }
  return null;
}

/**
 * 解析「学生学业情况查询」页面顶部的摘要行。
 *
 * 原文形如：
 *   陈铭霏 同学，……（统计时间2026-11-06 00:36:27之前有效）当前所有课程 平均学分绩点（GPA）： 3.16
 *   计划总课程 99 门 通过 82 门，未通过 0 门；未修 11 门；在读 6 门！计划外：通过 0 门，未通过 0 门
 *
 * @param {string} text 页面顶部纯文本
 * @returns {{
 *   gpa: number|null,
 *   statsAt: string|null,
 *   courseCount: {planned: number|null, passed: number|null, failed: number|null,
 *                 notTaken: number|null, inProgress: number|null,
 *                 extraPassed: number|null, extraFailed: number|null}
 * }}
 */
export function parseGpaHeader(text) {
  const src = String(text || '');
  const gpaMatch = /平均学分绩点（GPA）：\s*([\d.]+)/.exec(src);
  const statsMatch = /统计时间\s*([\d-]{10}\s+[\d:]{8})\s*之前有效/.exec(src);

  // 「！计划外：……」之后是另一段口径，必须先切开再统计，否则通过/未通过会被后段覆盖
  const bangAt = src.indexOf('！');
  const inside = bangAt === -1 ? src : src.slice(0, bangAt);
  const outside = bangAt === -1 ? '' : src.slice(bangAt + 1);

  return {
    gpa: gpaMatch ? Number(gpaMatch[1]) : null,
    statsAt: statsMatch ? statsMatch[1].replace(/\s+/g, ' ') : null,
    courseCount: {
      planned: /计划总课程\s*(\d+)\s*门/.exec(src)
        ? Number(/计划总课程\s*(\d+)\s*门/.exec(src)[1])
        : null,
      passed: pickPlainCount(inside, '通过'),
      failed: pickPlainCount(inside, '未通过'),
      notTaken: pickPlainCount(inside, '未修'),
      inProgress: pickPlainCount(inside, '在读'),
      extraPassed: pickPlainCount(outside, '通过'),
      extraFailed: pickPlainCount(outside, '未通过'),
    },
  };
}

/**
 * 解析学分构成树（缩进即层级）。
 *
 * 为什么用「行首空格数 / 2」推 depth 而不是解析 DOM：教务这一块的容器标签混乱
 * （有时 <ul>、有时 <div>、有时干脆是 <br> 换行），只有缩进是页面上肉眼可见的真实层级。
 *
 * @param {string} text 学分树区域的纯文本（每行一个节点）
 * @returns {Array<object>} 扁平节点数组，见 `web/data/types.js` 的 GpaModule
 */
export function parseCreditTree(text) {
  const out = [];
  for (const rawLine of String(text || '').split('\n')) {
    const line = rawLine.replace(/\s+$/, '');
    if (!line.trim()) continue;

    const indent = line.length - line.trimStart().length;
    const body = line.trim();
    // 节点名与第一个数据列之间是 2 个以上空格；单个空格只出现在「共（N）门 通过（N）门」内部
    const parts = body.split(/\s{2,}/);
    const name = parts[0];
    const rest = parts.slice(1).join('  ');

    const num = (re) => {
      const match = re.exec(rest);
      return match ? Number(match[1]) : undefined;
    };
    const str = (re) => {
      const match = re.exec(rest);
      return match ? normDecimal(match[1]) : undefined;
    };

    const logicMatch = /(并且|或者|缺省)\s*$/.exec(rest);
    /** @type {Record<string, unknown>} */
    const node = {
      name,
      depth: Math.floor(indent / 2),
      requireCredits: num(/要求学分:([\d.]+)/) ?? 0,
      earnedCredits: num(/获得学分:([\d.]+)/) ?? 0,
      missingCredits: num(/未获得学分:([\d.]+)/) ?? 0,
    };

    const requiredCount = num(/要求门次:(\d+)/);
    if (requiredCount !== undefined) node.requiredCount = requiredCount;
    const reachedRaw = str(/到达要求:([\d.]+)/);
    if (reachedRaw !== undefined) node.reachedRaw = reachedRaw;
    const unreachedRaw = str(/未达到要求:([\d.]+)/);
    if (unreachedRaw !== undefined) node.unreachedRaw = unreachedRaw;
    const courseTotal = pickParenCount(rest, '共');
    if (courseTotal !== undefined) node.courseTotal = courseTotal;
    const coursePassed = pickParenCount(rest, '通过');
    if (coursePassed !== undefined) node.coursePassed = coursePassed;
    if (logicMatch) node.logic = logicMatch[1];

    out.push(node);
  }
  return out;
}

/* ══════════════════════════════════════════════════════════════════════════
 * 三、断言
 * ════════════════════════════════════════════════════════════════════════ */

/** @type {string[]} */
const failures = [];
let passed = 0;

/**
 * @param {boolean} ok
 * @param {string} label
 * @param {unknown} [actual]
 * @param {unknown} [expected]
 */
function check(ok, label, actual, expected) {
  if (ok) {
    passed += 1;
    return;
  }
  failures.push(`${label}\n      期望: ${JSON.stringify(expected)}\n      实际: ${JSON.stringify(actual)}`);
}

/** 按名字取节点（同名节点有两个：公费师范生方向，靠 depth 区分） */
function nodeOf(list, name, depth) {
  return list.find((item) => item.name === name && (depth === undefined || item.depth === depth));
}

const header = parseGpaHeader(FIXTURE_HEADER);
const tree = parseCreditTree(FIXTURE_TREE);

/* A. 顶部摘要 */
check(header.gpa === 3.16, 'A1 GPA 应为 3.16', header.gpa, 3.16);
check(header.statsAt === '2026-11-06 00:36:27', 'A2 统计时间应逐字保留', header.statsAt, '2026-11-06 00:36:27');
check(header.courseCount.planned === 99, 'A3 计划总课程 99 门', header.courseCount.planned, 99);
check(header.courseCount.passed === 82, 'A4 通过 82 门（不能被「未通过 0 门」污染）', header.courseCount.passed, 82);
check(header.courseCount.failed === 0, 'A5 未通过 0 门', header.courseCount.failed, 0);
check(header.courseCount.notTaken === 11, 'A6 未修 11 门', header.courseCount.notTaken, 11);
check(header.courseCount.inProgress === 6, 'A7 在读 6 门', header.courseCount.inProgress, 6);
check(header.courseCount.extraPassed === 0, 'A8 计划外通过 0 门', header.courseCount.extraPassed, 0);
check(header.courseCount.extraFailed === 0, 'A9 计划外未通过 0 门', header.courseCount.extraFailed, 0);

/* B. 学分树 */
check(tree.length === 21, 'B1 学分树应有 21 个节点', tree.length, 21);
check(tree[0].name === '主修', 'B2 首节点应是主修', tree[0].name, '主修');
check(tree[0].depth === 0, 'B3 主修 depth=0', tree[0].depth, 0);
check(tree[1].name === '必修课' && tree[1].depth === 1, 'B4 必修课应为第二节点且 depth=1', tree[1], '必修课/depth1');
check(tree[1].requireCredits === 27.75, 'B5 必修课要求学分 27.75', tree[1].requireCredits, 27.75);
check(tree[1].earnedCredits === 123, 'B6 必修课获得学分 123.0', tree[1].earnedCredits, 123);
check(tree[1].logic === '并且', 'B7 必修课规则为「并且」', tree[1].logic, '并且');

const common = nodeOf(tree, '必修课公共部分', 2);
check(Boolean(common), 'B8 应存在必修课公共部分(depth=2)', Boolean(common), true);
check(common && common.depth === 2, 'B9 必修课公共部分 depth=2', common && common.depth, 2);
check(common && common.earnedCredits === 117.5, 'B10 必修课公共部分获得学分 117.5', common && common.earnedCredits, 117.5);
check(common && common.courseTotal === 89, 'B11 共（89）门', common && common.courseTotal, 89);
check(common && common.coursePassed === 80, 'B12 通过（80）门', common && common.coursePassed, 80);
check(common && common.reachedRaw === '0.60', 'B13 到达要求 .60 应规范为 0.60（保两位）', common && common.reachedRaw, '0.60');
check(common && common.unreachedRaw === '0.0', 'B14 未达到要求 .0 应规范为 0.0', common && common.unreachedRaw, '0.0');
check(common && common.requiredCount === 2, 'B15 要求门次 2', common && common.requiredCount, 2);
check(common && common.logic === undefined, 'B16 必修课公共部分本身不带规则标记（规则挂在父节点）', common && common.logic, undefined);

const elective = nodeOf(tree, '任选课');
check(elective && elective.requireCredits === 21, 'B17 任选课要求学分 21', elective && elective.requireCredits, 21);
check(elective && elective.earnedCredits === 10.5, 'B18 任选课获得学分 10.5', elective && elective.earnedCredits, 10.5);
check(elective && elective.missingCredits === 10.5, 'B19 任选课未获得学分 10.5', elective && elective.missingCredits, 10.5);
check(elective && elective.courseTotal === 14, 'B20 任选课共（14）门', elective && elective.courseTotal, 14);

check(tree.filter((item) => item.depth === 1).length === 5, 'B21 depth=1 应恰好 5 个（必修/限选/任选/人文素养/教师教育）', tree.filter((item) => item.depth === 1).length, 5);
check(tree.filter((item) => item.depth === 2).length === 6, 'B22 depth=2 应恰好 6 个', tree.filter((item) => item.depth === 2).length, 6);
check(tree.filter((item) => item.depth === 3).length === 6, 'B23 depth=3 应恰好 6 个', tree.filter((item) => item.depth === 3).length, 6);

const literacy = nodeOf(tree, '人文社会与科学素养选修课');
check(literacy && literacy.depth === 1, 'B24 人文社会与科学素养选修课 depth=1', literacy && literacy.depth, 1);
check(literacy && literacy.logic === '并且', 'B25 该节点规则为「并且」', literacy && literacy.logic, '并且');
check(literacy && literacy.requireCredits === 11 && literacy.earnedCredits === 16, 'B26 要求 11 / 获得 16', literacy && `${literacy.requireCredits}/${literacy.earnedCredits}`, '11/16');

const direction = nodeOf(tree, '必修课方向部分');
check(direction && direction.logic === '缺省', 'B27 必修课方向部分规则为「缺省」', direction && direction.logic, '缺省');
const limitDir = nodeOf(tree, '限选课方向部分');
check(limitDir && limitDir.logic === '或者', 'B28 限选课方向部分规则为「或者」', limitDir && limitDir.logic, '或者');
const normalDir = tree.find((item) => item.name === '公费师范生方向' && item.depth === 3 && item.courseTotal === undefined);
check(Boolean(normalDir) && normalDir.earnedCredits === 5.5, 'B29 同名「公费师范生方向」靠 depth 区分，首个获得 5.5', normalDir && normalDir.earnedCredits, 5.5);
check(tree.filter((item) => item.name === '公费师范生方向').length === 2, 'B30 公费师范生方向出现 2 次', tree.filter((item) => item.name === '公费师范生方向').length, 2);

const manner = nodeOf(tree, '礼仪与沟通类');
check(manner && manner.reachedRaw === '1' && manner.unreachedRaw === '0', 'B31 整数达成度原样保留（1 / 0）', manner && `${manner.reachedRaw}/${manner.unreachedRaw}`, '1/0');

check(tree[18].name === '资格证书信息' && tree[18].depth === 0, 'B32 第 19 节点为资格证书信息(depth=0)', tree[18], '资格证书信息');
check(tree[19].name === '其他课程' && tree[19].courseTotal === 0 && tree[19].coursePassed === 0, 'B33 其他课程 共0/通过0', tree[19], '其他课程');
check(tree[20].name === '创新创业情况尚未数据信息' && tree[20].depth === 0, 'B34 末节点为创新创业情况尚未数据信息', tree[20], '创新创业情况尚未数据信息');

/* C. 全角半角括号都要能匹配 */
const halfWidth = FIXTURE_TREE.replace(/（/g, '(').replace(/）/g, ')');
const treeHalf = parseCreditTree(halfWidth);
check(JSON.stringify(treeHalf) === JSON.stringify(tree), 'C1 半角括号解析结果应与全角完全一致', treeHalf.length, tree.length);
check(treeHalf[2].courseTotal === 89 && treeHalf[2].coursePassed === 80, 'C2 半角括号共(89)门 通过(80)门', treeHalf[2].courseTotal, 89);
check(treeHalf[9].courseTotal === 14, 'C3 半角括号任选课共(14)门', treeHalf[9].courseTotal, 14);

/* D. 演示数据快照必须与解析结果一致（防两边漂移） */
check(OFFICIAL_GPA.gpa === header.gpa, 'D1 DEMO.gpa 的 GPA 与解析值一致', OFFICIAL_GPA.gpa, header.gpa);
check(OFFICIAL_GPA.gpaOrigin === 'official', 'D2 gpaOrigin 必须是 official', OFFICIAL_GPA.gpaOrigin, 'official');
check(OFFICIAL_GPA.statsAt === header.statsAt, 'D3 statsAt 与页面一致', OFFICIAL_GPA.statsAt, header.statsAt);
check(JSON.stringify(OFFICIAL_GPA.courseCount) === JSON.stringify(header.courseCount), 'D4 courseCount 与页面一致', OFFICIAL_GPA.courseCount, header.courseCount);
check(OFFICIAL_GPA.modules.length === tree.length, 'D5 快照节点数与原文一致', OFFICIAL_GPA.modules.length, tree.length);
check(JSON.stringify(OFFICIAL_GPA.modules) === JSON.stringify(tree), 'D6 快照学分树与原文逐字段一致（顺序/层级/数值）', OFFICIAL_GPA.modules.length, tree.length);
check(OFFICIAL_GPA.disclaimer === '此页面信息仅做学业修读情况参考。', 'D7 免责声明逐字保留', OFFICIAL_GPA.disclaimer, '此页面信息仅做学业修读情况参考。');

/* ══════════════════════════════════════════════════════════════════════════
 * 四、汇总
 * ════════════════════════════════════════════════════════════════════════ */

if (failures.length > 0) {
  process.stderr.write(`\n  [FAIL] 学分绩点解析 ${failures.length} 条断言未通过\n\n`);
  for (const item of failures) process.stderr.write(`    ✗ ${item}\n`);
  process.stderr.write('\n');
  process.exitCode = 1;
} else {
  process.stdout.write(
    `\n  [PASS] 学分绩点解析 PASS ${passed + failures.length} 条断言全部通过` +
      `（GPA ${header.gpa} / 树 ${tree.length} 节点 / 计划 ${header.courseCount.planned} 门 通过 ${header.courseCount.passed} 门）\n\n`
  );
  process.exitCode = 0;
}
