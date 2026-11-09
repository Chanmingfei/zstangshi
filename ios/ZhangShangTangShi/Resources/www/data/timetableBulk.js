/**
 * timetableBulk.js —— 本地课表的**批量**写入（整份导入 / 以教务为准恢复）
 *
 * 与 localTimetable.js 的分工：那边管单门课的增删改与合并规则，这里只做「一次动一整份」，
 * 且两条路各有自己的安全阀：
 *
 *   · `importCourses`  **只增不改**。推荐课表只是「建议修读方案」，个人课表可能已经出炉、
 *     也可能有学生手改的版本 —— 用一份推荐方案去覆盖它们是不可接受的，所以已存在的
 *     id 一律跳过。新加入的标 `jwxt`：等真正的个人课表同步到，会被正常接管或清理。
 *
 *   · `restoreFromJwxt` **以教务为准**。这是「我改错了，想恢复」的后悔药，方向必须反过来：
 *     本地 `edited` 降级成 `jwxt` 再走普通合并 —— 手改的周次/时间会被教务版本覆盖，
 *     教务已取消的课会被删掉。唯一不动的是 `custom`（学生手动新建的课，教务里根本没有
 *     它们，删了就真找不回来了）。
 */

import { SOURCE, readLocal, writeLocal, mergeImported } from './localTimetable.js';

/**
 * 批量追加课程（推荐课表「一键导入我的课表」）。
 * @param {object} local readLocal 的结果
 * @param {object[]} incoming 待导入的课程
 * @returns {{local: object, added: number, skipped: number}}
 */
export function importCourses(local, incoming) {
  const courses = Array.isArray(local.courses) ? local.courses : [];
  const have = new Set(courses.map((course) => course.id));
  const added = [];
  let skipped = 0;
  for (const course of Array.isArray(incoming) ? incoming : []) {
    if (!course || !course.id) continue;
    if (have.has(course.id)) {
      skipped += 1;
      continue;
    }
    have.add(course.id);
    added.push({ ...course, source: SOURCE.Jwxt });
  }
  return {
    local: {
      ...local,
      courses: [...courses, ...added],
      importedAt: new Date().toISOString(),
    },
    added: added.length,
    skipped,
  };
}

/**
 * 恢复成教务原样（个人课表「重新与教务同步」）。
 * @param {object[]} localCourses 本地现有课程
 * @param {object[]} incoming 教务这次返回的课程
 * @returns {{courses: object[], stats: object}}
 */
export function restoreFromJwxt(localCourses, incoming) {
  const demoted = (Array.isArray(localCourses) ? localCourses : []).map((course) =>
    course && course.source === SOURCE.Edited ? { ...course, source: SOURCE.Jwxt } : course
  );
  return mergeImported(demoted, incoming);
}

/**
 * 把推荐课表导入本地并落盘。
 * @param {string} [semesterId]
 * @param {object[]} incoming
 * @returns {{local: object, added: number, skipped: number}}
 */
export function saveBulkImport(semesterId, incoming) {
  const local = readLocal(semesterId);
  const result = importCourses(local, incoming);
  return { ...result, local: writeLocal(semesterId, result.local) };
}

/**
 * 以教务为准重写本地并落盘。
 * @param {string} [semesterId]
 * @param {object[]} incoming
 * @returns {{local: object, stats: object}}
 */
export function saveRestored(semesterId, incoming) {
  const local = readLocal(semesterId);
  const restored = restoreFromJwxt(local.courses, incoming);
  const saved = writeLocal(semesterId, {
    ...local,
    courses: restored.courses,
    importedAt: new Date().toISOString(),
  });
  return { local: saved, stats: restored.stats };
}
