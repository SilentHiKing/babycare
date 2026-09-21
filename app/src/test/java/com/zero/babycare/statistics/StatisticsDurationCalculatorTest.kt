package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.StatisticsDurationCalculator
import com.zero.babydata.domain.TimerMetadata
import com.zero.babydata.domain.TimerMetadataCodec
import com.zero.babydata.domain.TimerPart
import com.zero.babydata.domain.TimerSegment
import com.zero.babydata.domain.TimerStatus
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class StatisticsDurationCalculatorTest {

    @Test
    fun `record segments allocate asymmetric midnight pauses exactly and preserve totals`() {
        val midnight = LocalDate.of(2026, 9, 20).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val minute = 60_000L
        val parts = listOf(
            TimerSegment(midnight - 10 * minute, midnight - 5 * minute, 5 * minute, TimerPart.LEFT),
            TimerSegment(midnight + 5 * minute, midnight + 20 * minute, 15 * minute, TimerPart.RIGHT)
        )
        val feeding = FeedingRecord(feedingStart = parts.first().startAt, feedingEnd = parts.last().endAt!!,
            feedingDuration = 20 * minute, feedingDurationBreastLeft = 5 * minute,
            feedingDurationBreastRight = 15 * minute, timerData = TimerMetadataCodec.encode(TimerMetadata(segments = parts)))
        val sleep = SleepRecord(sleepStart = feeding.feedingStart, sleepEnd = feeding.feedingEnd,
            sleepDuration = feeding.feedingDuration, timerData = TimerMetadataCodec.encode(TimerMetadata(
                segments = parts.map { it.copy(part = TimerPart.SLEEP) })))
        val before = StatisticsDurationCalculator.feedingDurationMillis(listOf(feeding), 0, midnight - 1)
        val after = StatisticsDurationCalculator.feedingDurationMillis(listOf(feeding), midnight, Long.MAX_VALUE)
        assertEquals(5 * minute, before)
        assertEquals(15 * minute, after)
        assertEquals(20 * minute, before + after)
        assertEquals(before, StatisticsDurationCalculator.sleepDurationMillis(listOf(sleep), 0, midnight - 1))
        assertEquals(after, StatisticsDurationCalculator.sleepDurationMillis(listOf(sleep), midnight, Long.MAX_VALUE))
        assertEquals(0L, StatisticsDurationCalculator.sleepDurationMillis(listOf(sleep), midnight, midnight + 5 * minute - 1))
        assertEquals(0L, StatisticsDurationCalculator.sleepDurationMillis(
            listOf(sleep.copy(timerStatus = TimerStatus.PAUSED.code)), 0, Long.MAX_VALUE))
    }

    @Test
    fun `segment intersections respect a twenty three hour daylight saving day`() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 3, 8)
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val start = date.atTime(1, 30).atZone(zone).toInstant().toEpochMilli()
        val end = date.atTime(3, 30).atZone(zone).toInstant().toEpochMilli()
        val duration = end - start
        val sleep = SleepRecord(sleepStart = start, sleepEnd = end, sleepDuration = duration,
            timerData = TimerMetadataCodec.encode(TimerMetadata(segments = listOf(
                TimerSegment(start, end, duration, TimerPart.SLEEP)))))
        assertEquals(23 * 3_600_000L, nextMidnight - midnight)
        assertEquals(3_600_000L, StatisticsDurationCalculator.sleepDurationMillis(listOf(sleep), midnight, nextMidnight - 1))
        assertEquals(0L, StatisticsDurationCalculator.sleepDurationMillis(listOf(sleep), nextMidnight, Long.MAX_VALUE))
    }

    @Test
    fun `cross midnight paused duration is allocated without counting pauses again`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val midnight = LocalDate.of(2026, 5, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val minute = TimeUnit.MINUTES.toMillis(1)
        val start = midnight - 30 * minute
        val end = midnight + 30 * minute
        val before = StatisticsDurationCalculator.overlappedDuration(start, end, 40 * minute, start, midnight - 1)
        val after = StatisticsDurationCalculator.overlappedDuration(start, end, 40 * minute, midnight, end)
        val whole = StatisticsDurationCalculator.overlappedDuration(start, end, 40 * minute, start, end)

        assertEquals(20 * minute, before)
        assertEquals(20 * minute, after)
        assertEquals(whole, before + after)
    }

    @Test
    fun `millisecond allocation preserves total over adjacent ranges`() {
        val first = StatisticsDurationCalculator.overlappedDuration(1_000, 1_003, 2, 1_000, 1_000)
        val second = StatisticsDurationCalculator.overlappedDuration(1_000, 1_003, 2, 1_001, 1_001)
        val third = StatisticsDurationCalculator.overlappedDuration(1_000, 1_003, 2, 1_002, 1_002)
        assertEquals(2L, first + second + third)
    }

    @Test
    fun `inclusive day end retains the final millisecond`() {
        assertEquals(1_000L, StatisticsDurationCalculator.overlappedDuration(1_000, 2_000, 1_000, 1_000, 1_999))
    }

    @Test
    fun `duration is clipped to the part overlapping selected range`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2026, 5, 2)
        val rangeStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val recordStart = day.minusDays(1).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val recordEnd = day.atTime(0, 30).atZone(zone).toInstant().toEpochMilli()

        val result = StatisticsDurationCalculator.overlappedDuration(
            start = recordStart,
            end = recordEnd,
            duration = TimeUnit.HOURS.toMillis(1),
            rangeStart = rangeStart,
            rangeEnd = rangeEnd
        )

        assertEquals(TimeUnit.MINUTES.toMillis(30), result)
    }

    @Test
    fun `contained record keeps persisted duration but never exceeds actual overlap`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2026, 5, 2)
        val rangeStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val recordStart = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val recordEnd = day.atTime(11, 0).atZone(zone).toInstant().toEpochMilli()

        val result = StatisticsDurationCalculator.overlappedDuration(
            start = recordStart,
            end = recordEnd,
            duration = TimeUnit.MINUTES.toMillis(45),
            rangeStart = rangeStart,
            rangeEnd = rangeEnd
        )

        assertEquals(TimeUnit.MINUTES.toMillis(45), result)
    }
}
