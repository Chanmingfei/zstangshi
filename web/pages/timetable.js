/**
 * timetable.js —— 我的课表（P07）
 *
 * 几个不能想当然的点：
 *   1. **周次判定只认 `course.weeks`**（唯一真源）。`weekRange` / `weekParity` 是派生字段，
 *      本页一次都不读、也绝不会反写 —— 单双周课（书写技能训练 / 普通话训练）一读派生字段就会错周。
 *   2. **节次是 12 小节，但分三段**：上午 1-4、下午 5-9（是 5 节，不是 4 节）、晚间 10-12。
 *      所以网格必须 12 行，不能按「上午 4 + 下午 4」想当然排。
 *   3. **考试周不做特殊态**（2026-11-06 用户口径）：18/19 周也可能因调课排课，教务给什么展示什么
 *      「复习考试周」，否则用户会以为数据丢了。
 *   4. 横向滑动才切周，纵向一律让给原生滚动（将来这页挂下拉刷新时也不用改判定）：
 *      第一次位移就定主轴，`|dx| > |dy| * 1.2` 才算横滑，之后不再改判。
 *   5. 周次存在 `ui.weekNo`：切到第 15 周 → 去「我的」→ 回来仍是第 15 周（验收 ⑤）。
 *   6. **「进行中」不重建网格**：定时器只调 `markNow()` 加减一个类名。整格重绘会打断
 *      入场动画与滚动位置，而这件事每分钟都要发生一次。定时器在 cleanup 里必须清掉。
 *
 * 卡片直接复用 `courseCard`（四类配色、44px 热区、读屏标签都在组件里），只补一个类把它压紧。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { stagger } from '../core/animate.js';
import { createSheet } from '../components/sheet.js';
import { emptyState } from '../components/emptyState.js';
import { skeletonList } from '../components/skeleton.js';
import { currentSection, currentSemester, weekDates, coursesInCell } from './timetableUtils.js';
import { buildGrid, markNow } from './timetableView.js';
import { createCourseDetail } from './timetableDetail.js';

/** 横滑判定：位移阈值 + 主轴比例（见文件头第 4 条） */
const SWIPE_THRESHOLD = 48;
const AXIS_RATIO = 1.2;
/** 「进行中」的重算间隔：一节 45 分钟，60s 足够，跨边界最多晚一分钟但不会漏 */
const NOW_TICK_MS = 60000;

