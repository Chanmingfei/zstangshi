/**
 * externalLinks.js —— 外部站点入口地址的唯一来源（学工系统 / 教务系统）
 *
 * 为什么单独一个文件：这些地址只是「交给原生 WebView 打开的字符串」，不是要加载的资源。
 * 集中在这里，check-rules 守卫②（web/** 不得出现外链）就只需给本文件开一条白名单，
 * 而不是每新增一个入口页面（首页 / 登录页 / 我的）都去改白名单；地址变更也只改一处。
 *
 * 注意：两处都不能写成 src / href —— 那会被浏览器当成资源加载（守卫② 会直接拦下）。
 */

import { getActiveOrigin } from './jwxtOrigin.js';

/** 学工系统（学校站点只提供 http 明文；Android 侧由 network_security_config 对 tstc.edu.cn 放行） */
export const XUEGONG_URL = 'http://sws.tstc.edu.cn/XGTSTC/Phone/#/login';

/**
 * 教务系统（正方教务原站点，首页「更多功能」入口）。
 * 协议不能写死：校园网 https 可用，移动数据下 443 不通、只有 http —— 由 jwxtOrigin 探测，
 * 这里只读探测结果。未探测时回落到 https，原生 WebView 加载失败还会换协议重试。
 * @returns {string}
 */
export function jwxtUrl() {
  return `${getActiveOrigin()}/`;
}
