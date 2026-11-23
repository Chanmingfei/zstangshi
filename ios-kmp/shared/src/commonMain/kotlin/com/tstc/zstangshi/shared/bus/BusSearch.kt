package com.tstc.zstangshi.shared.bus

import com.tstc.zstangshi.shared.Js

/**
 * 线路搜索的「结果行」。
 *
 * 为什么不直接把 `Js.Obj` 交给 Swift：那等于让 Swift 去摸一棵动态 JSON 树，
 * 桥接层每取一个字段都要处理 Optional / 类型判断。这里 K/N 的 `data class`
 * 会导出成带属性的普通 ObjC 类，Swift 侧 `hit.name` 就完事。
 */
class BusLineHit(
    val lineId: String,
    val name: String,
    val no: String,
    val startSn: String,
    val endSn: String
) {
    override fun toString(): String = "$name($no) $startSn→$endSn"
}

/**
 * 解析 `query!nSearch.action` 的响应。
 *
 * 真实响应结构（2026-11 实测）：
 *   `**YGKJ{"jsonr":{"data":{"result":{"lineCount":2,"lines":[{...}]}}}}YGKJ##`
 * 注意同名线路会因方向不同出现两次（`direction` 不同），界面上通常合成一行 ——
 * 这里保留原始条数，去重交给界面（和 Android 的 `mergeDirections` 保持一致的分工）。
 */
fun parseSearchHits(body: String): List<BusLineHit> {
    val env = ChelaileCrypto.extract(body) ?: return emptyList()
    val result = env.optObj("result") ?: return emptyList()
    val lines = result.optArr("lines") ?: return emptyList()
    val out = ArrayList<BusLineHit>(lines.size)
    for (item in lines.items) {
        val o = item as? Js.Obj ?: continue
        val lineId = o.optString("lineId")
        val name = o.optString("name").ifEmpty { o.optString("lineNo") }
        if (lineId.isEmpty() || name.isEmpty()) continue
        out.add(
            BusLineHit(
                lineId = lineId,
                name = name,
                no = o.optString("lineNo"),
                startSn = o.optString("startSn"),
                endSn = o.optString("endSn")
            )
        )
    }
    return out
}
