package com.zero.babycare.home.record

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babydata.entity.SleepRecord
import com.zero.components.base.vm.BaseViewModel
import com.zero.babydata.domain.BabyDataHelper
import com.zero.babydata.room.BabyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zero.babycare.home.OngoingRecordManager
import com.zero.common.R

class SleepRecordViewModel(private val injectedRepository: BabyRepository? = null) : BaseViewModel() {
    // 延迟获取默认数据源，让草稿状态恢复独立于数据库初始化，并支持隔离的数据层验证。
    private val repository by lazy { injectedRepository ?: BabyDataHelper.repository }
    internal val mutation = RecordMutationController(viewModelScope)

    private val _lastSleepRecord = MutableLiveData<SleepRecord?>()
    val lastSleepRecord: LiveData<SleepRecord?> = _lastSleepRecord

    internal fun save(sleepRecord: SleepRecord, target: RecordMutationController.Target) {
        val submittedSession = OngoingRecordManager.getSleepSession(sleepRecord.babyId)
            ?.takeIf { it.startAt == sleepRecord.sleepStart }
        mutation.submit(target, RecordMutationController.Kind.SAVE, R.string.save_failed) {
            if (target.recordId != null) repository.updateSleepRecord(sleepRecord)
            else {
                OngoingSessionSavePolicy.save(
                    submittedSession = submittedSession,
                    persist = { repository.insertSleepRecordOnce(sleepRecord, target.draftId) },
                    currentSession = { OngoingRecordManager.getSleepSession(sleepRecord.babyId) },
                    finishSession = { OngoingRecordManager.endSleep(sleepRecord.babyId) }
                )
            }
            BabyRecordChangeBus.notifyChanged(sleepRecord.babyId)
        }
    }

    /** 删除结果由重建后的页面消费，后台写入不再反向持有旧 View。 */
    internal fun delete(sleepId: Int, target: RecordMutationController.Target) {
        mutation.submit(target, RecordMutationController.Kind.DELETE, R.string.record_delete_failed) {
            repository.deleteSleepRecordById(sleepId)
            BabyRecordChangeBus.notifyChanged(target.babyId)
        }
    }

    fun loadLastSleepRecord(babyId: Int) {
        // 查询走 IO，避免主线程阻塞
        viewModelScope.launch(Dispatchers.IO) {
            val record = repository.getLastSleepRecord(babyId)
            _lastSleepRecord.postValue(record)
        }
    }

    fun loadSleepRecordById(sleepId: Int, callback: (SleepRecord?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val record = repository.getSleepRecordById(sleepId)
            withContext(Dispatchers.Main) {
                callback(record)
            }
        }
    }

}
