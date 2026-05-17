package com.zero.babycare.statistics.mapper

import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import java.util.concurrent.TimeUnit

/**
 * 统计页时长计算器。
 *
 * 周/月/年洞察不能直接累加记录保存的整段时长，因为跨天或跨月记录只应把
 * 与当前统计范围重叠的部分计入该范围，避免周期趋势被边界记录放大。
 */
object StatisticsDurationCalculator {

    fun overlappedDuration(
        start: Long,
        end: Long,
        duration: Long,
        rangeStart: Long,
        rangeEnd: Long
    ): Long {
        if (start <= 0L || end <= start || rangeEnd < rangeStart) return 0L

        // StatisticsDateRange 暴露的是包含当天最后 1ms 的 endMillis；
        // 时长计算使用右开区间，才能让跨完整一天的记录得到完整 24 小时。
        val rangeEndExclusive = if (rangeEnd == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            rangeEnd + 1
        }
        val overlapStart = maxOf(start, rangeStart)
        val overlapEnd = minOf(end, rangeEndExclusive)
        val overlap = (overlapEnd - overlapStart).coerceAtLeast(0L)
        if (overlap <= 0L) return 0L

        val isFullyWithinRange = start >= rangeStart && end <= rangeEndExclusive
        return if (isFullyWithinRange && duration > 0L) {
            // 完全落在范围内时保留记录的真实时长，但仍做上限保护，避免脏数据放大统计。
            minOf(duration, overlap)
        } else {
            overlap
        }
    }

    fun feedingDurationMillis(
        records: List<FeedingRecord>,
        rangeStart: Long,
        rangeEnd: Long
    ): Long {
        return records.sumOf { record ->
            overlappedDuration(
                start = record.feedingStart,
                end = record.feedingEnd,
                duration = record.feedingDuration,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd
            )
        }
    }

    fun sleepDurationMillis(
        records: List<SleepRecord>,
        rangeStart: Long,
        rangeEnd: Long
    ): Long {
        return records.sumOf { record ->
            overlappedDuration(
                start = record.sleepStart,
                end = record.sleepEnd,
                duration = record.sleepDuration,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd
            )
        }
    }

    fun toWholeMinutes(durationMillis: Long): Int {
        return TimeUnit.MILLISECONDS.toMinutes(durationMillis).toInt()
    }
}
