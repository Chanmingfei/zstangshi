#!/usr/bin/env node
/**
 * test-jwxt-parse.mjs —— 正方教务抓取解析器回归测试
 *
 * 验证 `web/data/jwxtParse.js`：把教务原始字段/原文变成 App 模型，且
 * 与 demo 里那份教务权威快照 OFFICIAL_GPA 逐项一致（防止抓取链路与演示数据漂移）。
 *
 * fixture 全部来自真实来源：
 *   · kbList 字段名与取值形态：用户提供的 provider.js / docs/remote-api-map.md §4.1
 *   · GPA 原文：2026-11-06 00:36 从「学生学业情况查询」逐字抄下，复用 test-gpa.mjs
 *   · 12 节作息：用户提供的《最新作息时间表（不分冬夏）》
 *
 * 运行：node tools/test-jwxt-parse.mjs
 */

import { readFileSync } from 'node:fs';
import {
  PERIOD_TIMES, parseWeeks, parseSections, deriveWeekFields,
  kbRowToCourse, parseKbList, htmlToText, parseGpaPage,
  readSelectValue, normalizeTerm, gradeRowToGrade, parseGradeList,
  parseGradeComponents, mergeGradeComponents,
  parseWeekList, parseWeekMask, weeksFromRow, mergeSplitRows,
} from '../web/data/jwxtParse.js';
import { coursesInCell } from '../web/pages/timetableUtils.js';
import { ENDPOINTS, missingEndpoints } from '../web/data/jwxtClient.js';
import {
  parseFilterOptions, classRowToItem, buildListBody, buildYlMap, semesterIdOf,
} from '../web/data/jwxtRecommend.js';
import { OFFICIAL_GPA } from '../web/data/demo/grades.js';
import { FIXTURE_HEADER, FIXTURE_TREE } from './test-gpa.mjs';

const failures = [];
let passed = 0;
function check(ok, label, actual, expected) {
  if (ok) { passed += 1; return; }
  failures.push(`${label}\n      期望: ${JSON.stringify(expected)}\n      实际: ${JSON.stringify(actual)}`);
}
const eq = (label, actual, expected) => check(
  JSON.stringify(actual) === JSON.stringify(expected), label, actual, expected
);

/* ── A. 周次解析：weeks 是唯一真源，必须能还原任意集合 ─────────────────── */
eq('A1 1-16周 → 16 个', parseWeeks('1-16周'), Array.from({ length: 16 }, (_, i) => i + 1));
eq('A2 1-9周(单) → 奇数周', parseWeeks('1-9周(单)'), [1, 3, 5, 7, 9]);
eq('A3 2-8周(双) → 偶数周', parseWeeks('2-8周(双)'), [2, 4, 6, 8]);
eq('A4 多段 1-9周,11-19周 → 18 个', parseWeeks('1-9周,11-19周').length, 18);
check('A5 第 10 周不在 1-9,11-19 里', !parseWeeks('1-9周,11-19周').includes(10));
eq('A6 第1-16周带「第」字', parseWeeks('第1-16周').length, 16);
eq('A7 空串 → 空数组（不猜）', parseWeeks(''), []);
eq('A8 派生字段：全奇数', deriveWeekFields([1, 3, 5]).weekParity, 'odd');
eq('A9 派生字段：混合', deriveWeekFields([1, 2, 3]).weekParity, 'all');
eq('A10 派生字段：范围', deriveWeekFields([3, 7, 11]).weekRange, [3, 11]);

/* ── B. 节次解析 + 真实作息 ─────────────────────────────────────────────── */
eq('B1 1-2节,5-6节', parseSections('1-2节,5-6节'), [1, 2, 5, 6]);
eq('B2 10-12节（晚间三节）', parseSections('10-12节'), [10, 11, 12]);
eq('B3 越界夹到 1..12', parseSections('0-14节'), Array.from({ length: 12 }, (_, i) => i + 1));
eq('B4 共 12 节，上午 4 / 下午 5 / 晚间 3', PERIOD_TIMES.length, 12);
eq('B5 第 5 节 14:00 开始', PERIOD_TIMES[4].startTime, '14:00');
eq('B6 第 12 节 21:25 结束', PERIOD_TIMES[11].endTime, '21:25');

