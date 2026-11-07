/**
 * navStack.js —— 应用内返回栈（纯函数，零依赖）
 *
 * 为什么不用浏览器历史：WebView 里 hash 导航不是可靠的「一页一条记录」。
 * 实测（2026-11-07，1.5.0 真机）：从成绩详情按返回，`history.back()` 直接跳回
 * App 首页（那条无 hash 的初始记录），而用户期待的是回到成绩列表。
 * 所以「谁打开我，返回就回谁」由 App 自己记，浏览器历史只当兜底。
 *
 * 这里只放两个纯函数，让「返回去哪」的规则可以脱离 DOM 单测（tools/test-pages.mjs §D）。
 */

/**
 * 记录一次「从 from 打开 to」的层级关系。
 * 同页跳转、无来路（深链首屏）都不入栈；与栈顶相同也不重复入栈。
 * @param {string[]} stack 原地修改
 * @param {string|null} from 出发页路径
 * @param {string} to 目标页路径
 * @returns {string[]} 同一个数组引用
 */
export function recordVisit(stack, from, to) {
  if (!from || !to || from === to) return stack;
  if (stack[stack.length - 1] !== from) stack.push(from);
  return stack;
}

/**
 * 取上一层路径（弹掉它），跳过与当前页相同的脏记录。
 * @param {string[]} stack 原地修改
 * @param {string} [currentPath] 当前页，用于跳过自指记录
 * @returns {string|null} 没有上一层时返回 null（调用方决定回首页）
 */
export function takePrevious(stack, currentPath) {
  while (stack.length) {
    const prev = stack.pop();
    if (prev && prev !== currentPath) return prev;
  }
  return null;
}
