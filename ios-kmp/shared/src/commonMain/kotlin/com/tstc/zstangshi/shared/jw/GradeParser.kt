package com.tstc.zstangshi.shared.jw

import com.tstc.zstangshi.shared.Js

/** 一条成绩。`score` 可能是数字也可能是「优秀/合格/缓考」这类文字，原样保留。 */
class GradeRow(
    val name: String,
    val score: String,
    val credit: String,
    val point: String,
    val term: String,
    val kind: String
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
 * 成绩接口解析（正方 `/cjcx/cjcx_cxDgXscj.html` 的数据接口）。
 *
 * 字段口径照抄安卓 `JwApi`：
 *   kcmc/kcmcmc/courseName、cj/score、xf/credits、jd/xfjd、
 *   xnmmc/xnm + xqmmc/xqm、kcxzmc/kclbmc。
 * 封套不一致（items/rows/data/tmpList/裸数组），按顺序探。
 */
fun parseGrades(json: String): GradeResult {
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
    for (item in arr.items) {
        val o = item as? Js.Obj ?: continue
        val name = pick(o, "kcmc", "kcmcmc", "courseName")
        if (name.isEmpty()) continue
        val term = pick(o, "xnmmc", "xnm") + (if (pick(o, "xqmmc", "xqm").isEmpty()) "" else " · " + pick(o, "xqmmc", "xqm"))
        r.add(
            GradeRow(
                name = name,
                score = pick(o, "cj", "score"),
                credit = pick(o, "xf", "credits"),
                point = pick(o, "jd", "xfjd"),
                term = term,
                kind = pick(o, "kcxzmc", "kclbmc")
            )
        )
    }
    r.ok = r.rows.isNotEmpty()
    r.message = if (r.ok) "" else "教务暂未返回成绩（可能还没出分）"
    return r
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
