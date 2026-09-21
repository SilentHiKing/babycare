package com.zero.babycare.home.record

import com.blankj.utilcode.util.LogUtils
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babydata.domain.*
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyRepository
import com.zero.babydata.domain.TimerRecordSnapshot
import com.zero.common.R
import com.zero.common.util.TimerClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 页面共用的计时命令入口。锁跨挂起保持顺序，Room 快照是唯一计时事实。 */
internal class PersistentTimerEditor(
    private val scope: CoroutineScope,
    private val repository: () -> BabyRepository,
    private val kind: TimerRecordKind
) {
    data class State(
        val snapshot: TimerRecordSnapshot? = null,
        val busy: Boolean = false,
        val errorRes: Int? = null,
        val babyId: Int? = null,
        val eventType: Int? = null
    )
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var generation = 0L

    fun open(babyId: Int, eventType: Int? = null, expectedSessionId: String? = null) {
        val token = ++generation
        mutableState.value = State(babyId = babyId, eventType = eventType, busy = true)
        scope.launch {
            mutex.withLock {
                try {
                    val result = withContext(Dispatchers.IO) {
                        if (expectedSessionId == null) repository().timers.findUnfinished(kind, babyId, eventType)
                        else repository().timers.getBySession(kind, babyId, expectedSessionId)
                            ?: throw TimerOperationException(TimerFailure.NOT_FOUND)
                    }
                    if (kind == TimerRecordKind.EVENT && result != null && result.event?.type != eventType)
                        throw TimerOperationException(TimerFailure.STALE_REVISION)
                    if (generation == token) mutableState.value = State(result, babyId = babyId, eventType = eventType)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (generation == token) mutableState.value = mutableState.value.copy(busy = false, errorRes = message(failure))
                }
            }
        }
    }

    fun startFeeding(record: FeedingRecord, automaticStart: Boolean, part: TimerPart = TimerPart.UNSPECIFIED) = command {
        repository().timers.startFeeding(record, TimerClock.snapshot(), part, automaticStart)
    }
    fun startSleep(record: SleepRecord, automaticStart: Boolean) = command {
        repository().timers.startSleep(record, TimerClock.snapshot(), automaticStart)
    }
    fun startEvent(record: EventRecord, automaticStart: Boolean) = command {
        repository().timers.startEvent(record, TimerClock.snapshot(), automaticStart)
    }
    fun pause() = change { repository().timers.pause(it.ref, TimerClock.snapshot()) }
    fun resume() = change { repository().timers.resume(it.ref, TimerClock.snapshot()) }
    fun editStart(value: Long) = change { repository().timers.editStart(it.ref, value, TimerClock.snapshot()) }
    fun editEnd(value: Long) = change { repository().timers.editEnd(it.ref, value, TimerClock.snapshot()) }
    fun stop() = change { repository().timers.stop(it.ref, TimerClock.snapshot()) }
    fun switchPart(part: TimerPart) = change { repository().timers.switchPart(it.ref, part, TimerClock.snapshot()) }
    fun updateFeedingType(type: Int) = change { snapshot ->
        repository().timers.updateDraft(snapshot.ref, feeding = requireNotNull(snapshot.feeding).copy(feedingType = type))
    }
    fun discard() {
        val snapshot = state.value.snapshot ?: return
        command { repository().timers.discard(snapshot.ref); null }
    }
    fun clearError() { mutableState.value = mutableState.value.copy(errorRes = null) }
    fun adopt(snapshot: TimerRecordSnapshot?, babyId: Int) {
        generation++
        mutableState.value = State(snapshot = snapshot, babyId = babyId, eventType = snapshot?.event?.type)
    }
    fun acknowledgeReview() = change { repository().timers.acknowledgeReview(it.ref, TimerClock.snapshot()) }

    /** 恢复也经过命令锁，旧页面尚未提交的暂停不能被较早的查询快照覆盖。 */
    suspend fun recoverSession(babyId: Int, expectedSessionId: String?): TimerRecordSnapshot? = mutex.withLock {
        val snapshot = withContext(Dispatchers.IO) {
            if (expectedSessionId == null) repository().timers.findUnfinished(kind, babyId)
            else repository().timers.getBySession(kind, babyId, expectedSessionId)
                ?: throw TimerOperationException(TimerFailure.NOT_FOUND)
        }
        adopt(snapshot?.takeUnless { it.status == com.zero.babydata.domain.TimerStatus.COMPLETED }, babyId)
        snapshot
    }

    private fun change(action: suspend (TimerRecordSnapshot) -> TimerRecordSnapshot) {
        // 捕获点击所属记录，等待 IO 期间切换宝宝不能把旧命令施加到新槽位。
        val snapshot = mutableState.value.snapshot ?: return
        command { action(snapshot) }
    }

    private fun command(action: suspend () -> TimerRecordSnapshot?) {
        if (mutableState.value.busy) return
        val token = generation
        val before = mutableState.value
        before.babyId?.let(::notifyChanged)
        mutableState.value = before.copy(busy = true, errorRes = null)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                try {
                    val result = withContext(Dispatchers.IO) { action() }
                    if (generation == token) mutableState.value = before.copy(snapshot = result)
                    before.babyId?.let(::notifyChanged)
                    LogUtils.i("计时状态已保存：${result?.status?.name ?: "已放弃"}")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    var refreshed = before.snapshot
                    var errorRes = message(failure)
                    if ((failure as? TimerOperationException)?.reason == TimerFailure.STALE_REVISION) {
                        try {
                            refreshed = before.snapshot?.let { old -> withContext(Dispatchers.IO) {
                                repository().timers.getBySession(kind, old.ref.babyId, old.ref.sessionId)
                            } }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (refreshFailure: Exception) {
                            // 冲突后的恢复读取也可能失败，不能逸出异常并把页面永久留在忙碌态。
                            errorRes = R.string.save_failed
                            LogUtils.w("计时冲突后重新读取失败：${refreshFailure.javaClass.simpleName}")
                        }
                    }
                    if (generation == token) mutableState.value = before.copy(snapshot = refreshed,
                        busy = false, errorRes = errorRes)
                    LogUtils.w("计时操作失败：${failure.javaClass.simpleName}")
                }
            }
        }
    }

    /** 结束先单独提交，后续表单校验或完成失败都不能重新开始计时。 */
    suspend fun stopForSave(expectedSessionId: String? = state.value.snapshot?.ref?.sessionId): TimerRecordSnapshot? = mutex.withLock {
        val token = generation
        val snapshot = mutableState.value.snapshot ?: return@withLock null
        checkIdentity(snapshot, expectedSessionId)
        val stopped = withContext(Dispatchers.IO) { repository().timers.stop(snapshot.ref, TimerClock.snapshot()) }
        if (token == generation) mutableState.value = mutableState.value.copy(snapshot = stopped, busy = false)
        notifyChanged(stopped.ref.babyId)
        stopped
    }

    suspend fun finishFeeding(record: FeedingRecord, requestId: String, expectedSessionId: String?) = mutex.withLock {
        val token = generation
        val snapshot = requireSnapshot(expectedSessionId, record.babyId)
        val draft = withContext(Dispatchers.IO) { repository().timers.updateDraft(snapshot.ref, feeding = record) }
        if (token == generation) mutableState.value = mutableState.value.copy(snapshot = draft)
        withContext(Dispatchers.IO) { repository().timers.finalize(draft.ref, feeding = record) }
        if (token == generation) mutableState.value = mutableState.value.copy(snapshot = null)
    }
    suspend fun finishSleep(record: SleepRecord, requestId: String, expectedSessionId: String?) = mutex.withLock {
        val token = generation
        val snapshot = requireSnapshot(expectedSessionId, record.babyId)
        val draft = withContext(Dispatchers.IO) { repository().timers.updateDraft(snapshot.ref, sleep = record) }
        if (token == generation) mutableState.value = mutableState.value.copy(snapshot = draft)
        withContext(Dispatchers.IO) { repository().timers.finalize(draft.ref, sleep = record) }
        if (token == generation) mutableState.value = mutableState.value.copy(snapshot = null)
    }
    suspend fun finishEvent(record: EventRecord, requestId: String, expectedSessionId: String? = state.value.snapshot?.ref?.sessionId) = mutex.withLock {
        val token = generation
        val snapshot = requireSnapshot(expectedSessionId, record.babyId)
        if (snapshot.event?.type != record.type) throw TimerOperationException(TimerFailure.STALE_REVISION)
        val draft = withContext(Dispatchers.IO) { repository().timers.updateDraft(snapshot.ref, event = record) }
        if (token == generation) mutableState.value = mutableState.value.copy(snapshot = draft)
        withContext(Dispatchers.IO) { repository().timers.finalize(draft.ref, event = record) }
        if (token == generation) mutableState.value = mutableState.value.copy(snapshot = null)
    }
    private fun requireSnapshot(sessionId: String?, babyId: Int): TimerRecordSnapshot {
        val snapshot = state.value.snapshot ?: throw TimerOperationException(TimerFailure.NOT_FOUND)
        checkIdentity(snapshot, sessionId)
        if (snapshot.ref.babyId != babyId) throw TimerOperationException(TimerFailure.STALE_REVISION)
        return snapshot
    }
    private fun checkIdentity(snapshot: TimerRecordSnapshot, sessionId: String?) {
        if (sessionId == null || snapshot.ref.sessionId != sessionId) throw TimerOperationException(TimerFailure.STALE_REVISION)
    }
    private fun notifyChanged(babyId: Int) {
        runCatching { BabyRecordChangeBus.notifyChanged(babyId) }
            .onFailure { LogUtils.w("计时已保存，派生刷新待重试：${it.javaClass.simpleName}") }
    }
    private fun message(error: Exception): Int = when ((error as? TimerOperationException)?.reason) {
        TimerFailure.NEEDS_REVIEW -> R.string.timer_review_required
        TimerFailure.STALE_REVISION, TimerFailure.NOT_FOUND -> R.string.record_draft_already_saved
        TimerFailure.INVALID_DATA, TimerFailure.INVALID_TRANSITION -> R.string.timer_invalid_change
        else -> R.string.save_failed
    }
}
