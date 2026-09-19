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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.zero.components.R
import kotlin.math.max

/**
 * BabyCare 统一单选 Sheet。
 *
 * 低数量选项不再使用滚轮或描边选中块，避免局部高亮破坏列表整体感；业务页只提交最终确认值。
 */
internal class BabyCareChoicePopup<T>(
    context: Context,
    private val title: String,
    private val items: List<DialogHelper.PickerOption<T>>,
    initialIndex: Int,
    private val onSelected: (T) -> Unit,
    private val onCancel: (() -> Unit)? = null
) : BabyCareBottomSheetPopup(context) {

    private var selectedIndex: Int = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    private lateinit var contentTouchArea: View

    override fun getImplLayoutId(): Int = R.layout.popup_babycare_choice_sheet

    override fun getMaxHeight(): Int {
        // 按宿主实际窗口扣除系统安全区，横屏/分屏不能用整张选项列表撑高底部弹层。
        val decor = activity?.window?.decorView
        val windowHeight = decor?.height?.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val insets = decor?.let(ViewCompat::getRootWindowInsets)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return (windowHeight - (insets?.top ?: 0) - (insets?.bottom ?: 0)).coerceAtLeast(1)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::contentTouchArea.isInitialized) {
            protectSheetDragForContentTouch(event, contentTouchArea)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate() {
        super.onCreate()
        contentTouchArea = findViewById(R.id.choice_scroll)
        ViewCompat.setAccessibilityPaneTitle(findViewById(R.id.option_container), title)
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
        val horizontalPadding = resources.getDimensionPixelSize(com.zero.common.R.dimen.page_horizontal_padding)
        val gap = resources.getDimensionPixelSize(com.zero.common.R.dimen.content_gap_medium)
        val iconSize = resources.getDimensionPixelSize(com.zero.common.R.dimen.icon_size_standard)
        val touchHeight = resources.getDimensionPixelSize(com.zero.common.R.dimen.min_touch_target)
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val row = FrameLayout(context).apply {
            minimumHeight = touchHeight + gap
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            isClickable = true
            isFocusable = true
            this.isSelected = isSelected
            foreground = context.getThemeDrawable(android.R.attr.selectableItemBackground)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (selectedIndex == index) return@setOnClickListener
                selectedIndex = index
                // 保留原节点，读屏焦点不会因重建整张选项列表而跳回页首。
                refreshSelection()
            }
        }
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.RadioButton::class.java.name
                info.text = item.label
                info.isCheckable = true
                info.isChecked = index == selectedIndex
            }
        })

        row.addView(TextView(context).apply {
            text = item.label
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            gravity = Gravity.CENTER
            includeFontPadding = false
            TextViewCompat.setTextAppearance(this, com.zero.common.R.style.TextAppearance_BabyCare_Body1)
            // 两侧对称保留勾选图标宽度，长选项换行增高且不会与尾部图标重叠。
            setPaddingRelative(iconSize + gap, gap, iconSize + gap, gap)
            setTextColor(context.resolveThemeColor(com.zero.common.R.attr.colorTextPrimary))
            typeface = Typeface.create(
                if (isSelected) "sans-serif-medium" else "sans-serif",
                Typeface.NORMAL
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        })

        row.addView(AppCompatImageView(context).apply {
            visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            setImageResource(com.zero.common.R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(context.resolveThemeColor(com.zero.common.R.attr.colorBrandForeground))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(
                iconSize,
                iconSize,
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
                    marginStart = horizontalPadding
                    marginEnd = horizontalPadding
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        return wrapper
    }

    private fun refreshSelection() {
        val container = findViewById<LinearLayout>(R.id.option_container)
        for (index in items.indices) {
            val row = (container.getChildAt(index) as LinearLayout).getChildAt(0) as FrameLayout
            val selected = index == selectedIndex
            (row.getChildAt(0) as TextView).typeface = Typeface.create(
                if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL
            )
            row.getChildAt(1).visibility = if (selected) View.VISIBLE else View.INVISIBLE
            row.isSelected = selected
        }
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
