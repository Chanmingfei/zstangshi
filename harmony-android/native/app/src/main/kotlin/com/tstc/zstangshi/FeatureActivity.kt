package com.tstc.zstangshi

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import com.tstc.zstangshi.data.Dates
import com.tstc.zstangshi.data.JwglxtFetcher
import com.tstc.zstangshi.data.ScheduleSettings
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.data.Timetable
import com.tstc.zstangshi.data.WeekMath
import com.tstc.zstangshi.data.WeekOverlay
import com.tstc.zstangshi.jw.JwApi
import com.tstc.zstangshi.ui.PullRefreshLayout
import com.tstc.zstangshi.ui.TimetableGridView

import org.json.JSONArray
import org.json.JSONObject

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 内的教务功能页：成绩 / 学分绩点 / 自主选课 / 推荐课表。
 *
 * 这四个入口以前是直接塞一个教务网页，跟「放弃 WebView 架构」的口径冲突；
 * 现在统一走 [JwApi] 的原生数据链，返回的是 App 自己画的卡片 / 课表网格。
 */
class FeatureActivity : BaseActivity() {

    private lateinit var store: Store
    private lateinit var api: JwApi
    private lateinit var type: String

    /** 原名 title，与 Activity.title 属性冲突，改名后仍只在本类内使用（行为不变）。 */
    private lateinit var pageTitle: String

    /** 整页的圆角卡片进出场（见 ui/PageSlide）。 */
    private var slide: com.tstc.zstangshi.ui.PageSlide? = null
    private lateinit var body: LinearLayout
    private var current: JwApi.Data? = null
    private var filterPicked: IntArray? = null
    private var semesterPicked = 0
    private var semesterOptions = arrayOf("全部学期")
    private var kbData: JwApi.Data? = null
    private var kbTimetable: Timetable? = null
    private var kbWeek = 1
    private var kbTotalWeeks = 20

    /** 推荐课表的周次切换模块（与个人课表同一套）：持久字段，翻周只换数据不重建视图。 */
    private var kbGrid: TimetableGridView? = null
    private var kbWeekLabel: TextView? = null
    private var kbFab: TextView? = null
    private var kbFabShown = false
    private var kbSwipe: KbSwipe? = null

    /** 成绩页的下拉刷新容器（其他页不套，避免和课表网格抢手势）。 */
    private var pull: PullRefreshLayout? = null

