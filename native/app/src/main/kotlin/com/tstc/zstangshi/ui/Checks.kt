package com.tstc.zstangshi.ui

import android.content.Context
import android.content.res.ColorStateList
import android.widget.CheckBox

import com.tstc.zstangshi.R

/**
 * 全 App 统一的勾选框。
 *
 * 登录页的「记住密码 / 自动登录」和乘车码弹层的「记住选择」是同一类控件，
 * 之前乘车码那边是自绘方块 +「✓」字符，圆角、字号、描边、勾中颜色全是另起一套，
 * 放到一起明显不是一个东西。这里收成一个工厂：谁要勾选框都从这儿拿，
 * 颜色取品牌色（勾中）/ text_tertiary（未勾中），字 13sp、text_secondary，
 * 和登录页原来那套逐字一样。
 */
object Checks {

    /** 新建一个勾选框；[on] 是初始勾选态。 */
    @JvmStatic
    fun create(c: Context, label: String, on: Boolean): CheckBox {
        val b = CheckBox(c)
        b.text = label
        b.textSize = 13f
        b.setTextColor(c.getColor(R.color.text_secondary))
        b.isChecked = on
        b.buttonTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(c.getColor(R.color.brand), c.getColor(R.color.text_tertiary))
        )
        return b
    }
}
