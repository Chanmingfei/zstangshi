/**
 * recommend.js —— 推荐课表（P08）
 *
 * 【页面形态仿教务原页】「信息查询 → 推荐课表打印」= 筛选下拉（年级/专业/班级）
 * +「查询」按钮 + 周课表表格。本页据此布局：顶部级联筛选栏，下面复用
 * timetableView.buildGrid 的 12 节周课表网格与周次切换器。
 *
 * 数据链（jwxtRecommend.js，PC 实测铁证 docs/diagnostics/bjkbdy-*）：进页只拉级联
 * 字典（contextOnly）；选年级/院系翻页拉班级行联动专业/班级下拉；点「查询」才抓课表。
 *
 * 诚实边界：学期不可查看 / 班级无课都按教务原文如实展示；降级演示数据时
 * 隐藏筛选栏并标注「当前为演示数据」，不假装能级联查询。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { stagger } from '../core/animate.js';
import { createSheet } from '../components/sheet.js';
import { emptyState } from '../components/emptyState.js';
import { skeletonList } from '../components/skeleton.js';
import { currentSemester, weekDates, coursesInCell } from './timetableUtils.js';
import { buildGrid } from './timetableView.js';
import { createCourseDetail } from './timetableDetail.js';

const SWIPE_THRESHOLD = 48;
const AXIS_RATIO = 1.2;

/** 用 options 填一个 select；value 空串为首项（「全部/请选择」语义） */
function fillSelect(select, options, placeholder, picked) {
  select.replaceChildren(
    h('option', { value: '', text: placeholder }),
    ...options.map((o) => h('option', { value: o.id, text: o.name })),
  );
  select.value = picked && options.some((o) => o.id === picked) ? picked : '';
}

