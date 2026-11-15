/**
 * format.js —— 页面通用的纯格式化小函数
 *
 * 只有一处被两个页面的「缓存态」文案同时需要，所以单独放 core：
 * 成绩列表与成绩详情都要显示「上次同步 11-05 22:10」，各写一份必然会漂。
 * 本文件不 import 任何模块、不碰 DOM。
 */

/**
 * 缓存时间戳 → `11-05 22:10`（月-日 时:分，本机时区）。
 * 传 null / 非法时间时退化成「现在」，避免出现 NaN。
 * @param {string|number|Date|null} iso
 * @returns {string}
 */
export function syncText(iso) {
  const d = iso ? new Date(iso) : new Date();
  const safe = Number.isNaN(d.getTime()) ? new Date() : d;
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(safe.getMonth() + 1)}-${pad(safe.getDate())} ${pad(safe.getHours())}:${pad(safe.getMinutes())}`;
}
