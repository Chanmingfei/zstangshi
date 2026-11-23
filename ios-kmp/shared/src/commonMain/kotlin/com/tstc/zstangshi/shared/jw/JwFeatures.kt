package com.tstc.zstangshi.shared.jw

import com.tstc.zstangshi.shared.Js
import com.tstc.zstangshi.shared.toJsonString

/**
 * 选课 + 推荐课表的纯逻辑层 —— 对齐 Android `JwApi` 的 selection / recommend* 口径。
 * 网络与会话状态留 Swift；这里只做「HTML→上下文 / JSON→列表 / 表单组装 / 级联过滤 / 课表修补」。
 *
 * 导出给 Swift 的类型：简单类 + `val x: List<T> get()` 只读访问器（K/N 会把
 * MutableList 导成 NSMutableArray，Swift 侧没法 ForEach）。
 */

// ==================== 自主选课 ====================

class SelectionCourse {
    var name: String = ""
    var sub: String = ""
    var value: String = ""
}

class SelectionPool {
    var ok: Boolean = false
    var message: String = ""
    var headline: String = ""
    var note: String = ""
    private val _courses = ArrayList<SelectionCourse>()
    val courses: List<SelectionCourse> get() = _courses
    internal fun addCourse(c: SelectionCourse) { _courses.add(c) }
}

/** 选课页上下文：闸门(iskxk) + 窗口提示 + display 表单。 */
class SelectionContext {
    var gate: String = ""
    var notice: String = ""
    private val _form = ArrayList<FormField>()
    val form: List<FormField> get() = _form
    internal fun addField(k: String, v: String) { _form.add(FormField(k, v)) }
}

private fun fieldValue(fields: List<FormField>, vararg keys: String): String {
    for (k in keys) for (f in fields) if (f.key == k && f.value.isNotEmpty()) return f.value
    return ""
}

fun selectionContext(html: String): SelectionContext {
    val hidden = hiddenInputs(html)
    val c = SelectionContext()
    c.gate = fieldValue(hidden, "iskxk")
    c.notice = noticeOf(html)
    c.addField("xkkz_id", fieldValue(hidden, "firstXkkzId", "xkkz_id"))
    c.addField("kklxdm", fieldValue(hidden, "firstKklxdm", "kklxdm"))
    c.addField("xszxzt", fieldValue(hidden, "xszxzt"))
    c.addField("njdm_id", fieldValue(hidden, "njdm_id"))
    c.addField("zyh_id", fieldValue(hidden, "zyh_id"))
    return c
}

/** display 请求体：上下文字段 + 分页 0。 */
fun selectionDisplayForm(ctx: SelectionContext): List<FormField> {
    val out = ArrayList<FormField>()
    for (f in ctx.form) out.add(FormField(f.key, f.value))
    out.add(FormField("kspage", "0"))
    out.add(FormField("jspage", "0"))
    return out
}

/** part 请求体：display 回页的全部 hidden + 分页 1~15。 */
fun selectionPartForm(displayHidden: List<FormField>): List<FormField> {
    val out = ArrayList<FormField>(displayHidden)
    out.add(FormField("kspage", "1"))
    out.add(FormField("jspage", "15"))
    return out
}

/** 课池 JSON → 课程行。字段名按安卓 pick 顺序。 */
fun parseSelectionPool(json: String): SelectionPool {
    val p = SelectionPool()
    val root = parseJson(json)
    if (root == null) {
        p.message = "课池解析失败：不是合法 JSON"
        return p
    }
    for (o in firstArray(root)) {
        val name = pickField(o, "kcmc", "kcmcmc")
        if (name.isEmpty()) continue
        val teacher = pickField(o, "jsxm", "jsm", "xm")
        val time = pickField(o, "sksj", "jsxx", "jxdd")
        val cap = pickField(o, "jxbrl", "rsyxz")
        val got = pickField(o, "yxzrs", "rsz")
        val c = SelectionCourse()
        c.name = name
        c.sub = (if (teacher.isEmpty()) "" else teacher + " · ") + time
        c.value = if (cap.isEmpty()) "" else got + "/" + cap
        p.addCourse(c)
    }
    p.ok = true
    p.headline = "课池 ${p.courses.size} 门"
    p.note = "课程池与余量以教务自主选课数据为准；本页只读，抢课请到教务网页完成"
    return p
}

// ==================== 推荐课表 ====================

class RecContext {
    var xnm: String = ""
    var xqm: String = ""
    var ok: Boolean = false
    var message: String = ""
    private val _grade = OptionGroup()
    private val _dept = OptionGroup()
    val grade: OptionGroup get() = _grade
    val dept: OptionGroup get() = _dept
    internal fun appendGrade(g: OptionGroup) { for (i in g.ids.indices) _grade.add(g.ids[i], g.names[i]) }
    internal fun appendDept(g: OptionGroup) { for (i in g.ids.indices) _dept.add(g.ids[i], g.names[i]) }
}

