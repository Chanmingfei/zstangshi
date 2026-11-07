/**
 * home.js —— 首页（P02）
 *
 * 首页只回答两个问题：**现在第几周**（顶栏，app.js 负责）和 **今天上什么课**（本页）。
 * 其余都是一次点击的入口。所以本页的取舍是：
 *   · 今日课程卡是唯一有网络依赖的区块，失败时**只降级它自己**，宫格与倒计时永远可用；
 *   · 倒计时不是写死的日期，从 school-calendar.json 的 examWeeks / semesterEnd 推出来，
 *     明年换校历不用改代码；
 *   · 6 个宫格 +「更多功能」+ 学工入口一律走 data-action 全局委托，页面不绑零散 click；
 *   · 下拉刷新只重取今日课程（顶栏与宫格是同步数据，刷新它们没有意义）。
 *
 * 演示态用 `term.todayIso`（app.js 在 demo 模式写入 DEMO.today），所以「今天」永远是
 * 2026-11-05 周四那 4 门课 —— 否则打开时间不同结果就不同，验收不可复现。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { countUp, stagger } from '../core/animate.js';
import { card } from '../components/card.js';
import { courseCard } from '../components/courseCard.js';
import { emptyState } from '../components/emptyState.js';
import { skeletonList } from '../components/skeleton.js';
import { createPullRefresh } from '../components/pullRefresh.js';
import {
  WEEKDAY_LABELS,
  dayNumberOfIso,
  getSemesters,
  isoOfDayNumber,
} from '../data/calendar.js';
import { jwxtUrl, XUEGONG_URL } from '../data/externalLinks.js';

/** 快捷入口（顺序即渲染顺序）。`path` 是 hash 路由。
 * 「教学执行计划查看」按用户口径已整条下线，这里是剩下的 6 个。 */
const QUICK_ENTRIES = [
  { path: '/grades', label: '成绩', icon: 'grades' },
  { path: '/gpa', label: '学分绩点', icon: 'chart' },
  { path: '/timetable', label: '我的课表', icon: 'timetable' },
  { path: '/selection', label: '选课', icon: 'list' },
  { path: '/recommend', label: '推荐课表', icon: 'star' },
  { path: '/calendar', label: '校历', icon: 'calendar' },
];

/** 学工入口与「更多功能」的地址都来自 data/externalLinks.js（页面不写死 URL），
 * 两者都从首页一次点击直达，不做中间跳转页。 */

/**
 * 从校历推出倒计时（不写死日期）。
 * 放假 = 校历的寒假开始日（winterBreakStart，缺省用学期结束次日）。
 * 不再单列「距期末考试」：18/19 周也可能因调课排课，考试周概念对用户没必要（2026-11-06 口径）。
 *
 * @param {object|null} calendar
 * @param {object|null} context term.context
 * @param {string} todayIso
 * @returns {Array<{key: string, label: string, days: number|null}>}
 */
function buildCountdowns(calendar, context, todayIso) {
  if (!calendar || !context) {
    return [{ key: 'vacation', label: '距放假', days: null }];
  }
  const semesters = getSemesters(calendar);
  const semester = semesters.find((item) => item.id === context.semesterId) || semesters[0];
  const today = dayNumberOfIso(todayIso);

  const vacationStart = semester && semester.winterBreakStart
    ? semester.winterBreakStart
    : semester && semester.semesterEnd
      ? isoOfDayNumber(dayNumberOfIso(semester.semesterEnd) + 1)
      : null;

  const toDays = (target) => {
    if (!target) return null;
    const diff = dayNumberOfIso(target) - today;
    return Number.isFinite(diff) ? diff : null;
  };

  return [{ key: 'vacation', label: '距放假', days: toDays(vacationStart) }];
}

/**
 * 把今日课程渲染进容器（只有这一个区块会被局部替换）。
 * @param {HTMLElement} host
 * @param {object} slot
 * @param {() => void} retry
 */
