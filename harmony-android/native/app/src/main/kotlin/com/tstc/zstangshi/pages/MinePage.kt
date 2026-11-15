package com.tstc.zstangshi.pages

import android.app.Activity
import android.app.DatePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tstc.zstangshi.LoginActivity
import com.tstc.zstangshi.MainActivity
import com.tstc.zstangshi.R
import com.tstc.zstangshi.WebViewActivity
import com.tstc.zstangshi.data.AcademicCalendar
import com.tstc.zstangshi.data.Dates
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.data.Timetable
import com.tstc.zstangshi.data.WeekMath
import com.tstc.zstangshi.jw.JwClient
import com.tstc.zstangshi.ui.OptionSheet
import com.tstc.zstangshi.ui.SegmentedControl
import com.tstc.zstangshi.ui.Sheet
import com.tstc.zstangshi.widget.TodayWidget
import java.util.Calendar

/**
 * 我的：登录教务、同步课表、开学日期、深色模式、关于。
 *
 * 登录与同步放在这里而不是首屏，是为了兑现「没登录也能看课表」：
 * 首屏直接进课表，需要更新时才来这里按一下。
 */
class MinePage(private val host: Activity, private val store: Store,
               private val callback: OnTimetableChanged?) : ScrollView(host) {

    fun interface OnTimetableChanged {
        fun onChanged()
    }

    private val body: LinearLayout

    private lateinit var accountTitle: TextView
    private lateinit var accountSub: TextView
    private lateinit var accountMeta: TextView
    private lateinit var accountAvatar: TextView
    private lateinit var accountTag: TextView
    private lateinit var syncHint: TextView
    private var awaitingLogin = false

    init {
        setBackgroundColor(if (store.darkNow()) 0xFF0F1520.toInt() else host.getColor(R.color.bg_page))
        setFillViewport(true)

        body = LinearLayout(host)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16), dp(12), dp(16), dp(24))
        addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))

        build()
    }

    private fun build() {
        body.removeAllViews()

        // ── 账号卡（旧版：圆形头像 + 姓名主体 + 打码学号 + 状态标签） ──
        val accountCard = card()
        val hero = LinearLayout(host)
        hero.orientation = LinearLayout.HORIZONTAL
        hero.gravity = Gravity.CENTER_VERTICAL

        accountAvatar = TextView(host)
        accountAvatar.setTextSize(20f)
        accountAvatar.typeface = Typeface.DEFAULT_BOLD
        accountAvatar.gravity = Gravity.CENTER
        accountAvatar.setTextColor(0xFFFFFFFF.toInt())
        val avBg = GradientDrawable()
        avBg.setShape(GradientDrawable.OVAL)
        avBg.setColor(host.getColor(R.color.brand))
        accountAvatar.background = avBg
        hero.addView(accountAvatar, LinearLayout.LayoutParams(dp(46), dp(46)))

        val who = LinearLayout(host)
        who.orientation = LinearLayout.VERTICAL
        val whoLp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        whoLp.leftMargin = dp(12)

        accountTitle = TextView(host)
        accountTitle.setTextSize(16f)
        accountTitle.typeface = Typeface.DEFAULT_BOLD
        accountTitle.setTextColor(host.getColor(R.color.text_primary))
        who.addView(accountTitle)

        accountSub = TextView(host)
        accountSub.setTextSize(12f)
        accountSub.setTextColor(host.getColor(R.color.text_tertiary))
        accountSub.setPadding(0, dp(3), 0, 0)
        who.addView(accountSub)
        hero.addView(who, whoLp)

        accountTag = TextView(host)
        accountTag.setTextSize(11f)
        accountTag.typeface = Typeface.DEFAULT_BOLD
        accountTag.setTextColor(host.getColor(R.color.brand))
        accountTag.setPadding(dp(10), dp(5), dp(10), dp(5))
        val tagBg = GradientDrawable()
        tagBg.cornerRadius = dp(9).toFloat()
        tagBg.setColor(if (store.darkNow()) 0x332E86E8 else 0x1A0062CC)
        accountTag.background = tagBg
        hero.addView(accountTag)
        accountCard.addView(hero)

        accountMeta = TextView(host)
        accountMeta.setTextSize(11.5f)
        accountMeta.setTextColor(host.getColor(R.color.text_tertiary))
        accountMeta.setPadding(0, dp(10), 0, 0)
        accountCard.addView(accountMeta)

        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(14), 0, 0)

        val loginBtn = btn(if (isLoggedIn()) "重新登录" else "登录教务", false)
        loginBtn.setOnClickListener { showLogin() }
        row.addView(loginBtn, LinearLayout.LayoutParams(0, dp(44), 1f))

        val syncBtn = btn("同步课表", true)
        syncBtn.setOnClickListener { doSync(syncBtn) }
        val slp = LinearLayout.LayoutParams(0, dp(44), 1f)
        slp.leftMargin = dp(10)
        row.addView(syncBtn, slp)
        accountCard.addView(row)

        syncHint = TextView(host)
        syncHint.setTextSize(11f)
        syncHint.setTextColor(host.getColor(R.color.text_tertiary))
        syncHint.setPadding(0, dp(10), 0, 0)
        accountCard.addView(syncHint)

        // 自动登录：冷启动跳过登录页。没有存密码就开不了，引导先登录一次
        accountCard.addView(switchItem("自动登录", store.autoLogin(), object : SwitchChange {
            override fun onChanged(sw: View, on: Boolean) {
                if (on && store.pass().isEmpty()) {
                    store.setAutoLogin(false)
                    paintSwitch(sw, false)
                    Toast.makeText(host, "先登录一次并记住密码，才能自动登录", Toast.LENGTH_SHORT).show()
                    return
                }
                store.setAutoLogin(on)
                Toast.makeText(host, if (on) "下次打开直接自动登录" else "已关闭自动登录，下次要先登录",
                        Toast.LENGTH_SHORT).show()
            }
        }))

        // ── 课表设置（只保留「学期与教学周」：学年 / 学期 / 第一周周一都在那张表单里，
        //    单独的开学日、节次数量入口没有存在价值，反而把页面堆乱） ──
        val set = card()
        set.addView(sectionTitle("课表设置"))

        val t = store.loadTimetable()
        val sem = if (t == null) "—" else t.semester.name
        set.addView(item("学期与教学周", if (sem.isEmpty()) "点这里设置" else sem,
                View.OnClickListener { openTermSheet() }))

        // ── 外观（三联段控，跟底栏一套胶囊动效） ──
        val look = card()
        look.addView(sectionTitle("外观"))
        val seg = SegmentedControl(host,
                arrayOf("浅色", "深色", "跟随系统"), store.themeMode())
        val segLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        segLp.topMargin = dp(4)
        look.addView(seg, segLp)
        seg.setOnChange(object : SegmentedControl.OnChange {
            override fun onChanged(index: Int) {
                store.setThemeMode(index)
                // 等胶囊滑到位（260ms）再重建，重建完由 MainActivity 淡入，
                // 这样看到的是「胶囊滑过去 → 界面换色」两个动作，而不是硬切
                MainActivity.beginThemeRestart()
                seg.postDelayed({ host.recreate() }, 260)
            }
        })

        // ── 桌面小组件（独立板块，和「关于」区分开） ──
        val widgetCard = card()
        widgetCard.addView(sectionTitle("桌面小组件"))
        widgetCard.addView(item("添加今日课程", "一键放到桌面", View.OnClickListener { pinWidget() }))
        widgetCard.addView(item("刷新小组件", "立即重画今日课程", View.OnClickListener {
            TodayWidget.refreshAll(host)
            Toast.makeText(host, "已刷新", Toast.LENGTH_SHORT).show()
        }))

        // ── 关于 ──
        val about = card()
        about.addView(sectionTitle("关于"))
        about.addView(item("版本", versionName(), null))
        // 鸿蒙专版标记：和 Play/应用市场里的标准版明确区分
        about.addView(item("版本类型", "鸿蒙专版 · HarmonyOS 适配", null))
        about.addView(item("数据来源", "正方教务系统 · 教务处校历", null))

        // 开发者后面直接挂两个平台主页（logo 与文字等大，走 App 内置浏览器）
        val devName = TextView(host)
        devName.text = "陈铭霏"
        devName.setTextSize(12.5f)
        devName.setTextColor(host.getColor(R.color.text_secondary))
        devName.setSingleLine(true)
        val devLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        devLp.rightMargin = dp(4)
        devName.layoutParams = devLp
        about.addView(customItem("开发者", devName,
                linkChip(R.drawable.ic_github, "GitHub主页", GITHUB_HOME, "GitHub"),
                linkChip(R.drawable.ic_gitee, "Gitee主页", GITEE_HOME, "Gitee")))

        about.addView(item("荣誉出品", "百度唐山师范学院贴吧吧务组", null))

        // 项目地址：GitHub 与 Gitee 两个仓库，同样应用内打开
        about.addView(customItem("项目地址",
                linkChip(R.drawable.ic_github, "GitHub仓库", REPO_GITHUB, "GitHub 仓库"),
                linkChip(R.drawable.ic_gitee, "Gitee仓库", REPO_GITEE, "Gitee 仓库")))

        refreshAccount()
    }

    // ---------- 开发者主页 / 项目仓库 ----------

    /** 左边标题、右边一串自定义控件的行（纯文本行用 item()）。 */
    private fun customItem(label: String, vararg right: View): LinearLayout {
        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(12), 0, dp(12))

        val l = TextView(host)
        l.text = label
        l.setTextSize(14f)
        l.setTextColor(host.getColor(R.color.text_primary))
        l.setSingleLine(true)
        l.ellipsize = TextUtils.TruncateAt.END
        row.addView(l, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        for (v in right) row.addView(v)
        return row
    }

    /** 平台链接：小 logo + 品牌色文字，点开走 App 内置浏览器。 */
    private fun linkChip(iconRes: Int, text: String, url: String, title: String): LinearLayout {
        val chip = LinearLayout(host)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.isClickable = true
        chip.background = linkRipple()
        chip.setPadding(dp(6), dp(5), dp(6), dp(5))

        val logo = ImageView(host)
        logo.setImageResource(iconRes)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        // 与文字等大：按 12.5sp 的实际像素高度取正方形
        val side = Math.round(12.5f * resources.displayMetrics.scaledDensity)
        chip.addView(logo, LinearLayout.LayoutParams(side, side))

        val tv = TextView(host)
        tv.text = text
        tv.setTextSize(12.5f)
        tv.setTextColor(host.getColor(R.color.brand))
        tv.setSingleLine(true)
        val tlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlp.leftMargin = dp(4)
        chip.addView(tv, tlp)

        chip.setOnClickListener { WebViewActivity.open(host, url, title) }

        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = dp(2)
        chip.layoutParams = lp
        return chip
    }

    /** 链接的按压水波纹：内容透明，只留一层圆角涟漪。 */
    private fun linkRipple(): Drawable {
        val mask = GradientDrawable()
        mask.setColor(0xFFFFFFFF.toInt())
        mask.cornerRadius = dp(9).toFloat()
        return RippleDrawable(ColorStateList.valueOf(0x220062CC), null, mask)
    }

    // ---------- 学期与教学周（旧版那张「学期与教学周设置」表单） ----------

    /**
     * 学年 / 学期 / 第一周周一 —— 这三个才是「第几周」的真正输入。
     * 保存后立刻切到该学期并落盘，课表、首页、小组件都跟着重算。
     */
    private fun openTermSheet() {
        val t = store.loadTimetable()
        val curId = if (t == null) store.activeSemester() else t.semester.id
        val activeId = curId
        var curStart = if (t == null) "" else t.semester.startDate
        if (curStart.isEmpty()) curStart = store.startDate(curId)

        // 学年候选：8 月起算新学年，前后各留两年
        val now = Calendar.getInstance()
        val base = if (now.get(Calendar.MONTH) + 1 >= 8)
            now.get(Calendar.YEAR) else now.get(Calendar.YEAR) - 1
        val years = Array(4) { i -> (base - 2 + i).toString() + "-" + (base - 1 + i) }

        val year = arrayOf(if (curId.length >= 9) curId.substring(0, 9) else years[2])
        val term = arrayOf(if (curId.endsWith("-2")) "第二学期" else "第一学期")
        val start = arrayOf(curStart)

        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(18), dp(4), dp(18), dp(12))

        val title = TextView(host)
        title.text = "学期与教学周"
        title.setTextSize(16f)
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(host.getColor(R.color.text_primary))
        title.setPadding(0, 0, 0, dp(4))
        box.addView(title)

        val yearRow = item("学年", year[0] + " 学年", null)
        yearRow.setOnClickListener {
            OptionSheet.show(host, "选择学年",
                    years, indexOf(years, year[0]), object : OptionSheet.OnPick {
                        override fun onPick(index: Int) {
                            year[0] = years[index]
                            (yearRow.getChildAt(1) as TextView).text = year[0] + " 学年"
                        }
                    })
        }
        box.addView(yearRow)

        val termOpts = arrayOf("第一学期", "第二学期")
        val termRow = item("学期", term[0], null)
        termRow.setOnClickListener {
            OptionSheet.show(host, "选择学期",
                    termOpts, if (term[0] == "第二学期") 1 else 0, object : OptionSheet.OnPick {
                        override fun onPick(index: Int) {
                            term[0] = termOpts[index]
                            (termRow.getChildAt(1) as TextView).text = term[0]
                        }
                    })
        }
        box.addView(termRow)

        val startRow = item("第一周周一", if (start[0].isEmpty()) "选一个日期" else start[0], null)
        startRow.setOnClickListener {
            val c = Calendar.getInstance()
            if (Dates.valid(start[0])) {
                val p = start[0].split("-")
                try {
                    c.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
                } catch (ignored: Exception) {
                }
            }
            DatePickerDialog(host, { picker, y, m, d ->
                start[0] = String.format("%04d-%02d-%02d", y, m + 1, d)
                (startRow.getChildAt(1) as TextView).text = start[0]
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH),
                    c.get(Calendar.DAY_OF_MONTH)).show()
        }
        box.addView(startRow)

        val hint = TextView(host)
        hint.text = "保存后按这个学期与第一周算周数，重启仍生效；非周一的日期会自动归到那一周的周一。"
        hint.setTextSize(11.5f)
        hint.setTextColor(host.getColor(R.color.text_tertiary))
        hint.setLineSpacing(dp(2).toFloat(), 1f)
        hint.setPadding(0, dp(8), 0, dp(14))
        box.addView(hint)

        // ── 底部：取消 / 保存并生效 ──
        val actions = LinearLayout(host)
        actions.orientation = LinearLayout.HORIZONTAL

        val cancel = TextView(host)
        cancel.text = "取消"
        cancel.setTextSize(14f)
        cancel.gravity = Gravity.CENTER
        cancel.setTextColor(host.getColor(R.color.text_secondary))
        cancel.background = host.getDrawable(R.drawable.btn_outline)

        val save = TextView(host)
        save.text = "保存并生效"
        save.setTextSize(14f)
        save.typeface = Typeface.DEFAULT_BOLD
        save.gravity = Gravity.CENTER
        save.setTextColor(0xFFFFFFFF.toInt())
        save.background = host.getDrawable(R.drawable.btn_primary)

        val halfLp = LinearLayout.LayoutParams(0, dp(44), 1f)
        actions.addView(cancel, halfLp)
        val saveLp = LinearLayout.LayoutParams(0, dp(44), 1f)
        saveLp.leftMargin = dp(10)
        actions.addView(save, saveLp)
        box.addView(actions)

        val sheet = Sheet.show(host, box, dp(360))
        cancel.setOnClickListener { sheet.dismiss() }
        save.setOnClickListener {
            val id = year[0] + (if (term[0] == "第二学期") "-2" else "-1")
            if (start[0].isEmpty() || !Dates.valid(start[0])) {
                Toast.makeText(host, "先选第一周周一的日期", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val monday = WeekMath.normalizeToMonday(start[0])
            if (id != activeId && store.loadTimetable(id) == null) {
                Toast.makeText(host, "这个学期还没有课表，先登录教务同步一次", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            store.setActiveSemester(id)
            store.setStartDate(id, monday)
            sheet.dismiss()
            callback?.onChanged()
            build()
            Toast.makeText(host, "已按第 1 周 " + monday + " 生效", Toast.LENGTH_SHORT).show()
        }
    }

    /** 系统支持的话直接弹「添加小组件」确认框，省得用户去桌面长按翻列表。 */
    private fun pinWidget() {
        // ⚠️ 鸿蒙专版：鸿蒙没有 appwidget 服务，getInstance 和
        // isRequestPinAppWidgetSupported 都可能抛，整段包起来，失败只提示文字。
        try {
            val mgr = AppWidgetManager.getInstance(host)
            if (mgr == null || !mgr.isRequestPinAppWidgetSupported) {
                Toast.makeText(host, "请在桌面长按 → 小组件里添加「今日课程」", Toast.LENGTH_LONG).show()
                return
            }
            val cn = ComponentName(
                    host, com.tstc.zstangshi.widget.TodayWidget::class.java)
            mgr.requestPinAppWidget(cn, null, null)
        } catch (t: Throwable) {
            Toast.makeText(host, "请在桌面长按 → 小组件里添加「今日课程」", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshAccount() {
        val logged = isLoggedIn()
        val name = store.studentName(store.user())
        val masked = maskNo(store.user())

        // 姓名是主体，学号弱化成第二行；抓不到姓名时回退展示学号（旧版口径）
        accountTitle.text = if (logged)
            (if (name.isEmpty()) (if (masked.isEmpty()) "已登录" else masked) else name)
        else "未登录"
        accountSub.text = if (logged)
            (if (name.isEmpty()) "登录状态由教务会话维持" else masked)
        else "登录教务后可同步课表与成绩"
        accountTag.text = if (logged) "已登录" else "访客"
        accountAvatar.text = if (name.isEmpty()) "唐" else name.substring(0, 1)

        val last = store.lastSync()
        val t = store.loadTimetable()
        // 自动校正开学日：没手动改过、且官方校历覆盖本学期时，用校历开学日覆盖旧的猜测值
        if (t != null && !t.semester.startDateTouched) {
            var term: AcademicCalendar.Term? = AcademicCalendar.termById(t.semester.id)
            if (term == null) term = AcademicCalendar.termFor(WeekMath.todayDateLabel())
            if (term != null && term.classStart.isNotEmpty() &&
                    term.classStart != t.semester.startDate) {
                t.semester.startDate = WeekMath.normalizeToMonday(term.classStart)
                store.setStartDate(t.semester.id, t.semester.startDate)
            }
        }
        if (t != null && t.courses.isNotEmpty()) {
            accountMeta.text = "本地课表 " + t.courses.size + " 门 · " +
                    (if (last > 0) "上次同步 " + ago(last) else "从未同步")
        } else {
            accountMeta.text = "本地还没有课表，登录教务同步一次"
        }
        syncHint.text = if (logged)
            "同步会以教务为准覆盖本地改动（手工加的课保留）"
        else "密码只存在本机，用于自动登录教务"
    }

    /** 学号只露前 4 后 4，跟旧版一致。 */
    private fun maskNo(no: String): String {
        if (no.length < 8) return no
        return no.substring(0, 4) + "****" + no.substring(no.length - 4)
    }

    private fun isLoggedIn(): Boolean {
        return store.user().isNotEmpty() && store.pass().isNotEmpty()
    }

    // ---------- 登录 ----------

    private fun showLogin() {
        // 不再在这里弹系统输入框：登录页是独立页面（品牌图 + 记住密码 + 自动登录），
        // 从「我的」进去登录成功后原地回到本页，不再新开一个 MainActivity。
        awaitingLogin = true
        host.startActivity(Intent(host, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_RETURN, true))
    }

    /** 登录页回来时叫一次：走了登录流程才重建，正常切页不白白刷一遍。 */
    fun onReturned() {
        if (awaitingLogin) {
            awaitingLogin = false
            build()
        }
    }

    // ---------- 同步 ----------

    private fun doSync(btn: View?) {
        if (!isLoggedIn()) {
            Toast.makeText(host, "先登录教务", Toast.LENGTH_SHORT).show()
            showLogin()
            return
        }
        if (btn != null) btn.isEnabled = false
        Toast.makeText(host, "正在同步课表…", Toast.LENGTH_SHORT).show()
        Thread(work@{
            val c = JwClient(store)
            val login = c.login(store.user(), store.pass())
            if (!login.ok) {
                host.runOnUiThread {
                    if (btn != null) btn.isEnabled = true
                    Toast.makeText(host, login.message, Toast.LENGTH_LONG).show()
                }
                return@work
            }
            if (login.studentName.isNotEmpty()) store.setStudentName(login.studentName, store.user())
            val tt = c.fetchTimetable()
            if (!tt.ok) {
                host.runOnUiThread {
                    if (btn != null) btn.isEnabled = true
                    Toast.makeText(host, tt.message, Toast.LENGTH_LONG).show()
                }
                return@work
            }
            // 开学日期：教务接口不给，沿用本地已有的；第一次同步时用今天往前推到周一兜底
            val timetable = tt.timetable ?: return@work
            val old = store.startDate(tt.semesterId)
            if (old.isEmpty()) {
                timetable.semester.startDate = guessStart(timetable)
            } else {
                timetable.semester.startDate = old
            }
            store.saveTimetable(timetable)
            TodayWidget.refreshAll(host)

            host.runOnUiThread {
                if (btn != null) btn.isEnabled = true
                refreshAccount()
                Toast.makeText(host, "同步完成：" + timetable.courses.size + " 门课",
                        Toast.LENGTH_SHORT).show()
                callback?.onChanged()
            }
        }).start()
    }

    /**
     * 开学日期：教务接口不给，第一次同步时用「教务处校历」的开学日（第 1 周周一）。
     * 唐山师范学院 2026-2027-1 校历为 2026-08-31 正式上课；若校历未覆盖当前学期，
     * 再退回「今天所在周周一」保底（用户手动改过会记住 startDateTouched）。
     */
    private fun guessStart(t: Timetable): String {
        var term: AcademicCalendar.Term? = AcademicCalendar.termById(t.semester.id)
        if (term == null) {
            term = AcademicCalendar.termFor(WeekMath.todayDateLabel())
        }
        if (term != null && term.classStart.isNotEmpty()) {
            return WeekMath.normalizeToMonday(term.classStart)
        }
        // 兜底：仍按今天所在周周一（极端情况下校历未覆盖）
        return WeekMath.normalizeToMonday(WeekMath.todayDateLabel())
    }

    fun onShown() {
        build()
    }

    // ---------- 小工具 ----------

    /** 造一张卡片并**已经挂到 body 上**（返回它只是为了往里加内容，别再 addView 一次）。 */
    private fun card(): LinearLayout {
        val l = LinearLayout(host)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), dp(16), dp(16), dp(16))
        l.background = host.getDrawable(R.drawable.card_bg)
        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(12)
        body.addView(l, lp)
        return l
    }

    private fun sectionTitle(s: String): TextView {
        val v = TextView(host)
        v.text = s
        v.setTextSize(13f)
        v.typeface = Typeface.DEFAULT_BOLD
        v.setTextColor(host.getColor(R.color.text_tertiary))
        v.setPadding(0, 0, 0, dp(8))
        return v
    }

    private fun item(label: String, value: String, click: View.OnClickListener?): LinearLayout {
        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(12), 0, dp(12))

        val l = TextView(host)
        l.text = label
        l.setTextSize(14f)
        l.setTextColor(host.getColor(R.color.text_primary))
        row.addView(l, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val v = TextView(host)
        v.text = value
        v.setTextSize(13f)
        v.setTextColor(host.getColor(R.color.text_tertiary))
        row.addView(v)

        if (click != null) row.setOnClickListener(click)
        return row
    }

    private fun interface SwitchChange {
        fun onChanged(btn: View, on: Boolean)
    }

    /** 开关的「开/关」胶囊外观。toggle 与外部回滚（比如没密码不让开自动登录）共用一份。 */
    private fun paintSwitch(view: View, on: Boolean) {
        val sw = view as TextView
        sw.text = if (on) "开" else "关"
        sw.setTextSize(13f)
        sw.gravity = Gravity.CENTER
        sw.setPadding(dp(14), dp(6), dp(14), dp(6))
        sw.background = host.getDrawable(if (on) R.drawable.pill_on else R.drawable.pill_off)
        sw.setTextColor(if (on) 0xFFFFFFFF.toInt() else host.getColor(R.color.text_secondary))
    }

    private fun switchItem(label: String, on: Boolean, cb: SwitchChange): LinearLayout {
        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))

        val l = TextView(host)
        l.text = label
        l.setTextSize(14f)
        l.setTextColor(host.getColor(R.color.text_primary))
        row.addView(l, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val state = booleanArrayOf(on)
        val sw = TextView(host)
        paintSwitch(sw, on)
        sw.setOnClickListener {
            state[0] = !state[0]
            paintSwitch(sw, state[0])
            cb.onChanged(sw, state[0])
        }
        row.addView(sw)
        return row
    }

    private fun btn(s: String, primary: Boolean): TextView {
        val v = TextView(host)
        v.text = s
        v.setTextSize(14f)
        v.typeface = Typeface.DEFAULT_BOLD
        v.gravity = Gravity.CENTER
        v.background = host.getDrawable(if (primary) R.drawable.btn_primary else R.drawable.btn_outline)
        v.setTextColor(if (primary) 0xFFFFFFFF.toInt() else host.getColor(R.color.brand))
        return v
    }

    private fun versionName(): String {
        return try {
            host.packageManager.getPackageInfo(host.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            "—"
        }
    }

    private fun ago(ts: Long): String {
        val min = (System.currentTimeMillis() - ts) / 60000
        if (min < 1) return "刚刚"
        if (min < 60) return min.toString() + " 分钟前"
        val h = min / 60
        if (h < 24) return h.toString() + " 小时前"
        return (h / 24).toString() + " 天前"
    }

    private fun dp(v: Int): Int {
        return Math.round(v * resources.displayMetrics.density)
    }

    companion object {
        private const val GITHUB_HOME = "https://github.com/Chanmingfei/"
        private const val GITEE_HOME = "https://gitee.com/mingfei123/"
        private const val REPO_GITHUB = "https://github.com/Chanmingfei/zstangshi"
        private const val REPO_GITEE = "https://gitee.com/mingfei123/zstangshi"

        private fun indexOf(arr: Array<String>, v: String): Int {
            for (i in arr.indices) {
                if (arr[i] == v) return i
            }
            return 0
        }
    }
}
