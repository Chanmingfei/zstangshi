/**
 * charts.js —— 四个手写 SVG 图表（零依赖，不引任何图表库）
 *
 * 为什么手写而不是引库：Chart.js / ECharts 最小包也有 60–200KB，且都要 canvas 或
 * 自己的渲染层；本项目要的只是四种固定形态的图，用 300 行 SVG 字符串拼装足够，还能
 * 直接吃 CSS 变量（深色模式零成本切换）。
 *
 * 统一约定（四个图一致，不许各自发明坐标）：
 *   · viewBox 恒为 `0 0 320 180`，`preserveAspectRatio="xMidYMid meet"`
 *     —— meet 而不是 none：none 会把圆点拉成椭圆、把文字压扁，在 320/390/430 三档宽度下都会露馅
 *   · 颜色全部走 components.css 的 `.c-chart__*` 类，本文件不出现任何色值
 *   · 点击一律 **data-index + 事件委托**：一个图只挂 1 个 listener，
 *     每个点位配一条整列宽的透明命中带（hit band），保证热区远大于 44×44
 *   · 每个点位回调都拿到 `{ label, value, index }`
 */

import { h } from '../core/dom.js';

/** 四个图共用的视口与缩放策略 */
export const CHART_VIEW = Object.freeze({
  width: 320,
  height: 180,
  preserveAspectRatio: 'xMidYMid meet',
});

/** 绘图区内边距（左/右留白给首末标签，下留白给横轴文字） */
const PAD = { left: 10, right: 10, top: 20, bottom: 26 };
const W = CHART_VIEW.width;
const H = CHART_VIEW.height;
const INNER_W = W - PAD.left - PAD.right;
const INNER_H = H - PAD.top - PAD.bottom;

/** clipPath 的 id 需要全局唯一，否则同页多个堆叠条会互相裁切 */
let clipSeed = 0;

/**
 * 数值展示：最多一位小数，整数不补 .0（3.16 就写 3.16，4 就写 4）。
 * @param {number} value
 * @returns {string}
 */
function fmt(value) {
  const num = Number(value);
  if (!Number.isFinite(num)) return '--';
  return Number.isInteger(num) ? String(num) : String(Math.round(num * 100) / 100);
}

/**
 * 建 svg 骨架，内容用字符串一次性灌入。
 * 内容全部由本文件生成（不含用户输入），所以用 innerHTML 是安全的，也避免几百次 createElementNS。
 * @param {string} inner
 * @param {string} label 读屏用的图名
 * @param {string} modifier 形态修饰类（line/bar/ring/stack）
 * @returns {SVGSVGElement}
 */
function buildSvg(inner, label, modifier) {
  const svg = h('svg', {
    className: `c-chart__svg c-chart__svg--${modifier}`,
    viewBox: `0 0 ${CHART_VIEW.width} ${CHART_VIEW.height}`,
    preserveAspectRatio: CHART_VIEW.preserveAspectRatio,
    role: 'img',
    'aria-label': label,
  });
  svg.innerHTML = inner;
  return svg;
}

/**
 * 给图表根节点挂「一个」点击委托。
 * @param {HTMLElement} root
 * @param {Array<{label: string, value: number}>} points
 * @param {((payload: {label: string, value: number, index: number}) => void)|null} handler
 */
function bindPointClick(root, points, handler) {
  if (typeof handler !== 'function') return;
  root.addEventListener('click', (event) => {
    const target = event.target instanceof Element ? event.target.closest('[data-index]') : null;
    if (!target || !root.contains(target)) return;
    const index = Number(target.getAttribute('data-index'));
    const point = points[index];
    if (!point) return;
    // 选中态只换 data-active，样式由 CSS 接管（圆点变大、描边加深）
    for (const node of root.querySelectorAll('[data-active="true"]')) node.removeAttribute('data-active');
    const circle = root.querySelector(`.c-chart__pt[data-index="${index}"]`);
    if (circle) circle.setAttribute('data-active', 'true');
    handler({ label: point.label, value: point.value, index });
  });
}

