package com.zero.components.base.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.widget.TextViewCompat
import com.zero.components.R
import kotlin.math.max

/**
 * BabyCare 统一单选 Sheet。
 *
 * 低数量选项不再使用滚轮或描边选中块，避免局部高亮破坏列表整体感；业务页只提交最终确认值。
 */
internal class BabyCareChoicePopup<T>(
    context: Context,
    title: String,
    private val items: List<DialogHelper.PickerOption<T>>,
    initialIndex: Int,
    private val onSelected: (T) -> Unit,
    private val onCancel: (() -> Unit)? = null
) : BabyCareBottomSheetPopup(context) {

    private var selectedIndex: Int = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    private lateinit var contentTouchArea: View

    override fun getImplLayoutId(): Int = R.layout.popup_babycare_choice_sheet

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::contentTouchArea.isInitialized) {
            protectSheetDragForContentTouch(event, contentTouchArea)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate() {
        super.onCreate()
        contentTouchArea = findViewById(R.id.option_container)
        findViewById<TextView>(R.id.tv_cancel).setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }
        findViewById<TextView>(R.id.tv_confirm).setOnClickListener {
            items.getOrNull(selectedIndex)?.value?.let(onSelected)
            dismiss()
        }
        renderOptions()
    }

    private fun renderOptions() {
        val container = findViewById<LinearLayout>(R.id.option_container)
        container.removeAllViews()
        items.forEachIndexed { index, item ->
            container.addView(createOptionRow(index, item, index == items.lastIndex))
        }
    }

    private fun createOptionRow(index: Int, item: DialogHelper.PickerOption<T>, isLast: Boolean): View {
        val isSelected = index == selectedIndex
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val row = FrameLayout(context).apply {
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            foreground = context.getThemeDrawable(android.R.attr.selectableItemBackground)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
            setOnClickListener {
                if (selectedIndex == index) return@setOnClickListener
                selectedIndex = index
                renderOptions()
            }
        }

        row.addView(TextView(context).apply {
            text = item.label
            gravity = Gravity.CENTER
            includeFontPadding = false
            TextViewCompat.setTextAppearance(this, com.zero.common.R.style.TextAppearance_BabyCare_Body1)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CHOICE_ITEM_TEXT_SIZE_SP)
            setTextColor(context.resolveThemeColor(com.zero.common.R.attr.colorTextPrimary))
            typeface = Typeface.create(
                if (isSelected) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })

        row.addView(AppCompatImageView(context).apply {
            visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            setImageResource(com.zero.common.R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(context.resolveThemeColor(com.zero.common.R.attr.colorBrand))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = null
            layoutParams = FrameLayout.LayoutParams(
                dp(24),
                dp(24),
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        })
        wrapper.addView(row)
        if (!isLast) {
            wrapper.addView(View(context).apply {
                setBackgroundColor(context.getColor(com.zero.common.R.color.control_border_default))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    max(1, resources.getDimensionPixelSize(com.zero.common.R.dimen.surface_stroke_width))
                ).apply {
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        return wrapper
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val CHOICE_ITEM_TEXT_SIZE_SP = 15f
    }
}

internal fun Context.resolveThemeColor(attr: Int): Int {
    val typedValue = TypedValue()
    theme.resolveAttribute(attr, typedValue, true)
    return if (typedValue.resourceId != 0) getColor(typedValue.resourceId) else typedValue.data
}

private fun Context.getThemeDrawable(attr: Int) = TypedValue().let { typedValue ->
    theme.resolveAttribute(attr, typedValue, true)
    getDrawable(typedValue.resourceId)
}
