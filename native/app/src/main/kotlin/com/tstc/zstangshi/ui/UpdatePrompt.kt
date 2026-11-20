package com.tstc.zstangshi.ui

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

import com.tstc.zstangshi.R
import com.tstc.zstangshi.update.AutoCheck
import com.tstc.zstangshi.update.Updater

/**
 * 启动时自动检查到新版本后的提醒弹层。
 *
 * 和「我的 → 关于 → 检查更新」共用同一套底部弹层与按钮，只是更短：
 * 版本号、日期、体积、几行更新说明，加三个出口 —— 立即更新 / 稍后 / 忽略此版本。
 *
 * 「忽略此版本」记的是**版本号**而不是「关掉自动检查」：自动检查照跑，
 * 只是同一个（或更低的）版本不再打扰，等更高的版本发布再提醒。
 */
object UpdatePrompt {

    @JvmStatic
    fun show(host: Activity?, r: Updater.Release) {
        if (host == null || host.isFinishing || host.isDestroyed) return

        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(d(host, 20), 0, d(host, 20), d(host, 12))

        // ── 标题：发现新版本 + 版本号（版本号用品牌色，一眼看到「新在哪」）──
        val head = LinearLayout(host)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(d(host, 2), d(host, 4), 0, d(host, 4))

        val title = TextView(host)
        title.text = "发现新版本"
        title.setTextSize(16f)
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(host.getColor(R.color.text_primary))
        head.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val ver = TextView(host)
        ver.text = "v" + r.version
        ver.setTextSize(16f)
        ver.typeface = Typeface.DEFAULT_BOLD
        ver.setTextColor(host.getColor(R.color.brand))
        val vlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        vlp.leftMargin = d(host, 8)
        head.addView(ver, vlp)
        box.addView(head)

        // ── 一行元信息：当前版本 · 日期 · 体积 ──
        val bits = ArrayList<String>()
        bits.add("当前 " + currentVersion(host))
        val date = Updater.dateText(r.publishedAt)
        if (date.isNotEmpty()) bits.add(date)
        val size = Updater.sizeText(r.size)
        if (size.isNotEmpty()) bits.add(size)

        val meta = TextView(host)
        meta.text = bits.joinToString(" · ")
        meta.setTextSize(12f)
        meta.setTextColor(host.getColor(R.color.text_tertiary))
        meta.setPadding(d(host, 2), 0, 0, d(host, 8))
        box.addView(meta)

        // ── 更新说明只露前几行，全文进「立即更新」那一层看 ──
        val notes = TextView(host)
        notes.text = UpdateSheet.plainNotes(r.notes)
        notes.setTextSize(12.5f)
        notes.setTextColor(host.getColor(R.color.text_secondary))
        notes.setLineSpacing(d(host, 3).toFloat(), 1f)
        notes.maxLines = 6
        notes.ellipsize = TextUtils.TruncateAt.END
        notes.setPadding(d(host, 2), 0, d(host, 2), d(host, 12))
        box.addView(notes)

        val handle = arrayOfNulls<Sheet.Handle>(1)

        // 主按钮在上：先看到「要做什么」，再看到「算了」
        box.addView(action(host, "立即更新", true) {
            handle[0]?.dismiss()
            // 等这个弹层往下退的 200ms 走完再开更新页，避免两个弹层叠在一起闪一下
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                UpdateSheet.showFor(host, null, r)
            }, 200L)
        })

        // 两个次要出口并排：稍后（这次不装）/ 忽略此版本（这个版本别再提醒）
        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        val rlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = d(host, 8)
        box.addView(row, rlp)

        val later = action(host, "稍后", false) { handle[0]?.dismiss() }
        val llp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(later, llp)

        val ignore = action(host, "忽略此版本", false) {
            AutoCheck.ignore(host, r.version)
            handle[0]?.dismiss()
        }
        val ilp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        ilp.leftMargin = d(host, 8)
        row.addView(ignore, ilp)

        // 高度按内容量：这个弹层不该占满一屏
        val width = host.resources.displayMetrics.widthPixels
        box.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        handle[0] = Sheet.show(host, box, box.measuredHeight + d(host, 18))
    }

    /** 一个圆角按钮：主按钮实色白字，次按钮描边灰字。 */
    private fun action(host: Activity, text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val v = TextView(host)
        v.text = text
        v.setTextSize(14.5f)
        v.gravity = Gravity.CENTER
        v.setPadding(d(host, 14), d(host, 13), d(host, 14), d(host, 13))
        if (primary) {
            v.typeface = Typeface.DEFAULT_BOLD
            v.setTextColor(0xFFFFFFFF.toInt())
            v.background = host.getDrawable(R.drawable.btn_primary)
        } else {
            v.setTextColor(host.getColor(R.color.text_secondary))
            v.background = host.getDrawable(R.drawable.btn_outline)
        }
        v.isClickable = true
        v.setOnClickListener { onClick() }
        return v
    }

    private fun currentVersion(host: Activity): String {
        return try {
            host.packageManager.getPackageInfo(host.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun d(host: Activity, v: Int): Int {
        return Math.round(v * host.resources.displayMetrics.density)
    }
}
