/**
 * timetableView.js —— 课表页的 DOM 拼装（网格 / 详情层内容）
 *
 * 与 `timetable.js` 的分工：这里只回答「长什么样」，不碰 store、不绑事件；
 * 页面那边负责周次状态、手势、定时重算与弹层生命周期。拆开也是为了让两个文件都 ≤400 行。
 *
 * 三处不能想当然的地方（WakeUp 课表对照后改的）：
 *   1. **一格只画一门课**。旧版把同格两门课各占 50% 并排，在 36px 宽的列里等于不能用。
 *      现在画「主课」一门，右下角画一个 CSS 三角表示这格还有别的课，
 *      详情层里左右滑动切换（timetableDetail.js）。
 *   2. **卡片只有课程名 + 地点**。教师、节次时间、课程号都不上网格卡 ——
 *      卡片在哪个格子就代表哪节课，时间再写一遍是冗余；学生扫一眼是找「在哪上」。
 *   3. **「进行中」是独立的 DOM 层**，不靠整格重绘：定时器只调 `markNow()` 加/删
 *      `is-now` 一个类，避免每 60 秒把整张网格重建一次（会打断滚动位置与入场动画）。
 */

import { h } from '../core/dom.js';
import { courseCard, COURSE_TYPE_LABEL } from '../components/courseCard.js';
import { kvList } from '../components/card.js';
import { WEEKDAY_LABELS } from '../data/calendar.js';
import { PERIOD_TIMES, TOTAL_SECTIONS, SEGMENT_STARTS, periodInfo, weeksText, coursesInCell } from './timetableUtils.js';

/**
 * 一格多课时，挑哪一门画在格子里。
 *
 * 规则（按优先级）：
 *   ⓪ 板块占位行（isPlaceholder）排在真实课后面 —— 它只是「这个时段有板块」的提示；
 *   ① 用户手动点过「显示这门课」的那门 → 用用户的选择；
 *   ② 周次覆盖最多的那门 —— 冲突里通常一门是贯穿整个模块的主线课（1-9 周），
 *      一门是插空 / 双周的短课，画主线课更符合「这门课一直在上」的直觉；
 *   ③ 仍然并列则按 id 排序，保证每次重绘画的都是同一门（不会来回闪）。
 *
 * @param {object[]} list 同格的课程（都已在当前周命中）
 * @param {string} [shownId] 用户显式选中的课程 id
 * @returns {object}
 */
export function pickPrimary(list, shownId) {
  if (shownId) {
    const chosen = list.find((course) => course.id === shownId);
    if (chosen) return chosen;
  }
  return list
    .slice()
    .sort((a, b) => {
      const weeksOf = (course) => (Array.isArray(course.weeks) ? course.weeks.length : 0);
      const pseudo = (course) => (course.isPlaceholder ? 1 : 0);
      return pseudo(a) - pseudo(b) || weeksOf(b) - weeksOf(a) || String(a.id).localeCompare(String(b.id));
    })[0];
}

/**
 * 建网格：1 列时间轴 + 7 列星期，12 行节次；课程卡按 `dayOfWeek × sections` 定位。
 *
 * @param {object[]} courses
 * @param {{weekNo: number, todayIndex: number, dates: string[], nowSection?: number,
 *          shown?: Record<string, string>}} view
 *   `shown` 是「冲突格里用户指定的课程」映射，键为 `day-start`；
 *   `nowSection` 为当前时间命中的节次（0 表示不在任何节次内）。
 * @returns {{grid: HTMLElement, cards: HTMLElement[]}}
 */
