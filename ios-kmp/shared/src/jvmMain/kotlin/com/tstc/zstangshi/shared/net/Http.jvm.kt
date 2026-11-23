package com.tstc.zstangshi.shared.net

import java.net.HttpURLConnection
import java.net.URL

actual suspend fun httpGetText(url: String, headers: Map<String, String>): HttpResult {
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        HttpResult(code, body)
    } catch (e: Throwable) {
        HttpResult(0, "", e.message ?: e.toString())
    } finally {
        conn?.disconnect()
    }
}
