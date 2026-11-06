/**
 * store.js —— 极简可订阅状态容器（约 60 行实现 + state 形状真源注释）
 *
 * 设计取舍（ARCHITECTURE §4.3）：
 *   · 不做虚拟 DOM、不做整体重渲染。store 只负责「值 + 变更通知」，
 *     UI 用 `subscribe(selector, cb)` 细粒度订阅，cb 里做目标 DOM 局部替换。
 *   · `subscribe` 必须做值比较：相等就不触发。原始值用 Object.is，
 *     对象/数组用**浅比较** —— 所以 selector 不要返回每次都新建的字面量对象，
 *     否则永远「不相等」（这是有意为之：宁可少触发，也不要无意义重绘）。
 *
 * ═══ state 形状（唯一真源，data/types.js 与此保持一致）═══════════════════
 *
 * const initialState = {
 *   // ── 应用外壳 ──
 *   app: {
 *     booted: false,          // 启动流程完成
 *     env: 'web',             // 'web' | 'android'
 *     version: '1.0.0',
 *     theme: 'light',         // 'light' | 'dark'（生效值）
 *     themeSource: 'system',  // 'system' | 'user'
 *     offline: false,         // !navigator.onLine || 最近一次 NETWORK 错误
 *     reducedMotion: false    // prefers-reduced-motion
 *   },
 *   // ── 会话 ──
 *   session: {
 *     status: 'guest',        // 'guest' | 'pending' | 'authed' | 'demo' | 'failed'
 *     studentNo: '',          // 明文仅存内存，绝不落 localStorage
 *     rememberId: false,
 *     name: '',
 *     error: null             // ProviderError | null
 *   },
 *   // ── 数据源 ──
 *   source: {
 *     preferred: 'demo',      // 用户选择
 *     effective: 'demo',      // 实际生效（探测/降级结果）
 *     probing: false,
 *     probe: { reachable: false, latencyMs: null, reason: null },
 *     bannerDismissed: false, // 演示提示条（本次启动有效）
 *     lastFallbackAt: null,
 *     lastError: null
 *   },
 *   // ── 学期与时间（顶栏唯一来源）──
 *   term: {
 *     calendar: null,         // 解析后的 school-calendar.json
 *     context: {              // TermHeaderContext，由 calendar.js 计算
 *       displayYear: '26~27', termLabel: '第一学期', week: 10,
 *       phase: 'inTerm',
 *       left: '26~27学年第一学期',  // 左段
 *       text: '第 10 教学周',        // 右段（学期外为「假期中」/「距开学 X 天」）
 *       full: '26~27学年第一学期 第 10 教学周',
 *       semesterId: '2026-2027-1'
 *     },
 *     todayIso: '2026-11-05',
 *     semesters: []           // SemesterOption[]，成绩页筛选用
 *   },
 *   // ── 数据槽（8 个，形状统一）──
 *   slots: {
 *     profile:      { status:'idle', data:null, error:null, updatedAt:null, fromCache:false, degraded:false },
 *     todayCourses: { status:'idle', data:null, error:null, updatedAt:null, fromCache:false, degraded:false },
 *     timetable:    { status:'idle', data:null, error:null, updatedAt:null, fromCache:false, degraded:false },
 *     grades:       { status:'idle', data:null, error:null, updatedAt:null, fromCache:false, degraded:false }, // data:{ bySemester:{} }
 *     gpa:          { status:'idle', data:null, error:null, updatedAt:null, fromCache:false, degraded:false },
 *     plan:         { status:'idle', data:null, error:null, updatedAt:null, fromCache:false, degraded:false },
 *     selection:    { status:'idle', data:null, error:null, updatedAt:null, fromCache:false, degraded:false },
 *     recommend:    { status:'idle', data:null, error:null, updatedAt:null, fromCache:false, degraded:false }
 *   },
 *   // status ∈ 'idle' | 'loading' | 'ready' | 'stale' | 'error'
 *   // 'stale' = 有缓存但本次刷新失败（UI 展示缓存 + 「上次同步 …」标注）
 *   // ── UI 局部状态（不入缓存）──
 *   ui: {
 *     route: '/home',
 *     params: {},
 *     activeTab: 'home',          // 'home' | 'timetable' | 'grades' | 'mine'
 *     weekNo: 10,                 // 课表当前周
 *     semesterFilter: '2026-2027-1',
 *     gradesQuery: '',
 *     selectionFilter: { type:'all', dept:'all', onlyAvailable:false },
 *     selectionIntent: [],
 *     calendarMonth: '2026-11',
 *     gpaRange: 'all',
 *     expandedModules: [],
 *     sheet: null,
 *     busy: {},
 *     toasts: []
 *   },
 *   // ── 缓存 ──
 *   cache: { totalBytes: 0, lastClearedAt: null }
 * };
 * ═══════════════════════════════════════════════════════════════════════
 */

