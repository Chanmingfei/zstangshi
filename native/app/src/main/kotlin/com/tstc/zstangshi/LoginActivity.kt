package com.tstc.zstangshi

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.jw.Auth
import com.tstc.zstangshi.ui.Checks
import com.tstc.zstangshi.widget.TodayWidget

/**
 * 登录页。
 *
 * 版式照旧版网页还原：整屏居中 —— 上面是 Logo + 校名标语，下面一张白卡片装表单
 * （学号、密码、记住密码/自动登录、错误条、登录按钮），卡片外面是「先看看课表」
 * 和一行隐私说明。
 *
 * 交互口径（2026-11-10 定）：
 *   · 勾了「自动登录」就一定要记住密码，两个勾互相咬住；
 *   · 登录成功才写盘，失败的输入留在框里不丢；
 *   · 底部的「先看看课表」是逃生门：教务连不上时，本地已有的课表不该被挡住；
 *   · 返回键直接退到桌面。
 */
class LoginActivity : BaseActivity() {

    private lateinit var store: Store

    /** true = 从「我的」进来补登录，成功后原地返回，而不是再开一个 MainActivity。 */
    private var returnMode = false
    private lateinit var idInput: EditText
    private lateinit var pwdInput: EditText

    /** 密码当前是否明文显示（显隐切换的显式状态，不再靠 inputType 位运算猜）。 */
    private var pwdShown = false
    private lateinit var remember: CheckBox
    private lateinit var auto: CheckBox
    private lateinit var error: TextView
    private lateinit var submit: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        returnMode = intent != null && intent.getBooleanExtra(EXTRA_RETURN, false)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(pageColor())
        scroll.isFillViewport = true

        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.gravity = Gravity.CENTER_VERTICAL
        page.setPadding(dpInt(24), dpInt(14), dpInt(24), dpInt(14))
        scroll.addView(
            page, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ── 品牌：校徽＋校名横版图 + 应用名 + 标语（与旧版登录页一致） ──
        val brand = LinearLayout(this)
        brand.orientation = LinearLayout.VERTICAL
        brand.gravity = Gravity.CENTER_HORIZONTAL

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.login_badge)
        logo.adjustViewBounds = true
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        brand.addView(
            logo, LinearLayout.LayoutParams(
                dpInt(182),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val name = TextView(this)
        name.setText(R.string.app_name)
        name.setTextSize(21f)
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(getColor(R.color.text_primary))
        name.letterSpacing = 0.04f
        name.gravity = Gravity.CENTER
        name.setPadding(0, dpInt(8), 0, 0)
        brand.addView(name)

        val slogan = TextView(this)
        slogan.text = "唐山师范学院 · 教务数据随身看"
        slogan.setTextSize(12.5f)
        slogan.setTextColor(getColor(R.color.text_secondary))
        slogan.gravity = Gravity.CENTER
        slogan.setPadding(0, dpInt(4), 0, dpInt(14))
        brand.addView(slogan)
        page.addView(
            brand, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ── 表单卡片 ──
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dpInt(16), dpInt(14), dpInt(16), dpInt(14))
        card.background = getDrawable(R.drawable.card_bg)
        card.elevation = dpInt(2).toFloat()

        card.addView(fieldLabel("学号"))
        idInput = EditText(this)
        idInput.hint = "10-12 位学号"
        idInput.setHintTextColor(getColor(R.color.text_tertiary))
        idInput.setTextColor(getColor(R.color.text_primary))
        idInput.setTextSize(15f)
        idInput.inputType = InputType.TYPE_CLASS_NUMBER
        idInput.background = getDrawable(R.drawable.input_bg)
        idInput.setPadding(dpInt(14), dpInt(11), dpInt(14), dpInt(11))
        idInput.isSingleLine = true
        idInput.setText(store.user())
        card.addView(
            idInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val pwdLabel = fieldLabel("密码")
        pwdLabel.setPadding(0, dpInt(10), 0, dpInt(6))
        card.addView(pwdLabel)

        val pwdRow = LinearLayout(this)
        pwdRow.orientation = LinearLayout.HORIZONTAL
        pwdRow.gravity = Gravity.CENTER_VERTICAL
        pwdRow.background = getDrawable(R.drawable.input_bg)
        pwdRow.setPadding(dpInt(14), 0, dpInt(6), 0)

        pwdInput = EditText(this)
        pwdInput.hint = "教务系统密码"
        pwdInput.setHintTextColor(getColor(R.color.text_tertiary))
        pwdInput.setTextColor(getColor(R.color.text_primary))
        pwdInput.setTextSize(15f)
        // 注意顺序：setSingleLine 会重置输入类型、清掉密码掩码（默认变明文），
        // 必须先调它，最后才设密码类型，保证初始就是密文、和眼睛初始图标一致
        pwdInput.isSingleLine = true
        pwdInput.imeOptions = EditorInfo.IME_ACTION_DONE
        pwdInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        pwdInput.background = null
        pwdInput.setPadding(0, dpInt(11), 0, dpInt(11))
        val remembered = store.rememberPwd() && store.pass().isNotEmpty()
        if (remembered) pwdInput.setText(store.pass())
        pwdRow.addView(
            pwdInput, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )

        // 眼睛按钮（照老版 Web 的 eye / eyeOff 图标）：显隐状态用显式布尔记录。
        // 之前用 inputType 按位与判断，VISIBLE_PASSWORD 和 PASSWORD 的位有重叠，
        // 判定永远是「隐藏中」，所以点第一次能显示、之后再也切不回去。
        pwdShown = false
        val eye = ImageView(this)
        eye.setImageResource(R.drawable.ic_eye)
        eye.setPadding(dpInt(8), dpInt(8), dpInt(8), dpInt(8))
        eye.contentDescription = "显示密码"
        eye.setOnClickListener {
            pwdShown = !pwdShown
            if (pwdShown) {
                pwdInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                eye.setImageResource(R.drawable.ic_eye_off)
                eye.contentDescription = "隐藏密码"
            } else {
                pwdInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                eye.setImageResource(R.drawable.ic_eye)
                eye.contentDescription = "显示密码"
            }
            // setInputType 会把字体重置成等宽，摆回去；光标挪到末尾
            pwdInput.typeface = Typeface.DEFAULT
            pwdInput.setSelection(pwdInput.text.length)
        }
        pwdRow.addView(eye)
        card.addView(
            pwdRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ── 记住密码 / 自动登录 ──
        val checks = LinearLayout(this)
        checks.orientation = LinearLayout.HORIZONTAL
        checks.gravity = Gravity.CENTER_VERTICAL
        checks.setPadding(0, dpInt(12), 0, 0)

        remember = check("记住密码", store.rememberPwd() || remembered)
        auto = check("自动登录", store.autoLogin())
        checks.addView(
            remember, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )
        checks.addView(auto)
        card.addView(checks)

        remember.setOnCheckedChangeListener { _, on ->
            if (!on && auto.isChecked) auto.isChecked = false
        }
        auto.setOnCheckedChangeListener { _, on ->
            if (on && !remember.isChecked) remember.isChecked = true
        }

        // ── 错误条 ──
        error = TextView(this)
        error.setTextSize(12.5f)
        error.setTextColor(0xFFB3261E.toInt())
        val eg = GradientDrawable()
        eg.setColor(0xFFFDECEA.toInt())
        eg.cornerRadius = dpInt(10).toFloat()
        error.background = eg
        error.setPadding(dpInt(12), dpInt(9), dpInt(12), dpInt(9))
        error.visibility = View.GONE
        val elp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        elp.topMargin = dpInt(12)
        card.addView(error, elp)

        val msg = intent?.getStringExtra(EXTRA_MESSAGE)
        if (!msg.isNullOrEmpty()) {
            error.text = msg
            error.visibility = View.VISIBLE
        }

        // ── 登录按钮 ──
        submit = TextView(this)
        submit.text = "登 录"
        submit.setTextSize(15.5f)
        submit.typeface = Typeface.DEFAULT_BOLD
        submit.gravity = Gravity.CENTER
        submit.setTextColor(0xFFFFFFFF.toInt())
        submit.background = getDrawable(R.drawable.btn_primary)
        submit.setPadding(0, dpInt(12), 0, dpInt(12))
        val slp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        slp.topMargin = dpInt(12)
        card.addView(submit, slp)
        submit.setOnClickListener { doLogin() }

        page.addView(
            card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ── 仅登录学工系统：绕开教务的侧门，不建立 App 登录态 ──
        val alt = LinearLayout(this)
        alt.orientation = LinearLayout.HORIZONTAL
        alt.gravity = Gravity.CENTER_VERTICAL
        alt.background = getDrawable(R.drawable.card_bg)
        alt.setPadding(dpInt(14), dpInt(14), dpInt(14), dpInt(14))
        alt.isClickable = true
        alt.setOnClickListener {
            WebViewActivity.open(
                this,
                "http://sws.tstc.edu.cn/XGTSTC/Phone/#/login", "学工系统"
            )
        }

        val altIcon = TextView(this)
        altIcon.text = "↗"
        altIcon.setTextSize(18f)
        altIcon.typeface = Typeface.DEFAULT_BOLD
        altIcon.gravity = Gravity.CENTER
        altIcon.setTextColor(getColor(R.color.brand))
        val iconBg = GradientDrawable()
        iconBg.setColor(getColor(R.color.brand_soft))
        iconBg.cornerRadius = dpInt(10).toFloat()
        altIcon.background = iconBg
        alt.addView(altIcon, LinearLayout.LayoutParams(dpInt(36), dpInt(36)))

        val altText = LinearLayout(this)
        altText.orientation = LinearLayout.VERTICAL
        val altTextLp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        altTextLp.leftMargin = dpInt(12)
        val altTitle = TextView(this)
        altTitle.text = "仅登录学工系统"
        altTitle.setTextSize(14f)
        altTitle.typeface = Typeface.DEFAULT_BOLD
        altTitle.setTextColor(getColor(R.color.text_primary))
        altText.addView(altTitle)
        val altDesc = TextView(this)
        altDesc.text = "奖助贷、请假、综合素质 · 免教务登录"
        altDesc.setTextSize(11f)
        altDesc.setTextColor(getColor(R.color.text_tertiary))
        altDesc.setPadding(0, dpInt(2), 0, 0)
        altText.addView(altDesc)
        alt.addView(altText, altTextLp)

        val altArrow = TextView(this)
        altArrow.text = "›"
        altArrow.setTextSize(22f)
        altArrow.setTextColor(getColor(R.color.text_tertiary))
        alt.addView(altArrow)

        val altLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        altLp.topMargin = dpInt(10)
        page.addView(alt, altLp)

        // ── 仅使用实时公交：不想登录教务的人也有门进，样式与上面的侧门卡片一致 ──
        val bus = LinearLayout(this)
        bus.orientation = LinearLayout.HORIZONTAL
        bus.gravity = Gravity.CENTER_VERTICAL
        bus.background = getDrawable(R.drawable.card_bg)
        bus.setPadding(dpInt(14), dpInt(14), dpInt(14), dpInt(14))
        bus.isClickable = true
        bus.setOnClickListener {
            // 不建立任何登录态，看完实时公交按返回还能回到登录页
            startActivity(Intent(this, BusActivity::class.java))
            overridePendingTransition(0, 0)
        }

        // 图标与实时公交入口同款：brand_soft 圆角底 + 品牌色公交正面
        val busIcon = ImageView(this)
        busIcon.setImageResource(R.drawable.ic_quick_bus)
        busIcon.setColorFilter(getColor(R.color.brand))
        val busBg = GradientDrawable()
        busBg.setColor(getColor(R.color.brand_soft))
        busBg.cornerRadius = dpInt(10).toFloat()
        busIcon.background = busBg
        busIcon.setPadding(dpInt(8), dpInt(8), dpInt(8), dpInt(8))
        bus.addView(busIcon, LinearLayout.LayoutParams(dpInt(36), dpInt(36)))

        val busText = LinearLayout(this)
        busText.orientation = LinearLayout.VERTICAL
        val busTextLp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        busTextLp.leftMargin = dpInt(12)
        val busTitle = TextView(this)
        busTitle.text = "仅使用实时公交"
        busTitle.setTextSize(14f)
        busTitle.typeface = Typeface.DEFAULT_BOLD
        busTitle.setTextColor(getColor(R.color.text_primary))
        busText.addView(busTitle)
        val busDesc = TextView(this)
        busDesc.text = "查附近站点与到站时间 · 免登录免账号"
        busDesc.setTextSize(11f)
        busDesc.setTextColor(getColor(R.color.text_tertiary))
        busDesc.setPadding(0, dpInt(2), 0, 0)
        busText.addView(busDesc)
        bus.addView(busText, busTextLp)

        val busArrow = TextView(this)
        busArrow.text = "›"
        busArrow.setTextSize(22f)
        busArrow.setTextColor(getColor(R.color.text_tertiary))
        bus.addView(busArrow)

        val busLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        busLp.topMargin = dpInt(10)
        page.addView(bus, busLp)
        // 从「我的」进来补登录的人本就在应用里，这扇门没有意义，一并藏掉
        if (returnMode) bus.visibility = View.GONE

        // ── 逃生门（从「我的」进来时没意义：本就在应用里） ──
        val skip = TextView(this)
        skip.text = if (store.hasLocal()) "先看看课表 ›" else "暂时不登录，直接进应用 ›"
        skip.setTextSize(13f)
        skip.setTextColor(getColor(R.color.text_secondary))
        skip.gravity = Gravity.CENTER
        skip.setPadding(0, dpInt(10), 0, 0)
        skip.setOnClickListener { goMain() }
        if (returnMode) skip.visibility = View.GONE
        page.addView(skip)

        val note = TextView(this)
        note.text = "密码用教务系统的 RSA 公钥加密后提交，本地只留一份用于自动登录"
        note.setTextSize(11f)
        note.setTextColor(getColor(R.color.text_secondary))
        note.gravity = Gravity.CENTER
        note.setPadding(dpInt(8), dpInt(10), dpInt(8), 0)
        page.addView(note)

        setContentView(scroll)
    }

    private fun fieldLabel(s: String): TextView {
        val v = TextView(this)
        v.text = s
        v.setTextSize(12.5f)
        v.typeface = Typeface.DEFAULT_BOLD
        v.setTextColor(getColor(R.color.text_secondary))
        v.setPadding(0, 0, 0, dpInt(6))
        return v
    }

    private fun check(label: String, on: Boolean): CheckBox {
        // 样式收在 ui/Checks 里：乘车码弹层的「记住选择」用的是同一个控件同一套颜色
        return Checks.create(this, label, on)
    }

    // ---------- 登录 ----------

    private fun doLogin() {
        val u = idInput.text.toString().trim()
        val p = pwdInput.text.toString()
        if (u.isEmpty()) {
            showError("先填学号")
            return
        }
        if (p.isEmpty()) {
            showError("先填密码")
            return
        }
        hideError()
        setBusy(true, "登录中…")

        Thread {
            val o = Auth.loginAndMaybeSync(u, p, store, true)
            runOnUiThread {
                setBusy(false, "登 录")
                if (!o.ok) {
                    showError(o.message)
                    return@runOnUiThread
                }
                persist(u, p)
                TodayWidget.refreshAll(this)
                goMain()
            }
        }.start()
    }

    /** 按勾选落盘：记住密码才存密码，否则只留学号。 */
    private fun persist(u: String, p: String) {
        val keepPwd = remember.isChecked || auto.isChecked
        store.setRememberPwd(keepPwd)
        store.setAutoLogin(auto.isChecked)
        if (keepPwd) store.saveLogin(u, p)
        else store.saveUser(u)
    }

    private fun setBusy(busy: Boolean, label: String) {
        submit.isEnabled = !busy
        submit.alpha = if (busy) 0.6f else 1f
        submit.text = label
    }

    private fun showError(s: String?) {
        error.text = if (s.isNullOrEmpty()) "登录失败，请再试一次" else s
        error.visibility = View.VISIBLE
    }

    private fun hideError() {
        error.visibility = View.GONE
    }

    private fun goMain() {
        if (returnMode) {
            // 从「我的」进来的：回到原来的 MainActivity，界面自己刷新
            finish()
            return
        }
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        finish()
    }

    /** 本页内部统一用 Int 的 dp（BaseActivity.dp 收 Float，字面量调用不便）。 */
    private fun dpInt(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    companion object {
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_RETURN = "return_to_caller"
    }
}
