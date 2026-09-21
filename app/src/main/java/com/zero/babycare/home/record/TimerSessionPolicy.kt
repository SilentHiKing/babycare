package com.zero.babycare.home.record

enum class TimerMode {
    FEEDING,
    SLEEP,
    ACTIVITY
}

enum class TimerStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED
}

enum class DurationSource {
    NONE,
    TIME_RANGE,
    ACTIVE_TIMER
}

data class TimerSessionState(
    val startAt: Long = 0L,
    val endAt: Long? = null,
    val status: TimerStatus = TimerStatus.IDLE,
    val activeDuration: Long = 0L,
    val displayDuration: Long = 0L,
    val durationSource: DurationSource = DurationSource.NONE
)

data class TimerDuration(
    val valueMs: Long,
    val source: DurationSource
)

object TimerSessionPolicy {

    fun calculateTimeRangeDuration(startAt: Long, endAt: Long?): TimerDuration {
        return if (startAt <= 0L || endAt == null || endAt <= startAt) {
            TimerDuration(0L, DurationSource.NONE)
        } else {
            TimerDuration(endAt - startAt, DurationSource.TIME_RANGE)
        }
    }

    fun calculateDisplayDuration(
        mode: TimerMode,
        state: TimerSessionState,
        now: Long = System.currentTimeMillis()
    ): TimerDuration {
        return calculateDurationForMode(
            mode = mode,
            startAt = state.startAt,
            endAt = state.endAt ?: now,
            activeDuration = state.activeDuration
        )
    }

    fun calculateSavedDuration(
        mode: TimerMode,
        startAt: Long,
        endAt: Long,
        activeDuration: Long
    ): TimerDuration {
        return calculateDurationForMode(
            mode = mode,
            startAt = startAt,
            endAt = endAt,
            activeDuration = activeDuration
        )
    }

    fun shouldResumeFromStart(mode: TimerMode): Boolean {
        return false
    }

    private fun calculateDurationForMode(
        mode: TimerMode,
        startAt: Long,
        endAt: Long,
        activeDuration: Long
    ): TimerDuration {
        val timeRangeDuration = calculateTimeRangeDuration(startAt, endAt)
        if (timeRangeDuration.source == DurationSource.NONE) {
            return timeRangeDuration
        }

        // 所有计时方式统一扣除明确暂停，无片段的手工区间才使用首尾差。
        return if (activeDuration > 0L && activeDuration <= timeRangeDuration.valueMs) {
            TimerDuration(activeDuration, DurationSource.ACTIVE_TIMER)
        } else {
            timeRangeDuration
        }
    }
}
