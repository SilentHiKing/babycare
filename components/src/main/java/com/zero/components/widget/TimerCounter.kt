package com.zero.components.widget

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zero.common.util.DurationFormatUtils
import com.zero.common.util.TimerClock
import com.zero.common.util.TimerClockSnapshot

class TimerCounter(private val clock: () -> TimerClockSnapshot = TimerClock::snapshot) {

    var startTime = 0L
        private set
    private var pauseOffset = 0L
    
    // 累计的总时长（用于多次暂停/继续场景和手动设置时长）
    private var accumulatedDuration = 0L
    
    // 标记是否通过手动设置了时长（而非通过计时）
    private var isManualDuration = false

    private var isRunning = false
    private var runningAnchor: TimerClockSnapshot? = null
    private var displayActive = true

    private val handler = Handler(Looper.getMainLooper())

    private val _elapsedTime = MutableLiveData<Long>(0L)
    val elapsedTime: LiveData<Long> get() = _elapsedTime

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRunning && displayActive) {
                val current = getDuration()
                _elapsedTime.value = current
                handler.postDelayed(this, 1000L) // 每 1 秒更新一次
            }
        }
    }

    fun setPauseOffset(value: Long) {
        pauseOffset = value
    }

    /**
     * 获取当前时长
     * - 如果正在运行：返回实时计算的时长
     * - 如果暂停/手动设置：返回累计时长
     */
    fun getDuration(): Long {
        return when {
            isRunning -> accumulatedDuration + (runningAnchor?.let { clock().elapsedSince(it) } ?: 0L)
            isManualDuration -> accumulatedDuration
            accumulatedDuration > 0 -> accumulatedDuration
            else -> 0L
        }
    }

    /** 设置显示的时长（毫秒），用于手动设置时长显示 */
    fun setDisplayDuration(duration: Long) {
        accumulatedDuration = duration.coerceAtLeast(0L)
        isManualDuration = true
        pauseOffset = 0L
        _elapsedTime.value = accumulatedDuration
    }

    /** 获取累计时长 */
    fun getAccumulatedDuration(): Long = accumulatedDuration

    /** 检查是否有有效的时长数据 */
    fun hasValidDuration(): Boolean = accumulatedDuration > 0 || isRunning

    /**
     * 开始计时
     * @param fromInit 是否从初始状态开始（而非从暂停状态继续）
     */
    fun start(fromInit: Boolean = false) {
        if (isRunning) return

        // 开始计时时，清除手动设置标记
        isManualDuration = false
        
        // 如果是从初始状态开始，清零累计时长
        if (fromInit) {
            accumulatedDuration = 0L
        }
        
        val now = clock()
        startTime = now.wallTimeMs - pauseOffset.coerceAtLeast(0L)
        // 日期起点仍供输入框使用；运行累计只使用单调时钟，系统校时不会增减已计时内容。
        accumulatedDuration += pauseOffset.coerceAtLeast(0L)
        runningAnchor = now
        pauseOffset = 0L
        isRunning = true
        if (displayActive) handler.post(updateRunnable)
    }

    fun pause() {
        if (!isRunning) return

        // 将本次运行的时长加入累计时长
        accumulatedDuration = getDuration()
        runningAnchor = null
        pauseOffset = 0L
        isRunning = false
        isManualDuration = false
        handler.removeCallbacks(updateRunnable)
        _elapsedTime.value = accumulatedDuration
    }

    fun stop() {
        isRunning = false
        isManualDuration = false
        handler.removeCallbacks(updateRunnable)
        _elapsedTime.value = 0L
        pauseOffset = 0L
        accumulatedDuration = 0L
        startTime = 0L
        runningAnchor = null
    }

    fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
    }

    /** 隐藏页面只停止刷新，不改变计时锚点；回到前台重新计算真实耗时。 */
    fun setDisplayActive(active: Boolean) {
        displayActive = active
        handler.removeCallbacks(updateRunnable)
        if (active && isRunning) handler.post(updateRunnable)
    }

    /** 检查是否正在运行 */
    fun isTimerRunning(): Boolean = isRunning

    fun formatToMinSec(ms: Long): String {
        // 计时显示统一委托 common 工具，避免不同组件对长时长和负数处理不一致。
        return DurationFormatUtils.formatTimerClock(ms)
    }

    fun formatToHourMinSec(ms: Long): String {
        // 保留原有 API，实际格式规则集中在 DurationFormatUtils 中维护。
        return DurationFormatUtils.formatTimerClock(ms)
    }
}