    /** 本地快照的读取时间，用于界面上如实标注「数据截止」。 */
    private var cacheAt = 0L

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        store = Store(this)
        api = JwApi(store)
        val t = intent.getStringExtra(EXTRA_TYPE)
        type = t ?: T_GRADES
        val ti = intent.getStringExtra(EXTRA_TITLE)
        if (ti == null || ti.isEmpty()) pageTitle = "教务查询" else pageTitle = ti

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pageColor())
        root.addView(header())

        val sc: ScrollView
        val scroller: View
        // 成绩、学分绩点都用同一套下拉刷新：平时只看本地快照，想看新数据拉一下
        if (T_GRADES == type || T_GPA == type) {
            val p = PullRefreshLayout(this)
            p.setOnRefresh { load(true) }
            pull = p
            sc = p.scrollView()
            scroller = p
        } else {
            val s = ScrollView(this)
            s.isFillViewport = true
            s.clipToPadding = false
            sc = s
            scroller = s
        }
        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16f), dp(12f), dp(16f), dp(28f))
        sc.addView(
            body, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        // 内容包一层 FrameLayout：「回到本周」悬浮按钮要叠在滚动区右下角
        val content = FrameLayout(this)
        content.addView(
            scroller, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        if (T_RECOMMEND == type) {
            val fab = buildKbFab()
            kbFab = fab
            content.addView(fab, kbFabParams())
        }
        root.addView(
            content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)
        // 窗口现在是全屏的（见 PageSlide.fullBleed），系统栏间距要自己留
        applyInsets(root)
        // 整页圆角卡片进场：和返回时的系统预测性返回动画用同一个圆角半径
        val sld = com.tstc.zstangshi.ui.PageSlide(this, pageColor())
        slide = sld
        sld.enter()
        sld.attachBack()
        initialLoad()
    }

    /** 全屏窗口下的系统栏间距：内容自己让出来，页面底色仍然铺到屏幕四边。 */
    private fun applyInsets(root: View) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        } else {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            root.setPadding(0, if (id > 0) resources.getDimensionPixelSize(id) else 0, 0, 0)
        }
    }

    /** 「回到本周」悬浮按钮：外观与个人课表同一套（品牌色圆角胶囊，翻离本周才浮出）。 */
    private fun buildKbFab(): TextView {
        val fab = TextView(this)
        fab.text = "回到本周"
        fab.textSize = 12.5f
        fab.typeface = Typeface.DEFAULT_BOLD
        fab.gravity = Gravity.CENTER
        fab.setTextColor(0xFFFFFFFF.toInt())
        val bg = GradientDrawable()
        bg.setColor(getColor(R.color.brand))
        bg.cornerRadius = dp(22f).toFloat()
        fab.background = bg
        fab.setPadding(dp(16f), 0, dp(16f), 0)
        fab.elevation = dp(5f).toFloat()
        fab.contentDescription = "回到本周"
        fab.visibility = View.GONE
        fab.alpha = 0f
        fab.setOnClickListener {
            val w = currentKbWeek()
            if (w != kbWeek && kbSwipe != null) kbSwipe!!.flipTo(w)
        }
        return fab
    }

    private fun kbFabParams(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(42f)
        )
        lp.gravity = Gravity.BOTTOM or Gravity.END
        lp.rightMargin = dp(18f)
        lp.bottomMargin = dp(18f)
        return lp
    }

    /**
     * 进页先画本地快照（秒开），成绩 / 绩点若本次登录会话还没抓过
     * （快照时间早于登录时间），再后台补一次最新数据 —— 不挡页面、不闪加载框。
     * 之后同一会话内重进不再联网，想看新数据靠下拉刷新。
     */
    private fun initialLoad() {
        if (T_RECOMMEND != type) {
            val cached = store.feature(type)
            if (cached.isNotEmpty()) {
                val d = JwApi.Data.fromJson(cached)
                if (d.ok) {
                    cacheAt = d.at
                    android.util.Log.d("ZSTS-JW", "功能页命中本地快照：$type")
                    render(d)
                    if ((T_GRADES == type || T_GPA == type) && store.featureAt(type) < store.loginAt()) {
                        android.util.Log.d("ZSTS-JW", "本会话没抓过，进页补一次：$type")
                        load(false)
                    }
                    return
                }
            }
        }
        load(false)
    }

    private fun header(): LinearLayout {
        val h = com.tstc.zstangshi.ui.HeaderBar.create(this, pageTitle) { closePage() }
        h.addView(com.tstc.zstangshi.ui.HeaderBar.action(this, "刷新") { load(true) })
        return h
    }

    /** 关页面统一走滑动出场，别直接 finish 让窗口「啪」一下消失。 */
    private fun closePage() {
        val s = slide
        if (s != null) {
            s.finish()
        } else {
            finish()
        }
    }

    /**
     * 返回：Android 13 起返回手势走系统调度器，系统自己会把本页缩成圆角卡片退场，
     * 这里再叠一层滑动会两套动画打架 —— 所以只在没有预测性返回的老系统上补出场动画。
     */
    override fun onBackPressed() {
        val s = slide
        if (s != null && !com.tstc.zstangshi.ui.BackGestures.supported()) {
            s.finish()
            return
        }
        super.onBackPressed()
    }

    // ---------- 取数 ----------

    private fun load() {
        load(false)
    }

    /** force 只由下拉刷新 / 顶部「刷新」按钮传入，才会真的重新打教务接口。 */
    private fun load(force: Boolean) {
        pull?.setRefreshing(true)
        // 下拉刷新时保留现有列表，别先清空再转圈
        if (!force || current == null) showState("正在从教务读取…")
        Thread { work(force) }.start()
    }

    private fun work(force: Boolean) {
        if (store.user().isEmpty() || store.pass().isEmpty()) {
            runOnUiThread { showError("还没有登录教务", true) }
            return
        }
        if (!api.login(store.user(), store.pass())) {
            val msg = api.loginMessage()
            runOnUiThread { showError(if (msg.isEmpty()) "登录教务失败" else msg, true) }
            return
        }
        val d: JwApi.Data
        if (T_GPA == type) d = api.gpa()
        else if (T_SELECTION == type) d = api.selection()
        else if (T_RECOMMEND == type) d = api.recommendContext()
        else d = api.grades(force)
        runOnUiThread {
            finishRefresh()
            if (d.ok && T_RECOMMEND != type) {
                cacheAt = if (d.at > 0) d.at else System.currentTimeMillis()
                val json = d.toJson()
                if (json.isNotEmpty()) store.saveFeature(type, json)
            }
            render(d)
        }
    }

    private fun finishRefresh() {
        pull?.setRefreshing(false)
    }

    private fun showState(text: String) {
        body.removeAllViews()
        val v = TextView(this)
        v.text = text
        v.textSize = 13f
        v.setTextColor(getColor(R.color.text_tertiary))
        v.gravity = Gravity.CENTER
        v.setPadding(0, dp(60f), 0, dp(60f))
        body.addView(v)
    }

    private fun showError(msg: String, needLogin: Boolean) {
        finishRefresh()
        body.removeAllViews()
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.background = getDrawable(R.drawable.card_bg)
        c.setPadding(dp(18f), dp(24f), dp(18f), dp(24f))

        val t = TextView(this)
        t.text = msg
        t.textSize = 14f
        t.setTextColor(getColor(R.color.text_primary))
        t.gravity = Gravity.CENTER
        t.setLineSpacing(dp(3f).toFloat(), 1f)
        c.addView(t)

        val btn = TextView(this)
        btn.text = if (needLogin) "去登录教务" else "重试"
        btn.textSize = 14f
        btn.typeface = Typeface.DEFAULT_BOLD
        btn.gravity = Gravity.CENTER
        btn.setTextColor(getColor(R.color.brand))
        btn.background = getDrawable(R.drawable.btn_outline)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)
        )
        lp.topMargin = dp(18f)
        c.addView(btn, lp)
        btn.setOnClickListener {
            if (needLogin) {
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .putExtra(LoginActivity.EXTRA_RETURN, true)
                )
            } else {
                load(true)
            }
        }

        val clp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        clp.topMargin = dp(10f)
        body.addView(c, clp)
    }

    // ---------- 渲染 ----------

    private fun render(d: JwApi.Data) {
        if (!d.ok) {
            showError(d.message, false)
            return
        }
        // 查询只命中一个班时，数据层已经把课表一起带回来了，直接画网格
        if (d.kbJson.isNotEmpty()) {
            val parsed = JwglxtFetcher.parse(d.kbJson)
            android.util.Log.d(
                "ZSTS-JW", "推荐课表解析 原始=" + parsed.rawRecords
                        + " 行，课程=" + parsed.courseCount
                        + "，周次解析失败=" + parsed.failedWeekExpr
            )
            renderKb(d, if (parsed.ok()) parsed.timetable else null)
            return
        }
        current = d
        if (T_RECOMMEND == type && d.filterLabels.isNotEmpty()) {
            renderFilters(d)
            return
        }
        paint()
    }

    /** 按当前筛选状态重画（学期筛选、级联选择都会走这里，不重新联网）。 */
    private fun paint() {
        val cur = current ?: return
        body.removeAllViews()
        body.addView(if (T_GPA == type) bigCard(cur) else headCard(cur))
        body.addView(refreshHint())
        if (T_GRADES == type) body.addView(semesterFilter())
        for (i in cur.rows.indices) {
            val r = cur.rows[i]
            if (T_GRADES == type && semesterPicked > 0
                && semesterOptions[semesterPicked] != r.tag
            ) continue
            val pos = i
            val click: View.OnClickListener? = if (T_RECOMMEND == type) {
                View.OnClickListener { loadKb(pos) }
            } else {
                null
            }
            body.addView(rowCard(r, click))
        }
    }

    /** 让「数据什么时候取的、怎么刷新」一眼可见，不假装是实时数据。 */
    private fun refreshHint(): TextView {
        val t = TextView(this)
        val whenText = if (cacheAt > 0)
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(cacheAt))
        else
            "刚刚"
        val how = if (pull != null) "下拉可刷新" else "点右上角「刷新」更新"
        t.text = how + " · 读取于 " + whenText + "（本页只加载一次）"
        t.textSize = 11.5f
        t.setTextColor(getColor(R.color.text_tertiary))
        t.gravity = Gravity.CENTER
        t.setPadding(0, 0, 0, dp(10f))
        return t
    }

    private fun semesterFilter(): LinearLayout {
        val cur = current!!
        val uniq = java.util.ArrayList<String>()
        for (r in cur.rows) {
            if (r.tag.isNotEmpty() && !uniq.contains(r.tag)) uniq.add(r.tag)
        }
        // 教务返回顺序是乱的；「YYYY-YYYY · N」这种定长格式字典序就是时间顺序
        java.util.Collections.sort(uniq)
        uniq.add(0, "全部学期")
        semesterOptions = uniq.toTypedArray()
        if (semesterPicked >= semesterOptions.size) semesterPicked = 0
        return rowCard(
            JwApi.Row(
                "学期筛选", "只看某一学期的成绩",
                semesterOptions[semesterPicked], ""
            )
        ) {
            com.tstc.zstangshi.ui.OptionSheet.show(
                this, "选择学期",
                semesterOptions, semesterPicked,
                object : com.tstc.zstangshi.ui.OptionSheet.OnPick {
                    override fun onPick(index: Int) {
                        semesterPicked = index
                        paint()
                    }
                }
            )
        }
    }

    /** 老版推荐课表的形态：年级 / 院系 / 专业 / 班级 四个下拉排两行，下面一个「查询」。 */
    private fun renderFilters(d: JwApi.Data) {
        body.removeAllViews()
        body.addView(headCard(d))
        if (filterPicked == null || filterPicked!!.size != d.filterLabels.size) {
            filterPicked = IntArray(d.filterLabels.size)
        }

        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.background = getDrawable(R.drawable.card_bg)
        grid.setPadding(dp(12f), dp(14f), dp(12f), dp(14f))
        for (row in 0 until 2) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            for (col in 0 until 2) {
                val gi = row * 2 + col
                if (gi >= d.filterLabels.size) break
                val lp = LinearLayout.LayoutParams(0, dp(44f), 1f)
                if (col == 0) lp.rightMargin = dp(5f)
                else lp.leftMargin = dp(5f)
                line.addView(selectBox(d, gi), lp)
            }
            val rowLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rowLp.bottomMargin = dp(10f)
            grid.addView(line, rowLp)
        }
        val glp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        glp.bottomMargin = dp(12f)
        body.addView(grid, glp)

        val query = TextView(this)
        query.text = "查询课表"
        query.textSize = 14f
        query.typeface = Typeface.DEFAULT_BOLD
        query.gravity = Gravity.CENTER
        query.setTextColor(0xFFFFFFFF.toInt())
        query.background = getDrawable(R.drawable.btn_primary)
        query.setOnClickListener { loadQuery() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)
        )
        lp.topMargin = dp(2f)
        body.addView(query, lp)
    }

    /** 仿教务原页的 select：一行一个带边框的下拉框，点开弹选项层。 */
    private fun selectBox(d: JwApi.Data, gi: Int): TextView {
        val names = d.filterNames[gi]
        val sel = if (filterPicked!![gi] < names.size) filterPicked!![gi] else 0
        val tv = TextView(this)
        tv.text = d.filterLabels[gi] + "：" + (if (sel == 0) "全部" else names[sel]) + "  ▾"
        tv.textSize = 12.5f
        tv.setTextColor(getColor(R.color.text_primary))
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setPadding(dp(10f), 0, dp(8f), 0)
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        val g = GradientDrawable()
        g.setColor(getColor(R.color.bg_surface))
        g.setStroke(1, getColor(R.color.border_subtle))
        g.cornerRadius = dp(9f).toFloat()
        tv.background = g
        val group = gi
        tv.setOnClickListener { openCascade(group) }
        return tv
    }

    /** 级联选项的后台任务串行化，避免两个线程同时改 activeIds。 */
    private val cascadeLock = Any()

    /**
     * 年级(0)/院系(1) 是静态页面字典，直接弹；
     * 专业(2)/班级(3) 需先按已选年级 / 院系向服务端拉班级清单行，后台取完再弹层。
     * 没选上级条件时给 Toast 提示，不弹空层。
     */
    private fun openCascade(group: Int) {
        if (group == 0 || group == 1) {
            showCascadeSheet(group)
            return
        }
        if (filterPicked == null) return
        val picked = filterPicked!!.clone()
        if (group == 2 && picked[0] == 0 && picked[1] == 0) {
            android.widget.Toast.makeText(
                this, "请先选择年级或院系", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (group == 3 && picked[2] == 0) {
            android.widget.Toast.makeText(this, "请先选择专业", android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }
        val g = group
        android.widget.Toast.makeText(this, "正在加载选项…", android.widget.Toast.LENGTH_SHORT).show()
        Thread({
            val opts = api.recommendOptions(picked, g)
            runOnUiThread {
                val cur = current
                if (cur == null || isFinishing) return@runOnUiThread
                // 只有一条（占位提示）时不弹层，直接提示
                if (opts[0].size <= 1) {
                    android.widget.Toast.makeText(
                        this, if (opts[1].isNotEmpty()) opts[1][0] else "暂无选项",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@runOnUiThread
                }
                if (g < cur.filterIds.size) {
                    cur.filterIds.set(g, opts[0])
                    cur.filterNames.set(g, opts[1])
                }
                showCascadeSheet(g)
            }
        }, "jw-cascade-open").start()
    }

    private fun showCascadeSheet(group: Int) {
        val cur = current ?: return
        if (filterPicked == null) return
        val names = cur.filterNames[group]
        val sel = Math.max(0, Math.min(filterPicked!![group], names.size - 1))
        val g = group
        com.tstc.zstangshi.ui.OptionSheet.show(
            this,
            "选择" + cur.filterLabels[group], names, sel,
            object : com.tstc.zstangshi.ui.OptionSheet.OnPick {
                override fun onPick(index: Int) {
                    // 「选项加载失败，点此重试」：再等一次清单就绪后重开本级
                    if (index >= 0 && index < names.size
                        && "选项加载失败，点此重试" == names[index]
                    ) {
                        openCascade(g)
                        return
                    }
                    pickFilter(g, index)
                }
            }
        )
    }

    /**
     * 选中某一级后清空下级，并按「已选条件」重算专业 / 班级的下拉内容。
     * 先即时重绘显示已选值，再在后台把两级选项刷新好，下一次点开就是收窄后的结果。
     */
    private fun pickFilter(group: Int, index: Int) {
        val cur = current ?: return
        if (filterPicked == null) return
        filterPicked!![group] = index
        for (j in group + 1 until filterPicked!!.size) filterPicked!![j] = 0
        // 上级条件一变，下级旧选项一条都不能留：先作废，再后台按新条件重算
        for (j in group + 1 until cur.filterIds.size) {
            cur.filterIds.set(j, arrayOf(""))
            cur.filterNames.set(j, arrayOf("全部"))
        }
        renderFilters(cur)

        val picked = filterPicked!!.clone()
        val snapshot = cur
        Thread({
            var r1: Array<Array<String>>? = null
            var r2: Array<Array<String>>? = null
            var r3: Array<Array<String>>? = null
            if (group < 1) r1 = api.recommendOptions(picked, 1)
            if (group < 2) r2 = api.recommendOptions(picked, 2)
            if (group < 3) r3 = api.recommendOptions(picked, 3)
            runOnUiThread {
                if (current !== snapshot || isFinishing) return@runOnUiThread
                val a1 = r1
                val a2 = r2
                val a3 = r3
                if (a1 != null) {
                    snapshot.filterIds.set(1, a1[0])
                    snapshot.filterNames.set(1, a1[1])
                }
                if (a2 != null) {
                    snapshot.filterIds.set(2, a2[0])
                    snapshot.filterNames.set(2, a2[1])
                }
                if (a3 != null) {
                    snapshot.filterIds.set(3, a3[0])
                    snapshot.filterNames.set(3, a3[1])
                }
                renderFilters(snapshot)
            }
        }, "jw-cascade-next").start()
    }

    private fun loadQuery() {
        if (filterPicked == null) return
        var any = false
        for (p in filterPicked!!) {
            if (p > 0) {
                any = true
                break
            }
        }
        if (!any) {
            android.widget.Toast.makeText(
                this,
                "请先选择至少一个筛选条件，再点查询", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        showState("正在按条件查询课表…")
        val picked = filterPicked!!.clone()
        Thread {
            val d = api.recommendQuery(picked)
            runOnUiThread { render(d) }
        }.start()
    }

    private fun headCard(d: JwApi.Data): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = getDrawable(R.drawable.card_bg)
        c.setPadding(dp(18f), dp(18f), dp(18f), dp(18f))

        val h = TextView(this)
        h.text = d.headline
        h.textSize = 21f
        h.typeface = Typeface.DEFAULT_BOLD
        h.setTextColor(getColor(R.color.text_primary))
        c.addView(h)

        if (d.note.isNotEmpty()) {
            val n = TextView(this)
            n.text = d.note
            n.textSize = 11.5f
            n.setTextColor(getColor(R.color.text_tertiary))
            n.setLineSpacing(dp(2f).toFloat(), 1f)
            n.setPadding(0, dp(8f), 0, 0)
            c.addView(n)
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(12f)
        c.layoutParams = lp
        return c
    }

    private fun bigCard(d: JwApi.Data): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = getDrawable(R.drawable.card_bg)
        c.setPadding(dp(18f), dp(18f), dp(18f), dp(18f))

        val h = TextView(this)
        h.text = "学分绩点"
        h.textSize = 13f
        h.typeface = Typeface.DEFAULT_BOLD
        h.setTextColor(getColor(R.color.text_tertiary))
        c.addView(h)

        val big = TextView(this)
        big.text = d.headline
        big.textSize = 52f
        big.typeface = Typeface.DEFAULT_BOLD
        big.gravity = Gravity.CENTER
        big.setTextColor(getColor(R.color.brand))
        big.setPadding(0, dp(10f), 0, dp(4f))
        c.addView(big)

        val label = TextView(this)
        label.text = "平均学分绩点（GPA）"
        label.textSize = 12.5f
        label.gravity = Gravity.CENTER
        label.setTextColor(getColor(R.color.text_secondary))
        c.addView(label)

        val note = TextView(this)
        note.text = d.note
        note.textSize = 11.5f
        note.gravity = Gravity.CENTER
        note.setTextColor(getColor(R.color.text_tertiary))
        note.setPadding(0, dp(12f), 0, 0)
        note.setLineSpacing(dp(2f).toFloat(), 1f)
        c.addView(note)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(12f)
        c.layoutParams = lp
        return c
    }

    private fun rowCard(r: JwApi.Row, click: View.OnClickListener?): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.background = getDrawable(R.drawable.card_bg)
        c.setPadding(dp(15f), dp(13f), dp(15f), dp(13f))

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = r.title
        t.textSize = 14.5f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(getColor(R.color.text_primary))
        left.addView(t)
        if (r.sub.isNotEmpty()) {
            val s = TextView(this)
            s.text = r.sub
            s.textSize = 11.5f
            s.setTextColor(getColor(R.color.text_tertiary))
            s.setPadding(0, dp(4f), 0, 0)
            left.addView(s)
        }
        c.addView(
            left, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        if (r.value.isNotEmpty()) {
            val v = TextView(this)
            v.text = r.value
            v.textSize = 16f
            v.typeface = Typeface.DEFAULT_BOLD
            v.setTextColor(getColor(R.color.brand))
            right.addView(v)
        }
        if (r.extra.isNotEmpty()) {
            val e = TextView(this)
            e.text = r.extra
            e.textSize = 11f
            e.setTextColor(getColor(R.color.text_tertiary))
            right.addView(e)
        }
        c.addView(right)
        if (click != null) {
            c.isClickable = true
            c.setOnClickListener(click)
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10f)
        c.layoutParams = lp
        return c
    }

    // ---------- 推荐课表：点班级 → 查课表 → 画周课表网格 ----------

    private fun loadKb(pos: Int) {
        showState("正在载入该班课表…")
        Thread {
            val d = api.recommendKb(pos)
            var t: Timetable? = null
            if (d.ok) {
                val parsed = JwglxtFetcher.parse(d.kbJson)
                if (parsed.ok()) t = parsed.timetable
            }
            val tt = t
            runOnUiThread { renderKb(d, tt) }
        }.start()
    }

    private fun renderKb(d: JwApi.Data, t: Timetable?) {
        kbData = d
        kbTimetable = t
        kbWeek = 1
        kbTotalWeeks = if (t != null && t.semester.totalWeeks > 0) t.semester.totalWeeks else 20
        // 推荐课表的周次写法比个人课表杂，解析失败的时段兜底成「全周」，
        // 否则这些课在任何一周都画不出来，看起来就是「课程不全」。
        if (t != null) {
            val total = if (t.semester.totalWeeks > 0) t.semester.totalWeeks else 20
            for (c in t.courses) {
                for (s in c.sessions) {
                    if (s.weeks.isNotEmpty()) continue
                    for (w in 1..total) s.weeks.add(w)
                    if (s.weeksRaw.isEmpty()) s.weeksRaw = "全周"
                }
            }
        }
        paintKb()
    }

    private fun paintKb() {
        body.removeAllViews()
        kbGrid = null

        body.addView(
            com.tstc.zstangshi.ui.HeaderBar
                .backLink(this, "重新筛选") { load() }
        )

        val data = kbData!!
        if (!data.ok) {
            showErrorKeepBack(data.message)
            return
        }
        body.addView(headCard(data))

        val t = kbTimetable
        if (t == null) {
            // 网格解析不出来时也不假装成功：把教务原始行如实列出来
            addKbFallbackRows(data.kbJson)
            return
        }
        // 周次切换模块 + 网格都是持久视图：翻周走 applyKbWeek() 只换数据，
        // 这样跟手翻页动画（滑出 → 离屏换数据 → 滑入）才不会被打断
        body.addView(kbWeekBar())

        val start = kbStartDate()
        val sch = store.schedule()
        sch.ensureCovers(ScheduleSettings.maxSectionUsed(t))

        val grid = TimetableGridView(this)
        grid.setNight(night())
        grid.setSchedule(sch)
        grid.setOnCourseClickListener { c, s, day, startSection ->
            showCourseDetail(c, s, day, startSection)
        }
        kbGrid = grid
        val swipe = KbSwipe(this)
        kbSwipe = swipe
        grid.setOnTouchListener(swipe)
        body.addView(
            grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(620f)
            )
        )
        applyKbWeek()
    }

    /**
     * 课表起始周一：教务给的优先。推荐课表解析出来的 Timetable 不带学期信息，
     * 兜底依次取「我的」本地已同步课表的起始日（同校同学期口径一致）→ 官方校历
     * → 今天所在周周一。之前直接退今天所在周，currentWeek 恒等于 1，
     * 「回到本周」和周首日期就全错了。
     */
    private fun kbStartDate(): String {
        val t = kbTimetable!!
        if (Dates.valid(t.semester.startDate)) return t.semester.startDate
        val local = store.loadTimetable()
        if (local != null && Dates.valid(local.semester.startDate)) {
            return local.semester.startDate
        }
        val term = com.tstc.zstangshi.data.AcademicCalendar.termFor(
            com.tstc.zstangshi.data.WeekMath.todayDateLabel()
        )
        if (term != null && Dates.valid(term.classStart)) {
            return WeekMath.normalizeToMonday(term.classStart)
        }
        return WeekMath.normalizeToMonday(WeekMath.todayDateLabel())
    }

    /** 只更新网格数据与标签，不重建视图 —— 翻页动画的离屏中点只做这件事。 */
    private fun applyKbWeek() {
        val t = kbTimetable ?: return
        val start = kbStartDate()
        kbWeekLabel?.text = "第 " + kbWeek + " 周"
        val grid = kbGrid
        if (grid != null) {
            grid.setWeekDates(WeekMath.weekDates(start, kbWeek))
            grid.setData(t, WeekOverlay.build(t, start, kbWeek), kbWeek)
        }
        updateKbFab()
    }

    /** 教务「本周」的规范值（学期外由 WeekMath 夹到 1 / 总周数）。 */
    private fun currentKbWeek(): Int {
        if (kbTimetable == null) return 1
        return Math.max(
            1, Math.min(
                kbTotalWeeks,
                WeekMath.currentWeek(kbStartDate(), kbTotalWeeks)
            )
        )
    }

    /** 与个人课表一致：翻离本周才浮出「回到本周」，回本周自动淡出。 */
    private fun updateKbFab() {
        val fab = kbFab ?: return
        val t = kbTimetable
        val show = t != null && t.courses.isNotEmpty() && kbWeek != currentKbWeek()
        if (show == kbFabShown) return
        kbFabShown = show
        fab.animate().cancel()
        if (show) {
            fab.visibility = View.VISIBLE
            fab.scaleX = 0.82f
            fab.scaleY = 0.82f
            fab.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(230)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                .start()
        } else {
            fab.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                .setDuration(160)
                .withEndAction {
                    if (!kbFabShown) fab.visibility = View.GONE
                }.start()
        }
    }

    /** 周次切换模块：外观照搬个人课表 —— ‹ [第 N 周] ›，胶囊点开全部教学周弹层。 */
    private fun kbWeekBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(2f), dp(2f), dp(2f), dp(8f))

        // 左侧占位把切换器推到右边（个人课表里这个位置是标题，模块同样靠右）
        val spacer = View(this)
        bar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val prev = kbStepBtn("‹", -1)
        bar.addView(prev)

        val label = TextView(this)
        kbWeekLabel = label
        label.textSize = 13f
        label.gravity = Gravity.CENTER
        label.setTextColor(getColor(R.color.text_primary))
        val weekBg = GradientDrawable()
        weekBg.setColor(getColor(R.color.brand_soft))
        weekBg.cornerRadius = dp(9f).toFloat()
        label.background = weekBg
        // 高度固定 dp(34)，与箭头同高，模块整体一条中线（与个人课表同规格）
        label.setPadding(0, 0, 0, 0)
        label.setOnClickListener { openKbWeekPicker() }
        bar.addView(label, LinearLayout.LayoutParams(dp(82f), dp(34f)))

        val next = kbStepBtn("›", 1)
        bar.addView(next)
        return bar
    }

    private fun kbStepBtn(s: String, delta: Int): TextView {
        val v = TextView(this)
        v.text = s
        v.textSize = 18f
        v.gravity = Gravity.CENTER
        // 单引号字形在字体里天然偏上，关掉字体 padding 才能光学居中
        v.includeFontPadding = false
        v.setTextColor(getColor(R.color.text_primary))
        v.contentDescription = if (delta < 0) "上一周" else "下一周"
        v.layoutParams = LinearLayout.LayoutParams(dp(36f), dp(34f))
        v.setOnClickListener {
            val next = kbWeek + delta
            if (next < 1 || next > kbTotalWeeks) return@setOnClickListener
            if (kbSwipe != null) kbSwipe!!.flipTo(next)
        }
        return v
    }

    /** 点顶部周次胶囊 → 底部弹层铺开全部教学周，点一格直接跳过去（与个人课表同一套）。 */
    private fun openKbWeekPicker() {
        val holder = arrayOfNulls<com.tstc.zstangshi.ui.Sheet.Handle>(1)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18f), dp(4f), dp(18f), dp(14f))

        val t = TextView(this)
        t.text = "选择周次"
        t.textSize = 16f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(getColor(R.color.text_primary))
        t.setPadding(0, 0, 0, dp(4f))
        box.addView(t)

        val hint = TextView(this)
        hint.text = "当前第 " + kbWeek + " 周 · 共 " + kbTotalWeeks + " 周"
        hint.textSize = 11.5f
        hint.setTextColor(getColor(R.color.text_tertiary))
        hint.setPadding(0, 0, 0, dp(10f))
        box.addView(hint)

        val cols = 5
        val rows = (kbTotalWeeks + cols - 1) / cols
        for (r in 0 until rows) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.setPadding(0, dp(3f), 0, dp(3f))
            for (c in 0 until cols) {
                val w = r * cols + c + 1
                val cell = TextView(this)
                cell.gravity = Gravity.CENTER
                cell.textSize = 13f
                if (w <= kbTotalWeeks) {
                    cell.text = w.toString()
                    val picked = w == kbWeek
                    val bg = GradientDrawable()
                    bg.cornerRadius = dp(10f).toFloat()
                    if (picked) {
                        bg.setColor(getColor(R.color.brand))
                        cell.setTextColor(0xFFFFFFFF.toInt())
                        cell.typeface = Typeface.DEFAULT_BOLD
                    } else {
                        bg.setColor(if (night()) 0x18FFFFFF else 0x140062CC)
                        cell.setTextColor(getColor(R.color.text_primary))
                    }
                    cell.background = bg
                    cell.setOnClickListener {
                        if (holder[0] != null) holder[0]!!.dismiss()
                        if (kbSwipe != null) kbSwipe!!.flipTo(w)
                    }
                } else {
                    cell.text = ""
                }
                val lp = LinearLayout.LayoutParams(0, dp(42f), 1f)
                lp.leftMargin = if (c == 0) 0 else dp(6f)
                line.addView(cell, lp)
            }
            box.addView(line)
        }
        holder[0] = com.tstc.zstangshi.ui.Sheet.show(
            this, box,
            dp(96f) + rows * dp(48f)
        )
    }

    /** 推荐课表里的课程块也要能点开详情（和课表页同一套底部弹层）。 */
    private fun showCourseDetail(c: Timetable.Course, s: Timetable.Session, day: Int, start: Int) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18f), dp(6f), dp(18f), dp(16f))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val dot = android.view.View(this)
        val dg = android.graphics.drawable.GradientDrawable()
        dg.shape = android.graphics.drawable.GradientDrawable.OVAL
        dg.setColor(getColor(R.color.brand))
        dot.background = dg
        head.addView(dot, LinearLayout.LayoutParams(dp(10f), dp(10f)))
        val name = TextView(this)
        name.text = c.name
        name.textSize = 17f
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(getColor(R.color.text_primary))
        name.setPadding(dp(10f), 0, 0, 0)
        head.addView(name)
        box.addView(head)

        val teacher = if (s.teacher.isEmpty()) c.teacher else s.teacher
        val sub = TextView(this)
        sub.text = "周" + "一二三四五六日"[Math.max(0, Math.min(6, day - 1))] +
                " · 第 " + s.startSection + "-" + s.endSection + " 节" +
                (if (teacher.isEmpty()) "" else " · " + teacher)
        sub.textSize = 12f
        sub.setTextColor(getColor(R.color.text_tertiary))
        sub.setPadding(0, dp(6f), 0, dp(12f))
        box.addView(sub)

        val sch = store.schedule()
        var rows = 0
        box.addView(
            fieldRow(
                "时间", sch.startTime(s.startSection) + " - "
                        + sch.endTime(s.endSection)
            )
        )
        rows++
        if (s.location.isNotEmpty()) {
            box.addView(
                fieldRow(
                    "地点", s.location
                            + (if (s.campus.isEmpty()) "" else " · " + s.campus)
                )
            )
            rows++
        } else if (s.campus.isNotEmpty()) {
            box.addView(fieldRow("校区", s.campus))
            rows++
        }
        if (s.weeksRaw.isNotEmpty()) {
            box.addView(fieldRow("周次", s.weeksRaw))
            rows++
        }
        if (c.credit > 0) {
            box.addView(fieldRow("学分", String.format(Locale.CHINA, "%.1f", c.credit)))
            rows++
        }
        com.tstc.zstangshi.ui.Sheet.show(this, box, dp(120f) + rows * dp(38f))
    }

    private fun fieldRow(k: String, v: String): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(0, dp(8f), 0, dp(8f))
        val kk = TextView(this)
        kk.text = k
        kk.textSize = 13f
        kk.setTextColor(getColor(R.color.text_tertiary))
        r.addView(
            kk, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )
        val vv = TextView(this)
        vv.text = v
        vv.textSize = 13f
        vv.typeface = Typeface.DEFAULT_BOLD
        vv.setTextColor(getColor(R.color.text_primary))
        r.addView(vv)
        return r
    }

    private fun showErrorKeepBack(msg: String) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = getDrawable(R.drawable.card_bg)
        c.setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        val t = TextView(this)
        t.text = msg
        t.textSize = 14f
        t.setTextColor(getColor(R.color.text_primary))
        c.addView(t)
        body.addView(c)
    }

    private fun addKbFallbackRows(json: String) {
        try {
            val arr = JSONObject(json).optJSONArray("kbList")
            if (arr == null) return
            var i = 0
            while (i < arr.length() && i < 40) {
                val o = arr.optJSONObject(i)
                if (o != null) {
                    val name = str(o, "kcmc", "kcmcmc")
                    if (name.isNotEmpty()) {
                        val sub = "周" + str(o, "xqj", "xqjmc") + " · " +
                                str(o, "jc", "jcs") + " 节 · " + str(o, "cdmc", "jxdd") +
                                " · " + str(o, "xm", "jsxm")
                        body.addView(rowCard(JwApi.Row(name, sub, str(o, "zcd"), ""), null))
                    }
                }
                i++
            }
        } catch (ignored: Exception) {
        }
    }

    /** KbSwipe / KbFling 是静态嵌套类，读不到 BaseActivity 的 protected dp()，这里转一层。 */
    private fun swipeDp(v: Float): Int = dp(v)

    /**
     * 跟手的周次翻页：与个人课表（TimetablePage.WeekSwipe）同一套节奏 ——
     * MOVE 按手指实时平移 + 轻微淡出（到边界加阻尼）；松手先滑出屏幕，
     * 在 alpha=0 的离屏阶段才做唯一一次换周数据（applyKbWeek），再从另一侧滑入。
     * 换周永远发生在看不见的时候，掉帧不会被眼睛捕捉到。
     *
     * 静态嵌套类 + page 字段，避开 d8 对 this$0 的内部错误。
     */
    private class KbSwipe(private val page: FeatureActivity) : View.OnTouchListener {

        private val detector: android.view.GestureDetector
        private val slop: Int
        private var downX = 0f
        private var downY = 0f
        private var lastDx = 0f
        var dragging = false
        private var animating = false

        init {
            slop = android.view.ViewConfiguration.get(page).scaledTouchSlop
            detector = android.view.GestureDetector(page, KbFling(page, this))
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val t = page.kbTimetable
            if (t == null || t.courses.isEmpty()) return false
            if (animating) return true // 翻页过程中吞掉事件，避免误触课程格
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    lastDx = 0f
                    dragging = false
                    detector.onTouchEvent(e)
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!dragging) {
                        detector.onTouchEvent(e)
                        // 横向意图一旦确立（横移超过 slop 且不输纵移）立刻认领。
                        // 认领条件太苛 + 不锁父容器时，外层 ScrollView 会先把
                        // 事件截走，横滑就会"时灵时不灵"。
                        if (Math.abs(dx) < slop || Math.abs(dx) < Math.abs(dy)) {
                            return false
                        }
                        dragging = true
                        // 锁住父容器拦截：之后的 MOVE 不会中途被 ScrollView 抢走
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        page.kbGrid!!.setPressed(false)
                        page.kbGrid!!.cancelLongPress()
                    }
                    lastDx = dx
                    drag(dx)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        page.kbGrid!!.setPressed(false)
                        finishDrag(lastDx)
                        return true
                    }
                    if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                        lastDx = 0f
                    }
                    detector.onTouchEvent(e)
                    return false
                }
                else -> {
                    detector.onTouchEvent(e)
                    return false
                }
            }
        }

        private fun drag(dx: Float) {
            val w = page.kbGrid!!.width
            if (w <= 0) return
            val edge = (dx < 0 && page.kbWeek >= page.kbTotalWeeks) ||
                    (dx > 0 && page.kbWeek <= 1)
            val d = dx * (if (edge) 0.28f else 0.82f)
            page.kbGrid!!.translationX = d
            val p = Math.min(1f, Math.abs(d) / (w * 0.6f))
            page.kbGrid!!.alpha = 1f - 0.28f * p
        }

        private fun finishDrag(dx: Float) {
            val w = page.kbGrid!!.width.toFloat()
            val threshold = Math.max(page.swipeDp(48f).toFloat(), w * 0.16f)
            val dir = if (dx < 0) 1 else -1
            val target = page.kbWeek + dir
            if (Math.abs(dx) < threshold || target < 1 || target > page.kbTotalWeeks) {
                springBack()
            } else {
                flip(dir, target)
            }
        }

        private fun springBack() {
            animating = true
            val g: View = page.kbGrid!!
            g.animate().cancel()
            g.animate().translationX(0f).alpha(1f)
                .setDuration(260).setInterpolator(EASE_OUT)
                .withEndAction { animating = false }.start()
        }

        /** 按钮 / 周次格子 / 「回到本周」共用这一套过渡。 */
        fun flipTo(target: Int) {
            if (target == page.kbWeek || animating) return
            flip(if (target > page.kbWeek) 1 else -1, target)
        }

        fun flip(dir: Int, target: Int) {
            if (target < 1 || target > page.kbTotalWeeks) {
                springBack()
                return
            }
            animating = true
            val g: View = page.kbGrid!!
            g.animate().cancel()
            val w = if (g.width > 0) g.width.toFloat() else page.swipeDp(320f).toFloat()
            val out = -dir * w * 0.32f
            g.animate().translationX(out).alpha(0f)
                .setDuration(140).setInterpolator(EASE_IN)
                .withEndAction {
                    page.kbWeek = target       // 离屏时换数据，只重绘一次
                    page.applyKbWeek()
                    g.translationX = -out
                    g.alpha = 0f
                    g.animate().translationX(0f).alpha(1f)
                        .setDuration(300).setInterpolator(EASE_OUT)
                        .withEndAction { animating = false }.start()
                }.start()
        }

        companion object {
            private val EASE_OUT =
                android.view.animation.PathInterpolator(0.22f, 0.8f, 0.30f, 1f)
            private val EASE_IN =
                android.view.animation.PathInterpolator(0.4f, 0f, 0.8f, 0.4f)
        }
    }

    /** 快速甩动（MOVE 事件来不及形成拖拽）时的兜底翻页。 */
    private class KbFling(
        private val page: FeatureActivity,
        private val ctrl: KbSwipe
    ) : android.view.GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            vx: Float,
            vy: Float
        ): Boolean {
            if (e1 == null || ctrl.dragging) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (Math.abs(dx) < page.swipeDp(40f) || Math.abs(dx) < Math.abs(dy)) {
                return false
            }
            val dir = if (dx < 0) 1 else -1
            val target = page.kbWeek + dir
            if (target < 1 || target > page.kbTotalWeeks) return true
            ctrl.flip(dir, target)
            return true
        }
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_TITLE = "title"
        const val T_GRADES = "grades"
        const val T_GPA = "gpa"
        const val T_SELECTION = "selection"
        const val T_RECOMMEND = "recommend"

        @JvmStatic
        fun open(host: Activity, type: String, title: String) {
            host.startActivity(
                Intent(host, FeatureActivity::class.java)
                    .putExtra(EXTRA_TYPE, type)
                    .putExtra(EXTRA_TITLE, title)
            )
        }

        @JvmStatic
        private fun str(o: JSONObject, vararg keys: String): String {
            for (k in keys) {
                val v = o.optString(k, "")
                if (v.trim().isNotEmpty()) return v.trim()
            }
            return ""
        }
    }
}
