package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.StatisticsDateRange
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

class StatisticsDateRangeTest {

    @Test
    fun `day range covers the selected local day`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val selectedDate = LocalDate.of(2026, 5, 2)

        val range = StatisticsDateRange.day(selectedDate, zone)

        assertEquals(
            selectedDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            range.startMillis
        )
        assertEquals(
            selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
            range.endMillis
        )
    }

    @Test
    fun `month range uses the selected date month`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val range = StatisticsDateRange.month(LocalDate.of(2026, 5, 2), zone)

        assertEquals(LocalDate.of(2026, 5, 1), range.startDate)
        assertEquals(LocalDate.of(2026, 5, 31), range.endDate)
    }

    @Test
    fun `week range follows the provided locale first day`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val weekFields = WeekFields.of(DayOfWeek.MONDAY, 4)
        val range = StatisticsDateRange.week(LocalDate.of(2026, 5, 2), zone, weekFields)

        assertEquals(LocalDate.of(2026, 4, 27), range.startDate)
        assertEquals(LocalDate.of(2026, 5, 3), range.endDate)
    }

    @Test
    fun `default week fields come from locale`() {
        val fields = StatisticsDateRange.weekFields(Locale.CHINA)

        assertEquals(WeekFields.of(Locale.CHINA).firstDayOfWeek, fields.firstDayOfWeek)
    }
}
