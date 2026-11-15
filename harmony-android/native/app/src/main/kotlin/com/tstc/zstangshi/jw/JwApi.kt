package com.tstc.zstangshi.jw

import com.tstc.zstangshi.data.Store
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.regex.Pattern

/**
 * 教务四项查询功能（成绩 / 学分绩点 / 选课 / 推荐课表）的原生实现。
 *
 * 旧版 WebView 架构里这些是 H5 页面，路径与字段都来自 2026-11 的 PC / 真机实测
 * （docs/diagnostics 与 data/jwxtClient.js ENDPOINTS）。这里全部翻成 framework API：
 * 复用 {@link JwClient} 的 Cookie 会话，JSON 用系统自带 org.json，不引第三方库。
 *
 * 口径：只读教务原文，不本地换算绩点、不编造课程；抓不到就如实把原因带回去。
 */
class JwApi(store: Store) {

    /** 一行展示数据：主标题 / 副标题 / 右侧值 / 附加说明。 */
    class Row {
        @JvmField
        val title: String

        @JvmField
        val sub: String

        @JvmField
        val value: String

        @JvmField
        val extra: String

        /** 分组标签（成绩用它按学期筛选）。 */
        @JvmField
        val tag: String

        constructor(t: String, s: String, v: String, e: String) : this(t, s, v, e, "")

        constructor(t: String, s: String, v: String, e: String, tag: String) {
            title = t
            sub = s
            value = v
            extra = e
            this.tag = tag
        }
    }

    class Data {
        @JvmField
        var ok: Boolean = false

        @JvmField
        var message: String = ""

        @JvmField
        var headline: String = ""

        @JvmField
        var note: String = ""

        @JvmField
        val rows: MutableList<Row> = ArrayList()

        /** 推荐课表的级联筛选：一组 = 一个下拉框的候选项。 */
        @JvmField
        val filterKeys: MutableList<String> = ArrayList()

        @JvmField
        val filterLabels: MutableList<String> = ArrayList()

        @JvmField
        val filterIds: MutableList<Array<String>> = ArrayList()

        @JvmField
        val filterNames: MutableList<Array<String>> = ArrayList()

        /** 推荐课表的班级原始行，点某一行时按 index 回查课表。 */
        @JvmField
        val classes: MutableList<JSONObject> = ArrayList()

        /** 推荐课表接口原文，交给 JwglxtFetcher 解析成 Timetable。 */
        @JvmField
        var kbJson: String = ""

        /** 落盘快照的时间戳，界面上如实告诉用户数据是什么时候取的。 */
        @JvmField
        var at: Long = 0L

        /** 成绩等功能页的本地快照：进页秒开，只有下拉刷新才重新联网。 */
        fun toJson(): String {
            try {
                val o = JSONObject()
                o.put("ok", ok)
                o.put("message", message)
                o.put("headline", headline)
                o.put("note", note)
                o.put("at", if (at == 0L) System.currentTimeMillis() else at)
                val a = JSONArray()
                for (r in rows) {
                    val x = JSONObject()
                    x.put("t", r.title)
                    x.put("s", r.sub)
                    x.put("v", r.value)
                    x.put("e", r.extra)
                    x.put("g", r.tag)
                    a.put(x)
                }
                o.put("rows", a)
                return o.toString()
            } catch (e: Exception) {
                return ""
            }
        }

        companion object {
            @JvmStatic
            fun fromJson(json: String): Data {
                val d = Data()
                try {
                    val o = JSONObject(json)
                    d.ok = o.optBoolean("ok")
                    d.message = o.optString("message", "")
                    d.headline = o.optString("headline", "")
                    d.note = o.optString("note", "")
                    d.at = o.optLong("at", 0L)
                    val a = o.optJSONArray("rows")
                    if (a != null) {
                        for (i in 0 until a.length()) {
                            val x = a.optJSONObject(i)
                            if (x == null) continue
                            d.rows.add(
                                Row(
                                    x.optString("t", ""), x.optString("s", ""),
                                    x.optString("v", ""), x.optString("e", ""),
                                    x.optString("g", "")
                                )
                            )
                        }
                    }
                } catch (ignored: Exception) {
                    d.ok = false
                }
                return d
            }
        }
    }

    private val client: JwClient = JwClient(store)
    private val classRows: MutableList<JSONObject> = ArrayList()
    private val recKeys: MutableList<String> = ArrayList()
    private val recIds: MutableList<Array<String>> = ArrayList()
    private val recNames: MutableList<Array<String>> = ArrayList()
    private val recLabels: MutableList<String> = ArrayList()

    /** 当前每一组真实生效的下拉值（联动后会替换页面初始解析的那份）。 */
    private val activeIds: MutableList<Array<String>> = ArrayList()

    /** 级联选项读写（recIds/activeIds）的同步锁，避免后台线程并发改坏。 */
    private val cascadeLock = Any()
    private var recXnm: String = ""
    private var recXqm: String = ""
    private var loginMsg: String = ""
    private var savedUser: String = ""
    private var savedPass: String = ""

    fun login(user: String, pass: String): Boolean {
        savedUser = user
        savedPass = pass
        val r = client.login(user, pass)
        loginMsg = r.message
        return r.ok
    }

    fun loginMessage(): String {
        return loginMsg
    }

    // ---------- 成绩 ----------

    fun grades(): Data {
        return grades(false)
    }

