/**
 * calendar.js —— 校历页（P10 / T12）
 *
 * 【唯一真源，别再算第二遍】
 * 教学周口径只认 `web/data/calendar.js`：week = floor((today − semesterStart)/7) + 1，
 * 内部把日期折成 UTC 日序号消时区。首页顶栏（app.js bootTerm）用的也是
 * `pickSemester()` + `buildTermHeader()`，本页**复用同一组函数**，
 * 连概览文案都直接取 `buildTermHeader().full` —— 不自己拼「第 N 教学周」，
 * 否则两处数字迟早打架。月历网格的周次角标则由 `calendarGrid.buildMonthGrid()`
 * 内部调用同一个 `weekOfDate()` 生成，也不会有第二套算法。
 *
 * 【「今天」取哪个日期】
 * 演示会话用 DEMO_TODAY（2026-11-05 周四），正式登录用系统当天。
 * 这个判断**已经在 bootTerm 里做完并写进 `state.term.todayIso`**，本页直接读它，
 * 不再重复判 provider.mode —— 一处锚点、全 App 一致。
 *
 * 【数据从哪来】
 * 校历 JSON 由 bootTerm 落到 `state.term.calendar`。本页不自己 fetch、不缓存副本，
 * 所以「权威数据 + 副本 deepEqual」这条守卫（check-rules ④）永远由同一份文件保证。
 * 教务处校历地址也从 JSON 的 `sources.calendar` 读，JS 里不写外链字面量（守卫②）。
 *
 * 页面生命周期：render 同步出骨架，mount 订阅 term、绑月份切换，cleanup 全解绑。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { emptyState } from '../components/emptyState.js';
import { skeletonCard } from '../components/skeleton.js';
import {
  WEEKDAY_LABELS,
  buildTermHeader,
  getSemesters,
  isoOfDayNumber,
  listWeekRanges,
  pickSemester,
  todayDayNumber,
  weekOfDate,
} from '../data/calendar.js';
import { buildMonthGrid, indexMilestones } from '../data/calendarGrid.js';

/** 节点类型 → 中文图例。颜色由 pagesCalendar.css 的 .p-cal__dot--xxx 给，不在 JS 里造色。 */
const MILESTONE_LABELS = {
  holiday: '节假日',
  event: '校园活动',
  enroll: '报到注册',
  teaching: '教学节点',
  vacation: '假期',
  exam: '考试',
};

/**
 * 把 'YYYY-MM' 拆成 [年, 月]，非法输入退回指定月份。
 * @param {string} value
 * @param {string} fallback 'YYYY-MM'
 * @returns {[number, number]}
 */
function parseMonth(value, fallback) {
  const safe = /^\d{4}-(0[1-9]|1[0-2])$/.test(String(value || '')) ? value : fallback;
  const [year, month] = String(safe).split('-').map(Number);
  return [year, month];
}

/**
 * 月份 ±1（跨年靠 Date.UTC 自己进位，不手写 12→1 判断）。
 * @param {string} value 'YYYY-MM'
 * @param {number} delta
 * @param {string} fallback 非法输入时退回的月份（一般是「今天」所在月）
 * @returns {string}
 */
