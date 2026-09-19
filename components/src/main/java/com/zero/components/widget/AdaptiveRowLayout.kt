package com.zero.components.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/** 成对的信息块在窄窗或大字号下改为纵排，保留各块完整文字和正常字号。 */
class AdaptiveRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val originalParams = mutableMapOf<View, LayoutParams>()
    private var stacked: Boolean? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val items = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val minimumWidth = 120f * resources.displayMetrics.density * resources.configuration.fontScale.coerceAtLeast(1f)
        val stack = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && available < minimumWidth * items.size
        if (stacked != stack || items.any { it !in originalParams }) {
            stacked = stack
            orientation = if (stack) VERTICAL else HORIZONTAL
            val gap = resources.getDimensionPixelSize(com.zero.common.R.dimen.record_time_field_gap)
            items.forEachIndexed { index, child ->
                val original = originalParams.getOrPut(child) { LayoutParams(child.layoutParams as LayoutParams) }
                child.layoutParams = LayoutParams(original).apply {
                    if (stack) {
                        width = LayoutParams.MATCH_PARENT
                        weight = 0f
                        marginStart = 0
                        marginEnd = 0
                        topMargin = if (index == 0) 0 else gap
                    }
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        originalParams.remove(child)
    }
}
