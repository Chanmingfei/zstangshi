/**
 * gradeDetail.js —— 成绩详情（P04）
 *
 * 三条约束：
 *   1. **直刷必须能开**。`#/grade/g-2627-1-01` 复制到浏览器里回车也要出页面 ——
 *      render() 同步给出「标题 + 大号分数位 + 字段卡」骨架，数据在 mount 里补。
 *   2. **查不到不是错误**。教务归档后 `getGradeById` 返回 `ok(null)`，这时给「该课程记录已更新，
 *      请返回列表刷新」+ 返回按钮，而不是红色错误页 —— 用户没做错任何事。
 *   3. **不补算、不猜**。分数、绩点原样展示；`scoreDetail` 和教师/学时缺失就整块不显示或缺省为
 *      「教务未提供」，绝不摆三个「--」假装有数据。
 *
 * 大号分数用 countUp 350ms 从 0 滚到目标值（复用动效总线，减弱动效时直接落终值）。
 */

import { h } from '../core/dom.js';
import { countUp } from '../core/animate.js';
import { playShared } from '../core/shared.js';
import { syncText } from '../core/format.js';
import { emptyState } from '../components/emptyState.js';
import { GRADE_KIND, gradeResultOf } from '../data/gradeText.js';

/** 课程类型 / 考核方式中文（与 gradeRow 同口径，组件之间不互相 import） */
const TYPE_LABEL = { required: '必修', elective: '选修', public: '公共', practice: '实践' };
const ASSESS_LABEL = { exam: '考试', check: '考查' };
/** 教务没给的值（教师、学时）—— 明说「教务未提供」，不留空白也不摆「--」 */
const NOT_PROVIDED = '教务未提供';

/** 分项得分可能是汉字（`良好`），不用数字字体渲染，否则会撑破格子 */
function isTextScore(value) {
  return !/^-?\d+(?:\.\d+)?$/.test(String(value).trim());
}

/**
 * 取一门课的成绩构成。
 * 首选 `components`（教务成绩明细查询的原样分项，带权重）；
 * 兜底兼容早期的 `scoreDetail: {usual, midterm, final}`（老缓存 / 演示数据）。
 * @param {object} grade
 * @returns {Array<{label: string, weight: number|null, score: string|number}>}
 */
function componentsOf(grade) {
  if (Array.isArray(grade.components) && grade.components.length) return grade.components;
  const legacy = grade.scoreDetail;
  if (!legacy) return [];
  return [
    { label: '平时', weight: null, score: legacy.usual },
    { label: '期中', weight: null, score: legacy.midterm },
    { label: '期末', weight: null, score: legacy.final },
  ].filter((item) => item.score !== null && item.score !== undefined && item.score !== '');
}

/**
 * 从已加载的课表里补齐教师与学时。
 * 成绩接口只给分数，教师/学时在课程侧；课表槽没加载过（比如直刷进来）就返回空对象，
 * 字段退化成「教务未提供」—— 为两个展示字段单独打一次网络请求不值得。
 * @param {object} grade
 * @param {object} store
 * @returns {{teacher?: string, hours?: number}}
 */
function enrichFromTimetable(grade, store) {
  const slot = store.getState().slots.timetable;
  const courses = slot && slot.data && Array.isArray(slot.data.courses) ? slot.data.courses : [];
  if (courses.length === 0) return {};
  const hit =
    courses.find((course) => course.id === grade.courseId) ||
    courses.find((course) => course.code && course.code === grade.courseCode);
  if (!hit) return {};
  return { teacher: hit.teacher, hours: hit.hours };
}

/**
 * 一行 kv。
 * @param {string} label
 * @param {string|number} value
 * @returns {HTMLElement}
 */
function kvRow(label, value) {
  const text = value === null || value === undefined || value === '' ? NOT_PROVIDED : String(value);
  return h('div', { className: 'p-gd__row' }, [
    h('span', { className: 'p-gd__row-label', text: label }),
    h('span', {
      className: `p-gd__row-value${text === NOT_PROVIDED ? ' is-empty' : ''}`,
      text,
    }),
  ]);
}

