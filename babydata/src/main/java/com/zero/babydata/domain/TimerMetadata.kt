package com.zero.babydata.domain

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.zero.common.util.TimerClockSnapshot

/** 业务表上的状态码保持稳定；默认值代表普通已完成手工记录。 */
enum class TimerStatus(val code: Int) {
    COMPLETED(0), RUNNING(1), PAUSED(2), STOPPED(3);
    companion object {
        fun fromCode(code: Int): TimerStatus = entries.firstOrNull { it.code == code }
            ?: throw TimerOperationException(TimerFailure.INVALID_DATA)
    }
}

enum class TimerRecordKind { FEEDING, SLEEP, EVENT }
enum class DurationBasis { UNMEASURED, MANUAL_INTERVAL, MANUAL_TOTAL, SEGMENTED }
enum class TimerPart { LEFT, RIGHT, UNSPECIFIED, SLEEP, ACTIVITY }
enum class TimerSegmentSource { OBSERVED, MANUAL, ESTIMATED }
enum class TimerFailure { NOT_FOUND, STALE_REVISION, INVALID_TRANSITION, INVALID_DATA, NEEDS_REVIEW }
class TimerOperationException(val reason: TimerFailure) : IllegalStateException(reason.name)

/** endAt 为空只表示当前开放段；durationMs 保存此前确认的累计，运行增量由锚点推导。 */
data class TimerSegment(
    val startAt: Long,
    val endAt: Long? = null,
    val durationMs: Long = 0L,
    val part: TimerPart = TimerPart.UNSPECIFIED,
    val source: TimerSegmentSource = TimerSegmentSource.OBSERVED
)

/** JSON 仅保存可恢复的业务事实，不保存 View、进程锁或 UI 回调。 */
data class TimerMetadata(
    val version: Int = 1,
    val basis: DurationBasis = DurationBasis.SEGMENTED,
    val segments: List<TimerSegment> = emptyList(),
    val clockAnchor: TimerClockSnapshot? = null,
    val automaticStart: Boolean = false,
    val requiresReview: Boolean = false
)

data class TimerRef(
    val kind: TimerRecordKind,
    val babyId: Int,
    val recordId: Int,
    val sessionId: String,
    val revision: Long
)

/** 所有入口共用有界解析和校验，备份不能另造一套片段含义。 */
object TimerMetadataCodec {
    const val MAX_SEGMENTS = 2_000
    private const val MAX_JSON_CHARS = 1_048_576
    private val gson = Gson()

