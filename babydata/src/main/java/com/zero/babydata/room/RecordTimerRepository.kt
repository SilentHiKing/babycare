package com.zero.babydata.room

import androidx.room.withTransaction
import com.zero.babydata.domain.*
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.RecordWriteReceipt
import com.zero.babydata.entity.SleepRecord
import com.zero.common.util.TimerClockSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/** 直接在三张业务表执行计时事务；同一行从开始到完成，不再协调另一份 MMKV 会话。 */
class RecordTimerRepository internal constructor(private val database: BabyDatabase) {
    private val feedingDao = database.feedingRecordDao()
    private val sleepDao = database.sleepRecordDao()
    private val eventDao = database.eventRecordDao()

    suspend fun startFeeding(record: FeedingRecord, now: TimerClockSnapshot,
        part: TimerPart = TimerPart.UNSPECIFIED, automaticStart: Boolean = false): TimerRecordSnapshot = database.withTransaction {
        findUnfinished(TimerRecordKind.FEEDING, record.babyId)?.let { return@withTransaction it }
        requireBaby(record.babyId)
        val metadata = TimerTransitions.start(record.feedingStart.takeIf { it > 0 } ?: now.wallTimeMs, now, part, automaticStart)
        TimerMetadataCodec.validate(metadata, kind = TimerRecordKind.FEEDING)
        val initial = record.copy(feedingId = 0, feedingEnd = 0L, timerStatus = TimerStatus.RUNNING.code,
            timerSessionId = UUID.randomUUID().toString(), timerRevision = 1L,
            timerData = TimerMetadataCodec.encode(metadata), timerSlot = 1)
        initial.feedingId = feedingDao.insertFeedingRecord(project(initial, metadata, TimerStatus.RUNNING, 1L)).toInt()
        snapshot(requireNotNull(feedingDao.getFeedingRecordById(initial.feedingId)))
    }

    suspend fun startSleep(record: SleepRecord, now: TimerClockSnapshot,
        automaticStart: Boolean = false): TimerRecordSnapshot = database.withTransaction {
        findUnfinished(TimerRecordKind.SLEEP, record.babyId)?.let { return@withTransaction it }
        requireBaby(record.babyId)
        val metadata = TimerTransitions.start(record.sleepStart.takeIf { it > 0 } ?: now.wallTimeMs, now, TimerPart.SLEEP, automaticStart)
        val initial = record.copy(sleepId = 0, sleepEnd = 0L, timerStatus = TimerStatus.RUNNING.code,
            timerSessionId = UUID.randomUUID().toString(), timerRevision = 1L,
            timerData = TimerMetadataCodec.encode(metadata), timerSlot = 1)
        initial.sleepId = sleepDao.insertSleepRecord(project(initial, metadata, TimerStatus.RUNNING, 1L)).toInt()
        snapshot(requireNotNull(sleepDao.getSleepRecordById(initial.sleepId)))
    }

    suspend fun startEvent(record: EventRecord, now: TimerClockSnapshot,
        automaticStart: Boolean = false): TimerRecordSnapshot = database.withTransaction {
        if (!EventType.hasDuration(record.type)) fail(TimerFailure.INVALID_DATA)
        findUnfinished(TimerRecordKind.EVENT, record.babyId, record.type)?.let { return@withTransaction it }
        requireBaby(record.babyId)
        val metadata = TimerTransitions.start(record.time.takeIf { it > 0 } ?: now.wallTimeMs, now, TimerPart.ACTIVITY, automaticStart)
        val initial = record.copy(eventId = 0, endTime = 0L, timerStatus = TimerStatus.RUNNING.code,
            timerSessionId = UUID.randomUUID().toString(), timerRevision = 1L,
            timerData = TimerMetadataCodec.encode(metadata), timerSlot = record.type)
        initial.eventId = eventDao.insertEventRecord(project(initial, metadata, TimerStatus.RUNNING, 1L)).toInt()
        snapshot(requireNotNull(eventDao.getEventRecordById(initial.eventId)))
    }