/**
 * 折线图（通用趋势图）。
 *
 * ⚠️ 当前**没有任何页面在用**（只有 dev/components.html 的示例在用）。
 *    它给 P06 之后的「成绩走势」预留 —— 教务「学生学业情况查询」只给累计 GPA 快照，
 *    没有逐学期 GPA，所以**不要在学分绩点页接折线**（产品已明确删除 GPA 趋势图，
 *    ARCHITECTURE 风险 R11）。要用之前先确认数据里真有逐期序列。
 *
 * @param {{
 *   points: Array<{label: string, value: number}>,
 *   min?: number, max?: number,
 *   unit?: string,
 *   label?: string,
 *   onPointClick?: ((payload: {label: string, value: number, index: number}) => void)|null
 * }} options
 * @returns {HTMLElement}
 */
export function lineChart(options = {}) {
  const raw = Array.isArray(options.points) ? options.points : [];
  const points = raw
    .filter((p) => p && Number.isFinite(Number(p.value)))
    .map((p) => ({ label: String(p.label === undefined ? '' : p.label), value: Number(p.value) }));

  // 数据不足 2 点：画不出趋势，降级成单点柱状（PRD P05 异常态），而不是硬拉一条直线
  if (points.length < 2) {
    return barChart({
      items: points.length === 1 ? points : [],
      label: options.label || '趋势图',
      onPointClick: options.onPointClick,
      empty: '数据不足，暂时画不出趋势',
    });
  }

  const values = points.map((p) => p.value);
  let max = Number.isFinite(Number(options.max)) ? Number(options.max) : Math.max(...values);
  let min = Number.isFinite(Number(options.min)) ? Number(options.min) : Math.min(...values);
  if (max === min) {
    max += 0.5;
    min -= 0.5;
  }
  const span = max - min;
  max += span * 0.18;
  min -= span * 0.18;

  const xOf = (i) => PAD.left + (points.length === 1 ? INNER_W / 2 : (INNER_W * i) / (points.length - 1));
  const yOf = (v) => PAD.top + INNER_H - ((v - min) / (max - min)) * INNER_H;

  const coords = points.map((p, i) => ({ x: xOf(i), y: yOf(p.value) }));

  // 网格线只画 3 条：多了会让 390px 宽的图看起来很脏
  const grid = [0, 0.5, 1]
    .map((t) => {
      const y = PAD.top + INNER_H * t;
      return `<line class="c-chart__grid" x1="${PAD.left}" y1="${y.toFixed(1)}" x2="${PAD.left + INNER_W}" y2="${y.toFixed(1)}"/>`;
    })
    .join('');

  const line = coords.map((c, i) => `${i === 0 ? 'M' : 'L'}${c.x.toFixed(1)},${c.y.toFixed(1)}`).join(' ');
  const area = `${line} L${coords[coords.length - 1].x.toFixed(1)},${(PAD.top + INNER_H).toFixed(1)} L${coords[0].x.toFixed(1)},${(PAD.top + INNER_H).toFixed(1)} Z`;

  const step = Math.max(1, Math.ceil(points.length / 4));
  const labels = points
    .map((p, i) => {
      if (i % step !== 0 && i !== points.length - 1) return '';
      return `<text class="c-chart__label" x="${coords[i].x.toFixed(1)}" y="${H - 7}" text-anchor="middle">${p.label}</text>`;
    })
    .join('');

  const bandW = INNER_W / points.length;
  const hits = points
    .map((p, i) => {
      const x = Math.max(PAD.left, coords[i].x - bandW / 2);
      return `<rect class="c-chart__hit" data-index="${i}" x="${x.toFixed(1)}" y="${PAD.top}" width="${bandW.toFixed(1)}" height="${INNER_H}"/>`;
    })
    .join('');

  const dots = coords
    .map(
      (c, i) =>
        `<circle class="c-chart__pt" data-index="${i}" cx="${c.x.toFixed(1)}" cy="${c.y.toFixed(1)}" r="3.6"/>`
    )
    .join('');

  const inner =
    grid +
    `<path class="c-chart__area" d="${area}"/>` +
    `<path class="c-chart__line" d="${line}"/>` +
    dots +
    labels +
    hits;

  const svg = buildSvg(inner, options.label || `趋势图，共 ${points.length} 个数据点`, 'line');
  const figure = h('figure', { className: 'c-chart c-chart--line' }, svg);
  bindPointClick(figure, points, options.onPointClick);
  return figure;
}

