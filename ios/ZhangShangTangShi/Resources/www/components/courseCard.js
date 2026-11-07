/**
 * courseCard.js —— 课程卡（首页今日课程 / 课表网格两处共用）
 *
 * ══ 配色：为什么**不**做「课程名 hash → 预设色板」 ════════════════════════
 * WakeUp 课程表的做法是给课程名算 hash 映射到一组预设色，还允许用户自选色。
 * 我们**保留按课程类型配色**（required / elective / public / practice），原因有两条：
 *   1. 类型有真实信息价值 —— 学生要能一眼分出必修 / 选修 / 公共 / 实践；
 *      hash 出来的颜色是纯装饰，上一学期课也记不住「这个蓝代表什么」。
 *   2. tokens.css 的 --course-* 四色已经过 WCAG 对比度校验（29 对，最低 4.57），
 *      换成 hash 色板等于把这套校验作废，`tools/check-rules.mjs` 守卫⑤ 直接挂。
 * → 后来者请**不要**把「类型配色」当成「不够好看」改掉。
 *
 * ══ 信息层级：网格卡只有两级 ═══════════════════════════════════════════
 * 课表网格里一节只有 46px 高、320px 屏一列才 36px 宽，塞四行字根本读不了。
 * 而且**节次时间在网格里是冗余的** —— 卡片在哪个格子就代表哪节课，
 * 再写一遍「第 1-2 节 08:00-09:35」纯属浪费。
 * 所以网格卡只留：课程名（视觉焦点，最多折 2 行）+ 地点（次要色小字）。
 * 教师**不上网格卡**：学生扫课表是在找「在哪上」，不是「谁教的」；
 * 教师、节次、课程号这些都收进详情层（见 pages/timetableView.js 的 detailNodes）。
 * 首页的今日课程卡仍要带时间与教师（PRD F-03 明文要求），所以这里靠 options 区分，
 * 而不是给网格另写一个组件。
 *
 * 卡是 <button> 而不是 <div>：原生键盘可达、读屏播报为按钮，44px 热区由 CSS 保证。
 * 点击不在这里处理，交给页面的委托（ARCHITECTURE §8.6）。
 */

import { h } from '../core/dom.js';

/** 课程类型 → 中文标签（配色由 CSS 的 .c-course--xxx 负责） */
export const COURSE_TYPE_LABEL = Object.freeze({
  required: '必修',
  elective: '选修',
  public: '公共',
  practice: '实践',
});

/**
 * 把节次数组压成 '第 1-2 节' / '第 1,3 节'。
 * @param {number[]} sections
 * @returns {string}
 */
function sectionText(sections) {
  const list = Array.isArray(sections) ? sections.filter((n) => Number.isFinite(Number(n))) : [];
  if (list.length === 0) return '';
  const sorted = list.slice().sort((a, b) => a - b);
  const isRange = sorted.every((n, i) => i === 0 || n === sorted[i - 1] + 1);
  return isRange && sorted.length > 1 ? `第 ${sorted[0]}-${sorted[sorted.length - 1]} 节` : `第 ${sorted.join(',')} 节`;
}

/**
 * 单张课程卡。
 *
 * @param {{
 *   id: string, name: string, location?: string, teacher?: string,
 *   sections?: number[], startTime?: string, endTime?: string,
 *   courseType?: 'required'|'elective'|'public'|'practice', weeksText?: string
 * }} course
 * @param {{
 *   conflict?: boolean,  // 同格多课：只加语义类 + 读屏标记，三角由网格容器画（CSS）
 *   dim?: boolean,       // 非本周课程半透明（当前无使用者，见 CSS 注释）
 *   compact?: boolean,
 *   showTime?: boolean,  // false = 网格卡，不渲染节次时间
 *   parity?: string,     // 'odd' / 'even'：写进读屏标签；网格上的可见角标由容器画
 *   action?: string
 * }} [options]
 * @returns {HTMLElement}
 */
export function courseCard(course, options = {}) {
  const type = COURSE_TYPE_LABEL[course && course.courseType] ? course.courseType : 'required';
  const name = (course && course.name) || '未命名课程';
  const showTime = options.showTime !== false;
  const parityText = options.parity === 'odd' ? '单周' : options.parity === 'even' ? '双周' : '';
  const timeText =
    course && course.startTime && course.startTime !== '--:--'
      ? `${course.startTime}-${course.endTime || ''}`.replace(/-$/, '')
      : '';
  const sections = sectionText(course && course.sections);

  const classes = ['c-course', `c-course--${type}`];
  if (options.conflict) classes.push('c-course--conflict');
  if (options.dim) classes.push('c-course--dim');
  if (options.compact) classes.push('c-course--compact');

  // 地点在前、教师在后；网格卡传 teacher: '' 就只剩地点
  const metaLine = [course && course.location, course && course.teacher].filter(Boolean).join(' · ');

  const ariaParts = [
    name,
    COURSE_TYPE_LABEL[type],
    parityText,
    options.conflict ? '与另一门课时间冲突' : '',
    sections,
    metaLine,
  ];
  const dataset = {
    courseId: course && course.id ? String(course.id) : '',
    courseType: type,
  };
  if (options.action) dataset.action = options.action;

  return h(
    'button',
    {
      className: classes.join(' '),
      type: 'button',
      dataset,
      'aria-label': ariaParts.filter(Boolean).join('，'),
    },
    [
      h('span', { className: 'c-course__name', text: name }),
      metaLine ? h('span', { className: 'c-course__meta', text: metaLine }) : null,
      showTime && (sections || timeText)
        ? h('span', { className: 'c-course__time', text: [sections, timeText].filter(Boolean).join(' ') })
        : null,
    ]
  );
}