    suspend fun findUnfinished(kind: TimerRecordKind, babyId: Int, eventType: Int? = null): TimerRecordSnapshot? = when (kind) {
        TimerRecordKind.FEEDING -> feedingDao.getUnfinished(babyId).firstOrNull()?.let(::snapshot)
        TimerRecordKind.SLEEP -> sleepDao.getUnfinished(babyId).firstOrNull()?.let(::snapshot)
        TimerRecordKind.EVENT -> eventDao.getUnfinished(babyId).firstOrNull { eventType == null || it.type == eventType }?.let(::snapshot)
    }

    suspend fun getBySession(kind: TimerRecordKind, babyId: Int, sessionId: String): TimerRecordSnapshot? = when (kind) {
        TimerRecordKind.FEEDING -> feedingDao.getByTimerSession(sessionId)?.takeIf { it.babyId == babyId }?.let(::snapshot)
        TimerRecordKind.SLEEP -> sleepDao.getByTimerSession(sessionId)?.takeIf { it.babyId == babyId }?.let(::snapshot)
        TimerRecordKind.EVENT -> eventDao.getByTimerSession(sessionId)?.takeIf { it.babyId == babyId }?.let(::snapshot)
    }

    fun observeUnfinished(babyId: Int): Flow<List<TimerRecordSnapshot>> = combine(
        feedingDao.observeUnfinished(babyId), sleepDao.observeUnfinished(babyId), eventDao.observeUnfinished(babyId)
    ) { feedings, sleeps, events -> feedings.map(::snapshot) + sleeps.map(::snapshot) + events.map(::snapshot) }

    suspend fun pause(ref: TimerRef, now: TimerClockSnapshot): TimerRecordSnapshot = mutate(ref, TimerStatus.PAUSED) { current ->
        requireStatus(current, TimerStatus.RUNNING)
        TimerTransitions.pause(current.metadata, now) to TimerStatus.PAUSED
    }

    suspend fun resume(ref: TimerRef, now: TimerClockSnapshot, part: TimerPart? = null): TimerRecordSnapshot = mutate(ref, TimerStatus.RUNNING) { current ->
        requireStatus(current, TimerStatus.PAUSED)
        TimerTransitions.resume(current.metadata, now, part ?: current.part) to TimerStatus.RUNNING
    }

    suspend fun switchPart(ref: TimerRef, part: TimerPart, now: TimerClockSnapshot): TimerRecordSnapshot = mutate(ref) { current ->
        requireStatus(current, TimerStatus.RUNNING, TimerStatus.PAUSED)
        if (current.part == part) current.metadata to current.status
        else if (current.status == TimerStatus.RUNNING) {
            val paused = TimerTransitions.pause(current.metadata, now)
            // 校时异常先保住关闭片段和待核对状态；不能因继续失败整笔回滚成仍在运行。
            if (paused.requiresReview) paused to TimerStatus.PAUSED
            else TimerTransitions.resume(paused, now, part) to TimerStatus.RUNNING
        }
        else TimerTransitions.resume(current.metadata, now, part) to TimerStatus.RUNNING
    }

    suspend fun editStart(ref: TimerRef, startAt: Long, now: TimerClockSnapshot): TimerRecordSnapshot = mutate(ref) { current ->
        TimerTransitions.editStart(current.metadata, startAt, now) to current.status
    }

    suspend fun editEnd(ref: TimerRef, endAt: Long, now: TimerClockSnapshot): TimerRecordSnapshot = mutate(ref) { current ->
        val metadata = TimerTransitions.editEnd(current.metadata, endAt, now)
        metadata to if (current.status == TimerStatus.COMPLETED) TimerStatus.COMPLETED else TimerStatus.STOPPED
    }

    /** 停止独立提交；后续表单校验或最终保存失败仍保留已停止的业务事实。 */
    suspend fun stop(ref: TimerRef, now: TimerClockSnapshot): TimerRecordSnapshot = mutate(ref, TimerStatus.STOPPED) { current ->
        requireStatus(current, TimerStatus.RUNNING, TimerStatus.PAUSED)
        TimerTransitions.pause(current.metadata, now) to TimerStatus.STOPPED
    }