function paintToday(host, slot, retry) {
  host.replaceChildren();
  const status = slot && slot.status ? slot.status : 'idle';

  if (status === 'loading' || status === 'idle') {
    host.appendChild(skeletonList({ rows: 3, withTrailing: false }));
    return;
  }

  if (status === 'error') {
    host.appendChild(
      emptyState({
        kind: navigator.onLine === false ? 'offline' : 'error',
        title: (slot.error && slot.error.message) || '今日课程没能取回来',
        description: '顶栏和快捷入口不受影响，可以先看别的',
        actionLabel: '重试',
        onAction: retry,
        compact: true,
      })
    );
    return;
  }

  const courses = Array.isArray(slot.data) ? slot.data : [];
  if (courses.length === 0) {
    host.appendChild(
      emptyState({
        kind: 'empty',
        title: '今天没有课',
        description: '好好休息，或者去图书馆坐坐',
        compact: true,
      })
    );
    return;
  }

  host.appendChild(
    h(
      'div',
      { className: 'p-home__courses' },
      courses.map((course) => courseCard(course, { action: `course:${course.id}` }))
    )
  );

  // 降级数据（remote → demo）在这张卡上标出来，避免用户以为看到的是真实课表
  if (slot.degraded || (slot.meta && slot.meta.degraded)) {
    host.appendChild(h('p', { className: 'p-home__hint', text: '当前展示演示数据（教务系统暂不可达）' }));
  } else if (slot.fromCache && slot.updatedAt) {
    host.appendChild(
      h('p', { className: 'p-home__hint', text: `上次同步 ${new Date(slot.updatedAt).toLocaleTimeString()}` })
    );
  }
}

