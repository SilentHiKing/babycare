package com.zero.babycare.home.record

import android.content.Context
import android.view.View
import com.blankj.utilcode.util.StringUtils
import com.blankj.utilcode.util.ToastUtils
import com.zero.common.R
import com.zero.common.util.DateUtils
import com.zero.components.base.util.DialogHelper
import com.zero.components.widget.RecordView
import com.zero.components.widget.RecordView.RecordState
import com.zero.components.widget.TimeEditText

class RecordTimerController(
    private val context: Context,
    private val timerView: RecordView,
    private val startInput: TimeEditText,
    private val endInput: TimeEditText,
    private val startPicker: View,
    private val endPicker: View,
    private val config: Config,
    private val callbacks: Callbacks = Callbacks()
) {

    data class Config(
        val invalidEndTimeMessageRes: Int,
        val mode: TimerMode = TimerMode.FEEDING,
        val shouldIgnoreInput: () -> Boolean = { false }
    )

    data class Callbacks(
        val onStartTimeChanged: (Long) -> Unit = {},
        val onEndTimeChanged: (Long?) -> Unit = {},
        val onTimerStart: (Long) -> Unit = {},
        val onTimerResume: () -> Unit = {},
        val onTimerPause: (Long) -> Unit = {},
        val onDirty: () -> Unit = {}
    )

    private var pausedEndTimestamp = 0L

    init {
        bind()
    }

    fun reset() {
        startInput.clear()
        endInput.clear()
        endInput.clearReferenceTimestamp()
        pausedEndTimestamp = 0L
        timerView.reset()
    }

    fun syncStartTime(
        timestamp: Long,
        clearEnd: Boolean = false,
        resetTimer: Boolean = false,
        notify: Boolean = false,
        notifyEndClear: Boolean = false
    ) {
        setStartTime(timestamp, notify)
        if (clearEnd) {
            clearEndTime(notify = notifyEndClear)
        }
        if (resetTimer) {
            timerView.reset()
        }
    }

    fun setStartTime(timestamp: Long, notify: Boolean = false) {
        if (timestamp <= 0L) {
            startInput.clear()
            endInput.clearReferenceTimestamp()
            if (notify) {
                callbacks.onStartTimeChanged(0L)
            }
            return
        }

        startInput.setTimestamp(timestamp)
        updateEndTimeReference()
        if (notify) {
            callbacks.onStartTimeChanged(timestamp)
        }
    }

    fun setEndTime(
        timestamp: Long,
        notify: Boolean = false,
        updateDuration: Boolean = false
    ) {
        if (timestamp <= 0L) {
            endInput.clear()
            if (notify) {
                callbacks.onEndTimeChanged(null)
            }
            return
        }

        endInput.setTimestamp(timestamp)
        if (notify) {
            callbacks.onEndTimeChanged(timestamp)
        }
        if (updateDuration) {
            updateDurationFromInputs()
        }
    }

    fun clearEndTime(notify: Boolean = true) {
        endInput.clear()
        pausedEndTimestamp = 0L
        if (notify) {
            callbacks.onEndTimeChanged(null)
        }
    }

    fun updateDurationFromInputs() {
        if (!startInput.hasValidTime() || !endInput.hasValidTime()) {
            return
        }
        val duration = DateUtils.calculateDuration(startInput.getTimestamp(), endInput.getTimestamp())
        timerView.showDurationWithoutTimer(duration)
    }

    private fun bind() {
        setupTimeInputs()
        setupTimerCounter()
        setupTimePickerButtons()
    }

    private fun setupTimeInputs() {
        startInput.setOnTimeEnteredListener { _, _ ->
            if (config.shouldIgnoreInput()) return@setOnTimeEnteredListener
            val timestamp = startInput.getTimestamp()
            if (timestamp > 0L) {
                applyStartTimeChange(timestamp)
            }
        }

        endInput.setOnTimeEnteredListener { _, _ ->
            if (config.shouldIgnoreInput()) return@setOnTimeEnteredListener
            val timestamp = endInput.getTimestamp()
            if (timestamp > 0L) {
                applyEndTimeChange(timestamp)
            }
        }
    }

    private fun setupTimerCounter() {
        timerView.statusChange = { current, next ->
            when {
                current == RecordState.INIT && next == RecordState.RECORDING -> {
                    handleTimerStart()
                }
                current == RecordState.PAUSE && next == RecordState.RECORDING -> {
                    if (isEndTimeManuallyModified()) {
                        showResumeTimerConfirmDialog()
                    } else {
                        continueTimerAfterPause()
                    }
                }
                next == RecordState.PAUSE -> {
                    handleTimerPause()
                }
            }
        }
    }

    private fun setupTimePickerButtons() {
        startPicker.setOnClickListener {
            showTimePickerWithDefault(startInput.getTimestamp()) { timestamp ->
                applyStartTimeChange(timestamp)
            }
        }

        endPicker.setOnClickListener {
            updateEndTimeReference()
            val startTimestamp = startInput.getTimestamp().takeIf { it > 0L }
            showTimePickerWithDefault(endInput.getTimestamp(), minTime = startTimestamp) { timestamp ->
                applyEndTimeChange(timestamp)
            }
        }
    }

    private fun applyStartTimeChange(timestamp: Long) {
        setStartTime(timestamp, notify = true)
        clearEndTime(notify = true)
        timerView.reset()
        callbacks.onDirty()
    }

    private fun applyEndTimeChange(timestamp: Long) {
        if (timerView.currentShowState == RecordState.RECORDING) {
            timerView.forcePause()
        }

        callbacks.onDirty()
        if (startInput.hasValidTime() && endInput.hasValidTime()) {
            val startTime = startInput.getTimestamp()
            if (!DateUtils.isEndAfterStart(startTime, timestamp)) {
                ToastUtils.showShort(config.invalidEndTimeMessageRes)
                clearEndTime(notify = true)
                return
            }
            setEndTime(timestamp, notify = true)
            updateDurationFromInputs()
        } else {
            setEndTime(timestamp, notify = true)
        }
    }

    private fun handleTimerStart() {
        if (startInput.hasValidTime()) {
            val startTimestamp = startInput.getTimestamp()
            val offset = (System.currentTimeMillis() - startTimestamp).coerceAtLeast(0L)
            if (offset > 0L) {
                timerView.setPauseOffset(offset)
            }
        }

        clearEndTime(notify = true)
        callbacks.onDirty()

        timerView.post {
            val timerStartTime = timerView.getStartTimestamp()
            if (timerStartTime > 0) {
                setStartTime(timerStartTime, notify = true)
                callbacks.onTimerStart(timerStartTime)
            }
        }
    }

    private fun continueTimerAfterPause() {
        handleTimerResume()
        if (TimerSessionPolicy.shouldResumeFromStart(config.mode) && startInput.hasValidTime()) {
            // 睡眠和活动以开始时间到当前时间作为完整区间，暂停后继续需按原始开始时间恢复计时展示。
            val offset = (System.currentTimeMillis() - startInput.getTimestamp()).coerceAtLeast(0L)
            timerView.startFromOffset(offset)
        } else {
            timerView.resumeFromPause()
        }
    }

    private fun handleTimerResume() {
        clearEndTime(notify = true)
        callbacks.onDirty()
        callbacks.onTimerResume()
    }

    private fun handleTimerPause() {
        val currentTime = System.currentTimeMillis()
        setEndTime(currentTime, notify = true)
        pausedEndTimestamp = currentTime
        callbacks.onTimerPause(currentTime)
    }

    private fun isEndTimeManuallyModified(): Boolean {
        val currentEndTime = endInput.getTimestamp()
        if (currentEndTime <= 0L) return false
        if (pausedEndTimestamp == 0L) return true
        return currentEndTime != pausedEndTimestamp
    }

    private fun showResumeTimerConfirmDialog() {
        DialogHelper.showConfirmDialog(
            context = context,
            title = StringUtils.getString(R.string.continue_timing),
            content = StringUtils.getString(R.string.end_time_will_be_cleared),
            confirmText = StringUtils.getString(R.string.confirm),
            cancelText = StringUtils.getString(R.string.cancel),
            onConfirm = {
                continueTimerAfterPause()
            }
        )
    }

    private fun updateEndTimeReference() {
        if (startInput.hasValidTime()) {
            endInput.setReferenceTimestamp(startInput.getTimestamp())
        } else {
            endInput.clearReferenceTimestamp()
        }
    }

    private fun showTimePickerWithDefault(
        defaultTimestamp: Long,
        minTime: Long? = null,
        onTimeSet: (Long) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val effectiveMinTime = minTime?.takeIf { it in 1L..now }
        val initialTime = normalizePickerInitialTime(
            defaultTimestamp = defaultTimestamp,
            minTime = effectiveMinTime,
            maxTime = now
        )

        DialogHelper.showMonthDayTimeSecondSheet(
            context = context,
            title = StringUtils.getString(R.string.select_time),
            initialTime = initialTime,
            minTime = effectiveMinTime,
            maxTime = now,
            onConfirm = onTimeSet
        )
    }

    private fun normalizePickerInitialTime(
        defaultTimestamp: Long,
        minTime: Long?,
        maxTime: Long
    ): Long {
        val candidate = defaultTimestamp.takeIf { it > 0L } ?: maxTime
        return when {
            minTime != null && candidate < minTime -> minTime
            candidate > maxTime -> maxTime
            else -> candidate
        }
    }
}