    /** 仅在用户明确核对后接受墙钟区间；未知耗时不再冒充单调时钟的精确测量。 */
    suspend fun acknowledgeReview(ref: TimerRef, now: TimerClockSnapshot): TimerRecordSnapshot = mutate(ref) { current ->
        requireStatus(current, TimerStatus.PAUSED, TimerStatus.STOPPED)
        val segments = current.metadata.segments.map { segment ->
            val end = segment.endAt ?: fail(TimerFailure.INVALID_DATA)
            if (end < segment.startAt || end > now.wallTimeMs) fail(TimerFailure.INVALID_DATA)
            if (segment.source == TimerSegmentSource.ESTIMATED)
                segment.copy(durationMs = end - segment.startAt, source = TimerSegmentSource.MANUAL)
            else segment
        }
        current.metadata.copy(segments = segments, requiresReview = false, clockAnchor = null) to current.status
    }

    /** 普通字段更新不接受表单伪造的时长/片段；记录身份、状态和汇总始终来自同一数据库行。 */
    suspend fun updateDraft(ref: TimerRef, feeding: FeedingRecord? = null, sleep: SleepRecord? = null,
        event: EventRecord? = null): TimerRecordSnapshot = database.withTransaction {
        val current = checked(ref)
        requireStatus(current, TimerStatus.RUNNING, TimerStatus.PAUSED, TimerStatus.STOPPED)
        write(withPayload(current, feeding, sleep, event), current.metadata, current.status, current.ref.revision + 1L)
    }

    /** 正式记录和回执同事务完成，失败不释放槽位，重试复用原记录 ID。 */
    suspend fun finalize(ref: TimerRef, feeding: FeedingRecord? = null, sleep: SleepRecord? = null,
        event: EventRecord? = null): TimerRecordSnapshot = database.withTransaction {
        val existing = getBySession(ref.kind, ref.babyId, ref.sessionId) ?: fail(TimerFailure.NOT_FOUND)
        if (existing.ref.recordId != ref.recordId) fail(TimerFailure.NOT_FOUND)
        val completedPayload = withPayload(existing, feeding, sleep, event)
        val receiptDao = database.recordWriteReceiptDao()
        val requestId = requestId(ref.sessionId)
        val oldReceipt = receiptDao.getReceipt(requestId)
        if (existing.status == TimerStatus.COMPLETED) {
            if (oldReceipt == null || oldReceipt.recordId != ref.recordId || oldReceipt.payloadHash != hash(completedPayload))
                throw RecordWriteException(RecordWriteFailure.CONTENT_CONFLICT)
            return@withTransaction existing
        }
        if (existing.ref.revision != ref.revision) fail(TimerFailure.STALE_REVISION)
        requireStatus(existing, TimerStatus.STOPPED)
        TimerMetadataCodec.validate(existing.metadata, completed = true, kind = ref.kind)
        val completed = write(completedPayload, existing.metadata.copy(clockAnchor = null), TimerStatus.COMPLETED, ref.revision + 1L)
        validateCompleted(completed)
        receiptDao.insertReceipt(RecordWriteReceipt(requestId, ref.babyId, recordType(ref.kind), ref.recordId, hash(completed)))
        completed
    }

    suspend fun discard(ref: TimerRef): Boolean = database.withTransaction {
        val current = getBySession(ref.kind, ref.babyId, ref.sessionId) ?: return@withTransaction false
        if (current.ref.recordId != ref.recordId || current.ref.revision != ref.revision) fail(TimerFailure.STALE_REVISION)
        if (current.status == TimerStatus.COMPLETED) fail(TimerFailure.INVALID_TRANSITION)
        when (ref.kind) {
            TimerRecordKind.FEEDING -> feedingDao.deleteFeedingRecordById(ref.recordId)
            TimerRecordKind.SLEEP -> sleepDao.deleteSleepRecordById(ref.recordId)
            TimerRecordKind.EVENT -> eventDao.deleteEventRecordById(ref.recordId)
        }
        true
    }

