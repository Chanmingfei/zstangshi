package com.tstc.zstangshi.shared.crypto

/**
 * 平台能力：MD5 摘要 与 AES/ECB/PKCS5 解密。
 *
 * 为什么是 expect/actual 而不是自己写纯 Kotlin 实现：
 *   - MD5 与 AES 都属于「有标准答案」的算法，自己实现只增加出错面；
 *   - 两端系统都有成熟实现：Android/JVM 用 `MessageDigest` / `javax.crypto`，
 *     iOS 用 CommonCrypto（Kotlin/Native 的 `platform.CoreCrypto` 直接可用，**无需 cinterop**）。
 *   追客（Trackr）的 `ChelaileCipher.ios.kt` 就是这条路，属于已验证可行的写法。
 *
 * PKCS5 与 PKCS7 在 16 字节分组上完全等价 —— iOS 只有 PKCS7 选项，直接用即可。
 */

/** 返回 16 字节摘要。 */
expect fun md5(input: ByteArray): ByteArray

/**
 * AES/ECB/PKCS5Padding 解密。密钥长度决定 AES-128/192/256（32 字节 = AES-256）。
 * 解密失败抛异常。
 */
expect fun aesEcbPkcs5Decrypt(cipherBytes: ByteArray, key: ByteArray): ByteArray

/** 小写十六进制，用于签名。 */
fun ByteArray.toLowerHex(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
    }
    return sb.toString()
}

fun md5Hex(text: String): String = md5(text.encodeToByteArray()).toLowerHex()

/**
 * Base64 解码（手写，不用 `kotlin.io.encoding.Base64`）。
 *
 * 原因：那个 API 在 1.9 是实验性的、2.2 之后才稳定，同一个文件跨两个编译器版本
 * 要么警告要么要 @OptIn。而这里只需要「解码，允许换行/缺省填充」这一种行为，40 行足够。
 */
object Base64Lite {

    private val MAP = IntArray(256) { -1 }.also { m ->
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in alphabet.indices) m[alphabet[i].code] = i
        m['-'.code] = 62   // URL-safe 变体
        m['_'.code] = 63
    }

    /** 允许空白、允许缺省 `=`。遇到非法字符抛异常。 */
    fun decode(input: String): ByteArray {
        val out = ArrayList<Byte>(input.length * 3 / 4 + 3)
        var buf = 0
        var bits = 0
        for (c in input) {
            if (c == '=' || c.isWhitespace()) continue
            val v = MAP[c.code]
            require(v >= 0) { "非法 base64 字符 '$c'" }
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buf ushr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}

fun base64Decode(text: String): ByteArray = Base64Lite.decode(text)