/* ── C. kbList → Course（字段名来自用户提供的 provider.js）─────────────── */
const kbRow = {
  kcmc: '语文课程与教学论', kch: '0832011', xm: '张文娟', xf: '2.5', xs: '40',
  cdmc: '敏行楼C305', xqj: '4', zcd: '1-17周', jc: '1-2节',
  kcxzmc: '必修', jxbmc: '2026-1-0832011-01', xqmc: '大学道北校区',
};
const course = kbRowToCourse(kbRow, '2026-2027-1', { isSelected: true });
check('C1 生成 Course', Boolean(course));
eq('C2 课程名', course.name, '语文课程与教学论');
eq('C3 教师', course.teacher, '张文娟');
eq('C4 地点', course.location, '敏行楼C305');
eq('C5 星期四', course.dayOfWeek, 4);
eq('C6 节次', course.sections, [1, 2]);
eq('C7 周次 17 个', course.weeks.length, 17);
eq('C8 起止时间按 12 节作息补全', [course.startTime, course.endTime], ['08:00', '09:35']);
eq('C9 课程类型配色键', course.courseType, 'required');
eq('C10 学分', course.credits, 2.5);
eq('C11 已选标记', course.isSelected, true);
eq('C12 派生 weekRange', course.weekRange, [1, 17]);
eq('C13 无效行（无课名）被丢弃', kbRowToCourse({ kch: 'x' }, 's'), null);
eq('C14 缺周次的行被丢弃', kbRowToCourse({ ...kbRow, zcd: '' }, 's'), null);
eq('C15 包装对象 { kbList } 能解开', parseKbList({ kbList: [kbRow, null] }, 's').length, 1);
eq('C16 单双周 → weeks 是集合不是范围', kbRowToCourse({ ...kbRow, zcd: '2-8周(双)' }, 's').weeks, [2, 4, 6, 8]);
eq('C17 非数组输入返回空', parseKbList('坏数据', 's'), []);

/* ── D. HTML → 文本 ────────────────────────────────────────────────────── */
const text = htmlToText('<div>平均学分绩点（GPA）： 3.16<br><span>提示：仅供参考</span></div>');
check('D1 剥掉标签保留文本', text.includes('3.16') && !text.includes('<div>'), text);

/* ── E. GPA 原文 → 权威快照，与 OFFICIAL_GPA 逐项对齐 ──────────────────── */
const pageText = `${FIXTURE_HEADER}\n${FIXTURE_TREE}\n提示：此页面信息仅做学业修读情况参考。`;
const gpa = parseGpaPage(pageText, OFFICIAL_GPA.fetchedAt);
eq('E1 GPA 直接取教务原文 3.16（零计算）', gpa.gpa, 3.16);
eq('E2 统计时间', gpa.statsAt, OFFICIAL_GPA.statsAt);
eq('E3 gpaOrigin 标权威', gpa.gpaOrigin, 'official');
eq('E4 七项门次统计', gpa.courseCount, OFFICIAL_GPA.courseCount);
eq('E5 学分树 21 节点', gpa.modules.length, OFFICIAL_GPA.modules.length);
eq('E6 根节点是「主修」', gpa.modules[0].name, '主修');
eq('E7 缩进层级：必修课公共部分 depth=2',
  gpa.modules.find((m) => m.name === '必修课公共部分').depth, 2);
eq('E8 学分三件套',
  [gpa.modules[1].requireCredits, gpa.modules[1].earnedCredits, gpa.modules[1].missingCredits],
  [27.75, 123.0, 0.0]);
eq('E9 「并且」规则', gpa.modules[1].logic, '并且');
eq('E10 共 N 门 / 通过 N 门',
  [gpa.modules[2].courseTotal, gpa.modules[2].coursePassed], [89, 80]);
check('E11 没有本地加权计算', gpa.gpa !== 3.07);

/* ── F. 端点表的诚实边界 ───────────────────────────────────────────────── */
check('F1 课表页已实测 N2151', ENDPOINTS.timetablePage.includes('N2151'));
check('F2 学业情况已实测 N105515', ENDPOINTS.gpa.includes('N105515'));
eq('F3 教学执行计划已下线，端点表里不再有 plan', ENDPOINTS.plan, undefined);
/* 2026-11-06 PC 直连实测（docs/diagnostics/bjkbdy-*）：预览壳/课表本体/可看检查三件套 */
check('F4 预览壳是 ylBjkbdyIndex（N214505，map[key] 嵌套口径）',
  ENDPOINTS.recommend.includes('N214505') && ENDPOINTS.recommend.includes('ylBjkbdyIndex'));
