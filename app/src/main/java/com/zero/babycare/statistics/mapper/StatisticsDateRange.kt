package com.zero.babycare.statistics.mapper

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

/**
 * 统计页统一时间范围计算，避免各模块对 selectedDate 的边界理解不一致。
 */
object StatisticsDateRange {

    data class Range(
        val startDate: LocalDate,
        val endDate: LocalDate,
        val startMillis: Long,
        val endMillis: Long
    )

    /**
     * 统计业务固定使用周一到周日，避免系统地区设置导致趋势区和日历区范围不一致。
     */
    fun statisticsWeekFields(): WeekFields {
        return WeekFields.of(DayOfWeek.MONDAY, 4)
    }

    fun day(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Range {
        return buildRange(date, date, zone)
    }

    fun week(
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        weekFields: WeekFields = statisticsWeekFields()
    ): Range {
        val start = date.with(TemporalAdjusters.previousOrSame(weekFields.firstDayOfWeek))
        return buildRange(start, start.plusDays(6), zone)
    }

    fun month(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Range {
        val month = YearMonth.from(date)
        return buildRange(month.atDay(1), month.atEndOfMonth(), zone)
    }

    fun year(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Range {
        return buildRange(LocalDate.of(date.year, 1, 1), LocalDate.of(date.year, 12, 31), zone)
    }

    private fun buildRange(startDate: LocalDate, endDate: LocalDate, zone: ZoneId): Range {
        val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return Range(startDate, endDate, startMillis, endMillis)
    }
}
