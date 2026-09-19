package com.zero.components.widget

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import kotlin.math.roundToInt

/** 主文案与尾部值/控件的两项布局；窄窗改为上下排列，空值不占行，不修改业务状态。 */
class AdaptiveTrailingLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private var stacked = false
    private var showTrailing = false
    private val gap get() = resources.getDimensionPixelSize(com.zero.common.R.dimen.content_gap_medium)
    private var observedValue: TextView? = null
    private val valueWatcher = object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(text: Editable?) {
            // TextView 同行高更新可能只重绘；尾值宽度和空/非空状态仍会改变父容器排布。
            requestLayout()
        }
    }

    private fun observeValue() {
        val value = (getChildAt(1) as? TextView)?.takeUnless { it is CompoundButton }
        if (observedValue === value) return
        observedValue?.removeTextChangedListener(valueWatcher)
        observedValue = value
        value?.addTextChangedListener(valueWatcher)
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        observeValue()
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        observeValue()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observeValue()
    }

    override fun onDetachedFromWindow() {
        observedValue?.removeTextChangedListener(valueWatcher)
        observedValue = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val content = getChildAt(0)
        val trailing = getChildAt(1)
        if (content == null || trailing == null) {
            setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec),
                resolveSize(suggestedMinimumHeight, heightMeasureSpec))
            return
        }
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val naturalWidth = if (bounded) MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)
            else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val childHeight = getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, LayoutParams.WRAP_CONTENT)
        content.measure(naturalWidth, childHeight)
        // Switch 等 CompoundButton 本身也继承 TextView；无文字不代表没有可操作内容。
        val emptyValue = trailing is TextView && trailing !is CompoundButton && trailing.text.isNullOrEmpty()
        showTrailing = trailing.visibility != GONE && !emptyValue
        if (showTrailing) trailing.measure(naturalWidth, childHeight)
        else trailing.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY))

        val trailingWidth = if (showTrailing) trailing.measuredWidth else 0
        val spacing = if (showTrailing) gap else 0
        val desiredWidth = content.measuredWidth + trailingWidth + spacing + paddingLeft + paddingRight
        val width = resolveSize(maxOf(suggestedMinimumWidth, desiredWidth), widthMeasureSpec)
        val innerWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        // 至少保留约一段短标签的阅读宽度；字号变大时同步增加，而不是把文字挤成窄列。
        val readableWidth = (resources.getDimension(com.zero.common.R.dimen.adaptive_trailing_content_min_width) *
            resources.configuration.fontScale.coerceAtLeast(1f)).roundToInt()
        stacked = showTrailing && innerWidth - trailingWidth - spacing < minOf(content.measuredWidth, readableWidth)
        val contentWidth = if (stacked) innerWidth else (innerWidth - trailingWidth - spacing).coerceAtLeast(0)
        content.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY), childHeight)
        if (stacked) {
            // 值可占整行换行；开关等固有尺寸控件保留自身宽度，在 onLayout 中靠 end 放置。
            val mode = if (trailing.layoutParams.width == LayoutParams.MATCH_PARENT) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST
            trailing.measure(MeasureSpec.makeMeasureSpec(innerWidth, mode), childHeight)
        }
        val contentHeight = if (stacked) content.measuredHeight + spacing + trailing.measuredHeight
            else maxOf(content.measuredHeight, if (showTrailing) trailing.measuredHeight else 0)
        setMeasuredDimension(width, resolveSize(maxOf(suggestedMinimumHeight,
            contentHeight + paddingTop + paddingBottom), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val content = getChildAt(0) ?: return
        val trailing = getChildAt(1) ?: return
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val innerHeight = height - paddingTop - paddingBottom
        fun place(child: View, atEnd: Boolean, y: Int) {
            val x = if (atEnd != rtl) width - paddingRight - child.measuredWidth else paddingLeft
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        place(content, false, if (stacked) paddingTop else paddingTop + (innerHeight - content.measuredHeight) / 2)
        if (showTrailing) {
            place(trailing, true, if (stacked) content.bottom + gap
                else paddingTop + (innerHeight - trailing.measuredHeight) / 2)
        } else {
            trailing.layout(0, 0, 0, 0)
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet) = LayoutParams(context, attrs)
    override fun generateLayoutParams(params: LayoutParams) = LayoutParams(params)
    override fun checkLayoutParams(params: LayoutParams?) = params != null
}
