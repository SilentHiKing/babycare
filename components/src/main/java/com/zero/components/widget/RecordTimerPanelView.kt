package com.zero.components.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import com.zero.components.R
import com.zero.components.databinding.ViewRecordTimerPanelBinding

class RecordTimerPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val viewBinding = ViewRecordTimerPanelBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )

    val timerView: RecordView
        get() = viewBinding.rvTimer

    val startInput: TimeEditText
        get() = viewBinding.etStartTime

    val endInput: TimeEditText
        get() = viewBinding.etEndTime

    val startPicker: View
        get() = viewBinding.ivStartTime

    val endPicker: View
        get() = viewBinding.ivEndTime

    init {
        orientation = VERTICAL
        applyAttributes(attrs, defStyleAttr)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            .coerceAtLeast(0)
        val narrowWidth = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            availableWidth / resources.displayMetrics.density < 320f
        updateTimeFieldLayout(narrowWidth || resources.configuration.fontScale > 1.3f)
        // 必须先确定排列方向再测量；在 onSizeChanged 中切换会沿用旧行高并裁掉结束字段。
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateTimeFieldLayout(stackFields: Boolean) {
        val targetOrientation = if (stackFields) VERTICAL else HORIZONTAL
        if (viewBinding.timeRow.orientation == targetOrientation) return

        // 大字号和窄窗改为上下排列，给日期、秒级时间和 48dp 选择入口保留足够空间。
        viewBinding.timeRow.orientation = targetOrientation
        listOf(viewBinding.startTimeField, viewBinding.endTimeField).forEach { field ->
            field.updateLayoutParams<LayoutParams> {
                width = if (stackFields) LayoutParams.MATCH_PARENT else 0
                weight = if (stackFields) 0f else 1f
            }
        }
        val gap = resources.getDimensionPixelSize(com.zero.common.R.dimen.record_time_field_gap)
        viewBinding.timeFieldGap.updateLayoutParams<LayoutParams> {
            width = if (stackFields) 1 else gap
            height = if (stackFields) gap else 1
        }
    }

    fun setStartLabelText(text: CharSequence?) {
        if (!text.isNullOrBlank()) {
            viewBinding.tvStartTime.text = text
        }
    }

    fun setEndLabelText(text: CharSequence?) {
        if (!text.isNullOrBlank()) {
            viewBinding.tvEndTime.text = text
        }
    }

    fun setStartHintText(text: CharSequence?) {
        if (!text.isNullOrBlank()) {
            viewBinding.etStartTime.hint = text
        }
    }

    fun setEndHintText(text: CharSequence?) {
        if (!text.isNullOrBlank()) {
            viewBinding.etEndTime.hint = text
        }
    }

    fun setTimeRowMarginTop(marginTopPx: Int) {
        viewBinding.timeRow.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = marginTopPx
        }
    }

    fun setTimerVisible(visible: Boolean) {
        viewBinding.rvTimer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setStartIconRes(resId: Int) {
        if (resId != 0) {
            viewBinding.ivStartTime.setImageResource(resId)
        }
    }

    fun setEndIconRes(resId: Int) {
        if (resId != 0) {
            viewBinding.ivEndTime.setImageResource(resId)
        }
    }

    private fun applyAttributes(attrs: AttributeSet?, defStyleAttr: Int) {
        if (attrs == null) return
        val typedArray = context.obtainStyledAttributes(
            attrs,
            R.styleable.RecordTimerPanelView,
            defStyleAttr,
            0
        )
        val startLabel = typedArray.getString(R.styleable.RecordTimerPanelView_startLabelText)
        val endLabel = typedArray.getString(R.styleable.RecordTimerPanelView_endLabelText)
        val startHint = typedArray.getString(R.styleable.RecordTimerPanelView_startHintText)
        val endHint = typedArray.getString(R.styleable.RecordTimerPanelView_endHintText)
        val rowMarginTop = typedArray.getDimensionPixelSize(
            R.styleable.RecordTimerPanelView_timeRowMarginTop,
            0
        )
        val showTimer = typedArray.getBoolean(R.styleable.RecordTimerPanelView_showTimer, true)
        val startIcon = typedArray.getResourceId(R.styleable.RecordTimerPanelView_startIcon, 0)
        val endIcon = typedArray.getResourceId(R.styleable.RecordTimerPanelView_endIcon, 0)
        typedArray.recycle()

        setStartLabelText(startLabel)
        setEndLabelText(endLabel)
        setStartHintText(startHint)
        setEndHintText(endHint)
        if (rowMarginTop > 0) {
            setTimeRowMarginTop(rowMarginTop)
        }
        setTimerVisible(showTimer)
        setStartIconRes(startIcon)
        setEndIconRes(endIcon)
    }
}
