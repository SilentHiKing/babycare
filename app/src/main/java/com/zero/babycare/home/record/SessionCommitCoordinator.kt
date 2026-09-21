package com.zero.babycare.home.record

import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.home.OngoingRecordManager.OngoingSession
import com.zero.babycare.home.OngoingRecordManager.RecordType
import com.zero.babydata.domain.*
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.room.BabyRepository
import com.zero.common.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 恢复和完成均读取同一业务行；提交不再跨 MMKV 和 Room 协调两份计时事实。 */
internal class SessionCommitCoordinator(
    private val scope: CoroutineScope,
    private val repository: () -> BabyRepository,
    private val type: RecordType,
    private val timer: PersistentTimerEditor = PersistentTimerEditor(scope, repository,
        if (type == RecordType.FEEDING) TimerRecordKind.FEEDING else TimerRecordKind.SLEEP)
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
    private val mutableCommitting = MutableStateFlow<Set<String>>(emptySet())
    val committingSessions = mutableCommitting.asStateFlow()
    private var recoveryJob: Job? = null
    private val kind get() = if (type == RecordType.FEEDING) TimerRecordKind.FEEDING else TimerRecordKind.SLEEP

    fun recover(target: RecordMutationController.Target, expectedSessionId: String? = null) {
        recoveryJob?.cancel()
        mutableRecovery.value = Recovery(target, true)
        recoveryJob = scope.launch {
            try {
                val snapshot = timer.recoverSession(target.babyId, expectedSessionId)
                val completed = snapshot?.status == com.zero.babydata.domain.TimerStatus.COMPLETED
                mutableRecovery.value = Recovery(target, false,
                    snapshot?.takeUnless { completed }?.let(OngoingRecordManager::fromSnapshot), completed)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableRecovery.value = Recovery(target, false, errorRes = R.string.save_failed) }
        }
    }

    suspend fun saveFeeding(record: FeedingRecord, draftId: String, sessionId: String?) {
        if (sessionId == null) { repository().insertFeedingRecordOnce(record, draftId); return }
        committing(sessionId) {
            timer.stopForSave(sessionId)
            timer.finishFeeding(record, draftId, sessionId)
        }
    }
    suspend fun saveSleep(record: SleepRecord, draftId: String, sessionId: String?) {
        if (sessionId == null) { repository().insertSleepRecordOnce(record, draftId); return }
        committing(sessionId) {
            timer.stopForSave(sessionId)
            timer.finishSleep(record, draftId, sessionId)
        }
    }
    private suspend fun committing(sessionId: String, action: suspend () -> Unit) {
        mutableCommitting.value = mutableCommitting.value + sessionId
        try { action() } finally { mutableCommitting.value = mutableCommitting.value - sessionId }
    }
}
