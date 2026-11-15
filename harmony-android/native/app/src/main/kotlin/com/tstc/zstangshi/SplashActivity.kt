package com.tstc.zstangshi

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.jw.Auth
import com.tstc.zstangshi.widget.TodayWidget

/**
 * 冷启动的第一屏（加载页）。
 *
 * 版式照旧版 index.html 的 #boot-splash：校徽横版图（缓慢呼吸）+ 掌上唐师 +
 * 标语 + 三颗错开呼吸的圆点 —— 它和登录页品牌区同源，从加载页切到登录页不会「跳一下」。
 *
 * 两条路：
 *   · 勾了自动登录、本地又有账号密码 —— 停在这里显示「自动登录中」，后台登教务，
 *     成功了直接进主界面，课表过期就顺手同步一次；
 *   · 没勾自动登录 —— 亮一下就去登录页（带 350ms 过渡，避免闪一下就跳）。
 *
 * 这里同时也是「点桌面图标」的唯一入口；桌面小组件是另一条线，它直接跳
 * MainActivity，不经过登录 —— 断网时点小组件也得能看课表。
 */
class SplashActivity : BaseActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    /** 自动登录期间的快捷入口：不等教务，先去实时公交。 */
    private lateinit var skipBtn: TextView
    private var left = false

    /** 用户点了「直接进入实时公交」：登录留在后台，回加载页时再接去主界面。 */
    private var busJumped = false

    /** 后台登录是否已结束（busJumped 时用它决定回到加载页后怎么走）。 */
    private var loginDone = false
    private var loginOk = false

    /** 加载页当前是否在前台（busJumped 后回来时要不要立刻进主界面）。 */
    private var resumed = false
    private lateinit var store: Store

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(pageColor())

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        val pad = dp(24)
        box.setPadding(pad, pad, pad, pad)

        // ── 校徽 + 校名（呼吸：缩放 1 → 0.995，透明度 1 → 0.86，2.6s 一循环） ──
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.login_badge)
        logo.adjustViewBounds = true
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        box.addView(
            logo, LinearLayout.LayoutParams(
                dp(200),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        breathe(logo)

        val name = TextView(this)
        name.setText(R.string.app_name)
        name.setTextSize(22f)
        name.typeface = Typeface.DEFAULT_BOLD
        name.setTextColor(getColor(R.color.text_primary))
        name.gravity = Gravity.CENTER
        name.letterSpacing = 0.04f
        val nlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        nlp.topMargin = dp(16)
        box.addView(name, nlp)

        val slogan = TextView(this)
        slogan.text = "唐山师范学院 · 教务数据随身看"
        slogan.setTextSize(12.5f)
        slogan.setTextColor(getColor(R.color.text_secondary))
        slogan.gravity = Gravity.CENTER
        val slp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        slp.topMargin = dp(8)
        box.addView(slogan, slp)

        // ── 三颗呼吸圆点（错开 160ms，比转圈轻，不跟校徽抢注意力） ──
        val dots = LinearLayout(this)
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        for (i in 0 until 3) {
            val dot = View(this)
            val c = GradientDrawable()
            c.shape = GradientDrawable.OVAL
            c.setColor(getColor(R.color.brand))
            dot.background = c
            dot.alpha = 0.24f
            val dlp2 = LinearLayout.LayoutParams(dp(6), dp(6))
            if (i > 0) dlp2.leftMargin = dp(8)
            dots.addView(dot, dlp2)
            pulse(dot, i * 160L)
        }
        val dlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dlp.topMargin = dp(32)
        box.addView(dots, dlp)

        status = TextView(this)
        status.setTextSize(11.5f)
        status.setTextColor(getColor(R.color.text_tertiary))
        status.gravity = Gravity.CENTER
        val stlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        stlp.topMargin = dp(14)
        box.addView(status, stlp)

        // 教务自动登录有时要等很久：给个横条按钮，不想等的人先进实时公交，
        // 登录在后台接着跑，回到这页时如果已经登好就直接送进主界面。
        // 位置放在加载动画正下方 —— 大拇指自然悬停的高度，不用伸到底部够；
        // 样式和 APP 其他主按钮一致：圆角矩形（12dp）
        box.minimumWidth = dp(280)
        skipBtn = TextView(this)
        skipBtn.text = "直接进入实时公交"
        skipBtn.setTextSize(14.5f)
        skipBtn.typeface = Typeface.DEFAULT_BOLD
        skipBtn.setTextColor(0xFFFFFFFF.toInt())
        skipBtn.gravity = Gravity.CENTER
        val skipBg = GradientDrawable()
        skipBg.setColor(getColor(R.color.brand))
        skipBg.cornerRadius = dp(12).toFloat()
        skipBtn.background = skipBg
        skipBtn.setOnClickListener { goBus() }
        val skipLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        )
        skipLp.topMargin = dp(24)
        box.addView(skipBtn, skipLp)

        val boxLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        boxLp.gravity = Gravity.CENTER
        root.addView(box, boxLp)
        setContentView(root)

        if (!Auth.canAutoLogin(store)) {
            // 没开自动登录：亮一下就去登录页（旧版这里只有圆点，不写文案）
            status.text = ""
            skipBtn.visibility = View.GONE
            ui.postDelayed({ goLogin() }, 350)
            return
        }

        status.text = "自动登录中"

        ui.postDelayed({
            if (left || busJumped) return@postDelayed   // 已经进实时公交的就别再踢去登录页了
            goLogin("自动登录超时了，请手动登录一次")
        }, TIMEOUT_MS)

        Thread {
            val o = Auth.loginAndMaybeSync(store.user(), store.pass(), store, false)
            ui.post {
                if (left) return@post
                loginDone = true
                loginOk = o.ok
                if (busJumped) {
                    // 人已经在实时公交里：不打扰，只把结果记下，等回到这页再接走
                    if (o.ok) {
                        TodayWidget.refreshAll(this)
                        status.text = "教务登录完成，返回后自动进入主界面"
                        if (resumed) goMain()
                    } else {
                        status.text = o.message
                        skipBtn.text = "去手动登录"
                        skipBtn.setOnClickListener { goLogin(o.message) }
                    }
                    return@post
                }
                if (o.ok) {
                    TodayWidget.refreshAll(this)
                    goMain()
                } else {
                    goLogin(o.message)
                }
            }
        }.start()
    }

    /**
     * 「直接进入实时公交」：加载页留在任务栈里，登录继续在后台跑。
     * 可以反复点 —— 从实时公交退回来（登录还没好）再点一次要照样能进。
     */
    private fun goBus() {
        if (left) return
        busJumped = true
        status.text = "教务登录已转到后台，正在进入实时公交…"
        startActivity(Intent(this, BusActivity::class.java))
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // 从实时公交退回来时，后台登录多半已经完成 —— 别让人再对着加载页等
        if (busJumped && loginDone && loginOk && !left) goMain()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    /** 校徽呼吸：透明度 1 ↔ 0.86，缩放 1 ↔ 0.995，2.6s 一轮。 */
    private fun breathe(v: View) {
        val a = ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.86f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.995f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.995f)
        )
        a.duration = 1300
        a.repeatMode = ValueAnimator.REVERSE
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = AccelerateDecelerateInterpolator()
        a.start()
    }

    /**
     * 圆点呼吸：0.24/0.86 起，40% 到 1/1，再回 0.24/0.86，1.2s 一轮。
     * 用 Keyframe 而不是 REVERSE —— 原版峰值在 40% 处，不是正中。
     */
    private fun pulse(dot: View, delayMs: Long) {
        val alpha = PropertyValuesHolder.ofKeyframe(
            View.ALPHA,
            Keyframe.ofFloat(0f, 0.24f),
            Keyframe.ofFloat(0.4f, 1f),
            Keyframe.ofFloat(1f, 0.24f)
        )
        val sx = PropertyValuesHolder.ofKeyframe(
            View.SCALE_X,
            Keyframe.ofFloat(0f, 0.86f),
            Keyframe.ofFloat(0.4f, 1f),
            Keyframe.ofFloat(1f, 0.86f)
        )
        val sy = PropertyValuesHolder.ofKeyframe(
            View.SCALE_Y,
            Keyframe.ofFloat(0f, 0.86f),
            Keyframe.ofFloat(0.4f, 1f),
            Keyframe.ofFloat(1f, 0.86f)
        )
        val a = ObjectAnimator.ofPropertyValuesHolder(dot, alpha, sx, sy)
        a.duration = 1200
        a.startDelay = delayMs
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = AccelerateDecelerateInterpolator()
        a.start()
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    private fun goLogin() {
        goLogin("")
    }

    private fun goLogin(message: String?) {
        val i = Intent(this, LoginActivity::class.java)
        i.putExtra(LoginActivity.EXTRA_MESSAGE, message ?: "")
        startActivity(i)
        left = true
        finish()
        overridePendingTransition(0, 0)
    }

    private fun goMain() {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        left = true
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** 自动登录最长等这么久，超时就去登录页，不让人对着转圈干等 */
        private const val TIMEOUT_MS = 25_000L
    }
}
