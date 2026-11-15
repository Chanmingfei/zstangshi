/**
 * gpa.js —— 学生学业情况查看（P05）
 *
 * 【用户口径 2026-11-06】这一页**只展示 GPA**。
 * 教务「信息查询 → 学生学业情况查询」里确实还有七项门次统计和 21 节点学分构成树，
 * 但用户明确不需要，所以本页一个都不画：不画进度条、不画树、不画折叠、不画图表。
 *
 * 【为什么零计算】学校口径对公选课不计入总绩点、重修取首次成绩、五级制单独折算，
 * 本地加权只能算出 3.07，教务印的是 3.16。任何客户端平均都是错的，不是近似。
 * 所以这里只把教务原文里的 `gpa` 与 `statsAt` 搬上屏，连 toFixed 之外的运算都没有。
 *
 * 来源与降级要诚实：抓取失败降级成演示/缓存副本时，页面必须标出来，不能装成实时值。
 */

import { h } from '../core/dom.js';
import { emptyState } from '../components/emptyState.js';
import { skeletonCard } from '../components/skeleton.js';
import { prefersReducedMotion } from '../core/animate.js';

const SOURCE_NOTE = '数据来自教务系统「信息查询 → 学生学业情况查询」';
const DISCLAIMER = '提示：此页面信息仅做学业修读情况参考。';
/** 数字入场滚动时长（PRD 350ms） */
const COUNT_MS = 350;

export default {
  name: 'gpa',
  title: '学分绩点',
  requiresAuth: true,

  render() {
    const value = h('span', { className: 'p-gpa__value', text: '--' });
    const source = h('p', { className: 'p-gpa__source', hidden: true });
    const body = h('div', { className: 'p-gpa__body' }, skeletonCard({ lines: 2 }));

    const el = h('div', { className: 'page p-gpa' }, [source, body]);
    el.__view = { value, source, body };
    return el;
  },

  /** @returns {() => void} cleanup */
  mount(el, ctx) {
    const view = el.__view;
    const { store, actions } = ctx;
    /** @type {number} requestAnimationFrame 句柄，离开页面必须取消 */
    let raf = 0;

    /** 数字从 0 滚到目标值；减少动效偏好时直接落终值 */
    function countTo(target) {
      if (raf) cancelAnimationFrame(raf);
      if (typeof target !== 'number' || !Number.isFinite(target) || prefersReducedMotion()) {
        view.value.textContent = typeof target === 'number' ? target.toFixed(2) : '--';
        return;
      }
      const started = performance.now();
      const tick = (now) => {
        const t = Math.min(1, (now - started) / COUNT_MS);
        const eased = 1 - Math.pow(1 - t, 3);
        view.value.textContent = (target * eased).toFixed(2);
        if (t < 1) raf = requestAnimationFrame(tick);
        else raf = 0;
      };
      raf = requestAnimationFrame(tick);
    }

    function paint() {
      const slot = store.getState().slots.gpa;
      if (!slot || slot.status === 'idle' || slot.status === 'loading') {
        view.body.replaceChildren(skeletonCard({ lines: 2 }));
        view.source.hidden = true;
        return;
      }
      if (!slot.data) {
        view.body.replaceChildren(emptyState({
          kind: 'error',
          title: (slot.error && slot.error.message) || '学业情况没能取回来',
          description: '稍后重试即可；教务高峰期可能响应较慢',
          actionLabel: '重试',
          onAction: () => actions.loadSlot('gpa', { force: true }),
        }));
        view.source.hidden = true;
        return;
      }

      const data = slot.data;
      const isDemo = store.getState().session.status === 'demo';
      const stale = Boolean(slot.degraded || slot.fromCache || slot.status === 'stale' || isDemo);
      const hasGpa = typeof data.gpa === 'number' && Number.isFinite(data.gpa);

      const nodes = [
        h('section', { className: 'p-gpa__card' }, [
          h('p', { className: 'p-gpa__label', text: '平均学分绩点（GPA）' }),
          view.value,
          h('p', {
            className: 'p-gpa__badge',
            text: data.gpaOrigin === 'official' ? '教务官方值' : '教务统计值',
          }),
          h('p', {
            className: 'p-gpa__stats',
            text: data.statsAt ? `统计时间 ${data.statsAt} 之前有效` : '教务未提供统计时间',
          }),
        ]),
        hasGpa ? null : emptyState({
          kind: 'empty',
          title: '教务暂未生成学业情况',
          description: '请稍后再试，或到教务系统「学生学业情况查询」核对',
        }),
        h('p', { className: 'p-gpa__disclaimer', text: DISCLAIMER }),
        stale
          ? h('p', {
            className: 'p-gpa__stale',
            text: isDemo
              ? '当前为演示数据，登录后展示教务实时抓取结果'
              : '教务暂不可用，当前显示的是上次同步的副本',
          })
          : null,
      ].filter(Boolean);
      // 必须滤掉 null：replaceChildren(null) 会把 null 当文本节点渲染成字面 "null"
      view.body.replaceChildren(...nodes);
      countTo(hasGpa ? data.gpa : null);

      view.source.textContent = SOURCE_NOTE;
      view.source.hidden = false;
    }

    const unsubscribe = store.subscribe((s) => s.slots.gpa, () => paint());
    paint();
    const slot = store.getState().slots.gpa;
    if (!slot || slot.status === 'idle') actions.loadSlot('gpa');

    return () => {
      unsubscribe();
      if (raf) cancelAnimationFrame(raf);
    };
  },

  unmount() { /* 收尾都在 cleanup */ },
};
