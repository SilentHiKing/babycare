package com.zero.babycare.home.record

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.ThreadUtils
import com.zero.babydata.entity.FeedingRecord
import com.zero.components.base.vm.BaseViewModel
import com.zero.babydata.domain.BabyDataHelper.repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FeedingRecordViewModel : BaseViewModel() {

    private val _lastFeedingRecord = MutableLiveData<FeedingRecord?>()
    val lastFeedingRecord: LiveData<FeedingRecord?> = _lastFeedingRecord

    fun insert(feedingRecord: FeedingRecord, callback: Runnable) {
        safeLaunch {
            repository.insertFeedingRecord(feedingRecord, callback)
        }
    }

    fun update(feedingRecord: FeedingRecord, callback: Runnable) {
        safeLaunch {
            repository.updateFeedingRecord(feedingRecord) {
                ThreadUtils.runOnUiThread { callback.run() }
            }
        }
    }

    fun loadLastFeedingRecord(babyId: Int) {
        safeLaunch {
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

    fun getRecentFeedings(babyId: Int, limit: Int): List<FeedingRecord> {
        return repository.getRecentFeedings(babyId, limit)
    }
}
