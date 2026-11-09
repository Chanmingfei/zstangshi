#!/usr/bin/env node
/**
 * test-local-timetable.mjs —— 本地课表（可编辑）回归测试
 *
 * 重点验证「教务同步与手工修改怎么共存」这条规则链，它比 UI 更容易悄悄坏：
 *   · 手动新建的课同步后不能被删
 *   · 改动过的教务课不能被下一次同步覆盖回去
 *   · 教务取消的课要删掉（否则课表会永远留着一门已经不上的课）
 *
 * 存储层在 Node 里会走内存兜底（`window` 不存在 → isAvailable() 返回 false），
 * 所以这里可以直接测 readLocal / writeLocal / saveImported 的往返。
 *
 * 运行：node tools/test-local-timetable.mjs
 */

import {
  SOURCE,
  normalizeWeeks,
  normalizeSections,
  deriveWeeks,
  makeCustomCourse,
  mergeImported,
  upsertCourse,
  updateCourse,
  deleteCourse,
  skipWeek,
  readLocal,
  writeLocal,
  saveImported,
  resetLocal,
  hasLocal,
  readLatestLocal,
  hasAnyLocal,
} from '../web/data/localTimetable.js';
import * as storage from '../web/core/storage.js';
import { importCourses, restoreFromJwxt, saveBulkImport, saveRestored } from '../web/data/timetableBulk.js';

let passed = 0;
const failures = [];

function check(name, condition) {
  if (condition) {
    passed += 1;
  } else {
    failures.push(name);
    console.error(`  [FAIL] ${name}`);
  }
}

/** 造一门教务课 */
function jwxtCourse(id, patch = {}) {
  return {
    id,
    name: `课程${id}`,
    teacher: '张老师',
    location: 'A101',
    credits: 3,
    courseType: 'required',
    weeks: [1, 2, 3, 4],
    weekRange: [1, 4],
    weekParity: 'all',
    dayOfWeek: 1,
    sections: [1, 2],
    startTime: '08:00',
    endTime: '09:40',
    ...patch,
  };
}

/* ── A. 归一化 ──────────────────────────────────────────────────────────── */

check('A1 周次去重升序并过滤越界值', JSON.stringify(normalizeWeeks([4, 2, 2, 0, 31, '3', 'x'])) === '[2,3,4]');
check('A2 节次只保留 1-12', JSON.stringify(normalizeSections([13, 0, 2, 2, '5'])) === '[2,5]');

/* ── B. 派生字段 ────────────────────────────────────────────────────────── */

check('B1 连续周次 → all', JSON.stringify(deriveWeeks([1, 2, 3, 16])) === JSON.stringify({ weekRange: [1, 16], weekParity: 'all' }));
check('B2 全奇数 → odd', deriveWeeks([1, 3, 5]).weekParity === 'odd');
check('B3 全偶数 → even', deriveWeeks([2, 4, 6]).weekParity === 'even');
check('B4 空周次兜底不抛错', JSON.stringify(deriveWeeks([])) === JSON.stringify({ weekRange: [1, 1], weekParity: 'all' }));
check('B5 单周不标单双周', deriveWeeks([3]).weekParity === 'all');

/* ── C. 手动新建 ────────────────────────────────────────────────────────── */

const custom = makeCustomCourse(
  { name: '  ', teacher: '李老师', location: '', weeks: [1, 3, 5], dayOfWeek: 3, sections: [3, 4, 5], startTime: '09:55', endTime: '12:15' },
  1700000000000
);
check('C1 空课名给默认值', custom.name === '未命名课程');
check('C2 空地点给「未安排」', custom.location === '未安排');
check('C3 来源标记为 custom', custom.source === SOURCE.Custom);
check('C4 id 带 custom_ 前缀', /^custom_/.test(custom.id));
check('C5 节次连续化', JSON.stringify(custom.sections) === '[3,4,5]');
check('C6 周次派生正确（全奇数）', custom.weekParity === 'odd' && custom.weekRange[0] === 1 && custom.weekRange[1] === 5);
check('C7 起止时间原样保留', custom.startTime === '09:55' && custom.endTime === '12:15');
check('C8 学分缺省为 0 而不是 NaN', custom.credits === 0);

/* ── D. 教务同步合并 ────────────────────────────────────────────────────── */

const localList = [
  JWXT_LOCAL(),
  { ...jwxtCourse('e1'), source: SOURCE.Edited, name: '改过的课', weeks: [5, 6] },
  { ...jwxtCourse('c1'), source: SOURCE.Custom, name: '我加的课' },
  { ...jwxtCourse('gone'), source: SOURCE.Jwxt, name: '教务已取消的课' },
];