/**
 * 柱状图（成绩分布 / 单点降级）。柱顶直接标数值。
 *
 * @param {{
 *   items?: Array<{label: string, value: number}>,
 *   label?: string,
 *   empty?: string,
 *   max?: number,
 *   unit?: string,
 *   onPointClick?: ((payload: {label: string, value: number, index: number}) => void)|null
 * }} options
 * @returns {HTMLElement}
 */
export function barChart(options = {}) {
  const items = (Array.isArray(options.items) ? options.items : [])
    .filter((it) => it && Number.isFinite(Number(it.value)))
    .map((it) => ({ label: String(it.label === undefined ? '' : it.label), value: Number(it.value) }));

  if (items.length === 0) {
    const svg = buildSvg(
      `<text class="c-chart__label c-chart__label--center" x="${W / 2}" y="${H / 2}" text-anchor="middle">${options.empty || '暂无数据'}</text>`,
      options.label || '柱状图',
      'bar'
    );
    return h('figure', { className: 'c-chart c-chart--bar is-empty' }, svg);
  }

  const max = Number.isFinite(Number(options.max)) ? Number(options.max) : Math.max(...items.map((it) => it.value), 1);
  const band = INNER_W / items.length;
  const barW = Math.min(46, band * 0.56);
  const baseY = PAD.top + INNER_H;
  const step = Math.max(1, Math.ceil(items.length / 6));

  const bars = items
    .map((it, i) => {
      const ratio = Math.max(0, Math.min(1, it.value / max));
      const barH = Math.max(2, ratio * (INNER_H - 12));
      const x = PAD.left + band * i + (band - barW) / 2;
      const y = baseY - barH;
      const valueText = `<text class="c-chart__value" x="${(x + barW / 2).toFixed(1)}" y="${(y - 5).toFixed(1)}" text-anchor="middle">${fmt(it.value)}</text>`;
      const labelText =
        i % step === 0 || i === items.length - 1
          ? `<text class="c-chart__label" x="${(x + barW / 2).toFixed(1)}" y="${H - 7}" text-anchor="middle">${it.label}</text>`
          : '';
      const hit = `<rect class="c-chart__hit" data-index="${i}" x="${(PAD.left + band * i).toFixed(1)}" y="${PAD.top}" width="${band.toFixed(1)}" height="${INNER_H}"/>`;
      return `<rect class="c-chart__bar" data-index="${i}" x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${barW.toFixed(1)}" height="${barH.toFixed(1)}" rx="3"/>${valueText}${labelText}${hit}`;
    })
    .join('');

  const axis = `<line class="c-chart__axis" x1="${PAD.left}" y1="${baseY}" x2="${PAD.left + INNER_W}" y2="${baseY}"/>`;
  const svg = buildSvg(bars + axis, options.label || `柱状图，共 ${items.length} 组`, 'bar');
  const figure = h('figure', { className: 'c-chart c-chart--bar' }, svg);
  bindPointClick(figure, items, options.onPointClick);
  return figure;
}

/**
 * 环形进度（学分完成度）。
 *
 * @param {{
 *   value: number, max?: number,
 *   text?: string, caption?: string,
 *   tone?: 'brand'|'accent'|'success',
 *   label?: string
 * }} options
 * @returns {HTMLElement}
 */
