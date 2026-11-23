package com.tstc.zstangshi.shared.bus

import com.tstc.zstangshi.shared.Js
import com.tstc.zstangshi.shared.net.HttpResult
import com.tstc.zstangshi.shared.net.buildQuery
import com.tstc.zstangshi.shared.net.httpGetText

/**
 * 车来了 HTTP 层：签名 → 拼查询串 → GET → 拆壳解密。
 *
 * 对应 Android `bus/ChelaileClient.call()`；参数顺序、请求头、URL 路径都照抄，
 * 因为签名对参数顺序敏感，UA/Referer 对服务端也敏感（缺 Referer 会被当成非小程序流量）。
 */
object ChelaileApi {

    const val BASE_URL = "https://web.chelaile.net.cn/api"

    private const val UA = "Mozilla/5.0 (Linux; Android 14; iPhone) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36 MicroMessenger/8.0.44"

    private const val REFERER = "https://servicewechat.com/wx71d589ea01ce3321/814/page-frame.html"

    /** 所有接口都要带的公共参数（顺序即签名顺序）。 */
    fun baseParams(): MutableMap<String, String> {
        val p = LinkedHashMap<String, String>()
        p["s"] = "h5"
        p["wxs"] = "wx_app"
        p["sign"] = "1"
        p["h5RealData"] = "1"
        p["v"] = "3.11.28"
        p["src"] = "weixinapp_cx"
        p["ctm_mp"] = "mp_wx"
        p["vc"] = "2"
        p["favoriteGray"] = "1"
        p["gpstype"] = "wgs"
        p["geo_type"] = "wgs"
        p["scene"] = "1256"
        return p
    }

    /** 拼出带签名的完整 URL（拆出来是为了能在 Windows 上断言，不必联网）。 */
    fun signedUrl(path: String, params: Map<String, String>): String {
        val signed = LinkedHashMap(params)
        signed["cryptoSign"] = ChelaileCrypto.sign(params)
        return BASE_URL + path + "?" + buildQuery(signed)
    }

    fun headers(): Map<String, String> = linkedMapOf(
        "User-Agent" to UA,
        "Referer" to REFERER,
        "xweb_xhr" to "1",
        "Accept" to "*/*",
        "Accept-Language" to "zh-CN,zh;q=0.9"
    )

    suspend fun call(path: String, params: Map<String, String>): Js.Obj? {
        val url = signedUrl(path, params)
        val res: HttpResult = httpGetText(url, headers())
        if (!res.ok) {
            throw IllegalStateException("车来了请求失败（HTTP ${res.code}）${res.error ?: ""}")
        }
        return ChelaileCrypto.extract(res.body)
    }

    /**
     * 线路搜索（最轻的接口，用来验证整条链路）。
     * 对应 Android `searchResult()`，只保留必需参数。
     */
    suspend fun search(cityId: String, keyword: String): Js.Obj? {
        val p = baseParams()
        p["cityId"] = cityId
        p["localCityId"] = cityId
        p["key"] = keyword
        p["supportPhyStn"] = "true"
        return call("/bus/query!nSearch.action", p)?.optObj("result")
    }
}
