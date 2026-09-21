package com.zero.babycare.home.record

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.home.OngoingRecordManager.OngoingSession
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babycare.home.OngoingRecordManager.SessionStatus
import com.zero.common.mmkv.MMKVStore
import com.zero.common.util.TimerClockSnapshot
import com.zero.common.util.TimerClock
import com.zero.components.widget.RecordView
import com.zero.components.widget.TimeEditText
import com.zero.components.widget.TimerCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/** 使用可控时钟和独立合成会话，不更改系统时间、不写应用数据库或实际调度提醒。 */
@RunWith(AndroidJUnit4::class)
class TimerClockRecoveryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val start = TimerClockSnapshot(1_800_000_000_000L, 100_000L, 8, "test-process")
    private fun later(minutes: Int, jump: Long = 0L) = start.copy(
        wallTimeMs = start.wallTimeMs + minutes * 60_000L + jump,
        elapsedRealtimeMs = start.elapsedRealtimeMs!! + minutes * 60_000L
    )

    @Test fun counterPreservesAccumulatedTimeWhenWallClockMovesBackwardOrForward() {
        instrumentation.runOnMainSync {
            var now = start
            val timer = TimerCounter { now }
            try {
                timer.start(fromInit = true)
                now = later(10)
                timer.pause()
                assertEquals(600_000L, timer.getDuration())
                now = later(20)
                timer.start()
                now = later(25, -3_600_000L)
                assertEquals(900_000L, timer.getDuration())
                timer.pause()
                assertEquals(900_000L, timer.getDuration())
                now = later(30, 3_600_000L)
                timer.start()
                now = later(35, 7_200_000L)
                assertEquals(1_200_000L, timer.getDuration())
            } finally { timer.release() }
        }
    }

    @Test fun forcedPauseKeepsRealDurationAndOnlyCorrectsAutomaticallyChosenDate() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(app, com.zero.common.R.style.AppTheme_Boy)
            listOf(true, false).forEach { automatic ->
                var now = start
                val timer = RecordView(context, clock = { now })
                val from = TimeEditText(context)
                val until = TimeEditText(context)
                var session = OngoingSession.running(1_900_000_321, RecordType.FEEDING, start.wallTimeMs,
                    automaticStart = automatic, now = start)
                val controller = RecordTimerController(context, timer, from, until, View(context), View(context),
                    RecordTimerController.Config(com.zero.common.R.string.end_time_must_after_start),
                    RecordTimerController.Callbacks(onTimerPause = { end ->
                        session = session.paused(end, timer.getDuration(), now)
                    }), clock = { now })
                try {
                    controller.setStartTime(start.wallTimeMs)
                    controller.restoreAutomaticStart(automatic, start)
                    timer.startFromOffset(0L)
                    now = later(15, -3_600_000L)
                    // 保存路径先改变 View 状态再回调，不能只验证普通点击暂停的顺序。
                    timer.forcePause(triggerCallback = true)
                    assertEquals(900_000L, timer.getDuration())
                    assertEquals(SessionStatus.PAUSED, session.status)
                    assertEquals(if (automatic) start.wallTimeMs - 3_600_000L else start.wallTimeMs, from.getTimestamp())
                    assertEquals(from.getTimestamp(), session.startAt)
                    assertEquals(now.wallTimeMs, until.getTimestamp())
                    val restored = Gson().fromJson(Gson().toJson(session), OngoingSession::class.java)
                    assertEquals(900_000L, restored.elapsedDuration(later(40)))
                    assertFalse(timer.isTimerRunning())
                } finally {
                    controller.dispose()
                    timer.release()
                }
            }
        }
    }


    @Test fun firstEndSelectionUpdatesDurationAndPersistsManualPause() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(app, com.zero.common.R.style.AppTheme_Boy)
            var now = start
            val timer = RecordView(context, clock = { now })
            val from = TimeEditText(context)
            val until = TimeEditText(context)
            var session = OngoingSession.running(1_900_000_321, RecordType.FEEDING, start.wallTimeMs, now = start)
            var pauses = 0
            val controller = RecordTimerController(context, timer, from, until, View(context), View(context),
                RecordTimerController.Config(com.zero.common.R.string.end_time_must_after_start),
                RecordTimerController.Callbacks(onTimerPause = { end ->
                    pauses++
                    session = session.paused(end, timer.getDuration(), now)
                }), clock = { now })
            val applyEnd = RecordTimerController::class.java.getDeclaredMethod("applyEndTimeChange", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            try {
                controller.setStartTime(start.wallTimeMs)
                assertFalse(until.hasValidTime())
                applyEnd.invoke(controller, later(5).wallTimeMs)
                assertEquals("首次从空字段选择结束也应立即显示区间", 300_000L, timer.getDuration())
                assertEquals("编辑历史区间不能触及实时会话", 0, pauses)
                timer.startFromOffset(0L)
                now = later(15)
                applyEnd.invoke(controller, later(10).wallTimeMs)
                assertEquals(1, pauses)
                assertEquals(RecordView.RecordState.PAUSE, timer.currentShowState)
                assertEquals(600_000L, timer.getDuration())
                assertEquals(SessionStatus.PAUSED, session.status)
                assertEquals(later(10).wallTimeMs, session.endAt)
                assertEquals(600_000L, session.elapsedDuration(later(30)))
                assertTrue("手改结束后继续仍需确认", RecordTimerController::class.java
                    .getDeclaredMethod("isEndTimeManuallyModified").apply { isAccessible = true }.invoke(controller) as Boolean)
            } finally { controller.dispose(); timer.release() }
        }
    }

    @Test fun queuedStartCannotMutateResetOrDisposedController() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(app, com.zero.common.R.style.AppTheme_Boy)
            listOf(false, true).forEach { dispose ->
                val timer = RecordView(context, clock = { start })
                val from = TimeEditText(context)
                val until = TimeEditText(context)
                var starts = 0
                val controller = RecordTimerController(context, timer, from, until, View(context), View(context),
                    RecordTimerController.Config(com.zero.common.R.string.end_time_must_after_start),
                    RecordTimerController.Callbacks(onTimerStart = { starts++ }), clock = { start })
                try {
                    timer.performClick()
                    val task = RecordTimerController::class.java.getDeclaredField("pendingTimerStart")
                        .apply { isAccessible = true }.get(controller) as Runnable
                    if (dispose) controller.dispose() else controller.reset()
                    // 即使平台已取出旧 Runnable，主动回放也不能更新新输入或重建持久会话。
                    task.run()
                    assertEquals(0, starts)
                    assertEquals(0L, from.getTimestamp())
                } finally { controller.dispose(); timer.release() }
            }
        }
    }


    @Test fun persistentClicksWaitForDatabaseAndUnchangedEndPreservesPausedDuration() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(app, com.zero.common.R.style.AppTheme_Boy)
            val timer = RecordView(context, clock = { later(20) })
            val from = TimeEditText(context)
            val until = TimeEditText(context)
            var starts = 0
            var edits = 0
            val controller = RecordTimerController(context, timer, from, until, View(context), View(context),
                RecordTimerController.Config(com.zero.common.R.string.end_time_must_after_start, persistent = true),
                RecordTimerController.Callbacks(onTimerStart = { starts++ }, onEndTimeChanged = { edits++ }),
                clock = { later(20) })
            try {
                timer.performClick()
                assertEquals(1, starts)
                assertEquals("数据库提交前只能发送命令", RecordView.RecordState.INIT, timer.currentShowState)
                val metadata = com.zero.babydata.domain.TimerMetadata(segments = listOf(
                    com.zero.babydata.domain.TimerSegment(start.wallTimeMs, later(5).wallTimeMs, 300_000L),
                    com.zero.babydata.domain.TimerSegment(later(10).wallTimeMs, later(15).wallTimeMs, 300_000L)))
                val snapshot = com.zero.babydata.domain.TimerRecordSnapshot(
                    com.zero.babydata.domain.TimerRef(com.zero.babydata.domain.TimerRecordKind.FEEDING, 91, 1, "test-session", 4),
                    com.zero.babydata.domain.TimerStatus.PAUSED, metadata,
                    feeding = com.zero.babydata.entity.FeedingRecord(babyId = 91, feedingStart = start.wallTimeMs,
                        feedingEnd = later(15).wallTimeMs, feedingDuration = 600_000L))
                controller.renderPersistent(snapshot)
                RecordTimerController::class.java.getDeclaredMethod("applyEndTimeChange", Long::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(controller, later(15).wallTimeMs)
                assertEquals(0, edits)
                assertEquals("确认原值不能把五分钟暂停加回去", 600_000L, timer.getDuration())
            } finally { controller.dispose(); timer.release() }
        }
    }

}
