package com.zero.babycare.home

import com.zero.babycare.home.OngoingRecordManager.OngoingSession
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babycare.home.OngoingRecordManager.SessionStatus
import com.zero.babycare.home.bean.DashboardData
import com.zero.babydata.domain.*
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.common.util.TimerClockSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DashboardDurationMapperTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val midnight = LocalDate.of(2026, 9, 20).atStartOfDay(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    @Test
    fun `wall rollback does not erase current automatic feeding duration`() {
        val start = TimerClockSnapshot(midnight + 120 * minute, 60_000L, 3, "first")
        val session = OngoingSession.running(1, RecordType.FEEDING, start.wallTimeMs,
            automaticStart = true, now = start)
        val now = start.copy(wallTimeMs = start.wallTimeMs - 45 * minute,
            elapsedRealtimeMs = 60_000L + 15 * minute, processId = "restored")
        val result = DashboardDurationMapper.update(DashboardData(), session, null,
            now.wallTimeMs, zone, now)
        assertEquals(15 * minute, result.ongoingFeedingDurationMillis)
        assertEquals(15L, result.displayFeedingMinutes)
    }

    @Test
    fun `paused feeding stays frozen and excludes pause time from today`() {
        val session = OngoingSession(
            babyId = 1, type = RecordType.FEEDING, startAt = midnight + 10 * minute,
            endAt = midnight + 40 * minute, status = SessionStatus.PAUSED,
            activeDurationMs = 20 * minute
        )
        val result = DashboardDurationMapper.update(
            DashboardData(totalFeedingMinutes = 15), session, null, midnight + 120 * minute, zone
        )
        assertEquals(20 * minute, result.ongoingFeedingDurationMillis)
        assertEquals(35L, result.displayFeedingMinutes)
        val later = DashboardDurationMapper.update(result, session, null, midnight + 180 * minute, zone)
        assertEquals(result, later)
    }

    @Test
    fun `resumed feeding combines saved active time with the current segment`() {
        val session = OngoingSession(
            babyId = 1, type = RecordType.FEEDING, startAt = midnight + 10 * minute,
            status = SessionStatus.RUNNING, activeDurationMs = 15 * minute,
            resumedAt = midnight + 50 * minute
        )
        val result = DashboardDurationMapper.update(
            DashboardData(), session, null, midnight + 60 * minute, zone
        )
        assertEquals(25 * minute, result.ongoingFeedingDurationMillis)
        assertEquals(25L, result.displayFeedingMinutes)
    }

    @Test
    fun `cross midnight paused sleep counts only its completed part today`() {
        val session = OngoingSession(
            babyId = 1, type = RecordType.SLEEP, startAt = midnight - 60 * minute,
            endAt = midnight + 30 * minute, status = SessionStatus.PAUSED,
            activeDurationMs = 90 * minute
        )
        val result = DashboardDurationMapper.update(
            DashboardData(), null, session, midnight + 120 * minute, zone
        )
        assertEquals(90 * minute, result.ongoingSleepDurationMillis)
        assertEquals(30L, result.displaySleepMinutes)
    }

    @Test
    fun `room paused sleep uses exact active intersections and remains frozen`() {
        val session = roomSession(TimerRecordKind.SLEEP, TimerStatus.PAUSED, listOf(
            segment(-20, -15, TimerPart.SLEEP), segment(10, 25, TimerPart.SLEEP)
        ))
        val result = DashboardDurationMapper.update(
            DashboardData(totalSleepMinutes = 30), null, session, midnight + 120 * minute, zone
        )
        assertEquals(20 * minute, result.ongoingSleepDurationMillis)
        assertEquals(45L, result.displaySleepMinutes)
        assertEquals(DashboardData.OngoingState.PAUSED, result.ongoingSleepState)
        assertEquals(result, DashboardDurationMapper.update(result, null, session, midnight + 300 * minute, zone))
    }

    @Test
    fun `running room feeding derives open segment without mutating persisted metadata`() {
        val anchor = clock(50)
        val session = roomSession(TimerRecordKind.FEEDING, TimerStatus.RUNNING, listOf(
            segment(10, 25, TimerPart.LEFT), TimerSegment(anchor.wallTimeMs, part = TimerPart.RIGHT)
        ), anchor)
        val original = session.snapshot!!.metadata
        val result = DashboardDurationMapper.update(
            DashboardData(), session, null, clock(60).wallTimeMs, zone, clock(60)
        )
        assertEquals(25 * minute, result.ongoingFeedingDurationMillis)
        assertEquals(25L, result.displayFeedingMinutes)
        assertEquals(DashboardData.OngoingState.RUNNING, result.ongoingFeedingState)
        assertEquals(original, session.snapshot.metadata)
        assertEquals(null, original.segments.last().endAt)
    }

    @Test
    fun `room clock anomaly retains measured total but does not attribute uncertain segment to today`() {
        val anchor = clock(50)
        val session = roomSession(TimerRecordKind.FEEDING, TimerStatus.RUNNING, listOf(
            segment(10, 25, TimerPart.LEFT), TimerSegment(anchor.wallTimeMs, part = TimerPart.RIGHT)
        ), anchor)
        val now = clock(60).copy(wallTimeMs = midnight + 15 * minute)
        val result = DashboardDurationMapper.update(DashboardData(), session, null, now.wallTimeMs, zone, now)
        assertEquals(25 * minute, result.ongoingFeedingDurationMillis)
        assertEquals(15L, result.displayFeedingMinutes)
        assertEquals(DashboardData.OngoingState.NEEDS_REVIEW, result.ongoingFeedingState)
    }

    @Test
    fun `running room sleep takes headline over later stopped feeding while both durations stay visible`() {
        val sleep = roomSession(TimerRecordKind.SLEEP, TimerStatus.RUNNING,
            listOf(TimerSegment(clock(10).wallTimeMs, part = TimerPart.SLEEP)), clock(10))
        val feeding = roomSession(TimerRecordKind.FEEDING, TimerStatus.STOPPED,
            listOf(segment(20, 30, TimerPart.LEFT)))
        val result = DashboardDurationMapper.update(DashboardData(), feeding, sleep,
            clock(40).wallTimeMs, zone, clock(40))
        assertEquals(DashboardData.BabyStatus.SLEEPING, result.currentStatus)
        assertEquals(DashboardData.OngoingState.STOPPED, result.ongoingFeedingState)
        assertEquals(10 * minute, result.ongoingFeedingDurationMillis)
        assertEquals(30 * minute, result.ongoingSleepDurationMillis)
        val later = DashboardDurationMapper.update(DashboardData(), feeding, null,
            clock(200).wallTimeMs, zone, clock(200))
        assertEquals(result.ongoingFeedingDurationMillis, later.ongoingFeedingDurationMillis)
    }

    private fun clock(minutes: Long) = TimerClockSnapshot(midnight + minutes * minute,
        elapsedRealtimeMs = (minutes + 100) * minute, bootCount = 3, processId = "test")

    private fun segment(start: Long, end: Long, part: TimerPart) = TimerSegment(
        midnight + start * minute, midnight + end * minute, (end - start) * minute, part
    )

    private fun roomSession(kind: TimerRecordKind, status: TimerStatus, segments: List<TimerSegment>,
        anchor: TimerClockSnapshot? = null): OngoingSession {
        val metadata = TimerMetadata(segments = segments, clockAnchor = anchor)
        val start = segments.first().startAt
        val end = segments.last().endAt ?: 0L
        val total = segments.sumOf { it.durationMs }
        val snapshot = TimerRecordSnapshot(TimerRef(kind, 1, 1, "test-session", 1), status, metadata,
            feeding = if (kind == TimerRecordKind.FEEDING) FeedingRecord(babyId = 1,
                feedingStart = start, feedingEnd = end, feedingDuration = total) else null,
            sleep = if (kind == TimerRecordKind.SLEEP) SleepRecord(babyId = 1,
                sleepStart = start, sleepEnd = end, sleepDuration = total) else null)
        return OngoingRecordManager.fromSnapshot(snapshot)
    }
}
