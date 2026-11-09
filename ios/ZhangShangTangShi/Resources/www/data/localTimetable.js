/**
 * localTimetable.js —— 本地课表（可写的课表真源）
 *
 * 为什么需要这一层：
 *   1. 老师调课不一定回教务系统更新，学期初教务排课也可能不全 —— 学生必须能自己改；
 *   2. 改完要能在没网、没登录时照常看（教务抓不到时不能只剩一个空页面）。
 *
 * 存储：每个学期一条，key = `timetable.<semesterId>`（storage.js 统一加 `zsts.` 前缀）。
 *
 * 课程来源三态（`source`）：
 *   · `jwxt`   教务导入、未被改动 —— 下次同步可被覆盖或删除
 *   · `custom` 学生手动新建     —— 同步**永不删除**
 *   · `edited` 教务导入后被改过 —— 同步**保留学生版本**
 *
 * 合并规则（mergeImported，纯函数）：
 *   ① 教务有、本地没有            → 新增（jwxt）
 *   ② 教务有、本地是同 id 的 jwxt → 覆盖课程字段，保留 colorIndex / note
 *   ③ 教务有、本地是同 id 的 edited → 保留本地（学生改过的以学生为准）
 *   ④ 教务没有、本地是 custom/edited → 保留
 *   ⑤ 教务没有、本地是 jwxt        → 删除（教务已取消这门课）
 *
 * 本文件零 DOM、零 UI 依赖，所有函数「输入 → 输出」，可直接单测。
 */

import { get, set, remove, keys } from '../core/storage.js';

export const SCHEMA_VERSION = 1;

/** 课程来源 */
export const SOURCE = Object.freeze({ Jwxt: 'jwxt', Custom: 'custom', Edited: 'edited' });

const KEY_PREFIX = 'timetable.';

/**
 * 学期 → 存储 key。
 * @param {string} [semesterId]
 * @returns {string}
 */
export function keyOf(semesterId) {
  return `${KEY_PREFIX}${semesterId || 'default'}`;
}

/**
 * 空课表结构。
 * @param {string} [semesterId]
 * @returns {{schemaVersion: number, semesterId: string, importedAt: string|null, updatedAt: string|null, courses: object[]}}
 */
export function emptyLocal(semesterId) {
  return {
    schemaVersion: SCHEMA_VERSION,
    semesterId: semesterId || 'default',
    importedAt: null,
    updatedAt: null,
    courses: [],
  };
}

/**
 * 读取某学期的本地课表。坏数据（非对象 / courses 不是数组）一律当空，不抛错。
 * @param {string} [semesterId]
 * @returns {object}
 */
export function readLocal(semesterId) {
  const raw = get(keyOf(semesterId), null);
  if (!raw || typeof raw !== 'object' || !Array.isArray(raw.courses)) return emptyLocal(semesterId);
  return {
    ...emptyLocal(semesterId),
    ...raw,
    courses: raw.courses.filter((course) => course && typeof course === 'object' && course.id),
  };
}

/**
 * 写入某学期的本地课表（自动刷新 updatedAt）。
 * @param {string} [semesterId]
 * @param {object} data
 * @returns {object} 实际写入的结构
 */
export function writeLocal(semesterId, data) {
  const payload = {
    ...emptyLocal(semesterId),
    ...data,
    schemaVersion: SCHEMA_VERSION,
    semesterId: semesterId || 'default',
    updatedAt: new Date().toISOString(),
  };
  set(keyOf(semesterId), payload);
  return payload;
}

/**
 * 是否已有本地课程（决定课表页能否免登录打开）。
 * @param {string} [semesterId]
 * @returns {boolean}
 */
export function hasLocal(semesterId) {
  return readLocal(semesterId).courses.length > 0;
}

/**
 * 找**最近改过的那份**本地课表（跨学期）。
 *
 * 为什么需要：免登录能不能看课表，不能只认「当前学期码」这一个 key。
 *   · 校历跨到新学期（`semesterId` 从 2026-2027-1 变成 -2），旧课表还在旧 key 里；
 *   · 校历没加载出来时 `semesterId` 是 undefined，会去查 `timetable.default`；
 * 这两种情况下若只查当前 key，「明明存过课表却打不开」——正是免登录失效的观感来源。
 *
 * @returns {{semesterId: string, data: object}|null}
 */
