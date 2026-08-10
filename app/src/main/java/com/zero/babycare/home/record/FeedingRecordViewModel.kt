package com.zero.babycare.home.record

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babydata.entity.FeedingRecord
import com.zero.components.base.vm.BaseViewModel
import com.zero.babydata.domain.BabyDataHelper.repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FeedingRecordViewModel : BaseViewModel() {

    private val _lastFeedingRecord = MutableLiveData<FeedingRecord?>()
    val lastFeedingRecord: LiveData<FeedingRecord?> = _lastFeedingRecord

    fun insert(
        feedingRecord: FeedingRecord,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        persistRecord(feedingRecord, onSuccess, onError) {
            repository.insertFeedingRecord(feedingRecord)
        }
    }

    fun update(
        feedingRecord: FeedingRecord,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        persistRecord(feedingRecord, onSuccess, onError) {
            repository.updateFeedingRecord(feedingRecord)
        }
    }

    /** 删除后统一发出记录变更，确保首页、统计和预测都基于最新数据重算。 */
    fun delete(feedingId: Int, babyId: Int, onSuccess: () -> Unit, onError: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.deleteFeedingRecordById(feedingId)
                BabyRecordChangeBus.notifyChanged(babyId)
                onSuccess()
            } catch (_: Exception) {
                onError()
            }
        }
    }

    private fun persistRecord(
        feedingRecord: FeedingRecord,
        onSuccess: () -> Unit,
        onError: () -> Unit,
        operation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            try {
                operation()
                BabyRecordChangeBus.notifyChanged(feedingRecord.babyId)
                onSuccess()
            } catch (_: Exception) {
                // 数据库失败时保留当前表单，由页面恢复操作按钮供用户重试。
                onError()
            }
        }
    }

    fun loadLastFeedingRecord(babyId: Int) {
        // 查询走 IO，避免主线程阻塞
        viewModelScope.launch(Dispatchers.IO) {
            val record = repository.getLastFeedingRecord(babyId)
            _lastFeedingRecord.postValue(record)
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
