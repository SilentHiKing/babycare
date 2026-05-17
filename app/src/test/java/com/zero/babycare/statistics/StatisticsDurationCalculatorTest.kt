package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.StatisticsDurationCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class StatisticsDurationCalculatorTest {

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
