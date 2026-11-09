/**
 * timetable.js —— 我的课表（P07）
 *
 * 几个不能想当然的点：
 *   1. **周次判定只认 `course.weeks`**（唯一真源）。`weekRange` / `weekParity` 是派生字段，
 *      本页一次都不读、也绝不会反写 —— 单双周课一读派生字段就会错周。
 *   2. **节次是 12 小节，但分三段**：上午 1-4、下午 5-9（是 5 节，不是 4 节）、晚间 10-12。
 *   3. **本地课表优先**（v1.6）：老师调课不一定回教务更新、学期初排课也可能不全，
 *      学生改过的课存在本地。渲染顺序是「本地 → 教务快照」，教务同步只做合并，不覆盖手改。
 *   4. **免登录可看**：有本地课表时不需要登录态；没登录也没有本地数据才引导登录 / 新建。
 *   5. **「进行中」不重建网格**：定时器只调 `markNow()` 加减一个类名，整格重绘会打断动画。
 *   6. 横向滑动才切周（见 timetableSwipe.js），长按空白格新建课程（500ms，位移 >10px 取消）。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { stagger } from '../core/animate.js';
import { toast, confirm } from '../core/toast.js';
import { emptyState } from '../components/emptyState.js';
import { skeletonList } from '../components/skeleton.js';
import { currentSection, currentSemester, weekDates, coursesInCell } from './timetableUtils.js';
import { buildGrid, markNow } from './timetableView.js';
import { createCourseDetail } from './timetableDetail.js';
import { createTimetableEditor } from './timetableEditor.js';
import { createWeekPicker } from './timetableWeekPicker.js';
import { bindWeekSwipe } from './timetableSwipe.js';
import { bindCellPress } from './timetablePress.js';
import {
  readLocal,
  writeLocal,
  saveImported,
  makeCustomCourse,
  upsertCourse,
  updateCourse,
  deleteCourse,
  skipWeek,
} from '../data/localTimetable.js';
import { syncWidget } from '../data/widget.js';

/** 「进行中」的重算间隔：一节 45 分钟，60s 足够 */
const NOW_TICK_MS = 60000;

const PLUS_ICON =
  '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>';