function shiftMonth(value, delta, fallback) {
  const [year, month] = parseMonth(value, fallback);
  const d = new Date(Date.UTC(year, month - 1 + delta, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

/**
 * '2026-11-05' → '11/5'（月历里的日期文案）。
 * @param {string} iso
 * @returns {string}
 */
function shortDate(iso) {
  const parts = String(iso || '').split('-');
  return parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : String(iso || '');
}

export default {
  name: 'calendar',
  title: '校历',
  requiresAuth: false,

  /**
   * 同步出骨架：概览 / 月份切换 / 月历网格 / 19 周列表 / 节点列表 / 来源说明。
   * @param {object} ctx
   * @returns {HTMLElement}
   */
  render(ctx) {
    const state = ctx.store.getState();
    const todayIso = (state.term && state.term.todayIso) || isoOfDayNumber(todayDayNumber());
    const month = (state.ui && state.ui.calendarMonth) || todayIso.slice(0, 7);

    const overview = h('div', { className: 'p-cal__overview' }, skeletonCard({ lines: 3 }));

    const prevBtn = h('button', {
      className: 'p-cal__nav', type: 'button', 'aria-label': '上一个月',
      html: icon('back', 20),
    });
    const nextBtn = h('button', {
      className: 'p-cal__nav', type: 'button', 'aria-label': '下一个月',
      html: icon('forward', 20),
    });
    const monthLabel = h('span', { className: 'p-cal__month-label', text: '' });
    const backBtn = h('button', {
      className: 'p-cal__back', type: 'button', hidden: true, text: '回到本月',
    });
    const monthHead = h('div', { className: 'p-cal__month-head' }, [prevBtn, monthLabel, nextBtn, backBtn]);

    const weekdays = h('div', { className: 'p-cal__weekdays' },
      WEEKDAY_LABELS.map((label) => h('span', { className: 'p-cal__weekday', text: label })));
    const grid = h('div', { className: 'p-cal__grid', role: 'grid' });
    const weeks = h('div', { className: 'p-cal__weeks' });
    const events = h('div', { className: 'p-cal__events' });
    const legend = h('div', { className: 'p-cal__legend' });
    const source = h('div', { className: 'p-cal__source' });
    const loading = h('div', { className: 'p-cal__loading' }, skeletonCard({ lines: 4 }));

    const el = h('div', { className: 'page p-cal' }, [
      overview,
      h('h2', { className: 'p-cal__section-title', text: '月历' }),
      monthHead,
      weekdays,
      grid,
      legend,
      loading,
      h('h2', { className: 'p-cal__section-title', text: '19 个教学周' }),
      weeks,
      h('h2', { className: 'p-cal__section-title', text: '关键节点' }),
      events,
      source,
    ]);

    el.__view = {
      overview, prevBtn, nextBtn, monthLabel, backBtn,
      grid, weeks, events, legend, source, loading, month,
    };
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

    /** 本月（由「今天」锚点决定，不是系统当月） */
    function anchor() {
      const state = store.getState();
      const term = state.term || {};
      const todayIso = term.todayIso || isoOfDayNumber(todayDayNumber());
      return {
        todayIso,
        monthOfToday: todayIso.slice(0, 7),
        calendarJson: term.calendar || null,
      };
    }

    /** 从校历 JSON 里取学期：复用 app.js bootTerm 的同一组函数 */
    function resolveSemester(calendarJson, todayIso) {
      const list = getSemesters(calendarJson);
      const date = new Date(`${todayIso}T12:00:00`);
      const picked = pickSemester(date, calendarJson) || list[0] || null;
      return { semester: picked, header: buildTermHeader(date, picked) };
    }

    /** 概览：文案直接取 buildTermHeader()，保证与顶栏逐字一致 */
    function paintOverview(calendarJson, todayIso, header, semester) {
      const totalWeeks = (semester && Number(semester.totalWeeks)) || 19;
      const teachingWeeks = (semester && Number(semester.teachingWeeks)) || 17;
      const examWeeks = (semester && Array.isArray(semester.examWeeks) ? semester.examWeeks : []).map(Number);
      const weekFromDate = weekOfDate(todayIso, semester);
      const weekText = weekFromDate === null ? '假期中' : `第 ${weekFromDate} 教学周`;

      view.overview.replaceChildren(
        h('p', { className: 'p-cal__term', text: header.left || '校历' }),
        h('p', { className: 'p-cal__week', text: header.text || '—' }),
        h('p', {
          className: 'p-cal__meta',
          text: [
            `今天 ${shortDate(todayIso)} · ${weekText}`,
            `共 ${totalWeeks} 周（上课 ${teachingWeeks} 周）`,
            examWeeks.length ? `复习考试周 ${examWeeks.join('、')}` : '',
          ].filter(Boolean).join('　|　'),
        }),
        h('p', {
          className: 'p-cal__meta',
          text: calendarJson && calendarJson._verified_at
            ? `校历核对于 ${calendarJson._verified_at} · 学期 ${semester.semesterStart} ~ ${semester.semesterEnd}`
            : `学期 ${semester.semesterStart} ~ ${semester.semesterEnd}`,
        })
      );
    }

    /** 图例：只列这份校历里真实出现过的节点类型，不摆一堆用不上的色点 */
    function paintLegend(semester) {
      const seen = new Set((semester.milestones || []).map((item) => item.type || 'event'));
      const items = Object.keys(MILESTONE_LABELS).filter((type) => seen.has(type));
      view.legend.replaceChildren(
        ...items.map((type) =>
          h('span', { className: 'p-cal__legend-item' }, [
            h('span', { className: `p-cal__dot p-cal__dot--${type}`, 'aria-hidden': 'true' }),
            h('span', { text: MILESTONE_LABELS[type] }),
          ])
        ),
        h('span', { className: 'p-cal__legend-item' }, [
          h('span', { className: 'p-cal__dot p-cal__dot--exam', 'aria-hidden': 'true' }),
          h('span', { text: '复习考试周' }),
        ])
      );
    }

    /** 月历网格：42 格，周次角标 / 今日 / 当前周 / 考试周 / 节点色点 */
    function paintGrid(semester, todayIso, currentWeek, examWeeks) {
      const [year, month] = parseMonth(view.month, todayIso.slice(0, 7));
      const cells = buildMonthGrid(year, month, semester, todayIso);
      view.monthLabel.textContent = `${year} 年 ${month} 月`;
      view.backBtn.hidden = view.month === todayIso.slice(0, 7);

      view.grid.replaceChildren(
        ...cells.map((cell) => {
          const classes = ['p-cal__cell'];
          if (!cell.inMonth) classes.push('is-outside');
          if (cell.isToday) classes.push('is-today');
          if (cell.weekNo === currentWeek) classes.push('is-current');
          const isExam = cell.weekNo !== null && examWeeks.includes(cell.weekNo);
          if (isExam) classes.push('is-exam');

          const dots = cell.events.slice(0, 3).map((event) =>
            h('span', {
              className: `p-cal__cell-dot p-cal__dot--${event.type || 'event'}`,
              'aria-hidden': 'true',
            })
          );
          const labels = [shortDate(cell.date), cell.weekNo === null ? '非学期内' : `第 ${cell.weekNo} 教学周`];
          if (isExam) labels.push('复习考试周');
          for (const event of cell.events) labels.push(event.title);

          return h('div', {
            className: classes.join(' '),
            role: 'gridcell',
            'aria-label': labels.join('，'),
          }, [
            h('span', { className: 'p-cal__cell-day', text: String(cell.day) }),
            cell.weekNo !== null
              ? h('span', { className: 'p-cal__cell-week', text: isExam ? '考' : `W${cell.weekNo}` })
              : null,
            h('span', { className: 'p-cal__cell-dots' }, dots),
          ]);
        })
      );
    }

    /** 19 周列表：当前周高亮，考试周给「复习考试周 · 不排课」空态标记 */
    function paintWeeks(semester, currentWeek, examWeeks) {
      const ranges = listWeekRanges(semester);
      view.weeks.replaceChildren(
        ...ranges.map((range) => {
          const isExam = examWeeks.includes(range.week);
          const classes = ['p-cal__weeks-row'];
          if (range.week === currentWeek) classes.push('is-current');
          if (isExam) classes.push('is-exam');
          return h('div', {
            className: classes.join(' '),
            'aria-label': `第 ${range.week} 教学周，${range.start} 至 ${range.end}${isExam ? '，复习考试周' : ''}`,
          }, [
            h('span', { className: 'p-cal__weeks-no', text: `第 ${range.week} 周` }),
            h('span', { className: 'p-cal__weeks-range', text: `${shortDate(range.start)} ~ ${shortDate(range.end)}` }),
            range.week === currentWeek
              ? h('span', { className: 'p-cal__weeks-tag', text: '本周' })
              : isExam
                ? h('span', { className: 'p-cal__weeks-tag is-exam', text: '复习考试周 · 不排课' })
                : null,
          ]);
        })
      );
    }

    /** 关键节点列表 + 图例，都从 indexMilestones / milestones 现取，不另造一份 */
    function paintEvents(semester) {
      const map = indexMilestones(semester);
      const rows = [];
      for (const [date, list] of [...map.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
        for (const event of list) {
          rows.push(h('div', { className: 'p-cal__event' }, [
            h('span', { className: `p-cal__dot p-cal__dot--${event.type || 'event'}`, 'aria-hidden': 'true' }),
            h('span', { className: 'p-cal__event-date', text: shortDate(date) }),
            h('span', { className: 'p-cal__event-title', text: event.title }),
          ]));
        }
      }
      view.events.replaceChildren(
        ...(rows.length ? rows : [h('p', { className: 'p-cal__meta', text: '本学期暂无关键节点记录' })])
      );
    }

    /** 来源说明：URL 从校历 JSON 读，JS 里不出现外链字面量（守卫②） */
    function paintSource(calendarJson) {
      const sourceText = (calendarJson && calendarJson._source) || '校历数据来自唐山师范学院教务处发布的学期校历。';
      const url = calendarJson && calendarJson.sources ? calendarJson.sources.calendar : '';
      view.source.replaceChildren(
        h('p', { className: 'p-cal__source-text', text: sourceText }),
        url ? h('p', { className: 'p-cal__source-url', text: `校历原页：${url}` }) : null,
        url
          ? h('button', {
              className: 'p-cal__source-btn', type: 'button', text: '在浏览器中查看教务处校历',
            })
          : null,
        h('p', { className: 'p-cal__source-note', text: '教学周按校历 semesterStart 逐周推算；第 18、19 周为复习考试周，不排课。' })
      );
    }

    /** 主绘制：校历未加载 / 正常两态 */
    function paint() {
      const { todayIso, calendarJson } = anchor();
      if (!calendarJson) {
        view.loading.hidden = false;
        view.loading.replaceChildren(
          emptyState({
            kind: 'error',
            title: '校历还没加载好',
            description: '校历数据在启动时读取，返回上一页重新进入即可',
          })
        );
        return;
      }
      view.loading.hidden = true;

      const { semester, header } = resolveSemester(calendarJson, todayIso);
      if (!semester) {
        view.loading.hidden = false;
        view.loading.replaceChildren(emptyState({ kind: 'empty', title: '校历里没有学期数据' }));
        return;
      }

      const currentWeek = header.week;
      const examWeeks = (semester.examWeeks || []).map(Number);
      paintOverview(calendarJson, todayIso, header, semester);
      paintLegend(semester);
      paintGrid(semester, todayIso, currentWeek, examWeeks);
      paintWeeks(semester, currentWeek, examWeeks);
      paintEvents(semester);
      paintSource(calendarJson);
    }

    /** 月份切换同时写回 ui.calendarMonth，离开再回来还在同一月 */
    function setMonth(next) {
      view.month = next;
      actions.setUi({ calendarMonth: next });
      paint();
    }

    const onPrev = () => setMonth(shiftMonth(view.month, -1, anchor().monthOfToday));
    const onNext = () => setMonth(shiftMonth(view.month, 1, anchor().monthOfToday));
    const onBack = () => setMonth(anchor().monthOfToday);
    const onSource = (event) => {
      const button = event.target.closest('.p-cal__source-btn');
      if (!button) return;
      // 地址只从校历 JSON 读，不在 JS 里写外链字面量（check-rules 守卫②）
      const calendarJson = store.getState().term.calendar || {};
      const url = calendarJson.sources && calendarJson.sources.calendar ? calendarJson.sources.calendar : '';
      if (!url) return;
      const bridge = window.NativeBridge; // 外链统一应用内 WebView（2026-11-06 口径），无壳退系统浏览器
      if (bridge && typeof bridge.openWebView === 'function') bridge.openWebView(url);
      else window.open(url, '_blank', 'noopener');
    };

    view.prevBtn.addEventListener('click', onPrev);
    view.nextBtn.addEventListener('click', onNext);
    view.backBtn.addEventListener('click', onBack);
    view.source.addEventListener('click', onSource);

    // bootTerm 把 calendar / context / todayIso 一次性 patch 进 term；订阅整个 term 对象即可
    const unsubscribe = store.subscribe((state) => state.term, () => paint());
    paint();

    return () => {
      unsubscribe();
      view.prevBtn.removeEventListener('click', onPrev);
      view.nextBtn.removeEventListener('click', onNext);
      view.backBtn.removeEventListener('click', onBack);
      view.source.removeEventListener('click', onSource);
    };
  },

  unmount() {
    /* 订阅与监听在 cleanup 里收尾 */
  },
};
