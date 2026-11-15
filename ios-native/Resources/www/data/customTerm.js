/**
 * customTerm.js —— 用户自定义「当前学期 + 第一周周一」（我的页设置）
 *
 * 为什么需要：校历 JSON 是打包进 App 的静态快照，学校换学期后 semesterStart
 * 要等 App 更新才对。这里允许用户自选学期与第一周所在日期，持久化到
 * localStorage（storage.js 的 `zsts.customTerm`，永不过期），冷启动后仍然生效。
 *
 * 生效链路（两处接入，都收敛到 appActions.recomputeTerm）：
 *   · 启动 bootTerm：calendar + custom → 学期对象 → 顶栏/周数 + configureRemote 学期码
 *   · 我的页保存：同上，并清空 todayCourses/timetable 槽重取
 *
 * 学期码口径（正方）：xnm = 学年起始年；xqm 3/12/16 对应第 1/2/3 学期
 * （docs/remote-api-map.md 实测）。semesterId 统一用 `2026-2027-1` 这种日历口径。
 *
 * 零 DOM、可 node 单测（tools/test-week.mjs 同款约定）。
 */

import * as storage from '../core/storage.js';
import { dayNumberOfIso, isoOfDayNumber } from './calendar.js';

/** storage 短键名（持久化，永不过期） */
export const KEY = 'customTerm';

/** 正方学期码：term 1/2/3 → xqm */
const TERM_XQM = Object.freeze({ 1: '3', 2: '12', 3: '16' });

/** term → 中文标签 */
const TERM_LABELS = Object.freeze({ 1: '第一学期', 2: '第二学期', 3: '第三学期' });

/**
 * 读自定义设置。坏数据（缺键/学期号不合口径/日期非法）一律当「没有设置」。
 * @returns {{semesterId: string, startDate: string, totalWeeks: number}|null}
 */
export function readCustom() {
  const raw = storage.get(KEY, null);
  if (!raw || typeof raw !== 'object') return null;
  const semesterId = String(raw.semesterId || '');
  const startDate = String(raw.startDate || '');
  const totalWeeks = Number(raw.totalWeeks) || 0;
  if (!parseSemesterId(semesterId)) return null;
  if (Number.isNaN(dayNumberOfIso(startDate))) return null;
  return { semesterId, startDate, totalWeeks };
}

/**
 * 保存自定义设置（startDate 会先规范化到那一周的周一）。
 * @param {{semesterId: string, startDate: string, totalWeeks?: number}} value
 * @returns {{semesterId: string, startDate: string, totalWeeks: number}|null} 规范化后的值；入参非法返回 null
 */
export function saveCustom(value) {
  if (!value || typeof value !== 'object') return null;
  const parsed = parseSemesterId(String(value.semesterId || ''));
  if (!parsed) return null;
  const num = dayNumberOfIso(String(value.startDate || ''));
  if (Number.isNaN(num)) return null;
  // 教学周以周一为界：用户随手选了别的星期也归一到本周周一
  const weekday = weekdayNumber(num);
  const monday = isoOfDayNumber(num - (weekday - 1));
  const out = {
    semesterId: parsed.id,
    startDate: monday,
    totalWeeks: Number(value.totalWeeks) || 0,
  };
  storage.set(KEY, out, 0);
  return out;
}

/** 清除自定义设置，回到「跟随校历」 */
export function clearCustom() {
  storage.remove(KEY);
}

/**
 * '2026-2027-1' → { id, academicYear: '2026-2027', term: 1 }；不合口径返回 null。
 * @param {string} id
 */
export function parseSemesterId(id) {
  const m = /^(\d{4})-(\d{4})-([123])$/.exec(String(id || '').trim());
  if (!m) return null;
  return { id: `${m[1]}-${m[2]}-${m[3]}`, academicYear: `${m[1]}-${m[2]}`, term: Number(m[3]) };
}

/**
 * semesterId → 正方学期码。无法解析时给空串（等于「不用手工学期码」）。
 * @param {string} semesterId
 * @returns {{xnm: string, xqm: string}}
 */
export function xnmXqmOf(semesterId) {
  const parsed = parseSemesterId(semesterId);
  if (!parsed) return { xnm: '', xqm: '' };
  return { xnm: parsed.academicYear.slice(0, 4), xqm: TERM_XQM[parsed.term] || '' };
}

/** '2026-2027' → '26~27'（顶栏 displayYear 口径，波浪号 U+007E） */
export function displayYearOf(academicYear) {
  const m = /^(\d{4})-(\d{4})$/.exec(String(academicYear || '').trim());
  if (!m) return '';
  return `${m[1].slice(2)}~${m[2].slice(2)}`;
}

/**
 * 把自定义设置覆盖到校历选出的学期上（纯函数，不改入参）。
 *   · 无 custom → 原样返回 picked
 *   · id 相同 → 只改 semesterStart（换学期初日期校准的最常见用法）
 *   · id 不同或 picked 为空 → 用 custom 合成一个学期对象（totalWeeks 缺省 19 周）
 * @param {object|null} picked pickSemester 的结果
 * @param {{semesterId: string, startDate: string, totalWeeks?: number}|null} custom
 * @returns {object|null} 生效学期对象
 */
export function applyCustomTerm(picked, custom) {
  if (!custom) return picked;
  const parsed = parseSemesterId(custom.semesterId);
  if (!parsed) return picked;
  if (picked && picked.id === custom.semesterId) {
    return {
      ...picked,
      semesterStart: custom.startDate,
      totalWeeks: Number(custom.totalWeeks) || picked.totalWeeks,
    };
  }
  return {
    id: parsed.id,
    academicYear: parsed.academicYear,
    term: parsed.term,
    displayYear: displayYearOf(parsed.academicYear),
    termLabel: TERM_LABELS[parsed.term],
    semesterStart: custom.startDate,
    totalWeeks: Number(custom.totalWeeks) || 19,
    semesterEnd: '',
    examWeeks: [],
    milestones: [],
    weeks: [],
  };
}

/* ── 内部 ─────────────────────────────────────────────────────────────── */

/** 日序号 → 星期几（1=周一…7=周日）。与 calendar.weekdayOfDayNumber 同式，这里避免多引一层。 */
function weekdayNumber(num) {
  const offset = ((num % 7) + 7) % 7;
  return ((offset + 3) % 7) + 1;
}
