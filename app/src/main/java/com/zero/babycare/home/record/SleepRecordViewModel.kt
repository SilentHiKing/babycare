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
import com.zero.common.util.TimerClockSnapshot

class SleepRecordViewModel(private val injectedRepository: BabyRepository? = null) : BaseViewModel() {
    // 延迟获取默认数据源，让草稿状态恢复独立于数据库初始化，并支持隔离的数据层验证。
    private val repository by lazy { injectedRepository ?: BabyDataHelper.repository }
    internal val mutation = RecordMutationController(viewModelScope)
    internal val sessions = SessionCommitCoordinator(viewModelScope, { repository }, OngoingRecordManager.RecordType.SLEEP)

    private val _lastSleepRecord = MutableLiveData<SleepRecord?>()
    val lastSleepRecord: LiveData<SleepRecord?> = _lastSleepRecord

    internal fun save(sleepRecord: SleepRecord, target: RecordMutationController.Target, sessionId: String? = null) {
        val frozenRecord = sleepRecord.copy()
        mutation.submit(target, RecordMutationController.Kind.SAVE, R.string.save_failed) {
            if (target.recordId != null) repository.updateSleepRecord(frozenRecord)
            else sessions.saveSleep(frozenRecord, target.draftId, sessionId)
            // 数据事务成功后通知刷新失败不能误报保存失败，引导用户再次创建记录。
            runCatching { BabyRecordChangeBus.notifyChanged(sleepRecord.babyId) }
                .onFailure { com.blankj.utilcode.util.LogUtils.w("睡眠已保存，派生状态刷新失败：${it.javaClass.simpleName}") }
        }
    }

    /** 时钟同步只更新同一自动计时会话，旧表单不能覆盖后来开启的睡眠。 */
    internal fun synchronizeAutomaticStart(babyId: Int, sessionId: String, startTime: Long, clock: TimerClockSnapshot) {
        OngoingRecordManager.synchronizeAutomaticStart(
            babyId, OngoingRecordManager.RecordType.SLEEP, sessionId, startTime, clock
        )
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
