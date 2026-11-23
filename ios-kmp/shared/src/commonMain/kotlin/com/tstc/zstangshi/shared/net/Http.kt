package com.tstc.zstangshi.shared.net

/**
 * URL / 查询串工具（纯计算，无平台依赖）。
 *
 * 这里**故意不含 HTTP 调用本身**。原因：
 *   车来了要求必须带 `Referer`（缺了直接 400），而 Kotlin/Native 的 Foundation 绑定
 *   没暴露 `NSMutableURLRequest.setValue:forHTTPHeaderField:` 与带 completionHandler 的
 *   `dataTaskWithRequest`（两个都在编译期报 unresolved），想设请求头就得绕一大圈。
 *   而 Swift 侧的 URLSession 又成熟又好写 —— 所以分工是：
 *     Kotlin：拼 URL、签名、解密、解析（全部是纯函数，可以在 Windows 上跑断言）
 *     Swift：发请求（带请求头、超时、错误映射）
 *   代价是每个接口多几行 Swift 胶水；收益是共享层保持零平台依赖、零网络状态。
 */

/** 表单/查询串编码：只保留 RFC 3986 unreserved，其余按 UTF-8 百分号编码。 */
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