export default {
  name: 'recommend',
  title: '推荐课表',
  requiresAuth: true,

  /** 同步出骨架；数据在 mount 里填。与个人课表共用 ui.weekNo，切页后周次不丢。 */
  render() {
    const weekText = h('span', { className: 'p-tt__week-text', text: '第 1 周' });
    const prevBtn = h('button', {
      className: 'p-tt__nav', type: 'button', 'aria-label': '上一周', html: icon('back', 20),
    });
    const nextBtn = h('button', {
      className: 'p-tt__nav', type: 'button', 'aria-label': '下一周', html: icon('forward', 20),
    });
    const pickBtn = h('button', {
      className: 'p-tt__week-pick', type: 'button', 'aria-haspopup': 'dialog', 'aria-label': '选择周次',
    }, [weekText, h('span', {
      className: 'p-tt__week-caret', 'aria-hidden': 'true', html: icon('chevronDown', 16),
    })]);

    const source = h('p', { className: 'p-rec__source', hidden: true });
    const gradeSel = h('select', { className: 'p-rec__select', 'aria-label': '年级' });
    const deptSel = h('select', { className: 'p-rec__select', 'aria-label': '院系' });
    const majorSel = h('select', { className: 'p-rec__select', 'aria-label': '专业' });
    const classSel = h('select', { className: 'p-rec__select p-rec__select--class', 'aria-label': '班级' });
    const queryBtn = h('button', { className: 'p-rec__query', type: 'button', text: '查询' });
    const filterBar = h('div', { className: 'p-rec__filter', hidden: true }, [
      gradeSel, deptSel, majorSel, classSel, queryBtn,
    ]);
    const body = h('div', { className: 'p-tt__body' }, skeletonList({ rows: 4, withTrailing: false }));
    const fab = h('button', { className: 'p-tt__fab', type: 'button', hidden: true }, [
      h('span', { className: 'p-tt__fab-icon', 'aria-hidden': 'true', html: icon('target', 16) }),
      h('span', { text: '回到本周' }),
    ]);

    const el = h('div', { className: 'page p-tt p-tt--recommend' }, [
      source,
      filterBar,
      h('div', { className: 'p-tt__head' }, [h('div', { className: 'p-tt__switch' }, [prevBtn, pickBtn, nextBtn])]),
      body,
      h('div', { className: 'p-tt__fab-wrap' }, fab),
    ]);
    el.__view = { weekText, prevBtn, nextBtn, pickBtn, source, filterBar, gradeSel, deptSel, majorSel, classSel, queryBtn, body, fab };
    return el;
  },

  /** @returns {() => void} cleanup */
  mount(el, ctx) {
    const view = el.__view;
    const { store, actions, provider } = ctx;
    const semester = currentSemester(store.getState());
    // 推荐课表不标考试周：教务侧数据无法区分，个人课表（timetable.js）才用
    const totalWeeks = (semester && Number(semester.totalWeeks)) || 19;
    const currentWeek = Number(store.getState().term.context && store.getState().term.context.week) || 1;

    let weekNo = Math.min(totalWeeks, Math.max(1, Number(store.getState().ui.weekNo) || currentWeek));
    /** 冲突格里用户选过「显示这门课」的课程，key = `day-start` */
    const shown = {};
    /** 级联选择（gradeId/departmentId/majorId/classId）与当前筛选的班级行 */
    const sel = { gradeId: '', departmentId: '', majorId: '', classId: '' };
    let gradeItems = [];
    /** 「查询」结果覆盖层：{data} | {error}；null = 展示 slot（默认自己班） */
    let local = null;
    let busy = false;
    let filterReady = false;

    const detail = createCourseDetail({
      onShowCourse(course) {
        shown[`${course.dayOfWeek}-${Math.min(...course.sections)}`] = course.id;
        paint(false);
      },
    });
    const picker = createSheet({ title: '选择周次' });

    function updateHead() {
      view.weekText.textContent = `第 ${weekNo} 周`;
      view.prevBtn.disabled = weekNo <= 1;
      view.nextBtn.disabled = weekNo >= totalWeeks;
      view.fab.hidden = weekNo === currentWeek;
      view.pickBtn.setAttribute('aria-label', `选择周次，当前第 ${weekNo} 周`);
    }

    /** 来源说明：教务原文 + 降级提示。降级绝不能装成实时数据。 */
    function paintSource(data) {
      if (!data) { view.source.hidden = true; return; }
      const isDemo = store.getState().session.status === 'demo';
      const base = data.sourceNote || '数据来自教务系统「信息查询 → 推荐课表打印」';
      view.source.textContent = isDemo
        ? `${base} · 当前为演示数据，登录后展示教务实时抓取结果`
        : base;
      view.source.classList.toggle('is-stale', isDemo);
      view.source.hidden = false;
    }

    /* ── 级联筛选栏 ─────────────────────────────────────────────────────── */
    function fillMajorsAndClasses() {
      const majors = [...new Map(gradeItems.filter((r) => r.majorId)
        .map((r) => [r.majorId, { id: r.majorId, name: r.majorName || r.majorId }])).values()];
      if (sel.majorId && !majors.some((m) => m.id === sel.majorId)) sel.majorId = '';
      fillSelect(view.majorSel, majors, '全部专业', sel.majorId);
      const classes = gradeItems.filter((r) => r.classId && (!sel.majorId || r.majorId === sel.majorId));
      fillSelect(view.classSel, classes.map((r) => ({ id: r.classId, name: r.className })), '请选择班级', sel.classId);
      view.classSel.disabled = classes.length === 0;
    }

    /** 字典到了：铺年级/院系下拉；班级行等用户选了年级/院系再拉 */
    function initFilter(data) {
      filterReady = true;
      gradeItems = Array.isArray(data.classList) ? data.classList : [];
      sel.gradeId = '';
      fillSelect(view.gradeSel, (data.dictionaries && data.dictionaries.grades) || [], '全部年级', sel.gradeId);
      fillSelect(view.deptSel, (data.dictionaries && data.dictionaries.departments) || [], '全部院系', sel.departmentId);
      fillMajorsAndClasses();
      view.filterBar.hidden = false;
    }

    /** 换年级/院系：拉对应班级行，联动专业/班级下拉（课表展示不动） */
    let loadSeq = 0;
    async function loadGrade(gradeId, departmentId = sel.departmentId) {
      const seq = ++loadSeq;
      busy = true;
      view.majorSel.replaceChildren();
      view.classSel.replaceChildren(h('option', { value: '', text: '加载中…' }));
      view.queryBtn.disabled = true;
      const res = await provider.getRecommendedTimetable({ gradeId, departmentId });
      if (seq !== loadSeq) return; // 用户又改了筛选：过期响应直接丢弃，防旧数据覆盖新下拉
      busy = false;
      // 降级演示数据绝不进级联（班级清单是真实教务口径，演示行只会给错专业）
      const usable = res.ok && !(res.meta && res.meta.degraded);
      if (usable) { gradeItems = res.data.classList || []; sel.classId = ''; fillMajorsAndClasses(); }
      else view.classSel.replaceChildren(h('option', { value: '', text: '清单获取失败' }));
      paint(false);
    }

    async function runQuery() {
      if (!sel.classId) {
        // 选项填写不全：按钮不再禁用，点了就明确告诉用户缺什么
        local = null;
        view.body.replaceChildren(emptyState({
          kind: 'empty', title: '还没选完',
          description: '请把年级 / 院系 / 专业 / 班级选好（至少选到具体班级），再点「查询」',
        }));
        return;
      }
      busy = true;
      paint(false);
      const res = await provider.getRecommendedTimetable({
        gradeId: sel.gradeId, departmentId: sel.departmentId,
        majorId: sel.majorId, classId: sel.classId,
      });
      local = res.ok ? { data: res.data } : { error: res.error };
      busy = false;
      paint();
    }

    function paint(animate = true) {
      const active = local && local.data ? local.data : null;
      updateHead();
      view.queryBtn.disabled = busy;
      if (active && active.dictionaries && !filterReady) initFilter(active);
      paintSource(active);

      if (busy) {
        view.body.replaceChildren(skeletonList({ rows: 4, withTrailing: false }));
        return;
      }
      if (local && local.error) {
        view.body.replaceChildren(emptyState({
          kind: 'error',
          title: local.error.message || '该班课表没能取回来',
          description: '可换一个班级重试',
          actionLabel: '重试',
          onAction: () => runQuery(),
        }));
        return;
      }
      if (!active) {
        // 查询前不显示任何课表（2026-11-06 口径：取消默认课表）
        view.body.replaceChildren(emptyState({
          kind: 'empty', title: '查询推荐课表',
          description: '选择年级 / 院系 / 专业 / 班级后点「查询」查看该班推荐课表',
        }));
        return;
      }
      const courses = active.courses || [];
      if (active.classId && courses.length === 0) {
        view.body.replaceChildren(emptyState({
          kind: 'empty', title: '该班本学期没有排课',
          description: `「${active.className || ''}」在当前学期无推荐课表记录`,
        }));
        return;
      }
      const { grid, cards } = buildGrid(courses, {
        weekNo,
        // 推荐课表不做「今天/进行中」高亮：它是建议修读方案，不是真实上课日程
        todayIndex: 0,
        dates: weekDates(semester, weekNo),
        nowSection: 0,
        shown,
      });
      view.body.replaceChildren(grid);
      if (animate) stagger(cards, { step: 40 });
    }

    function setWeek(next) {
      const clamped = Math.min(totalWeeks, Math.max(1, next));
      if (clamped === weekNo) return;
      weekNo = clamped;
      actions.setUi({ weekNo: clamped });
      paint();
    }

    /* ── 周次切换器 ─────────────────────────────────────────────────────── */
    const onPrev = () => setWeek(weekNo - 1);
    const onNext = () => setWeek(weekNo + 1);
    view.prevBtn.addEventListener('click', onPrev);
    view.nextBtn.addEventListener('click', onNext);

    const onPickOpen = async () => {
      const cells = [];
      for (let w = 1; w <= totalWeeks; w += 1) {
        const cls = ['p-tt__week-cell'];
        if (w === weekNo) cls.push('is-current');
        cells.push(h('button', {
          className: cls.join(' '), type: 'button', dataset: { week: String(w) },
          'aria-label': `第 ${w} 周`, text: String(w),
        }));
      }
      picker.setContent(h('div', { className: 'p-tt__weeks' }, cells));
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

    const onFab = () => { if (weekNo !== currentWeek) setWeek(currentWeek); };
    view.fab.addEventListener('click', onFab);

    /* ── 级联交互 ───────────────────────────────────────────────────────── */
    /** 全校清单翻页上限 500 行装不下 2198 个班：年级院系都为空就不拉，本地清空 */
    const emptyScope = () => !sel.gradeId && !sel.departmentId;

    const onGradeChange = () => {
      sel.gradeId = view.gradeSel.value;
      sel.majorId = '';
      sel.classId = '';
      if (emptyScope()) { gradeItems = []; fillMajorsAndClasses(); return; }
      loadGrade(sel.gradeId, sel.departmentId);
    };
    const onDeptChange = () => {
      sel.departmentId = view.deptSel.value;
      sel.majorId = '';
      sel.classId = '';
      if (emptyScope()) { gradeItems = []; fillMajorsAndClasses(); return; }
      loadGrade(sel.gradeId, sel.departmentId);
    };
    const onMajorChange = () => { sel.majorId = view.majorSel.value; sel.classId = ''; fillMajorsAndClasses(); };
    const onClassChange = () => { sel.classId = view.classSel.value; view.queryBtn.disabled = !sel.classId; };
    view.gradeSel.addEventListener('change', onGradeChange);
    view.deptSel.addEventListener('change', onDeptChange);
    view.majorSel.addEventListener('change', onMajorChange);
    view.classSel.addEventListener('change', onClassChange);
    view.queryBtn.addEventListener('click', runQuery);

    /* ── 卡片 → 详情层 ──────────────────────────────────────────────────── */
    const onBodyClick = (event) => {
      const holder = event.target.closest('.p-tt__gridcard');
      if (!holder) return;
      const source = local && local.data ? local.data : store.getState().slots.recommend.data;
      if (!source) return;
      const day = Number(holder.dataset.day);
      const start = Number(holder.dataset.start);
      const { active, rotating } = coursesInCell(source.courses, day, start, weekNo);
      const courses = active.concat(rotating);
      if (courses.length === 0) return;
      const at = Math.max(0, courses.findIndex((c) => c.id === holder.dataset.courseId));
      detail.open(courses, at);
    };
    view.body.addEventListener('click', onBodyClick);

    /* ── 左右滑切周（与个人课表同一套主轴判定）─────────────────────────── */
    let tracking = false;
    let axis = null;
    let startX = 0;
    let startY = 0;
    const onTouchStart = (event) => {
      const t = event.touches[0];
      if (!t) return;
      tracking = true; axis = null; startX = t.clientX; startY = t.clientY;
    };
    const onTouchMove = (event) => {
      if (!tracking) return;
      const t = event.touches[0];
      if (!t) return;
      const dx = t.clientX - startX;
      const dy = t.clientY - startY;
      if (!axis) {
        if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return;
        axis = Math.abs(dx) > Math.abs(dy) * AXIS_RATIO ? 'x' : 'y';
      }
      if (axis !== 'x' || Math.abs(dx) < SWIPE_THRESHOLD) return;
      tracking = false;
      setWeek(weekNo + (dx < 0 ? 1 : -1));
    };
    const onTouchEnd = () => { tracking = false; axis = null; };
    el.addEventListener('touchstart', onTouchStart, { passive: true });
    el.addEventListener('touchmove', onTouchMove, { passive: true });
    el.addEventListener('touchend', onTouchEnd);
    el.addEventListener('touchcancel', onTouchEnd);

    /* ── 订阅 + 首次取数 ────────────────────────────────────────────────── */
    const unsub = store.subscribe((s) => s.slots.recommend, () => paint());
    paint();
    // 进页只拉级联字典（轻量，不翻页），默认课表已取消 —— 用户查询才出数据
    (async () => {
      busy = true;
      paint();
      const res = await provider.getRecommendedTimetable({ contextOnly: true });
      busy = false;
      const usable = res.ok && !(res.meta && res.meta.degraded);
      if (usable) initFilter(res.data);
      else if (res.ok) view.body.replaceChildren(emptyState({
        kind: 'error', title: '级联字典获取失败', description: '教务系统暂不可达，请稍后重试',
      }));
      paint();
    })();

    return () => {
      unsub();
      view.prevBtn.removeEventListener('click', onPrev);
      view.nextBtn.removeEventListener('click', onNext);
      view.pickBtn.removeEventListener('click', onPickOpen);
      view.fab.removeEventListener('click', onFab);
      view.gradeSel.removeEventListener('change', onGradeChange);
      view.deptSel.removeEventListener('change', onDeptChange);
      view.majorSel.removeEventListener('change', onMajorChange);
      view.classSel.removeEventListener('change', onClassChange);
      view.queryBtn.removeEventListener('click', runQuery);
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

  unmount() { /* 收尾都在 cleanup */ },
};
