/**
 * tabBar.js —— 底部悬浮胶囊 Dock（4 Tab）
 *
 * 设计语言照搬烫梨（github.com/augustlies/tangli）的 CapsuleDock：
 *   · 整条是一颗**浮起来的胶囊**：左右留白 + 离底一段距离 + 发丝描边 + 一层浮起阴影
 *   · 选中块是胶囊内部再内缩一圈的**小胶囊**，不是下划线、不是整格变色
 *   · 切换用 **320ms + 回弹缓动**（--ease-spring），先冲过头再落位
 *   · **按住左右拖**：小胶囊跟着手指走、横向拉长 6%、经过一格轻震一下，
 *     松手吸附到最近的那格并切过去 —— 手指不用精准点中
 *   · 按在别的格上时先浮出一颗很淡的「幽灵胶囊」，预告松手会去哪
 *
 * 与烫梨的差异（按我们的信息密度调整）：
 *   · 烫梨只有 3 项、左图标右文字；我们有 4 项，改成**图标在上、文字在下**，
 *     横向不至于被文字撑开，胶囊也能保持一个合理高度
 *   · 选中色用品牌蓝（烫梨是纸墨主题的墨色），与 App 其余选中态同一套语义
 *
 * 热区：每个 Tab 仍是 flex:1 的 44px 高按钮，拖动手势只是额外增益，不替代点击
 * （读屏与键盘走的仍是原生 button 的 click）。
 *
 * 本文件只 import core/ —— 不碰 data。
 */

import { h } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { slideTo, forgetSlide } from '../core/slideTo.js';

/** 四个 Tab 的定义（顺序即渲染顺序）。`icon` 是 core/icons.js 里的图标名。 */
export const TAB_ITEMS = [
  { key: 'home', label: '首页', path: '/home', icon: 'home' },
  { key: 'timetable', label: '课表', path: '/timetable', icon: 'timetable' },
  { key: 'grades', label: '成绩', path: '/grades', icon: 'grades' },
  { key: 'mine', label: '我的', path: '/mine', icon: 'mine' },
];

/** 横向位移超过这么多像素、且明显比纵向大，才判定为「拖动切换」 */
const DRAG_MIN = 12;
/** 拖动时小胶囊横向拉长的比例（烫梨是 1.06） */
const STRETCH = 1.06;

/**
 * 建底部 Dock。
 *
 * @param {{active?: string, onSelect?: (key: string, path: string) => void}} [options]
 * @returns {{el: HTMLElement, setActive: (key: string, instant?: boolean) => void, destroy: () => void}}
 */