/** 推荐课表页 → 学年/学期 + 年级/院系字典。 */
fun recommendContext(html: String): RecContext {
    val c = RecContext()
    c.xnm = htmlSelectedValue(html, "xnm")
    c.xqm = htmlSelectedValue(html, "xqm")
    if (c.xnm.isEmpty() || c.xqm.isEmpty()) {
        c.message = "推荐课表页找不到学年/学期"
        return c
    }
    c.appendGrade(selectOptions(html, "njdm_id"))
    c.appendDept(selectOptions(html, "jg_id"))
    c.ok = true
    return c
}

/** 取 select 的选中值（没有 selected 取第一个 option）。 */
private fun htmlSelectedValue(html: String, id: String): String {
    val q = Regex.escape(id)
    val sel = Regex(
        "<select[^>]*(?:id|name)=[\"']" + q + "[\"'][^>]*>([\\s\\S]*?)</select>",
        RegexOption.IGNORE_CASE).find(html) ?: return ""
    var first = ""
    for (m in Regex("<option[^>]*>", RegexOption.IGNORE_CASE).findAll(sel.groupValues[1])) {
        val v = htmlAttr(m.value, "value")
        if (v.isEmpty()) continue
        if (first.isEmpty()) first = v
        if (m.value.contains("selected", ignoreCase = true)) return v
    }
    return first
}

/** 班级清单 JSON → 行（完整保留每行 JSON 串供级联/表单复用）+ 总数。 */
class ClassList {
    var total: Int = 0
    private val _rows = ArrayList<String>()
    val rows: List<String> get() = _rows
    internal fun addRow(s: String) { _rows.add(s) }
}

fun parseClassList(json: String): ClassList {
    val out = ClassList()
    val root = parseJson(json) ?: return out
    if (root is Js.Obj) out.total = root.optInt("totalResult", 0)
    for (o in firstArray(root)) out.addRow(o.toJsonString())
    return out
}

/** 班级清单分页请求体（listBody + 可选筛选）。 */
fun classListForm(xnm: String, xqm: String, page: Int, filter: List<FormField>): List<FormField> {
    val f = ArrayList<FormField>()
    for (k in arrayOf(
        "xqh_id", "njdm_id", "xb_id", "jg_id", "zyh_id", "zyfx_id",
        "bh_id", "xsdm", "pyccdm", "kclxdm", "kclbdm", "sfzhsjk",
        "kbsjlyqz", "zs", "yf")) f.add(FormField(k, ""))
    f.add(FormField("sfcxxqh", "0"))
    f.add(FormField("xnm", xnm))
    f.add(FormField("xqm", xqm))
    f.add(FormField("page", page.toString()))
    f.add(FormField("rows", "10"))
    f.addAll(filter)
    return f
}

/** 级联选项：从已拉的班级行里抽第 group 级去重（0=年级 1=院系 2=专业 3=班级）。
 *  rows 由 Swift 按已选上级筛选好传进来，这里只负责抽自身字段去重。 */
fun cascadeOptions(rows: List<String>, group: Int): OptionGroup {
    val out = OptionGroup()
    out.add("", "全部")
    val nameFields = arrayOf("njmc", "jgmc", "zymc", "bjmc")
    if (group < 0 || group > 3) return out
    val seen = HashSet<String>()
    for (rowJson in rows) {
        val js = parseJson(rowJson) as? Js.Obj ?: continue
        val id = when (group) {
            0 -> pickField(js, "njdm_id")
            1 -> pickAny(js, "jg_id", "jgdm")
            2 -> pickField(js, "zyh_id")
            else -> pickField(js, "bh_id")
        }
        if (id.isEmpty() || !seen.add(id)) continue
        val nm = pickField(js, nameFields[group])
        out.add(id, if (nm.isEmpty()) id else nm)
    }
    return out
}

/**
 * 四级级联：按已选上级 id 过滤班级行，再抽第 group 级去重选项。
 * group 0=年级 1=院系 2=专业 3=班级；只应用下标 < group 的上级条件
 * （抽取专业时按年级+院系过滤，抽取班级时再叠加专业 UUID）。
 */