export default {
  name: 'timetable',
  title: '我的课表',
  tab: 'timetable',
  requiresAuth: true,

  /**
   * 同步出骨架（周次切换器 + 占位），数据在 mount 里填。
   * @param {object} ctx
   * @returns {HTMLElement}
   */
  render(ctx) {
    const state = ctx.store.getState();
    const weekNo = Number(state.ui.weekNo) || Number(state.term.context && state.term.context.week) || 1;

    const weekText = h('span', { className: 'p-tt__week-text', text: `第 ${weekNo} 周` });
    const prevBtn = h('button', {
      className: 'p-tt__nav',
      type: 'button',
      'aria-label': '上一周',
      html: icon('back', 20),
    });
    const nextBtn = h('button', {
      className: 'p-tt__nav',
      type: 'button',
      'aria-label': '下一周',
      html: icon('forward', 20),
    });
    const pickBtn = h(
      'button',
      { className: 'p-tt__week-pick', type: 'button', 'aria-haspopup': 'dialog', 'aria-label': '选择周次' },
      [weekText, h('span', { className: 'p-tt__week-caret', 'aria-hidden': 'true', html: icon('chevronDown', 16) })]
    );

    const body = h('div', { className: 'p-tt__body' }, skeletonList({ rows: 4, withTrailing: false }));
    const fab = h('button', { className: 'p-tt__fab', type: 'button', hidden: true }, [
      h('span', { className: 'p-tt__fab-icon', 'aria-hidden': 'true', html: icon('target', 16) }),
      h('span', { text: '回到本周' }),
    ]);

    const el = h('div', { className: 'page p-tt' }, [
      h('div', { className: 'p-tt__head' }, [h('div', { className: 'p-tt__switch' }, [prevBtn, pickBtn, nextBtn])]),
      body,
      h('div', { className: 'p-tt__fab-wrap' }, fab),
    ]);

    el.__view = { weekText, prevBtn, nextBtn, pickBtn, body, fab };
    return el;
  },

  /**
   * @param {HTMLElement} el
   * @param {object} ctx
   * @returns {() => void} cleanup
   */
  mount(el, ctx) {
    const view = el.__view;
    const { store, actions } = ctx;

    const semester = currentSemester(store.getState());
    const totalWeeks = (semester && Number(semester.totalWeeks)) || 19;
    const currentWeek = Number(store.getState().term.context && store.getState().term.context.week) || 1;
    const todayIso = store.getState().term.todayIso;
    const todayWeekday = new Date(`${todayIso}T12:00:00`).getDay() || 7;

    let weekNo = Math.min(totalWeeks, Math.max(1, Number(store.getState().ui.weekNo) || currentWeek));

    /** 冲突格里用户手动选过「显示这门课」的课程：key = `day-start` */
    const shown = {};
    /** 当前时间命中的节次（0 = 不在任何节次内）；只在「本周」时有意义 */
    let nowSection = currentSection(new Date());

    const detail = createCourseDetail({
      onShowCourse(course) {
        const key = `${course.dayOfWeek}-${Math.min(...course.sections)}`;
        shown[key] = course.id;
        // 重画但不重新入场：用户刚在弹层里做过选择，网格再「飞一次」会显得没完没了
        paint(false);
      },
    });
    const picker = createSheet({ title: '选择周次' });

    /** 当前周如果不是本周，就不该给「今日列」高亮，也不该有「进行中」 */
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

    /** 重画整个 body（骨架 / 错误 / 考试周 / 正常网格四态） */
    function paint(animate = true) {
      const slot = store.getState().slots.timetable;
      updateHead();

      if (!slot || slot.status === 'idle' || slot.status === 'loading') {
        view.body.replaceChildren(skeletonList({ rows: 4, withTrailing: false }));
        return;
      }

      if (!slot.data) {
        view.body.replaceChildren(
          emptyState({
            kind: 'error',
            title: (slot.error && slot.error.message) || '课表没能取回来',
            description: '周次切换仍然可用，稍后重试即可',
            actionLabel: '重试',
            onAction: () => actions.loadSlot('timetable', { force: true }),
          })
        );
        return;
      }

      // 不做考试周特殊态：18/19 周也可能因调课排课，教务给什么就展示什么（用户口径 2026-11-06）

      const { grid, cards } = buildGrid(slot.data.courses, {
        weekNo,
        todayIndex: todayIndex(),
        dates: weekDates(semester, weekNo),
        nowSection,
        shown,
      });
      view.body.replaceChildren(grid);
      // 切周后按时间顺序入场（buildGrid 已按 星期→节次 排好）
      if (animate) stagger(cards, { step: 40 });
    }

    /**
     * 每 60 秒重算一次「现在上第几节」。只在真的换了节次时才动 DOM，
     * 否则每分钟都会引起一次样式重算（虽然只改一个类，但没必要）。
     */
    function refreshNow() {
      const next = todayIndex() ? currentSection(new Date()) : 0;
      if (next === nowSection) return;
      nowSection = next;
      markNow(view.body.querySelector('.p-tt__grid'), nowSection, todayIndex());
    }

    const nowTimer = window.setInterval(refreshNow, NOW_TICK_MS);
    // 切后台回来时定时器可能已经被浏览器降频，补算一次（跨节次边界不会漏）
    const onVisible = () => {
      if (document.visibilityState === 'visible') refreshNow();
    };
    document.addEventListener('visibilitychange', onVisible);

    function setWeek(next) {
      const clamped = Math.min(totalWeeks, Math.max(1, next));
      if (clamped === weekNo) return;
      weekNo = clamped;
      // 切周时立刻重算一次，否则从别的周切回本周会沿用上一周的 nowSection（可能已过期）
      nowSection = clamped === currentWeek ? currentSection(new Date()) : 0;
      actions.setUi({ weekNo: clamped });
      paint();
    }

    /* ── 周次切换器 ─────────────────────────────────────────────────────── */

    const onPrev = () => setWeek(weekNo - 1);
    const onNext = () => setWeek(weekNo + 1);
    view.prevBtn.addEventListener('click', onPrev);
    view.nextBtn.addEventListener('click', onNext);

    /** 周次选择器：19 格，考试周标注出来，避免点进去发现没课以为坏了 */
    function buildPicker() {
      const cells = [];
      for (let w = 1; w <= totalWeeks; w += 1) {
        const cls = ['p-tt__week-cell'];
        if (w === weekNo) cls.push('is-current');
        cells.push(
          h('button', {
            className: cls.join(' '),
            type: 'button',
            dataset: { week: String(w) },
            'aria-label': `第 ${w} 周`,
            text: String(w),
          })
        );
      }
      return h('div', { className: 'p-tt__weeks' }, cells);
    }

    const onPickOpen = async () => {
      picker.setContent(buildPicker());
      await picker.open();
      const current = picker.el.querySelector('.p-tt__week-cell.is-current');
      if (current) current.focus({ preventScroll: true });
    };
    view.pickBtn.addEventListener('click', onPickOpen);

    const onPickClick = (event) => {
      const cell = event.target.closest('.p-tt__week-cell');
      if (!cell) return;
      setWeek(Number(cell.dataset.week));
      picker.close('api');
    };
    picker.el.addEventListener('click', onPickClick);

    /* ── 回到本周 ───────────────────────────────────────────────────────── */

    const onFab = () => {
      if (weekNo === currentWeek) return;
      setWeek(currentWeek);
      // 短暂高亮：让用户知道「回来了」而不是页面自己跳了一下
      view.pickBtn.classList.remove('is-flash');
      void view.pickBtn.offsetWidth;
      view.pickBtn.classList.add('is-flash');
    };
    view.fab.addEventListener('click', onFab);
    view.pickBtn.addEventListener('animationend', () => view.pickBtn.classList.remove('is-flash'));

    /* ── 卡片 → 详情层 ──────────────────────────────────────────────────── */

    const onBodyClick = (event) => {
      const holder = event.target.closest('.p-tt__gridcard');
      if (!holder) return;
      const data = store.getState().slots.timetable.data;
      if (!data) return;

      // 同格的课要现算：卡上只画了「主课」，但详情层允许左右翻出这一格的全部课
      // （含本周不上、隔周才来的单/双周轮换课）
      const day = Number(holder.dataset.day);
      const start = Number(holder.dataset.start);
      const { active, rotating, relation } = coursesInCell(data.courses, day, start, weekNo);
      const courses = active.concat(rotating);
      if (courses.length === 0) return;

      const primaryId = holder.dataset.courseId;
      const startIndex = Math.max(0, courses.findIndex((course) => course.id === primaryId));
      detail.open(courses, startIndex, relation);
    };
    view.body.addEventListener('click', onBodyClick);

    /* ── 左右滑动切周 ───────────────────────────────────────────────────── */

    let tracking = false;
    let axis = null;
    let startX = 0;
    let startY = 0;

    const onTouchStart = (event) => {
      const touch = event.touches[0];
      if (!touch) return;
      tracking = true;
      axis = null;
      startX = touch.clientX;
      startY = touch.clientY;
    };

    const onTouchMove = (event) => {
      if (!tracking) return;
      const touch = event.touches[0];
      if (!touch) return;
      const dx = touch.clientX - startX;
      const dy = touch.clientY - startY;
      // 位移还太小就先不定主轴，避免手抖把一次竖滑判成横滑
      if (!axis) {
        if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return;
        axis = Math.abs(dx) > Math.abs(dy) * AXIS_RATIO ? 'x' : 'y';
      }
      if (axis !== 'x') return; // 纵向一概不管：交给原生滚动（下拉刷新将来也在这条路上）
      if (Math.abs(dx) < SWIPE_THRESHOLD) return;
      tracking = false; // 一次手势只切一周
      setWeek(weekNo + (dx < 0 ? 1 : -1));
    };

    const onTouchEnd = () => {
      tracking = false;
      axis = null;
    };

    el.addEventListener('touchstart', onTouchStart, { passive: true });
    el.addEventListener('touchmove', onTouchMove, { passive: true });
    el.addEventListener('touchend', onTouchEnd);
    el.addEventListener('touchcancel', onTouchEnd);

    /* ── 订阅 + 首次取数 ────────────────────────────────────────────────── */

    const unsubscribe = store.subscribe(
      (state) => state.slots.timetable,
      () => paint()
    );
    paint();

    const slot = store.getState().slots.timetable;
    if (!slot || slot.status === 'idle') actions.loadSlot('timetable');

    return () => {
      unsubscribe();
      window.clearInterval(nowTimer);
      document.removeEventListener('visibilitychange', onVisible);
      view.prevBtn.removeEventListener('click', onPrev);
      view.nextBtn.removeEventListener('click', onNext);
      view.pickBtn.removeEventListener('click', onPickOpen);
      view.fab.removeEventListener('click', onFab);
      view.body.removeEventListener('click', onBodyClick);
      picker.el.removeEventListener('click', onPickClick);
      el.removeEventListener('touchstart', onTouchStart);
      el.removeEventListener('touchmove', onTouchMove);
      el.removeEventListener('touchend', onTouchEnd);
      el.removeEventListener('touchcancel', onTouchEnd);
      detail.destroy();
      picker.destroy();
    };
  },

  unmount() {
    /* 全部监听与弹层在 cleanup 里收尾 */
  },
};
