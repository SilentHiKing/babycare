package com.zero.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerClockSnapshotTest {
    private val anchor = TimerClockSnapshot(10_000_000L, 100_000L, 4, "old-process")

    @Test fun `same boot preserves elapsed time across process and wall clock changes`() {
        listOf(-3_600_000L, 3_600_000L).forEach { jump ->
            val now = TimerClockSnapshot(anchor.wallTimeMs + 300_000L + jump, 400_000L, 4, "new-process")
            assertEquals(300_000L, now.elapsedSince(anchor))
            assertEquals(jump, now.automaticWallShiftSince(anchor))
        }
    }

    @Test fun `same process stays monotonic before boot identity is ready`() {
        val pending = anchor.copy(bootCount = null)
        val now = pending.copy(wallTimeMs = 1L, elapsedRealtimeMs = 400_000L)
        assertTrue(now.sharesMonotonicClock(pending))
        assertEquals(300_000L, now.elapsedSince(pending))
    }

    @Test fun `different boot and legacy data fall back without subtracting accumulated time`() {
        val now = TimerClockSnapshot(anchor.wallTimeMs - 3_600_000L, 500_000L, 5, "new-process")
        listOf(anchor, TimerClockSnapshot(anchor.wallTimeMs)).forEach { previous ->
            assertFalse(now.sharesMonotonicClock(previous))
            assertEquals(600_000L, 600_000L + now.elapsedSince(previous))
        }
        assertEquals(60_000L, now.copy(wallTimeMs = anchor.wallTimeMs + 60_000L).elapsedSince(anchor))
    }
}
