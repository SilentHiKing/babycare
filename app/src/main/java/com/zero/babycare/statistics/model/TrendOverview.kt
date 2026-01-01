package com.zero.babycare.statistics.model

import java.time.LocalDate

/**
 * 周/月/年趋势汇总
 */
data class TrendOverview(
    val summaries: List<TrendPeriodSummary>
)

/**
 * 单个周期的统计汇总
 */
data class TrendPeriodSummary(
    val period: TrendPeriod,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val feedingCount: Int,
    val feedingTotalMinutes: Int,
    val sleepCount: Int,
    val sleepTotalMinutes: Int,
    val diaperCount: Int,
    val otherEventCount: Int
) {
    val dayCount: Int
        get() = if (endDate.isBefore(startDate)) {
            0
        } else {
            (java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()
        }
}

/**
 * 趋势周期类型
 */
enum class TrendPeriod {
    WEEK,
    MONTH,
    YEAR
}
