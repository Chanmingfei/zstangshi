/**
 * grades.js —— 成绩列表（P03）
 *
 * 三条产品底线（都踩过坑，写在最前面）：
 *   1. **不算均分、不算加权、不算 GPA**。GPA 只从教务权威页取（OFFICIAL_GPA），
 *      成绩页自己算一个就会和绩点页打架 —— 本地加权 3.07 vs 教务 3.16 的教训（R11）。
 *      这里只给三个客观计数：门数 / 已获学分 / 不及格门数。
 *   2. **未录入 ≠ 0 分 ≠ 缓考 ≠ 文字成绩**。`score: null` 只是「没有数字」，
 *      缓考是「考了但顺延」，而「良好/优秀」是**已经出分**的五级制成绩。三者都不能被当成 0
 *      参与统计，但文字成绩该算已获学分、该算不及格（判定统一在 data/gradeText.js）。
 *   3. **60 分是及格线**，压线通过不许标红；只有 < 60 才算不及格。
 *
 * 已获学分 = 该学期「分数 ≥ 60」或「文字成绩通过」的 credits 之和（未录入/缓考/不及格都不计）。
 *
 * 搜索与学期筛选都在**已取回的整份数据**上做，不做假防抖：数据量就 36 条，
 * 每次输入直接过滤比等 300ms 更跟手。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { createChipGroup } from '../components/chip.js';
import { gradeRow } from '../components/gradeRow.js';
import { emptyState } from '../components/emptyState.js';
import { skeletonList } from '../components/skeleton.js';
import { rememberShared } from '../core/shared.js';
import { syncText } from '../core/format.js';
import { gradeResultOf } from '../data/gradeText.js';

/** 「全部学期」的 chip id */
const ALL = 'all';

/**
 * 三项概览。注意统计口径：数字分 ≥ 60 或文字成绩通过（良好/优秀/及格…）才计学分，
 * 文字「不及格」计入门数；未录入 / 缓考两边都不计。
 * @param {object[]} list 已按学期筛选的记录
 * @returns {{count: number, credits: number, failed: number}}
 */
function summarize(list) {
  let credits = 0;
  let failed = 0;
  for (const grade of list) {
    const result = gradeResultOf(grade);
    if (result.kind === 'pending') continue;
    if (result.failed) failed += 1;
    else credits += Number(grade.credits) || 0;
  }
  // 学分有 .5，浮点加完可能 42.99999，保留一位
  return { count: list.length, credits: Math.round(credits * 10) / 10, failed };
}

/**
 * 学期 chip 的选项：从数据里现取（不去问校历），保证「有数据的学期」才出现。
 * @param {object[]} list
 * @returns {Array<{id: string, label: string}>}
 */
function semesterOptions(list) {
  const ids = [...new Set(list.map((g) => g.semesterId))].sort();
  return [{ id: ALL, label: '全部' }, ...ids.map((id) => ({ id, label: id }))];
}

/**
 * 模糊匹配课程名 / 课程号。
 * @param {object} grade
 * @param {string} query 已 trim + lowercase
 * @returns {boolean}
 */
function matches(grade, query) {
  if (!query) return true;
  const name = String(grade.courseName || '').toLowerCase();
  const code = String(grade.courseCode || '').toLowerCase();
  return name.includes(query) || code.includes(query);
}

