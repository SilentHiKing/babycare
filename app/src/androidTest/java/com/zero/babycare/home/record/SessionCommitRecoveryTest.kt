package com.zero.babycare.home.record

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.domain.*
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyDatabase
import com.zero.babydata.room.BabyRepository
import com.zero.common.util.TimerClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 真实 Room 与页面共用协调器验证停止、失败恢复和相同 ID 完成，不再伪造 MMKV 会话。 */
@RunWith(AndroidJUnit4::class)
class SessionCommitRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var database: BabyDatabase
    private lateinit var repository: BabyRepository
    private lateinit var scope: CoroutineScope
    private val base = TimerClock.snapshot().let { it.copy(wallTimeMs = it.wallTimeMs - 1_200_000L,
        elapsedRealtimeMs = it.elapsedRealtimeMs?.minus(1_200_000L)) }
    private fun clock(minutes: Int) = base.copy(wallTimeMs = base.wallTimeMs + minutes * 60_000L,
        elapsedRealtimeMs = base.elapsedRealtimeMs?.plus(minutes * 60_000L))

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, BabyDatabase::class.java).build()
        repository = BabyRepository(database)
        repository.insertBabyInfo(BabyInfo(babyId = 91, name = "合成计时恢复"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    @After fun tearDown() { scope.cancel(); database.close() }

    @Test fun completedSessionRestoresAsSuccessAndKeepsOriginalRecordId() = runBlocking {
        var snapshot = repository.timers.startSleep(SleepRecord(babyId = 91, sleepStart = base.wallTimeMs), base, true)
        val originalId = snapshot.ref.recordId
        snapshot = repository.timers.pause(snapshot.ref, clock(5))
        snapshot = repository.timers.resume(snapshot.ref, clock(10))
        snapshot = repository.timers.stop(snapshot.ref, clock(15))
        val editor = PersistentTimerEditor(scope, { repository }, TimerRecordKind.SLEEP)
        val coordinator = SessionCommitCoordinator(scope, { repository }, RecordType.SLEEP, editor)
        val target = target()
        onMain { coordinator.recover(target, snapshot.ref.sessionId) }
        withTimeout(5_000) { coordinator.recovery.filterNotNull().first { !it.loading } }.also { assertNull(it.errorRes) }
        coordinator.saveSleep(requireNotNull(snapshot.sleep).copy(note = "完成"), target.draftId, snapshot.ref.sessionId)
        val saved = repository.getAllSleepRecords(91).single()
        assertEquals(originalId, saved.sleepId)
        assertEquals(600_000L, saved.sleepDuration)
        assertEquals(2, TimerMetadataCodec.decode(saved.timerData)!!.segments.size)
        val restored = SessionCommitCoordinator(scope, { repository }, RecordType.SLEEP)
        onMain { restored.recover(target(), snapshot.ref.sessionId) }
        val result = withTimeout(5_000) { restored.recovery.filterNotNull().first { !it.loading } }
        assertTrue(result.committed)
        assertNull(result.errorRes)
        assertEquals(1, repository.getAllSleepRecords(91).size)
    }

    @Test fun failedCompletionRetainsStoppedTimeAndRetryFinishesSameRow() = runBlocking {
        var snapshot = repository.timers.startSleep(SleepRecord(babyId = 91, sleepStart = base.wallTimeMs), base, true)
        snapshot = repository.timers.pause(snapshot.ref, clock(5))
        val editor = PersistentTimerEditor(scope, { repository }, TimerRecordKind.SLEEP)
        onMain { editor.adopt(snapshot, 91) }
        val stopped = requireNotNull(editor.stopForSave(snapshot.ref.sessionId))
        try {
            editor.finishSleep(requireNotNull(stopped.sleep).copy(babyId = 92), "ignored", snapshot.ref.sessionId)
            fail("不能把旧计时写入另一宝宝")
        } catch (_: TimerOperationException) { }
        val recovered = requireNotNull(repository.timers.getBySession(TimerRecordKind.SLEEP, 91, snapshot.ref.sessionId))
        assertEquals(com.zero.babydata.domain.TimerStatus.STOPPED, recovered.status)
        assertEquals(clock(5).wallTimeMs, recovered.endAt)
        assertTrue(repository.getAllSleepRecords(91).isEmpty())
        editor.finishSleep(requireNotNull(recovered.sleep), "ignored", snapshot.ref.sessionId)
        assertEquals(snapshot.ref.recordId, repository.getAllSleepRecords(91).single().sleepId)
    }

    @Test fun reloadKeepsPauseAndDoesNotAddTimeSpentAway() = runBlocking {
        val started = repository.timers.startSleep(SleepRecord(babyId = 91, sleepStart = base.wallTimeMs), base, true)
        val paused = repository.timers.pause(started.ref, clock(5))
        val editor = PersistentTimerEditor(scope, { repository }, TimerRecordKind.SLEEP)
        onMain { editor.open(91, expectedSessionId = paused.ref.sessionId) }
        val state = withTimeout(5_000) { editor.state.first { !it.busy && it.snapshot != null } }
        assertEquals(com.zero.babydata.domain.TimerStatus.PAUSED, state.snapshot!!.status)
        assertEquals(300_000L, TimerCalculations.totalDuration(state.snapshot!!.metadata, clock(50)))
    }

    @Test fun failedConflictRefreshPreservesSnapshotAndClearsBusyWithoutUncaughtException() = runBlocking {
        val stale = repository.timers.startSleep(SleepRecord(babyId = 91, sleepStart = base.wallTimeMs), base, true)
        val paused = repository.timers.pause(stale.ref, clock(5))
        val current = repository.timers.resume(paused.ref, clock(10))
        val providerCalls = AtomicInteger()
        val uncaught = CompletableDeferred<Throwable>()
        val guardedScope = CoroutineScope(scope.coroutineContext + CoroutineExceptionHandler { _, failure ->
            uncaught.complete(failure)
        })
        val editor = PersistentTimerEditor(guardedScope, {
            // 首次命令真实命中过期版本，只有冲突后的恢复读取模拟外部访问失败。
            if (providerCalls.incrementAndGet() == 1) repository else error("合成恢复读取失败")
        }, TimerRecordKind.SLEEP)
        onMain { editor.adopt(stale, 91); editor.pause() }
        withTimeout(5_000L) { requireNotNull(scope.coroutineContext[Job]).children.toList().joinAll() }
        assertEquals(2, providerCalls.get())
        assertFalse("恢复异常必须由编辑器转换为错误状态", uncaught.isCompleted)
        val result = editor.state.value
        assertFalse(result.busy)
        assertEquals(stale, result.snapshot)
        assertEquals(com.zero.common.R.string.save_failed, result.errorRes)
        assertEquals(current, repository.timers.getBySession(TimerRecordKind.SLEEP, 91, stale.ref.sessionId))
    }

    private fun target() = RecordMutationController.Target(UUID.randomUUID().toString(), 91, null,
        NavTarget.SleepRecord(), NavTarget.Dashboard)
    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync(block) }
}
