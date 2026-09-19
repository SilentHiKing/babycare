package com.zero.babycare.home.record

import com.google.gson.Gson
import com.zero.babycare.home.OngoingRecordManager.OngoingSession
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babycare.home.OngoingRecordManager.SessionStatus
import com.zero.common.util.TimerClockSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合成时钟直接覆盖持久化会话转换，不调整设备时间。 */
class OngoingSessionClockTest {
    private val start = TimerClockSnapshot(10_000_000L, 100_000L, 7, "process-a")
    private fun later(minutes: Int, wallJump: Long = 0L, process: String = "process-a") = start.copy(
        wallTimeMs = start.wallTimeMs + minutes * 60_000L + wallJump,
        elapsedRealtimeMs = start.elapsedRealtimeMs!! + minutes * 60_000L,
        processId = process
    )

    @Test fun `rollback preserves accumulated feeding and paused state through serialization`() {
        val session = OngoingSession.running(91, RecordType.FEEDING, start.wallTimeMs,
            automaticStart = true, now = start)
            .paused(later(10).wallTimeMs, 600_000L, later(10))
            .resumed(later(20))
        val now = later(25, -3_600_000L)
        assertEquals(900_000L, session.elapsedDuration(now))
        val paused = session.paused(now.wallTimeMs, 900_000L, now)
        val restored = Gson().fromJson(Gson().toJson(paused), OngoingSession::class.java)
        assertEquals(SessionStatus.PAUSED, restored.status)
        assertEquals(900_000L, restored.elapsedDuration(later(30, -3_600_000L, "process-b")))
        assertTrue(restored.endAt!! > restored.startAt)
        assertEquals(25 * 60_000L, restored.endAt!! - restored.startAt)
    }

    @Test fun `sleep includes pause in running and recovered full interval`() {
        val resumed = OngoingSession.running(91, RecordType.SLEEP, start.wallTimeMs,
            automaticStart = true, now = start)
            .paused(later(10).wallTimeMs, 600_000L, later(10))
            .resumed(later(20))
        assertEquals(25 * 60_000L, resumed.elapsedDuration(later(25, process = "process-b")))
        assertEquals(25 * 60_000L, resumed.paused(later(25).wallTimeMs, 900_000L, later(25))
            .elapsedDuration(later(30)))
    }

    @Test fun `manual start never shifts and legacy reboot fallback never subtracts accumulated time`() {
        val manual = OngoingSession.running(91, RecordType.FEEDING, start.wallTimeMs,
            automaticStart = false, now = start)
        val rollback = later(5, -3_600_000L)
        assertEquals(start.wallTimeMs, manual.reconcileClock(rollback).startAt)
        assertEquals(SessionStatus.PAUSED, manual.paused(rollback.wallTimeMs, 300_000L, rollback).status)
        val legacy = OngoingSession(91, RecordType.FEEDING, start.wallTimeMs,
            status = SessionStatus.RUNNING, activeDurationMs = 600_000L, resumedAt = start.wallTimeMs)
        assertEquals(600_000L, legacy.reconcileClock(rollback.copy(bootCount = 8, processId = "reboot"))
            .elapsedDuration(rollback.copy(bootCount = 8, processId = "reboot")))
    }

    @Test fun `boot identity arriving after start upgrades only the same process anchor`() {
        val session = OngoingSession.running(91, RecordType.FEEDING, start.wallTimeMs,
            automaticStart = true, now = start.copy(bootCount = null))
        val upgraded = session.reconcileClock(later(5))
        assertEquals(7, upgraded.clockAnchor?.bootCount)
        assertEquals(600_000L, upgraded.elapsedDuration(later(10, -3_600_000L, "process-b")))
        val oldUnknown = session.reconcileClock(later(5, -3_600_000L, "process-b"))
        assertEquals(0L, oldUnknown.activeDurationMs)
    }
}
