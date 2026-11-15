package com.tstc.zstangshi.pages

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tstc.zstangshi.FeatureActivity
import com.tstc.zstangshi.R
import com.tstc.zstangshi.data.Dates
import com.tstc.zstangshi.data.DayPlan
import com.tstc.zstangshi.data.ScheduleSettings
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.data.WeekMath
import com.tstc.zstangshi.ui.PullRefreshLayout
import java.util.Calendar

/**
 * 首页：今天还有什么课。
 *
 * 与旧版（WebView 里的 #/home）一致的三块内容：
 *   1. 顶部一句「今天周二 · 第 10 周 · 11.10」；
 *   2. 一张「正在上 / 下一节」的大卡（课名、时间、教室、还有多久）；
 *   3. 今日全部课程，已经上完的置灰并打勾。
 *
 * 数据全部来自本地课表 + [DayPlan]（它会算上停课、调课、放假），
 * 所以断网也能用。下拉刷新：先按本地数据重画，再静默向教务对一次账 ——
 * 教务会话已被超时踢掉时，这次刷新顺带自动重登并重拉课表，全程只显示刷新动画。
 */
class HomePage(private val host: Activity, private val store: Store) : PullRefreshLayout(host) {

    private val body: LinearLayout

    init {
        setBackgroundColor(if (store.darkNow()) 0xFF0F1520.toInt() else host.getColor(R.color.bg_page))

        val sc = scrollView()
        sc.setBackgroundColor(if (store.darkNow()) 0xFF0F1520.toInt() else host.getColor(R.color.bg_page))
        sc.setFillViewport(true)

        body = LinearLayout(host)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16), dp(14), dp(16), dp(24))
        sc.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))

        setOnRefresh { refreshFromJw() }
        build()
    }

    /**
     * 首页下拉刷新。本地部分立刻重画（日期时间推进）；然后只向教务核对一次
     * 登录状态 —— 会话被超时踢掉就自动重登续期，不动课表数据（个人课表以
     * 「我的」页手动同步为准）。不弹提示、不摆加载页：只有下拉动画本身。
     */
    private fun refreshFromJw() {
        build()
        if (store.user().isEmpty() || store.pass().isEmpty()) {
            setRefreshing(false)
            return
        }
        Thread({
            val start = android.os.SystemClock.elapsedRealtime()
            try {
                // 只登录不同步课表：登录成功 = 会话续上（被踢了也会自动重登）
                com.tstc.zstangshi.jw.Auth.loginOnly(store.user(), store.pass(), store)
            } catch (e: Exception) {
                android.util.Log.d("ZSTS", "首页刷新核对登录态失败: " + e)
            }
            // 会话正常时核对一瞬就完，动画立刻收起会让人怀疑刷新没发生：
            // 保底让动画转满 2 秒再收，作为「刷新过了」的安抚反馈
            val wait = Math.max(0L, 2000L - (android.os.SystemClock.elapsedRealtime() - start))
            host.runOnUiThread {
                build()
                postDelayed({ setRefreshingOff() }, wait)
            }
        }, "home-refresh").start()
    }

    private fun setRefreshingOff() {
        setRefreshing(false)
    }

    private fun build() {
        body.removeAllViews()

        val t = store.loadTimetable()
        val today = WeekMath.todayDateLabel()
        val nowMin = nowMinute()
        val dow = Dates.dayOfWeek(today)

        // ── 顶部日期行 ──
        val head = LinearLayout(host)
        head.orientation = LinearLayout.VERTICAL
        head.setPadding(dp(4), 0, dp(4), dp(12))

        val big = TextView(host)
        big.setTextSize(24f)
        big.typeface = Typeface.DEFAULT_BOLD
        big.setTextColor(host.getColor(R.color.text_primary))
        big.text = greeting()

        val sub = TextView(host)
        sub.setTextSize(13f)
        sub.setTextColor(host.getColor(R.color.text_tertiary))
        sub.setPadding(0, dp(6), 0, 0)
        var weekLabel = ""
        if (t != null && Dates.valid(t.semester.startDate)) {
            val w = WeekMath.currentWeek(t.semester.startDate, t.semester.totalWeeks)
            if (w >= 1) weekLabel = " · 第 " + w + " 周"
        }
        sub.text = "今天周" + "一二三四五六日"[Math.max(0, Math.min(6, dow - 1))] +
                " · " + shortDate(today) + weekLabel

        head.addView(big)
        head.addView(sub)
        body.addView(head)

        if (t == null || t.courses.isEmpty()) {
            body.addView(emptyCard("还没有课表", "到「我的」里登录教务同步一次"))
            addShortcuts()
            return
        }

        // ── 今天的课 ──
        val sch = store.schedule()
        sch.ensureCovers(ScheduleSettings.maxSectionUsed(t))
        val n = ScheduleSettings.MAX_SECTIONS
        val ss = IntArray(n + 1)
        val se = IntArray(n + 1)
        for (i in 1..n) {
            ss[i] = sch.startMinutes(i)
            se[i] = sch.endMinutes(i)
        }
        val items = DayPlan.of(t, t.semester.startDate, today, ss, se)

        if (items.isEmpty()) {
            body.addView(emptyCard("今天没课", "好好休息，或者预习一下明天的"))
            addShortcuts()
            return
        }

        var current: DayPlan.Item? = null
        var next: DayPlan.Item? = null
        for (item in items) {
            if (item.startMin <= nowMin && item.endMin > nowMin) {
                current = item
                break
            }
            if (item.startMin > nowMin) {
                next = item
                break
            }
        }

        val focus = current ?: next
        if (focus != null) {
            body.addView(focusCard(focus, current != null, nowMin))
        }

        val listTitle = TextView(host)
        listTitle.text = "今天的课 · " + items.size + " 门"
        listTitle.setTextSize(13f)
        listTitle.typeface = Typeface.DEFAULT_BOLD
        listTitle.setTextColor(host.getColor(R.color.text_tertiary))
        listTitle.setPadding(dp(4), dp(6), 0, dp(8))
        body.addView(listTitle)

        for (item in items) {
            body.addView(courseRow(item, nowMin))
        }

        addShortcuts()
    }

    /** 不管今天有没有课，宫格和学工侧门都得在（以前「今天没课」直接 return 把它们吞了）。 */
    private fun addShortcuts() {
        body.addView(gridTitle("常用功能"))
        body.addView(quickGrid())
        body.addView(affairsCard())
        body.addView(campusArt())
    }

    /**
     * 页脚那张校园线稿：不着卡片、不加底色，直接压在页面背景上。
     * 原图是白底蓝线的位图，已经处理成「透明底 + 纯白线」的单色蒙版，
     * 这里用品牌色染色，深浅色主题共用一张图、颜色自动跟着换。
     */
    private fun campusArt(): ImageView {
        val iv = ImageView(host)
        iv.setImageResource(R.drawable.home_campus)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.adjustViewBounds = true
        iv.setColorFilter(host.getColor(R.color.brand))
        iv.contentDescription = "唐山师范学院校园"
        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(20)
        lp.bottomMargin = dp(4)
        iv.layoutParams = lp
        return iv
    }

    /** 「常用功能」小标题：和旧版一样，宫格上方一行灰字。 */
    private fun gridTitle(s: String): TextView {
        val v = TextView(host)
        v.text = s
        v.setTextSize(13f)
        v.typeface = Typeface.DEFAULT_BOLD
        v.setTextColor(host.getColor(R.color.text_tertiary))
        v.setPadding(dp(4), dp(18), 0, dp(10))
        return v
    }

    /**
     * 首页宫格。8 个位置铺满两行：课表 / 校历是本地页，成绩 / 绩点 / 选课 / 推荐课表
     * 走 [com.tstc.zstangshi.FeatureActivity] 的原生教务查询，
     * 「实时公交」是独立的原生二级页，只有「更多功能」打开原教务站点网页。
     *
     * 原来第 8 格是「学工系统」，和下面那张 [affairsCard] 横条入口重复，
     * 已让位给实时公交 —— 学工只保留横条一处，避免同一页出现两个同义入口。
     */
    private fun quickGrid(): LinearLayout {
        val wrap = LinearLayout(host)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.background = host.getDrawable(R.drawable.card_bg)
        wrap.setPadding(dp(7), dp(14), dp(7), dp(8))

        val row1 = gridRow()
        row1.addView(cell(R.drawable.ic_nav_timetable, "我的课表", Runnable { tab(1) }))
        row1.addView(cell(R.drawable.ic_nav_grades, "成绩",
                Runnable { feature(FeatureActivity.T_GRADES, "成绩") }))
        row1.addView(cell(R.drawable.ic_quick_gpa, "学分绩点",
                Runnable { feature(FeatureActivity.T_GPA, "学分绩点") }))
        row1.addView(cell(R.drawable.ic_nav_calendar, "校历", Runnable { openCalendar() }))
        wrap.addView(row1)

        val row2 = gridRow()
        row2.addView(cell(R.drawable.ic_quick_select, "选课",
                Runnable { feature(FeatureActivity.T_SELECTION, "自主选课") }))
        row2.addView(cell(R.drawable.ic_quick_recommend, "推荐课表",
                Runnable { feature(FeatureActivity.T_RECOMMEND, "推荐课表") }))
        row2.addView(cell(R.drawable.ic_quick_bus, "实时公交",
                Runnable { com.tstc.zstangshi.BusActivity.open(host) }))
        row2.addView(cell(R.drawable.ic_quick_more, "更多功能",
                Runnable { browse(JWXT, "教务系统") }))
        wrap.addView(row2)
        return wrap
    }

    private fun feature(type: String, title: String) {
        FeatureActivity.open(host, type, title)
    }

    /** 首页的学工入口：已经能进应用了，就不再是「仅登录」侧门。 */
    private fun affairsCard(): LinearLayout {
        val c = LinearLayout(host)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.background = host.getDrawable(R.drawable.card_bg)
        c.setPadding(dp(14), dp(13), dp(14), dp(13))
        c.isClickable = true
        c.setOnClickListener { browse(XUEGONG, "学工系统") }

        val ic = TextView(host)
        ic.text = "↗"
        ic.setTextSize(18f)
        ic.typeface = Typeface.DEFAULT_BOLD
        ic.gravity = Gravity.CENTER
        ic.setTextColor(host.getColor(R.color.brand))
        ic.background = roundRect(host.getColor(R.color.brand_soft), dp(10))
        c.addView(ic, LinearLayout.LayoutParams(dp(36), dp(36)))

        val text = LinearLayout(host)
        text.orientation = LinearLayout.VERTICAL
        val tlp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.leftMargin = dp(12)
        val t = TextView(host)
        t.text = "登录学工系统"
        t.setTextSize(14f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(host.getColor(R.color.text_primary))
        text.addView(t)
        val d = TextView(host)
        d.text = "奖助贷、请假、综合素质 · 外部网页"
        d.setTextSize(11f)
        d.setTextColor(host.getColor(R.color.text_tertiary))
        d.setPadding(0, dp(2), 0, 0)
        text.addView(d)
        c.addView(text, tlp)

        val arrow = TextView(host)
        arrow.text = "›"
        arrow.setTextSize(22f)
        arrow.setTextColor(host.getColor(R.color.text_tertiary))
        c.addView(arrow)

        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(12)
        c.layoutParams = lp
        return c
    }

    // 教务与学工原站点。「更多功能」是教务首页，学工系统只提供 http。
    // 教务在校园网下 443 常常不通，这里直接用登录时探测过的 http（WebView 另有协议兜底）。
    private fun gridRow(): LinearLayout {
        val r = LinearLayout(host)
        r.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(6)
        r.layoutParams = lp
        return r
    }

    private fun cell(icon: Int, label: String, onClick: Runnable): LinearLayout {
        val cell = LinearLayout(host)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER_HORIZONTAL
        cell.setPadding(0, dp(8), 0, dp(8))
        cell.isClickable = true
        cell.background = ripple()
        cell.setOnClickListener { onClick.run() }

        val ic = TextView(host)
        ic.setTextSize(0f)
        ic.gravity = Gravity.CENTER
        val d: Drawable = requireNotNull(host.getDrawable(icon)).mutate()
        d.setTint(host.getColor(R.color.brand))
        ic.setCompoundDrawablesWithIntrinsicBounds(null, d, null, null)
        val pad = dp(11)
        ic.setPadding(pad, pad, pad, pad)
        ic.background = roundRect(host.getColor(R.color.brand_soft), dp(14))
        cell.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))

        val t = TextView(host)
        t.text = label
        t.setTextSize(11.5f)
        t.setTextColor(host.getColor(R.color.text_secondary))
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(8), 0, 0)
        cell.addView(t)

        val lp = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        cell.layoutParams = lp
        return cell
    }

    private fun roundRect(color: Int, radius: Int): Drawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radius.toFloat()
        return g
    }

    private fun ripple(): Drawable {
        val g = GradientDrawable()
        g.setColor(0x00000000)
        g.cornerRadius = dp(14).toFloat()
        return RippleDrawable(ColorStateList.valueOf(0x1A0062CC), g, null)
    }

    private fun tab(i: Int) {
        if (host is com.tstc.zstangshi.MainActivity) {
            (host as com.tstc.zstangshi.MainActivity).switchTab(i)
        }
    }

    private fun openCalendar() {
        host.startActivity(android.content.Intent(host,
                com.tstc.zstangshi.CalendarActivity::class.java))
    }

    /** 用内置 WebView 打开，和 App 共享登录态，不跳系统浏览器。 */
    private fun browse(url: String, title: String) {
        com.tstc.zstangshi.WebViewActivity.open(host, url, title)
    }

    private fun focusCard(it: DayPlan.Item, ongoing: Boolean, nowMin: Int): LinearLayout {
        val card = LinearLayout(host)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(18), dp(16), dp(18), dp(16))
        card.background = host.getDrawable(R.drawable.card_bg)

        val tag = TextView(host)
        tag.text = if (ongoing) "正在上" else "下一节"
        tag.setTextSize(11.5f)
        tag.typeface = Typeface.DEFAULT_BOLD
        tag.setTextColor(host.getColor(R.color.brand))

        val name = TextView(host)
        name.text = it.name
        name.setTextSize(19f)
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(host.getColor(R.color.text_primary))
        name.setPadding(0, dp(8), 0, 0)

        val meta = TextView(host)
        meta.setTextSize(12.5f)
        meta.setTextColor(host.getColor(R.color.text_secondary))
        meta.setPadding(0, dp(8), 0, 0)
        val loc = if (it.placeLabel().isEmpty()) "" else " · " + it.placeLabel()
        meta.text = it.timeLabel() + loc

        val cd = TextView(host)
        cd.setTextSize(12.5f)
        cd.setTextColor(host.getColor(R.color.brand))
        cd.setPadding(0, dp(8), 0, 0)
        val mins = if (ongoing) it.endMin - nowMin else it.startMin - nowMin
        cd.text = if (ongoing) "还剩 " + mins + " 分钟下课" else mins.toString() + " 分钟后上课"

        card.addView(tag)
        card.addView(name)
        card.addView(meta)
        card.addView(cd)

        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(14)
        card.layoutParams = lp
        return card
    }

    private fun courseRow(it: DayPlan.Item, nowMin: Int): LinearLayout {
        val done = it.endMin <= nowMin

        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(12), dp(16), dp(12))
        row.background = host.getDrawable(R.drawable.card_bg)

        // 左侧色条
        val bar = TextView(host)
        val blp = LinearLayout.LayoutParams(dp(3), dp(30))
        blp.rightMargin = dp(10)
        bar.setBackgroundColor(host.getColor(barColor(it.colorIndex)))
        row.addView(bar, blp)

        val mid = LinearLayout(host)
        mid.orientation = LinearLayout.VERTICAL
        val name = TextView(host)
        name.text = it.name
        name.setTextSize(14f)
        name.typeface = if (done) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        name.setTextColor(host.getColor(if (done) R.color.text_tertiary else R.color.text_primary))

        val line = TextView(host)
        line.setTextSize(11.5f)
        line.setTextColor(host.getColor(R.color.text_tertiary))
        val loc = if (it.placeLabel().isEmpty()) "" else "  " + it.placeLabel()
        line.text = it.timeLabel() + loc

        mid.addView(name)
        mid.addView(line)
        row.addView(mid, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val state = TextView(host)
        state.setTextSize(11f)
        state.setTextColor(host.getColor(R.color.text_tertiary))
        state.text = if (done) "已上完" else if (it.startMin <= nowMin) "进行中" else ""
        row.addView(state)

        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        row.layoutParams = lp
        return row
    }

    private fun emptyCard(title: String, sub: String): LinearLayout {
        val card = LinearLayout(host)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER
        card.setPadding(dp(16), dp(36), dp(16), dp(36))
        card.background = host.getDrawable(R.drawable.card_bg)

        val a = TextView(host)
        a.text = title
        a.setTextSize(15f)
        a.typeface = Typeface.DEFAULT_BOLD
        a.setTextColor(host.getColor(R.color.text_primary))
        a.gravity = Gravity.CENTER

        val b = TextView(host)
        b.text = sub
        b.setTextSize(12.5f)
        b.setTextColor(host.getColor(R.color.text_tertiary))
        b.gravity = Gravity.CENTER
        b.setPadding(0, dp(8), 0, 0)

        card.addView(a)
        card.addView(b)
        return card
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (h < 6) return "夜深了"
        if (h < 11) return "早上好"
        if (h < 14) return "中午好"
        if (h < 18) return "下午好"
        return "晚上好"
    }

    private fun barColor(idx: Int): Int {
        val ids = intArrayOf(R.color.course_0, R.color.course_1, R.color.course_2, R.color.course_3,
                R.color.course_4, R.color.course_5, R.color.course_6, R.color.course_7)
        return ids[Math.abs(idx) % 8]
    }

    fun reload() {
        build()
    }

    private fun dp(v: Int): Int {
        return Math.round(v * host.resources.displayMetrics.density)
    }

    companion object {
        // 教务与学工原站点。「更多功能」是教务首页，学工系统只提供 http。
        // 教务在校园网下 443 常常不通，这里直接用登录时探测过的 http（WebView 另有协议兜底）。
        private const val JWXT = "http://jwxt.tstc.edu.cn/xtgl/index_initMenu.html"
        private const val XUEGONG = "http://sws.tstc.edu.cn/XGTSTC/Phone/#/login"

        private fun shortDate(ymd: String): String {
            val p = ymd.split("-")
            if (p.size != 3) return ymd
            return p[1].toInt().toString() + " 月 " + p[2].toInt() + " 日"
        }

        private fun nowMinute(): Int {
            val c = Calendar.getInstance()
            return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        }
    }
}