    fun decode(json: String?): TimerMetadata? {
        if (json == null) return null
        requireValid(json.length <= MAX_JSON_CHARS && json.isNotBlank())
        return try {
            // 固定结构只允许有限层，先遍历再交给 Gson，避免恶意嵌套进入递归对象化。
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
            fun inspect(depth: Int) {
                requireValid(depth <= 12)
                when (reader.peek()) {
                    com.google.gson.stream.JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        while (reader.hasNext()) { reader.nextName(); inspect(depth + 1) }
                        reader.endObject()
                    }
                    com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray(); var count = 0
                        while (reader.hasNext()) { requireValid(++count <= MAX_SEGMENTS); inspect(depth + 1) }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.use { inspect(0); requireValid(it.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT) }
            requireValid(JsonParser.parseString(json).isJsonObject)
            gson.fromJson(json, TimerMetadata::class.java).also { validate(it) }
        } catch (failure: TimerOperationException) { throw failure }
        catch (_: Exception) { throw TimerOperationException(TimerFailure.INVALID_DATA) }
    }

    fun encode(metadata: TimerMetadata): String {
        validate(metadata)
        return gson.toJson(metadata).also { requireValid(it.length <= MAX_JSON_CHARS) }
    }

    /** 未核对的时钟异常允许暂存，但不能完成或作为精确片段进入备份。 */
    fun validate(metadata: TimerMetadata, completed: Boolean = false, kind: TimerRecordKind? = null) {
        requireValid(metadata.version == 1 && metadata.segments.size <= MAX_SEGMENTS)
        requireValid(metadata.basis in DurationBasis.entries)
        if (metadata.basis != DurationBasis.SEGMENTED) requireValid(metadata.segments.isEmpty())
        if (completed && metadata.requiresReview) throw TimerOperationException(TimerFailure.NEEDS_REVIEW)
        var previousEnd: Long? = null
        metadata.segments.forEachIndexed { index, segment ->
            requireValid(segment.startAt > 0L && segment.durationMs >= 0L)
            requireValid(segment.part in TimerPart.entries && segment.source in TimerSegmentSource.entries)
            requireValid(segment.source != TimerSegmentSource.ESTIMATED || metadata.requiresReview)
            requireValid(segment.endAt != null || (!completed && index == metadata.segments.lastIndex))
            if (!metadata.requiresReview) {
                requireValid(previousEnd == null || segment.startAt >= previousEnd!!)
                segment.endAt?.let { end ->
                    requireValid(end >= segment.startAt && segment.durationMs == end - segment.startAt)
                }
            }
            kind?.let {
                requireValid(when (it) {
                    TimerRecordKind.FEEDING -> segment.part in listOf(TimerPart.LEFT, TimerPart.RIGHT, TimerPart.UNSPECIFIED)
                    TimerRecordKind.SLEEP -> segment.part == TimerPart.SLEEP
                    TimerRecordKind.EVENT -> segment.part == TimerPart.ACTIVITY
                })
            }
            previousEnd = segment.endAt
        }
        requireValid(metadata.segments.none { it.endAt == null } || metadata.clockAnchor != null)
    }

    /** 正式记录与 JSON 汇总一起核验，避免导入和页面保存各自解释片段。 */
    fun validateRecord(metadata: TimerMetadata, kind: TimerRecordKind, startAt: Long, endAt: Long,
        durationMs: Long?, leftMs: Long? = null, rightMs: Long? = null) {
        validate(metadata, completed = true, kind = kind)
        requireValid(startAt > 0L)
        when (metadata.basis) {
            DurationBasis.UNMEASURED -> requireValid(kind != TimerRecordKind.SLEEP && endAt == 0L && (durationMs == null || durationMs == 0L))
            DurationBasis.MANUAL_INTERVAL -> requireValid(endAt >= startAt && durationMs == endAt - startAt)
            DurationBasis.MANUAL_TOTAL -> requireValid(endAt >= startAt && durationMs != null && durationMs in 0L..(endAt - startAt))
            DurationBasis.SEGMENTED -> {
                requireValid(metadata.segments.isNotEmpty())
                requireValid(metadata.segments.first().startAt == startAt && metadata.segments.last().endAt == endAt)
                requireValid(durationMs == TimerCalculations.totalDuration(metadata))
                if (kind == TimerRecordKind.FEEDING) {
                    requireValid(leftMs == TimerCalculations.partDuration(metadata, TimerPart.LEFT))
                    requireValid(rightMs == TimerCalculations.partDuration(metadata, TimerPart.RIGHT))
                }
            }
        }
    }

    private fun requireValid(condition: Boolean) {
        if (!condition) throw TimerOperationException(TimerFailure.INVALID_DATA)
    }
}

/** 计时与统计使用同一片段算法；调用方不能以整段跨度覆盖暂停事实。 */
object TimerCalculations {
    fun totalDuration(metadata: TimerMetadata, now: TimerClockSnapshot? = null): Long =
        metadata.segments.sumOf { segment ->
            segment.durationMs + if (segment.endAt == null && now != null && metadata.clockAnchor != null)
                now.elapsedSince(metadata.clockAnchor) else 0L
        }

    fun partDuration(metadata: TimerMetadata, part: TimerPart, now: TimerClockSnapshot? = null): Long =
        totalDuration(metadata.copy(segments = metadata.segments.filter { it.part == part }), now)

    /** 预测只锚定发生过有效活动的末段，零时长确认不能把下一次提醒推迟。 */
    fun lastEffectiveEnd(metadata: TimerMetadata): Long? =
        metadata.segments.lastOrNull { it.durationMs > 0L && it.endAt != null }?.endAt

    /** 范围终点沿用现有 DAO 的闭区间约定；未核对数据不得伪装成精确日分摊。 */
    fun overlapDuration(metadata: TimerMetadata, rangeStart: Long, rangeEnd: Long): Long {
        TimerMetadataCodec.validate(metadata, completed = true)
        if (rangeEnd < rangeStart) return 0L
        val exclusiveEnd = if (rangeEnd == Long.MAX_VALUE) rangeEnd else rangeEnd + 1L
        // 已校验的精确段直接求交，不经过比例/Double 分摊引入毫秒舍入。
        return metadata.segments.sumOf { segment ->
            (minOf(requireNotNull(segment.endAt), exclusiveEnd) - maxOf(segment.startAt, rangeStart)).coerceAtLeast(0L)
        }
    }
}
