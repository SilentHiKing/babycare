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