export default {
  name: 'timetable',
  title: '我的课表',
  tab: 'timetable',
  // 有本地课表就能看，不再要求登录（未登录时的引导由页面自己给）
  requiresAuth: false,

  render(ctx) {
    const state = ctx.store.getState();
    const weekNo = Number(state.ui.weekNo) || Number(state.term.context && state.term.context.week) || 1;

    const weekText = h('span', { className: 'p-tt__week-text', text: `第 ${weekNo} 周` });
    const prevBtn = h('button', { className: 'p-tt__nav', type: 'button', 'aria-label': '上一周', html: icon('back', 20) });
    const nextBtn = h('button', { className: 'p-tt__nav', type: 'button', 'aria-label': '下一周', html: icon('forward', 20) });
    const pickBtn = h(
      'button',
      { className: 'p-tt__week-pick', type: 'button', 'aria-haspopup': 'dialog', 'aria-label': '选择周次' },
      [weekText, h('span', { className: 'p-tt__week-caret', 'aria-hidden': 'true', html: icon('chevronDown', 16) })]
    );
    const addBtn = h('button', {
      className: 'p-tt__add',
      type: 'button',
      'aria-label': '新建课程',
      html: PLUS_ICON,
    });

    const body = h('div', { className: 'p-tt__body' }, skeletonList({ rows: 4, withTrailing: false }));
    const fab = h('button', { className: 'p-tt__fab', type: 'button', hidden: true }, [
      h('span', { className: 'p-tt__fab-icon', 'aria-hidden': 'true', html: icon('target', 16) }),
      h('span', { text: '回到本周' }),
    ]);

    const el = h('div', { className: 'page p-tt' }, [
      h('div', { className: 'p-tt__head' }, [
        h('div', { className: 'p-tt__switch' }, [prevBtn, pickBtn, nextBtn]),
        addBtn,
      ]),
      body,
      h('div', { className: 'p-tt__fab-wrap' }, fab),
    ]);

    el.__view = { weekText, prevBtn, nextBtn, pickBtn, addBtn, body, fab };
    return el;
  },

  mount(el, ctx) {
    const view = el.__view;
    const { store, actions } = ctx;

    const semester = currentSemester(store.getState());
    const semesterId = semester && semester.id;
    const totalWeeks = Math.min(30, Math.max(1, (semester && Number(semester.totalWeeks)) || 19));
    const currentWeek = Number(store.getState().term.context && store.getState().term.context.week) || 1;
    const todayIso = store.getState().term.todayIso;
    const todayWeekday = new Date(`${todayIso}T12:00:00`).getDay() || 7;

    let weekNo = Math.min(totalWeeks, Math.max(1, Number(store.getState().ui.weekNo) || currentWeek));
    /** 本地课表（可写真源） */
    let local = readLocal(semesterId);
    /** 上次吸收的教务快照时间戳，避免同一份数据反复合并落盘 */
    let lastSync = null;
    /** 正在编辑的课程（编辑态保存时用） */
    let editing = null;

    const shown = {};
    let nowSection = currentSection(new Date());

    /* ── 本地课表读写 ─────────────────────────────────────────────────── */

    /** 保存并重绘（`next` 是 localTimetable 的纯函数返回值） */
    function save(next, message) {
      local = writeLocal(semesterId, next);
      paint(false);
      // 课表变了就要让桌面小组件跟着变：改课最常见的原因就是「老师临时调课」，
      // 用户第二天看桌面时以为什么都没发生，才是最糟的体验
      syncWidget(store.getState());
      if (message) toast(message, 'success');
    }

    /** 把教务快照并进本地（手改的课不会被覆盖，手动新建的课不会被删） */
    function absorbSlot() {
      const slot = store.getState().slots.timetable;
      if (!slot || slot.status !== 'ready' || !slot.data || !Array.isArray(slot.data.courses)) return false;
      const stamp = slot.updatedAt || 'ready';
      if (stamp === lastSync) return false;
      lastSync = stamp;
      local = saveImported(semesterId, slot.data.courses).local;
      syncWidget(store.getState());
      return true;
    }

    /** 当前要画的课程：本地优先，本地为空才用教务快照 */
    function coursesNow() {
      if (local.courses.length) return local.courses;
      const slot = store.getState().slots.timetable;
      return slot && slot.data && Array.isArray(slot.data.courses) ? slot.data.courses : null;
    }

    /* ── 编辑 / 详情 ──────────────────────────────────────────────────── */

    async function removeCourse(course) {
      const ok = await confirm('删除这门课？', `「${course.name}」会从本地课表移除；重新同步教务可以恢复导入的课程。`, {
        confirmText: '删除',
        danger: true,
      });
      if (!ok) return false;
      save(deleteCourse(local, course.id), '已删除');
      return true;
    }

    const editor = createTimetableEditor({
      onSave(input, mode) {
        if (mode === 'create') {
          save(upsertCourse(local, makeCustomCourse(input)), '已添加到课表');
          return true;
        }
        if (!editing) return false;
        save(updateCourse(local, editing.id, input), '已保存修改');
        editing = null;
        return true;
      },
      onDelete: removeCourse,
    });

    const detail = createCourseDetail({
      onShowCourse(course) {
        shown[`${course.dayOfWeek}-${Math.min(...course.sections)}`] = course.id;
        paint(false);
      },
      onEdit(course) {
        editing = course;
        editor.openEdit(course, totalWeeks);
      },
      async onDelete(course) {
        if (await removeCourse(course)) detail.close();
      },
      async onSkipWeek(course, week) {
        const ok = await confirm(`第 ${week} 周停课？`, `「${course.name}」只在第 ${week} 周停上，其余周次保留。`, {
          confirmText: '停课',
        });
        if (!ok) return;
        save(skipWeek(local, course.id, week), `已在第 ${week} 周停课`);
        detail.close();
      },
    });

    /* ── 渲染 ─────────────────────────────────────────────────────────── */

    function todayIndex() {
      return weekNo === currentWeek ? todayWeekday : 0;
    }

    function updateHead() {
      view.weekText.textContent = `第 ${weekNo} 周`;
      view.prevBtn.disabled = weekNo <= 1;
      view.nextBtn.disabled = weekNo >= totalWeeks;
      view.fab.hidden = weekNo === currentWeek;
      view.pickBtn.setAttribute('aria-label', `选择周次，当前第 ${weekNo} 周`);
    }

    function paint(animate = true) {
      const slot = store.getState().slots.timetable;
      updateHead();
      const courses = coursesNow();

      if (courses === null) {
        // 只有「真的在取数」才给骨架：未登录且没有本地数据时槽位一直是 idle，
        // 这时该给空状态（登录 / 新建），否则用户会盯着一片灰条等一个永远不会来的请求。
        const sessionStatus = store.getState().session.status;
        const willLoad = Boolean(slot) && slot.status === 'idle' && (sessionStatus === 'authed' || sessionStatus === 'demo');
        if ((slot && slot.status === 'loading') || willLoad) {
          view.body.replaceChildren(skeletonList({ rows: 4, withTrailing: false }));
          return;
        }
        const failed = Boolean(slot && slot.error);
        view.body.replaceChildren(
          emptyState({
            kind: failed ? 'error' : 'empty',
            title: failed ? (slot.error.message || '课表没能取回来') : '还没有课表',
            description: failed ? '可以稍后重试，或直接手动新建一门课' : '登录教务同步一次，或直接手动新建课程',
            actionLabel: failed ? '重试' : '新建课程',
            onAction: failed
              ? () => actions.loadSlot('timetable', { force: true })
              : () => editor.openCreate({ totalWeeks, dayOfWeek: todayWeekday }),
          })
        );
        return;
      }

      const { grid, cards } = buildGrid(courses, {
        weekNo,
        todayIndex: todayIndex(),
        dates: weekDates(semester, weekNo),
        nowSection,
        shown,
      });
      view.body.replaceChildren(grid);
      if (animate) stagger(cards, { step: 40 });
    }

    function refreshNow() {
      const next = todayIndex() ? currentSection(new Date()) : 0;
      if (next === nowSection) return;
      nowSection = next;
      markNow(view.body.querySelector('.p-tt__grid'), nowSection, todayIndex());
    }

    const nowTimer = window.setInterval(refreshNow, NOW_TICK_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') refreshNow();
    };
    document.addEventListener('visibilitychange', onVisible);

    function setWeek(next) {
      const clamped = Math.min(totalWeeks, Math.max(1, next));
      if (clamped === weekNo) return;
      weekNo = clamped;
      nowSection = clamped === currentWeek ? currentSection(new Date()) : 0;
      actions.setUi({ weekNo: clamped });
      paint();
    }

    /* ── 周次切换器 ───────────────────────────────────────────────────── */

    const onPrev = () => setWeek(weekNo - 1);
    const onNext = () => setWeek(weekNo + 1);
    view.prevBtn.addEventListener('click', onPrev);
    view.nextBtn.addEventListener('click', onNext);

    const picker = createWeekPicker({ totalWeeks, onPick: (week) => setWeek(week) });

    const onPickOpen = () => picker.open(weekNo);
    view.pickBtn.addEventListener('click', onPickOpen);

    /* ── 新建：右上角 + / 长按空白格 ──────────────────────────────────── */

    const onAdd = () => editor.openCreate({ totalWeeks, dayOfWeek: todayWeekday });
    view.addBtn.addEventListener('click', onAdd);

    const unbindPress = bindCellPress(view.body, {
      onPress: (cell) =>
        editor.openCreate({
          totalWeeks,
          dayOfWeek: Number(cell.dataset.day) || 1,
          start: Number(cell.dataset.section) || 1,
        }),
    });

    /* ── 回到本周 ─────────────────────────────────────────────────────── */

    const onFab = () => {
      if (weekNo === currentWeek) return;
      setWeek(currentWeek);
      view.pickBtn.classList.remove('is-flash');
      void view.pickBtn.offsetWidth;
      view.pickBtn.classList.add('is-flash');
    };
    view.fab.addEventListener('click', onFab);
    view.pickBtn.addEventListener('animationend', () => view.pickBtn.classList.remove('is-flash'));

    /* ── 卡片 → 详情层 ────────────────────────────────────────────────── */

    const onBodyClick = (event) => {
      const holder = event.target.closest('.p-tt__gridcard');
      if (!holder) return;
      const courses = coursesNow();
      if (!courses) return;
      const day = Number(holder.dataset.day);
      const start = Number(holder.dataset.start);
      const { active, rotating } = coursesInCell(courses, day, start, weekNo);
      const list = active.concat(rotating);
      if (list.length === 0) return;
      const primaryId = holder.dataset.courseId;
      const startIndex = Math.max(0, list.findIndex((course) => course.id === primaryId));
      detail.open(list, startIndex, { weekNo });
    };
    view.body.addEventListener('click', onBodyClick);

    /* ── 手势 / 订阅 / 首次取数 ───────────────────────────────────────── */

    const unbindSwipe = bindWeekSwipe(el, { onSwipe: (direction) => setWeek(weekNo + direction) });

    const unsubscribe = store.subscribe(
      (state) => state.slots.timetable,
      () => {
        absorbSlot();
        paint();
      }
    );

    absorbSlot();
    paint();

    const slot = store.getState().slots.timetable;
    const status = store.getState().session.status;
    if ((!slot || slot.status === 'idle') && (status === 'authed' || status === 'demo')) {
      actions.loadSlot('timetable');
    }

    return () => {
      unsubscribe();
      unbindSwipe();
      unbindPress();
      window.clearInterval(nowTimer);
      document.removeEventListener('visibilitychange', onVisible);
      view.prevBtn.removeEventListener('click', onPrev);
      view.nextBtn.removeEventListener('click', onNext);
      view.pickBtn.removeEventListener('click', onPickOpen);
      view.addBtn.removeEventListener('click', onAdd);
      view.fab.removeEventListener('click', onFab);
      view.body.removeEventListener('click', onBodyClick);
      detail.destroy();
      editor.destroy();
      picker.destroy();
    };
  },

  unmount() {
    /* 全部监听与弹层在 cleanup 里收尾 */
  },
};