export function readLatestLocal() {
  let best = null;
  for (const name of keys()) {
    if (!name.startsWith(KEY_PREFIX)) continue;
    const semesterId = name.slice(KEY_PREFIX.length);
    const data = readLocal(semesterId);
    if (!data.courses.length) continue;
    // updatedAt 是 ISO 串，字典序即时间序；没写过的排最末。
    // 用 `>=` 而不是 `>`：同一毫秒内连写两份（自动化测试、连续保存）时，后写的那份才算「最近」
    if (!best || String(data.updatedAt || '') >= String(best.data.updatedAt || '')) {
      best = { semesterId, data };
    }
  }
  return best;
}

/**
 * 任意学期是否存过课表（免登录首屏判断用）。
 * @returns {boolean}
 */
export function hasAnyLocal() {
  return readLatestLocal() !== null;
}

/** 周次归一化：去重、升序、只留 1-30 的整数 */
export function normalizeWeeks(weeks) {
  const out = new Set();
  for (const raw of Array.isArray(weeks) ? weeks : []) {
    const n = Number(raw);
    if (Number.isInteger(n) && n >= 1 && n <= 30) out.add(n);
  }
  return [...out].sort((a, b) => a - b);
}

/** 节次归一化：去重、升序、只留 1-12 的整数 */
export function normalizeSections(sections) {
  const out = new Set();
  for (const raw of Array.isArray(sections) ? sections : []) {
    const n = Number(raw);
    if (Number.isInteger(n) && n >= 1 && n <= 12) out.add(n);
  }
  return [...out].sort((a, b) => a - b);
}

/**
 * 由 weeks 现算派生字段（weekRange / weekParity）。
 * `weeks` 永远是唯一真源，这两个字段只用于文案，不得反写。
 * @param {number[]} weeks
 * @returns {{weekRange: [number, number], weekParity: 'all'|'odd'|'even'}}
 */
export function deriveWeeks(weeks) {
  const list = normalizeWeeks(weeks);
  if (list.length === 0) return { weekRange: [1, 1], weekParity: 'all' };
  const multi = list.length > 1;
  const allOdd = multi && list.every((n) => n % 2 === 1);
  const allEven = multi && list.every((n) => n % 2 === 0);
  return {
    weekRange: [list[0], list[list.length - 1]],
    weekParity: allOdd ? 'odd' : allEven ? 'even' : 'all',
  };
}

/**
 * 校验并生成一门手动新建的课程。
 * `startTime` / `endTime` 由调用方按作息表传入（本文件不引 UI 常量）。
 * @param {object} input
 * @param {number} [seed] 生成 id 用（测试可固定）
 * @returns {object} Course
 */
export function makeCustomCourse(input, seed) {
  const weeks = normalizeWeeks(input && input.weeks);
  const sections = normalizeSections(input && input.sections);
  const stamp = Number.isFinite(seed) ? seed : Date.now();
  return {
    id: (input && input.id) || `custom_${stamp.toString(36)}${Math.random().toString(36).slice(2, 6)}`,
    code: '',
    name: String((input && input.name) || '').trim() || '未命名课程',
    teacher: String((input && input.teacher) || '').trim(),
    teacherTitle: '',
    credits: Number(input && input.credits) || 0,
    hours: 0,
    courseType: (input && input.courseType) || 'required',
    assessment: (input && input.assessment) || 'exam',
    campus: (input && input.campus) || '',
    location: String((input && input.location) || '').trim() || '未安排',
    classNo: '',
    capacity: 0,
    enrolled: 0,
    weeks,
    ...deriveWeeks(weeks),
    dayOfWeek: Math.min(7, Math.max(1, Number(input && input.dayOfWeek) || 1)),
    sections,
    startTime: (input && input.startTime) || '--:--',
    endTime: (input && input.endTime) || '--:--',
    isSelected: false,
    source: SOURCE.Custom,
    note: String((input && input.note) || '').trim(),
    colorIndex: Number.isInteger(input && input.colorIndex) ? input.colorIndex : null,
  };
}

