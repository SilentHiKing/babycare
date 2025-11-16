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

    private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())

    private val _elapsedTime = MutableLiveData<Long>(0L)
    val elapsedTime: LiveData<Long> get() = _elapsedTime

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val current = System.currentTimeMillis() - startTime
                _elapsedTime.value = current
                handler.postDelayed(this, 1000L) // 每 1 秒更新一次
            }
        }
    }


    fun setPauseOffset(value: Long) {
        pauseOffset = value
    }

    fun getDuration(): Long {
        return System.currentTimeMillis() - startTime
    }



    fun start() {
        if (isRunning) return

        startTime = System.currentTimeMillis() - pauseOffset
        isRunning = true
        handler.post(updateRunnable)
    }

    fun pause() {
        if (!isRunning) return

        pauseOffset = System.currentTimeMillis() - startTime
        isRunning = false
        handler.removeCallbacks(updateRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        _elapsedTime.value = 0L
        pauseOffset = 0L
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("DefaultLocale")
    fun formatToMinSec(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

}
