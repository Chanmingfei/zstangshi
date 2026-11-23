@file:OptIn(ExperimentalForeignApi::class)

package com.tstc.zstangshi.shared.net

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
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
 * 三个 K/N 特有的坑（都是编译期报出来的，记下来省得下次再踩）：
 *   1. `NSMutableURLRequest.requestWithURL()` 的静态类型是只读的 `NSURLRequest`，
 *      调不到 `setValue:forHTTPHeaderField:` —— 必须用构造器 `NSMutableURLRequest(uRL = …)`。
 *   2. `HTTPMethod` 在 Kotlin/Native 里没有暴露（NSMutableURLRequest 的重声明没生成）。
 *      好在 NSURLRequest 默认就是 GET，直接不设。
 *   3. ObjC 带 completionHandler 的重载必须写 `completionHandler = { … }`，
 *      漏掉标签会解析成无回调版本，然后报「参数太多」。
 */
actual suspend fun httpGetText(url: String, headers: Map<String, String>): HttpResult =
    suspendCoroutine { cont ->
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            cont.resume(HttpResult(0, "", "URL 非法：$url"))
            return@suspendCoroutine
        }
        val request = NSMutableURLRequest(uRL = nsUrl)
        for ((k, v) in headers) {
            request.setValue(v, forHTTPHeaderField = k)
        }

        val task = NSURLSession.sharedSession.dataTaskWithRequest(
            request,
            completionHandler = { data: NSData?, response: NSURLResponse?, error: NSError? ->
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
            }
        )
        task.resume()
    }
