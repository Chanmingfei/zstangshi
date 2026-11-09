/**
 * timetableWeekPicker.js —— 周次选择弹层（1..总周数）
 *
 * 从 `timetable.js` 抽出：这是一个自洽的「打开 → 点一格 → 关闭」弹层，
 * 留在页面里既占行数又让周次状态和渲染逻辑混在一起。
 */

import { h } from '../core/dom.js';
import { createSheet } from '../components/sheet.js';

/**
 * @param {{totalWeeks: number, onPick: (week: number) => void}} options
 * @returns {{open: (current: number) => Promise<void>, destroy: () => void}}
 */
export function createWeekPicker(options = {}) {
  const sheet = createSheet({ title: '选择周次' });
  const total = Math.min(30, Math.max(1, Number(options.totalWeeks) || 19));

  function cells(current) {
    const out = [];
    for (let w = 1; w <= total; w += 1) {
      out.push(h('button', {
        className: `p-tt__week-cell${w === current ? ' is-current' : ''}`,
        type: 'button',
        dataset: { week: String(w) },
        'aria-label': `第 ${w} 周`,
        text: String(w),
      }));
    }
    return h('div', { className: 'p-tt__weeks' }, out);
  }

  const onClick = (event) => {
    const target = event.target instanceof Element ? event.target.closest('.p-tt__week-cell') : null;
    if (!target) return;
    const week = Number(target.dataset.week);
    if (!Number.isFinite(week)) return;
    sheet.close('api');
    if (typeof options.onPick === 'function') options.onPick(week);
  };
  sheet.el.addEventListener('click', onClick);

  return {
    /**
     * @param {number} current 当前周（高亮）
     */
    async open(current) {
      sheet.setContent(cells(Number(current) || 1));
      await sheet.open();
      const currentCell = sheet.el.querySelector('.p-tt__week-cell.is-current');
      if (currentCell) currentCell.focus({ preventScroll: true });
    },
    destroy() {
      sheet.el.removeEventListener('click', onClick);
      sheet.destroy();
    },
  };
}
