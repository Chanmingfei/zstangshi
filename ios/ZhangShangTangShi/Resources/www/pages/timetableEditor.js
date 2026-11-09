/**
 * timetableEditor.js —— 新建 / 编辑课程的弹层表单
 *
 * 为什么单独一个文件：`timetable.js` 已经背着周次状态、手势、定时器和数据同步，
 * 再塞一套表单必然顶破 400 行守卫；而表单本身是「打开 → 填 → 保存」的自洽单元。
 *
 * 三个设计决定：
 *   1. **周次用 1..N 的格子直接勾**，不做「起止 + 单双周」两段式。
 *      教务的周次本来就是任意集合（`1-8,10-16`、`1-15(单)`），两段式表达不了，
 *      而调课场景最常做的恰恰是「只改某几周」。
 *   2. **节次只选起止，存成连续区间**。跨节的单门课（1-2、3-5）是常态，
 *      允许乱序勾选只会产生教务里不存在的课，反而让人怀疑数据错了。
 *   3. **保存只回传表单字段，不构造完整 Course**：新建由 `makeCustomCourse` 补默认值，
 *      编辑走 `updateCourse` 的 patch 合并 —— 这样教务课的学分、课程号、教学班不会被抹掉。
 */

import { h } from '../core/dom.js';
import { toast } from '../core/toast.js';
import { createSheet } from '../components/sheet.js';
import { WEEKDAY_LABELS } from '../data/calendar.js';
import { PERIOD_TIMES, TOTAL_SECTIONS } from './timetableUtils.js';
import { normalizeWeeks, normalizeSections } from '../data/localTimetable.js';

/** 周次快捷选择 */
const QUICK = [
  { key: 'all', label: '全部' },
  { key: 'odd', label: '单周' },
  { key: 'even', label: '双周' },
  { key: 'none', label: '清除' },
];

/**
 * 建编辑器。
 *
 * @param {{
 *   onSave?: (input: object, mode: 'create'|'edit') => (boolean|Promise<boolean>),
 *   onDelete?: (course: object) => (boolean|Promise<boolean>)
 * }} [options]
 * @returns {{openCreate: Function, openEdit: Function, destroy: Function}}
 */
