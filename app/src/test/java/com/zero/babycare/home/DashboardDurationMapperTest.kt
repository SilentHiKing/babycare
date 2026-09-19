package com.zero.babycare.home

import com.zero.babycare.home.OngoingRecordManager.OngoingSession
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babycare.home.OngoingRecordManager.SessionStatus
import com.zero.babycare.home.bean.DashboardData
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
            endAt = midnight + 30 * minute, status = SessionStatus.PAUSED
        )
        val result = DashboardDurationMapper.update(
            DashboardData(), null, session, midnight + 120 * minute, zone
        )
        assertEquals(90 * minute, result.ongoingSleepDurationMillis)
        assertEquals(30L, result.displaySleepMinutes)
    }
}