export default {
  name: 'home',
  title: '首页',
  tab: 'home',
  requiresAuth: false,

  /**
   * 同步返回完整骨架：倒计时与宫格在 render 里就算好（纯本地数据），
   * 今日课程卡先给骨架屏，数据到了再替换那一块。
   *
   * @param {object} ctx
   * @returns {HTMLElement}
   */
  render(ctx) {
    const state = ctx.store.getState();
    const todayIso = state.term.todayIso;
    const context = state.term.context;
    const weekday = new Date(`${todayIso}T12:00:00`).getDay() || 7; // 1..7，周日归 7
    const weekdayText = WEEKDAY_LABELS[weekday - 1] || '';
    // 11 月 5 日 而不是 11 月 05 日：中文里日期不补零
    const [, month, day] = todayIso.split('-');
    const dateText = `${Number(month)} 月 ${Number(day)} 日 · 周${weekdayText}`;

    /**
     * 倒计时条：距放假天数（按校历寒假开始日）。
     * 它撑不起两块大卡的分量，而且首页第一屏必须留给「快速查询」—— 宫格在倒计时之上。
     * 数字仍走 countUp 滚动，强调色用 --brand-accent-text（暖金当文字只有这一档够对比度）。
     */
    const countNodes = new Map();
    const countdowns = buildCountdowns(state.term.calendar, context, todayIso);
    const countStrip = h(
      'div',
      { className: 'p-home__counts', role: 'status', 'aria-label': '关键节点倒计时' },
      countdowns.reduce((nodes, item, index) => {
        const num = h('span', { className: 'p-home__count-num u-num', text: '--' });
        countNodes.set(item.key, { el: num, days: item.days });
        if (index > 0) nodes.push(h('span', { className: 'p-home__count-sep', text: '·', 'aria-hidden': 'true' }));
        nodes.push(
          h('span', { className: 'p-home__count' }, [
            h('span', { className: 'p-home__count-label', text: item.label }),
            num,
            h('span', { className: 'p-home__count-unit', text: '天' }),
          ])
        );
        return nodes;
      }, [])
    );

    /* ── 今日课程卡 ── */
    const todayHost = h('div', { className: 'p-home__today' });
    const todayCard = card({ title: '今日课程', subtitle: dateText }, todayHost);

    /* ── 快捷宫格 + 更多功能 + 学工入口 ── */
    const grid = h(
      'div',
      { className: 'p-home__grid' },
      QUICK_ENTRIES.map((entry) =>
        h(
          'button',
          {
            className: 'p-home__cell',
            type: 'button',
            dataset: { action: `navigate:${entry.path}` },
            'aria-label': entry.label,
          },
          [
            h('span', { className: 'p-home__cell-icon', 'aria-hidden': 'true', html: icon(entry.icon, 24) }),
            h('span', { className: 'p-home__cell-label', text: entry.label }),
          ]
        )
      ).concat(
        // 「更多功能」：不属于 App 内路由，与学工入口同一条原生 WebView 通道打开教务系统原站点。
        // 尺寸/质感与上面 6 格完全一致，只把它排在宫格末尾（第 7 格），设计不做其它改动。
        h(
          'button',
          {
            className: 'p-home__cell',
            type: 'button',
            dataset: { action: `open-external:${jwxtUrl()}` },
            'aria-label': '更多功能',
          },
          [
            h('span', { className: 'p-home__cell-icon', 'aria-hidden': 'true', html: icon('grid', 24) }),
            h('span', { className: 'p-home__cell-label', text: '更多功能' }),
          ]
        )
      )
    );

    const affairs = h(
      'button',
      {
        className: 'p-home__affairs',
        type: 'button',
        dataset: { action: `open-xuegong:${XUEGONG_URL}` },
      },
      [
        h('span', { className: 'p-home__affairs-icon', 'aria-hidden': 'true', html: icon('external', 20) }),
        h('span', { className: 'p-home__affairs-text' }, [
          h('span', { className: 'p-home__affairs-title', text: '学工系统' }),
          h('span', { className: 'p-home__affairs-desc', text: '奖助贷、请假、综合素质 · 外部网页' }),
        ]),
        h('span', { className: 'p-home__affairs-arrow', 'aria-hidden': 'true', html: icon('chevronRight', 18) }),
      ]
    );

    // 顺序按 PRD P02：今日课程 → 快捷宫格 → 关键节点倒计时。
    // 宫格必须在首屏内（核心诉求就是「快速查成绩/绩点/课表」），倒计时是补充信息，垫底。
    const content = h('div', { className: 'p-home__content' }, [
      todayCard,
      h('h2', { className: 'c-section__title p-home__grid-title', text: '常用功能' }),
      grid,
      countStrip,
      affairs,
    ]);

    const pull = createPullRefresh({
      content,
      onRefresh: () => ctx.actions.loadSlot('todayCourses', { force: true }),
    });

    // 页面根就是下拉容器：router 会给它加 .c-page
    const el = pull.el;
    el.classList.add('page', 'p-home');
    el.__view = { pull, todayHost, countNodes, grid };
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

    const retry = () => actions.loadSlot('todayCourses', { force: true });

    // 只替换今日课程卡那一块 DOM，宫格与倒计时一次都不重绘
    const unsubscribe = store.subscribe(
      (s) => s.slots.todayCourses,
      (slot) => paintToday(view.todayHost, slot, retry)
    );
    paintToday(view.todayHost, store.getState().slots.todayCourses, retry);

    const slot = store.getState().slots.todayCourses;
    if (!slot || slot.status === 'idle') actions.loadSlot('todayCourses');

    // 倒计时：数字滚动 350ms（减弱动效时 countUp 内部直接落终值）
    for (const [, item] of view.countNodes) {
      if (item.days === null) {
        item.el.textContent = '--';
        continue;
      }
      countUp(item.el, Math.max(0, item.days), { decimals: 0 });
    }

    // 宫格交错入场：40ms/个，第 9 个起不再叠加（此处 6 格 + 更多功能 = 7 个）
    stagger(view.grid.children);

    return () => {
      unsubscribe();
      view.pull.destroy();
    };
  },

  unmount() {
    /* cleanup 已销毁下拉容器并退订 */
  },
};
