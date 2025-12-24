package com.zero.components.widget

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class TimerCounter {

    var startTime = 0L
        private set
    private var pauseOffset = 0L
    
    // 累计的总时长（用于多次暂停/继续场景和手动设置时长）
    private var accumulatedDuration = 0L
    
    // 标记是否通过手动设置了时长（而非通过计时）
    private var isManualDuration = false

    private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())

    private val _elapsedTime = MutableLiveData<Long>(0L)
    val elapsedTime: LiveData<Long> get() = _elapsedTime

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val current = System.currentTimeMillis() - startTime + accumulatedDuration
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
            isRunning -> System.currentTimeMillis() - startTime + accumulatedDuration
            isManualDuration -> accumulatedDuration
            accumulatedDuration > 0 -> accumulatedDuration
            else -> 0L
        }
    }

    /** 设置显示的时长（毫秒），用于手动设置时长显示 */
    fun setDisplayDuration(duration: Long) {
        accumulatedDuration = duration
        isManualDuration = true
        pauseOffset = 0L
        _elapsedTime.value = duration
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
        
        startTime = System.currentTimeMillis() - pauseOffset
        pauseOffset = 0L
        isRunning = true
        handler.post(updateRunnable)
    }

    fun pause() {
        if (!isRunning) return

        // 将本次运行的时长加入累计时长
        val currentSessionDuration = System.currentTimeMillis() - startTime
        accumulatedDuration += currentSessionDuration
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
    }

    fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
    }

    /** 检查是否正在运行 */
    fun isTimerRunning(): Boolean = isRunning

    @SuppressLint("DefaultLocale")
    fun formatToMinSec(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    @SuppressLint("DefaultLocale")
    fun formatToHourMinSec(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