/**
 * 浅比较：只比第一层，键集合不同或任一值 !== 即不等。
 * @param {object} a
 * @param {object} b
 * @returns {boolean}
 */
function shallowEqual(a, b) {
  if (Object.is(a, b)) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  const keysA = Object.keys(a);
  const keysB = Object.keys(b);
  if (keysA.length !== keysB.length) return false;
  for (const key of keysA) {
    if (!Object.is(a[key], b[key])) return false;
  }
  return true;
}

/**
 * 订阅时的「值是否相同」判定：原始值用 Object.is，对象/数组用浅比较。
 * @param {unknown} a
 * @param {unknown} b
 * @returns {boolean}
 */
function sameValue(a, b) {
  if (Object.is(a, b)) return true;
  if (a && b && typeof a === 'object' && typeof b === 'object') return shallowEqual(a, b);
  return false;
}

/**
 * 建一个 store。
 * @template S
 * @param {S} initialState 初始状态（见文件顶部形状注释）
 * @returns {{
 *   getState: () => S,
 *   patch: (partial: Partial<S>) => S,
 *   subscribe: (selector: (s: S) => unknown, cb: (value: any, state: S) => void) => (() => void),
 *   select: (selector: (s: S) => unknown) => unknown,
 *   batch: (fn: () => void) => void
 * }}
 */
export function createStore(initialState) {
  /** @type {S} */
  let state = initialState;
  /** @type {Set<{selector: Function, cb: Function, last: unknown}>} */
  const listeners = new Set();
  let batchDepth = 0;
  let dirty = false;

  /**
   * 取当前 state（只读引用，调用方不得就地修改）。
   * @returns {S}
   */
  function getState() {
    return state;
  }

  /**
   * 计算派生值。
   * @param {(s: S) => unknown} selector
   * @returns {unknown}
   */
  function select(selector) {
    return selector(state);
  }

  /** 通知所有订阅者（值没变的不触发）。 */
  function notify() {
    for (const listener of Array.from(listeners)) {
      if (!listeners.has(listener)) continue; // cb 里可能退订
      let next;
      try {
        next = listener.selector(state);
      } catch (err) {
        console.error('[zsts] store selector 抛错', err);
        continue;
      }
      if (sameValue(next, listener.last)) continue;
      listener.last = next;
      try {
        listener.cb(next, state);
      } catch (err) {
        // 单个订阅者出错不能拖垮其它订阅者与整页
        console.error('[zsts] store subscriber 抛错', err);
      }
    }
  }

  /**
   * 浅合并到根（嵌套子树请自带新对象：`patch({ ui: { ...ui, weekNo } })`）。
   * @param {Partial<S>} partial
   * @returns {S}
   */
  function patch(partial) {
    if (!partial || typeof partial !== 'object') return state;
    state = { ...state, ...partial };
    if (batchDepth > 0) {
      dirty = true;
      return state;
    }
    notify();
    return state;
  }

  /**
   * 订阅某个派生值，值变化才回调。
   * @param {(s: S) => unknown} selector
   * @param {(value: any, state: S) => void} cb
   * @returns {() => void} 退订函数
   */
  function subscribe(selector, cb) {
    const listener = { selector, cb, last: selector(state) };
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }

  /**
   * 批量更新：期间不通知，结束后统一通知一次。
   * @param {() => void} fn
   */
  function batch(fn) {
    batchDepth += 1;
    try {
      fn();
    } finally {
      batchDepth -= 1;
      if (batchDepth === 0 && dirty) {
        dirty = false;
        notify();
      }
    }
  }

  return { getState, patch, subscribe, select, batch };
}