export default {
  name: 'grades',
  title: '成绩',
  tab: 'grades',
  requiresAuth: true,

  /**
   * 同步出骨架：概览三格 + 学期 chip + 搜索框 + 骨架列表。
   * @param {object} ctx
   * @returns {HTMLElement}
   */
  render(ctx) {
    const state = ctx.store.getState();
    const overview = h('div', { className: 'p-grades__overview' });
    const syncLine = h('p', { className: 'p-grades__sync', hidden: true });

    const query = h('input', {
      className: 'p-grades__search-input',
      type: 'search',
      inputmode: 'search',
      autocomplete: 'off',
      placeholder: '搜索课程名 / 课程号',
      'aria-label': '搜索课程名或课程号',
      value: state.ui.gradesQuery || '',
    });

    const search = h('div', { className: 'p-grades__search' }, [
      h('span', { className: 'p-grades__search-icon', 'aria-hidden': 'true', html: icon('search', 18) }),
      query,
    ]);

    const chips = createChipGroup({
      items: [{ id: ALL, label: '全部' }],
      active: state.ui.semesterFilter || ALL,
      ariaLabel: '按学期筛选成绩',
    });

    const list = h('div', { className: 'p-grades__list' }, skeletonList({ rows: 5, withTrailing: false }));

    const el = h('div', { className: 'page p-grades' }, [
      overview,
      chips.el,
      search,
      syncLine,
      list,
    ]);

    el.__view = { overview, chips, query, list, syncLine };
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

    /** 当前筛选（学期）；搜索词单独存在 input 上，不落 store 以免每敲一个字母就整页重绘 */
    let semester = store.getState().ui.semesterFilter || ALL;
    let keyword = '';
    /** chip 选项签名：变了才重建 chipbar */
    let chipsSignature = '';
    /** @type {object[]} */
    let all = [];

    function paintOverview(list) {
      const { count, credits, failed } = summarize(list);
      const cell = (label, value, tone) =>
        h('div', { className: 'p-grades__stat' }, [
          h('span', { className: `p-grades__stat-value${tone ? ` is-${tone}` : ''}`, text: String(value) }),
          h('span', { className: 'p-grades__stat-label', text: label }),
        ]);
      view.overview.replaceChildren(
        cell('门数', count),
        cell('已获学分', credits),
        cell('不及格', failed, failed > 0 ? 'fail' : '')
      );
    }

    /** 学期筛选后的全集（概览永远按它算，不受搜索影响） */
    function semesterList() {
      return semester === ALL ? all : all.filter((g) => g.semesterId === semester);
    }

    function paintList(animate) {
      const rows = semesterList().filter((g) => matches(g, keyword));
      if (rows.length === 0) {
        view.list.replaceChildren(
          emptyState({
            kind: 'empty',
            title: keyword ? '没有匹配的成绩' : '这个学期还没有成绩',
            description: keyword ? '换个课程名或课程号试试' : '成绩公布后会出现在这里',
          })
        );
        return;
      }
      view.list.replaceChildren(
        ...rows.map((grade) => gradeRow(grade))
      );
      if (animate && typeof view.list.animate === 'function') {
        // 切学期时整体淡入 + 8px 位移（PRD P03），只动 transform / opacity
        view.list.animate(
          [
            { opacity: 0, transform: 'translate3d(0, 8px, 0)' },
            { opacity: 1, transform: 'translate3d(0, 0, 0)' },
          ],
          { duration: 250, easing: 'cubic-bezier(0.2, 0, 0, 1)' }
        );
      }
    }

    function paintAll(animate) {
      paintOverview(semesterList());
      paintList(animate);
    }

    /** 主绘制：四态（骨架 / 错误 / 空 / 有数据） */
    function paint() {
      const slot = store.getState().slots.grades;
      if (!slot || slot.status === 'idle' || slot.status === 'loading') {
        view.overview.replaceChildren();
        view.syncLine.hidden = true;
        view.list.replaceChildren(skeletonList({ rows: 5, withTrailing: false }));
        return;
      }

      if (!slot.data) {
        view.overview.replaceChildren();
        view.syncLine.hidden = true;
        view.list.replaceChildren(
          emptyState({
            kind: 'error',
            title: (slot.error && slot.error.message) || '成绩没能取回来',
            description: '可以下拉重试，或先去别的页面看看',
            actionLabel: '重试',
            onAction: () => actions.loadSlot('grades', { force: true }),
          })
        );
        return;
      }

      all = Array.isArray(slot.data) ? slot.data : [];

      // chip 选项跟着数据生成（数据里没有的学期不摆出来）。
      // 只有选项集合真的变了才重建，否则只 setActive —— 刷新一次就重建会让选中项闪一下。
      const options = semesterOptions(all);
      if (!options.some((item) => item.id === semester)) semester = ALL;
      const signature = options.map((item) => item.id).join('|');
      if (signature !== chipsSignature) {
        // 顺序要紧：先把新 chipbar 换进去，再 destroy 旧的。
        // destroy() 会把自己那个节点从文档里 remove，先 destroy 会让 replaceWith 落空（整条 chipbar 消失）。
        const previous = view.chips;
        const next = createChipGroup({
          items: options,
          active: semester,
          ariaLabel: '按学期筛选成绩',
          onChange: (id) => {
            semester = id;
            actions.setUi({ semesterFilter: id });
            paintAll(true);
          },
        });
        previous.el.replaceWith(next.el);
        previous.destroy();
        view.chips = next;
        chipsSignature = signature;
      } else {
        view.chips.setActive(semester, true);
      }

      // 缓存/降级态：让用户知道这不是刚取的
      if (slot.fromCache || slot.status === 'stale') {
        view.syncLine.hidden = false;
        view.syncLine.textContent = `上次同步 ${syncText(slot.updatedAt)}`;
      } else {
        view.syncLine.hidden = true;
      }

      paintAll(false);
    }

    /* ── 交互 ───────────────────────────────────────────────────────────── */

    const onSearch = () => {
      keyword = String(view.query.value || '').trim().toLowerCase();
      paintList(false);
    };
    view.query.addEventListener('input', onSearch);

    const onListClick = (event) => {
      const row = event.target.closest('.c-grade');
      if (!row) return;
      const id = row.dataset.gradeId;
      if (!id) return;
      // 共享元素：把这一行名字的位置记下来，详情页标题从这儿飞过去
      rememberShared(`grade:${id}`, row.querySelector('.c-grade__name'));
      actions.navigate(`/grade/${id}`);
    };
    view.list.addEventListener('click', onListClick);

    /* ── 订阅 + 首次取数 ────────────────────────────────────────────────── */

    const unsubscribe = store.subscribe(
      (state) => state.slots.grades,
      () => paint()
    );
    paint();

    const slot = store.getState().slots.grades;
    if (!slot || slot.status === 'idle') actions.loadSlot('grades');

    return () => {
      unsubscribe();
      view.query.removeEventListener('input', onSearch);
      view.list.removeEventListener('click', onListClick);
      view.chips.destroy();
    };
  },

  unmount() {
    /* 监听与 chip 在 cleanup 里收尾 */
  },
};