export function buildGrid(courses, view) {
  const { weekNo, todayIndex, dates, nowSection = 0, shown = {} } = view;
  const grid = h('div', { className: 'p-tt__grid', role: 'grid', 'aria-label': `第 ${weekNo} 周课表` });

  // 表头与背景格子全部**显式定位**：课程卡用的是 grid-row/grid-column 显式坐标，
  // 一旦混进自动排布，自动排布会「跳过被占的格子」把后面的格子整体挤歪（实测会让第 4 节之后的
  // 编号错位、行高乱跳）。全显式之后，第 N 节永远在第 N+1 行。
  grid.appendChild(h('div', { className: 'p-tt__corner', 'aria-hidden': 'true', style: { gridRow: '1', gridColumn: '1' } }));
  WEEKDAY_LABELS.forEach((label, index) => {
    const isToday = index + 1 === todayIndex;
    grid.appendChild(
      h('div', {
        className: `p-tt__day${isToday ? ' is-today' : ''}`,
        role: 'columnheader',
        style: { gridRow: '1', gridColumn: String(index + 2) },
      }, [
        h('span', { className: 'p-tt__day-label', text: label }),
        dates[index] ? h('span', { className: 'p-tt__day-date', text: dates[index] }) : null,
      ])
    );
  });

  for (let section = 1; section <= TOTAL_SECTIONS; section += 1) {
    const seg = SEGMENT_STARTS.has(section) ? ' is-seg' : '';
    // 偶数节次行加极淡斑马纹：第 1 周只有 16 门课、84 个格子空一大片，纯白底分不清行列。
    // 只加在背景格上（课程卡是网格里的另一个元素，盖在上面），见 pagesTimetable.css。
    const zebra = section % 2 === 0 ? ' is-even' : '';
    const row = String(section + 1);
    grid.appendChild(
      h('div', {
        className: `p-tt__slot${seg}`,
        role: 'rowheader',
        dataset: { section: String(section) },
        style: { gridRow: row, gridColumn: '1' },
      }, [
        h('span', { className: 'p-tt__slot-no', text: String(section) }),
        // 偶数节次不重复标时间，避免 30px 宽的栏位塞满数字
        section % 2 === 1 ? h('span', { className: 'p-tt__slot-time', text: PERIOD_TIMES[section - 1][0] }) : null,
      ])
    );
    for (let day = 1; day <= 7; day += 1) {
      grid.appendChild(
        h('div', {
          className: `p-tt__cell${day === todayIndex ? ' is-today' : ''}${seg}${zebra}`,
          role: 'gridcell',
          style: { gridRow: row, gridColumn: String(day + 1) },
        })
      );
    }
  }

  // 本周的课：只认 weeks（唯一真源），weekRange / weekParity 一概不读
  const inWeek = (courses || []).filter(
    (course) =>
      course &&
      Array.isArray(course.weeks) &&
      course.weeks.includes(weekNo) &&
      course.dayOfWeek >= 1 &&
      course.dayOfWeek <= 7
  );

  const buckets = new Map();
  for (const course of inWeek) {
    const key = `${course.dayOfWeek}-${Math.min(...course.sections)}`;
    if (!buckets.has(key)) buckets.set(key, []);
    buckets.get(key).push(course);
  }

  const cells = [...buckets.entries()]
    .map(([key, list]) => ({ key, list, start: Number(key.split('-')[1]), day: Number(key.split('-')[0]) }))
    .sort((a, b) => a.day - b.day || a.start - b.start);

  const cards = [];
  for (const cell of cells) {
    const primary = pickPrimary(cell.list, shown[cell.key]);
    const start = Math.min(...primary.sections);
    const span = Math.max(...primary.sections) - start + 1;
    // 只有「本周都在这格、节次重叠」才是真冲突；周次互补的单/双周课另标轮换
    const { relation } = coursesInCell(courses, cell.day, cell.start, weekNo);
    const conflict = relation === 'conflict';

    const card = courseCard(
      // 教师不上网格卡：36px 宽的列放不下，而且学生扫课表找的是「在哪上」（理由见 courseCard.js）
      { ...primary, teacher: '' },
      { showTime: false, conflict, parity: primary.weekParity, multi: relation !== 'single' }
    );
    card.classList.add('p-tt__card');
    cards.push(card);

    const holder = h('div', {
      className: `p-tt__gridcard${conflict ? ' is-conflict' : ''}${relation !== 'single' ? ' is-multi' : ''}`,
      dataset: {
        day: String(cell.day),
        start: String(start),
        span: String(span),
        courseId: String(primary.id),
        conflictCount: String(cell.list.length),
        relation,
      },
      style: { gridRow: `${start + 1} / span ${span}`, gridColumn: String(cell.day + 1) },
    }, [
      // 「进行中」角标：常驻 DOM，平时 display:none，由 markNow() 切父元素的 is-now 显隐。
      // 常驻而不是每次重建，是为了让定时器只改一个 class，不动布局。
      h('span', { className: 'p-tt__live', text: '进行中' }),
      card,
    ]);

    grid.appendChild(holder);
  }

  if (nowSection) markNow(grid, nowSection, todayIndex);
  return { grid, cards };
}

