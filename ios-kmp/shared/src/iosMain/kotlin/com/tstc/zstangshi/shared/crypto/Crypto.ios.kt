@file:OptIn(ExperimentalForeignApi::class)

package com.tstc.zstangshi.shared.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

/**
 * iOS 实现：CommonCrypto。
 *
 * `platform.CoreCrypto` 是 Kotlin/Native 随附的平台库，不需要写 cinterop 定义、
 * 也不需要 pod —— 这是公交功能能在 iOS 上跑起来的关键一步。
 * 写法对齐追客（Trackr）的 `ChelaileCipher.ios.kt`，属于已在真机验证过的路径。
 */

actual fun md5(input: ByteArray): ByteArray {
    val out = ByteArray(CC_MD5_DIGEST_LENGTH)
    input.usePinned { inPin ->
        out.usePinned { outPin ->
            @Suppress("DEPRECATION")
            CC_MD5(
                if (input.isEmpty()) null else inPin.addressOf(0),
                input.size.convert(),
                outPin.addressOf(0).reinterpret<UByteVar>()
            )
        }
    }
    return out
}

actual fun aesEcbPkcs5Decrypt(cipherBytes: ByteArray, key: ByteArray): ByteArray {
    // 多留一个分组：CommonCrypto 的 PKCS7 去填充在输出缓冲里完成
    val out = ByteArray(cipherBytes.size + kCCBlockSizeAES128.toInt())
    return memScoped {
        val moved = alloc<size_tVar>()
        val status = cipherBytes.usePinned { inPin ->
            key.usePinned { keyPin ->
                out.usePinned { outPin ->
                    CCCrypt(
                        kCCDecrypt,
                        kCCAlgorithmAES,
                        kCCOptionPKCS7Padding or kCCOptionECBMode,
                        keyPin.addressOf(0),
                        key.size.convert(),
                        null,
                        if (cipherBytes.isEmpty()) null else inPin.addressOf(0),
                        cipherBytes.size.convert(),
                        outPin.addressOf(0),
                        out.size.convert(),
                        moved.ptr
                    )
                }
            }
        }
        if (status != kCCSuccess) error("AES 解密失败，CommonCrypto 状态码 $status")
        out.copyOf(moved.value.toInt())
    }
}
