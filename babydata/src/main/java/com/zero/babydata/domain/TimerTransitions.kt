package com.zero.babydata.domain

import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.common.util.TimerClockSnapshot
import kotlin.math.abs

/** 页面、首页与提醒只消费这份快照，不另存可竞争的计时事实。 */
data class TimerRecordSnapshot(
    val ref: TimerRef,
    val status: TimerStatus,
    val metadata: TimerMetadata,
    val feeding: FeedingRecord? = null,
    val sleep: SleepRecord? = null,
    val event: EventRecord? = null
) {
    val startAt: Long get() = feeding?.feedingStart ?: sleep?.sleepStart ?: requireNotNull(event).time
    val endAt: Long? get() = (feeding?.feedingEnd ?: sleep?.sleepEnd ?: event?.endTime)?.takeIf { it > 0L }
    val durationMs: Long get() = feeding?.feedingDuration ?: sleep?.sleepDuration ?: event?.activeDurationMs ?: 0L
    val feedingType: Int get() = feeding?.feedingType ?: 0
    val part: TimerPart get() = metadata.segments.lastOrNull()?.part ?: when (ref.kind) {
        TimerRecordKind.FEEDING -> TimerPart.UNSPECIFIED
        TimerRecordKind.SLEEP -> TimerPart.SLEEP
        TimerRecordKind.EVENT -> TimerPart.ACTIVITY
    }
}

/** 纯状态转换不访问时钟或数据库；每个命令只使用调用时冻结的一份时钟快照。 */
object TimerTransitions {
    fun start(startAt: Long, now: TimerClockSnapshot, part: TimerPart, automaticStart: Boolean): TimerMetadata {
        valid(startAt > 0L && startAt <= now.wallTimeMs)
        return TimerMetadata(
            segments = listOf(TimerSegment(startAt = startAt, durationMs = now.wallTimeMs - startAt,
                part = part, source = if (automaticStart) TimerSegmentSource.OBSERVED else TimerSegmentSource.MANUAL)),
            clockAnchor = now, automaticStart = automaticStart
        )
    }

    fun pause(metadata: TimerMetadata, now: TimerClockSnapshot): TimerMetadata {
        val open = metadata.segments.lastOrNull()?.takeIf { it.endAt == null } ?: return metadata
        val anchor = metadata.clockAnchor ?: invalid()
        val duration = Math.addExact(open.durationMs, now.elapsedSince(anchor))
        val wallDuration = now.wallTimeMs - open.startAt
        val uncertain = !now.sharesMonotonicClock(anchor) || abs(wallDuration - duration) >= 1_000L
        // 正常系统采样的毫秒差统一到单调耗时；真实校时不能静默挪动此前已经确认的片段。
        val end = if (!uncertain) Math.addExact(open.startAt, duration) else now.wallTimeMs
        val closed = open.copy(endAt = end, durationMs = duration,
            source = if (uncertain) TimerSegmentSource.ESTIMATED else open.source)
        return metadata.copy(segments = metadata.segments.dropLast(1) + closed, clockAnchor = null,
            requiresReview = metadata.requiresReview || uncertain)
    }

    fun resume(metadata: TimerMetadata, now: TimerClockSnapshot, part: TimerPart): TimerMetadata {
        if (metadata.requiresReview) throw TimerOperationException(TimerFailure.NEEDS_REVIEW)
        valid(metadata.segments.none { it.endAt == null })
        valid(metadata.segments.size < TimerMetadataCodec.MAX_SEGMENTS)
        valid(now.wallTimeMs >= (metadata.segments.lastOrNull()?.endAt ?: 0L))
        return metadata.copy(segments = metadata.segments + TimerSegment(now.wallTimeMs, part = part), clockAnchor = now)
    }

    /** 改起点只改变首段；跨越已知暂停或删除整段需要上层显式转换，不能悄悄吞掉事实。 */
    fun editStart(metadata: TimerMetadata, startAt: Long, now: TimerClockSnapshot): TimerMetadata {
        valid(startAt > 0L)
        val first = metadata.segments.firstOrNull() ?: invalid()
        if (first.startAt == startAt) return metadata
        val end = first.endAt ?: now.wallTimeMs
        valid(startAt <= end)
        val replacement = first.copy(startAt = startAt, durationMs = end - startAt, source = TimerSegmentSource.MANUAL)
        return metadata.copy(segments = listOf(replacement) + metadata.segments.drop(1), automaticStart = false,
            clockAnchor = if (first.endAt == null) now else metadata.clockAnchor)
    }

    /** 结束时间修改只触及末段；暂停后的确认原值不重算有效总量。 */
    fun editEnd(metadata: TimerMetadata, endAt: Long, now: TimerClockSnapshot): TimerMetadata {
        val last = metadata.segments.lastOrNull() ?: invalid()
        if (last.endAt == endAt) return metadata
        valid(endAt >= last.startAt && endAt <= now.wallTimeMs)
        val replacement = last.copy(endAt = endAt, durationMs = endAt - last.startAt, source = TimerSegmentSource.MANUAL)
        val segments = metadata.segments.dropLast(1) + replacement
        return metadata.copy(segments = segments, clockAnchor = null,
            requiresReview = segments.any { it.source == TimerSegmentSource.ESTIMATED })
    }

    private fun valid(condition: Boolean) { if (!condition) invalid() }
    private fun invalid(): Nothing = throw TimerOperationException(TimerFailure.INVALID_DATA)
}
