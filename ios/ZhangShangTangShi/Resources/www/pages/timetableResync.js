/**
 * timetableResync.js —— 个人课表「重新与教务系统同步」
 *
 * 为什么需要这个按钮：本地课表是可编辑的，改错的概率也随之而来 —— 周次点错一格、节次
 * 挪了一节、手滑删了一门。没有后悔药的话，学生只能重新登录教务、一格格对照着改回来。
 *
 * 防误触三道闸（这是个**破坏性**操作，手改的版本会被覆盖）：
 *   1. 按钮是次要样式（描边、不与「新建课程」抢主色），且未登录时直接提示去登录；
 *   2. 点击后二次确认，文案把后果写清楚：「手改的被覆盖，手动新建的课保留」；
 *   3. 真正写盘前不碰本地 —— 教务没连上 / 降级成演示数据 / 返回空课表，一律取消并说明原因
 *      （见 appActions.resyncTimetable 的三道闸）。
 */

import { toast, confirm } from '../core/toast.js';

/**
 * 绑定「重新与教务同步」按钮。
 *
 * @param {HTMLElement} btn
 * @param {{store: object, actions: object, onSynced?: (local: object) => void}} options
 * @returns {() => void} 解绑
 */
export function bindResync(btn, options) {
  const { store, actions } = options;
  const onSynced = typeof options.onSynced === 'function' ? options.onSynced : null;
  let busy = false;

  async function run() {
    if (busy) return;
    // 未登录时没有教务会话，同步是「必然失败」的操作 —— 先说实话，别让用户白等一次请求
    if (store.getState().session.status !== 'authed') {
      toast('登录教务后才能同步最新课表，现在看到的是本地保存的课表', 'info');
      return;
    }

    const yes = await confirm(
      '重新与教务同步？',
      '会拉取教务系统的最新课表：你手动改过的课程（周次 / 时间 / 地点）将被教务版本覆盖，手动新建的课程保留。改错了想恢复时用它。',
      { confirmText: '同步' }
    );
    if (!yes) return;

    busy = true;
    btn.disabled = true;
    btn.classList.add('is-busy');
    try {
      const result = await actions.resyncTimetable();
      if (!result || !result.ok) {
        toast((result && result.error && result.error.message) || '同步失败，本地课表未改动', 'warning');
        return;
      }
      const stats = result.stats || {};
      if (onSynced) onSynced(result.local);
      toast(`已同步教务课表：新增 ${stats.added || 0} 门，更新 ${stats.updated || 0} 门`, 'success');
    } finally {
      busy = false;
      btn.disabled = false;
      btn.classList.remove('is-busy');
    }
  }

  btn.addEventListener('click', run);
  return () => btn.removeEventListener('click', run);
}

export default bindResync;
