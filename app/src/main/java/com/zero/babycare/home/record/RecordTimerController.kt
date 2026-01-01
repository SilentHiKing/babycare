package com.zero.babycare.home.record

import android.app.TimePickerDialog
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
import java.util.Calendar

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
        val shouldIgnoreInput: () -> Boolean = { false }
    )

    data class Callbacks(
        val onStartTimeChanged: (Long) -> Unit = {},
        val onEndTimeChanged: (Long?) -> Unit = {},
        val onTimerStart: () -> Unit = {},
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
                        handleTimerResume()
                        timerView.resumeFromPause()
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
            showTimePickerWithDefault(startInput.getTimestamp()) { hour, minute ->
                val timestamp = calculateSmartTimestampForStartTime(hour, minute)
                applyStartTimeChange(timestamp)
            }
        }

        endPicker.setOnClickListener {
            updateEndTimeReference()
            showTimePickerWithDefault(endInput.getTimestamp()) { hour, minute ->
                val timestamp = calculateSmartTimestampForEndTime(
                    hour,
                    minute,
                    startInput.getTimestamp()
                )
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
            val offset = System.currentTimeMillis() - startTimestamp
            if (offset > 0) {
                timerView.setPauseOffset(offset)
            }
        }

        clearEndTime(notify = true)
        callbacks.onDirty()

        timerView.post {
            val timerStartTime = timerView.getStartTimestamp()
            if (timerStartTime > 0) {
                setStartTime(timerStartTime, notify = true)
                callbacks.onTimerStart()
            }
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
                handleTimerResume()
                timerView.resumeFromPause()
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
        onTimeSet: (Int, Int) -> Unit
    ) {
        val calendar = if (defaultTimestamp > 0L) {
            Calendar.getInstance().apply { timeInMillis = defaultTimestamp }
        } else {
            Calendar.getInstance()
        }

        TimePickerDialog(
            context,
            { _, hour, minute -> onTimeSet(hour, minute) },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun calculateSmartTimestampForStartTime(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (calendar.timeInMillis > now.timeInMillis) {
            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }

        return calendar.timeInMillis
    }

    private fun calculateSmartTimestampForEndTime(
        hour: Int,
        minute: Int,
        startTimestamp: Long
    ): Long {
        val now = Calendar.getInstance()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (startTimestamp > 0L) {
            val startCalendar = Calendar.getInstance().apply { timeInMillis = startTimestamp }
            val startHour = startCalendar.get(Calendar.HOUR_OF_DAY)
            val startMinute = startCalendar.get(Calendar.MINUTE)

            calendar.set(Calendar.YEAR, startCalendar.get(Calendar.YEAR))
            calendar.set(Calendar.MONTH, startCalendar.get(Calendar.MONTH))
            calendar.set(Calendar.DAY_OF_MONTH, startCalendar.get(Calendar.DAY_OF_MONTH))

            if (hour < startHour || (hour == startHour && minute < startMinute)) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                if (calendar.timeInMillis > now.timeInMillis) {
                    calendar.add(Calendar.DAY_OF_MONTH, -1)
                }
            }
        }

        if (calendar.timeInMillis > now.timeInMillis) {
            calendar.add(Calendar.DAY_OF_MONTH, -1)
        }

        return calendar.timeInMillis
    }
}