check('F4a 课表本体是大写 K 的 cxBjKb（平铺参数）',
  ENDPOINTS.recommendKb.includes('bjkbdy_cxBjKb.html'));
check('F4b 可看检查是 cxXnxqsfkz（"true"=不可查看）',
  ENDPOINTS.recommendSfkz.includes('cxXnxqsfkz'));
check('F5 推荐课表不再列在待实测清单', !missingEndpoints().includes('recommend'));
/* 2026-11-06 本地代理实测补齐：首页 clickMenu / 菜单 JSON / 我的应用 */
check('F6 成绩页 N305005', ENDPOINTS.grades.includes('N305005'));
check('F7 成绩数据接口是学生角色 cxXsgrcj', ENDPOINTS.gradesData.includes('cxXsgrcj'));
check('F8 自主选课页 N253512', ENDPOINTS.selection.includes('N253512'));
check('F9 查询个人信息页 N100801', ENDPOINTS.profile.includes('N100801'));
check('F10 推荐课表打印页 N214505', ENDPOINTS.recommendPage.includes('N214505'));
check('F11 推荐课表清单接口 TjkbList', ENDPOINTS.recommendList.includes('TjkbList'));
check('F12 自主选课课池已接入 PartDisplay（N253512，2026-11-06 按唐师真实结构重写）',
  ENDPOINTS.selectionData.includes('N253512') && ENDPOINTS.selectionData.includes('PartDisplay'));
check('F13 课池接口不再列在待实测清单', !missingEndpoints().includes('selectionData'));
/* 2026-11-06 真机铁证重写：教学班补全前缀 zzxkyzbjk_、已选无 Display 后缀、提交是 Quickly */
check('F16 教学班补全接口前缀 zzxkyzbjk_', ENDPOINTS.selectionJxb.includes('zzxkyzbjk_cxJxbWithKchZzxkYzb'));
check('F17 提交接口是快速选课 xkZzxkyzbQuickly', ENDPOINTS.selectionSubmit.includes('xkZzxkyzbQuickly'));
check('F18 已选接口是 Choosed（无 Display 后缀、无参数形态）',
  ENDPOINTS.selectionChoosed.includes('cxZzxkYzbChoosed.html') && !ENDPOINTS.selectionChoosed.includes('ChoosedDisplay'));
check('F19 选课链不再残留 zfn_api 通用契约的退课端点', ENDPOINTS.selectionDrop === undefined);
check('F14 课表数据接口是 POST 用的 cxXsKb', ENDPOINTS.timetableData.includes('cxXsKb'));
check('F15 我的应用别名接口已实测', ENDPOINTS.myApps.includes('index_cxWdyy'));

/* ── G. 学期码 <select> 与成绩行（2026-11-06 代理实测）─────────────────── */
const termSelect = `
  <select name="xnm" id="xnm" class="form-control chosen-select">
    <option value="" ></option><option value="2031">2031-2032</option>
    <option value="2026" selected="selected">2026-2027</option>
  </select>
  <select name="xqm" id="xqm" class="form-control chosen-select">
    <option value="" ></option><option value="3" selected="selected">1</option>
    <option value="12">2</option><option value="16">3</option>
  </select>`;
eq('G1 从 select 读出当前学年 2026', readSelectValue(termSelect, 'xnm'), '2026');
eq('G2 从 select 读出当前学期码 3', readSelectValue(termSelect, 'xqm'), '3');
eq('G3 无 selected 时取第一个非空 option',
  readSelectValue('<select name="xqm"><option value=""></option><option value="12">2</option></select>', 'xqm'), '12');
eq('G4 找不到 select 返回空串（不猜）', readSelectValue('<div>无</div>', 'xnm'), '');
eq('G5 学期码 3 → 第 1 学期', normalizeTerm('3', '1'), '1');
eq('G6 学期码 12 → 第 2 学期', normalizeTerm('12', '2'), '2');
eq('G7 学期码 16 → 第 3 学期', normalizeTerm('16', '3'), '3');
eq('G8 缺学期名时按码回退', normalizeTerm('3', ''), '1');

