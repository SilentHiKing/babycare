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

    /** 强制暂停计时器（用于手动输入结束时间时） */
    fun forcePause() {
        if (currentShowState == RecordState.RECORDING) {
            timerCounter.pause()
            currentShowState = RecordState.PAUSE
            viewBinding.tvProgress.visibility = VISIBLE
            viewBinding.ivRecording.setImageResource(com.zero.common.R.drawable.ic_record)
        }
    }

    /** 获取开始时间戳 */
    fun getStartTimestamp(): Long = timerCounter.startTime

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        timerCounter.elapsedTime.observeForever(observer)
    }

    val observer = object : androidx.lifecycle.Observer<Long> {
        override fun onChanged(value: Long) {
            showProgress(timerCounter.formatToMinSec(value))
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        timerCounter.elapsedTime.removeObserver(observer)
    }

    init {
        showState(currentShowState)
        setOnClickListener {
            if (currentShowState == RecordState.INIT) {
                statusChange(currentShowState, RecordState.RECORDING)
                showState(RecordState.RECORDING)
                return@setOnClickListener
            } else if (currentShowState == RecordState.RECORDING) {
                statusChange(currentShowState, RecordState.PAUSE)
                showState(RecordState.PAUSE)
                return@setOnClickListener
            } else if (currentShowState == RecordState.PAUSE) {
                statusChange(currentShowState, RecordState.RECORDING)
                showState(RecordState.RECORDING)
                return@setOnClickListener
            }
        }
    }

    fun reset() {
        showState(RecordState.INIT)
    }

    fun showState(state: RecordState) {
        currentShowState = state
        when (state) {
            RecordState.INIT -> {
                viewBinding.tvProgress.visibility = INVISIBLE
                viewBinding.ivRecording.setImageResource(com.zero.common.R.drawable.ic_record)
                timerCounter.stop()
            }

            RecordState.RECORDING -> {
                viewBinding.tvProgress.visibility = VISIBLE
                viewBinding.ivRecording.setImageResource(com.zero.common.R.drawable.ic_recording)
                timerCounter.start()
            }

            RecordState.PAUSE -> {
                viewBinding.tvProgress.visibility = VISIBLE
                viewBinding.ivRecording.setImageResource(com.zero.common.R.drawable.ic_record)
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
        timerCounter.setDisplayDuration(durationMs)
    }

    enum class RecordState {
        INIT,
        RECORDING,
        PAUSE,
    }
}
