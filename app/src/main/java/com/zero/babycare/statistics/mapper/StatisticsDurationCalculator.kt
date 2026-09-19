package com.zero.babycare.statistics.mapper

import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.domain.RecordDurationCalculator
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
        return RecordDurationCalculator.overlappedDuration(start, end, duration, rangeStart, rangeEnd)
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
