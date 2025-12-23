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
    
    // 累计暂停的总时长（用于多次暂停/继续场景）
    private var accumulatedDuration = 0L

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

    fun getDuration(): Long {
        return if (isRunning) {
            System.currentTimeMillis() - startTime + accumulatedDuration
        } else {
            accumulatedDuration + pauseOffset
        }
    }

    /** 设置显示的时长（毫秒），用于手动设置时长显示 */
    fun setDisplayDuration(duration: Long) {
        accumulatedDuration = duration
        _elapsedTime.value = duration
    }

    /** 获取累计时长 */
    fun getAccumulatedDuration(): Long = accumulatedDuration

    fun start() {
        if (isRunning) return

        startTime = System.currentTimeMillis() - pauseOffset
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
        handler.removeCallbacks(updateRunnable)
        _elapsedTime.value = accumulatedDuration
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        _elapsedTime.value = 0L
        pauseOffset = 0L
        accumulatedDuration = 0L
        startTime = 0L
    }

    fun release() {
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
