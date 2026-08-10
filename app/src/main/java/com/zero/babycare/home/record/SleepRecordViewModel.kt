package com.zero.babycare.home.record

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babydata.entity.SleepRecord
import com.zero.components.base.vm.BaseViewModel
import com.zero.babydata.domain.BabyDataHelper.repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SleepRecordViewModel : BaseViewModel() {

    private val _lastSleepRecord = MutableLiveData<SleepRecord?>()
    val lastSleepRecord: LiveData<SleepRecord?> = _lastSleepRecord

    fun insert(
        sleepRecord: SleepRecord,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        persistRecord(sleepRecord, onSuccess, onError) {
            repository.insertSleepRecord(sleepRecord)
        }
    }

    fun update(
        sleepRecord: SleepRecord,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        persistRecord(sleepRecord, onSuccess, onError) {
            repository.updateSleepRecord(sleepRecord)
        }
    }

    /** 删除记录后触发同一条变更链，避免统计或预测继续引用已删除数据。 */
    fun delete(sleepId: Int, babyId: Int, onSuccess: () -> Unit, onError: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.deleteSleepRecordById(sleepId)
                BabyRecordChangeBus.notifyChanged(babyId)
                onSuccess()
            } catch (_: Exception) {
                onError()
            }
        }
    }

    private fun persistRecord(
        sleepRecord: SleepRecord,
        onSuccess: () -> Unit,
        onError: () -> Unit,
        operation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            try {
                operation()
                BabyRecordChangeBus.notifyChanged(sleepRecord.babyId)
                onSuccess()
            } catch (_: Exception) {
                // 失败时不清空表单，页面恢复操作按钮后可以直接重试。
                onError()
            }
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
