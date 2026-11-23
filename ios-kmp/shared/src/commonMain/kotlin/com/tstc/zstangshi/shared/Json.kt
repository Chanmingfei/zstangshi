package com.tstc.zstangshi.shared

/**
 * 极简 JSON —— 替代 `org.json`，让解析代码可以原样搬进 KMP 的 commonMain。
 *
 * 为什么不用 kotlinx.serialization：
 *   教务返回的是「字段名动态、类型混杂」的脏 JSON（数字有时是字符串、字段可能缺失），
 *   用 @Serializable 得为每个接口手写一套模型，等于把 4,700 行解析代码全部重写。
 *   这里只提供一个 org.json 语义的兼容层，解析代码一行不动。
 *
 * 语义对齐点（与 org.json 一致，别改）：
 *   - `optString` 对数字返回它的原始字面量（"2026" 而不是 "2026.0"）；
 *   - `optInt` 解析失败返回默认值，不抛异常；
 *   - 重复键后者覆盖前者。
 */
sealed class Js {

    object Null : Js()

    class Bool(val value: Boolean) : Js()

    /** 保留原始字面量，避免 double 往返丢精度。 */
    class Num(val raw: String) : Js() {
        fun toIntOrNull(): Int? = raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
        fun toDoubleOrNull(): Double? = raw.toDoubleOrNull()
    }

    class Str(val value: String) : Js()

    class Arr(val items: MutableList<Js> = ArrayList()) : Js() {
        val size: Int get() = items.size
    }

    class Obj(val map: MutableMap<String, Js> = LinkedHashMap()) : Js() {

        fun optObj(key: String): Obj? = map[key] as? Obj

        fun optArr(key: String): Arr? = map[key] as? Arr

        fun optString(key: String, def: String = ""): String {
            return when (val v = map[key]) {
                null, is Null -> def
                is Str -> v.value
                is Num -> v.raw
                is Bool -> if (v.value) "true" else "false"
                else -> def
            }
        }

        fun optInt(key: String, def: Int = 0): Int {
            return when (val v = map[key]) {
                is Num -> v.toIntOrNull() ?: def
                is Str -> v.value.trim().toIntOrNull()
                    ?: v.value.trim().toDoubleOrNull()?.toInt() ?: def
                is Bool -> if (v.value) 1 else 0
                else -> def
            }
        }

        fun optBoolean(key: String, def: Boolean = false): Boolean {
            return when (val v = map[key]) {
                is Bool -> v.value
                is Str -> v.value.equals("true", true) || v.value == "1"
                is Num -> v.toIntOrNull() == 1
                else -> def
            }
        }

        fun has(key: String): Boolean = map.containsKey(key)
    }

    companion object {
        /** 解析失败返回 null，不抛异常（教务偶发返回 HTML 错误页）。 */
        fun parse(text: String): Js? = try {
            JsParser(text).let { p ->
                val v = p.readValue()
                p.skipWs()
                v
            }
        } catch (e: Throwable) {
            null
        }
    }
}

private class JsParser(private val s: String) {

    private var i = 0

    fun skipWs() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') i++ else break
        }
    }

    fun readValue(): Js {
        skipWs()
        if (i >= s.length) throw IllegalStateException("EOF")
        return when (val c = s[i]) {
            '{' -> readObj()
            '[' -> readArr()
            '"' -> Js.Str(readString())
            't' -> { expect("true"); Js.Bool(true) }
            'f' -> { expect("false"); Js.Bool(false) }
            'n' -> { expect("null"); Js.Null }
            else -> if (c == '-' || c in '0'..'9') readNum() else throw IllegalStateException("bad char '$c' at $i")
        }
    }

    private fun expect(word: String) {
        if (!s.startsWith(word, i)) throw IllegalStateException("expect $word at $i")
        i += word.length
    }

    private fun readObj(): Js.Obj {
        val o = Js.Obj()
        i++ // {
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return o }
        while (true) {
            skipWs()
            val k = readString()
            skipWs()
            if (i >= s.length || s[i] != ':') throw IllegalStateException("expect : at $i")
            i++
            o.map[k] = readValue()
            skipWs()
            if (i >= s.length) throw IllegalStateException("unterminated object")
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; return o }
                else -> throw IllegalStateException("expect , or } at $i")
            }
        }
    }

    private fun readArr(): Js.Arr {
        val a = Js.Arr()
        i++ // [
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return a }
        while (true) {
            a.items.add(readValue())
            skipWs()
            if (i >= s.length) throw IllegalStateException("unterminated array")
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; return a }
                else -> throw IllegalStateException("expect , or ] at $i")
            }
        }
    }

    private fun readString(): String {
        if (i >= s.length || s[i] != '"') throw IllegalStateException("expect string at $i")
        i++
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> { i++; return sb.toString() }
                c == '\\' -> {
                    i++
                    if (i >= s.length) throw IllegalStateException("bad escape")
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) throw IllegalStateException("bad \\u")
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalStateException("unknown escape $e")
                    }
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        throw IllegalStateException("unterminated string")
    }

    private fun readNum(): Js.Num {
        val start = i
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                    ((s[i] == '-' || s[i] == '+') && (s[i - 1] == 'e' || s[i - 1] == 'E')))
        ) i++
        return Js.Num(s.substring(start, i))
    }
}
