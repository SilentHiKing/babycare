package com.zero.babycare.home.record

import com.zero.babydata.domain.DurationBasis
import com.zero.babydata.domain.TimerCalculations
import com.zero.babydata.domain.TimerMetadataCodec
import com.zero.babydata.domain.TimerOperationException
import com.zero.babydata.domain.TimerPart
import com.zero.babydata.domain.TimerTransitions
import com.zero.common.util.TimerClock
import com.zero.common.util.TimerClockSnapshot

/** 编辑预览复用正式保存的首末片段规则，不把中间暂停重新计入有效时长。 */
internal object CompletedTimerDurationPreview {
    data class Value(val durationMs: Long, val leftMs: Long, val rightMs: Long)
    class InvalidRange(val startField: Boolean, cause: Throwable) : IllegalArgumentException(cause)

    /** null 表示普通手工区间；分段调整失败必须显式反馈，不能回退成首尾跨度。 */
    fun calculate(json: String?, startAt: Long, endAt: Long,
        now: TimerClockSnapshot = TimerClock.snapshot()): Result<Value>? {
        if (json == null) return null
        val metadata = TimerMetadataCodec.decode(json)
            ?: return Result.failure(IllegalArgumentException("计时明细无效"))
        if (metadata.basis != DurationBasis.SEGMENTED) return null
        return runCatching {
            val adjustedStart = try { TimerTransitions.editStart(metadata, startAt, now) }
                catch (error: TimerOperationException) { throw InvalidRange(true, error) }
            val adjusted = try { TimerTransitions.editEnd(adjustedStart, endAt, now) }
                catch (error: TimerOperationException) { throw InvalidRange(false, error) }
            Value(TimerCalculations.totalDuration(adjusted),
                TimerCalculations.partDuration(adjusted, TimerPart.LEFT),
                TimerCalculations.partDuration(adjusted, TimerPart.RIGHT))
        }
    }
}
