package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.StatisticsDateRange
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

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
    fun `statistics week is fixed to Monday through Sunday`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val range = StatisticsDateRange.week(LocalDate.of(2026, 5, 8), zone)

        assertEquals(LocalDate.of(2026, 5, 4), range.startDate)
        assertEquals(LocalDate.of(2026, 5, 10), range.endDate)
        assertEquals(
            "05.04-05.10",
            "${range.startDate.format(MONTH_DAY)}-${range.endDate.format(MONTH_DAY)}"
        )
    }

    @Test
    fun `week range can follow provided week fields`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val weekFields = WeekFields.of(DayOfWeek.SUNDAY, 1)
        val range = StatisticsDateRange.week(LocalDate.of(2026, 5, 2), zone, weekFields)

        assertEquals(LocalDate.of(2026, 4, 26), range.startDate)
        assertEquals(LocalDate.of(2026, 5, 2), range.endDate)
    }

    @Test
    fun `statistics week fields start on Monday`() {
        val fields = StatisticsDateRange.statisticsWeekFields()

        assertEquals(DayOfWeek.MONDAY, fields.firstDayOfWeek)
    }

    private companion object {
        val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd")
    }
}
