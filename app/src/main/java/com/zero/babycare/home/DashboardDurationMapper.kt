package com.zero.babycare.home

import com.zero.babycare.home.OngoingRecordManager.OngoingSession
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babycare.home.OngoingRecordManager.SessionStatus
import com.zero.babycare.home.bean.DashboardData
import com.zero.babydata.domain.RecordDurationCalculator
import com.zero.common.util.TimerClockSnapshot
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** 进行中时长与保存时长使用同一归属规则，View 只负责格式化结果。 */
internal object DashboardDurationMapper {

    fun update(
        data: DashboardData,
        feeding: OngoingSession?,
        sleep: OngoingSession?,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        clock: TimerClockSnapshot = TimerClockSnapshot(now)
    ): DashboardData {
        val currentFeeding = feeding?.reconcileClock(clock)
        val currentSleep = sleep?.reconcileClock(clock)
        val day = Instant.ofEpochMilli(clock.wallTimeMs).atZone(zone).toLocalDate()
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L

        fun OngoingSession.duration(): Long {
            return elapsedDuration(clock)
        }

        fun OngoingSession.todayMinutes(): Long {
            val end = if (status == SessionStatus.PAUSED) endAt ?: startAt else clock.wallTimeMs
            val activeDuration = duration()
            if (activeDuration <= 0L) return 0L
            return TimeUnit.MILLISECONDS.toMinutes(
                RecordDurationCalculator.overlappedDuration(startAt, end, activeDuration, dayStart, dayEnd)
            )
        }

        return data.copy(
            ongoingFeedingDurationMillis = currentFeeding?.duration(),
            ongoingSleepDurationMillis = currentSleep?.duration(),
            displayFeedingMinutes = data.totalFeedingMinutes + (currentFeeding?.todayMinutes() ?: 0L),
            displaySleepMinutes = data.totalSleepMinutes + (currentSleep?.todayMinutes() ?: 0L)
        )
    }
}