    /** force=true 只由「下拉刷新」触发，其余情况一律读缓存。 */
    fun grades(force: Boolean): Data {
        val c = gradeCache
        if (!force && c != null && c.ok
            && System.currentTimeMillis() - gradeCacheAt < GRADE_TTL
        ) {
            android.util.Log.d("ZSTS-JW", "成绩命中进程缓存，跳过网络")
            return c
        }
        val d = networkGrades()
        if (d.ok) {
            d.at = System.currentTimeMillis()
            gradeCache = d
            gradeCacheAt = d.at
        }
        return d
    }

    /**
     * 老版口径：页面壳只是壳，真实数据在两个 POST 里；两者没有依赖，并行发。
     * 明细（N305007）是「附加」不是「替代」——它失败只少分项，绝不能拖垮总评。
     */
    private fun networkGrades(): Data {
        val d = Data()
        val f = LinkedHashMap<String, String>()
        f["xnm"] = ""
        f["xqm"] = ""
        f["zd_fzdm"] = "N305005-xs"
        f["page"] = "1"
        f["rows"] = "200"
        f["limit"] = "200"
        f["queryModel.showCount"] = "200"
        f["queryModel.pageSize"] = "200"
        f["sidx"] = ""
        f["sord"] = "asc"
        val df = LinkedHashMap<String, String>()
        df["xnm"] = ""
        df["xqm"] = ""
        df["page"] = "1"
        df["rows"] = "1000"
        df["limit"] = "1000"
        df["queryModel.showCount"] = "1000"
        df["queryModel.pageSize"] = "1000"
        df["sidx"] = ""
        df["sord"] = "asc"

        val main = arrayOfNulls<String>(1)
        val detail = arrayOfNulls<String>(1)
        val t1 = Thread({ main[0] = client.post(GRADES_DATA, f) }, "jw-grades")
        val t2 = Thread({ detail[0] = client.post(GRADES_DETAIL, df) }, "jw-detail")
        t1.start()
        t2.start()
        try {
            t1.join()
            t2.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // 少数情况下教务会要求先访问 gnmkdm 页面建立会话语境；只在主表异常时补这一步，
        // 正常路径省掉这一次往返（老版也是两个 POST 直连）。
        var json = main[0]
        if (badPayload(json)) {
            client.get(GRADES_PAGE)
            json = client.post(GRADES_DATA, f)
        }
        if (badPayload(json) && relogin()) {
            json = client.post(GRADES_DATA, f)
        }
        if (json == null || json.trim().isEmpty()) return fail(d, "成绩接口没有响应，请检查网络")

        val comps: Map<String, String> = detail[0]?.let { gradeComponents(it) } ?: LinkedHashMap()

        try {
            val arr = firstArray(JSONObject(json))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) continue
                val name = pick(o, "kcmc", "kcmcmc", "courseName")
                if (name.isEmpty()) continue
                val score = pick(o, "cj", "score")
                val credit = pick(o, "xf", "credits")
                val point = pick(o, "jd", "xfjd")
                val sem = pick(o, "xnmmc", "xnm") + " · " + pick(o, "xqmmc", "xqm")
                val kind = pick(o, "kcxzmc", "kclbmc")
                var parts = comps["j:" + pick(o, "jxb_id", "key")]
                if (parts == null || parts.isEmpty()) parts = comps["n:" + name]
                val sub = sem +
                        (if (credit.isEmpty()) "" else " · " + credit + " 学分") +
                        (if (kind.isEmpty()) "" else " · " + kind) +
                        (if (parts == null || parts.isEmpty()) "" else "\n" + parts)
                d.rows.add(Row(name, sub, if (score.isEmpty()) "—" else score, point, sem))
            }
        } catch (e: Exception) {
            return fail(d, "成绩数据解析失败：" + e.message)
        }
        if (d.rows.isEmpty()) return fail(d, "教务暂未返回成绩（可能还没出分）")
        d.ok = true
        d.headline = d.rows.size.toString() + " 门"
        d.note = "数据来自教务「信息查询 → 学生成绩查询」；五级制/缓考等文字成绩保留原文"
        return d
    }

    /** 会话在免探测窗口里过期时的兜底：强制重登一次，别把登录页当成成绩显示。 */
    private fun relogin(): Boolean {
        if (savedUser.isEmpty() || savedPass.isEmpty()) return false
        client.clearSession()
        val r = client.login(savedUser, savedPass)
        android.util.Log.d("ZSTS-JW", "成绩会话失效，重新登录=" + r.ok)
        return r.ok
    }

    // ---------- 学分绩点 ----------

    fun gpa(): Data {
        val d = Data()
        val html = client.get(GPA_PAGE) ?: return fail(d, "学业情况页打不开，请检查网络")
        // 这一页服务端渲染能到 1MB，整页去标签纯属浪费：只切关键字前后的一段
        // （统计时间另切一小段）。
        val at = html.indexOf("平均学分绩点")
        val around = if (at >= 0)
            html.substring(Math.max(0, at - 200), Math.min(html.length, at + 600))
        else
            html.substring(0, Math.min(html.length, 4000))
        val text = stripTags(around)
        var timeText = ""
        val stat = html.indexOf("统计时间")
        if (stat >= 0) {
            timeText = stripTags(html.substring(stat, Math.min(html.length, stat + 200)))
        }
        android.util.Log.d("ZSTS-JW", "GPA 页 " + html.length + " 字节，含绩点关键字="
                + text.contains("平均学分绩点") + "，被踢回登录=" + html.contains("login_slogin"))
        // 服务端把标签、全半角括号混着渲染，别把括号写死：从「平均学分绩点」往后
        // 40 个非数字字符内抓第一个数字，就是教务印出来的那个权威值。
        val m = Pattern.compile("平均学分绩点[^0-9]{0,40}([0-9]+(?:\\.[0-9]+)?)")
            .matcher(text)
        if (!m.find()) {
            android.util.Log.d("ZSTS-JW", "GPA 页文本开头=" + text.substring(0, Math.min(300, text.length)))
            return fail(d, "学业情况页找不到平均学分绩点，教务可能改版或会话已过期")
        }
        d.ok = true
        d.headline = m.group(1)
        val st = Pattern.compile(
            "统计时间\\s*([0-9-]{10}\\s+[0-9:]{8})\\s*之前有效"
        ).matcher(timeText)
        d.note = if (st.find()) "统计截至 " + st.group(1) + " · 教务权威值，App 不做二次计算"
        else "数据来自教务「信息查询 → 学生学业情况查询」"
        d.rows.add(
            Row(
                "关于这个数字",
                "公选课是否计入、重修取哪次成绩、五级制如何折算，均由教务口径决定", "", ""
            )
        )
        d.rows.add(Row("提示", "此页面信息仅做学业修读情况参考", "", ""))
        return d
    }

    // ---------- 自主选课 ----------

    fun selection(): Data {
        val d = Data()
        val page = client.get(SELECTION) ?: return fail(d, "选课页打不开，请检查网络")
        val hidden = hiddenInputs(page)
        val notice = noticeOf(page)

        // 已选课程（HTML 片段），闸门开不开都先看一眼
        val chosen = client.get(SELECTION_CHOOSED)
        for (name in chosenNames(chosen)) {
            d.rows.add(Row(name, "已选课程", "", ""))
        }

        val gate = hidden["iskxk"] ?: ""
        if ("1" != gate) {
            d.ok = true
            d.headline = "当前不在选课时间窗"
            d.note = if (notice.isEmpty())
                "教务自主选课未开放；开放后这里会显示课池、余量与上课时间"
            else
                notice
            return d
        }

        val disp = LinkedHashMap<String, String>()
        disp["xkkz_id"] = first(hidden, "firstXkkzId", "xkkz_id")
        disp["kklxdm"] = first(hidden, "firstKklxdm", "kklxdm")
        disp["xszxzt"] = first(hidden, "xszxzt")
        disp["njdm_id"] = first(hidden, "njdm_id")
        disp["zyh_id"] = first(hidden, "zyh_id")
        disp["kspage"] = "0"
        disp["jspage"] = "0"
        if (disp["xkkz_id"].orEmpty().isEmpty()) {
            d.ok = true
            d.headline = "选课已开放，但读不到选课上下文"
            d.note = "教务页面没给出 xkkz_id，暂只能在教务网页里选课"
            return d
        }
        val display = client.post(SELECTION_DISPLAY, disp)
        if (display != null) hidden.putAll(hiddenInputs(display))

        val part = LinkedHashMap<String, String>(hidden)
        part["kspage"] = "1"
        part["jspage"] = "15"
        val pool = client.post(SELECTION_PART, part) ?: return fail(d, "课池接口没有响应")
        try {
            val root = JSONObject(pool)
            val scope: JSONObject? = root.optJSONObject("rst")
            val arr = firstArray(if (scope == null) root else scope)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) continue
                val name = pick(o, "kcmc", "kcmcmc")
                if (name.isEmpty()) continue
                val teacher = pick(o, "jsxm", "jsm", "xm")
                val time = pick(o, "sksj", "jsxx", "jxdd")
                val cap = pick(o, "jxbrl", "rsyxz")
                val got = pick(o, "yxzrs", "rsz")
                val sub = (if (teacher.isEmpty()) "" else teacher + " · ") + time
                val value = if (cap.isEmpty()) "" else got + "/" + cap
                d.rows.add(Row(name, sub, value, ""))
            }
        } catch (e: Exception) {
            return fail(d, "课池解析失败：" + e.message)
        }
        d.ok = true
        d.headline = "课池 " + d.rows.size + " 门"
        d.note = "课程池与余量以教务自主选课数据为准；本页只读，抢课请到教务网页完成"
        return d
    }

    // ---------- 推荐课表 ----------

    /**
     * 进页只拉「年级 / 院系 / 专业 / 班级」四级下拉和学期码，不查课表 —— 旧版
     * 推荐课表就是这个形态：勾完条件点「查询」才显示课表。
     */
    /**
     * 推荐课表筛选页：先拿到学期，再把「全量班级清单」一次拉回内存。
     * 之后年级/院系/专业/班级四级联动全部在内存里按行字段过滤，零网络请求，
     * 也避开了「页面字典 zyh_id 短码」和「清单行 zyh_id UUID」对不上的坑。
     */
    fun recommendContext(): Data {
        val d = Data()
        classRows.clear()
        recKeys.clear()
        recIds.clear()
        recNames.clear()
        recLabels.clear()
        activeIds.clear()
        SCOPE_CACHE.clear()
        val page = client.get(RECOMMEND_PAGE) ?: return fail(d, "推荐课表页打不开，请检查网络")
        recXnm = JwClient.selectedOption(page, "xnm")
        recXqm = JwClient.selectedOption(page, "xqm")
        if (recXnm.isEmpty() || recXqm.isEmpty()) return fail(d, "推荐课表页找不到学年/学期")

        // 四个下拉全部用页面自带的全量字典（年级 / 院系 / 专业 / 班级），与老版一致：
        // 年级 / 院系：用页面全量字典（短码，与清单行一致，可直接用）。
        // 专业 / 班级：不取页面字典——页面 zyh_id 是短码、和清单行 zyh_id(UUID) 对不上，
        // 会变成"全量不联动"。正确口径对齐老版 web（recommend.js）：选了年级+院系后向
        // 服务端拉「班级清单」行，再从行里提取 专业=zyh_id(UUID)+zymc、班级=bh_id+bjmc，
        // 选中的专业值(UUID)直接交给服务端做码值映射，绝不在客户端拿 UUID 比短码。
        val dict0 = selectOptions(page, "njdm_id")
        val dict1 = selectOptions(page, "jg_id")
        recKeys.add("njdm_id"); recLabels.add("年级")
        recKeys.add("jg_id"); recLabels.add("院系")
        recKeys.add("zyh_id"); recLabels.add("专业")
        recKeys.add("bh_id"); recLabels.add("班级")
        recIds.add(dict0[0]); recNames.add(dict0[1])
        recIds.add(dict1[0]); recNames.add(dict1[1])
        // 专业 / 班级初始为占位，选了年级+院系后由 recommendOptions 从服务端行里填充
        recIds.add(arrayOf("")); recNames.add(arrayOf("全部"))
        recIds.add(arrayOf("")); recNames.add(arrayOf("全部"))
        d.filterKeys.addAll(recKeys)
        d.filterLabels.addAll(recLabels)
        for (g in 0..3) {
            d.filterIds.add(recIds[g])
            d.filterNames.add(recNames[g])
            if (g < activeIds.size) {
                activeIds[g] = recIds[g]
            } else {
                activeIds.add(recIds[g])
            }
        }

        // 不再预热全量班级清单；专业 / 班级按所选年级 / 院系按需向服务端拉取（loadScope）。
        d.ok = true
        d.headline = "筛选条件"
        d.note = "先选年级 / 院系，专业 / 班级会自动按范围加载；选完点「查询」；课表来自教务「推荐课表打印」"
        android.util.Log.d("ZSTS-JW", "推荐课表字典就绪：年级=" + dict0[0].size
                + " 院系=" + dict1[0].size)
        return d
    }

    /** 按选中的四级字典码向服务端查班级（zyh_id 用页面短码，由教务做码值映射）。 */
    fun recommendQuery(picked: IntArray): Data {
        val d = Data()
        classRows.clear()
        if (recXnm.isEmpty() || recXqm.isEmpty()) {
            return fail(d, "请先进入筛选页拉取学年 / 学期")
        }
        // 把选中的四级字典码拼成服务端筛选条件
        val filter = LinkedHashMap<String, String>()
        val fk = arrayOf("njdm_id", "jg_id", "zyh_id", "bh_id")
        for (i in fk.indices) {
            val v = valueOf(picked, i)
            if (v.isNotEmpty()) filter[fk[i]] = v
        }
        if (filter.isEmpty()) {
            return fail(d, "请至少选择一个班级（年级 / 院系 / 专业 / 班级）")
        }
        val rows = fetchAllClasses(client, recXnm, recXqm, filter)
        if (rows.isEmpty()) {
            return fail(d, "没有符合条件的班级，换个筛选条件试试")
        }
        val cap = 80 // 一个班一张卡片，过多时只列前 80 个
        var matched = 0
        for (row in rows) {
            val name = pick(row, "bjmc", "bj", "bh_id")
            if (name.isEmpty()) continue
            matched++
            classRows.add(row)
            if (d.rows.size >= cap) continue
            d.classes.add(row)
            val sub = pick(row, "njmc", "njdm_id") + " · " +
                    pick(row, "zymc", "zyh_id") + " · " + pick(row, "jgmc")
            d.rows.add(Row(name, sub, pick(row, "xkrs") + " 人", ""))
        }
        if (matched == 0) return fail(d, "没有符合条件的班级，换个筛选条件试试")
        if (d.rows.size == 1) {
            val kb = recommendKb(0)
            if (kb.ok) return kb
            d.note = kb.message
        }
        d.ok = true
        d.headline = "匹配 " + matched + " 个班级"
        if (d.note.isEmpty()) {
            d.note = if (matched > cap)
                "仅列出前 " + cap + " 个，请把条件选细一点"
            else
                "点一个班级查看推荐课表"
        }
        return d
    }

    /** 全量清单是否已就绪（就绪后所有联动纯内存、零等待）。 */
    fun cascadeReady(): Boolean {
        val all = CLASS_ROWS
        return all != null && all.isNotEmpty()
    }

    /** 等清单就绪，最多等 ms 毫秒（在后台线程里调用，别在主线程）。 */
    fun waitForRows(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!cascadeReady() && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    /**
     * 逐级联动：完全在内存里按「行字段」过滤全量班级清单。
     * 选了教育学院 → 专业只来自教育学院的行；再选小学教育 → 班级只来自该专业的行。
     * 年级 / 院系 是顶层条件，取页面字典（值跟清单行一致）；专业 / 班级必须逐级收窄，
     * 因此没选上一级时直接提示先选上级，绝不把 467 个班一股脑倒给用户。
     */
    /**
     * 级联候选项（与老版 web recommend.js 一致）：
     * 年级(0)/院系(1) 直接返回页面全量字典；
     * 专业(2)/班级(3) 需先用已选年级/院系向服务端拉「班级清单」行，再从行里提取——
     * 专业 = 行里唯一的 zyh_id(UUID) + zymc；班级 = 按选中专业 UUID 过滤出的 bh_id + bjmc。
     * 选中的值（专业=UUID、班级=bh_id 短码）交给 recommendQuery 拼服务端筛选，由教务映射。
     */
    fun recommendOptions(picked: IntArray, group: Int): Array<Array<String>> {
        synchronized(cascadeLock) {
            if (group == 0 || group == 1) {
                val o = if (group < recIds.size)
                    arrayOf(recIds[group], recNames[group])
                else
                    arrayOf(arrayOf(""), arrayOf("全部"))
                syncOpts(group, o)
                return o
            }
            val grade = valueOf(picked, 0)
            val dept = valueOf(picked, 1)
            if (group == 2) {
                if (grade.isEmpty() && dept.isEmpty()) return placeholder("请先选择年级或院系")
                val rows = loadScope(grade, dept)
                val m = LinkedHashMap<String, String>()
                for (r in rows) {
                    val id = pick(r, "zyh_id")
                    val nm = pick(r, "zymc")
                    if (id.isNotEmpty() && !m.containsKey(id)) m[id] = if (nm.isEmpty()) id else nm
                }
                if (m.isEmpty()) return placeholder("该范围暂无专业")
                val o = toArrays(m)
                syncOpts(2, o)
                return o
            }
            // group == 3 班级：依赖已选专业（其 UUID）
            val majorId = valueOf(picked, 2)
            if (majorId.isEmpty()) return placeholder("请先选择专业")
            val rows = loadScope(grade, dept)
            val m = LinkedHashMap<String, String>()
            for (r in rows) {
                if (majorId != pick(r, "zyh_id")) continue
                val id = pick(r, "bh_id")
                val nm = pick(r, "bjmc", "bj")
                if (id.isNotEmpty() && !m.containsKey(id)) m[id] = if (nm.isEmpty()) id else nm
            }
            if (m.isEmpty()) return placeholder("该专业暂无班级")
            val o = toArrays(m)
            syncOpts(3, o)
            return o
        }
    }

    /** 把某一级候选项同步进 recIds/activeIds，保证 recommendQuery 的 valueOf 取到真实值（专业=UUID）。 */
    private fun syncOpts(group: Int, o: Array<Array<String>>) {
        if (group < recIds.size) {
            recIds[group] = o[0]
        } else {
            recIds.add(o[0])
        }
        if (group < recNames.size) {
            recNames[group] = o[1]
        } else {
            recNames.add(o[1])
        }
        if (group < activeIds.size) {
            activeIds[group] = o[0]
        } else {
            activeIds.add(o[0])
        }
    }

    /** 按年级/院系拉班级清单行（分页取全），按 (年级|院系) 缓存，避免重复翻页。 */
    private fun loadScope(grade: String, dept: String): List<JSONObject> {
        val key = (if (grade.isEmpty()) "*" else grade) + "|" + (if (dept.isEmpty()) "*" else dept)
        val cached = SCOPE_CACHE[key]
        if (cached != null) return cached
        val filter = LinkedHashMap<String, String>()
        if (grade.isNotEmpty()) filter["njdm_id"] = grade
        if (dept.isNotEmpty()) filter["jg_id"] = dept
        val rows = fetchAllClasses(client, recXnm, recXqm, filter)
        if (rows.isNotEmpty()) SCOPE_CACHE[key] = rows
        return rows
    }

    /** 从全量清单里抽第 group 级的去重选项，并按已选上级条件过滤。 */
    private fun optionsFor(picked: IntArray?, group: Int): Array<Array<String>> {
        val ids = ArrayList<String>()
        val names = ArrayList<String>()
        ids.add("")
        names.add("全部")
        val rows = CLASS_ROWS
        if (rows == null || rows.isEmpty()) {
            names[0] = "选项加载中…"
            return arrayOf(ids.toTypedArray(), names.toTypedArray())
        }
        // 逐级：专业要先选院系，班级要先选专业
        if (group == 2 && (picked == null || valueOf(picked, 1).isEmpty())) {
            names[0] = "请先选择院系"
            return arrayOf(ids.toTypedArray(), names.toTypedArray())
        }
        if (group == 3 && (picked == null || valueOf(picked, 2).isEmpty())) {
            names[0] = "请先选择专业"
            return arrayOf(ids.toTypedArray(), names.toTypedArray())
        }
        val seen = LinkedHashSet<String>()
        for (row in rows) {
            if (!matchPrefix(row, picked, group)) continue
            val id = pick(row, OPT_FIELDS[group][0])
            if (id.isEmpty() || !seen.add(id)) continue
            val name = pick(row, OPT_FIELDS[group][1])
            ids.add(id)
            names.add(if (name.isEmpty()) id else name)
        }
        return arrayOf(ids.toTypedArray(), names.toTypedArray())
    }

    /** 前 group 组条件是否都命中这一行；picked=0 表示「全部」，不约束。 */
    private fun matchPrefix(row: JSONObject, picked: IntArray?, group: Int): Boolean {
        val limit = Math.min(group, OPT_FIELDS.size)
        for (i in 0 until limit) {
            val want = valueOf(picked, i)
            if (want.isEmpty()) continue
            if (want != pick(row, OPT_FIELDS[i][0])) return false
        }
        return true
    }

    private fun valueOf(picked: IntArray?, i: Int): String {
        if (picked == null || i >= picked.size || picked[i] <= 0) return ""
        val ids = if (i < activeIds.size) activeIds[i] else recIds[i]
        return if (picked[i] < ids.size) ids[picked[i]] else ""
    }

    /** 进页后台预热一次全量班级清单，进程内 20 分钟复用，不阻塞界面。 */
    private fun warmClassRows() {
        val sem = recXnm + "-" + recXqm
        val c = CLASS_ROWS
        if (c != null && c.isNotEmpty() && sem == CLASS_ROWS_SEM
            && System.currentTimeMillis() - CLASS_ROWS_AT < CLASS_TTL
        ) return
        if (CLASS_LOADING) return
        CLASS_LOADING = true
        val cl = client
        val xnm = recXnm
        val xqm = recXqm
        val key = sem
        Thread({
            try {
                val rows = fetchAllClasses(cl, xnm, xqm, LinkedHashMap())
                if (rows.isNotEmpty()) {
                    CLASS_ROWS = rows
                    CLASS_ROWS_AT = System.currentTimeMillis()
                    CLASS_ROWS_SEM = key
                    android.util.Log.d("ZSTS-JW", "班级清单预热完成 " + rows.size + " 行")
                }
            } catch (t: Throwable) {
                android.util.Log.w("ZSTS-JW", "班级清单预热失败：" + t)
            } finally {
                CLASS_LOADING = false
            }
        }, "jw-class-warm").start()
    }

    /**
     * 教务每页固定 10 条：先取第 1 页问总行数，再把剩余页按 4 并发一次拉完。
     * 老版是串行翻最多 50 页；这里并发后等待时间约为原来的 1/4，且按页码顺序回填，
     * 下拉顺序稳定。单页失败只丢那一页，不影响整体。
     */
    private fun fetchAllClasses(
        cl: JwClient, xnm: String, xqm: String,
        filter: Map<String, String>
    ): MutableList<JSONObject> {
        val out = ArrayList<JSONObject>()
        val first = cl.post(RECOMMEND_LIST, pagedBody(xnm, xqm, 1, filter)) ?: return out
        var total = 0
        var per = 0
        try {
            val root = JSONObject(first)
            total = root.optInt("totalResult", 0)
            val arr = firstArray(root)
            per = arr.length()
            for (i in 0 until per) {
                val o = arr.optJSONObject(i)
                if (o != null) out.add(o)
            }
        } catch (e: Exception) {
            return out
        }
        if (per == 0 || out.size >= total) return out
        val pages = Math.min(60, (total + per - 1) / per)
        val pool = Executors.newFixedThreadPool(4)
        val futures = ArrayList<Future<JSONArray?>>()
        for (p in 2..pages) {
            val page = p
            futures.add(pool.submit(object : Callable<JSONArray?> {
                override fun call(): JSONArray? {
                    val s = cl.post(RECOMMEND_LIST, pagedBody(xnm, xqm, page, filter))
                    if (s == null) return null
                    return try {
                        firstArray(JSONObject(s))
                    } catch (e: Exception) {
                        null
                    }
                }
            }))
        }
        for (f in futures) {
            try {
                val a = f.get() ?: continue
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i)
                    if (o != null) out.add(o)
                }
            } catch (ignored: Exception) {
            }
        }
        pool.shutdown()
        return out
    }

    private fun pagedBody(
        xnm: String, xqm: String, page: Int,
        filter: Map<String, String>
    ): MutableMap<String, String> {
        val f = listBody(xnm, xqm, page)
        f.putAll(filter)
        return f
    }

    /** 按 recommendClasses() 结果里的下标查该班课表，返回可直接喂给 JwglxtFetcher 的 JSON。 */
    fun recommendKb(index: Int): Data {
        val d = Data()
        if (index < 0 || index >= classRows.size) return fail(d, "班级下标无效，请重新查询")
        val row = classRows[index]
        val m = ylMap(row)

        val sfkz = client.post(RECOMMEND_SFKZ, m)
        if (sfkz != null && "true" == sfkz.trim().replace("\"", "")) {
            return fail(d, "该学年的推荐课表当前不可查看（教务限制）")
        }
        client.post(RECOMMEND_PREVIEW, m) // 还原「预览已打开」的会话语境，失败不阻断
        var kb = client.post(RECOMMEND_KB, m) ?: return fail(d, "班级课表接口没有返回")
        try {
            val root = JSONObject(kb)
            val list = root.optJSONArray("kbList")
            android.util.Log.d("ZSTS-JW", "推荐课表 kbList "
                    + (if (list == null) 0 else list.length()) + " 行")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i)
                    if (o == null) continue
                    if (pick(o, "rs").isEmpty()) copy(o, "jxbrs", "rs")
                    if (pick(o, "yxrs").isEmpty()) copy(o, "xkrs", "yxrs")
                }
                root.put("kbList", list)
                kb = root.toString()
            }
        } catch (e: Exception) {
            return fail(d, "班级课表解析失败：" + e.message)
        }
        d.ok = true
        d.kbJson = kb
        d.headline = pick(row, "bjmc", "bj", "bh_id")
        d.note = "推荐课表 · " + pick(row, "njmc") + " · " + pick(row, "zymc")
        return d
    }

    companion object {

        private const val GRADES_PAGE = "/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005&layout=default"
        private const val GRADES_DATA = "/cjcx/cjcx_cxXsgrcj.html?doType=query"
        private const val GRADES_DETAIL = "/cjcx/cjcx_cxXsKccjList.html?gnmkdm=N305007"
        private const val GPA_PAGE = "/xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=N105515&layout=default"

        private const val SELECTION =
            "/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default"
        private const val SELECTION_DISPLAY = "/xsxk/zzxkyzb_cxZzxkYzbDisplay.html?gnmkdm=N253512"
        private const val SELECTION_PART = "/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html?gnmkdm=N253512"
        private const val SELECTION_CHOOSED = "/xsxk/zzxkyzb_cxZzxkYzbChoosed.html?gnmkdm=N253512"

        private const val RECOMMEND_PAGE =
            "/kbdy/bjkbdy_cxBjkbdyIndex.html?gnmkdm=N214505&layout=default"
        private const val RECOMMEND_LIST = "/kbdy/bjkbdy_cxBjkbdyTjkbList.html"
        private const val RECOMMEND_SFKZ = "/kbdy/bjkbdy_cxXnxqsfkz.html?gnmkdm=N214505"
        private const val RECOMMEND_PREVIEW = "/kbdy/bjkbdy_ylBjkbdyIndex.html?gnmkdm=N214505"
        private const val RECOMMEND_KB = "/kbdy/bjkbdy_cxBjKb.html?gnmkdm=N214505"

        /**
         * 进程级班级清单缓存：教务 467 个班、每页固定 10 条，一次并行翻页取全。
         * 老版就是「进页拿全量字典 → 级联全在本地过滤」，这里把它做成一次预热，
         * 之后四级联动和「查询」都是内存操作，零网络往返。
         */
        @Volatile
        private var CLASS_ROWS: List<JSONObject>? = null

        @Volatile
        private var CLASS_ROWS_AT: Long = 0L

        @Volatile
        private var CLASS_ROWS_SEM: String = ""

        @Volatile
        private var CLASS_LOADING: Boolean = false

        private const val CLASS_TTL = 20 * 60_000L

        /**
         * 按「已选上级条件」缓存的班级清单（老版 rowsByGrade 口径）。
         * 关键：逐级筛选不能等全量预热 —— 选了院系就按 jg_id 查那一个院系的班级，
         * 页数少、结果确定，点开专业下拉时一定只有该院系的专业。
         */
        private val SCOPE_CACHE: MutableMap<String, List<JSONObject>> = ConcurrentHashMap()

        /** 进程内成绩快照：同一进程里反复进成绩页不再重复打教务接口。 */
        @Volatile
        private var gradeCache: Data? = null

        @Volatile
        private var gradeCacheAt: Long = 0L

        private const val GRADE_TTL = 30 * 60_000L

        /** 班级清单行里的四级字段（院系 id 在行里叫 jgdm，页面字典里叫 jg_id，值相同）。 */
        private val OPT_FIELDS = arrayOf(
            arrayOf("njdm_id", "njmc"), arrayOf("jgdm", "jgmc"),
            arrayOf("zyh_id", "zymc"), arrayOf("bh_id", "bjmc")
        )

        private fun placeholder(msg: String): Array<Array<String>> {
            return arrayOf(arrayOf(""), arrayOf(msg))
        }

        private fun toArrays(m: Map<String, String>): Array<Array<String>> {
            val ids = ArrayList<String>(m.size + 1)
            val names = ArrayList<String>(m.size + 1)
            ids.add("")
            names.add("全部")
            for ((k, v) in m.entries) {
                ids.add(k)
                names.add(v)
            }
            return arrayOf(ids.toTypedArray(), names.toTypedArray())
        }

        private fun selectOptions(html: String?, id: String): Array<Array<String>> {
            val ids = ArrayList<String>()
            val names = ArrayList<String>()
            ids.add("")
            names.add("全部")
            val sel = Pattern.compile(
                "<select[^>]*(?:id|name)=[\"']" + Pattern.quote(id) + "[\"'][^>]*>([\\s\\S]*?)</select>",
                Pattern.CASE_INSENSITIVE
            ).matcher(html ?: "")
            if (sel.find()) {
                val opt = Pattern.compile(
                    "<option[^>]*value=[\"']([^\"']*)[\"'][^>]*>([^<]*)<",
                    Pattern.CASE_INSENSITIVE
                ).matcher(sel.group(1))
                // 老版页面里班级字典有 2198 项，之前硬截到 120 会让绝大多数班级查不到
                while (opt.find() && ids.size < 4000) {
                    val v = opt.group(1).trim()
                    if (v.isEmpty()) continue
                    val n = opt.group(2).trim()
                    ids.add(v)
                    names.add(if (n.isEmpty()) v else n)
                }
            }
            return arrayOf(ids.toTypedArray(), names.toTypedArray())
        }

        private fun badPayload(s: String?): Boolean {
            return s == null || s.contains("login_slogin") || !s.trim().startsWith("{")
        }

        /** N305007 明细行 → 按教学班 / 课名归组的「平时 x · 期末 y」。总评行丢掉，避免和主表重复。 */
        private fun gradeComponents(json: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            try {
                val arr = firstArray(JSONObject(json))
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i)
                    if (o == null) continue
                    val label = pick(o, "xmblmc")
                    val score = pick(o, "xmcj")
                    if (label.isEmpty() || score.isEmpty() || label.contains("总评")) continue
                    val text = label + " " + score
                    addComp(out, "j:" + pick(o, "jxb_id", "jxbmc"), text)
                    addComp(out, "n:" + pick(o, "kcmc", "kcmcmc"), text)
                }
            } catch (ignored: Exception) {
            }
            return out
        }

        private fun addComp(m: MutableMap<String, String>, key: String?, text: String) {
            if (key == null || key.length < 3) return
            val old = m[key]
            m[key] = if (old == null) text else old + " · " + text
        }

        private fun listBody(xnm: String, xqm: String, page: Int): MutableMap<String, String> {
            val f = LinkedHashMap<String, String>()
            val emptyKeys = arrayOf(
                "xqh_id", "njdm_id", "xb_id", "jg_id", "zyh_id", "zyfx_id",
                "bh_id", "xsdm", "pyccdm", "kclxdm", "kclbdm", "sfzhsjk",
                "kbsjlyqz", "zs", "yf"
            )
            for (k in emptyKeys) f[k] = ""
            f["sfcxxqh"] = "0"
            f["xnm"] = xnm
            f["xqm"] = xqm
            f["page"] = page.toString()
            f["rows"] = "10"
            return f
        }

        private fun ylMap(row: JSONObject): MutableMap<String, String> {
            val f = LinkedHashMap<String, String>()
            val keys = arrayOf(
                "xnm", "xqm", "xnmc", "xqmmc", "xqh_id", "njdm_id", "zyh_id",
                "bh_id", "tjkbzdm", "tjkbzxsdm", "zymc", "jgmc", "njmc", "bj",
                "xkrs", "jsxm", "lxdh", "bh"
            )
            for (k in keys) f[k] = pick(row, k)
            val blanks = arrayOf("zs", "xsdm", "kclxdm", "kclbdm", "kbsjlyqz", "yf")
            for (k in blanks) f[k] = ""
            f["zxszjjs"] = "false"
            f["akcxqjchb"] = "false"
            f["kzlx"] = "ck"
            f["sfcxxqh"] = if (f["xqh_id"].orEmpty().isEmpty()) "0" else "1"
            return f
        }

        // ---------- 小工具 ----------

        private fun fail(d: Data, msg: String): Data {
            d.ok = false
            d.message = msg
            return d
        }

        /** 教务各接口封套不一致：{items} / {rows} / {data} / {tmpList} / 裸数组都认。 */
        private fun firstArray(o: JSONObject?): JSONArray {
            if (o == null) return JSONArray()
            val keys = arrayOf("items", "rows", "data", "tmpList", "list")
            for (k in keys) {
                val a = o.optJSONArray(k)
                if (a != null) return a
            }
            return JSONArray()
        }

        /** 字段名大小写不敏感地取第一个非空值。 */
        private fun pick(o: JSONObject, vararg keys: String): String {
            for (k in keys) {
                val v = o.opt(k)
                if (v != null && v.toString().trim().isNotEmpty()) {
                    return v.toString().trim()
                }
            }
            // 再按小写 key 兜一遍（教务有时给 KCMC / kcmc 混用）
            val iter = o.keys()
            while (iter.hasNext()) {
                val real = iter.next()
                for (k in keys) {
                    if (real.equals(k, ignoreCase = true)) {
                        val v = o.opt(real)
                        if (v != null && v.toString().trim().isNotEmpty()) {
                            return v.toString().trim()
                        }
                    }
                }
            }
            return ""
        }

        private fun copy(o: JSONObject, from: String, to: String) {
            val v = pick(o, from)
            if (v.isNotEmpty()) {
                try {
                    o.put(to, v)
                } catch (ignored: Exception) {
                }
            }
        }

        private fun first(m: Map<String, String>, vararg keys: String): String {
            for (k in keys) {
                val v = m[k]
                if (v != null && v.isNotEmpty()) return v
            }
            return ""
        }

        private fun hiddenInputs(html: String?): MutableMap<String, String> {
            val out = LinkedHashMap<String, String>()
            val tags = Pattern.compile(
                "<input\\b[^>]*type=[\"']hidden[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE
            ).matcher(html ?: "")
            while (tags.find()) {
                val tag = tags.group()
                var name = attr(tag, "name")
                if (name.isEmpty()) name = attr(tag, "id")
                if (name.isNotEmpty()) out[name] = attr(tag, "value")
            }
            return out
        }

        private fun attr(tag: String, key: String): String {
            val m = Pattern.compile(
                key + "\\s*=\\s*[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE
            ).matcher(tag)
            return if (m.find()) m.group(1) else ""
        }

        private fun stripTags(html: String): String {
            return html.replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace(Regex("\\s+"), " ")
        }

        /** 选课页顶部的「当前不在选课时间窗」原文提示。 */
        private fun noticeOf(html: String?): String {
            val m = Pattern.compile("(?s)<div[^>]*id=[\"']nodata[\"'][^>]*>(.*?)</div>",
                Pattern.CASE_INSENSITIVE).matcher(html ?: "")
            if (m.find()) {
                val s = stripTags(m.group(1)).trim()
                if (s.length > 2 && s.length < 120) return s
            }
            return ""
        }

        /** 已选课程是服务端渲染的 HTML 片段，按单元格抓课程名。 */
        private fun chosenNames(html: String?): MutableList<String> {
            val out = ArrayList<String>()
            val m = Pattern.compile("<td[^>]*>\\s*([^<>]{2,40})\\s*</td>",
                Pattern.CASE_INSENSITIVE).matcher(html ?: "")
            while (m.find() && out.size < 30) {
                val s = stripTags(m.group(1)).trim().replace(Regex("\\s+"), "")
                if (s.length < 2 || s.matches(Regex("[0-9\\W]+"))) continue
                if (s.contains("课程") && s.length < 8) continue
                if (!out.contains(s)) out.add(s)
            }
            return out
        }
    }
}
