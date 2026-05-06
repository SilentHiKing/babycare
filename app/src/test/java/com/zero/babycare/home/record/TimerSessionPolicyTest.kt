package com.zero.babycare.home.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSessionPolicyTest {

    @Test
    fun `sleep saved duration always uses time range even when active duration is shorter`() {
        val state = createState(
            startAt = START_AT,
            endAt = START_AT + THIRTY_MINUTES,
            activeDuration = TEN_MINUTES
        )

        val result = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.SLEEP,
            startAt = state.startAt,
            endAt = state.endAt!!,
            activeDuration = state.activeDuration
        )

        assertEquals(THIRTY_MINUTES, result.valueMs)
        assertEquals(DurationSource.TIME_RANGE, result.source)
    }

    @Test
    fun `activity saved duration uses time range`() {
        val state = createState(
            startAt = START_AT,
            endAt = START_AT + THIRTY_MINUTES,
            activeDuration = TEN_MINUTES
        )

        val result = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.ACTIVITY,
            startAt = state.startAt,
            endAt = state.endAt!!,
            activeDuration = state.activeDuration
        )

        assertEquals(THIRTY_MINUTES, result.valueMs)
        assertEquals(DurationSource.TIME_RANGE, result.source)
    }

    @Test
    fun `feeding saved duration keeps valid active duration`() {
        val state = createState(
            startAt = START_AT,
            endAt = START_AT + THIRTY_MINUTES,
            activeDuration = TEN_MINUTES
        )

        val result = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.FEEDING,
            startAt = state.startAt,
            endAt = state.endAt!!,
            activeDuration = state.activeDuration
        )

        assertEquals(TEN_MINUTES, result.valueMs)
        assertEquals(DurationSource.ACTIVE_TIMER, result.source)
    }

    @Test
    fun `feeding saved duration falls back to time range when active duration exceeds range`() {
        val state = createState(
            startAt = START_AT,
            endAt = START_AT + TEN_MINUTES,
            activeDuration = THIRTY_MINUTES
        )

        val result = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.FEEDING,
            startAt = state.startAt,
            endAt = state.endAt!!,
            activeDuration = state.activeDuration
        )

        assertEquals(TEN_MINUTES, result.valueMs)
        assertEquals(DurationSource.TIME_RANGE, result.source)
    }

    @Test
    fun `invalid time range returns zero and none source`() {
        val state = createState(
            startAt = START_AT,
            endAt = START_AT - TEN_MINUTES,
            activeDuration = TEN_MINUTES
        )

        val result = TimerSessionPolicy.calculateSavedDuration(
            mode = TimerMode.FEEDING,
            startAt = state.startAt,
            endAt = state.endAt!!,
            activeDuration = state.activeDuration
        )

        assertEquals(0L, result.valueMs)
        assertEquals(DurationSource.NONE, result.source)
    }

    @Test
    fun `sleep and activity should resume from start`() {
        assertTrue(TimerSessionPolicy.shouldResumeFromStart(TimerMode.SLEEP))
        assertTrue(TimerSessionPolicy.shouldResumeFromStart(TimerMode.ACTIVITY))
    }

    @Test
    fun `time range duration returns none when end time is absent`() {
        val result = TimerSessionPolicy.calculateTimeRangeDuration(
            startAt = START_AT,
            endAt = null
        )

        assertEquals(0L, result.valueMs)
        assertEquals(DurationSource.NONE, result.source)
    }

    @Test
    fun `sleep display duration uses now when end time is absent`() {
        val state = TimerSessionState(
            startAt = START_AT,
            endAt = null,
            status = TimerStatus.RUNNING,
            activeDuration = TEN_MINUTES
        )

        val result = TimerSessionPolicy.calculateDisplayDuration(
            mode = TimerMode.SLEEP,
            state = state,
            now = START_AT + THIRTY_MINUTES
        )

        assertEquals(THIRTY_MINUTES, result.valueMs)
        assertEquals(DurationSource.TIME_RANGE, result.source)
    }

    @Test
    fun `feeding display duration uses active duration when valid`() {
        val state = TimerSessionState(
            startAt = START_AT,
            endAt = START_AT + THIRTY_MINUTES,
            status = TimerStatus.PAUSED,
            activeDuration = TEN_MINUTES
        )

        val result = TimerSessionPolicy.calculateDisplayDuration(
            mode = TimerMode.FEEDING,
            state = state,
            now = START_AT + THIRTY_MINUTES
        )

        assertEquals(TEN_MINUTES, result.valueMs)
        assertEquals(DurationSource.ACTIVE_TIMER, result.source)
    }

    @Test
    fun `feeding display duration falls back to range when active duration is missing`() {
        val state = TimerSessionState(
            startAt = START_AT,
            endAt = START_AT + THIRTY_MINUTES,
            status = TimerStatus.PAUSED,
            activeDuration = 0L
        )

        val result = TimerSessionPolicy.calculateDisplayDuration(
            mode = TimerMode.FEEDING,
            state = state,
            now = START_AT + THIRTY_MINUTES
        )

        assertEquals(THIRTY_MINUTES, result.valueMs)
        assertEquals(DurationSource.TIME_RANGE, result.source)
    }

    private fun createState(
        startAt: Long,
        endAt: Long,
        activeDuration: Long
    ): TimerSessionState {
        return TimerSessionState(
            startAt = startAt,
            endAt = endAt,
            status = TimerStatus.COMPLETED,
            activeDuration = activeDuration,
            displayDuration = activeDuration,
            durationSource = DurationSource.ACTIVE_TIMER
        )
    }

    private companion object {
        private const val START_AT = 1_700_000_000_000L
        private const val TEN_MINUTES = 10 * 60 * 1000L
        private const val THIRTY_MINUTES = 30 * 60 * 1000L
    }
}
