package com.zero.components.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import com.zero.common.util.DateUtils.timestampToMMddHHmmss
import com.zero.components.databinding.RecordViewBinding

class RecordView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var viewBinding: RecordViewBinding = RecordViewBinding.inflate(
        LayoutInflater.from(context),
        this,
    )

    var currentShowState: RecordState = RecordState.INIT
        private set

    lateinit var statusChange: (current: RecordState, next: RecordState) -> Unit

    val timerCounter = TimerCounter()

    fun getDuration(): Long {
        return timerCounter.getDuration()
    }

    fun setPauseOffset(value: Long) {
        timerCounter.setPauseOffset(value)
    }

    fun getStartTimeStr(): String {
        return timestampToMMddHHmmss(timerCounter.startTime)
    }

    /** 设置显示的时长（毫秒） */
    fun setDisplayDuration(duration: Long) {
        timerCounter.setDisplayDuration(duration)
        showProgress(timerCounter.formatToMinSec(duration))
    }

    /** 获取累计时长 */
    fun getAccumulatedDuration(): Long = timerCounter.getAccumulatedDuration()

    /** 检查计时器是否正在运行 */
    fun isTimerRunning(): Boolean = timerCounter.isTimerRunning()

    /** 检查是否有有效的时长数据 */
    fun hasValidDuration(): Boolean = timerCounter.hasValidDuration()

    /**
     * 强制暂停计时器（用于手动输入结束时间时）
     * @param triggerCallback 是否触发 statusChange 回调
     */
    fun forcePause(triggerCallback: Boolean = false) {
        if (currentShowState == RecordState.RECORDING) {
            val previousState = currentShowState
            timerCounter.pause()
            currentShowState = RecordState.PAUSE
            viewBinding.tvProgress.visibility = VISIBLE
            viewBinding.ivRecording.setImageResource(com.zero.common.R.drawable.ic_record_start)
            viewBinding.tvHint.visibility = VISIBLE
            viewBinding.tvHint.setText(com.zero.common.R.string.tap_to_continue)
            
            if (triggerCallback && ::statusChange.isInitialized) {
                statusChange(previousState, RecordState.PAUSE)
            }
        }
    }

    /** 获取开始时间戳 */
    fun getStartTimestamp(): Long = timerCounter.startTime

    /** 释放资源，页面销毁时调用 */
    fun release() {
        timerCounter.release()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        timerCounter.elapsedTime.observeForever(observer)
    }

    private val observer = androidx.lifecycle.Observer<Long> { value ->
        showProgress(timerCounter.formatToMinSec(value))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        timerCounter.elapsedTime.removeObserver(observer)
    }

    init {
        showState(currentShowState)
        setOnClickListener {
            when (currentShowState) {
                RecordState.INIT -> {
                    statusChange(currentShowState, RecordState.RECORDING)
                    showState(RecordState.RECORDING)
                }
                RecordState.RECORDING -> {
                    statusChange(currentShowState, RecordState.PAUSE)
                    showState(RecordState.PAUSE)
                }
                RecordState.PAUSE -> {
                    // PAUSE -> RECORDING 时，先回调让外部处理
                    // 外部可以选择调用 resumeFromPause() 来真正继续计时
                    // 或者不调用保持 PAUSE 状态
                    statusChange(currentShowState, RecordState.RECORDING)
                }
            }
        }
    }

    /**
     * 从暂停状态继续计时
     * 外部在收到 PAUSE -> RECORDING 的回调后，确认要继续时调用此方法
     */
    fun resumeFromPause() {
        if (currentShowState == RecordState.PAUSE) {
            showState(RecordState.RECORDING)
        }
    }

    fun reset() {
        showState(RecordState.INIT)
    }

    fun showState(state: RecordState) {
        val previousState = currentShowState
        currentShowState = state
        when (state) {
            RecordState.INIT -> {
                viewBinding.tvProgress.visibility = INVISIBLE
                viewBinding.ivRecording.setImageResource(com.zero.common.R.drawable.ic_record_start)
                viewBinding.tvHint.visibility = VISIBLE
                viewBinding.tvHint.setText(com.zero.common.R.string.tap_to_start)
                timerCounter.stop()
            }

            RecordState.RECORDING -> {
                viewBinding.tvProgress.visibility = VISIBLE
                viewBinding.ivRecording.setImageResource(com.zero.common.R.drawable.ic_recording)
                viewBinding.tvHint.visibility = VISIBLE
                viewBinding.tvHint.setText(com.zero.common.R.string.tap_to_pause)
                // 如果是从 INIT 状态开始，清零累计时长
                val fromInit = previousState == RecordState.INIT
                timerCounter.start(fromInit)
            }

            RecordState.PAUSE -> {
                viewBinding.tvProgress.visibility = VISIBLE
                viewBinding.ivRecording.setImageResource(com.zero.common.R.drawable.ic_record_start)
                viewBinding.tvHint.visibility = VISIBLE
                viewBinding.tvHint.setText(com.zero.common.R.string.tap_to_continue)
                timerCounter.pause()
            }
        }
    }

    fun showProgress(progress: String) {
        viewBinding.tvProgress.text = progress
    }

    /** 显示时长但不启动计时器（用于手动输入时间后显示时长） */
    fun showDurationWithoutTimer(durationMs: Long) {
        viewBinding.tvProgress.visibility = VISIBLE
        viewBinding.tvProgress.text = timerCounter.formatToMinSec(durationMs)
        viewBinding.tvHint.visibility = GONE
        timerCounter.setDisplayDuration(durationMs)
    }

    enum class RecordState {
        INIT,
        RECORDING,
        PAUSE,
    }
}
