package com.tstc.zstangshi.shared.bus

import com.tstc.zstangshi.shared.Js

/** 支持实时公交的城市。 */
class BusCity(val cityId: String, val cityName: String) {
    override fun toString(): String = "$cityName($cityId)"
}

/**
 * 城市列表（`/wwd/ncitylist`，明文接口，不需要签名）。
 * 响应：`{data:{cityList:[{cityId,cityName}]}}` 或直接 `{cityList:[...]}`，两种都兼容。
 */
fun parseCities(body: String): List<BusCity> {
    val start = body.indexOf('{')
    if (start < 0) return emptyList()
    val root = Js.parse(body.substring(start, ChelaileCrypto.jsonEnd(body, start))) as? Js.Obj
        ?: return emptyList()
    val data = root.optObj("data") ?: root
    val arr = data.optArr("cityList") ?: return emptyList()
    val out = ArrayList<BusCity>()
    for (item in arr.items) {
        val c = item as? Js.Obj ?: continue
        val id = c.optString("cityId")
        val name = c.optString("cityName")
        if (id.isEmpty() || name.isEmpty()) continue
        out.add(BusCity(id, name))
    }
    return out
}
