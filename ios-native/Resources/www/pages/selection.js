/**
 * selection.js —— 选课页（P09）。生命周期照 grades.js：render 出骨架、mount 订阅取数、cleanup 解绑。
 *   · 课池走 jwxtSelection 4 步链；降级时顶部来源条如实标注；轮次闸门与乐观更新在 actions.toggleSelection()。
 *   · 冲突只判时间（星期+节次+周次），基准 = timetable 已选 + 本轮已选。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { toast } from '../core/toast.js';
import { createChipGroup } from '../components/chip.js';
import { progressBar } from '../components/card.js';
import { COURSE_TYPE_LABEL } from '../components/courseCard.js';
import { emptyState } from '../components/emptyState.js';
import { skeletonList } from '../components/skeleton.js';
import { conflictsWith, stockLevel, filterCourses, selectionStats, timeTextOf } from './selectionUtils.js';

/** 来源说明原文（降级时必须说清楚）与倒计时重算间隔（只改文字不重建 DOM） */
const SOURCE_NOTE = '课程池与余量以教务系统自主选课数据为准';
const TICK_MS = 60000;
/** 余量三档 → 中文标签 / 进度条语义色 / 卡片修饰类 */
const STOCK_LABEL = { full: '已满', tight: '紧张', enough: '充足' };
const STOCK_TONE = { full: 'danger', tight: 'accent', enough: 'success' };
/** 轮次状态 → 文案 / 修饰类 */
const ROUND_STATUS = { open: ['开放中', 'is-open'], pending: ['未开始', 'is-pending'], closed: ['已结束', 'is-closed'] };
/** 课程类型 chip 的固定顺序（没有数据的类型不摆出来） */
const TYPE_ORDER = ['required', 'elective', 'public', 'practice'];

/** ISO → `11-02 08:00`。只切字符串不 new Date：轮次带 +08:00，用系统时区解析会飘 8 小时。 */
function windowText(iso) {
  const text = String(iso || '');
  return text ? text.slice(5, 16).replace('T', ' ') : '—';
}

