package com.zero.babycare.home.prediction

import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.domain.DurationBasis
import com.zero.babydata.domain.TimerCalculations
import com.zero.babydata.domain.TimerMetadataCodec
import com.zero.babydata.domain.TimerStatus

/** 预测与提醒共用有效样本，避免去重依据与实际预测的最近一次记录不同。 */
internal object PredictionRecordSelector {

    fun completedMilkFeedings(records: List<FeedingRecord>): List<FeedingRecord> {
        return records.filter {
            it.timerStatus == TimerStatus.COMPLETED.code && it.feedingStart > 0L &&
                feedingEnd(it) >= it.feedingStart &&
                when (it.getFeedingTypeEnum()) {
                    FeedingType.BREAST, FeedingType.FORMULA, FeedingType.MIXED -> true
                    FeedingType.SOLID_FOOD, FeedingType.OTHER -> false
                }
        }.sortedBy { it.feedingStart }
    }

    fun completedSleeps(records: List<SleepRecord>): List<SleepRecord> {
        return records.filter {
            it.timerStatus == TimerStatus.COMPLETED.code && it.sleepStart > 0L && sleepEnd(it) > it.sleepStart
        }
            .sortedBy { it.sleepStart }
    }

    /** 预测与提醒必须锚定同一个实际结束点；未计时奶量记录只提供发生时刻。 */
    fun feedingEnd(record: FeedingRecord): Long = effectiveEnd(
        record.timerData, record.feedingStart, record.feedingEnd, allowUnmeasured = true
    )

    fun sleepEnd(record: SleepRecord): Long = effectiveEnd(
        record.timerData, record.sleepStart, record.sleepEnd, allowUnmeasured = false
    )

    private fun effectiveEnd(json: String?, start: Long, end: Long, allowUnmeasured: Boolean): Long {
        if (json == null) return end.takeIf { it > start } ?: 0L
        return runCatching {
            val timing = requireNotNull(TimerMetadataCodec.decode(json))
            TimerMetadataCodec.validate(timing, completed = true)
            when (timing.basis) {
                DurationBasis.UNMEASURED -> if (allowUnmeasured) start else 0L
                DurationBasis.SEGMENTED -> TimerCalculations.lastEffectiveEnd(timing) ?: 0L
                else -> end.takeIf { it > start } ?: 0L
            }
        }.getOrDefault(0L)
    }
}