/** 脱敏后的成绩行（字段形态来自 cjcx_cxXsgrcj.html?doType=query 实测） */
const cjRow = {
  key: 'K-01', jxb_id: 'JXB-01', kch: '000000026', kcmc: '示例课程',
  cj: '75', jd: '2.50', xf: '1.0', xnmmc: '2024-2025', xqm: '3', xqmmc: '1',
  kcxzmc: '必修课', kclbmc: '通识教育课程', khfsmc: '考查',
  ksxz: '正常考试', cjsfzf: '否',
};
const grade = gradeRowToGrade(cjRow);
eq('G9 课程名', grade.courseName, '示例课程');
eq('G10 百分制成绩', grade.score, 75);
eq('G11 绩点直接读原文', grade.gradePoint, 2.5);
eq('G12 学分', grade.credits, 1);
eq('G13 学期用 xnmmc + xqmmc 原文拼', grade.semesterId, '2024-2025-1');
eq('G14 考核方式', grade.assessment, 'check');
eq('G15 正考 / 非重修', [grade.examType, grade.isRetake], ['normal', false]);
eq('G16 文字成绩不伪装成数字',
  (() => { const g = gradeRowToGrade({ ...cjRow, cj: '优秀' }); return [g.score, g.scoreText]; })(),
  [null, '优秀']);
eq('G17 缓考保留备注',
  (() => { const g = gradeRowToGrade({ ...cjRow, cj: '缓考' }); return [g.score, g.remark]; })(),
  [null, '缓考']);
eq('G18 重修标记',
  (() => { const g = gradeRowToGrade({ ...cjRow, cjsfzf: '是' }); return [g.isRetake, g.examType]; })(),
  [true, 'retake']);
eq('G19 补考判定', gradeRowToGrade({ ...cjRow, ksxz: '补考' }).examType, 'makeup');
eq('G20 第二学期码 12', gradeRowToGrade({ ...cjRow, xqm: '12', xqmmc: '2' }).semesterId, '2024-2025-2');
eq('G21 { items } 包装能解开且丢弃空行', parseGradeList({ items: [cjRow, null] }).length, 1);
eq('G22 裸数组也能解', parseGradeList([cjRow]).length, 1);
eq('G23 无课名行丢弃', gradeRowToGrade({ ...cjRow, kcmc: '' }), null);
eq('G24 非数组输入返回空', parseGradeList('坏数据'), []);

/* ── G2. 成绩构成（N305007 明细，2026-11-07 真机 216 行实测）───────────────
 * 一行 = 一门课的一个分项；`xmblmc` 形如 `平时(40%)`，`xmcj` 可能是「良好」。
 * 铁律：总评行丢掉（与成绩查询 cj 逐条一致），权重按括号数字解析，得分不折算。 */
const detailRow = (jxbId, label, score, extra = {}) => ({
  jxb_id: jxbId, xmblmc: label, xmcj: score, kcmc: '示例课程',
  xnm: '2024', xqm: '12', ...extra,
});
const detailMap = parseGradeComponents({
  items: [
    detailRow('J1', '平时(40%)', '90'),
    detailRow('J1', '期末(60%)', '85'),
    detailRow('J1', '总评', '87'),
    detailRow('J1', '平时(40%)', '90'),   // 重复项只留一条
    detailRow('J2', '期末(100%)', '良好'), // 单分项 + 汉字得分
    detailRow('J3', '技能考核（30％）', '88'), // 全角括号/全角百分号
    detailRow('', '平时(20%)', '77'),      // 无 jxb_id 时退化为 课号|学年|学期
  ],
});
eq('G25 分项按教学班归组，总评不计入', detailMap.get('J1').length, 2);
eq('G26 分项名去掉权重后缀', detailMap.get('J1').map((c) => c.label), ['平时', '期末']);
eq('G27 权重解析成数字', detailMap.get('J1').map((c) => c.weight), [40, 60]);
eq('G28 得分保留原文（含汉字等第）',
  [detailMap.get('J1').map((c) => c.score), detailMap.get('J2')[0].score], [['90', '85'], '良好']);
eq('G29 全角括号 / 全角百分号也认', detailMap.get('J3')[0], { label: '技能考核', weight: 30, score: '88' });
check('G30 缺 jxb_id 时用 课号|学年|学期 兜底', detailMap.has('|2024|12'));
eq('G31 非对象输入返回空 Map', parseGradeComponents(null).size, 0);

