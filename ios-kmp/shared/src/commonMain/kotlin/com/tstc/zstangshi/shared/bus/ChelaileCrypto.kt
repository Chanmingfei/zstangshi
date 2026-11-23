package com.tstc.zstangshi.shared.bus

import com.tstc.zstangshi.shared.Js
import com.tstc.zstangshi.shared.crypto.aesEcbPkcs5Decrypt
import com.tstc.zstangshi.shared.crypto.base64Decode
import com.tstc.zstangshi.shared.crypto.md5Hex

/**
 * 车来了（Chelaile）通信层的签名、解密与拆壳 —— 从 Android
 * `bus/ChelaileClient.kt` 移植，协议语义一字不改。
 *
 * 协议要点：
 *   1. 请求参数按**插入顺序**拼成 `"k"="v"&"k2"="v2"`，末尾追加盐 `qwihrnbtmj`，取 MD5 小写；
 *   2. 响应外层是 `**YGKJ{...}**YGKJ`（前后缀可能是别的杂字符），先按括号计数取出 JSON；
 *   3. `jsonr.data.encryptResult` 是 Base64 的密文，
 *      AES/ECB/PKCS5 解密，两把密钥（微信小程序 / H5）依次尝试。
 */
object ChelaileCrypto {

    /** 签名盐。改这个值，所有请求都会被服务端拒绝。 */
    const val SIGN_SALT = "qwihrnbtmj"

    /** 微信小程序接口密钥（32 个 ASCII 字符 → AES-256）。 */
    const val KEY_WX = "FF32AE65FBFD19414EAAFF6291A54B42"

    /** H5 接口备用密钥。 */
    const val KEY_H5 = "422556651C7F7B2B5C266EED06068230"

    /**
     * 计算 `cryptoSign`。
     *
     * 注意 `params` 必须是插入有序的 Map（LinkedHashMap / mapOf 的顺序字面量），
     * 因为服务端按字符串逐字比对 —— 顺序变了签名就错。
     */
    fun sign(params: Map<String, String>): String {
        val sb = StringBuilder()
        for ((k, v) in params) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append('"').append(k).append("\"=\"").append(v).append('"')
        }
        sb.append(SIGN_SALT)
        return md5Hex(sb.toString())
    }

    /**
     * AES/ECB/PKCS5 解密。两把密钥都试，只有解出来像 JSON 才算成功。
     *
     * 「像 JSON」这个判据不能省：错误密钥约有 1/256 概率碰巧通过填充校验，
     * 解出一串乱码。追客那边也踩过同一个坑。
     */
    fun decrypt(b64: String): String {
        val cipherBytes = base64Decode(b64)
        var lastError: Throwable? = null
        for (key in arrayOf(KEY_WX, KEY_H5)) {
            try {
                val text = aesEcbPkcs5Decrypt(cipherBytes, key.encodeToByteArray())
                    .decodeToString()
                    .trim()
                if (text.startsWith("{") || text.startsWith("[")) return text
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw IllegalStateException("车来了响应解密失败（两把密钥都不对）", lastError)
    }

    /**
     * 拆壳：剥掉前后缀取 JSON 主体；`jsonr.data` 是字符串或含 `encryptResult` 的对象时解密。
     *
     * 返回 null 表示响应根本不是 JSON（比如网关返回的 HTML 错误页）。
     */
    fun extract(raw: String): Js.Obj? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        val end = jsonEnd(raw, start)
        val env = Js.parse(raw.substring(start, end)) as? Js.Obj ?: return null

        val jsonr = env.optObj("jsonr")
        val data: Js? = jsonr?.map?.get("data") ?: env.map["data"]

        return when (data) {
            is Js.Str -> {
                val s = data.value.trim()
                if (s.isEmpty()) return Js.Obj()
                // 明文 JSON 与密文都可能出现在这里：先当密文试，不行再当明文
                (runCatching { Js.parse(decrypt(s)) }.getOrNull() as? Js.Obj)
                    ?: (Js.parse(s) as? Js.Obj)
            }

            is Js.Obj -> {
                val enc = data.optString("encryptResult")
                if (enc.isNotEmpty()) Js.parse(decrypt(enc)) as? Js.Obj else data
            }

            else -> jsonr ?: env
        }
    }

    /** 括号计数找 JSON 结尾，跳过字符串字面量里的花括号与转义。 */
    fun jsonEnd(raw: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            if (c == '"') inStr = true
            else if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return i + 1
            }
        }
        val last = raw.lastIndexOf('}')
        return if (last > start) last + 1 else raw.length
    }
}