/** 倒计时文案：60s 粒度，只到「天 / 小时 / 分」，不写秒。 */
function remainText(round) {
  if (!round) return '';
  const target = Date.parse(round.status === 'pending' ? round.openAt : round.closeAt);
  // 教务页面解析不出时间窗时保持沉默，宁可不显示也不谎报「已结束」
  if (!Number.isFinite(target)) return '';
  const diff = target - Date.now();
  if (!Number.isFinite(diff) || diff <= 0) return round.status === 'open' ? '本轮已结束' : '本轮未开放';
  const days = Math.floor(diff / 86400000);
  const hours = Math.floor(diff / 3600000) % 24;
  const minutes = Math.floor(diff / 60000) % 60;
  const span = days > 0 ? `${days} 天 ${hours} 小时` : hours > 0 ? `${hours} 小时 ${minutes} 分` : `${minutes} 分钟`;
  return `${round.status === 'pending' ? '距开始' : '距结束'} ${span}`;
}
export default {
  name: 'selection',
  title: '选课',
  requiresAuth: true,

  /** 同步出骨架：来源条 + 轮次卡 + 三项统计 + 搜索 + 筛选 + 骨架列表。 */
  render(ctx) {
    const state = ctx.store.getState();
    const filter = state.ui.selectionFilter || { type: 'all', dept: 'all', onlyAvailable: false };

    const source = h('p', { className: 'p-sel__source', role: 'note' });
    const sourceRetry = h('button', { className: 'c-btn c-btn--ghost p-sel__source-retry', type: 'button', text: '重试同步', hidden: true });

    const roundLabel = h('h2', { className: 'p-sel__round-label', text: '正在读取选课轮次…' });
    const roundStatus = h('span', { className: 'p-sel__round-status', hidden: true });
    const roundWindow = h('p', { className: 'p-sel__round-window' });
    const roundMax = h('p', { className: 'p-sel__round-max' });
    const roundRemain = h('p', { className: 'p-sel__round-remain' });
    const round = h('section', { className: 'p-sel__round' }, [
      h('div', { className: 'p-sel__round-head' }, [roundLabel, roundStatus]),
      roundWindow, roundMax, roundRemain,
    ]);

    const stats = h('div', { className: 'p-sel__stats' });

    const query = h('input', {
      className: 'p-sel__search-input', type: 'search', inputmode: 'search', autocomplete: 'off',
      placeholder: '搜索课名 / 教师 / 课号', 'aria-label': '搜索课程名、教师或课号',
      value: '',
    });
    const search = h('div', { className: 'p-sel__search' }, [
      h('span', { className: 'p-sel__search-icon', 'aria-hidden': 'true', html: icon('search', 18) }),
      query,
    ]);

    const chipsHost = h('div', { className: 'p-sel__chips' });
    const onlyAvailable = h('input', { className: 'p-sel__switch-input', type: 'checkbox', checked: Boolean(filter.onlyAvailable) });
    const onlyConflict = h('input', { className: 'p-sel__switch-input', type: 'checkbox' });
    const switchTrack = () => h('span', { className: 'p-sel__switch-track', 'aria-hidden': 'true' }, h('span', { className: 'p-sel__switch-thumb' }));
    const switchRow = h('div', { className: 'p-sel__switches' }, [
      h('label', { className: 'p-sel__switch' }, [onlyAvailable, switchTrack(), h('span', { className: 'p-sel__switch-label', text: '只看有余量' })]),
      h('label', { className: 'p-sel__switch' }, [onlyConflict, switchTrack(), h('span', { className: 'p-sel__switch-label', text: '只看不冲突' })]),
    ]);
    const filters = h('div', { className: 'p-sel__filters' }, [chipsHost, switchRow]);

    const hint = h('p', { className: 'p-sel__hint', hidden: true });
    const list = h('div', { className: 'p-sel__list' }, skeletonList({ rows: 5, withTrailing: true }));

    const el = h('div', { className: 'page p-sel' }, [source, sourceRetry, round, stats, search, filters, hint, list]);
    el.__view = {
      source, sourceRetry, round, roundLabel, roundStatus, roundWindow, roundMax, roundRemain,
      stats, query, chipsHost, onlyAvailable, onlyConflict, hint, list,
    };
    return el;
  },

  /** @returns {() => void} cleanup */
  mount(el, ctx) {
    const view = el.__view;
    const { store, actions } = ctx;

    /** 筛选条件（onlyNoConflict 只在本页生效，不写进 ui.selectionFilter 以免改 state 形状） */
    let filter = {
      type: 'all', dept: 'all', onlyAvailable: false, onlyNoConflict: false,
      ...(store.getState().ui.selectionFilter || {}),
    };
    let keyword = '';
    /** 数据是否已到位：骨架态的倒计时 tick 不能把占位文字覆盖成「无开放轮次」 */
    let ready = false;
    /** @type {object[]} */
    let courses = [];
    /** @type {object|null} */
    let round = null;
    let chips = null;
    let chipsSignature = '';

    /* ── 绘制 ───────────────────────────────────────────────────────────── */

    /** 来源说明：演示态 / 降级态绝不装成实时数据。 */
    function paintSource(slot) {
      const isDemo = store.getState().session.status === 'demo';
      const stale = Boolean((slot && (slot.degraded || slot.fromCache || slot.status === 'stale')) || isDemo);
      const suffix = isDemo
        ? '当前为演示数据，登录后展示教务实时抓取结果'
        : stale ? '教务暂不可用，当前显示的是上次同步的副本' : '';
      view.source.textContent = suffix ? `${SOURCE_NOTE} · ${suffix}` : SOURCE_NOTE;
      view.source.classList.toggle('is-stale', Boolean(suffix));
      // 只有「remote 失败落到的降级副本」给重试；演示态是显式选择，不给按钮
      view.sourceRetry.hidden = isDemo || !stale;
    }

    /** 轮次卡。倒计时由定时器单独调本函数，只改文字不重建节点。 */
    function paintRound() {
      if (!ready) return;
      if (!round) {
        view.round.hidden = false;
        view.roundLabel.textContent = '当前无开放选课轮次';
        view.roundStatus.hidden = true;
        view.roundWindow.textContent = '下次开放时间以教务通知为准';
        view.roundMax.textContent = '';
        view.roundRemain.textContent = '';
        return;
      }
      const [statusText, statusClass] = ROUND_STATUS[round.status] || ROUND_STATUS.closed;
      const max = Number(round.maxCourses) || 0;
      view.round.hidden = false;
      view.roundLabel.textContent = round.label || '当前选课轮次';
      view.roundStatus.hidden = false;
      view.roundStatus.className = `p-sel__round-status ${statusClass}`;
      view.roundStatus.textContent = statusText;
      // 时间窗缺失时优先展示教务原文提示（iskxk=0 = 当前不属于选课阶段），否则留空收起
      view.roundWindow.textContent = round.note || (round.openAt || round.closeAt
        ? `${windowText(round.openAt)} — ${windowText(round.closeAt)}` : '');
      view.roundMax.textContent = max > 0 ? `本轮最多选 ${max} 门` : '';
      view.roundRemain.textContent = remainText(round);
    }

    /** 三项统计：已选门数 / 已选学分 / 课程池总学分。 */
    function paintStats() {
      if (!courses.length) {
        view.stats.replaceChildren();
        return;
      }
      const { picked, credits, totalCredits } = selectionStats(courses);
      const cell = (label, value) => h('div', { className: 'p-sel__stat' }, [
        h('span', { className: 'p-sel__stat-value', text: String(value) }),
        h('span', { className: 'p-sel__stat-label', text: label }),
      ]);
      view.stats.replaceChildren(cell('已选门数', picked), cell('已选学分', credits), cell('课程池总学分', totalCredits));
    }

    /** 类型 chip 从数据里现取：没有该类型的课程就不摆出来（照 grades.js 的做法）。 */
    function paintChips() {
      const present = TYPE_ORDER.filter((type) => courses.some((course) => course.courseType === type));
      const items = [{ id: 'all', label: '全部' }, ...present.map((type) => ({ id: type, label: COURSE_TYPE_LABEL[type] }))];
      const signature = items.map((item) => item.id).join('|');
      if (signature !== chipsSignature) {
        const next = createChipGroup({
          items, active: filter.type, ariaLabel: '按课程类型筛选',
          onChange: (id) => applyFilter({ type: id }),
        });
        if (chips) {
          // 先换新节点再 destroy 旧的：destroy() 会 remove 自己的 el，反过来会让 replaceWith 落空
          chips.el.replaceWith(next.el);
          chips.destroy();
        } else {
          view.chipsHost.replaceChildren(next.el);
        }
        chips = next;
        chipsSignature = signature;
      } else {
        chips.setActive(filter.type, true);
      }
    }

    /** 计算冲突集合：基准 = 已选课表 + 本轮已选，排除自身。 */
    function conflictIdsOf(picked) {
      const base = (store.getState().slots.timetable.data || {}).courses || [];
      const ids = new Set();
      for (const course of courses) {
        const others = [...base, ...picked.filter((item) => item.id !== course.id)];
        if (conflictsWith(course, others)) ids.add(course.id);
      }
      return ids;
    }

    /** 单门课程卡。 */
    function courseRow(course, pickedIds, conflictIds) {
      const level = stockLevel(course);
      const left = Math.max(0, Number(course.capacity) - Number(course.enrolled));
      const isPicked = pickedIds.has(course.id);
      const conflict = conflictIds.has(course.id);
      return h('article', {
        className: `p-sel__c${conflict ? ' is-conflict' : ''}${isPicked ? ' is-picked' : ''}`,
        dataset: { id: course.id },
      }, [
        h('div', { className: 'p-sel__c-main' }, [
          h('div', { className: 'p-sel__c-head' }, [
            h('h3', { className: 'p-sel__c-name', text: course.name || '未命名课程' }),
            h('span', { className: 'p-sel__tag p-sel__tag--type', text: COURSE_TYPE_LABEL[course.courseType] || '课程' }),
            conflict ? h('span', { className: 'p-sel__tag p-sel__tag--conflict', text: '冲突' }) : null,
          ]),
          h('p', { className: 'p-sel__c-meta', text: `${course.teacher || '—'} · ${course.credits} 学分` }),
          h('p', { className: 'p-sel__c-place', text: `${timeTextOf(course)} · ${course.location || '地点待定'}` }),
          h('div', { className: 'p-sel__c-stock' }, [
            h('span', { className: `p-sel__tag p-sel__tag--stock is-${level}`, text: STOCK_LABEL[level] }),
            h('span', { className: 'p-sel__c-left', text: `余 ${left} / ${course.capacity}` }),
          ]),
          progressBar({ value: course.enrolled, max: course.capacity, tone: STOCK_TONE[level], label: `${course.name} 选课余量` }),
        ]),
        h('button', {
          className: `p-sel__pick${isPicked ? ' is-picked' : ''}`,
          type: 'button', dataset: { pick: course.id },
          'aria-pressed': String(isPicked),
          'aria-label': `${isPicked ? '退选' : '加选'} ${course.name || ''}`,
          text: isPicked ? '已选' : '加选',
        }),
      ]);
    }

    /** 列表（含筛选后的空态）。 */
    function paintList() {
      const picked = courses.filter((course) => course.isSelected);
      const pickedIds = new Set(picked.map((course) => course.id));
      const conflictIds = conflictIdsOf(picked);
      const rows = filterCourses(courses, { ...filter, q: keyword, conflicting: [...conflictIds] });

      view.hint.hidden = false;
      view.hint.textContent = `显示 ${rows.length} / ${courses.length} 门`;
      if (!round) {
        view.list.replaceChildren(emptyState({
          kind: 'empty', title: '当前无开放选课轮次', description: '下次开放时间以教务通知为准',
        }));
        return;
      }
      if (rows.length === 0) {
        const filtered = Boolean(keyword || filter.type !== 'all' || filter.onlyAvailable || filter.onlyNoConflict);
        const closed = round.status !== 'open';
        view.list.replaceChildren(emptyState({
          kind: 'empty',
          title: filtered ? '没有匹配的课程' : closed ? '当前不在选课阶段' : '本轮暂无可选课程',
          description: filtered
            ? '换个关键字，或关掉筛选条件再看看'
            : closed ? (round.note || '开放时间以教务通知为准') : '本轮暂无课程，可稍后刷新同步',
        }));
        return;
      }
      view.list.replaceChildren(...rows.map((course) => courseRow(course, pickedIds, conflictIds)));
    }

    /** 四态主绘制（骨架 / 错误 / 无数据 / 有数据）。 */
    function paint() {
      const slot = store.getState().slots.selection;
      paintSource(slot);
      if (!slot || slot.status === 'idle' || slot.status === 'loading') {
        ready = false;
        view.round.hidden = false;
        view.roundLabel.textContent = '正在读取选课轮次…';
        view.roundStatus.hidden = true;
        view.roundWindow.textContent = '';
        view.roundMax.textContent = '';
        view.roundRemain.textContent = '';
        view.stats.replaceChildren();
        view.hint.hidden = true;
        view.list.replaceChildren(skeletonList({ rows: 5, withTrailing: true }));
        return;
      }
      if (!slot.data) {
        ready = false;
        round = null;
        courses = [];
        view.round.hidden = true;
        view.stats.replaceChildren();
        view.hint.hidden = true;
        view.list.replaceChildren(emptyState({
          kind: 'error',
          title: (slot.error && slot.error.message) || '选课数据没能取回来',
          description: '教务高峰期响应较慢，稍后重试即可',
          actionLabel: '重试',
          onAction: () => actions.loadSlot('selection', { force: true }),
        }));
        return;
      }
      const data = slot.data;
      ready = true;
      courses = Array.isArray(data.courses) ? data.courses : [];
      round = data.round || null;
      view.round.hidden = false;
      paintRound();
      paintStats();
      paintChips();
      paintList();
    }

    /* ── 交互 ───────────────────────────────────────────────────────────── */

    /** 改筛选：写回 ui.selectionFilter 的既有字段，只重画列表。 */
    function applyFilter(patch) {
      filter = { ...filter, ...patch };
      actions.setUi({
        selectionFilter: { type: filter.type, dept: filter.dept, onlyAvailable: filter.onlyAvailable },
      });
      paintList();
    }

    const onSearch = () => {
      keyword = String(view.query.value || '').trim().toLowerCase();
      paintList();
    };
    view.query.addEventListener('input', onSearch);

    const onAvailable = () => applyFilter({ onlyAvailable: view.onlyAvailable.checked });
    const onConflictFree = () => applyFilter({ onlyNoConflict: view.onlyConflict.checked });
    view.onlyAvailable.addEventListener('change', onAvailable);
    view.onlyConflict.addEventListener('change', onConflictFree);

    /** 选 / 退：只调 action，失败把 action 给的错误文案弹出来。 */
    const onListClick = async (event) => {
      if (!(event.target instanceof Element)) return;
      const button = event.target.closest('[data-pick]');
      if (!button || !view.list.contains(button)) return;
      const id = button.dataset.pick;
      const course = courses.find((item) => item.id === id);
      const wasPicked = Boolean(course && course.isSelected);
      const result = await actions.toggleSelection(id);
      if (!result) return;
      if (!result.ok) {
        toast((result.error && result.error.message) || '操作失败，请重试', 'warning');
        return;
      }
      toast(`${wasPicked ? '已退选' : '已加选'} ${course ? course.name : ''}`.trim(), 'success');
    };
    view.list.addEventListener('click', onListClick);

    /* ── 订阅 + 首次取数 ────────────────────────────────────────────────── */

    const unsubscribeSelection = store.subscribe((state) => state.slots.selection, () => paint());
    // 冲突基准来自已选课表：课表数据到位后要重算角标（失败时退化为只比本轮已选）
    const unsubscribeTimetable = store.subscribe(
      (state) => state.slots.timetable.data,
      () => { if (store.getState().slots.selection.data) paintList(); }
    );
    const tickTimer = window.setInterval(paintRound, TICK_MS);
    const onSourceRetry = () => actions.loadSlot('selection', { force: true });
    view.sourceRetry.addEventListener('click', onSourceRetry);

    paint();
    const selectionSlot = store.getState().slots.selection;
    if (!selectionSlot || selectionSlot.status === 'idle') actions.loadSlot('selection');
    const timetableSlot = store.getState().slots.timetable;
    if (!timetableSlot || timetableSlot.status === 'idle') actions.loadSlot('timetable');

    return () => {
      unsubscribeSelection();
      unsubscribeTimetable();
      window.clearInterval(tickTimer);
      view.sourceRetry.removeEventListener('click', onSourceRetry);
      view.query.removeEventListener('input', onSearch);
      view.onlyAvailable.removeEventListener('change', onAvailable);
      view.onlyConflict.removeEventListener('change', onConflictFree);
      view.list.removeEventListener('click', onListClick);
      if (chips) chips.destroy();
    };
  },

  unmount() { /* 订阅、事件与定时器都在 cleanup 里收尾 */ },
};
