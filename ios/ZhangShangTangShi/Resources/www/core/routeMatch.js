/**
 * routeMatch.js —— 路由匹配工具（从 router.js 拆出，只为守住单文件 400 行上限）
 *
 * 三件事：把 `/grades/:id` 编译成正则、解析查询串、安全 decode 路径段。
 * 纯函数、零依赖，router.js 与将来的验收脚本都可以直接用。
 */

/**
 * 把 `'/grades/:id'` 编译成正则 + 参数名表。
 * @param {string} pattern
 * @returns {{keys: string[], regex: RegExp}}
 */
export function compilePattern(pattern) {
  const keys = [];
  const source = pattern.replace(/\/:([A-Za-z0-9_]+)/g, (_, key) => {
    keys.push(key);
    return '/([^/]+)';
  });
  return { keys, regex: new RegExp(`^${source}/?$`) };
}

/**
 * 解析查询串。
 * @param {string} search 形如 'a=1&b=2'（不含 '?'）
 * @returns {Record<string, string>}
 */
export function parseQuery(search) {
  const query = {};
  if (!search) return query;
  for (const pair of search.split('&')) {
    if (!pair) continue;
    const index = pair.indexOf('=');
    const key = index === -1 ? pair : pair.slice(0, index);
    const rawValue = index === -1 ? '' : pair.slice(index + 1);
    try {
      query[decodeURIComponent(key)] = decodeURIComponent(rawValue);
    } catch {
      query[key] = rawValue;
    }
  }
  return query;
}

/** 安全 decode 一个路径段（畸形编码原样返回，别让一个 % 炸掉整个路由）。 */
export function safeDecode(segment) {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

