package com.tstc.zstangshi.pages

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.tstc.zstangshi.FeatureActivity
import com.tstc.zstangshi.R
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.jw.JwApi
import com.tstc.zstangshi.ui.OptionSheet
import com.tstc.zstangshi.ui.PullRefreshLayout
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * 底栏「成绩」页：点开直接展示教务成绩。
 *
 * 数据用 FeatureActivity 同一份本地快照（store.feature("grades")），有快照就秒开、
 * 不联网；没有快照才后台取一次并落盘。顶栏「刷新」与顶部下拉刷新都能强制更新，
 * 「更多」进完整页（那里有学期筛选和下拉刷新）。不再先摆本地课表统计概览。
 */
class GradesPage(private val host: Activity, private val store: Store) : PullRefreshLayout(host) {

    private val body: LinearLayout

    @Volatile
    private var loading = false
    private var currentData: JwApi.Data? = null
    private var semesterPicked = 0
    private var semesterOptions: Array<String> = arrayOf("全部学期")

    init {
        setBackgroundColor(if (store.darkNow()) 0xFF0F1520.toInt() else host.getColor(R.color.bg_page))

        val sc = scrollView()
        sc.setBackgroundColor(if (store.darkNow()) 0xFF0F1520.toInt() else host.getColor(R.color.bg_page))
        sc.setClipToPadding(false)

        body = LinearLayout(host)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16), dp(14), dp(16), dp(24))
        sc.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))

        setOnRefresh { load(true) }
        build()
    }

    /** 只重画，不联网；MainActivity 切回这个 tab 时调用，保证和完整页刷新后的缓存一致。 */
    fun reload() {
        build()
    }

    private fun build() {
        body.removeAllViews()
        body.addView(bar(), lp())

        val cached = store.feature(FeatureActivity.T_GRADES)
        val d: JwApi.Data? = if (cached.isEmpty()) null else JwApi.Data.fromJson(cached)
        if (d == null || !d.ok) {
            currentData = null
            state(if (loading) "正在从教务读取成绩…" else "还没有教务成绩", false)
            if (!loading) load(false)
            return
        }
        currentData = d
        body.addView(overview(d), lp())
        // 学期筛选（与完整成绩页一致：纯客户端按行 tag 过滤，不重新联网）
        body.addView(semesterFilter(), lp())
        for (r in d.rows) {
            if (semesterPicked > 0 && semesterOptions[semesterPicked] != r.tag) continue
            body.addView(courseCard(r), lp())
        }
        // 本次登录会话还没抓过成绩（快照早于登录时间）：先显快照，再悄悄补一次；
        // 抓完 featureAt 会晚于 loginAt，同一会话内切回这个 tab 不会再联网
        if (!loading && store.featureAt(FeatureActivity.T_GRADES) < store.loginAt()) {
            load(false)
        }
    }

    /**
     * 学期筛选：把行里出现的学期去重、排序（"YYYY-YYYY · N" 字典序即时间序），
     * 选了只在本地过滤，不重新联网。多学期才显示，单学期没必要筛。
     */
    private fun semesterFilter(): LinearLayout {
        val uniq = ArrayList<String>()
        val data = currentData
        if (data != null) {
            for (r in data.rows) {
                if (r.tag.isNotEmpty() && !uniq.contains(r.tag)) uniq.add(r.tag)
            }
        }
        Collections.sort(uniq)
        uniq.add(0, "全部学期")
        semesterOptions = uniq.toTypedArray()
        if (semesterPicked >= semesterOptions.size) semesterPicked = 0
        val multi = uniq.size > 2 // 去掉「全部学期」后仍多于 1 个
        val click: View.OnClickListener? = if (multi) View.OnClickListener { _ ->
            OptionSheet.show(host, "选择学期", semesterOptions, semesterPicked,
                    object : OptionSheet.OnPick {
                        override fun onPick(index: Int) {
                            semesterPicked = index
                            build()
                        }
                    })
        } else null
        return rowCard(JwApi.Row("学期筛选",
                if (multi) "只看某一学期的成绩" else "当前仅一个学期",
                semesterOptions[semesterPicked], ""),
                click)
    }

    /** 仿 FeatureActivity.rowCard：左标题+副标题，右当前值，可点击展开选项。 */
    private fun rowCard(r: JwApi.Row, click: View.OnClickListener?): LinearLayout {
        val c = LinearLayout(host)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.background = host.getDrawable(R.drawable.card_bg)
        c.setPadding(dp(15), dp(13), dp(15), dp(13))

        val left = LinearLayout(host)
        left.orientation = LinearLayout.VERTICAL
        val t = TextView(host)
        t.text = r.title
        t.setTextSize(14.5f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(host.getColor(R.color.text_primary))
        left.addView(t)
        if (r.sub.isNotEmpty()) {
            val s = TextView(host)
            s.text = r.sub
            s.setTextSize(11.5f)
            s.setTextColor(host.getColor(R.color.text_tertiary))
            s.setPadding(0, dp(4), 0, 0)
            left.addView(s)
        }
        c.addView(left, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val right = LinearLayout(host)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        if (r.value.isNotEmpty()) {
            val v = TextView(host)
            v.text = r.value
            v.setTextSize(16f)
            v.typeface = Typeface.DEFAULT_BOLD
            v.setTextColor(host.getColor(R.color.brand))
            right.addView(v)
        }
        if (r.extra.isNotEmpty()) {
            val e = TextView(host)
            e.text = r.extra
            e.setTextSize(11f)
            e.setTextColor(host.getColor(R.color.text_tertiary))
            right.addView(e)
        }
        c.addView(right)
        if (click != null) {
            c.isClickable = true
            c.setOnClickListener(click)
        }
        return c
    }

    private fun bar(): LinearLayout {
        val h = LinearLayout(host)
        h.orientation = LinearLayout.HORIZONTAL
        h.gravity = Gravity.CENTER_VERTICAL
        h.setPadding(dp(2), dp(2), dp(2), dp(8))

        val title = TextView(host)
        title.text = "教务成绩"
        title.setTextSize(19f)
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(host.getColor(R.color.text_primary))
        h.addView(title, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val more = TextView(host)
        more.text = "更多 ›"
        more.setTextSize(13f)
        more.setTextColor(host.getColor(R.color.brand))
        more.setPadding(dp(12), dp(8), dp(6), dp(8))
        more.setOnClickListener {
            FeatureActivity.open(host,
                    FeatureActivity.T_GRADES, "成绩")
        }
        h.addView(more)

        // 刷新中在右上角放一个转圈，别只把「刷新」换成文字
        if (loading) {
            val spin = spinner()
            val sp = LinearLayout.LayoutParams(dp(15), dp(15))
            sp.rightMargin = dp(2)
            h.addView(spin, sp)
        }

        val refresh = TextView(host)
        refresh.text = if (loading) "读取中" else "刷新"
        refresh.isEnabled = !loading
        refresh.setTextSize(13f)
        refresh.setTextColor(host.getColor(R.color.brand))
        refresh.setPadding(dp(10), dp(8), dp(2), dp(8))
        refresh.setOnClickListener { load(true) }
        h.addView(refresh)
        return h
    }

    private fun spinner(): ProgressBar {
        val p = ProgressBar(host, null, android.R.attr.progressBarStyleSmall)
        p.isIndeterminate = true
        p.indeterminateTintList = ColorStateList.valueOf(host.getColor(R.color.brand))
        return p
    }

    private fun overview(d: JwApi.Data): LinearLayout {
        val c = LinearLayout(host)
        c.orientation = LinearLayout.VERTICAL
        c.background = host.getDrawable(R.drawable.card_bg)
        c.setPadding(dp(16), dp(16), dp(16), dp(16))

        val h = TextView(host)
        h.text = d.headline
        h.setTextSize(22f)
        h.typeface = Typeface.DEFAULT_BOLD
        h.setTextColor(host.getColor(R.color.brand))
        c.addView(h)

        if (d.note.isNotEmpty()) {
            val n = TextView(host)
            n.text = d.note
            n.setTextSize(11.5f)
            n.setTextColor(host.getColor(R.color.text_tertiary))
            n.setLineSpacing(dp(2).toFloat(), 1f)
            n.setPadding(0, dp(6), 0, 0)
            c.addView(n)
        }
        val when0 = if (d.at > 0)
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(d.at))
        else "刚刚"
        val t = TextView(host)
        t.text = "读取于 " + when0 + " · 点「刷新」更新，不自动重复加载"
        t.setTextSize(11f)
        t.setTextColor(host.getColor(R.color.text_tertiary))
        t.setPadding(0, dp(8), 0, 0)
        c.addView(t)
        return c
    }

    private fun courseCard(r: JwApi.Row): LinearLayout {
        val c = LinearLayout(host)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.background = host.getDrawable(R.drawable.card_bg)
        c.setPadding(dp(14), dp(12), dp(14), dp(12))

        val left = LinearLayout(host)
        left.orientation = LinearLayout.VERTICAL
        val name = TextView(host)
        name.text = r.title
        name.setTextSize(14.5f)
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(host.getColor(R.color.text_primary))
        left.addView(name)
        if (r.sub.isNotEmpty()) {
            val sub = TextView(host)
            sub.text = r.sub
            sub.setTextSize(11.5f)
            sub.setTextColor(host.getColor(R.color.text_tertiary))
            sub.setLineSpacing(dp(2).toFloat(), 1f)
            sub.setPadding(0, dp(4), 0, 0)
            left.addView(sub)
        }
        c.addView(left, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val right = LinearLayout(host)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.CENTER_HORIZONTAL
        right.setPadding(dp(10), 0, 0, 0)
        val score = TextView(host)
        score.text = if (r.value.isEmpty()) "—" else r.value
        score.setTextSize(19f)
        score.typeface = Typeface.DEFAULT_BOLD
        score.setTextColor(host.getColor(R.color.brand))
        score.gravity = Gravity.CENTER
        right.addView(score)
        if (r.extra.isNotEmpty()) {
            val point = TextView(host)
            point.text = "绩点 " + r.extra
            point.setTextSize(11f)
            point.setTextColor(host.getColor(R.color.text_tertiary))
            right.addView(point)
        }
        c.addView(right)
        return c
    }

    private fun state(text: String, failed: Boolean) {
        val c = LinearLayout(host)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.background = host.getDrawable(R.drawable.card_bg)
        c.setPadding(dp(18), dp(26), dp(18), dp(26))

        if (loading) {
            val spin = spinner()
            val sp = LinearLayout.LayoutParams(dp(26), dp(26))
            sp.bottomMargin = dp(12)
            c.addView(spin, sp)
        }

        val t = TextView(host)
        t.text = text
        t.setTextSize(13.5f)
        t.gravity = Gravity.CENTER
        t.setTextColor(host.getColor(R.color.text_secondary))
        t.setLineSpacing(dp(3).toFloat(), 1f)
        c.addView(t)

        if (failed) {
            val retry = TextView(host)
            retry.text = "重新读取"
            retry.setTextSize(14f)
            retry.typeface = Typeface.DEFAULT_BOLD
            retry.gravity = Gravity.CENTER
            retry.setTextColor(host.getColor(R.color.brand))
            retry.background = host.getDrawable(R.drawable.btn_outline)
            val rp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            rp.topMargin = dp(16)
            retry.layoutParams = rp
            retry.setOnClickListener { load(true) }
            c.addView(retry)
        }
        body.addView(c, lp())
    }

    /** 只有「首次没快照」或用户点刷新才会走这里。 */
    private fun load(force: Boolean) {
        if (loading) return
        loading = true
        host.runOnUiThread { build() }
        Thread({
            val api = JwApi(store)
            var d: JwApi.Data? = null
            var err = ""
            if (store.user().isEmpty() || store.pass().isEmpty()) {
                err = "还没有登录教务，请先到「我的」登录"
            } else if (!api.login(store.user(), store.pass())) {
                err = if (api.loginMessage().isEmpty()) "登录教务失败" else api.loginMessage()
            } else {
                d = api.grades(force)
            }
            val data = d
            if (data != null && data.ok) {
                val json = data.toJson()
                if (!json.isEmpty()) store.saveFeature(FeatureActivity.T_GRADES, json)
            }
            val result = data
            val msg = if (err.isEmpty())
                (if (result == null || result.message.isEmpty()) "成绩读取失败" else result.message) else err
            host.runOnUiThread {
                loading = false
                setRefreshing(false)
                if (result == null || !result.ok) {
                    body.removeAllViews()
                    body.addView(bar(), lp())
                    state(msg, true)
                } else {
                    build()
                }
            }
        }, "jw-grades-tab").start()
    }

    private fun lp(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.bottomMargin = dp(10)
        return p
    }

    private fun dp(v: Int): Int {
        return Math.round(v * resources.displayMetrics.density)
    }
}
