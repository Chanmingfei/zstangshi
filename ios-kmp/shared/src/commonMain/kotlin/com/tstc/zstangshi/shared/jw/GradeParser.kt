package com.tstc.zstangshi.shared.jw

import com.tstc.zstangshi.shared.Js

/** 一条成绩。`score` 可能是数字也可能是「优秀/合格/缓考」这类文字，原样保留。 */
class GradeRow(
    val name: String,
    val score: String,
    val credit: String,
    val point: String,
    val term: String,
    val kind: String,
    /** 平时/期末等分项，形如「平时 90 · 期末 85」；来自 N305007 明细接口。 */
    val detail: String = ""
)

class GradeResult {
    var ok: Boolean = false
    var message: String = ""
    private val _rows = ArrayList<GradeRow>()
    val rows: List<GradeRow> get() = _rows

    internal fun add(r: GradeRow) { _rows.add(r) }

    /** 总学分（能解析成数字的才计入）。 */
    fun totalCredits(): Double = _rows.sumOf { it.credit.toDoubleOrNull() ?: 0.0 }

    /** 加权平均分（按学分加权；文字成绩不计入）。 */
    fun weightedAverage(): Double {
        var sum = 0.0
        var weight = 0.0
        for (r in _rows) {
            val s = r.score.toDoubleOrNull() ?: continue
            val c = r.credit.toDoubleOrNull() ?: 1.0
            sum += s * c
            weight += c
        }
        return if (weight > 0) sum / weight else 0.0
    }

    /** 平均绩点（按学分加权，取 jd 字段）。 */
    fun weightedPoint(): Double {
        var sum = 0.0
        var weight = 0.0
        for (r in _rows) {
            val p = r.point.toDoubleOrNull() ?: continue
            val c = r.credit.toDoubleOrNull() ?: 1.0
            sum += p * c
            weight += c
        }
        return if (weight > 0) sum / weight else 0.0
    }
}

/**
 * 成绩接口解析（正方 `/cjcx/cjcx_cxXsgrcj.html` 的数据接口）。
 *
 * 字段口径照抄安卓 `JwApi`：
 *   kcmc/kcmcmc/courseName、cj/score、xf/credits、jd/xfjd、
 *   xnmmc/xnm + xqmmc/xqm、kcxzmc/kclbmc。
 * 封套不一致（items/rows/data/tmpList/裸数组），按顺序探。
 */
fun parseGrades(json: String): GradeResult = parseGradesWithDetail(json, "")

/**
 * 带平时成绩明细的解析 —— 对齐安卓 `JwApi.grades()` 的双接口口径。
 *
 * 安卓并发请求：
 *   主表  `/cjcx/cjcx_cxXsgrcj.html?doType=query`（总评/学分/绩点）
 *   明细  `/cjcx/cjcx_cxXsKccjList.html?gnmkdm=N305007`（xmblmc/xmcj 分项）
 * 明细按「教学班 jxb_id」和「课名 kcmc」两种键归组，生成「平时 x · 期末 y」，
 * 合并到对应行的 detail。总评行（label 含「总评」）丢弃，避免和主表重复。
 *
 * 明细是「附加」不是「替代」—— detailJson 为空或解析失败时，主表照常显示，
 * 只是没有分项，绝不能拖垮总评（与安卓注释一致）。
 */
fun parseGradesWithDetail(json: String, detailJson: String): GradeResult {
    val r = GradeResult()
    if (json.trim().isEmpty()) {
        r.message = "成绩接口没有响应"
        return r
    }
    val root = Js.parse(json)
    val arr = firstArray(root)
    if (arr == null) {
        r.message = "成绩数据格式不认识"
        return r
    }

    val comps = gradeComponents(detailJson)
    for (item in arr.items) {
        val o = item as? Js.Obj ?: continue
        val name = pick(o, "kcmc", "kcmcmc", "courseName")
        if (name.isEmpty()) continue
        val term = pick(o, "xnmmc", "xnm") + (if (pick(o, "xqmmc", "xqm").isEmpty()) "" else " · " + pick(o, "xqmmc", "xqm"))
        val jxb = pick(o, "jxb_id", "key")
        var parts = if (jxb.isEmpty()) "" else (comps["j:$jxb"] ?: "")
        if (parts.isEmpty()) parts = comps["n:$name"] ?: ""
        r.add(
            GradeRow(
                name = name,
                score = pick(o, "cj", "score"),
                credit = pick(o, "xf", "credits"),
                point = pick(o, "jd", "xfjd"),
                term = term,
                kind = pick(o, "kcxzmc", "kclbmc"),
                detail = parts
            )
        )
    }
    r.ok = r.rows.isNotEmpty()
    r.message = if (r.ok) "" else "教务暂未返回成绩（可能还没出分）"
    return r
}

/** N305007 明细行 → 按教学班 / 课名归组的「平时 x · 期末 y」。总评行丢掉。 */
private fun gradeComponents(json: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    if (json.trim().isEmpty()) return out
    val root = Js.parse(json) ?: return out
    val arr = firstArray(root) ?: return out
    for (item in arr.items) {
        val o = item as? Js.Obj ?: continue
        val label = pick(o, "xmblmc")
        val score = pick(o, "xmcj")
        if (label.isEmpty() || score.isEmpty() || label.contains("总评")) continue
        val text = label + " " + score
        val jxb = pick(o, "jxb_id", "jxbmc")
        val name = pick(o, "kcmc", "kcmcmc")
        addComp(out, "j:$jxb", text)
        addComp(out, "n:$name", text)
    }
    return out
}

private fun addComp(m: MutableMap<String, String>, key: String, text: String) {
    // 对齐安卓：key 至少 3 字符，避免空值把不相干的课程串到一起
    if (key.length < 4) return
    val old = m[key]
    m[key] = if (old.isNullOrEmpty()) text else old + " · " + text
}

private fun pick(o: Js.Obj, vararg keys: String): String {
    for (k in keys) {
        val v = o.optString(k)
        if (v.isNotEmpty()) return v
    }
    return ""
}

private fun firstArray(root: Js?): Js.Arr? = when (root) {
    is Js.Arr -> root
    is Js.Obj -> {
        var found: Js.Arr? = null
        for (k in arrayOf("items", "rows", "data", "tmpList", "list")) {
            val a = root.optArr(k)
            if (a != null) { found = a; break }
        }
        found
    }
    else -> null
}
