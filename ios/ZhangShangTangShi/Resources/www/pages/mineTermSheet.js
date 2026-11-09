/**
 * mineTermSheet.js —— 「学期与教学周」设置弹层（从 mine.js 拆出，守住单文件 400 行）
 *
 * 为什么这个弹层值得单独一个文件：
 *   它要读校历、读用户上次的自定义、把「学年 + 学期 + 第一周周一」三件事拼成
 *   `semesterId` 与 `startDate` 两个值，还要在保存后让全局重算周数 —— 是一段
 *   有明确输入输出的独立流程，混在「我的」页里会把页面本身淹掉。
 *
 * 与页面的契约只有一个回调 `onChange`：保存/恢复后由页面去调 actions.applyTermSettings()
 * 并刷新摘要行。本文件不碰 store、不碰路由，只负责这一张表单。
 */

import { h } from '../core/dom.js';
import { createSheet } from '../components/sheet.js';
import { toast } from '../core/toast.js';
import { getSemesters } from '../data/calendar.js';
import { readCustom, saveCustom, clearCustom, parseSemesterId } from '../data/customTerm.js';

/** 学期文案（第三学期 = 暑期短学期，教务确实会用到） */
export const TERM_LABELS = Object.freeze(['第一学期', '第二学期', '第三学期']);

/**
 * @param {{onChange?: () => void}} [options] onChange：保存或恢复校历之后调用
 * @returns {{open: (term: object) => void, destroy: () => void}}
 */
export function createTermSheet(options = {}) {
  const sheet = createSheet({ title: '学期与教学周' });
  const onChange = typeof options.onChange === 'function' ? options.onChange : () => {};

  /**
   * 打开表单。
   * @param {object} term store 里的 term（要 calendar / context.semesterId）
   */
  function open(term) {
    // 预填：优先用户上次的自定义；否则当前生效学期（含其校历开学日）
    const fallback = (() => {
      const list = getSemesters(term && term.calendar);
      const id = String((term && term.context && term.context.semesterId) || '');
      const semester = list.find((item) => item.id === id) || list[0] || null;
      return semester ? { semesterId: semester.id, startDate: semester.semesterStart } : null;
    })();
    const preset = readCustom() || fallback || { semesterId: '2026-2027-1', startDate: '' };
    const parsed = parseSemesterId(preset.semesterId);

    const now = new Date();
    const base = now.getMonth() >= 7 ? now.getFullYear() : now.getFullYear() - 1; // 8 月起算新学年
    const years = [];
    for (let y = base - 2; y <= base + 1; y += 1) years.push(`${y}-${y + 1}`);

    const yearSel = h('select', { className: 'p-mine__form-field', 'aria-label': '学年' },
      years.map((y) => h('option', { value: y, text: `${y} 学年` })));
    const termSel = h('select', { className: 'p-mine__form-field', 'aria-label': '学期' },
      TERM_LABELS.map((label, at) => h('option', { value: String(at + 1), text: label })));
    const dateInput = h('input', {
      className: 'p-mine__form-field', type: 'date', 'aria-label': '第一周周一的日期',
    });

    if (parsed) {
      // 老数据可能落在候选年份之外，补一个 option 再选中，否则 select.value 会被重置成空
      if (!years.includes(parsed.academicYear)) {
        yearSel.insertBefore(
          h('option', { value: parsed.academicYear, text: `${parsed.academicYear} 学年` }),
          yearSel.firstChild
        );
      }
      yearSel.value = parsed.academicYear;
      termSel.value = String(parsed.term);
    }
    if (preset.startDate) dateInput.value = preset.startDate;

    const saveBtn = h('button', { className: 'c-btn p-mine__action', type: 'button', text: '保存并生效' });
    const resetBtn = h('button', { className: 'c-btn c-btn--ghost p-mine__action', type: 'button', text: '跟随校历' });

    const onSave = () => {
      const year = yearSel.value;
      const termNo = Number(termSel.value);
      if (!/^\d{4}-\d{4}$/.test(year) || !(termNo >= 1 && termNo <= 3)) {
        toast('请选择学年与学期', 'info');
        return;
      }
      if (!dateInput.value) {
        toast('请选择第一周周一的日期', 'info');
        return;
      }
      const saved = saveCustom({ semesterId: `${year}-${termNo}`, startDate: dateInput.value });
      if (!saved) {
        toast('保存失败，请检查日期格式', 'error');
        return;
      }
      sheet.close('api');
      onChange();
      toast(`已按第 1 周 ${saved.startDate} 生效，课表与周数已刷新`, 'success');
    };

    const onReset = () => {
      clearCustom();
      sheet.close('api');
      onChange();
      toast('已恢复跟随校历', 'success');
    };

    saveBtn.addEventListener('click', onSave);
    resetBtn.addEventListener('click', onReset);

    sheet.setContent(h('div', { className: 'p-mine__form' }, [
      h('span', { className: 'p-mine__form-label', text: '学年' }), yearSel,
      h('span', { className: 'p-mine__form-label', text: '学期' }), termSel,
      h('span', { className: 'p-mine__form-label', text: '第一周周一' }), dateInput,
      h('p', { className: 'p-mine__hint', text: '保存后 App 按此学期与周数显示课表，重启后仍生效；非周一的日期会自动归到那一周的周一。' }),
      h('div', { className: 'p-mine__actions' }, [resetBtn, saveBtn]),
    ]));
    sheet.open();
  }

  return { open, destroy: () => sheet.destroy() };
}