    private suspend fun mutate(ref: TimerRef, idempotentStatus: TimerStatus? = null,
        transition: (TimerRecordSnapshot) -> Pair<TimerMetadata, TimerStatus>): TimerRecordSnapshot = database.withTransaction {
        val current = checked(ref, idempotentStatus)
        if (idempotentStatus != null && current.status == idempotentStatus) return@withTransaction current
        val (metadata, status) = transition(current)
        TimerMetadataCodec.validate(metadata, completed = status == TimerStatus.COMPLETED, kind = ref.kind)
        if (metadata == current.metadata && status == current.status) current
        else write(current, metadata, status, ref.revision + 1L)
    }

    private suspend fun checked(ref: TimerRef, idempotentStatus: TimerStatus? = null): TimerRecordSnapshot {
        requireBaby(ref.babyId)
        val current = getBySession(ref.kind, ref.babyId, ref.sessionId) ?: fail(TimerFailure.NOT_FOUND)
        if (current.ref.recordId != ref.recordId) fail(TimerFailure.NOT_FOUND)
        if (current.ref.revision != ref.revision && current.status != idempotentStatus) fail(TimerFailure.STALE_REVISION)
        return current
    }

    private fun withPayload(current: TimerRecordSnapshot, feeding: FeedingRecord?, sleep: SleepRecord?, event: EventRecord?): TimerRecordSnapshot {
        if (listOfNotNull(feeding, sleep, event).size > 1) fail(TimerFailure.INVALID_DATA)
        if (feeding != null && (current.ref.kind != TimerRecordKind.FEEDING || feeding.babyId != current.ref.babyId)) fail(TimerFailure.INVALID_DATA)
        if (sleep != null && (current.ref.kind != TimerRecordKind.SLEEP || sleep.babyId != current.ref.babyId)) fail(TimerFailure.INVALID_DATA)
        if (event != null && (current.ref.kind != TimerRecordKind.EVENT || event.babyId != current.ref.babyId || event.type != current.event?.type)) fail(TimerFailure.INVALID_DATA)
        return current.copy(
            feeding = feeding?.copy(feedingId = current.ref.recordId, timerSessionId = current.ref.sessionId,
                createdAt = current.feeding?.createdAt ?: feeding.createdAt) ?: current.feeding,
            sleep = sleep?.copy(sleepId = current.ref.recordId, timerSessionId = current.ref.sessionId,
                createdAt = current.sleep?.createdAt ?: sleep.createdAt) ?: current.sleep,
            event = event?.copy(eventId = current.ref.recordId, timerSessionId = current.ref.sessionId,
                createdAt = current.event?.createdAt ?: event.createdAt) ?: current.event)
    }

    private suspend fun write(current: TimerRecordSnapshot, metadata: TimerMetadata, status: TimerStatus, revision: Long): TimerRecordSnapshot = when (current.ref.kind) {
        TimerRecordKind.FEEDING -> project(requireNotNull(current.feeding), metadata, status, revision).also { feedingDao.updateFeedingRecord(it) }.let(::snapshot)
        TimerRecordKind.SLEEP -> project(requireNotNull(current.sleep), metadata, status, revision).also { sleepDao.updateSleepRecord(it) }.let(::snapshot)
        TimerRecordKind.EVENT -> project(requireNotNull(current.event), metadata, status, revision).also { eventDao.updateEventRecord(it) }.let(::snapshot)
    }

    private fun project(record: FeedingRecord, metadata: TimerMetadata, status: TimerStatus, revision: Long) = record.copy(
        feedingStart = metadata.segments.first().startAt, feedingEnd = metadata.segments.last().endAt ?: 0L,
        feedingDuration = TimerCalculations.totalDuration(metadata),
        feedingDurationBreastLeft = TimerCalculations.partDuration(metadata, TimerPart.LEFT),
        feedingDurationBreastRight = TimerCalculations.partDuration(metadata, TimerPart.RIGHT),
        timerStatus = status.code, timerRevision = revision, timerData = TimerMetadataCodec.encode(metadata),
        timerSlot = if (status == TimerStatus.COMPLETED) null else 1)

