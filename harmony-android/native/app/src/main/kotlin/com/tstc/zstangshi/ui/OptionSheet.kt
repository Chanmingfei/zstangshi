package com.tstc.zstangshi.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import com.tstc.zstangshi.R

import java.util.Locale

/**
 * 统一样式的选择列表（替代系统的 AlertDialog.setItems）。
 *
 * 为什么不用系统的：黑底白字的单选弹窗跟这套浅色卡片完全不搭，圆角、行高、选中态
 * 都控制不了。这里换成底部弹层里的一列圆角行，选中的那行是品牌色淡底 + 右侧对勾。
 */
object OptionSheet {

    fun interface OnPick {
        fun onPick(index: Int)
    }

    /** 超过这个数量就带搜索框，避免一次建两千多个 View（教务班级字典 2198 项）。 */
    private const val SEARCH_FROM = 60
    private const val MAX_ROWS = 120

    @JvmStatic
    fun show(host: Activity, title: String?, options: Array<String>, selected: Int, pick: OnPick?) {
        val d = host.resources.displayMetrics.density
        val dp = Math.round(d)

        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp * 20, 0, dp * 20, dp * 16)

        val t = TextView(host)
        t.setText(title)
        t.setTextSize(15f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(host.getColor(R.color.text_primary))
        t.setPadding(dp * 2, dp * 4, 0, dp * 10)
        box.addView(t)

        val searchable = options.size > SEARCH_FROM
        val input = EditText(host)
        if (searchable) {
            input.hint = "输入关键字筛选（共 " + options.size + " 项）"
            input.setTextSize(13f)
            input.isSingleLine = true
            input.setTextColor(host.getColor(R.color.text_primary))
            input.setHintTextColor(host.getColor(R.color.text_tertiary))
            input.setPadding(dp * 12, dp * 10, dp * 12, dp * 10)
            val ig = GradientDrawable()
            ig.setColor(host.getColor(R.color.bg_sunken))
            ig.setCornerRadius((dp * 10).toFloat())
            input.background = ig
            val ilp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            ilp.bottomMargin = dp * 10
            box.addView(input, ilp)
        }

        val sc = ScrollView(host)
        val list = LinearLayout(host)
        list.orientation = LinearLayout.VERTICAL
        sc.addView(list)

        val handle = arrayOfNulls<Sheet.Handle>(1)
        val fill = Runnable {
            list.removeAllViews()
            val q = if (searchable) input.text.toString().trim().lowercase(Locale.ROOT) else ""
            var shown = 0
            for (i in options.indices) {
                if (shown >= MAX_ROWS) break
                if (q.isNotEmpty() && !options[i].lowercase(Locale.ROOT).contains(q)) continue
                val on = i == selected
                val row = LinearLayout(host)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp * 14, dp * 13, dp * 14, dp * 13)

                val g = GradientDrawable()
                g.setColor(host.getColor(if (on) R.color.brand_soft else R.color.bg_sunken))
                g.setCornerRadius((dp * 12).toFloat())
                row.background = g

                val label = TextView(host)
                label.setText(options[i])
                label.setTextSize(14.5f)
                label.setTextColor(host.getColor(if (on) R.color.brand_on_soft else R.color.text_primary))
                if (on) label.typeface = Typeface.DEFAULT_BOLD
                row.addView(
                    label,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )

                if (on) {
                    val tick = TextView(host)
                    tick.setText("✓")
                    tick.setTextSize(14f)
                    tick.typeface = Typeface.DEFAULT_BOLD
                    tick.setTextColor(host.getColor(R.color.brand))
                    row.addView(tick)
                }

                val idx = i
                row.setOnClickListener {
                    handle[0]?.dismiss()
                    pick?.onPick(idx)
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp * 8
                list.addView(row, lp)
                shown++
            }
            if (shown == 0 || (searchable && q.isEmpty() && options.size > MAX_ROWS)) {
                val hint = TextView(host)
                hint.setText(
                    if (shown == 0) "没有匹配项，换个关键字试试"
                    else "仅列出前 " + MAX_ROWS + " 项，输入关键字可缩小范围"
                )
                hint.setTextSize(12f)
                hint.gravity = Gravity.CENTER
                hint.setTextColor(host.getColor(R.color.text_tertiary))
                hint.setPadding(0, dp * 10, 0, dp * 10)
                list.addView(hint)
            }
        }
        if (searchable) {
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    fill.run()
                }
            })
        }
        fill.run()

        box.addView(
            sc,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val rows = Math.min(options.size, 7)
        // 有的选项自带「\n」当说明（比如数据源切换的两句话），高度得按实际行数给，
        // 不然第二行被压在方块外。每个额外行约 22dp；单行选项不受影响
        var extraLines = 0
        for (i in 0 until rows) {
            extraLines += options[i].count { ch -> ch == '\n' }
        }
        val h = dp * (74 + rows * 56 + extraLines * 22 + (if (searchable) 62 else 0))
        val cap = Math.round(host.resources.displayMetrics.heightPixels * 0.7f)
        handle[0] = Sheet.show(host, box, Math.min(h, cap))
    }

    /** 选项多的时候只要标题 + 一列，高度交给 Sheet 自己收。 */
    @JvmStatic
    fun wrapHeight(host: Activity, rows: Int): Int {
        val d = host.resources.displayMetrics.density
        return Math.round((74 + rows * 56) * d)
    }

    /**
     * 二次确认也用同一套皮肤（系统的 AlertDialog 在这套浅色界面里太出戏）。
     *
     * @param danger true 时确定按钮是红底，删除类操作用
     */
    @JvmStatic
    fun confirm(host: Activity, title: String?, message: String?,
                positive: String?, danger: Boolean, onYes: Runnable?) {
        val dp = Math.round(host.resources.displayMetrics.density)

        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp * 22, 0, dp * 22, dp * 18)

        val t = TextView(host)
        t.setText(title)
        t.setTextSize(16f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(host.getColor(R.color.text_primary))
        t.setPadding(dp * 2, dp * 4, 0, dp * 8)
        box.addView(t)

        val m = TextView(host)
        m.setText(message)
        m.setTextSize(13f)
        m.setLineSpacing((dp * 4).toFloat(), 1f)
        m.setTextColor(host.getColor(R.color.text_secondary))
        m.setPadding(dp * 2, 0, 0, dp * 20)
        box.addView(m)

        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL

        val handle = arrayOfNulls<Sheet.Handle>(1)

        val cancel = TextView(host)
        cancel.setText("取消")
        cancel.setTextSize(14f)
        cancel.gravity = Gravity.CENTER
        cancel.setTextColor(host.getColor(R.color.text_secondary))
        val cg = GradientDrawable()
        cg.setColor(host.getColor(R.color.bg_sunken))
        cg.setCornerRadius((dp * 12).toFloat())
        cancel.background = cg
        cancel.setOnClickListener {
            handle[0]?.dismiss()
        }
        row.addView(cancel, LinearLayout.LayoutParams(0, dp * 46, 1f))

        val ok = TextView(host)
        ok.setText(positive)
        ok.setTextSize(14f)
        ok.typeface = Typeface.DEFAULT_BOLD
        ok.gravity = Gravity.CENTER
        ok.setTextColor(0xFFFFFFFF.toInt())
        val og = GradientDrawable()
        og.setColor(if (danger) 0xFFE0533F.toInt() else host.getColor(R.color.brand))
        og.setCornerRadius((dp * 12).toFloat())
        ok.background = og
        ok.setOnClickListener {
            handle[0]?.dismiss()
            onYes?.run()
        }
        val okLp = LinearLayout.LayoutParams(0, dp * 46, 1f)
        okLp.leftMargin = dp * 10
        row.addView(ok, okLp)

        box.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        handle[0] = Sheet.show(host, box, dp * 182)
    }
}
