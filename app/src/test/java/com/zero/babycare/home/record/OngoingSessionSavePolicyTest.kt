package com.zero.babycare.home.record

import com.zero.babycare.home.OngoingRecordManager.OngoingSession
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babycare.home.OngoingRecordManager.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OngoingSessionSavePolicyTest {
    @Test
    fun `new session created while persistence is suspended survives old completion`() {
        RecordType.entries.forEach { type ->
            val submitted = pausedSession(type)
            val newer = OngoingSession.running(41, type, 30_000L)
            assertCompletionKeepsChangedSession(submitted, newer)
        }
    }

    @Test
    fun `resuming submitted session while saving preserves the resumed timer`() {
        RecordType.entries.forEach { type ->
            val submitted = pausedSession(type)
            val resumed = submitted.copy(status = SessionStatus.RUNNING, endAt = null, resumedAt = 30_000L)
            assertCompletionKeepsChangedSession(submitted, resumed)
        }
    }

    @Test
    fun `unchanged submitted session ends only after successful persistence`() = runBlocking {
        RecordType.entries.forEach { type ->
            val submitted = pausedSession(type)
            var current: OngoingSession? = submitted.copy()
            var persisted = false
            OngoingSessionSavePolicy.save(submitted,
                persist = { persisted = true },
                currentSession = { current },
                finishSession = { check(persisted); current = null })
            assertNull(current)
        }
    }

    @Test
    fun `manual record without submitted session cannot clear another timer`() = runBlocking {
        val current = OngoingSession.running(41, RecordType.FEEDING, 30_000L)
        var cleared = false
        OngoingSessionSavePolicy.save(null, {}, { current }, { cleared = true })
        assertEquals(false, cleared)
    }

    private fun assertCompletionKeepsChangedSession(submitted: OngoingSession, changed: OngoingSession) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseWrite = CompletableDeferred<Unit>()
        var current: OngoingSession? = submitted
        try {
            scope.launch {
                OngoingSessionSavePolicy.save(submitted,
                    persist = { releaseWrite.await() },
                    currentSession = { current },
                    finishSession = { current = null })
            }
            current = changed
            releaseWrite.complete(Unit)
            assertEquals(changed, current)
        } finally {
            scope.cancel()
        }
    }

    private fun pausedSession(type: RecordType) = OngoingSession(
        babyId = 41, type = type, startAt = 10_000L, endAt = 20_000L,
        status = SessionStatus.PAUSED, activeDurationMs = 10_000L
    )
}