export function ringProgress(options = {}) {
  const max = Number(options.max) > 0 ? Number(options.max) : 1;
  const value = Number(options.value);
  const ratio = Number.isFinite(value) ? Math.min(1, Math.max(0, value / max)) : 0;

  const r = 58;
  const circumference = 2 * Math.PI * r;
  const dash = ratio * circumference;
  const tone = options.tone || 'brand';
  const text = options.text === undefined ? `${Math.round(ratio * 100)}%` : String(options.text);

  const inner =
    `<circle class="c-chart__ring-track" cx="${W / 2}" cy="${H / 2}" r="${r}" stroke-width="14"/>` +
    `<circle class="c-chart__ring-arc c-chart__ring-arc--${tone}" cx="${W / 2}" cy="${H / 2}" r="${r}" stroke-width="14" ` +
    `stroke-linecap="round" transform="rotate(-90 ${W / 2} ${H / 2})" ` +
    `stroke-dasharray="${dash.toFixed(2)} ${circumference.toFixed(2)}"/>` +
    `<text class="c-chart__ring-text" x="${W / 2}" y="${H / 2 + 2}" text-anchor="middle">${text}</text>` +
    (options.caption
      ? `<text class="c-chart__label" x="${W / 2}" y="${H / 2 + 20}" text-anchor="middle">${options.caption}</text>`
      : '');

  return h(
    'figure',
    { className: 'c-chart c-chart--ring' },
    buildSvg(inner, options.label || `完成度 ${Math.round(ratio * 100)}%`, 'ring')
  );
}

/**
 * 堆叠条（学分构成：必修 / 限选 / 任选 / 素养）。
 *
 * @param {{
 *   segments: Array<{label: string, value: number}>,
 *   total?: number,
 *   label?: string,
 *   showLegend?: boolean,
 *   onPointClick?: ((payload: {label: string, value: number, index: number}) => void)|null
 * }} options
 * @returns {HTMLElement}
 */
export function stackedBar(options = {}) {
  const segments = (Array.isArray(options.segments) ? options.segments : [])
    .filter((s) => s && Number.isFinite(Number(s.value)) && Number(s.value) > 0)
    .map((s) => ({ label: String(s.label === undefined ? '' : s.label), value: Number(s.value) }));

  const sum = segments.reduce((acc, s) => acc + s.value, 0);
  const total = Number(options.total) > 0 ? Number(options.total) : sum;
  const barX = PAD.left;
  const barW = INNER_W;
  const barY = 68;
  const barH = 34;
  clipSeed += 1;
  const clipId = `c-stack-clip-${clipSeed}`;

  let cursor = barX;
  const rects = segments
    .map((s, i) => {
      const width = total > 0 ? (s.value / total) * barW : 0;
      const x = cursor;
      cursor += width;
      return `<rect class="c-chart__seg c-chart__seg--${(i % 5) + 1}" data-index="${i}" x="${x.toFixed(1)}" y="${barY}" width="${Math.max(0.5, width).toFixed(1)}" height="${barH}"/>`;
    })
    .join('');

  const inner =
    `<defs><clipPath id="${clipId}"><rect x="${barX}" y="${barY}" width="${barW}" height="${barH}" rx="8"/></clipPath></defs>` +
    `<g clip-path="url(#${clipId})">${rects}</g>` +
    `<text class="c-chart__value c-chart__value--lead" x="${barX}" y="${barY - 10}">已获 ${fmt(sum)}</text>` +
    `<text class="c-chart__label" x="${barX + barW}" y="${barY - 10}" text-anchor="end">要求 ${fmt(total)}</text>` +
    segments
      .map((s, i) => {
        const width = total > 0 ? (s.value / total) * barW : 0;
        const startX = barX + segments.slice(0, i).reduce((acc, it) => acc + (total > 0 ? (it.value / total) * barW : 0), 0);
        return `<rect class="c-chart__hit" data-index="${i}" x="${startX.toFixed(1)}" y="${barY - 12}" width="${Math.max(12, width).toFixed(1)}" height="${barH + 24}"/>`;
      })
      .join('');

  const svg = buildSvg(inner, options.label || `学分构成，共 ${segments.length} 类`, 'stack');

  const legend =
    options.showLegend === false
      ? null
      : h(
          'div',
          { className: 'c-chart__legend' },
          segments.map((s, i) =>
            h('span', { className: 'c-chart__legend-item' }, [
              h('i', { className: 'c-chart__dot', dataset: { seg: String((i % 5) + 1) }, 'aria-hidden': 'true' }),
              h('span', { className: 'c-chart__legend-label', text: s.label }),
              h('span', { className: 'c-chart__legend-value', text: fmt(s.value) }),
            ])
          )
        );

  const figure = h('figure', { className: 'c-chart c-chart--stack' }, [svg, legend]);
  bindPointClick(figure, segments, options.onPointClick);
  return figure;
}
