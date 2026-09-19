package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.StatisticsDurationCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class StatisticsDurationCalculatorTest {

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