/* 合并：只给有明细的课补 components，不能把没有分项的课弄丢（明细少 6 门网络学习课） */
const mergedList = mergeGradeComponents(
  [
    { id: 'a', courseId: 'J1', courseName: '有明细' },
    { id: 'b', courseId: 'J9', courseName: '无明细' },
  ],
  detailMap
);
eq('G32 有明细的课补上分项', mergedList[0].components.length, 2);
eq('G33 无明细的课保持原样（不写空数组）', mergedList[1].components, undefined);
eq('G34 明细为空表时不动成绩', mergeGradeComponents([{ id: 'a', courseId: 'J1' }], null)[0].components, undefined);

/* ── H. 推荐课表链路（2026-11-06 PC 实测，样本 docs/diagnostics/bjkbdy-*）──── */
const dictHtml = `
  <select name="xnm" id="xnm"><option value="2026" selected="selected">2026-2027</option></select>
  <select name="xqm" id="xqm"><option value="3" selected="selected">1</option></select>
  <select name="njdm_id" id="njdm_id">
    <option value="" selected="selected">全部</option><option value="2024">2024</option><option value="2023">2023</option>
  </select>
  <select name="zyh_id" id="zyh_id">
    <option value="">全部</option><option value="Z1">电子商务(2316)</option>
  </select>
  <select name="bh_id" id="bh_id">
    <option value="">全部</option><option value="24231641">2024级电子商务本科1班</option>
  </select>`;
const dicts = parseFilterOptions(dictHtml);
eq('H1 学期码随壳页解析', [dicts.xnm, dicts.xqm], ['2026', '3']);
eq('H2 年级字典跳过空值「全部」', dicts.grades.map((g) => g.id), ['2024', '2023']);
eq('H3 专业字典保留原名', dicts.majors[0], { id: 'Z1', name: '电子商务(2316)' });
eq('H4 班级字典', dicts.classes[0].id, '24231641');
eq('H5 壳页无该 select 时空数组（不猜）', parseFilterOptions('<div>无</div>').majors, []);
/** 清单行字段形态照抄 docs/diagnostics/bjkbdy-list-sample.json 首行（脱敏） */
const listRow = {
  bh_id: '24231641', bjmc: '2024级电子商务本科1班', njdm_id: '2024', njmc: '2024',
  zyh_id: 'Z1', zymc: '电子商务', jgmc: '海洋学院', xkrs: '42', xnm: '2026', xqm: '3',
  xnmc: '2026-2027', xqmmc: '1', xqh_id: '1', tjkbzdm: '1', tjkbzxsdm: '0',
  bj: '2024级电子商务本科1班', bh: '24231641',
};
const item = classRowToItem(listRow);
eq('H6 班级行 → 级联项',
  [item.gradeId, item.majorId, item.classId, item.className],
  ['2024', 'Z1', '24231641', '2024级电子商务本科1班']);
eq('H7 选人数字段', item.population, 42);
const body = buildListBody({ xnm: '2026', xqm: '3' }, { gradeId: '2024' }, 2);
check('H8 清单 body 是 paramMap 口径', body.njdm_id === '2024' && body.zyh_id === '' && body.page === '2');
check('H9 清单 body 带学期码与每页 10 条', body.xnm === '2026' && body.xqm === '3' && body.rows === '10');
const yl = buildYlMap(listRow);
check('H10 预览 map 平铺三 id + 会话语境字段',
  yl.njdm_id === '2024' && yl.zyh_id === 'Z1' && yl.bh_id === '24231641' && yl.kzlx === 'ck');
eq('H11 学期码归一 3 → 第 1 学期', semesterIdOf({ xnm: '2026', xqm: '3' }), '2026-1');

/* ── I. 单双周回归（2026-11-07 真机抓的 2024 级电商 1 班 + 落盘样本） ────
 * 症状：单/双周课程被当成全周课，同一格里两门课同时命中 → 页面显示「时间冲突」。
 * 根因：只解析 zcd 中文文案；教务其实还给了精确字段 zcds（列表）/ oldzc（位掩码）。
 */