export function createTabBar(options = {}) {
  const { onSelect = null } = options;
  let activeKey = options.active || TAB_ITEMS[0].key;

  /** 拖动中压住的格（松手才真正切换） */
  let dragging = false;
  let pressedSlot = -1;
  /** 拖动结束后紧跟的那次 click 要吞掉，否则会再切一次 */
  let swallowClick = false;
  let downX = 0;
  let downY = 0;
  let lastHover = -1;

  /** @type {Map<string, HTMLButtonElement>} */
  const buttons = new Map();

  const pill = h('span', { className: 'c-dock__pill', 'aria-hidden': 'true' });
  const ghost = h('span', { className: 'c-dock__ghost', 'aria-hidden': 'true' });
  const dock = h('div', { className: 'c-dock', role: 'tablist', 'aria-label': '主导航' }, [ghost, pill]);

  for (const item of TAB_ITEMS) {
    const button = h(
      'button',
      {
        className: 'c-dock__item',
        type: 'button',
        role: 'tab',
        'data-tab': item.key,
        'aria-selected': 'false',
        'aria-label': item.label,
        // 必须写驼峰 onClick：h() 的 applyProps 把 onXxx 转成 addEventListener，
        // 早期写成小写 onclick 会被当普通属性 setAttribute 掉，底栏整个点不动。
        onClick: () => {
          if (swallowClick) {
            swallowClick = false;
            return;
          }
          if (item.key === activeKey) {
            // 点当前 Tab 也要有反馈：回到页顶。否则用户会以为「底栏点了没反应」。
            const main = document.getElementById('app-main');
            if (main) main.scrollTo({ top: 0, behavior: 'smooth' });
            return;
          }
          setActive(item.key);
          if (typeof onSelect === 'function') onSelect(item.key, item.path);
        },
      },
      [
        h('span', { className: 'c-dock__icon', html: icon(item.icon) }),
        h('span', { className: 'c-dock__label', text: item.label }),
      ]
    );
    buttons.set(item.key, button);
    dock.appendChild(button);
  }

  /* ── 几何 ───────────────────────────────────────────────────────────── */

  const indexOf = (key) => Math.max(0, TAB_ITEMS.findIndex((item) => item.key === key));

  /**
   * 量出「胶囊内边距盒子」为原点的格宽与首格起点。
   * 用 rect 相减而不是 offsetLeft：offsetLeft 的参照系（边框盒 / 内边距盒）
   * 在各引擎上口径不一致，差 1px 就会让整颗胶囊慢慢跑偏。
   */
  function measure() {
    const first = buttons.get(TAB_ITEMS[0].key);
    if (!first) return null;
    const box = dock.getBoundingClientRect();
    const cell = first.getBoundingClientRect();
    if (!cell.width) return null;
    return { cell: cell.width, base: cell.left - box.left - dock.clientLeft };
  }

  /**
   * 把胶囊摆到 pos（单位「格」，可以是小数）。
   *
   * 位移交给 core/slideTo.js 的 WAAPI 实现：起点取当前渲染位置、按剩余距离
   * 缩短时长，所以连点两下时胶囊从半路直接换向，不会排完上一段再走。
   * 拖动时走 instant（跟手，不能有过渡）。
   *
   * @param {number} pos
   * @param {{instant?: boolean, stretch?: number}} [opts]
   */
  function placePill(pos, opts = {}) {
    const geo = measure();
    if (!geo) return;
    const stretch = Number(opts.stretch) || 1;
    const width = geo.cell * stretch;
    // 拉长时左右各溢出一点，视觉重心仍落在格心
    const x = geo.base + geo.cell * pos - (width - geo.cell) / 2;
    slideTo(pill, { x, width }, { instant: opts.instant, unit: geo.cell });
  }

  /** 幽灵胶囊：按在别的格上时预告松手落点 */
  function placeGhost(slot) {
    const geo = measure();
    if (!geo) return;
    ghost.style.width = `${geo.cell}px`;
    ghost.style.transform = `translate3d(${(geo.base + geo.cell * slot).toFixed(2)}px, 0, 0)`;
    ghost.classList.toggle('is-on', slot >= 0);
  }

  /* ── 选中态 ─────────────────────────────────────────────────────────── */

  /**
   * 切换高亮。
   * @param {string} key
   * @param {boolean} [instant] 首帧 / 尺寸变化时不走动画
   */
  function setActive(key, instant = false) {
    if (!buttons.has(key)) return;
    activeKey = key;
    for (const [tabKey, button] of buttons) {
      const selected = tabKey === key;
      if (button.getAttribute('aria-selected') === String(selected)) continue;
      button.setAttribute('aria-selected', String(selected));
      button.classList.toggle('is-active', selected);
    }
    placePill(indexOf(key), { instant });
  }

  /* ── 拖动手势 ───────────────────────────────────────────────────────── */

  /** 手指 x 落在哪一格（小数，夹在 0..n-1） */
  function slotAt(clientX) {
    const geo = measure();
    if (!geo) return 0;
    const raw = (clientX - dock.getBoundingClientRect().left - dock.clientLeft - geo.base) / geo.cell - 0.5;
    return Math.max(0, Math.min(TAB_ITEMS.length - 1, raw));
  }

  const onPointerDown = (event) => {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    downX = event.clientX;
    downY = event.clientY;
    dragging = false;
    swallowClick = false;
    pressedSlot = Math.round(slotAt(event.clientX));
    lastHover = indexOf(activeKey);
    if (pressedSlot !== lastHover) placeGhost(pressedSlot);
    window.addEventListener('pointermove', onPointerMove, { passive: true });
    window.addEventListener('pointerup', onPointerUp);
    window.addEventListener('pointercancel', onPointerUp);
  };

  const onPointerMove = (event) => {
    const dx = Math.abs(event.clientX - downX);
    const dy = Math.abs(event.clientY - downY);
    // 轻微抖动仍算点按；明显横着拖才接管，避免和页面横滑打架
    if (!dragging) {
      if (dx <= DRAG_MIN || dx <= dy * 1.5) return;
      dragging = true;
      pressedSlot = -1;
      placeGhost(-1);
      dock.classList.add('is-dragging');
    }
    const pos = slotAt(event.clientX);
    // 拖动必须跟手：instant 跳过动画，直接跟着手指落位
    placePill(pos, { stretch: STRETCH, instant: true });
    const hover = Math.round(pos);
    if (hover !== lastHover) {
      lastHover = hover;
      buzz();
    }
  };

  const onPointerUp = (event) => {
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerup', onPointerUp);
    window.removeEventListener('pointercancel', onPointerUp);

    if (!dragging) {
      placeGhost(-1);
      pressedSlot = -1;
      return;
    }

    dragging = false;
    dock.classList.remove('is-dragging');
    const pos = slotAt(event.clientX);
    const index = Math.round(pos);
    const key = TAB_ITEMS[index].key;
    placePill(index);
    if (key !== activeKey) {
      setActive(key);
      // 手指从 A 划到 B 时，click 会落在共同祖先上；这里兜住少数会把 click
      // 派到 A 上的情况，免得刚切到 B 又被 A 的 click 切回来。
      swallowClick = true;
      setTimeout(() => { swallowClick = false; }, 0);
      if (typeof onSelect === 'function') onSelect(key, TAB_ITEMS[index].path);
    } else {
      placePill(indexOf(activeKey));
    }
    pressedSlot = -1;
    lastHover = index;
  };

  /** 经过一格轻震一下：WebView 里大概率静默失败，失败就算了 */
  function buzz() {
    try {
      if (navigator.vibrate) navigator.vibrate(6);
    } catch {
      /* 没有振动权限的 WebView 会抛，忽略 */
    }
  }

  dock.addEventListener('pointerdown', onPointerDown);

  // 窗口尺寸变化（横竖屏切换 / 软键盘）后重新对位，不做动画
  const onResize = () => {
    placePill(indexOf(activeKey), { instant: true });
    placeGhost(-1);
  };
  window.addEventListener('resize', onResize);

  // 首帧对位：等元素进文档有宽度之后再量。登录页 Tab 是隐藏的，
  // 那时量不到宽度，导航到首屏时 app.js 会再 setActive 一次。
  requestAnimationFrame(() => setActive(activeKey, true));

  return {
    el: dock,
    setActive,
    destroy() {
      dock.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
      window.removeEventListener('pointercancel', onPointerUp);
      window.removeEventListener('resize', onResize);
      forgetSlide(pill);
      dock.remove();
    },
  };
}
