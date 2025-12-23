package com.zero.babycare.home.record

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zero.babydata.entity.FeedingRecord
import com.zero.components.base.vm.BaseViewModel
import com.zero.babydata.domain.BabyDataHelper.repository

class FeedingRecordViewModel : BaseViewModel() {

    private val _lastFeedingRecord = MutableLiveData<FeedingRecord?>()
    val lastFeedingRecord: LiveData<FeedingRecord?> = _lastFeedingRecord

    fun insert(feedingRecord: FeedingRecord, callback: Runnable) {
        safeLaunch {
            repository.insertFeedingRecord(feedingRecord, callback)
        }
    }

    fun loadLastFeedingRecord(babyId: Int) {
        safeLaunch {
            val record = repository.getLastFeedingRecord(babyId)
            _lastFeedingRecord.postValue(record)
        }
    }

    fun getRecentFeedings(babyId: Int, limit: Int): List<FeedingRecord> {
        return repository.getRecentFeedings(babyId, limit)
    }
}
