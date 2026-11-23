@file:OptIn(ExperimentalForeignApi::class)

package com.tstc.zstangshi.shared.net

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * iOS 实现：NSURLSession + 协程桥接。
 *
 * 只用到 stdlib 的 [suspendCoroutine]，没有 kotlinx-coroutines 依赖 ——
 * 共享层至今仍保持「只用 Kotlin 标准库」，这是它能同时被 kotlinc 1.9（Android 手写链）
 * 和 Kotlin/Native 2.4（iOS）编译的前提。
 *
 * 注意：NSURLSession 的回调在后台队列，resume 是线程安全的；
 * 但 continuation 只能 resume 一次，所以成功/失败分支要互斥（这里用 if/else 保证）。
 */
actual suspend fun httpGetText(url: String, headers: Map<String, String>): HttpResult =
    suspendCoroutine { cont ->
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            cont.resume(HttpResult(0, "", "URL 非法：$url"))
            return@suspendCoroutine
        }
        // 必须用构造器：+requestWithURL: 声明在 NSURLRequest 上，返回静态类型是只读的，
        // 拿不到 HTTPMethod 的 setter，也调不到 setValue:forHTTPHeaderField:（会连锁报错）。
        val request = NSMutableURLRequest(uRL = nsUrl)
        // K/N 把 -setHTTPMethod: 映射成属性而不是方法，写方法名会「未解析」
        request.HTTPMethod = "GET"
        for ((k, v) in headers) {
            request.setValue(v, forHTTPHeaderField = k)
        }

        NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            if (error != null) {
                cont.resume(HttpResult(0, "", error.localizedDescription))
            } else {
                val http = response as? NSHTTPURLResponse
                val code = http?.statusCode?.toInt() ?: 0
                val text = if (data == null) {
                    ""
                } else {
                    NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString() ?: ""
                }
                cont.resume(HttpResult(code, text))
            }
        }.resume()
    }
