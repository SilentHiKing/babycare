package com.zero.babycare.home.record

import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import com.zero.components.widget.RecordTimerPanelView
import com.zero.components.widget.RecordView.RecordState
import com.zero.common.util.TimerClock
import com.zero.common.util.TimerClockSnapshot

/** 保存尚未提交的表单 View 状态；动态选项和业务类型由各页面单独补充。 */
internal object RecordDraftState {
    const val KEY = "record_form_draft"

    /** 常驻页面只能恢复当前宝宝、当前编辑目标的草稿，重新进入页面仍沿用原来的重置流程。 */
    fun matches(state: Bundle, babyId: Int?, editId: Int?): Boolean =
        state.getInt("edit_id") == (editId ?: 0) &&
            (babyId == null || state.getInt("baby_id") == 0 || state.getInt("baby_id") == babyId)

    fun saveHierarchy(root: View, state: Bundle) {
        val hierarchy = SparseArray<Parcelable>()
        root.saveHierarchyState(hierarchy)
        state.putSparseParcelableArray("hierarchy", hierarchy)
    }

    @Suppress("DEPRECATION")
    fun restoreHierarchy(root: View, state: Bundle) {
        state.getSparseParcelableArray<Parcelable>("hierarchy")?.let(root::restoreHierarchyState)
    }

    /** 用原始输入检测尚未失焦的动态详情，避免仅监听已提交到 ViewModel 的数值。 */
    fun inputValues(root: View): ArrayList<String> = arrayListOf<String>().apply {
        fun visit(view: View) {
            when (view) {
                is EditText -> add("${view.id}:${view.text}")
                is RadioGroup -> add("${view.id}:${view.checkedRadioButtonId}")
                is Spinner -> add("${view.id}:${view.selectedItemPosition}")
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
    }

    fun saveTimer(panel: RecordTimerPanelView, controller: RecordTimerController?): Bundle = Bundle().apply {
        val now = controller?.synchronizeClock() ?: TimerClock.snapshot()
        putLong("start", panel.startInput.getTimestamp())
        putLong("end", panel.endInput.getTimestamp())
        putString("start_raw", panel.startInput.text.toString())
        putString("end_raw", panel.endInput.text.toString())
        putLong("duration", panel.timerView.getDuration())
        putLong("captured_at", now.wallTimeMs)
        putBundle("captured_clock", now.toBundle())
        controller?.timelineClock?.let { putBundle("timeline_clock", it.toBundle()) }
        putBoolean("automatic_start", controller?.isStartTimeAutomatic == true)
        putString("ongoing_session_id", controller?.ongoingSessionId)
        putString("timer_state", panel.timerView.currentShowState.name)
        putLong("pause_end", controller?.originalPauseTimestamp ?: 0L)
    }

    /** Android 只恢复输入框文字，时间戳与计时状态必须显式恢复且不能触发新会话。 */
    fun restoreTimer(panel: RecordTimerPanelView, controller: RecordTimerController?, state: Bundle) {
        val now = TimerClock.snapshot()
        val captured = state.getBundle("captured_clock")?.toClock()
            ?: TimerClockSnapshot(state.getLong("captured_at"))
        val timeline = state.getBundle("timeline_clock")?.toClock() ?: captured
        val automatic = state.getBoolean("automatic_start")
        val recording = state.getString("timer_state") == RecordState.RECORDING.name
        val shift = if (automatic && recording) now.automaticWallShiftSince(timeline) else 0L
        val start = state.getLong("start").let { if (it > 0L) it + shift else 0L }
        val end = state.getLong("end").let { if (it > 0L) it + shift else 0L }
        val startRaw = state.getString("start_raw") ?: panel.startInput.text.toString()
        val endRaw = state.getString("end_raw") ?: panel.endInput.text.toString()
        if (start > 0L) controller?.setStartTime(start, notify = false)
        if (end > 0L) controller?.setEndTime(end, notify = false)
        controller?.restoreAutomaticStart(automatic, timeline.copy(wallTimeMs = timeline.wallTimeMs + shift),
            state.getString("ongoing_session_id"))
        val duration = state.getLong("duration").coerceAtLeast(0L)
        when (state.getString("timer_state")) {
            RecordState.RECORDING.name -> {
                val elapsed = now.elapsedSince(captured)
                panel.timerView.startFromOffset(duration + elapsed)
            }
            RecordState.PAUSE.name -> controller?.restorePausedSession(end, duration,
                state.getLong("pause_end").let { if (it > 0L) it + shift else 0L })
            else -> if (duration > 0L) panel.timerView.showDurationWithoutTimer(duration)
        }
        // 暂停恢复会写结束字段；最后恢复原始未完成输入，不能用旧时间覆盖用户输入的一半。
        panel.startInput.restoreInputState(start, startRaw)
        panel.endInput.restoreInputState(end, endRaw)
    }

    private fun TimerClockSnapshot.toBundle() = Bundle().apply {
        putLong("wall", wallTimeMs)
        elapsedRealtimeMs?.let { putLong("elapsed", it) }
        bootCount?.let { putInt("boot", it) }
        putString("process", processId)
    }

    private fun Bundle.toClock() = TimerClockSnapshot(
        wallTimeMs = getLong("wall"),
        elapsedRealtimeMs = if (containsKey("elapsed")) getLong("elapsed") else null,
        bootCount = if (containsKey("boot")) getInt("boot") else null,
        processId = getString("process")
    )
}
