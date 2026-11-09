#!/usr/bin/env node
/**
 * test-widget.mjs —— 桌面小组件「今日课程」快照的回归测试
 *
 * 小组件的麻烦在于「算」和「画」分属两套运行时：H5 算快照、原生照着画。
 * 所以快照一旦字段错位（比如把周次算成 0、把隔周才上的课摆进今天），
 * 原生不会报错，只会在桌面上安静地显示错的课 —— 这类错只能靠这里守住。
 *
 * 覆盖：
 *   A 节次 → 时间 / 文案
 *   B 今天该上哪几门（周次过滤、板块占位行、排序、隔周课排除）
 *   C 快照字段（版本、日期不补零、hasData 三态）
 *   D 下发给原生（无原生壳降级、payload 可解析、只发类型不发色值）
 *
 * 运行：node tools/test-widget.mjs
 */

import {
  WIDGET_VERSION,
  timeOfSections,
  sectionsText,
  coursesOfDay,
  buildWidgetSnapshot,
  weekdayOfIso,
  pushWidget,
  syncWidget,
} from '../web/data/widget.js';
import { writeLocal, resetLocal } from '../web/data/localTimetable.js';

let passed = 0;
const failures = [];

function check(name, condition) {
  if (condition) passed += 1;
  else {
    failures.push(name);
    console.error(`  [FAIL] ${name}`);
  }
}

/** 造一门课（默认：周一第 1-2 节、全周） */
function course(patch = {}) {
  return {
    id: 'c1',
    name: '高等数学 A(上)',
    teacher: '张老师',
    location: '5-201',
    courseType: 'required',
    dayOfWeek: 1,
    sections: [1, 2],
    weeks: [1, 2, 3, 11],
    ...patch,
  };
}

/* ── A. 节次 → 时间 / 文案 ─────────────────────────────────────────────── */

const t12 = timeOfSections([1, 2]);
check('A1 第 1-2 节 08:00 开始', t12.start === '08:00');
check('A2 第 1-2 节 09:35 结束', t12.end === '09:35');
const t59 = timeOfSections([5, 9]);
check('A3 跨小节取首尾（5→14:00 / 9→18:20）', t59.start === '14:00' && t59.end === '18:20');
check('A4 乱序节次也能算', timeOfSections([2, 1]).end === '09:35');
check('A5 空节次给占位', timeOfSections([]).start === '--:--' && timeOfSections([]).end === '--:--');
check('A6 越界节次被忽略', timeOfSections([0, 99]).start === '--:--');
check('A7 连续节次写成范围', sectionsText([1, 2]) === '第 1-2 节');
check('A8 单节不写成范围', sectionsText([4]) === '第 4 节');
check('A9 不连续节次逐个列', sectionsText([3, 5]) === '第 3,5 节');
check('A10 空节次给破折号', sectionsText([]) === '—');

/* ── B. 今天该上哪几门 ─────────────────────────────────────────────────── */

const table = [
  course(),
  course({ id: 'c2', name: '大学英语', dayOfWeek: 2, sections: [5, 6], weeks: [11] }),
  course({ id: 'c3', name: '只上周三', dayOfWeek: 3, sections: [1, 2], weeks: [11] }),
  course({ id: 'c4', name: '单周课', dayOfWeek: 1, sections: [3, 4], weeks: [1, 3, 5] }),
  course({ id: 'c5', name: '板块占位', dayOfWeek: 1, sections: [9], weeks: [11], isPlaceholder: true }),
  course({ id: 'c6', name: '晚间课', dayOfWeek: 1, sections: [10, 11], weeks: [11] }),
];