export function createTimetableEditor(options = {}) {
  const sheet = createSheet({ title: '新建课程' });
  const body = sheet.panel.querySelector('.c-sheet__body');

  /** @type {'create'|'edit'} */
  let mode = 'create';
  /** @type {object|null} */
  let draft = null;
  let totalWeeks = 19;

  /** 当前勾选的周次（DOM 为准，保存时再读一次，避免两处状态不同步） */
  const selectedWeeks = () => {
    const out = [];
    for (const btn of body.querySelectorAll('.p-te__week.is-active')) out.push(Number(btn.dataset.week));
    return normalizeWeeks(out);
  };

  const selectedDay = () => {
    const btn = body.querySelector('.p-te__day.is-active');
    return btn ? Number(btn.dataset.day) : 1;
  };

  const sectionsOf = () => {
    const from = Number((body.querySelector('.p-te__from') || {}).value) || 1;
    const to = Number((body.querySelector('.p-te__to') || {}).value) || from;
    const [start, end] = from <= to ? [from, to] : [to, from];
    const list = [];
    for (let n = start; n <= end; n += 1) list.push(n);
    return normalizeSections(list);
  };

  const updateWeekHint = () => {
    const hint = body.querySelector('.p-te__week-hint');
    if (!hint) return;
    const count = selectedWeeks().length;
    hint.textContent = count ? `已选 ${count} 周` : '还没有选周次';
  };

  /** 按模式与草稿生成表单节点 */
  function formNodes(course) {
    const weeks = normalizeWeeks(course && course.weeks && course.weeks.length ? course.weeks : weeksRange(totalWeeks));
    const sections = normalizeSections((course && course.sections) || [1, 2]);
    const start = sections[0] || 1;
    const end = sections[sections.length - 1] || start;
    const day = (course && course.dayOfWeek) || 1;

    const nameInput = h('input', {
      className: 'p-te__input p-te__name',
      type: 'text',
      value: (course && course.name) || '',
      placeholder: '例如：高等数学 A(上)',
      maxlength: '40',
      autocomplete: 'off',
    });
    const teacherInput = h('input', {
      className: 'p-te__input p-te__teacher',
      type: 'text',
      value: (course && course.teacher) || '',
      placeholder: '教师（可留空）',
      maxlength: '20',
    });
    const placeInput = h('input', {
      className: 'p-te__input p-te__place',
      type: 'text',
      value: course && course.location && course.location !== '未安排' ? course.location : '',
      placeholder: '例如：博学楼 A302',
      maxlength: '30',
    });
    const noteInput = h('input', {
      className: 'p-te__input p-te__note',
      type: 'text',
      value: (course && course.note) || '',
      placeholder: '备注（可留空）',
      maxlength: '40',
    });

    const fromSel = h('select', { className: 'p-te__select p-te__from', 'aria-label': '开始节次' },
      PERIOD_TIMES.map((_, i) => h('option', { value: String(i + 1), text: `第 ${i + 1} 节` })));
    const toSel = h('select', { className: 'p-te__select p-te__to', 'aria-label': '结束节次' },
      PERIOD_TIMES.map((_, i) => h('option', { value: String(i + 1), text: `第 ${i + 1} 节` })));
    fromSel.value = String(start);
    toSel.value = String(end);

    const dayBtns = WEEKDAY_LABELS.map((label, i) =>
      h('button', {
        className: `p-te__day${i + 1 === day ? ' is-active' : ''}`,
        type: 'button',
        dataset: { day: String(i + 1) },
        'aria-pressed': i + 1 === day ? 'true' : 'false',
        text: label.replace('周', ''),
      }));

    const weekBtns = [];
    for (let w = 1; w <= totalWeeks; w += 1) {
      weekBtns.push(h('button', {
        className: `p-te__week${weeks.includes(w) ? ' is-active' : ''}`,
        type: 'button',
        dataset: { week: String(w) },
        'aria-pressed': weeks.includes(w) ? 'true' : 'false',
        'aria-label': `第 ${w} 周`,
        text: String(w),
      }));
    }

    const timeText = `${PERIOD_TIMES[start - 1][0]}-${PERIOD_TIMES[end - 1][1]}`;

    return [
      h('label', { className: 'p-te__field' }, [
        h('span', { className: 'p-te__label', text: '课程名' }),
        nameInput,
      ]),
      h('div', { className: 'p-te__row' }, [
        h('label', { className: 'p-te__field' }, [h('span', { className: 'p-te__label', text: '教师' }), teacherInput]),
        h('label', { className: 'p-te__field' }, [h('span', { className: 'p-te__label', text: '地点' }), placeInput]),
      ]),
      h('div', { className: 'p-te__block' }, [
        h('span', { className: 'p-te__label', text: '星期' }),
        h('div', { className: 'p-te__days' }, dayBtns),
      ]),
      h('div', { className: 'p-te__block' }, [
        h('span', { className: 'p-te__label', text: '节次' }),
        h('div', { className: 'p-te__sections' }, [
          fromSel,
          h('span', { className: 'p-te__dash', text: '至' }),
          toSel,
          h('span', { className: 'p-te__time', text: timeText }),
        ]),
      ]),
      h('div', { className: 'p-te__block' }, [
        h('div', { className: 'p-te__block-head' }, [
          h('span', { className: 'p-te__label', text: '周次' }),
          h('div', { className: 'p-te__quick' }, QUICK.map((item) =>
            h('button', {
              className: 'p-te__quick-btn',
              type: 'button',
              dataset: { quick: item.key },
              text: item.label,
            }))),
        ]),
        h('div', { className: 'p-te__weeks' }, weekBtns),
        h('p', { className: 'p-te__week-hint', text: `已选 ${weeks.length} 周` }),
      ]),
      h('label', { className: 'p-te__field' }, [
        h('span', { className: 'p-te__label', text: '备注' }),
        noteInput,
      ]),
      h('div', { className: 'p-te__actions' }, [
        mode === 'edit'
          ? h('button', { className: 'p-te__delete', type: 'button', text: '删除这门课' })
          : h('button', { className: 'p-te__cancel', type: 'button', text: '取消' }),
        h('button', { className: 'p-te__save', type: 'button', text: mode === 'edit' ? '保存修改' : '添加到课表' }),
      ]),
    ];
  }

  /** 点某个按钮后同步同步属性（读屏与视觉都要一致） */
  const markActive = (btn, on) => {
    btn.classList.toggle('is-active', on);
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
  };

  const onClick = (event) => {
    const target = event.target instanceof Element ? event.target : null;
    if (!target) return;

    const dayBtn = target.closest('.p-te__day');
    if (dayBtn) {
      for (const btn of body.querySelectorAll('.p-te__day')) markActive(btn, btn === dayBtn);
      return;
    }

    const weekBtn = target.closest('.p-te__week');
    if (weekBtn) {
      markActive(weekBtn, !weekBtn.classList.contains('is-active'));
      updateWeekHint();
      return;
    }

    const quickBtn = target.closest('.p-te__quick-btn');
    if (quickBtn) {
      const key = quickBtn.dataset.quick;
      for (const btn of body.querySelectorAll('.p-te__week')) {
        const week = Number(btn.dataset.week);
        let on = true;
        if (key === 'odd') on = week % 2 === 1;
        else if (key === 'even') on = week % 2 === 0;
        else if (key === 'none') on = false;
        markActive(btn, on);
      }
      updateWeekHint();
      return;
    }

    if (target.closest('.p-te__cancel')) {
      sheet.close('api');
      return;
    }

    if (target.closest('.p-te__delete')) {
      const course = draft;
      if (!course) return;
      Promise.resolve(options.onDelete ? options.onDelete(course) : false).then((done) => {
        if (done) sheet.close('api');
      });
      return;
    }

    if (target.closest('.p-te__save')) {
      const nameInput = body.querySelector('.p-te__name');
      const name = (nameInput.value || '').trim();
      if (!name) {
        toast('请先填写课程名', 'warning');
        nameInput.focus();
        return;
      }
      const weeks = selectedWeeks();
      if (weeks.length === 0) {
        toast('至少选择一周', 'warning');
        return;
      }
      const sections = sectionsOf();
      const input = {
        name,
        teacher: (body.querySelector('.p-te__teacher').value || '').trim(),
        location: (body.querySelector('.p-te__place').value || '').trim(),
        note: (body.querySelector('.p-te__note').value || '').trim(),
        dayOfWeek: selectedDay(),
        sections,
        weeks,
        startTime: PERIOD_TIMES[sections[0] - 1][0],
        endTime: PERIOD_TIMES[sections[sections.length - 1] - 1][1],
      };
      const btn = target.closest('.p-te__save');
      btn.disabled = true;
      Promise.resolve(options.onSave ? options.onSave(input, mode) : false).then((done) => {
        btn.disabled = false;
        if (done) sheet.close('api');
      });
    }
  };

  // 节次下拉变化时刷新旁边的时间文案（用户不用自己算 08:00-09:40）
  const onChange = (event) => {
    const target = event.target;
    if (!target.classList || !target.classList.contains('p-te__select')) return;
    const sections = sectionsOf();
    const label = body.querySelector('.p-te__time');
    if (label) label.textContent = `${PERIOD_TIMES[sections[0] - 1][0]}-${PERIOD_TIMES[sections[sections.length - 1] - 1][1]}`;
  };

  body.addEventListener('click', onClick);
  body.addEventListener('change', onChange);

  return {
    /**
     * 新建：可预填某一格的星期与起始节次（长按空白格进来时用）。
     * @param {{dayOfWeek?: number, start?: number, totalWeeks?: number}} [preset]
     */
    async openCreate(preset = {}) {
      mode = 'create';
      draft = null;
      totalWeeks = Math.min(30, Math.max(1, Number(preset.totalWeeks) || 19));
      sheet.setTitle('新建课程');
      const start = Math.min(TOTAL_SECTIONS, Math.max(1, Number(preset.start) || 1));
      body.replaceChildren(...formNodes({
        name: '',
        teacher: '',
        location: '',
        weeks: weeksRange(totalWeeks),
        dayOfWeek: preset.dayOfWeek || 1,
        sections: [start, Math.min(TOTAL_SECTIONS, start + 1)],
      }));
      updateWeekHint();
      await sheet.open();
      const nameInput = body.querySelector('.p-te__name');
      if (nameInput) nameInput.focus({ preventScroll: true });
    },

    /**
     * 编辑：填入现有课程。教务课也允许改，改完标记为「已修改」，下次同步不覆盖。
     * @param {object} course
     * @param {number} [weeks]
     */
    async openEdit(course, weeks) {
      mode = 'edit';
      draft = course;
      totalWeeks = Math.min(30, Math.max(1, Number(weeks) || 19));
      sheet.setTitle('编辑课程');
      body.replaceChildren(...formNodes(course));
      updateWeekHint();
      await sheet.open();
    },

    destroy() {
      body.removeEventListener('click', onClick);
      body.removeEventListener('change', onChange);
      sheet.destroy();
    },
  };
}

/** 1..n 的整数数组 */
function weeksRange(n) {
  const out = [];
  for (let i = 1; i <= n; i += 1) out.push(i);
  return out;
}
