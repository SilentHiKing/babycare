package com.zero.babycare.home

import com.zero.babycare.home.OngoingRecordManager.OngoingSession
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babycare.home.OngoingRecordManager.SessionStatus
import com.zero.babycare.home.bean.DashboardData
import com.zero.babydata.domain.RecordDurationCalculator
import com.zero.babydata.domain.TimerCalculations
import com.zero.babydata.domain.TimerSegmentSource
import com.zero.babydata.domain.TimerTransitions
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

        fun OngoingSession.displayMetadata() = snapshot?.metadata?.let {
            if (status == SessionStatus.RUNNING) TimerTransitions.pause(it, clock) else it
        }

        fun OngoingSession.duration(): Long {
            snapshot?.let {
                return TimerCalculations.totalDuration(it.metadata,
                    if (status == SessionStatus.RUNNING) clock else null)
            }
            return elapsedDuration(clock)
        }

        fun OngoingSession.todayMinutes(): Long {
            displayMetadata()?.let { metadata ->
                // 开放段只在显示副本中闭合，不因首页每分钟刷新向数据库写结束时间。
                // 时钟异常部分尚不能准确归日，已确认的历史段仍保留；待核对状态另行明确展示。
                val confirmed = metadata.copy(clockAnchor = null, requiresReview = false,
                    segments = metadata.segments.filter { it.endAt != null &&
                        it.source != TimerSegmentSource.ESTIMATED && it.durationMs == requireNotNull(it.endAt) - it.startAt })
                return TimeUnit.MILLISECONDS.toMinutes(TimerCalculations.overlapDuration(confirmed, dayStart, dayEnd))
            }
            val end = if (status != SessionStatus.RUNNING) endAt ?: startAt else clock.wallTimeMs
            val activeDuration = duration()
            if (activeDuration <= 0L) return 0L
            return TimeUnit.MILLISECONDS.toMinutes(
                RecordDurationCalculator.overlappedDuration(startAt, end, activeDuration, dayStart, dayEnd)
            )
        }

        fun OngoingSession.state(): DashboardData.OngoingState = when {
            displayMetadata()?.requiresReview == true -> DashboardData.OngoingState.NEEDS_REVIEW
            status == SessionStatus.RUNNING -> DashboardData.OngoingState.RUNNING
            status == SessionStatus.PAUSED -> DashboardData.OngoingState.PAUSED
            else -> DashboardData.OngoingState.STOPPED
        }
        // 正在发生的照护优先于更晚开始但已暂停/结束的草稿。
        val headline = listOfNotNull(currentFeeding, currentSleep).maxWithOrNull(
            compareBy<OngoingSession> { it.status == SessionStatus.RUNNING }.thenBy { it.startAt }
        )

        return data.copy(
            currentStatus = when (headline?.type) {
                RecordType.FEEDING -> DashboardData.BabyStatus.FEEDING
                RecordType.SLEEP -> DashboardData.BabyStatus.SLEEPING
                null -> DashboardData.BabyStatus.AWAKE
            },
            ongoingFeedingDurationMillis = currentFeeding?.duration(),
            ongoingSleepDurationMillis = currentSleep?.duration(),
            ongoingFeedingState = currentFeeding?.state(),
            ongoingSleepState = currentSleep?.state(),
            displayFeedingMinutes = data.totalFeedingMinutes + (currentFeeding?.todayMinutes() ?: 0L),
            displaySleepMinutes = data.totalSleepMinutes + (currentSleep?.todayMinutes() ?: 0L)
        )
    }
}
