package com.zero.babycare.home.record

import android.content.Context
import android.view.View
import com.blankj.utilcode.util.ToastUtils
import com.lxj.xpopup.core.BasePopupView
import com.zero.common.R
import com.zero.common.util.DateUtils
import com.zero.common.util.TimerClock
import com.zero.common.util.TimerClockSnapshot
import com.zero.components.base.util.DialogHelper
import com.zero.components.widget.RecordView
import com.zero.components.widget.RecordView.RecordState
import com.zero.components.widget.TimeEditText
import com.zero.babydata.domain.TimerCalculations
import com.zero.babydata.domain.TimerStatus as PersistentStatus
import com.zero.babydata.domain.TimerRecordSnapshot

class RecordTimerController(
    private val context: Context,
    private val timerView: RecordView,
    private val startInput: TimeEditText,
    private val endInput: TimeEditText,
    private val startPicker: View,
    private val endPicker: View,
    private val config: Config,
    private val callbacks: Callbacks = Callbacks(),
    private val clock: () -> TimerClockSnapshot = TimerClock::snapshot
) {

    data class Config(
        val invalidEndTimeMessageRes: Int,
        val mode: TimerMode = TimerMode.FEEDING,
        val persistent: Boolean = false,
        val shouldIgnoreInput: () -> Boolean = { false },
        val durationPreview: (Long, Long) -> Result<Long>? = { _, _ -> null }
    )

    data class Callbacks(
        val onStartTimeChanged: (Long) -> Unit = {},
        val onEndTimeChanged: (Long?) -> Unit = {},
        val onTimerStart: (Long) -> Unit = {},
        val onTimerResume: () -> Unit = {},
        val onTimerPause: (Long) -> Unit = {},
        val onClockSynchronized: (String, Long, TimerClockSnapshot) -> Unit = { _, _, _ -> },
        val onDirty: () -> Unit = {}
    )

    private var pausedEndTimestamp = 0L
    private var pendingTimerStart: Runnable? = null
    private var pendingPopup: BasePopupView? = null
    private var callbackGeneration = 0L
    private var disposed = false
    private var renderedStart: Long = 0L
    private var renderedEnd: Long = 0L
    /** 已完成分段记录的预览允许为零，保存不能把零误当作未计时再退回整跨度。 */
    var manualDurationPreview: Long? = null
        private set
    var ongoingSessionId: String? = null
        private set
    var isStartTimeAutomatic: Boolean = false
        private set
    var timelineClock: TimerClockSnapshot? = null
        private set

    /** 与当前结束输入分开保存，重建后仍能识别暂停后手动修改过的结束时间。 */
    val originalPauseTimestamp: Long get() = pausedEndTimestamp

    init {
        bind()
    }

    fun reset() {
        dismissPendingUi()
        startInput.clear()
        endInput.clear()
        endInput.clearReferenceTimestamp()
        pausedEndTimestamp = 0L
        isStartTimeAutomatic = false
        timelineClock = null
        ongoingSessionId = null
        renderedStart = 0L
        renderedEnd = 0L
        manualDurationPreview = null
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
        timelineClock = clock()
        if (timestamp <= 0L) {
            startInput.clear()
            endInput.clearReferenceTimestamp()
            if (notify) {
                callbacks.onStartTimeChanged(0L)
            }
            return
        }

        startInput.setTimestamp(timestamp)
        renderedStart = timestamp
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
        renderedEnd = timestamp
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

    fun updateDurationFromInputs(errorInput: TimeEditText = endInput, previewOnly: Boolean = false): Boolean {
        manualDurationPreview = null
        if (!startInput.hasValidTime() || !endInput.hasValidTime()) {
            return false
        }
        val start = startInput.getTimestamp()
        val end = endInput.getTimestamp()
        val preview = config.durationPreview(start, end)
        // 保存前只复核已委托的分段编辑，不重算普通记录已经保留的手工有效总量。
        if (preview == null && previewOnly) return true
        if (preview?.isFailure == true) {
            // 无效调整保留输入供纠正，撤下旧数字，不能展示一个保存时不会接受的跨度。
            val rangeError = preview.exceptionOrNull() as? CompletedTimerDurationPreview.InvalidRange
            val input = rangeError?.let { if (it.startField) startInput else endInput } ?: errorInput
            input.error = context.getString(R.string.timer_invalid_change)
            input.requestFocus()
            timerView.reset()
            timerView.setHintText(R.string.timer_invalid_change)
            return false
        }
        manualDurationPreview = preview?.getOrThrow()
        startInput.error = null
        endInput.error = null
        val duration = manualDurationPreview ?: DateUtils.calculateDuration(start, end)
        timerView.showDurationWithoutTimer(duration)
        return true
    }

    private fun bind() {
        timerView.externallyDriven = config.persistent
        setupTimeInputs()
        setupTimerCounter()
        setupTimePickerButtons()
    }

    /** 页面 View 销毁前解除所有回调，避免旧输入框反向持有 Fragment。 */
    fun dispose() {
        disposed = true
        dismissPendingUi()
        startInput.clearOnTimeEnteredListener()
        endInput.clearOnTimeEnteredListener()
        startInput.clearOnRawInputChangedListener()
        endInput.clearOnRawInputChangedListener()
        startPicker.setOnClickListener(null)
        endPicker.setOnClickListener(null)
        timerView.statusChange = null
    }

    /** 隐藏/销毁视图立即失效旧回调；不停止仍在运行的计时器或修改持久会话。 */
    fun dismissPendingUi() {
        callbackGeneration++
        pendingTimerStart?.let(timerView::removeCallbacks)
        pendingTimerStart = null
        pendingPopup?.dismiss()
        pendingPopup = null
    }

    private fun setupTimeInputs() {
        // 未完成的原始输入也属于草稿；仅输入结束回调不足以覆盖“输入一半后退出”。
        startInput.setOnRawInputChangedListener {
            if (!config.shouldIgnoreInput()) {
                isStartTimeAutomatic = false
                callbacks.onDirty()
            }
        }
        endInput.setOnRawInputChangedListener {
            if (!config.shouldIgnoreInput()) callbacks.onDirty()
        }
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
        if (timestamp / 1000 == renderedStart / 1000 && renderedStart > 0L) return
        if (config.persistent && ongoingSessionId != null) {
            callbacks.onStartTimeChanged(timestamp)
            callbacks.onDirty()
            return
        }
        isStartTimeAutomatic = false
        setStartTime(timestamp, notify = true)
        renderedStart = timestamp
        updateDurationFromInputs(startInput)
        callbacks.onDirty()
    }

    private fun applyEndTimeChange(timestamp: Long) {
        if (timestamp / 1000 == renderedEnd / 1000 && renderedEnd > 0L) return
        if (config.persistent && ongoingSessionId != null) {
            callbacks.onEndTimeChanged(timestamp)
            callbacks.onDirty()
            return
        }
        val wasRunning = timerView.currentShowState == RecordState.RECORDING
        if (wasRunning) synchronizeClock(activeTransition = true)
        if (startInput.hasValidTime()) {
            val startTime = startInput.getTimestamp()
            if (!DateUtils.isEndAfterStart(startTime, timestamp)) {
                ToastUtils.showShort(context.getString(config.invalidEndTimeMessageRes))
                clearEndTime(notify = true)
                return
            }
        }
        // 先校验所选时间，再终止真实运行；手改结束也必须同步暂停事实，不能只改页面。
        if (wasRunning) {
            timerView.forcePause()
        }
        callbacks.onDirty()
        setEndTime(timestamp, notify = true)
        renderedEnd = timestamp
        updateDurationFromInputs()
        if (wasRunning) callbacks.onTimerPause(timestamp)
        // 不覆盖 pausedEndTimestamp，保留手改结束后继续时的明确确认流程。
    }

    private fun handleTimerStart() {
        if (!startInput.text.isNullOrBlank() && !startInput.hasValidTime()) {
            startInput.error = context.getString(R.string.invalid_time)
            startInput.requestFocus()
            return
        }
        isStartTimeAutomatic = !startInput.hasValidTime()
        if (config.persistent) {
            callbacks.onTimerStart(if (startInput.hasValidTime()) startInput.getTimestamp() else clock().wallTimeMs)
            callbacks.onDirty()
            return
        }
        if (startInput.hasValidTime()) {
            val startTimestamp = startInput.getTimestamp()
            val offset = (clock().wallTimeMs - startTimestamp).coerceAtLeast(0L)
            if (offset > 0L) {
                timerView.setPauseOffset(offset)
            }
        }

        clearEndTime(notify = true)
        callbacks.onDirty()

        pendingTimerStart?.let(timerView::removeCallbacks)
        val generation = callbackGeneration
        val task = Runnable {
            if (disposed || callbackGeneration != generation || timerView.currentShowState != RecordState.RECORDING) {
                return@Runnable
            }
            pendingTimerStart = null
            val timerStartTime = timerView.getStartTimestamp()
            if (timerStartTime > 0) {
                setStartTime(timerStartTime, notify = true)
                callbacks.onTimerStart(timerStartTime)
            }
        }
        pendingTimerStart = task
        timerView.post(task)
    }

    private fun continueTimerAfterPause() {
        if (config.persistent) {
            callbacks.onTimerResume()
            callbacks.onDirty()
            return
        }
        synchronizeClock(activeTransition = true)
        handleTimerResume()
        if (TimerSessionPolicy.shouldResumeFromStart(config.mode) && startInput.hasValidTime()) {
            // 睡眠和活动以开始时间到当前时间作为完整区间，暂停后继续需按原始开始时间恢复计时展示。
            val offset = (clock().wallTimeMs - startInput.getTimestamp()).coerceAtLeast(0L)
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
        if (config.persistent) {
            callbacks.onTimerPause(clock().wallTimeMs)
            callbacks.onDirty()
            return
        }
        // forcePause 会先将 View 状态改为 PAUSE，再回调；仍属于本次运行的暂停转换。
        val currentTime = synchronizeClock(activeTransition = true).wallTimeMs
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
        dismissPendingUi()
        val generation = callbackGeneration
        pendingPopup = DialogHelper.showConfirmDialog(
            context = context,
            title = context.getString(R.string.continue_timing),
            content = context.getString(R.string.end_time_will_be_cleared),
            confirmText = context.getString(R.string.confirm),
            cancelText = context.getString(R.string.cancel),
            onConfirm = {
                if (!disposed && callbackGeneration == generation && timerView.isShown) {
                    pendingPopup = null
                    continueTimerAfterPause()
                }
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
        val now = clock().wallTimeMs
        val effectiveMinTime = minTime?.takeIf { it in 1L..now }
        val initialTime = normalizePickerInitialTime(
            defaultTimestamp = defaultTimestamp,
            minTime = effectiveMinTime,
            maxTime = now
        )

        dismissPendingUi()
        val generation = callbackGeneration
        pendingPopup = DialogHelper.showDateTimeSecondSheet(
            context = context,
            title = context.getString(R.string.select_time),
            initialTime = initialTime,
            minTime = effectiveMinTime,
            maxTime = now,
            onConfirm = { timestamp ->
                if (!disposed && callbackGeneration == generation && timerView.isShown) {
                    pendingPopup = null
                    onTimeSet(timestamp)
                }
            }
        )
    }

    /** 恢复进程退出前的暂停终态，并保留结束时间用于后续继续确认。 */
    fun restorePausedSession(endTimestamp: Long, durationMs: Long, originalPauseTimestamp: Long = endTimestamp) {
        setEndTime(endTimestamp, notify = false)
        pausedEndTimestamp = originalPauseTimestamp
        timerView.restorePaused(durationMs)
    }

    /** 恢复持久会话/草稿的来源标记；手工开始日期从不参与系统校时平移。 */
    fun restoreAutomaticStart(automatic: Boolean, anchor: TimerClockSnapshot? = null, sessionId: String? = null) {
        isStartTimeAutomatic = automatic
        timelineClock = anchor ?: clock()
        ongoingSessionId = sessionId
    }

    /** 自动运行记录暂停/保存前校正日期，避免单调时长正确但墙钟回拨导致结束早于开始。 */
    fun synchronizeClock(activeTransition: Boolean = false): TimerClockSnapshot {
        val now = clock()
        if (config.persistent) return now
        if (!isStartTimeAutomatic ||
            (!activeTransition && timerView.currentShowState != RecordState.RECORDING)) return now
        val shift = timelineClock?.let(now::automaticWallShiftSince) ?: 0L
        if (shift != 0L) {
            val start = startInput.getTimestamp()
            if (start > 0L) startInput.setTimestamp(start + shift)
            val end = endInput.getTimestamp()
            // 用户手改的结束字段不移动；继续计时的确认流程随后会明确清除此字段。
            if (end > 0L && end == pausedEndTimestamp) endInput.setTimestamp(end + shift)
            if (pausedEndTimestamp > 0L) pausedEndTimestamp += shift
            updateEndTimeReference()
        }
        timelineClock = now
        // 精确身份由持久会话 UUID 决定；独立采样的毫秒误差不能靠开始时间容差猜测归属。
        ongoingSessionId?.takeIf { it.isNotBlank() }?.let { id ->
            if (startInput.hasValidTime()) callbacks.onClockSynchronized(id, startInput.getTimestamp(), now)
        }
        return now
    }

    /** 只用数据库确认后的快照渲染；本地秒表仅插值显示，不能决定业务状态。 */
    fun renderPersistent(snapshot: TimerRecordSnapshot?) {
        ongoingSessionId = snapshot?.ref?.sessionId
        if (snapshot == null) return
        val metadata = snapshot.metadata
        isStartTimeAutomatic = metadata.automaticStart
        timelineClock = metadata.clockAnchor
        renderedStart = snapshot.startAt
        renderedEnd = snapshot.endAt ?: 0L
        setStartTime(renderedStart)
        setEndTime(renderedEnd)
        val duration = TimerCalculations.totalDuration(metadata, clock())
        if (snapshot.status == PersistentStatus.RUNNING) timerView.startFromOffset(duration)
        else restorePausedSession(renderedEnd, duration)
        if (snapshot.status == PersistentStatus.STOPPED) timerView.setHintText(R.string.timer_stopped_hint)
        else if (config.mode == TimerMode.SLEEP) timerView.setHintText(
            if (snapshot.status == PersistentStatus.RUNNING) R.string.timer_sleep_awake else R.string.timer_sleep_again)
    }

    /** 系统校时或重启后的估算由用户核对后确认，不能静默当作精确观测。 */
    fun showReviewConfirmation(onConfirm: () -> Unit) {
        dismissPendingUi()
        val generation = callbackGeneration
        pendingPopup = DialogHelper.showConfirmDialog(context,
            context.getString(R.string.timer_review_title), context.getString(R.string.timer_review_required),
            context.getString(R.string.confirm), context.getString(R.string.cancel), onConfirm = {
                if (!disposed && callbackGeneration == generation && timerView.isShown) onConfirm()
            })
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
