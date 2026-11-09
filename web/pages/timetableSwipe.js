/**
 * timetableSwipe.js —— 课表左右滑动切周
 *
 * 从 `timetable.js` 抽出：手势判定是独立的一块，且要和「长按空白格新建」共存 ——
 * 两者都在 touchstart 上起步，靠主轴比例区分（横滑切周、原地长按建课）。
 *
 * 判定规则（沿用原实现，实测有效）：
 *   · 第一次位移超过 8px 才定主轴，避免手抖把竖滑判成横滑；
 *   · |dx| > |dy| * 1.2 才算横滑；
 *   · 达到 48px 就切一周，一次手势只切一周。
 */

const SWIPE_THRESHOLD = 48;
const AXIS_RATIO = 1.2;
const AXIS_MIN = 8;

/**
 * 绑定滑动切周。
 * @param {HTMLElement} el 监听容器（整页）
 * @param {{onSwipe?: (direction: 1|-1) => void}} [options] direction: 1 = 下一周，-1 = 上一周
 * @returns {() => void} 解绑
 */
export function bindWeekSwipe(el, options = {}) {
  let tracking = false;
  let axis = null;
  let startX = 0;
  let startY = 0;

  const onStart = (event) => {
    const touch = event.touches[0];
    if (!touch) return;
    tracking = true;
    axis = null;
    startX = touch.clientX;
    startY = touch.clientY;
  };

  const onMove = (event) => {
    if (!tracking) return;
    const touch = event.touches[0];
    if (!touch) return;
    const dx = touch.clientX - startX;
    const dy = touch.clientY - startY;
    if (!axis) {
      if (Math.abs(dx) < AXIS_MIN && Math.abs(dy) < AXIS_MIN) return;
      axis = Math.abs(dx) > Math.abs(dy) * AXIS_RATIO ? 'x' : 'y';
    }
    if (axis !== 'x') return;
    if (Math.abs(dx) < SWIPE_THRESHOLD) return;
    tracking = false;
    if (typeof options.onSwipe === 'function') options.onSwipe(dx < 0 ? 1 : -1);
  };

  const onEnd = () => {
    tracking = false;
    axis = null;
  };

  el.addEventListener('touchstart', onStart, { passive: true });
  el.addEventListener('touchmove', onMove, { passive: true });
  el.addEventListener('touchend', onEnd);
  el.addEventListener('touchcancel', onEnd);

  return () => {
    el.removeEventListener('touchstart', onStart);
    el.removeEventListener('touchmove', onMove);
    el.removeEventListener('touchend', onEnd);
    el.removeEventListener('touchcancel', onEnd);
  };
}
