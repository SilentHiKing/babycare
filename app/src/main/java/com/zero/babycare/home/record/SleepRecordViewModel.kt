package com.zero.babycare.home.record

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.ThreadUtils
import com.zero.babydata.entity.SleepRecord
import com.zero.components.base.vm.BaseViewModel
import com.zero.babydata.domain.BabyDataHelper.repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SleepRecordViewModel : BaseViewModel() {

    private val _lastSleepRecord = MutableLiveData<SleepRecord?>()
    val lastSleepRecord: LiveData<SleepRecord?> = _lastSleepRecord

    fun insert(sleepRecord: SleepRecord, callback: () -> Unit) {
        safeLaunch {
            repository.insertSleepRecord(sleepRecord) {
                ThreadUtils.runOnUiThread { callback() }
            }
        }
    }

    fun update(sleepRecord: SleepRecord, callback: () -> Unit) {
        safeLaunch {
            repository.updateSleepRecord(sleepRecord) {
                ThreadUtils.runOnUiThread { callback() }
            }
        }
    }

    fun loadLastSleepRecord(babyId: Int) {
        safeLaunch {
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

    fun getRecentSleeps(babyId: Int, limit: Int): List<SleepRecord> {
        return repository.getRecentSleeps(babyId, limit)
    }
}
