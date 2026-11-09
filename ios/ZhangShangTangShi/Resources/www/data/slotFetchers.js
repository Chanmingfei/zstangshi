/**
 * slotFetchers.js —— 数据槽 → provider 方法的对照表
 *
 * 页面只喊 `loadSlot('todayCourses')`，由这里决定去调 provider 的哪个方法。
 * 放在数据层的原因：这张对照表是「业务口径」而不是「页面逻辑」，改口径不该动 action 集合。
 *
 * 口径备注：
 *   · `gpa` 零计算 —— 教务权威快照，App 侧加权平均会算错（曾出现 3.07 的错报）
 *   · `selection` 一次取全（22 门），筛选/排序都在本地做
 *   · `recommend` 也是一张课表（教务「推荐课表打印」），与个人课表同构，复用同一套网格
 */

import { getSemesters, weekOfDate } from './calendar.js';

/**
 * @param {{store: object, provider: object}} deps
 * @returns {Record<string, () => Promise<object>>}
 */
export function createSlotFetchers({ store, provider }) {
  /** 当前学期对象（顶栏 context 指向的那个，取不到就退第一个） */
  function currentSemester() {
    const term = store.getState().term;
    if (!term.calendar) return null;
    const list = getSemesters(term.calendar);
    return list.find((item) => item.id === (term.context && term.context.semesterId)) || list[0] || null;
  }

  return {
    /** 今日课程（首页）：周次按「今天属于第几教学周」现算，不跟顶栏对齐 */
    todayCourses() {
      const term = store.getState().term;
      const week = weekOfDate(term.todayIso, currentSemester()) || (term.context && term.context.week) || 1;
      return provider.getTodayCourses(term.todayIso, week);
    },
    /** 整学期课表：一次取全，周次切换在本地筛（教务也只有这一个粒度） */
    timetable() {
      const term = store.getState().term;
      return provider.getTimetable(term.context && term.context.semesterId);
    },
    /** 全部成绩：一次取 `all`，学期切换与搜索都在本地筛 */
    grades() {
      return provider.getGrades('all');
    },
    gpa() {
      return provider.getGPA();
    },
    selection() {
      return provider.getCourseSelection();
    },
    recommend() {
      return provider.getRecommendedTimetable();
    },
  };
}

export default createSlotFetchers;
