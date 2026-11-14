package com.tstc.zstangshi.ui

import android.content.Context
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * 横向排开的「页面带」：每个主页面占一个与屏幕等宽的槽位，槽位号就是底栏 tab 序号。
 *
 * 为什么不用 FrameLayout 叠着放：叠放只能让新旧两页各自平移，从首页直接点「我的」
 * 时中间两页根本不参演，看起来是两页擦过去。排成一条带之后，整体按
 * (槽位 - 当前页位置) × 屏宽 摆放，中途课表、成绩会真的依次掠过屏幕。
 *
 * 实现上所有子页都 layout 在同一块 [0,0,宽,高] 区域、只用 translationX 区分位置：
 * 容器的边界始终等于一屏，系统的裁剪与触摸命中都按真实位置换算，
 * 既不会被自己的边界裁掉，也不会把触摸派给屏幕外的页面。
 */
open class PageDeck(c: Context) : ViewGroup(c) {

    private val pages = SparseArray<View>()
    private var slotWidth = 0
    /** 当前「页位置」，可为小数（跟手拖动时介于两页之间）。 */
    private var position = 0f

    init {
        setClipChildren(true)
    }

    /** 槽位宽度 = 屏幕宽；尺寸变了（横竖屏）由调用方重新设一次。 */
    fun setSlotWidth(w: Int) {
        if (w <= 0 || w == slotWidth) return
        slotWidth = w
        applyOffsets()
        requestLayout()
    }

    fun slotWidth(): Int {
        return slotWidth
    }

    /** 把页面放到指定槽位（可乱序调用，摆放只认槽位号）。 */
    fun put(slot: Int, page: View) {
        val old = pages.get(slot)
        if (old === page) return
        if (old != null) {
            pages.remove(slot)
            removeView(old)
        }
        pages.put(slot, page)
        if (page.parent == null) {
            addView(page)
        }
        applyOffsets()
        requestLayout()
    }

    fun pageAt(slot: Int): View? {
        return pages.get(slot)
    }

    /** 立即把整条带挪到某个位置（可小数，用于跟手拖动）。 */
    fun jumpTo(pos: Float) {
        position = pos
        applyOffsets()
    }

    /** 平滑滚到某个位置（切 tab、手势结束时的收尾）。 */
    fun animateTo(pos: Float, duration: Long) {
        position = pos
        for (i in 0 until pages.size()) {
            val slot = pages.keyAt(i)
            val v = pages.valueAt(i)
            v.animate().cancel()
            v.animate().translationX((slot - pos) * slotWidth.toFloat())
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator(1.1f))
                .start()
        }
    }

    /** 打断正在跑的平移，原地停住。 */
    fun cancelAnimation() {
        for (i in 0 until pages.size()) {
            pages.valueAt(i).animate().cancel()
        }
    }

    fun position(): Float {
        return position
    }

    private fun applyOffsets() {
        for (i in 0 until pages.size()) {
            val slot = pages.keyAt(i)
            val v = pages.valueAt(i)
            v.animate().cancel()
            v.translationX = (slot - position) * slotWidth.toFloat()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val specW = View.MeasureSpec.getSize(widthSpec)
        val h = View.MeasureSpec.getSize(heightSpec)
        if (slotWidth <= 0) slotWidth = specW
        for (i in 0 until pages.size()) {
            val v = pages.valueAt(i)
            v.measure(
                View.MeasureSpec.makeMeasureSpec(slotWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(specW, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val h = b - t
        for (i in 0 until pages.size()) {
            val v = pages.valueAt(i)
            // 全都叠在同一块区域，位置差由 translationX 表达（见 applyOffsets）
            v.layout(0, 0, slotWidth, h)
        }
        // 这里不重设 translationX：布局可能由页面内部内容变化触发，
        // 若在此打断动画，切页会突然跳到终点。
    }
}
