/**
 * gradeRow.js —— 成绩列表行
 *
 * 一行信息密度很高，所以分三层：主信息（课程名 + 学分/类型/考核）、备注徽标（重修/缓考/
 * 不及格）、右侧成绩与绩点。`score` 为 null 时显示「未录入」——这是演示数据里真实存在的
 * 状态，不许显示成 0。
 *
 * 长按复制：手指按住 500ms 复制一行纯文本成绩，用于「发给同学 / 记到备忘录」。
 * 手势与写剪贴板都在 `core/clipboard.js`（课表详情层用的是同一套），这里只提供复制内容。
 *
 * 共享元素：课程名带 `data-shared`，进详情页时由页面做一次名字过渡（ARCHITECTURE §4.7）。
 */

import { h } from '../core/dom.js';
import { toast } from '../core/toast.js';
import { bindLongPress, writeClipboard } from '../core/clipboard.js';

/** 课程类型中文（与 courseCard 同口径，这里不 import 它以免两个组件互相依赖） */
const TYPE_LABEL = { required: '必修', elective: '选修', public: '公共', practice: '实践' };
const ASSESS_LABEL = { exam: '考试', check: '考查' };

/**
 * 拼一行可复制的成绩文本：`课程名 学分 成绩 绩点`（如 `中国古代文学（二） 5 88 3.7`）。
 * 未录入/缓考时对应位置给中文，不用空串，粘到备忘录里也读得懂。
 * @param {object} grade
 * @returns {string}
 */
function copyTextOf(grade) {
  const score = grade.score === null || grade.score === undefined
    ? grade.remark || '成绩未录入'
    : String(grade.score);
  const gp = grade.gradePoint === null || grade.gradePoint === undefined ? '--' : String(grade.gradePoint);
  return [grade.courseName, String(grade.credits), score, gp].join(' ');
}

/**
 * 建一行成绩。
 *
 * @param {{
 *   id: string, courseName: string, credits: number, score: number|null,
 *   scoreText?: string, gradePoint: number|null, courseType?: string,
 *   assessment?: string, remark?: string, isRetake?: boolean
 * }} grade
 * @param {{
 *   action?: string,
 *   showType?: boolean,
 *   onCopy?: ((text: string) => void)|null,
 *   onClick?: ((grade: object) => void)|null
 * }} [options]
 * @returns {HTMLElement}
 */
export function gradeRow(grade, options = {}) {
  const missing = grade.score === null || grade.score === undefined;
  /** 未录入 ≠ 缓考：缓考是「考了但顺延」，必须原样显示备注，不能都写成「未录入」 */
  const pendingLabel = grade.remark === '缓考' || grade.remark === '缺考' ? grade.remark : '未录入';
  /** 60 分是及格线，只有 < 60 才标红（压线不许误伤） */
  const failed = !missing && Number(grade.score) < 60;
  const remark = missing ? '' : grade.remark || (grade.isRetake ? '重修' : '');
  const typeText = options.showType === false ? '' : TYPE_LABEL[grade.courseType] || '';
  const assessText = ASSESS_LABEL[grade.assessment] || '';
  const meta = [`${grade.credits} 学分`, typeText, assessText].filter(Boolean).join(' · ');

  const dataset = { gradeId: grade.id ? String(grade.id) : '' };
  if (options.action) dataset.action = options.action;

  const row = h(
    'button',
    {
      className: `c-grade${missing ? ' c-grade--missing' : ''}${failed ? ' c-grade--fail' : ''}`,
      type: 'button',
      dataset,
      'aria-label': copyTextOf(grade),
    },
    [
      h('span', { className: 'c-grade__main' }, [
        h('span', {
          className: 'c-grade__name',
          text: grade.courseName,
          dataset: { shared: `grade:${grade.id || ''}` },
        }),
        h('span', { className: 'c-grade__meta', text: meta }),
        remark
          ? h('span', {
              className: `c-grade__remark${remark === '重修' ? ' c-grade__remark--retake' : ''}`,
              text: remark,
            })
          : null,
      ]),
      h('span', { className: 'c-grade__score' }, [
        h('span', {
          className: `c-grade__score-value${missing ? ' is-missing' : ''}${failed ? ' is-fail' : ''}`,
          text: missing ? pendingLabel : String(grade.score),
        }),
        // 文字成绩（优秀/良好）与分数并列给：95 · 优秀
        grade.scoreText && !missing
          ? h('span', { className: 'c-grade__score-tag', text: `· ${grade.scoreText}` })
          : null,
        h('span', {
          className: 'c-grade__gp',
          text: grade.gradePoint === null || grade.gradePoint === undefined
            ? '绩点 --'
            : `绩点 ${grade.gradePoint}`,
        }),
      ]),
    ]
  );

  /* ── 长按复制 ─────────────────────────────────────────────────────────── */

  const doCopy = async () => {
    const text = copyTextOf(grade);
    if (typeof options.onCopy === 'function') {
      options.onCopy(text);
    } else {
      const ok = await writeClipboard(text);
      toast(ok ? '已复制' : '复制失败，请手动选择文本', ok ? 'success' : 'warning');
    }
  };

  const longPress = bindLongPress(row, doCopy);

  const onClick = (event) => {
    if (longPress.consume()) {
      // 复制完的这次点击不该跳详情，吃掉
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    if (typeof options.onClick === 'function') options.onClick(grade);
  };

  row.addEventListener('click', onClick);

  return row;
}
