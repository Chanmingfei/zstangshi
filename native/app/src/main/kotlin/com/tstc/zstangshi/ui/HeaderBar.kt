package com.tstc.zstangshi.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

import com.tstc.zstangshi.R

/**
 * 二级页统一顶栏。
 *
 * 之前三个页面各自用 TextView 打「‹」：字号 26/28sp 的字体自带 ascent/descent 留白，
 * 箭头在 42~48dp 的方格里视觉上偏低偏左，标题又是另一套行高，于是「返回按钮和文字对不齐」。
 * 这里把箭头改成 Canvas 画的路径（几何居中，不受字体度量影响），并让箭头、「返回」、
 * 标题、右侧按钮共用同一行高 + includeFontPadding(false)，三个页面完全一致。
 */
object HeaderBar {

    /** 生成一行顶栏；右侧按钮用 [action] 生成后 addView 追加即可。 */
    @JvmStatic
    fun create(host: Activity, title: String?, onBack: View.OnClickListener?): LinearLayout {
        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(host, 8f), dp(host, 6f), dp(host, 12f), dp(host, 6f))
        row.minimumHeight = dp(host, 52f)

        val back = LinearLayout(host)
        back.orientation = LinearLayout.HORIZONTAL
        back.gravity = Gravity.CENTER_VERTICAL
        back.setPadding(dp(host, 6f), 0, dp(host, 10f), 0)
        back.setOnClickListener(onBack)
        back.addView(
            Chevron(host, host.getColor(R.color.brand)),
            LinearLayout.LayoutParams(dp(host, 15f), dp(host, 22f))
        )

        val label = TextView(host)
        label.setText("返回")
        label.setTextSize(13f)
        label.includeFontPadding = false
        label.gravity = Gravity.CENTER_VERTICAL
        label.setTextColor(host.getColor(R.color.brand))
        label.setPadding(dp(host, 5f), 0, 0, 0)
        back.addView(
            label,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(host, 40f))
        )
        row.addView(
            back,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(host, 40f))
        )

        val t = TextView(host)
        t.setText(title)
        t.setTextSize(17f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.includeFontPadding = false
        t.gravity = Gravity.CENTER_VERTICAL
        t.setTextColor(host.getColor(R.color.text_primary))
        t.setPadding(dp(host, 4f), 0, 0, 0)
        row.addView(t, LinearLayout.LayoutParams(0, dp(host, 40f), 1f))
        return row
    }

    /** 顶栏右侧的文字按钮（刷新等），和标题共用行高。 */
    @JvmStatic
    fun action(host: Activity, text: String?, onClick: View.OnClickListener?): TextView {
        val tv = TextView(host)
        tv.setText(text)
        tv.setTextSize(13f)
        tv.includeFontPadding = false
        tv.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        tv.setTextColor(host.getColor(R.color.brand))
        tv.setPadding(dp(host, 12f), 0, dp(host, 4f), 0)
        tv.minWidth = dp(host, 44f)
        tv.setOnClickListener(onClick)
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(host, 40f)
        )
        return tv
    }

    /**
     * 顶栏右侧「文字 + 下拉小三角」按钮（数据源切换）。
     *
     * <p>不用「文字 ▾」这种写法：▾ 是字体字符，挂在基线上、沉到文字底部，
     * 视觉上整个按钮像坠下去了一样和旁边的文字按钮对不齐。
     * 三角用几何绘制、和文字垂直居中，TextView 是第一个子 View，改文字直接对它 setText。
     */
    @JvmStatic
    fun actionWithCaret(host: Activity, text: String?,
                        onClick: View.OnClickListener?): LinearLayout {
        val box = LinearLayout(host)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(dp(host, 12f), 0, dp(host, 8f), 0)
        box.minimumWidth = dp(host, 44f)
        box.setOnClickListener(onClick)

        val tv = TextView(host)
        tv.setText(text)
        tv.setTextSize(13f)
        tv.includeFontPadding = false
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setTextColor(host.getColor(R.color.brand))
        box.addView(
            tv,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(host, 40f))
        )

        val cp = LinearLayout.LayoutParams(dp(host, 7f), dp(host, 4f))
        cp.leftMargin = dp(host, 4f)
        box.addView(Caret(host, host.getColor(R.color.brand)), cp)
        box.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(host, 40f)
        )
        return box
    }

    /** 正文里的「‹ 重新筛选」之类次级返回链接，同样用绘制的箭头保证对齐。 */
    @JvmStatic
    fun backLink(host: Activity, text: String?, onClick: View.OnClickListener?): LinearLayout {
        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(host, 2f), 0, dp(host, 12f))
        row.setOnClickListener(onClick)
        row.addView(
            Chevron(host, host.getColor(R.color.brand)),
            LinearLayout.LayoutParams(dp(host, 9f), dp(host, 14f))
        )

        val tv = TextView(host)
        tv.setText(text)
        tv.setTextSize(13f)
        tv.includeFontPadding = false
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setTextColor(host.getColor(R.color.brand))
        tv.setPadding(dp(host, 6f), 0, 0, 0)
        row.addView(
            tv,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(host, 22f))
        )
        return row
    }

    private fun dp(c: Context, v: Float): Int {
        return Math.round(v * c.resources.displayMetrics.density)
    }

    /** 纯几何的「‹」，不受字体 ascent/descent 影响，永远在 View 正中。 */
    private class Chevron(c: Context, color: Int) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        init {
            paint.setColor(color)
            paint.setStyle(Paint.Style.STROKE)
            paint.setStrokeWidth(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    2f, c.resources.displayMetrics
                )
            )
            paint.setStrokeCap(Paint.Cap.ROUND)
            paint.setStrokeJoin(Paint.Join.ROUND)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val arm = Math.min(width, height) * 0.30f
            path.reset()
            path.moveTo(cx + arm * 0.62f, cy - arm)
            path.lineTo(cx - arm * 0.62f, cy)
            path.lineTo(cx + arm * 0.62f, cy + arm)
            canvas.drawPath(path, paint)
        }
    }

    /** 纯几何的实心「▾」，和文字垂直居中，不受字体基线影响。 */
    private class Caret(c: Context, color: Int) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        init {
            paint.setColor(color)
            paint.setStyle(Paint.Style.FILL)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            path.reset()
            path.moveTo(0f, 0f)
            path.lineTo(w, 0f)
            path.lineTo(w / 2f, h * 0.9f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }
}