const kbRaw = JSON.parse(
  readFileSync(new URL('../docs/diagnostics/bjkbdy-kb-sample.json', import.meta.url), 'utf8')
);
const real = parseKbList(kbRaw, '2026-1');
const evenCourse = real.find((c) => c.name === '国际贸易实务实训');
const oddCourse = real.find((c) => c.name === '供应链与物流管理实训' && c.weeks.length === 3);
eq('I1 双周课 weeks 取 zcds 精确值', evenCourse && evenCourse.weeks, [10, 12, 14, 16]);
eq('I2 单周课 weeks 取 zcds 精确值', oddCourse && oddCourse.weeks, [11, 13, 15]);
check('I3 两门课确实同在周三 5-8 节', Boolean(evenCourse && oddCourse
  && evenCourse.dayOfWeek === 3 && String(evenCourse.sections) === '5,6,7,8'
  && String(oddCourse.sections) === '5,6,7,8'));
const cell10 = coursesInCell(real, 3, 5, 10);
const cell11 = coursesInCell(real, 3, 5, 11);
check('I4 第 10 周只上双周课，单周课进轮换列表',
  cell10.active.length === 1 && cell10.relation === 'alternating' && cell10.rotating[0].offWeek === true);
check('I5 第 11 周换成单周课', cell11.active.length === 1
  && cell11.active[0].name === '供应链与物流管理实训' && cell11.rotating.length === 1);
check('I6 周次互补的两门课绝不判「时间冲突」',
  cell10.relation !== 'conflict' && cell11.relation !== 'conflict');
const clash = [
  { id: 'A', dayOfWeek: 3, sections: [5, 6], weeks: [1, 2, 3] },
  { id: 'B', dayOfWeek: 3, sections: [5, 6], weeks: [1, 2, 3] },
];
check('I7 同一周真撞课仍然判冲突', coursesInCell(clash, 3, 5, 2).relation === 'conflict');
check('I8 单门课 relation=single',
  coursesInCell([clash[0]], 3, 5, 2).relation === 'single');

eq('I9 前缀「单周1-16周」', parseWeeks('单周1-16周'), [1, 3, 5, 7, 9, 11, 13, 15]);
eq('I10 前缀「双周2-16周」', parseWeeks('双周2-16周'), [2, 4, 6, 8, 10, 12, 14, 16]);
eq('I11 裸列表 1,3,5,7', parseWeeks('1,3,5,7'), [1, 3, 5, 7]);
eq('I12 混合段不被前缀二次过滤', parseWeeks('1-9周(单),2-8周(双)'), [1, 2, 3, 4, 5, 6, 7, 8, 9]);
eq('I13 zcds 精确列表', parseWeekList('10,12,14,16'), [10, 12, 14, 16]);
eq('I14 zcds 非法值不猜', parseWeekList('讲师'), []);
eq('I15 oldzc 255 → 1-8 周', parseWeekMask('255'), [1, 2, 3, 4, 5, 6, 7, 8]);
eq('I16 oldzc 64512 → 11-16 周', parseWeekMask('64512'), [11, 12, 13, 14, 15, 16]);
eq('I17 oldzc 43520 → 10/12/14/16 周', parseWeekMask('43520'), [10, 12, 14, 16]);
eq('I18 oldzc 空值', parseWeekMask(''), []);
eq('I19 weeksFromRow 精确字段优先于文案',
  weeksFromRow({ zcd: '1-17周', zcds: '10,12,14,16' }), [10, 12, 14, 16]);
eq('I20 weeksFromRow 无 zcds 时用 oldzc',
  weeksFromRow({ zcd: '1-8周', oldzc: '255' }), [1, 2, 3, 4, 5, 6, 7, 8]);
eq('I21 weeksFromRow 纯文案兜底',
  weeksFromRow({ zcd: '单周1-16周' }), [1, 3, 5, 7, 9, 11, 13, 15]);
eq('I22 weeksFromRow 字段名大小写不敏感', weeksFromRow({ ZCDS: '1,3,5' }), [1, 3, 5]);
const apiRow = {
  kcmc: '国际贸易实务实训', xqj: '3', jc: '5-8节', zcd: '1-17周',
  zcds: '10,12,14,16', oldzc: '43520', cdmc: '实验室A', xm: '张老师',
};
const apiCourse = kbRowToCourse(apiRow, '2026-1');
eq('I23 kbRowToCourse 取 zcds 而非 zcd', apiCourse.weeks, [10, 12, 14, 16]);
check('I24 派生字段跟着精确周次走',
  apiCourse.weekParity === 'even' && String(apiCourse.weekRange) === '10,16');
