package com.zero.babydata.domain

import com.zero.common.util.TimerClockSnapshot
import org.junit.Assert.*
import org.junit.Test

/** 使用显式时钟证明有效片段，不用真实等待或依赖 Android 时钟。 */
class TimerTransitionsTest {
    private fun clock(time: Long, elapsed: Long = time, boot: Int = 1) = TimerClockSnapshot(time, elapsed, boot, "process-$boot")

    @Test
    fun pauseExcludesGapAndSideChangeKeepsIndependentDurations() {
        var metadata = TimerTransitions.start(10_000, clock(10_000), TimerPart.LEFT, true)
        metadata = TimerTransitions.pause(metadata, clock(20_000))
        metadata = TimerTransitions.resume(metadata, clock(40_000), TimerPart.RIGHT)
        metadata = TimerTransitions.pause(metadata, clock(55_000))
        TimerMetadataCodec.validate(metadata, completed = true, kind = TimerRecordKind.FEEDING)
        assertEquals(25_000L, TimerCalculations.totalDuration(metadata))
        assertEquals(10_000L, TimerCalculations.partDuration(metadata, TimerPart.LEFT))
        assertEquals(15_000L, TimerCalculations.partDuration(metadata, TimerPart.RIGHT))
        assertEquals(0L, TimerCalculations.overlapDuration(metadata, 20_000, 39_999))
        assertEquals(5_000L, TimerCalculations.overlapDuration(metadata, 50_000, 59_999))
        assertEquals(metadata, TimerMetadataCodec.decode(TimerMetadataCodec.encode(metadata)))
        val zeroTail = metadata.copy(segments = metadata.segments + TimerSegment(70_000, 70_000, 0, TimerPart.RIGHT))
        assertEquals(55_000L, TimerCalculations.lastEffectiveEnd(zeroTail))
        assertEquals(25_000L, TimerCalculations.overlapDuration(zeroTail, Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun confirmingSameEndKeepsPauseFactsAndEditingBoundaryOnlyChangesAdjacentSegment() {
        var metadata = TimerTransitions.start(10_000, clock(10_000), TimerPart.SLEEP, true)
        metadata = TimerTransitions.pause(metadata, clock(20_000))
        metadata = TimerTransitions.resume(metadata, clock(40_000), TimerPart.SLEEP)
        metadata = TimerTransitions.pause(metadata, clock(50_000))
        assertSame(metadata, TimerTransitions.editEnd(metadata, 50_000, clock(60_000)))
        val edited = TimerTransitions.editStart(metadata, 15_000, clock(60_000))
        assertEquals(15_000L, TimerCalculations.totalDuration(edited))
        assertEquals(metadata.segments.last(), edited.segments.last())
        assertFails { TimerTransitions.editStart(metadata, 25_000, clock(60_000)) }
    }

    @Test
    fun clockRollbackOrBootChangePreservesElapsedButRequiresExplicitReview() {
        val start = TimerTransitions.start(100_000, clock(100_000, 1_000), TimerPart.ACTIVITY, true)
        val rollback = TimerTransitions.pause(start, clock(90_000, 11_000))
        assertEquals(10_000L, TimerCalculations.totalDuration(rollback))
        assertTrue(rollback.requiresReview)
        assertFails(TimerFailure.NEEDS_REVIEW) { TimerMetadataCodec.validate(rollback, completed = true) }
        val reboot = TimerTransitions.pause(start, clock(120_000, 500, 2))
        assertTrue(reboot.requiresReview)
        assertFails { TimerMetadataCodec.validate(reboot.copy(requiresReview = false)) }
    }

    @Test
    fun malformedMetadataCannotClaimPreciseZeroOrOverlappingSegments() {
        assertFails { TimerMetadataCodec.decode("{\"nested\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}") }
        assertFails { TimerMetadataCodec.validate(TimerMetadata(segments = listOf(TimerSegment(10, 20, 0)))) }
        assertFails { TimerMetadataCodec.validate(TimerMetadata(segments = listOf(TimerSegment(10, 20, 10), TimerSegment(15, 30, 15)))) }
        TimerMetadataCodec.validateRecord(TimerMetadata(basis = DurationBasis.UNMEASURED), TimerRecordKind.FEEDING, 10, 0, 0)
    }

    private fun assertFails(reason: TimerFailure = TimerFailure.INVALID_DATA, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue(failure is TimerOperationException)
        assertEquals(reason, (failure as TimerOperationException).reason)
    }
}
