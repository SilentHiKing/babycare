package com.zero.components.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.RadioGroup
import kotlin.math.max

/** 单选项按真实文字宽度换行，保留 RadioGroup 的互斥选择和无障碍语义。 */
class WrappingRadioGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RadioGroup(context, attrs) {
    private val itemBounds = mutableMapOf<View, Rect>()
    private val gap: Int
        get() = resources.getDimensionPixelSize(com.zero.common.R.dimen.record_time_field_gap)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val available = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 2
        else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val rows = mutableListOf<MutableList<View>>()
        var row = mutableListOf<View>()
        var rowWidth = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val nextWidth = child.measuredWidth + if (row.isEmpty()) 0 else gap
            if (row.isNotEmpty() && rowWidth + nextWidth > available) {
                rows.add(row)
                row = mutableListOf()
                rowWidth = 0
            }
            if (row.isNotEmpty()) rowWidth += gap
            row.add(child)
            rowWidth += child.measuredWidth
        }
        if (row.isNotEmpty()) rows.add(row)

        val naturalWidth = rows.maxOfOrNull { items ->
            items.sumOf { it.measuredWidth } + gap * (items.size - 1)
        } ?: 0
        val measuredWidth = resolveSize(naturalWidth + paddingLeft + paddingRight, widthMeasureSpec)
        val contentWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        var top = paddingTop
        itemBounds.clear()
        rows.forEach { items ->
            // 同一行平分余量，按钮保持整齐；大字号增加行数和行高，不能压缩文字。
            val extra = (contentWidth - items.sumOf { it.measuredWidth } - gap * (items.size - 1))
                .coerceAtLeast(0)
            items.forEachIndexed { index, child ->
                val childWidth = child.measuredWidth + extra / items.size + if (index < extra % items.size) 1 else 0
                child.measure(
                    MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
            }
            val height = items.maxOf { it.measuredHeight }
            var start = paddingLeft
            items.forEach { child ->
                child.measure(
                    MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                )
                itemBounds[child] = Rect(start, top, start + child.measuredWidth, top + height)
                start += child.measuredWidth + gap
            }
            top += height + gap
        }
        val contentHeight = top - if (rows.isEmpty()) 0 else gap
        setMeasuredDimension(measuredWidth, resolveSize(max(suggestedMinimumHeight, contentHeight + paddingBottom), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        itemBounds.forEach { (child, bounds) ->
            if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                child.layout(width - bounds.right, bounds.top, width - bounds.left, bounds.bottom)
            } else {
                child.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        itemBounds.remove(child)
    }
}
