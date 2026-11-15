package com.tstc.zstangshi

import android.graphics.Color
import android.graphics.Insets
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import com.tstc.zstangshi.data.Store
import com.tstc.zstangshi.pages.CalendarPage
import com.tstc.zstangshi.ui.BackGestures
import com.tstc.zstangshi.ui.HeaderBar
import com.tstc.zstangshi.ui.PageSlide

/**
 * 校历（从首页宫格进）。
 *
 * 底栏只有四个位置，校历这种「一周看一次」的东西放进去太占地方，
 * 所以它是个独立页面，返回就回到首页。
 */
class CalendarActivity : BaseActivity() {

    /** 整页的圆角卡片进出场（见 ui/PageSlide）。 */
    private var slide: PageSlide? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(pageColor())

        // 顶栏：与功能页 / WebView 页共用 HeaderBar，箭头与标题严格同一行高
        val bar = HeaderBar.create(this, "校历") { closePage() }

        root.addView(
            bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val page = CalendarPage(this, Store(this))
        root.addView(
            page, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        setContentView(root)
        applyInsets(root)
        window.statusBarColor = Color.TRANSPARENT

        // 整页圆角卡片进场：与系统预测性返回动画同一个圆角半径
        slide = PageSlide(this, pageColor())
        slide!!.enter()
        slide!!.attachBack()
    }

    private fun closePage() {
        if (slide != null) {
            slide!!.finish()
        } else {
            finish()
        }
    }

    /**
     * 13 起返回手势由系统接管（系统自己会把页面缩成圆角卡片退场），别叠动画；
     * 老系统才用我们自己的滑动出场。
     */
    override fun onBackPressed() {
        if (slide != null && !BackGestures.supported()) {
            slide!!.finish()
            return
        }
        super.onBackPressed()
    }

    private fun applyInsets(root: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars: Insets = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        } else {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            root.setPadding(0, if (id > 0) resources.getDimensionPixelSize(id) else 0, 0, 0)
        }
    }
}
