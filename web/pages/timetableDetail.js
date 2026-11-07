/**
 * timetableDetail.js —— 课表详情层（课程信息 / 冲突切换 / 长按复制）
 *
 * 为什么单独一个文件：`timetable.js` 已经背着周次状态、手势与定时器，接近 400 行；
 * 而这套东西全是「弹层内部」的自洽逻辑，拆出来后两个文件都能读。
 *
 * 三个设计决定：
 *   1. **冲突切换用「左右滑动 + 两个箭头 + 显示这门课」三条路**。
 *      滑动是快路径（WakeUp 的手感），箭头保证键盘/读屏也能切，
 *      「显示这门课」把用户的选择回写给网格 —— 否则用户每次滚到这格都要重新翻一遍。
 *   2. **长按复制挂在弹层内容容器上，不挂在每段文本上**。容器在整个弹层生命周期里不换，
 *      换课程只换子节点，所以长按监听只绑一次、不会随切换泄漏。
 *   3. **复制内容取「当前正在看的那门课」**，不是第一次点开的那门 ——
 *      翻到第二门再长按，复制的必须是第二门（这是最容易写错的一处）。
 */

import { h } from '../core/dom.js';
import { toast } from '../core/toast.js';
import { bindLongPress, writeClipboard } from '../core/clipboard.js';
import { createSheet } from '../components/sheet.js';
import { detailNodes, copyTextOf } from './timetableView.js';

/** 滑动切课的最小位移（再小就是手抖） */
const SWIPE_MIN = 40;

/**
 * 建详情层。
 *
 * @param {{
 *   onShowCourse?: ((course: object) => void)|null
 * }} [options] `onShowCourse`：用户点「显示这门课」时回调，由页面把该课设为主显
 * @returns {{
 *   open: (courses: object[], index?: number) => Promise<void>,
 *   close: () => Promise<void>,
 *   isOpen: () => boolean,
 *   destroy: () => void
 * }}
 */
