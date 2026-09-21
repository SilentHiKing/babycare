package com.zero.babydata.domain

import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.common.util.TimerClockSnapshot

/** 正式表单保存也复用片段规则，禁止新起止配上旧片段，或备注编辑重算有效时长。 */
internal object TimerRecordNormalizer {
    fun feeding(record: FeedingRecord, original: FeedingRecord?, now: TimerClockSnapshot): FeedingRecord {
        checkIdentity(record.timerStatus, record.timerSessionId, record.timerRevision,
            original?.timerStatus, original?.timerSessionId, original?.timerRevision)
        val metadata = normalize(record.timerData, original?.timerData, record.feedingStart, record.feedingEnd,
            record.feedingDuration, TimerRecordKind.FEEDING, now)
        val duration = when (metadata.basis) {
            DurationBasis.SEGMENTED -> TimerCalculations.totalDuration(metadata)
            DurationBasis.UNMEASURED -> 0L
            DurationBasis.MANUAL_INTERVAL -> record.feedingEnd - record.feedingStart
            DurationBasis.MANUAL_TOTAL -> record.feedingDuration
        }
        val result = record.copy(feedingDuration = duration,
            feedingDurationBreastLeft = if (metadata.basis == DurationBasis.SEGMENTED) TimerCalculations.partDuration(metadata, TimerPart.LEFT) else record.feedingDurationBreastLeft,
            feedingDurationBreastRight = if (metadata.basis == DurationBasis.SEGMENTED) TimerCalculations.partDuration(metadata, TimerPart.RIGHT) else record.feedingDurationBreastRight,
            timerData = TimerMetadataCodec.encode(metadata), timerSlot = null,
            timerRevision = nextRevision(original?.timerSessionId, original?.timerRevision))
        TimerMetadataCodec.validateRecord(metadata, TimerRecordKind.FEEDING, result.feedingStart, result.feedingEnd,
            result.feedingDuration, result.feedingDurationBreastLeft, result.feedingDurationBreastRight)
        return result
    }

    fun sleep(record: SleepRecord, original: SleepRecord?, now: TimerClockSnapshot): SleepRecord {
        checkIdentity(record.timerStatus, record.timerSessionId, record.timerRevision,
            original?.timerStatus, original?.timerSessionId, original?.timerRevision)
        val metadata = normalize(record.timerData, original?.timerData, record.sleepStart, record.sleepEnd,
            record.sleepDuration, TimerRecordKind.SLEEP, now)
        val duration = when (metadata.basis) {
            DurationBasis.SEGMENTED -> TimerCalculations.totalDuration(metadata)
            DurationBasis.MANUAL_INTERVAL -> record.sleepEnd - record.sleepStart
            else -> record.sleepDuration
        }
        val result = record.copy(sleepDuration = duration, timerData = TimerMetadataCodec.encode(metadata), timerSlot = null,
            timerRevision = nextRevision(original?.timerSessionId, original?.timerRevision))
        TimerMetadataCodec.validateRecord(metadata, TimerRecordKind.SLEEP, result.sleepStart, result.sleepEnd, result.sleepDuration)
        return result
    }

    fun event(record: EventRecord, original: EventRecord?, now: TimerClockSnapshot): EventRecord {
        checkIdentity(record.timerStatus, record.timerSessionId, record.timerRevision,
            original?.timerStatus, original?.timerSessionId, original?.timerRevision)
        if (!EventType.hasDuration(record.type)) {
            // 用户明确改为瞬时事件时清除计时明细；不能把旧活动片段混入新类型。
            return record.copy(activeDurationMs = null, timerData = null, timerSessionId = null,
                timerRevision = 0L, timerSlot = null)
        }
        val metadata = normalize(record.timerData, original?.timerData, record.time, record.endTime,
            record.activeDurationMs ?: (record.endTime - record.time).coerceAtLeast(0L), TimerRecordKind.EVENT, now)
        val duration = when (metadata.basis) {
            DurationBasis.SEGMENTED -> TimerCalculations.totalDuration(metadata)
            DurationBasis.MANUAL_INTERVAL -> record.endTime - record.time
            DurationBasis.UNMEASURED -> null
            DurationBasis.MANUAL_TOTAL -> record.activeDurationMs
        }
        val result = record.copy(activeDurationMs = duration, timerData = TimerMetadataCodec.encode(metadata), timerSlot = null,
            timerRevision = nextRevision(original?.timerSessionId, original?.timerRevision))
        TimerMetadataCodec.validateRecord(metadata, TimerRecordKind.EVENT, result.time, result.endTime, result.activeDurationMs)
        return result
    }

    private fun normalize(json: String?, originalJson: String?, start: Long, end: Long, duration: Long,
        kind: TimerRecordKind, now: TimerClockSnapshot): TimerMetadata {
        var metadata = TimerMetadataCodec.decode(json) ?: TimerMetadata(basis = when {
            end == 0L && kind != TimerRecordKind.SLEEP -> DurationBasis.UNMEASURED
            duration > 0L && duration < end - start -> DurationBasis.MANUAL_TOTAL
            else -> DurationBasis.MANUAL_INTERVAL
        })
        if (metadata.basis == DurationBasis.UNMEASURED && end > 0L && end >= start) {
            // 用户明确补齐起止才产生手工区间；不能沿用显示计时器的旧值冒充有效总量。
            metadata = TimerMetadata(basis = DurationBasis.MANUAL_INTERVAL)
        }
        if (metadata.basis == DurationBasis.SEGMENTED && json == originalJson) {
            metadata = TimerTransitions.editStart(metadata, start, now)
            metadata = TimerTransitions.editEnd(metadata, end, now)
        }
        return metadata
    }

    private fun nextRevision(sessionId: String?, revision: Long?): Long = if (sessionId == null) 0L else requireNotNull(revision) + 1L

    private fun checkIdentity(status: Int, sessionId: String?, revision: Long,
        oldStatus: Int?, oldSessionId: String?, oldRevision: Long?) {
        if (status != TimerStatus.COMPLETED.code || (oldStatus != null && oldStatus != TimerStatus.COMPLETED.code))
            throw TimerOperationException(TimerFailure.INVALID_TRANSITION)
        if (oldStatus != null && (sessionId != oldSessionId || revision != oldRevision))
            throw TimerOperationException(TimerFailure.STALE_REVISION)
    }
}