    private fun project(record: SleepRecord, metadata: TimerMetadata, status: TimerStatus, revision: Long) = record.copy(
        sleepStart = metadata.segments.first().startAt, sleepEnd = metadata.segments.last().endAt ?: 0L,
        sleepDuration = TimerCalculations.totalDuration(metadata), timerStatus = status.code, timerRevision = revision,
        timerData = TimerMetadataCodec.encode(metadata), timerSlot = if (status == TimerStatus.COMPLETED) null else 1)

    private fun project(record: EventRecord, metadata: TimerMetadata, status: TimerStatus, revision: Long) = record.copy(
        time = metadata.segments.first().startAt, endTime = metadata.segments.last().endAt ?: 0L,
        activeDurationMs = TimerCalculations.totalDuration(metadata), timerStatus = status.code, timerRevision = revision,
        timerData = TimerMetadataCodec.encode(metadata), timerSlot = if (status == TimerStatus.COMPLETED) null else record.type)

    private fun snapshot(record: FeedingRecord) = TimerRecordSnapshot(
        TimerRef(TimerRecordKind.FEEDING, record.babyId, record.feedingId, requireNotNull(record.timerSessionId), record.timerRevision),
        TimerStatus.fromCode(record.timerStatus), requireNotNull(TimerMetadataCodec.decode(record.timerData)), feeding = record)
    private fun snapshot(record: SleepRecord) = TimerRecordSnapshot(
        TimerRef(TimerRecordKind.SLEEP, record.babyId, record.sleepId, requireNotNull(record.timerSessionId), record.timerRevision),
        TimerStatus.fromCode(record.timerStatus), requireNotNull(TimerMetadataCodec.decode(record.timerData)), sleep = record)
    private fun snapshot(record: EventRecord) = TimerRecordSnapshot(
        TimerRef(TimerRecordKind.EVENT, record.babyId, record.eventId, requireNotNull(record.timerSessionId), record.timerRevision),
        TimerStatus.fromCode(record.timerStatus), requireNotNull(TimerMetadataCodec.decode(record.timerData)), event = record)

    private suspend fun requireBaby(babyId: Int) {
        if (babyId <= 0 || database.babyInfoDao().getBabyInfo(babyId) == null) fail(TimerFailure.NOT_FOUND)
    }
    private fun requireStatus(snapshot: TimerRecordSnapshot, vararg statuses: TimerStatus) {
        if (snapshot.status !in statuses) fail(TimerFailure.INVALID_TRANSITION)
    }
    private fun validateCompleted(snapshot: TimerRecordSnapshot) = TimerMetadataCodec.validateRecord(snapshot.metadata,
        snapshot.ref.kind, snapshot.startAt, snapshot.endAt ?: 0L, snapshot.durationMs,
        snapshot.feeding?.feedingDurationBreastLeft, snapshot.feeding?.feedingDurationBreastRight)
    private fun hash(snapshot: TimerRecordSnapshot): String = when (snapshot.ref.kind) {
        TimerRecordKind.FEEDING -> RecordWritePayload.feeding(project(requireNotNull(snapshot.feeding), snapshot.metadata, TimerStatus.COMPLETED, snapshot.ref.revision))
        TimerRecordKind.SLEEP -> RecordWritePayload.sleep(project(requireNotNull(snapshot.sleep), snapshot.metadata, TimerStatus.COMPLETED, snapshot.ref.revision))
        TimerRecordKind.EVENT -> RecordWritePayload.event(project(requireNotNull(snapshot.event), snapshot.metadata, TimerStatus.COMPLETED, snapshot.ref.revision))
    }
    private fun recordType(kind: TimerRecordKind): Int = when (kind) {
        TimerRecordKind.FEEDING -> RecordWritePayload.FEEDING
        TimerRecordKind.SLEEP -> RecordWritePayload.SLEEP
        TimerRecordKind.EVENT -> RecordWritePayload.EVENT
    }
    private fun requestId(sessionId: String): String = UUID.nameUUIDFromBytes("babycare-room-timer-v1:$sessionId".toByteArray(Charsets.UTF_8)).toString()
    private fun fail(reason: TimerFailure): Nothing = throw TimerOperationException(reason)
}
