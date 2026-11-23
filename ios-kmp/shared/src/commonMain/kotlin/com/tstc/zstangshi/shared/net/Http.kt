package com.tstc.zstangshi.shared.net

/**
 * 最小 HTTP GET。
 *
 * 为什么不引 Ktor（追客用的是 Ktor + OkHttp/Darwin 引擎）：
 *   Ktor 会给共享层带回一堆 Maven 依赖，而 Android 主线是 aapt2 + kotlinc + d8 手写链，
 *   解析不了 Maven 依赖。共享层目前要同时喂给「Windows 上的 kotlinc 断言」和
 *   「Codemagic 上的 Kotlin/Native」，零依赖是最省事的边界。
 *   代价：iOS 侧要自己写 NSURLSession 的协程桥接（见 Http.ios.kt，约 40 行）。
 */
data class HttpResult(
    val code: Int,
    val body: String,
    val error: String? = null
) {
    val ok: Boolean get() = code in 200..299
}

/**
 * 发起 GET。失败不抛异常，把原因放进 [HttpResult.error] ——
 * 公交这边「没网」是常态，上层要能区分「网络失败」和「服务端返回错误」。
 */
expect suspend fun httpGetText(url: String, headers: Map<String, String> = emptyMap()): HttpResult

/**
 * 表单/查询串编码：只保留 RFC 3986 unreserved，其余按 UTF-8 百分号编码。
 *
 * 不用平台自带 API（`URLEncoder` 是 JVM 专属），因为教务把中文站名、线路名
 * 直接塞进查询串，编码规则必须两端一致，否则签名对得上、服务端却解析歪。
 */
fun percentEncode(value: String): String {
    val hex = "0123456789ABCDEF"
    val sb = StringBuilder(value.length * 3)
    for (b in value.encodeToByteArray()) {
        val v = b.toInt() and 0xFF
        val c = v.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
            sb.append(c)
        } else {
            sb.append('%').append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
    }
    return sb.toString()
}

fun buildQuery(params: Map<String, String>): String {
    val sb = StringBuilder()
    for ((k, v) in params) {
        if (sb.isNotEmpty()) sb.append('&')
        sb.append(percentEncode(k)).append('=').append(percentEncode(v))
    }
    return sb.toString()
}