const monday11 = coursesOfDay(table, 1, 11);
check('B1 只留周一', monday11.every((item) => ['高等数学 A(上)', '晚间课'].includes(item.name)));
check('B2 剔掉板块占位行', !monday11.some((item) => item.name === '板块占位'));
check('B3 剔掉本周没课的（单周课第 11 周不上）', !monday11.some((item) => item.name === '单周课'));
check('B4 按开始时间排序', monday11[0].start < monday11[monday11.length - 1].start);
check('B5 带出节次文案与地点', monday11[0].sectionsText === '第 1-2 节' && monday11[0].location === '5-201');
check('B6 隔周课在它该上的周出现', coursesOfDay(table, 1, 3).some((item) => item.name === '单周课'));
check('B7 周次为 0 时什么都筛不出来', coursesOfDay(table, 1, 0).length === 0);
check('B8 不修改入参', table.length === 6 && table[0].name === '高等数学 A(上)');

/* ── C. 快照字段 ───────────────────────────────────────────────────────── */

const snap = buildWidgetSnapshot(table, { weekNo: 11, dayOfWeek: 1, todayIso: '2026-11-09', semesterId: '2026-2027-1' });
check('C1 版本号与原生一致', snap.v === WIDGET_VERSION && snap.v === 1);
check('C2 周次 / 星期正确', snap.weekNo === 11 && snap.dayOfWeek === 1 && snap.weekday === '一');
check('C3 日期不补零（11 月 9 日）', snap.dateText === '11 月 9 日');
check('C4 有课数据 hasData=true', snap.hasData === true && snap.total === 2);
check('C5 课程字段齐全', snap.courses.every((item) => item.name && item.start && item.end && item.courseType));
check('C6 只下发类型不下发色值', snap.courses.every((item) => !('color' in item) && !('colorIndex' in item)));

const emptySnap = buildWidgetSnapshot([], { weekNo: 11, dayOfWeek: 1, todayIso: '2026-11-09' });
check('C7 从没同步过 hasData=false', emptySnap.hasData === false && emptySnap.courses.length === 0);

const noCourseToday = buildWidgetSnapshot(table, { weekNo: 11, dayOfWeek: 7, todayIso: '2026-11-15' });
check('C8 有课表但今天没课 hasData 仍为 true', noCourseToday.hasData === true && noCourseToday.courses.length === 0);

check('C9 周日归 7', weekdayOfIso('2026-11-08') === 7);
check('C10 周一是 1', weekdayOfIso('2026-11-09') === 1);
check('C11 非法日期不抛错', weekdayOfIso('') === 1);

/* ── D. 下发 ───────────────────────────────────────────────────────────── */

check('D1 没有原生壳时静默返回 false', pushWidget({ v: 1 }) === false);
check('D2 没有原生壳时仍返回快照（便于测试）', Boolean(buildWidgetSnapshot(table, { weekNo: 11 })));

let captured = '';
globalThis.window = {
  NativeBridge: {
    pushWidget(json) {
      captured = json;
    },
  },
};

const SEM = 'widget-2026-1';
resetLocal(SEM);
writeLocal(SEM, { courses: [{ ...course(), source: 'custom' }] });

const state = {
  term: { context: { semesterId: SEM, week: 11 }, todayIso: '2026-11-09' },
};
const pushed = syncWidget(state);
check('D3 syncWidget 读本地课表并下发', captured.length > 0);
check('D4 下发的 JSON 可解析', (() => {
  try {
    return JSON.parse(captured).v === WIDGET_VERSION;
  } catch {
    return false;
  }
})());
check('D5 下发内容与返回的快照一致', JSON.parse(captured).total === pushed.total);
check('D6 周次取自 term.context', JSON.parse(captured).weekNo === 11);
check('D7 本地没有课时退回到调用方给的课程', (() => {
  const other = { term: { context: { semesterId: 'empty-sem', week: 11 }, todayIso: '2026-11-09' } };
  const snap2 = syncWidget(other, { courses: [course()] });
  return snap2.hasData === true && snap2.total === 1;
})());

delete globalThis.window;
resetLocal(SEM);

/* ── 汇总 ──────────────────────────────────────────────────────────────── */

if (failures.length) {
  console.error(`\n  [FAIL] 小组件快照 ${failures.length} 条断言失败（通过 ${passed} 条）`);
  process.exit(1);
}
console.log(`\n  [PASS] 小组件快照 ${passed} 条断言全部通过（节次换算 / 今日筛选 / 快照字段 / 下发）`);
