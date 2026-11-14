package com.tstc.zstangshi.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import com.tstc.zstangshi.R
import com.tstc.zstangshi.bus.TransitCode

/**
 * 「打开哪个乘车码」选择层。外观直接复用 [Sheet]（遮罩 / 圆角 / 上滑进场 / 顶部横杠），
 * 行样式跟 [OptionSheet] 一样：圆角行、选中行品牌色淡底、右侧一个单选点。
 *
 * 只负责「问用户想用哪个」并回调，真正拉起 App 交给 [TransitCode.open]，
 * 记住选择交给调用方写 [com.tstc.zstangshi.bus.BusStore.setTransitCode]。
 */
object TransitCodeSheet {

    /**
     * @param defaultId 当前默认渠道（用于预选中）
     * @param onPick    (id, remember)，remember = 勾了「记住选择，下次直接打开」
     */
    @JvmStatic
    fun show(host: Activity, defaultId: String, onPick: (String, Boolean) -> Unit) {
        if (host.isFinishing) return
        val d = host.resources.displayMetrics.density
        val dp = Math.round(d)

        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp * 20, 0, dp * 20, dp * 14)

        // 标题
        val title = TextView(host)
        title.setText("打开哪个乘车码")
        title.setTextSize(15f)
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(host.getColor(R.color.text_primary))
        title.setPadding(dp * 2, dp * 4, 0, dp * 10)
        box.addView(title)

        // 记住选择的勾选状态：默认跟着当前默认渠道走（默认是「每次询问」就默认不勾）
        val remember = booleanArrayOf(defaultId != TransitCode.ASK)
        val handle = arrayOfNulls<Sheet.Handle>(1)

        // 三行渠道。内容固定不多，但小屏 + 大字号下仍可能超 60%，所以外面套 ScrollView
        val sc = ScrollView(host)
        sc.isVerticalScrollBarEnabled = false
        val list = LinearLayout(host)
        list.orientation = LinearLayout.VERTICAL
        sc.addView(
            list,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val ids = TransitCode.ids()
        for (id in ids) {
            val on = id == defaultId
            val ok = TransitCode.installed(host, id)

            val row = LinearLayout(host)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp * 14, dp * 12, dp * 14, dp * 12)
            row.background = rounded(
                host.getColor(if (on) R.color.brand_soft else R.color.bg_sunken),
                dp * 12
            )

            val col = LinearLayout(host)
            col.orientation = LinearLayout.VERTICAL

            val name = TextView(host)
            name.setText(TransitCode.label(id))
            name.setTextSize(15f)
            name.typeface = Typeface.DEFAULT_BOLD
            name.setTextColor(host.getColor(if (on) R.color.brand_on_soft else R.color.text_primary))
            col.addView(name)

            val memo = TextView(host)
            // 未安装的渠道照样列出来（用户可能想先知道有这条路），只在说明后面标注
            val text = TransitCode.desc(id) + if (ok) "" else "（未安装）"
            memo.setText(text)
            memo.setTextSize(12f)
            memo.setTextColor(host.getColor(R.color.text_secondary))
            val mlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            mlp.topMargin = dp * 3
            col.addView(memo, mlp)

            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            // 右侧单选圆点：选中 = 品牌色实心 + 白点，未选中 = 空心描边
            val dot = View(host)
            val dlp = LinearLayout.LayoutParams(dp * 20, dp * 20)
            dlp.leftMargin = dp * 10
            dot.background = radioDot(host, on, dp)
            row.addView(dot, dlp)

            row.isClickable = true
            pressable(row)
            row.setOnClickListener {
                if (!ok) {
                    // 没装就别关弹层 —— 用户可能想改选别的渠道
                    Toast.makeText(host, notInstalledMsg(id), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // 和 Trackr 一致：点选即走，不额外点「确定」
                handle[0]?.dismiss()
                onPick(id, remember[0])
            }

            val rlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rlp.bottomMargin = dp * 8
            list.addView(row, rlp)
        }

        box.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 微信渠道已下线：直接说明原因，别让用户以为是我们漏了
        val note = TextView(host)
        note.setText("由于微信限制，暂不提供微信乘车码跳转")
        note.setTextSize(12f)
        note.setTextColor(host.getColor(R.color.text_tertiary))
        note.background = rounded(host.getColor(R.color.bg_sunken), dp * 10)
        note.setPadding(dp * 12, dp * 9, dp * 12, dp * 9)
        val nlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        nlp.topMargin = dp * 2
        box.addView(note, nlp)

        // 底部「记住选择」：和登录页「记住密码」同一个控件（ui/Checks），
        // 原来那个自绘方块 +「✓」字符是另起一套，字号/圆角/颜色都对不上
        val rememberBox = Checks.create(host, "记住选择，下次直接打开", remember[0])
        rememberBox.setOnCheckedChangeListener { _, on -> remember[0] = on }
        val clp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        clp.topMargin = dp * 4
        box.addView(rememberBox, clp)

        // 一行极小提示：告诉用户这个默认值随时能改，别担心「记住了就改不回来」
        val tip = TextView(host)
        tip.setText("（可长按乘车码按钮随时切换）")
        tip.setTextSize(12f)
        tip.setTextColor(host.getColor(R.color.text_tertiary))
        tip.setPadding(dp * 2, dp * 8, 0, 0)
        box.addView(tip)

        // 高度估算（和 OptionSheet 一样按行数算，不去 measure）：标题 + 两行 + 微信说明 + 勾选行 + 提示
        val h = dp * (40 + ids.size * 71 + 44 + 52 + 30)
        val cap = Math.round(host.resources.displayMetrics.heightPixels * 0.6f)
        handle[0] = Sheet.show(host, box, Math.min(h, cap))
    }

    // ---------------- 小工具 ----------------

    /** 纯色圆角块。 */
    private fun rounded(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.setCornerRadius(radius.toFloat())
        return g
    }

    /** 单选圆点：选中态是「品牌色实心圆 + 中间白点」，未选中是空心描边。 */
    private fun radioDot(host: Activity, on: Boolean, dp: Int): LayerDrawable {
        val r = (dp * 20 / 2).toFloat()
        if (!on) {
            val g = GradientDrawable()
            g.shape = GradientDrawable.OVAL
            g.setColor(0x00000000)
            g.setStroke(Math.max(2, Math.round(dp * 1.5f)), host.getColor(R.color.text_tertiary))
            return LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(g))
        }
        val outer = GradientDrawable()
        outer.shape = GradientDrawable.OVAL
        outer.setColor(host.getColor(R.color.brand))

        val inner = GradientDrawable()
        inner.shape = GradientDrawable.OVAL
        inner.setColor(0xFFFFFFFF.toInt())

        val layer = LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(outer, inner))
        val inset = Math.round(r * 0.6f)
        layer.setLayerInset(1, inset, inset, inset, inset)
        return layer
    }

    /** 按下轻微缩小，抬手回弹。OnTouch 返回 false，点击事件仍照常走 OnClickListener。 */
    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.985f).scaleY(0.985f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(140)
                        .setInterpolator(DecelerateInterpolator(1.6f))
                        .start()
            }
            false
        }
    }

    private fun notInstalledMsg(id: String): String {
        return when (id) {
            TransitCode.ALIPAY -> "未安装支付宝，无法打开乘车码"
            TransitCode.UNIONPAY -> "未安装云闪付客户端，无法打开乘车码"
            else -> "该应用未安装"
        }
    }
}
