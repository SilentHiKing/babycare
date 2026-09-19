package com.zero.babycare.home.record

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babydata.entity.FeedingRecord
import com.zero.components.base.vm.BaseViewModel
import com.zero.babydata.domain.BabyDataHelper
import com.zero.babydata.room.BabyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zero.babycare.home.OngoingRecordManager
import com.zero.common.R
import com.zero.common.util.TimerClockSnapshot

class FeedingRecordViewModel(private val injectedRepository: BabyRepository? = null) : BaseViewModel() {
    // 延迟获取默认数据源，让草稿状态恢复独立于数据库初始化，并支持隔离的数据层验证。
    private val repository by lazy { injectedRepository ?: BabyDataHelper.repository }
    internal val mutation = RecordMutationController(viewModelScope)
    internal val sessions = SessionCommitCoordinator(viewModelScope, { repository }, OngoingRecordManager.RecordType.FEEDING)

    private val _lastFeedingRecord = MutableLiveData<FeedingRecord?>()
    val lastFeedingRecord: LiveData<FeedingRecord?> = _lastFeedingRecord
    private var lastRequestedBabyId: Int? = null

    /** 页面只报告已校正的显示起点；持久会话按稳定身份同步，不能影响后来启动的记录。 */
    internal fun synchronizeTimerStart(babyId: Int, sessionId: String, start: Long, clock: TimerClockSnapshot) {
        OngoingRecordManager.synchronizeAutomaticStart(
            babyId, OngoingRecordManager.RecordType.FEEDING, sessionId, start, clock
        )
    }

    internal fun save(feedingRecord: FeedingRecord, target: RecordMutationController.Target, sessionId: String? = null) {
        val frozenRecord = feedingRecord.copy()
        mutation.submit(target, RecordMutationController.Kind.SAVE, R.string.save_failed) {
            if (target.recordId != null) repository.updateFeedingRecord(frozenRecord)
            else sessions.saveFeeding(frozenRecord, target.draftId, sessionId)
            // 保存已由 Room 确认，派生刷新失败只记录原因，不能让页面再次插入。
            runCatching { BabyRecordChangeBus.notifyChanged(feedingRecord.babyId) }
                .onFailure { com.blankj.utilcode.util.LogUtils.w("喂养已保存，派生状态刷新失败：${it.javaClass.simpleName}") }
        }
    }

    /** 删除后统一发出记录变更，界面仅消费有明确草稿归属的结果。 */
    internal fun delete(feedingId: Int, target: RecordMutationController.Target) {
        mutation.submit(target, RecordMutationController.Kind.DELETE, R.string.record_delete_failed) {
            repository.deleteFeedingRecordById(feedingId)
            BabyRecordChangeBus.notifyChanged(target.babyId)
        }
    }

    fun loadLastFeedingRecord(babyId: Int) {
        if (lastRequestedBabyId != babyId) _lastFeedingRecord.value = null
        lastRequestedBabyId = babyId
        // 查询走 IO；回到主线程核验请求归属，避免旧宝宝的慢查询覆盖新宝宝的结果。
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) { repository.getLastFeedingRecord(babyId) }
            if (lastRequestedBabyId == babyId) _lastFeedingRecord.value = record
        }
    }

    fun loadFeedingRecordById(feedingId: Int, callback: (FeedingRecord?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val record = repository.getFeedingRecordById(feedingId)
            withContext(Dispatchers.Main) {
                callback(record)
            }
        }
    }

}