function JWXT_LOCAL() {
  return { ...jwxtCourse('j1'), source: SOURCE.Jwxt, colorIndex: 3, note: '带错题本' };
}

const incomingList = [
  jwxtCourse('j1', { name: '教务改名了', location: 'B202' }),
  jwxtCourse('e1', { name: '教务又想覆盖', location: 'C303' }),
  jwxtCourse('j2', { name: '新开的课' }),
];

const merged = mergeImported(localList, incomingList);
const byId = Object.fromEntries(merged.courses.map((course) => [course.id, course]));

check('D1 教务新课被加入', byId.j2 && byId.j2.source === SOURCE.Jwxt);
check('D2 未改动的教务课被覆盖', byId.j1.name === '教务改名了' && byId.j1.location === 'B202');
check('D3 覆盖时保留用户配色', byId.j1.colorIndex === 3);
check('D4 覆盖时保留用户备注', byId.j1.note === '带错题本');
check('D5 改过的课不被覆盖', byId.e1.name === '改过的课' && byId.e1.location === 'A101');
check('D6 手动新建的课不被删', byId.c1 && byId.c1.name === '我加的课');
check('D7 教务取消且未改动的课被删', !byId.gone);
check('D8 统计：新增 1', merged.stats.added === 1);
check('D9 统计：更新 1', merged.stats.updated === 1);
check('D10 统计：删除 1', merged.stats.removed === 1);
check('D11 统计：保留 2（改过的 + 手动的）', merged.stats.kept === 2);
check('D12 输入数组未被就地修改', localList.length === 4 && localList[0].name === '课程j1');

/* ── E. 修改 ────────────────────────────────────────────────────────────── */

const baseLocal = { courses: [{ ...jwxtCourse('x1'), source: SOURCE.Jwxt }, { ...jwxtCourse('x2'), source: SOURCE.Custom }] };
const edited = updateCourse(baseLocal, 'x1', { weeks: [2, 4, 6], sections: [5, 6] });
const editedX1 = edited.courses.find((course) => course.id === 'x1');
check('E1 改周次后派生字段跟着变', editedX1.weekParity === 'even' && editedX1.weekRange[1] === 6);
check('E2 教务课被改后标记 edited', editedX1.source === SOURCE.Edited);
check('E3 未传的字段保持原值', editedX1.name === '课程x1' && editedX1.teacher === '张老师');
check('E4 手动课改了仍是 custom', updateCourse(baseLocal, 'x2', { name: '改名' }).courses.find((c) => c.id === 'x2').source === SOURCE.Custom);
check('E5 改不存在的 id 不新增课程', updateCourse(baseLocal, 'nope', { name: 'x' }).courses.length === 2);

/* ── F. 删除 / 单周停课 ─────────────────────────────────────────────────── */

check('F1 删除只删目标课', deleteCourse(baseLocal, 'x1').courses.length === 1);
check('F2 单周停课只摘掉那一周', JSON.stringify(skipWeek(baseLocal, 'x1', 2).courses.find((c) => c.id === 'x1').weeks) === '[1,3,4]');
check('F3 停课后的课仍是 edited（不被下次同步覆盖）', skipWeek(baseLocal, 'x1', 2).courses.find((c) => c.id === 'x1').source === SOURCE.Edited);
check('F4 停到没有周次也不抛错', skipWeek({ courses: [{ ...jwxtCourse('x3'), weeks: [2], source: SOURCE.Jwxt }] }, 'x3', 2).courses[0].weeks.length === 0);
check('F5 upsert 覆盖同 id 而不是追加', upsertCourse(baseLocal, { ...jwxtCourse('x1'), name: '替换' }).courses.length === 2);

/* ── G. 存储往返 ────────────────────────────────────────────────────────── */

const SEM = 'test-2026-1';
resetLocal(SEM);
check('G1 初始为空', hasLocal(SEM) === false);
check('G2 空表读出空数组', readLocal(SEM).courses.length === 0);

const saved = writeLocal(SEM, { courses: [{ ...jwxtCourse('s1'), source: SOURCE.Custom }] });
check('G3 写入后 hasLocal 为 true', hasLocal(SEM) === true);
check('G4 写入自动带 updatedAt', Boolean(saved.updatedAt));
check('G5 读出内容一致', readLocal(SEM).courses[0].id === 's1');

const imported = saveImported(SEM, [jwxtCourse('s1'), jwxtCourse('s2')]);
check('G6 导入合并进本地', imported.local.courses.length === 2);
check('G7 导入记录 importedAt', Boolean(imported.local.importedAt));
check('G8 导入后仍是有效结构', readLocal(SEM).courses.length === 2);

resetLocal(SEM);
check('G9 reset 后回到空', hasLocal(SEM) === false);