const split = [
  { id: 'X', dayOfWeek: 3, sections: [5, 6], weeks: [1, 2, 3, 4], teacher: 'T', location: 'C305' },
  { id: 'X', dayOfWeek: 3, sections: [5, 6], weeks: [5, 6, 7, 8], teacher: 'T', location: 'C305' },
];
const merged = mergeSplitRows(split);
check('I25 教务按周次拆的两行合并成一门',
  merged.length === 1 && merged[0].weeks.length === 8 && merged[0].weekParity === 'all');
const overlap = [
  { id: 'Y', dayOfWeek: 3, sections: [5, 6], weeks: [1, 2, 3], teacher: 'T', location: 'C305' },
  { id: 'Y', dayOfWeek: 3, sections: [5, 6], weeks: [3, 4, 5], teacher: 'T', location: 'C305' },
];
check('I26 周次有交集的重复行不吞掉（真冲突保持可见）', mergeSplitRows(overlap).length === 2);
// 真机抓到的「大学体育2025(板块)」：pkbj=-1 / 未排地点 / 0 学分，与 Java 课同格
const blockRow = {
  kcmc: '大学体育2025(板块)', xqj: '1', jc: '6-9节', zcd: '1-17周',
  zcds: '1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17',
  cdmc: '未排地点', pkbj: '-1',
};
const javaRow = { ...blockRow, kcmc: 'Java编程开发', jc: '6-7节', cdmc: 'C312', pkbj: '1' };
check('I27 板块占位行被标记', kbRowToCourse(blockRow, '2026-1').isPlaceholder === true
  && kbRowToCourse(javaRow, '2026-1').isPlaceholder === false);
const withBlock = parseKbList({ kbList: [javaRow, blockRow] }, '2026-1');
check('I28 真实课 + 板块占位行不算时间冲突',
  coursesInCell(withBlock, 1, 6, 10).relation === 'placeholder');
check('I29 详情层把真实课排在占位行前面',
  coursesInCell(withBlock, 1, 6, 10).active[0].name === 'Java编程开发');
check('I30 两门真实课同周重叠仍然判冲突',
  coursesInCell([...clash, { ...clash[0], id: 'C' }], 3, 5, 2).relation === 'conflict');
// 三角必须是课程卡的真实子节点，否则卡片有 stagger 入场动画、角标却静止贴在格子上
const cardSrc = readFileSync(new URL('../web/components/courseCard.js', import.meta.url), 'utf8');
const cardCss = readFileSync(new URL('../web/styles/componentsItem.css', import.meta.url), 'utf8');
const gridCss = readFileSync(new URL('../web/styles/pagesTimetable.css', import.meta.url), 'utf8');
check('I31 多课三角由卡片内部节点渲染', cardSrc.includes("className: 'c-course__corner'")
  && cardSrc.includes('options.multi') && cardCss.includes('.c-course__corner {'));
check('I32 格子上不再画静态角标伪元素', !gridCss.includes('.is-parity')
  && !gridCss.includes('is-conflict::after') && !gridCss.includes('data-parity'));
// base.css 把 button 重置成 text-align: inherit，按钮文字必须显式摆正
const sheetCss = readFileSync(new URL('../web/styles/pagesTimetableSheet.css', import.meta.url), 'utf8');
const showBtnCss = /\.p-tt__show-btn\s*\{([^}]*)\}/.exec(sheetCss);
check('I33 「显示这门课」按钮文字横竖居中', Boolean(showBtnCss
  && showBtnCss[1].includes('justify-content: center')
  && showBtnCss[1].includes('align-items: center')
  && showBtnCss[1].includes('text-align: center')));

/* ── 结论 ─────────────────────────────────────────────────────────────── */
if (failures.length) {
  process.stderr.write(`\n  [FAIL] 教务解析器 ${failures.length} 条断言未通过\n\n`);
  for (const item of failures) process.stderr.write(`    ✗ ${item}\n`);
  process.stderr.write('\n');
  process.exitCode = 1;
} else {
  process.stdout.write(`\n  [PASS] 教务解析器 ${passed + failures.length} 条断言全部通过（周次/单双周/节次/kbList/GPA 原文/学期码/成绩映射/端点边界）\n\n`);
  process.exitCode = 0;
}