/**
 * 教务导入结果与本地课表合并（纯函数，不写存储）。
 * @param {object[]} localCourses 本地现有课程
 * @param {object[]} incoming 教务这次返回的课程
 * @returns {{courses: object[], stats: {added: number, updated: number, kept: number, removed: number}}}
 */
export function mergeImported(localCourses, incoming) {
  const local = Array.isArray(localCourses) ? localCourses.filter(Boolean) : [];
  const localMap = new Map(local.map((course) => [course.id, course]));
  const courses = [];
  const seen = new Set();
  const stats = { added: 0, updated: 0, kept: 0, removed: 0 };

  for (const course of Array.isArray(incoming) ? incoming : []) {
    if (!course || !course.id) continue;
    seen.add(course.id);
    const old = localMap.get(course.id);
    if (!old) {
      courses.push({ ...course, source: SOURCE.Jwxt });
      stats.added += 1;
      continue;
    }
    if (old.source === SOURCE.Edited) {
      // 学生改过的课：教务的新版本不覆盖（调课后的时间才是他真正要上的）
      courses.push(old);
      stats.kept += 1;
      continue;
    }
    courses.push({
      ...course,
      source: SOURCE.Jwxt,
      colorIndex: old.colorIndex,
      note: old.note,
    });
    stats.updated += 1;
  }

  for (const old of local) {
    if (seen.has(old.id)) continue;
    if (old.source === SOURCE.Custom || old.source === SOURCE.Edited) {
      courses.push(old);
      stats.kept += 1;
    } else {
      // 教务这次没有、本地也没动过 → 教务已取消
      stats.removed += 1;
    }
  }

  return { courses, stats };
}

/**
 * 新增或替换一门课。
 * @param {object} local readLocal 的结果
 * @param {object} course
 * @returns {object} 新的 local
 */
export function upsertCourse(local, course) {
  const courses = (local.courses || []).slice();
  const at = courses.findIndex((item) => item.id === course.id);
  if (at >= 0) courses[at] = course;
  else courses.push(course);
  return { ...local, courses };
}

/**
 * 修改一门课。教务导入的课一旦被改就标成 `edited`（同步时不再被覆盖）。
 * @param {object} local
 * @param {string} id
 * @param {object} patch
 * @returns {object} 新的 local
 */
export function updateCourse(local, id, patch) {
  const courses = (local.courses || []).map((course) => {
    if (course.id !== id) return course;
    const weeks = patch && Array.isArray(patch.weeks) ? normalizeWeeks(patch.weeks) : course.weeks;
    const sections = patch && Array.isArray(patch.sections) ? normalizeSections(patch.sections) : course.sections;
    return {
      ...course,
      ...patch,
      weeks,
      sections,
      ...deriveWeeks(weeks),
      source: course.source === SOURCE.Custom ? SOURCE.Custom : SOURCE.Edited,
    };
  });
  return { ...local, courses };
}

/**
 * 删除一门课。
 * @param {object} local
 * @param {string} id
 * @returns {object}
 */
export function deleteCourse(local, id) {
  return { ...local, courses: (local.courses || []).filter((course) => course.id !== id) };
}

/**
 * 只停某一周（老师临时调课时最常用）：把该周从 weeks 里摘掉。
 * weeks 摘空后课程仍在，只是不再上网格，用户可在编辑里恢复。
 * @param {object} local
 * @param {string} id
 * @param {number} week
 * @returns {object}
 */
export function skipWeek(local, id, week) {
  const target = (local.courses || []).find((course) => course.id === id);
  if (!target) return local;
  const weeks = normalizeWeeks(target.weeks).filter((n) => n !== Number(week));
  return updateCourse(local, id, { weeks });
}

/**
 * 清空本地课表（下次同步等于重新导入）。
 * @param {string} [semesterId]
 */
export function resetLocal(semesterId) {
  remove(keyOf(semesterId));
}

/**
 * 把导入结果并进本地并落盘。
 * @param {string} [semesterId]
 * @param {object[]} incoming
 * @returns {{local: object, stats: object}}
 */
export function saveImported(semesterId, incoming) {
  const local = readLocal(semesterId);
  const merged = mergeImported(local.courses, incoming);
  const saved = writeLocal(semesterId, {
    ...local,
    courses: merged.courses,
    importedAt: new Date().toISOString(),
  });
  return { local: saved, stats: merged.stats };
}
