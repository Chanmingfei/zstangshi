/**
 * recommendImport.js —— 推荐课表「一键导入我的课表」
 *
 * 存在的理由（真实时序）：**推荐课表往往比个人课表先出炉**。学期初教务处先发各班的推荐
 * 方案，个人课表要等选课/排课结束才有；这时候学生只能对着推荐课表看，一门门手抄进本地
 * 课表既慢又容易抄错。所以给一个整份导入。
 *
 * 三条安全边界：
 *   1. **只增不改**（见 timetableBulk.importCourses）：已存在的课一门都不覆盖 —— 个人课表
 *      可能已经出炉、也可能有学生手改的版本，一份「建议方案」没资格改它们；
 *   2. **导入前必须二次确认**，并明确告诉用户会新增几门、已存在几门被跳过；
 *   3. 重复点不会重复导入（按钮进 busy，且第二次会被「已存在」全部跳过）。
 */

import { h } from '../core/dom.js';
import { toast, confirm } from '../core/toast.js';
import { currentSemester } from './timetableUtils.js';
import { readLocal } from '../data/localTimetable.js';
import { saveBulkImport } from '../data/timetableBulk.js';
import { syncWidget } from '../data/widget.js';

/**
 * 在推荐课表页挂一条导入操作条（查询出结果后才出现）。
 *
 * @param {{
 *   root: HTMLElement,
 *   store: object,
 *   getCourses: () => (object[]|null),
 *   onImported?: (local: object) => void
 * }} options
 * @returns {{el: HTMLElement, update: () => void, destroy: () => void}}
 */
export function createImportBar(options) {
  const { root, store, getCourses } = options;
  const onImported = typeof options.onImported === 'function' ? options.onImported : null;

  const btn = h('button', { className: 'p-rec__import', type: 'button', text: '导入到我的课表' });
  const el = h('div', { className: 'p-rec__actions', hidden: true }, [btn]);

  /** 当前查询结果里的课程（没有结果就是空数组） */
  const coursesNow = () => {
    const list = typeof getCourses === 'function' ? getCourses() : null;
    return Array.isArray(list) ? list.filter((course) => course && course.id) : [];
  };

  /** 和本地已有的课比一比，算出「会新增几门 / 已经有几门」 */
  function diff(list) {
    const sem = currentSemester(store.getState());
    const have = new Set(readLocal(sem && sem.id).courses.map((course) => course.id));
    const added = list.filter((course) => !have.has(course.id));
    return { added: added.length, skipped: list.length - added.length, semesterId: sem && sem.id };
  }

  function update() {
    const list = coursesNow();
    el.hidden = list.length === 0;
    btn.disabled = list.length === 0;
    if (list.length) btn.textContent = `导入到我的课表（${list.length} 门）`;
  }

  let busy = false;
  async function onClick() {
    if (busy) return;
    const list = coursesNow();
    if (!list.length) return;
    const { added, skipped, semesterId } = diff(list);
    if (added === 0) {
      toast(`这 ${list.length} 门课都已经在课表里了`, 'info');
      return;
    }
    const detail = skipped > 0
      ? `会新增 ${added} 门课；另有 ${skipped} 门已经在你的课表里，不会被覆盖。导入后可在「课表」里逐门修改。`
      : `会把 ${added} 门课追加到你的个人课表。导入后可在「课表」里逐门修改或删除。`;
    const yes = await confirm('导入到我的课表？', detail, { confirmText: '导入' });
    if (!yes) return;

    busy = true;
    btn.disabled = true;
    try {
      const result = saveBulkImport(semesterId, list);
      // 课表变了，桌面小组件的「今日课程」要跟着变
      syncWidget(store.getState());
      if (onImported) onImported(result.local);
      toast(result.added > 0 ? `已导入 ${result.added} 门课` : '没有需要新增的课', 'success');
    } finally {
      busy = false;
      update();
    }
  }

  btn.addEventListener('click', onClick);
  // 放在「数据来源」说明下方：用户刚看完「这是推荐方案」，紧跟着就是「要不要拿过去」
  const anchor = root.querySelector('.p-rec__source');
  if (anchor && anchor.parentNode) anchor.parentNode.insertBefore(el, anchor.nextSibling);
  else root.insertBefore(el, root.firstChild);

  return {
    el,
    update,
    destroy() {
      btn.removeEventListener('click', onClick);
      el.remove();
    },
  };
}

export default createImportBar;