export default {
  name: 'gradeDetail',
  title: '成绩详情',
  tab: null,
  requiresAuth: true,

  /**
   * 同步骨架：标题、大号分数位、字段卡（先放三行灰条）、缓存行。
   * @param {object} ctx
   * @returns {HTMLElement}
   */
  render(ctx) {
    const id = String((ctx.params && ctx.params.id) || '');

    const title = h('h1', { className: 'p-gd__title' });
    const scoreValue = h('span', { className: 'p-gd__score-value' });
    const scoreTag = h('span', { className: 'p-gd__score-tag' });
    const gp = h('span', { className: 'p-gd__gp' });
    const note = h('p', { className: 'p-gd__note' });

    const hero = h('section', { className: 'p-gd__hero' }, [
      title,
      h('div', { className: 'p-gd__score-line' }, [scoreValue, scoreTag]),
      gp,
      note,
    ]);

    const card = h('div', { className: 'p-gd__card' });
    const breakdown = h('section', { className: 'p-gd__breakdown', hidden: true });
    const syncLine = h('p', { className: 'p-gd__sync', hidden: true });
    const body = h('div', { className: 'p-gd__body' }, [hero, card, breakdown, syncLine]);
    const fallback = h('div', { className: 'p-gd__fallback', hidden: true });

    const el = h('div', { className: 'page p-gd' }, [body, fallback]);
    el.__view = { id, body, title, scoreValue, scoreTag, gp, note, card, breakdown, syncLine, fallback };
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

    /** 已经播过共享元素过渡就不再重播（数据刷新会把标题重绘一次） */
    let sharedPlayed = false;
    /** 大号分数的滚动只播一次：课表数据补进来会重绘一遍，不该再滚一次 */
    let counted = false;

    /** 加载态：字段卡里放灰条，位置与大号分数保持不变，避免加载完成时整页跳动 */
    function paintSkeleton() {
      view.body.hidden = false;
      view.fallback.hidden = true;
      view.scoreValue.textContent = '';
      view.scoreValue.classList.remove('is-missing', 'is-fail');
      view.scoreValue.removeAttribute('aria-label');
      view.scoreTag.textContent = '';
      view.gp.textContent = '';
      view.note.textContent = '';
      view.syncLine.hidden = true;
      view.card.replaceChildren(
        ...Array.from({ length: 5 }, () =>
          h('div', { className: 'p-gd__row' }, [
            h('span', { className: 'p-gd__row-label' }),
            h('span', { className: 'p-gd__row-value' }, [
              h('span', { className: 'p-gd__ghost', 'aria-hidden': 'true' }),
            ]),
          ])
        )
      );
      view.card.setAttribute('aria-busy', 'true');
    }

    /**
     * 兜底态：记录不存在 / 取数失败。两种情况都留在页面里（不跳错误页），都带返回入口。
     * @param {'missing'|'error'} kind
     * @param {object|null} error
     */
    function paintFallback(kind, error) {
      view.body.hidden = true;
      view.fallback.hidden = false;
      view.fallback.replaceChildren(
        kind === 'missing'
          ? emptyState({
              kind: 'empty',
              title: '该课程记录已更新，请返回列表刷新',
              description: '教务可能已归档或合并了这条成绩记录',
              actionLabel: '返回成绩列表',
              onAction: () => actions.navigate('/grades'),
            })
          : emptyState({
              kind: 'error',
              title: '这条成绩没能取回来',
              description: (error && error.message) || '请检查网络后重试',
              actionLabel: '重试',
              onAction: () => actions.loadGrade(view.id),
              secondaryLabel: '返回成绩列表',
              onSecondary: () => actions.navigate('/grades'),
            })
      );
    }

    /**
     * 成绩构成。教务「学生成绩明细查询」给什么就摆什么（平时/期末/期中，带权重），
     * 没给就整块不显示 —— 摆三个「--」比不摆更糟。
     * @param {object} grade
     */
    function paintBreakdown(grade) {
      const comps = componentsOf(grade);
      if (comps.length === 0) {
        view.breakdown.hidden = true;
        view.breakdown.replaceChildren();
        return;
      }
      view.breakdown.hidden = false;
      view.breakdown.replaceChildren(
        h('h2', { className: 'p-gd__section-title', text: '成绩构成' }),
        h(
          'div',
          {
            className: 'p-gd__breakdown-grid'
              + (comps.length === 1 ? ' is-single' : comps.length === 2 ? ' is-compact' : ''),
          },
          comps.map((item) =>
            h('div', { className: 'p-gd__breakdown-cell' }, [
              h('span', {
                className: `p-gd__breakdown-value${isTextScore(item.score) ? ' is-text' : ''}`,
                text: String(item.score),
              }),
              h('span', {
                className: 'p-gd__breakdown-label',
                text: item.weight === null || item.weight === undefined
                  ? item.label
                  : `${item.label} ${item.weight}%`,
              }),
            ])
          )
        ),
        h('p', { className: 'p-gd__breakdown-label', text: '来自教务「学生成绩明细查询」' })
      );
    }

    /**
     * 主绘制。
     * @param {object} grade
     * @param {object} slot
     */
    function paintGrade(grade, slot) {
      view.body.hidden = false;
      view.fallback.hidden = true;
      view.card.setAttribute('aria-busy', 'false');

      view.title.textContent = grade.courseName;
      // 列表行 → 详情标题：只在第一次落地时飞，后续刷新不重复播
      if (!sharedPlayed) {
        sharedPlayed = true;
        playShared(`grade:${grade.id}`, view.title);
      }

      // 三种形态统一判定：数字分 / 文字成绩（良好、优秀）/ 还没有结果（未录入、缓考）
      const result = gradeResultOf(grade);
      const missing = result.kind === GRADE_KIND.PENDING;
      const isText = result.kind === GRADE_KIND.TEXT;
      view.scoreValue.classList.toggle('is-missing', missing);
      view.scoreValue.classList.toggle('is-text', isText);
      view.scoreValue.classList.toggle('is-fail', result.failed);

      if (missing) {
        view.scoreValue.textContent = result.value;
        view.gp.textContent = '';
        // 缓考不是「没出分」，是「考了但顺延」，说明也要跟着变
        view.note.textContent =
          grade.remark === '缓考' || result.value === '缓考'
            ? '已办理缓考，成绩通常在考后 2-4 周公布'
            : '成绩通常在考后 2-4 周公布';
      } else {
        // 文字成绩（良好/优秀）没有数字可滚，直接落字，不能让它从 0 滚到「良好」
        if (isText) view.scoreValue.textContent = result.value;
        else if (!counted) countUp(view.scoreValue, grade.score, { duration: 350 });
        else view.scoreValue.textContent = String(grade.score);
        counted = true;
        view.gp.textContent =
          grade.gradePoint === null || grade.gradePoint === undefined
            ? '绩点 --'
            : `绩点 ${grade.gradePoint}`;
        // 文字成绩单独占了大字位，不再重复「· 良好」；只有数字分才需要这层标注
        view.note.textContent = isText ? '五级制成绩，由教务按等第记载' : '';
      }
      view.scoreTag.textContent = !missing && !isText && grade.scoreText ? `· ${grade.scoreText}` : '';

      const extra = enrichFromTimetable(grade, store);
      const rows = [
        kvRow('课程号', grade.courseCode),
        kvRow('学分', grade.credits === null || grade.credits === undefined ? null : `${grade.credits} 学分`),
        kvRow('学时', extra.hours ? `${extra.hours} 学时` : null),
        kvRow('教师', extra.teacher || null),
        kvRow('考核方式', ASSESS_LABEL[grade.assessment] || grade.assessment),
        kvRow('课程类型', TYPE_LABEL[grade.courseType] || grade.courseType),
        kvRow('学期', grade.semesterId),
      ];
      // 「无备注」不该占一行：只有真有备注（缓考/缺考/重修）才渲染这一行
      const remark = grade.remark || (grade.isRetake ? '重修' : '');
      if (remark) rows.push(kvRow('备注', remark));
      view.card.replaceChildren(...rows);

      paintBreakdown(grade);

      // 缓存态：数据来自本地缓存时说明「这不是刚取的」，否则用户会以为分数刚更新过
      if (slot.fromCache || slot.status === 'stale') {
        view.syncLine.hidden = false;
        view.syncLine.textContent = `上次同步 ${syncText(slot.updatedAt)}`;
      } else {
        view.syncLine.hidden = true;
      }
    }

    function paint() {
      const slot = store.getState().slots.gradeDetail;
      if (slot.id && slot.id !== view.id) return; // 别的详情的数据，别画到这一页
      if (slot.status === 'idle' || slot.status === 'loading') {
        paintSkeleton();
        return;
      }
      if (slot.status === 'missing') {
        paintFallback('missing', null);
        return;
      }
      if (slot.status === 'error' || !slot.data) {
        paintFallback('error', slot.error);
        return;
      }
      paintGrade(slot.data, slot);
    }

    /* ── 订阅 + 首次取数 ─────────────────────────────────────────────────── */

    const unsubscribe = store.subscribe(
      (state) => state.slots.gradeDetail,
      () => paint()
    );

    // 教师 / 学时在课程侧。课表槽已经加载过就直接用；没有就让它在后台取一次，
    // 取回来重画一遍把那两行从「教务未提供」补成真值（取不到就保持「教务未提供」，不影响分数）
    const unsubTimetable = store.subscribe(
      (state) => state.slots.timetable,
      () => {
        const slot = store.getState().slots.gradeDetail;
        if (slot.status === 'ready' && slot.data) paintGrade(slot.data, slot);
      }
    );

    // 直刷兜底：地址里没 id（手动改坏了 URL）就没有可查的记录，直接给「记录已更新」
    if (!view.id) paintFallback('missing', null);
    else {
      paint();
      const slot = store.getState().slots.gradeDetail;
      // 从列表点进来时槽里已经是这条（id 相同），不必再打一次
      if (!(slot.id === view.id && slot.status === 'ready')) actions.loadGrade(view.id);
      const timetable = store.getState().slots.timetable;
      if (!timetable || timetable.status === 'idle') actions.loadSlot('timetable');
    }

    return () => {
      unsubscribe();
      unsubTimetable();
    };
  },

  unmount() {
    /* 订阅在 cleanup 里注销 */
  },
};