/**
 * 只更新「当前正在上」的标记：左侧强调条 + 卡片角标。
 * 每次先清掉旧标记再打新标记 —— 跨节次边界时两个动作必须原子发生，
 * 否则会出现「两行都有强调条」的中间态。
 *
 * @param {HTMLElement} grid buildGrid 产出的网格
 * @param {number} section 当前节次（1-12，0 = 没有）
 * @param {number} todayIndex 今天在第几列（0 = 不是本周 / 今天不在列里）
 */
export function markNow(grid, section, todayIndex) {
  if (!grid) return;
  for (const el of grid.querySelectorAll('.is-now')) el.classList.remove('is-now');
  if (!section || !todayIndex) return;

  const slot = grid.querySelector(`.p-tt__slot[data-section="${section}"]`);
  if (slot) slot.classList.add('is-now');

  for (const holder of grid.querySelectorAll('.p-tt__gridcard')) {
    const start = Number(holder.dataset.start);
    const span = Number(holder.dataset.span) || 1;
    const day = Number(holder.dataset.day);
    if (day === todayIndex && section >= start && section < start + span) holder.classList.add('is-now');
  }
}

/**
 * 详情层内容（课名由弹层标题给，这里只放属性行与层级说明）。
 * @param {object} course
 * @returns {Node[]}
 */
export function detailNodes(course) {
  const info = periodInfo(course.sections);
  const parity = course.weekParity === 'odd' ? '单周' : course.weekParity === 'even' ? '双周' : '';
  const nodes = [
    h('p', {
      className: 'p-tt__detail-meta',
      text: [
        parity,
        COURSE_TYPE_LABEL[course.courseType] || '课程',
        `${course.credits} 学分`,
        course.assessment === 'exam' ? '考试' : '考查',
        course.campus || '',
      ]
        .filter(Boolean)
        .join(' · '),
    }),
    kvList([
      ['教师', course.teacher || '—'],
      ['地点', course.location || '未安排'],
      ['节次', `${info.sections} · ${info.time}`],
      ['周次', weeksText(course.weeks)],
      ['课程号', course.code || '—', { mono: true }],
      ['教学班', course.classNo || '—', { mono: true }],
    ]),
  ];
  // 单双周轮换的「另一半」：本周不上，但要说清楚它什么时候上，别让人以为课丢了
  if (course.offWeek) {
    nodes.unshift(h('p', {
      className: 'p-tt__offweek',
      text: `本周无课 · ${weeksText(course.weeks)} 上课`,
    }));
  }
  // 板块课占位行：教务只说「这个时段有板块」，没排具体地点
  if (course.isPlaceholder) {
    nodes.unshift(h('p', {
      className: 'p-tt__notice',
      text: '板块课 · 具体模块以自选结果为准',
    }));
  }
  return nodes;
}

/**
 * 详情层的「可复制文本」：`课程名 地点 教师 周次`。
 * 顺序按 WakeUp 文档给的样例来 —— 粘贴到聊天里，对方先看课名再看地点，最顺。
 * @param {object} course
 * @returns {string}
 */
export function copyTextOf(course) {
  return [
    course.name || '',
    course.location || '未安排',
    course.teacher || '—',
    weeksText(course.weeks),
  ].join(' ');
}
