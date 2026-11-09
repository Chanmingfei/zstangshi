/**
 * timetableSync.js —— 教务课表 → 本地课表 → 桌面小组件的同步泵
 *
 * 为什么单独一个文件而不塞在课表页里：
 *   **免登录查看的前提是数据真的在本地**。页面里的合并只有「用户正好打开课表页」时才跑；
 *   只在首页看今日课程、或看完就退出登录的用户，课表永远不落盘 —— 于是退出后打不开，
 *   「本地课表」形同虚设。所以落盘挂在数据层（`loadSlot('timetable')` 成功那一刻），
 *   与哪个页面在前台无关。
 *
 * 三类数据不许落盘：
 *   ① 演示态（用户点「先用演示数据看看」）—— 演示课表不能变成他的真课表
 *   ② 降级结果（教务抓取失败、withFallback 拿演示数据顶上）—— 同上，且更隐蔽
 *   ③ 空课表 —— 教务返回 0 门通常是解析异常，不该把本地课表清空
 */

import { saveImported } from './localTimetable.js';
import { syncWidget } from './widget.js';

/**
 * 把一次教务课表结果并进本地（幂等：同 id 覆盖，手改的保留）。
 *
 * @param {object} state store 状态快照（要 session / term）
 * @param {object|null} result ProviderResult
 * @returns {boolean} 是否真的写了本地
 */
export function absorbTimetable(state, result) {
  if (!state || !state.session || state.session.status === 'demo') return false;
  if (!result || !result.ok) return false;
  if (result.meta && result.meta.degraded) return false;
  const courses = result.data && Array.isArray(result.data.courses) ? result.data.courses : null;
  if (!courses || courses.length === 0) return false;

  const context = (state.term && state.term.context) || {};
  saveImported(context.semesterId, courses);
  // 周次 / 今日课程都可能变，桌面小组件跟着刷新
  syncWidget(state);
  return true;
}

export default absorbTimetable;
