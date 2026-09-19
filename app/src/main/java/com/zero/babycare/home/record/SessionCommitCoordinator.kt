package com.zero.babycare.home.record

import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.home.OngoingRecordManager.OngoingSession
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babydata.domain.RecordWriteException
import com.zero.babydata.domain.RecordWriteFailure
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyRepository
import com.zero.common.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 同一计时会话只有一个提交身份；页面草稿只承担输入和导航归属，不承担跨存储幂等。 */
internal class SessionCommitCoordinator(
    private val scope: CoroutineScope,
    private val repository: () -> BabyRepository,
    private val type: RecordType
) {
    data class Recovery(
        val target: RecordMutationController.Target,
        val loading: Boolean,
        val session: OngoingSession? = null,
        val committed: Boolean = false,
        val errorRes: Int? = null
    )

    private val mutableRecovery = MutableStateFlow<Recovery?>(null)
    val recovery = mutableRecovery.asStateFlow()
    val committingSessions = OngoingRecordManager.committingSessions
    private var recoveryJob: Job? = null

    /** 恢复绝不自动写记录；先核验已提交回执，未提交才允许页面继续编辑这段会话。 */
    fun recover(target: RecordMutationController.Target, expectedSessionId: String? = null) {
        recoveryJob?.cancel()
        mutableRecovery.value = Recovery(target, loading = true)
        recoveryJob = scope.launch {
            var attemptedSession: OngoingSession? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val initial = OngoingRecordManager.getSession(target.babyId, type)
                    attemptedSession = initial
                    val sessionId = expectedSessionId ?: initial?.sessionId
                    if (sessionId == null) return@withContext Recovery(target, loading = false)
                    acquire(sessionId)
                    try {
                        val session = OngoingRecordManager.getSession(target.babyId, type)
                        attemptedSession = session?.takeIf { it.sessionId == sessionId }
                        if (session?.sessionId != sessionId) {
                            // 已清理的旧页面不能借同一时间戳绑定后来启动的会话。
                            if (repository().hasRecordWriteReceipt(OngoingRecordManager.requestId(sessionId))) {
                                return@withContext Recovery(target, loading = false, committed = true)
                            }
                            throw RecordWriteException(RecordWriteFailure.CONTENT_CONFLICT)
                        }
                        val committed = findCommitted(session)
                        OngoingRecordManager.resolveCommit(session, committed)
                        Recovery(target, loading = false, session = if (committed) null else session,
                            committed = committed)
                    } finally {
                        OngoingRecordManager.releaseCommit(sessionId)
                    }
                }
                mutableRecovery.value = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableRecovery.value = Recovery(target, loading = false, session = attemptedSession,
                    errorRes = errorResource(failure))
            }
        }
    }

    suspend fun saveFeeding(record: FeedingRecord, draftId: String, sessionId: String?) {
        save(record.babyId, record.feedingStart, sessionId,
            prepare = { OngoingRecordManager.prepareCommit(it, feeding = record) },
            persist = { repository().insertFeedingRecordOnce(record, it) }, draftId = draftId)
    }

    suspend fun saveSleep(record: SleepRecord, draftId: String, sessionId: String?) {
        save(record.babyId, record.sleepStart, sessionId,
            prepare = { OngoingRecordManager.prepareCommit(it, sleep = record) },
            persist = { repository().insertSleepRecordOnce(record, it) }, draftId = draftId)
    }

    private suspend fun save(
        babyId: Int,
        startAt: Long,
        sessionId: String?,
        prepare: (OngoingSession) -> OngoingSession,
        persist: suspend (String) -> Int,
        draftId: String
    ) = withContext(Dispatchers.IO) {
        if (sessionId == null) {
            persist(draftId)
            return@withContext
        }
        acquire(sessionId)
        try {
            val session = OngoingRecordManager.getSession(babyId, type)
            if (session?.sessionId != sessionId || session.startAt != startAt) {
                throw RecordWriteException(RecordWriteFailure.CONTENT_CONFLICT)
            }
            // 完整暂停快照先同步落盘；失败或取消保留它供下一入口核验，不持久化进程锁。
            val frozen = prepare(session)
            persist(OngoingRecordManager.requestId(sessionId))
            // Room 是成功标准；取消恰好发生在返回边界时，恢复仍会查到同一回执。
            runCatching { OngoingRecordManager.resolveCommit(frozen, committed = true) }
                .onFailure { com.blankj.utilcode.util.LogUtils.w("记录已提交，会话清理待恢复：${it.javaClass.simpleName}") }
        } finally {
            OngoingRecordManager.releaseCommit(sessionId)
        }
    }

    private suspend fun findCommitted(session: OngoingSession): Boolean {
        val requestId = OngoingRecordManager.requestId(requireNotNull(session.sessionId))
        val recordId = when (type) {
            RecordType.FEEDING -> session.pendingFeeding?.let {
                repository().findCommittedFeedingRecord(it, requestId)
            }
            RecordType.SLEEP -> session.pendingSleep?.let {
                repository().findCommittedSleepRecord(it, requestId)
            }
        }
        if (recordId != null) return true
        // 缺失/损坏的快照不能按时间猜内容，也不能换 token 重插；保留输入并给出冲突反馈。
        if (repository().hasRecordWriteReceipt(requestId)) {
            throw RecordWriteException(RecordWriteFailure.CONTENT_CONFLICT)
        }
        return false
    }

    private suspend fun acquire(sessionId: String) {
        while (!OngoingRecordManager.tryAcquireCommit(sessionId)) {
            committingSessions.first { sessionId !in it }
        }
    }

    private fun errorResource(failure: Exception): Int = when ((failure as? RecordWriteException)?.reason) {
        RecordWriteFailure.CONTENT_CONFLICT -> R.string.record_draft_already_saved
        RecordWriteFailure.RECORD_DELETED -> R.string.record_draft_record_deleted
        else -> R.string.save_failed
    }
}
