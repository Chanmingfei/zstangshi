package com.tstc.zstangshi.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

import com.tstc.zstangshi.R

/**
 * 应用内的确认弹窗。走的是和 [OptionSheet] 同一套底部弹层（[Sheet]）：
 * 圆角卡片从下面滑上来、顶部有那条小横杠，按钮是圆角行，不用系统的白底 AlertDialog。
 */
object ConfirmDialog {

    /** 两个按钮：取消 / 确认。onOk 在主线程回调。 */
    @JvmStatic
    fun show(a: Activity?, title: String?, message: String?,
             cancelText: String?, okText: String?, onOk: Runnable?) {
        if (a == null || a.isFinishing) return
        val dp = Math.round(a.resources.displayMetrics.density)

        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp * 20, 0, dp * 20, dp * 12)

        val t = TextView(a)
        t.setText(title)
        t.setTextSize(15f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(a.getColor(R.color.text_primary))
        t.setPadding(dp * 2, dp * 4, 0, dp * 10)
        box.addView(t)

        if (message != null && message.isNotEmpty()) {
            val m = TextView(a)
            m.setText(message)
            m.setTextSize(13f)
            m.setTextColor(a.getColor(R.color.text_secondary))
            m.setLineSpacing((dp * 3).toFloat(), 1f)
            m.setPadding(dp * 2, 0, dp * 2, dp * 14)
            box.addView(m)
        }

        val handle = arrayOfNulls<Sheet.Handle>(1)
        // 主按钮在上，和弹层里「先看到要做什么、再看到算了」的顺序一致
        box.addView(row(a, okText, getColorSafe(a, R.color.brand), 0xFFFFFFFF.toInt(), true) {
            handle[0]?.dismiss()
            onOk?.run()
        })
        if (cancelText != null) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp * 8
            box.addView(row(a, cancelText, getColorSafe(a, R.color.bg_sunken),
                getColorSafe(a, R.color.text_secondary), false) {
                handle[0]?.dismiss()
            }, lp)
        }

        val width = a.resources.displayMetrics.widthPixels
        box.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        handle[0] = Sheet.show(a, box, box.measuredHeight + dp * 18)
    }

    /** 只有一个「知道了」的提示框。 */
    @JvmStatic
    fun alert(a: Activity?, title: String?, message: String?) {
        show(a, title, message, null, "知道了", null)
    }

    private fun getColorSafe(a: Activity, res: Int): Int {
        return a.getColor(res)
    }

    private fun row(a: Activity, text: String?, bg: Int, fg: Int,
                    bold: Boolean, onClick: Runnable): TextView {
        val dp = Math.round(a.resources.displayMetrics.density)
        val v = TextView(a)
        v.setText(text)
        v.setTextSize(14.5f)
        v.gravity = Gravity.CENTER
        v.setTextColor(fg)
        v.setPadding(dp * 14, dp * 13, dp * 14, dp * 13)
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        val g = GradientDrawable()
        g.setColor(bg)
        g.setCornerRadius((dp * 12).toFloat())
        v.background = g
        v.isClickable = true
        v.setOnClickListener { onClick.run() }
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return v
    }
}