/* ── H. 免登录查看：跨学期兜底 + 清缓存不误删 ───────────────────────────── */

const A = 'test-2025-2';
const B = 'test-2026-1';
resetLocal(A);
resetLocal(B);
writeLocal(A, { courses: [{ ...jwxtCourse('a1'), source: SOURCE.Jwxt }] });
writeLocal(B, { courses: [{ ...jwxtCourse('b1'), source: SOURCE.Custom }] });
check('H1 两个学期各自独立', readLocal(A).courses[0].id === 'a1' && readLocal(B).courses[0].id === 'b1');
check('H2 最近一份是刚写的那学期', readLatestLocal().semesterId === B);
check('H3 任意学期有课表即可免登录', hasAnyLocal() === true);
check('H4 当前学期为空时也能取到兜底数据', readLatestLocal().data.courses.length === 1);

// 清缓存 / 退出登录走 storage.clearAll —— 课表必须活得下来，别的缓存照删
storage.set('grades.all', { any: true });
storage.set('theme', 'dark');
const removed = storage.clearAll({ keep: ['theme', 'rememberId', 'source'] });
check('H5 clearAll 之后本地课表仍在', hasLocal(A) === true && hasLocal(B) === true);
check('H6 clearAll 照删普通缓存键', storage.has('grades.all') === false && removed >= 1);
check('H7 clearAll 保留 keep 名单里的键', storage.get('theme') === 'dark');

resetLocal(A);
resetLocal(B);
check('H8 清干净后 hasAnyLocal 为 false', hasAnyLocal() === false);

/* ── I. 批量导入 / 以教务为准恢复 ───────────────────────────────────────── */

// 推荐课表「一键导入」：只增不改
const mine = { courses: [{ ...jwxtCourse('m1'), name: '我改过的课', source: SOURCE.Edited }] };
const bulk = importCourses(mine, [jwxtCourse('m1'), jwxtCourse('m2')]);
check('I1 已存在的课不被推荐课表覆盖', bulk.local.courses.find((c) => c.id === 'm1').name === '我改过的课');
check('I2 本地没有的课被追加', bulk.local.courses.some((c) => c.id === 'm2'));
check('I3 统计新增 1 门 / 跳过 1 门', bulk.added === 1 && bulk.skipped === 1);
check('I4 新导入的标记为 jwxt（个人课表同步时可接管）', bulk.local.courses.find((c) => c.id === 'm2').source === SOURCE.Jwxt);

// 重复导入不会翻倍
const again = importCourses(bulk.local, [jwxtCourse('m1'), jwxtCourse('m2')]);
check('I5 重复导入零新增', again.added === 0 && again.local.courses.length === 2);

// 「重新与教务同步」：手改的被覆盖、教务取消的被删、手动新建的保留
const messy = {
  courses: [
    { ...jwxtCourse('r1'), weeks: [9], source: SOURCE.Edited },
    { ...jwxtCourse('r2'), source: SOURCE.Jwxt },
    { ...jwxtCourse('r3'), source: SOURCE.Custom },
  ],
};
const restored = restoreFromJwxt(messy.courses, [jwxtCourse('r1')]);
check('I6 手改的课被教务版本覆盖', restored.courses.find((c) => c.id === 'r1').weeks.join() === '1,2,3,4');
check('I7 教务已取消的课被删掉', !restored.courses.some((c) => c.id === 'r2'));
check('I8 手动新建的课保留', restored.courses.some((c) => c.id === 'r3' && c.source === SOURCE.Custom));
check('I9 恢复统计口径正确', restored.stats.updated === 1 && restored.stats.removed === 1 && restored.stats.kept === 1);

// 落盘往返
const SEM2 = 'test-2026-2';
resetLocal(SEM2);
saveBulkImport(SEM2, [jwxtCourse('b1'), jwxtCourse('b2')]);
check('I10 批量导入已落盘', readLocal(SEM2).courses.length === 2);
const reSaved = saveRestored(SEM2, [jwxtCourse('b1')]);
check('I11 恢复后落盘只剩教务口径的课', readLocal(SEM2).courses.length === 1);
check('I12 恢复返回新的 local 供页面重绘', reSaved.local.courses.length === 1);
resetLocal(SEM2);

/* ── 汇总 ──────────────────────────────────────────────────────────────── */

if (failures.length) {
  console.error(`\n  [FAIL] 本地课表 ${failures.length} 条断言失败（通过 ${passed} 条）`);
  process.exit(1);
}
console.log(`\n  [PASS] 本地课表 ${passed} 条断言全部通过（合并规则 / 编辑 / 单周停课 / 批量导入 / 按教务恢复 / 存储往返）`);