export function createCourseDetail(options = {}) {
  const sheet = createSheet({ title: '课程详情' });
  /** @type {object[]} */
  let list = [];
  let index = 0;

  const body = sheet.panel.querySelector('.c-sheet__body');

  /** 当前正在看的课程 */
  const current = () => list[index] || null;

  /** 切换条上的标签：只标周次性质，不写「时间冲突」这类解释 */
  const parityLabel = (course) => {
    if (course.isPlaceholder) return '板块课';
    if (course.weekParity === 'odd') return '单周';
    if (course.weekParity === 'even') return '双周';
    return '每周';
  };

  /** 长按复制：取当前这门课，格式 `课程名 地点 教师 周次` */
  const longPress = bindLongPress(body, async () => {
    const course = current();
    if (!course) return;
    const ok = await writeClipboard(copyTextOf(course));
    toast(ok ? '已复制' : '复制失败，请手动选择文本', ok ? 'success' : 'warning');
  });

  /**
   * 重画弹层内容。`courses` 只有 1 门时不画切换控件。
   * @param {boolean} [animate] 切换课程时给一次轻微的淡入，让「翻页了」有反馈
   */
  function render(animate = false) {
    const course = current();
    if (!course) return;
    sheet.setTitle(course.name);

    const nodes = detailNodes(course);

    if (list.length > 1) {
      nodes.unshift(
        h('div', { className: 'p-tt__pager' }, [
          h('button', {
            className: 'p-tt__pager-nav',
            type: 'button',
            'aria-label': '上一门课',
            html: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14.5 6 9 12l5.5 6"/></svg>',
            disabled: index === 0,
          }),
          h('span', {
            className: 'p-tt__pager-count',
            text: `${index + 1} / ${list.length} · ${parityLabel(course)}`,
          }),
          h('button', {
            className: 'p-tt__pager-nav',
            type: 'button',
            'aria-label': '下一门课',
            html: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9.5 6 15 12l-5.5 6"/></svg>',
            disabled: index === list.length - 1,
          }),
        ])
      );
      nodes.push(h('p', {
        className: 'p-tt__pager-hint',
        text: '左右滑动或点箭头切换这一格的课程',
      }));
      // 本周不上课的「另一半」不能被设成网格主显，否则切周后会指向一门不存在的课
      if (!current().offWeek) {
        nodes.push(h('button', {
          className: 'p-tt__show-btn',
          type: 'button',
          text: '显示这门课',
        }));
      }
    }

    nodes.push(h('p', { className: 'p-tt__copy-hint', text: '长按此区域可复制课程信息' }));

    sheet.setContent(nodes);
    if (animate && typeof body.animate === 'function') {
      body.animate(
        [
          { opacity: 0, transform: 'translate3d(0, 4px, 0)' },
          { opacity: 1, transform: 'translate3d(0, 0, 0)' },
        ],
        { duration: 250, easing: 'cubic-bezier(0.2, 0, 0, 1)' }
      );
    }
  }

  /** 翻到指定下标（夹到合法范围，越界不动） */
  function go(next, animate = true) {
    if (next < 0 || next >= list.length || next === index) return;
    index = next;
    render(animate);
  }

  /* ── 箭头 / 显示这门课（事件委托，切课后重建的按钮不用重新绑定）────────── */

  const onClick = (event) => {
    const target = event.target instanceof Element ? event.target : null;
    if (!target) return;
    if (target.closest('.p-tt__pager-nav')) {
      const btns = [...body.querySelectorAll('.p-tt__pager-nav')];
      const at = btns.indexOf(target.closest('.p-tt__pager-nav'));
      go(at === 0 ? index - 1 : index + 1);
      return;
    }
    const showBtn = target.closest('.p-tt__show-btn');
    if (showBtn) {
      const course = current();
      if (course && typeof options.onShowCourse === 'function') options.onShowCourse(course);
      sheet.close('api');
    }
  };
  body.addEventListener('click', onClick);

  /* ── 左右滑动切课 ───────────────────────────────────────────────────── */

  let startX = 0;
  let startY = 0;
  let tracking = false;

  const onTouchStart = (event) => {
    const touch = event.touches[0];
    if (!touch || list.length < 2) return;
    tracking = true;
    startX = touch.clientX;
    startY = touch.clientY;
  };

  const onTouchEnd = (event) => {
    if (!tracking) return;
    tracking = false;
    const touch = event.changedTouches[0];
    if (!touch) return;
    const dx = touch.clientX - startX;
    const dy = touch.clientY - startY;
    // 纵向位移更大 = 用户在下拉关闭弹层，不抢这个手势
    if (Math.abs(dx) < SWIPE_MIN || Math.abs(dx) < Math.abs(dy) * 1.4) return;
    go(dx < 0 ? index + 1 : index - 1);
  };

  const onTouchCancel = () => {
    tracking = false;
  };

  body.addEventListener('touchstart', onTouchStart, { passive: true });
  body.addEventListener('touchend', onTouchEnd);
  body.addEventListener('touchcancel', onTouchCancel);

  return {
    /**
     * @param {object[]} courses 同格的全部课程（1 门时就是普通详情）
     * @param {number} [startIndex] 从哪一门开始看
     */
    async open(courses, startIndex = 0) {
      list = Array.isArray(courses) ? courses.filter(Boolean) : [];
      index = Math.min(Math.max(0, startIndex), Math.max(0, list.length - 1));
      render(false);
      await sheet.open();
    },
    close: () => sheet.close('api'),
    isOpen: () => sheet.isOpen(),
    destroy() {
      body.removeEventListener('click', onClick);
      body.removeEventListener('touchstart', onTouchStart);
      body.removeEventListener('touchend', onTouchEnd);
      body.removeEventListener('touchcancel', onTouchCancel);
      longPress.destroy();
      sheet.destroy();
    },
  };
}
