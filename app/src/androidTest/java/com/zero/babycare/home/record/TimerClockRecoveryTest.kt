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

    @Test fun cancellationReplansOnlyOnceAndScopeCleanupDoesNotSchedule() {
        val babyId = 1_900_000_321
        val keys = RecordType.entries.associateWith { "ongoing_session_${it.name.lowercase()}_$babyId" }
        keys.values.forEach { assertTrue("合成键不能覆盖现有会话", MMKVStore.getString(it).isNullOrEmpty()) }
        try {
            RecordType.entries.forEach { type ->
                MMKVStore.put(keys.getValue(type), OngoingSession.running(babyId, type, start.wallTimeMs, now = start), encrypt = true)
                var requests = 0
                OngoingRecordManager.cancelSession(babyId, type) { requests++ }
                assertEquals(1, requests)
                OngoingRecordManager.cancelSession(babyId, type) { requests++ }
                assertEquals("重复取消不能重复调度", 1, requests)
                MMKVStore.put(keys.getValue(type), OngoingSession.running(babyId, type, start.wallTimeMs, now = start), encrypt = true)
                // 删除/覆盖的按宝宝清理由外层事务统一善后，不能冒充用户取消而单独调度。
                OngoingRecordManager.clearBaby(babyId)
                OngoingRecordManager.cancelSession(babyId, type) { requests++ }
                assertEquals(1, requests)
                assertTrue(MMKVStore.getString(keys.getValue(type)).isNullOrEmpty())
            }
        } finally {
            keys.values.forEach { MMKVStore.remove(it) }
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

    @Test fun separateClockSamplesUseExactSessionIdentityForSaveAndRejectReplacement() {
        val babyId = 1_900_000_322
        val key = "ongoing_session_feeding_$babyId"
        assertTrue("合成键不能覆盖现有会话", MMKVStore.getString(key).isNullOrEmpty())
        try {
            instrumentation.runOnMainSync {
                val now = TimerClock.snapshot()
                val controllerAnchor = now.copy(wallTimeMs = now.wallTimeMs - 900_000L + 3_600_000L,
                    elapsedRealtimeMs = now.elapsedRealtimeMs!! - 900_000L)
                val managerAnchor = controllerAnchor.copy(wallTimeMs = controllerAnchor.wallTimeMs + 2L)
                val original = OngoingSession.running(babyId, RecordType.FEEDING, controllerAnchor.wallTimeMs,
                    automaticStart = true, now = managerAnchor)
                MMKVStore.put(key, original, encrypt = true)
                val context = ContextThemeWrapper(app, com.zero.common.R.style.AppTheme_Boy)
                val timer = RecordView(context, clock = { now })
                val from = TimeEditText(context)
                val until = TimeEditText(context)
                val controller = RecordTimerController(context, timer, from, until, View(context), View(context),
                    RecordTimerController.Config(com.zero.common.R.string.end_time_must_after_start),
                    RecordTimerController.Callbacks(
                        onClockSynchronized = { id, timestamp, clock ->
                            OngoingRecordManager.synchronizeAutomaticStart(babyId, RecordType.FEEDING, id, timestamp, clock)
                        },
                        onTimerPause = { end -> OngoingRecordManager.pauseFeeding(babyId, end, timer.getDuration(), original.sessionId) }
                    ), clock = { now })
                try {
                    controller.setStartTime(original.startAt)
                    controller.restoreAutomaticStart(true, controllerAnchor, original.sessionId)
                    timer.startFromOffset(900_000L)
                    timer.forcePause(triggerCallback = true)
                    val submitted = requireNotNull(OngoingRecordManager.getFeedingSession(babyId))
                    assertEquals("校正后起点须精确匹配，不使用时间容差", from.getTimestamp(), submitted.startAt)
                    assertEquals(original.sessionId, submitted.sessionId)
                    runBlocking { OngoingSessionSavePolicy.save(submitted, {},
                        { OngoingRecordManager.getFeedingSession(babyId) }, { OngoingRecordManager.endFeeding(babyId) }) }
                    assertNull(OngoingRecordManager.getFeedingSession(babyId))
                    val replacement = OngoingSession.running(babyId, RecordType.FEEDING, now.wallTimeMs,
                        automaticStart = true, now = now)
                    MMKVStore.put(key, replacement, encrypt = true)
                    OngoingRecordManager.synchronizeAutomaticStart(babyId, RecordType.FEEDING,
                        requireNotNull(original.sessionId), from.getTimestamp(), now)
                    OngoingRecordManager.pauseFeeding(babyId, now.wallTimeMs, 900_000L, original.sessionId)
                    runBlocking { OngoingSessionSavePolicy.save(submitted, {},
                        { OngoingRecordManager.getFeedingSession(babyId) }, { OngoingRecordManager.endFeeding(babyId) }) }
                    val remaining = requireNotNull(OngoingRecordManager.getFeedingSession(babyId))
                    assertEquals(replacement.sessionId, remaining.sessionId)
                    assertEquals(SessionStatus.RUNNING, remaining.status)
                    OngoingRecordManager.updateFeedingStart(babyId, remaining.startAt, remaining.sessionId)
                    assertEquals("手工确认原值只改变日期来源，累计与身份不能被改写",
                        remaining.copy(automaticStart = false), OngoingRecordManager.getFeedingSession(babyId))
                } finally { controller.dispose(); timer.release() }
            }
        } finally { OngoingRecordManager.cancelSession(babyId, RecordType.FEEDING) {} }
    }
}
