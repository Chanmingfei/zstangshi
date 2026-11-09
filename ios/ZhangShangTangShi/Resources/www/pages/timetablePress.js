/**
 * timetablePress.js —— 「长按空白格新建课程」的触摸判定
 *
 * 为什么单独一个文件：它和 `bindWeekSwipe` 都从 touchstart 起步，是最容易互相打架的一对。
 * 拆出来后两边的判定条件可以分开读、分开改：
 *   · 横滑：位移 > 48px 且 |dx| > |dy| * 1.2（见 timetableSwipe.js）
 *   · 长按：500ms 内位移不超过 10px，且落点在空白格上（课程卡上的长按归详情层做复制）
 */

const PRESS_MS = 500;
const MOVE_TOLERANCE = 10;

/**
 * @param {HTMLElement} container
 * @param {{onPress?: (cell: HTMLElement) => void, delay?: number}} [options]
 * @returns {() => void} 解绑（同时清掉未触发的定时器）
 */
export function bindCellPress(container, options = {}) {
  const delay = Number(options.delay) || PRESS_MS;
  let timer = null;
  let origin = null;

  const cancel = () => {
    if (timer) window.clearTimeout(timer);
    timer = null;
    origin = null;
  };

  const onStart = (event) => {
    const touch = event.touches[0];
    const target = event.target instanceof Element ? event.target : null;
    const cell = target ? target.closest('.p-tt__cell') : null;
    // 课程卡上的长按留给详情层（复制课表文案），不在这里建课
    if (!touch || !cell || (target && target.closest('.p-tt__gridcard'))) return;
    origin = { x: touch.clientX, y: touch.clientY };
    timer = window.setTimeout(() => {
      timer = null;
      origin = null;
      if (typeof options.onPress === 'function') options.onPress(cell);
    }, delay);
  };

  const onMove = (event) => {
    if (!timer || !origin) return;
    const touch = event.touches[0];
    if (!touch) return;
    if (Math.abs(touch.clientX - origin.x) > MOVE_TOLERANCE || Math.abs(touch.clientY - origin.y) > MOVE_TOLERANCE) {
      cancel();
    }
  };

  container.addEventListener('touchstart', onStart, { passive: true });
  container.addEventListener('touchmove', onMove, { passive: true });
  container.addEventListener('touchend', cancel);
  container.addEventListener('touchcancel', cancel);

  return () => {
    cancel();
    container.removeEventListener('touchstart', onStart);
    container.removeEventListener('touchmove', onMove);
    container.removeEventListener('touchend', cancel);
    container.removeEventListener('touchcancel', cancel);
  };
}