fun recommendCascade(
    rows: List<String>,
    gradeId: String,
    deptId: String,
    majorId: String,
    group: Int
): OptionGroup {
    val out = OptionGroup()
    out.add("", "全部")
    if (group < 0 || group > 3) return out
    // 院系在班级行里的字段是 jgdm（页面字典叫 jg_id），两个都认——否则选了院系没结果
    val filterVals = arrayOf(gradeId, deptId, majorId)
    val nameFields = arrayOf("njmc", "jgmc", "zymc", "bjmc")
    val seen = HashSet<String>()
    for (rowJson in rows) {
        val js = parseJson(rowJson) as? Js.Obj ?: continue
        var ok = true
        for (i in 0 until minOf(group, 3)) {
            val actual = when (i) {
                0 -> pickField(js, "njdm_id")
                1 -> pickAny(js, "jg_id", "jgdm")
                else -> pickField(js, "zyh_id")
            }
            if (filterVals[i].isNotEmpty() && actual != filterVals[i]) {
                ok = false
                break
            }
        }
        if (!ok) continue
        val id = when (group) {
            0 -> pickField(js, "njdm_id")
            1 -> pickAny(js, "jg_id", "jgdm")
            2 -> pickField(js, "zyh_id")
            else -> pickField(js, "bh_id")
        }
        if (id.isEmpty() || !seen.add(id)) continue
        val nm = pickField(js, nameFields[group])
        out.add(id, if (nm.isEmpty()) id else nm)
    }
    return out
}

/** 按已选年级/院系/专业过滤班级行（空 id 不过滤），返回命中的行。 */
fun filterClassRows(rows: List<String>, gradeId: String, deptId: String, majorId: String): List<String> {
    val out = ArrayList<String>()
    for (rowJson in rows) {
        val js = parseJson(rowJson) as? Js.Obj ?: continue
        if (gradeId.isNotEmpty() && pickField(js, "njdm_id") != gradeId) continue
        if (deptId.isNotEmpty() && pickAny(js, "jg_id", "jgdm") != deptId) continue
        if (majorId.isNotEmpty() && pickField(js, "zyh_id") != majorId) continue
        out.add(rowJson)
    }
    return out
}

/** 点班级查课表需要的预览表单（ylMap 口径）。 */
fun recommendPreviewForm(rowJson: String): List<FormField> {
    val js = parseJson(rowJson) as? Js.Obj ?: return emptyList()
    val f = ArrayList<FormField>()
    for (k in arrayOf(
        "xnm", "xqm", "xnmc", "xqmmc", "xqh_id", "njdm_id", "zyh_id",
        "bh_id", "tjkbzdm", "tjkbzxsdm", "zymc", "jgmc", "njmc", "bj",
        "xkrs", "jsxm", "lxdh", "bh")) f.add(FormField(k, pickField(js, k)))
    for (k in arrayOf("zs", "xsdm", "kclxdm", "kclbdm", "kbsjlyqz", "yf")) f.add(FormField(k, ""))
    f.add(FormField("zxszjjs", "false"))
    f.add(FormField("akcxqjchb", "false"))
    f.add(FormField("kzlx", "ck"))
    f.add(FormField("sfcxxqh", if (pickField(js, "xqh_id").isEmpty()) "0" else "1"))
    return f
}

/** 班级显示名。 */
fun className(rowJson: String): String {
    val js = parseJson(rowJson) as? Js.Obj ?: return ""
    return pickField(js, "bjmc", "bj", "bh_id")
}

/** 班级副标题：年级 · 专业 · 学院。 */
fun classSubtitle(rowJson: String): String {
    val js = parseJson(rowJson) as? Js.Obj ?: return ""
    return pickField(js, "njmc", "njdm_id") + " · " +
        pickField(js, "zymc", "zyh_id") + " · " + pickField(js, "jgmc")
}

/** 班级人数。 */
fun classSize(rowJson: String): String {
    val js = parseJson(rowJson) as? Js.Obj ?: return ""
    return pickField(js, "xkrs")
}

/**
 * 推荐课表 kbList 修补：rs←jxbrs、yxrs←xkrs，再交回 Swift 喂 KbParser。
 * 安卓 recommendKb 就做了这一步，否则人数列为空。
 */
fun repairKbJson(json: String): String {
    val root = parseJson(json) ?: return json
    if (root !is Js.Obj) return json
    val list = root.optArr("kbList") ?: return json
    for (v in list.items) {
        val o = v as? Js.Obj ?: continue
        if (pickField(o, "rs").isEmpty()) {
            val jxbrs = pickField(o, "jxbrs")
            if (jxbrs.isNotEmpty()) o.map["rs"] = Js.Str(jxbrs)
        }
        if (pickField(o, "yxrs").isEmpty()) {
            val xkrs = pickField(o, "xkrs")
            if (xkrs.isNotEmpty()) o.map["yxrs"] = Js.Str(xkrs)
        }
    }
    return root.toJsonString()
}
