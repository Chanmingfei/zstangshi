package com.tstc.zstangshi.shared.jw

import com.tstc.zstangshi.shared.Js

/**
 * 教务页面/接口的脏活解析 —— 从 Android `JwApi` 的 private helper 移植到 commonMain。
 *
 * 这些是纯字符串/JSON 逻辑，没有任何网络与平台 API，所以在 Windows 上就能跑断言。
 * 网络与会话状态按项目既定边界留在 Swift（URLSession 自动管 Cookie + http 降级）。
 *
 * 导出给 Swift 的类型全部是简单类 + List/String，绝不把 [Js] 泄出去
 * （sealed class 跨 K/N 桥接不可靠）。
 */

/** 一个表单字段；Swift 侧把它折进 [String: String]。 */
class FormField(val key: String, val value: String)

/** 一组下拉选项（下标与 ids/names 一一对应，index 0 恒为「全部」空值）。 */
class OptionGroup {
    private val _ids = ArrayList<String>()
    private val _names = ArrayList<String>()
    val ids: List<String> get() = _ids
    val names: List<String> get() = _names

    internal fun add(id: String, name: String) { _ids.add(id); _names.add(name) }
    val size: Int get() = _ids.size
}

// ---------------- HTML ----------------

private val RX_HIDDEN = Regex("<input\\b[^>]*type=[\"']hidden[\"'][^>]*>", RegexOption.IGNORE_CASE)
private val RX_ATTR_CACHE = HashMap<String, Regex>()

private fun attrRegex(key: String): Regex =
    RX_ATTR_CACHE.getOrPut(key) {
        Regex(key + "\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    }

fun htmlAttr(tag: String, key: String): String =
    attrRegex(key).find(tag)?.groupValues?.getOrNull(1) ?: ""

/** 抓所有 hidden input 的 name/value（name 缺失退回 id）。 */
fun hiddenInputs(html: String): List<FormField> {
    val out = ArrayList<FormField>()
    for (m in RX_HIDDEN.findAll(html)) {
        val tag = m.value
        var name = htmlAttr(tag, "name")
        if (name.isEmpty()) name = htmlAttr(tag, "id")
        if (name.isNotEmpty()) out.add(FormField(name, htmlAttr(tag, "value")))
    }
    return out
}

fun stripTags(html: String): String = html
    .replace(Regex("<[^>]+>"), " ")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace(Regex("\\s+"), " ")

/** 选课页顶部「当前不在选课时间窗」原文提示。 */
fun noticeOf(html: String): String {
    val m = Regex("(?s)<div[^>]*id=[\"']nodata[\"'][^>]*>(.*?)</div>", RegexOption.IGNORE_CASE)
        .find(html) ?: return ""
    val s = stripTags(m.groupValues[1]).trim()
    return if (s.length in 3 until 120) s else ""
}

/** 已选课程是服务端渲染的 HTML 片段，按单元格抓课程名。 */
fun chosenNames(html: String): List<String> {
    val out = ArrayList<String>()
    for (m in Regex("<td[^>]*>\\s*([^<>]{2,40})\\s*</td>", RegexOption.IGNORE_CASE).findAll(html)) {
        if (out.size >= 30) break
        val s = stripTags(m.groupValues[1]).trim().replace(Regex("\\s+"), "")
        if (s.length < 2 || Regex("[0-9\\W]+").matches(s)) continue
        if (s.contains("课程") && s.length < 8) continue
        if (!out.contains(s)) out.add(s)
    }
    return out
}

/** `<select id|name="xxx">` 的全部 option（含「全部」空值项，下标 0）。 */
fun selectOptions(html: String, id: String): OptionGroup {
    val out = OptionGroup()
    out.add("", "全部")
    val q = Regex.escape(id)
    val sel = Regex(
        "<select[^>]*(?:id|name)=[\"']" + q + "[\"'][^>]*>([\\s\\S]*?)</select>",
        RegexOption.IGNORE_CASE).find(html) ?: return out
    val opt = Regex("<option[^>]*value=[\"']([^\"']*)[\"'][^>]*>([^<]*)<", RegexOption.IGNORE_CASE)
    for (m in opt.findAll(sel.groupValues[1])) {
        if (out.size >= 4000) break
        val v = m.groupValues[1].trim()
        if (v.isEmpty()) continue
        val n = m.groupValues[2].trim()
        out.add(v, if (n.isEmpty()) v else n)
    }
    return out
}

// ---------------- JSON ----------------

/** 字段名大小写不敏感地取第一个非空值（教务 kcmc/KCMC 混用）。 */
fun pickField(o: Js.Obj, keys: List<String>): String {
    for (k in keys) {
        val v = o.optString(k)
        if (v.trim().isNotEmpty()) return v.trim()
    }
    for ((real, v) in o.map) {
        for (k in keys) {
            if (real.equals(k, ignoreCase = true)) {
                val s = when (v) {
                    is Js.Str -> v.value
                    is Js.Num -> v.raw
                    is Js.Bool -> if (v.value) "true" else "false"
                    else -> ""
                }
                if (s.trim().isNotEmpty()) return s.trim()
            }
        }
    }
    return ""
}

fun pickField(o: Js.Obj, vararg keys: String): String = pickField(o, keys.toList())

/** 从多组候选字段里各取一个非空值（用于 jg_id/jgdm 这种同义字段）。 */
fun pickAny(o: Js.Obj, vararg groups: String): String {
    for (g in groups) {
        val v = pickField(o, g)
        if (v.isNotEmpty()) return v
    }
    return ""
}

/** 教务封套不一致：items/rows/data/tmpList/list/裸数组都认。 */
fun firstArray(root: Js): List<Js.Obj> {
    var arr: Js.Arr? = null
    if (root is Js.Arr) {
        arr = root
    } else if (root is Js.Obj) {
        for (k in arrayOf("items", "rows", "data", "tmpList", "list")) {
            val a = root.optArr(k)
            if (a != null) { arr = a; break }
        }
        if (arr == null) {
            // 有些接口把数组塞在 rst/queryModel 之类子对象里，再找一层
            for ((_, v) in root.map) {
                if (v is Js.Obj) {
                    for (k in arrayOf("items", "rows", "data", "tmpList", "list")) {
                        val a = v.optArr(k)
                        if (a != null) { arr = a; break }
                    }
                }
                if (arr != null) break
            }
        }
    }
    return arr?.items?.mapNotNull { it as? Js.Obj } ?: emptyList()
}

fun parseJson(text: String): Js? = Js.parse(text)
