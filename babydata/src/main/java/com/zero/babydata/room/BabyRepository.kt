package com.zero.babydata.room

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord

class BabyRepository(context: Context) {

    private val babyInfoDao: BabyInfoDao
    private val feedingRecordDao: FeedingRecordDao
    private val sleepRecordDao: SleepRecordDao
    private val childDailyRecordDao: ChildDailyRecordDao
    private val eventRecordDao: EventRecordDao

    init {
        val db = BabyDatabase.getInstance(context)
        babyInfoDao = db.babyInfoDao()
        feedingRecordDao = db.feedingRecordDao()
        sleepRecordDao = db.sleepRecordDao()
        childDailyRecordDao = db.childDailyRecordDao()
        eventRecordDao = db.eventRecordDao()
    }

    private fun run(action: Runnable, callback: Runnable?) {
        BabyDatabase.getDatabaseWriteExecutor().execute {
            action.run()
            callback?.run()
        }
    }

    // ==================== BabyInfo ====================

    fun insertBabyInfo(babyInfo: BabyInfo, callback: Runnable?) {
        run({ babyInfoDao.insertBabyInfo(babyInfo) }, callback)
    }

    fun updateBabyInfo(babyInfo: BabyInfo, callback: Runnable? = null) {
        run({ babyInfoDao.updateBabyInfo(babyInfo) }, callback)
    }

    fun deleteBabyInfo(babyInfo: BabyInfo, callback: Runnable? = null) {
        run({ babyInfoDao.deleteBabyInfo(babyInfo) }, callback)
    }

    fun getBabyInfo(babyId: Int): LiveData<BabyInfo?> {
        return MutableLiveData(babyInfoDao.getBabyInfo(babyId))
    }

    fun getAllBabyInfo(): List<BabyInfo> {
        return babyInfoDao.getAllBabyInfo()
    }

    // ==================== FeedingRecord ====================

    fun insertFeedingRecord(feedingRecord: FeedingRecord, callback: Runnable?) {
        run({ feedingRecordDao.insertFeedingRecord(feedingRecord) }, callback)
    }

    fun updateFeedingRecord(feedingRecord: FeedingRecord, callback: Runnable? = null) {
        run({ feedingRecordDao.updateFeedingRecord(feedingRecord) }, callback)
    }

    fun deleteFeedingRecord(feedingRecord: FeedingRecord, callback: Runnable? = null) {
        run({ feedingRecordDao.deleteFeedingRecord(feedingRecord) }, callback)
    }

    fun deleteFeedingRecordById(feedingId: Int, callback: Runnable? = null) {
        run({ feedingRecordDao.deleteFeedingRecordById(feedingId) }, callback)
    }

    fun getAllFeedingRecords(babyId: Int): LiveData<List<FeedingRecord>> {
        return MutableLiveData(feedingRecordDao.getAllFeedingRecords(babyId))
    }

    fun getFeedingRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<FeedingRecord> {
        return feedingRecordDao.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)
    }

    fun getLastFeedingRecord(babyId: Int): FeedingRecord? {
        return feedingRecordDao.getLastFeedingRecord(babyId)
    }

    fun getRecentFeedings(babyId: Int, limit: Int): List<FeedingRecord> {
        return feedingRecordDao.getRecentFeedings(babyId, limit)
    }

    // ==================== SleepRecord ====================

    fun insertSleepRecord(sleepRecord: SleepRecord, callback: Runnable? = null) {
        run({ sleepRecordDao.insertSleepRecord(sleepRecord) }, callback)
    }

    fun updateSleepRecord(sleepRecord: SleepRecord, callback: Runnable? = null) {
        run({ sleepRecordDao.updateSleepRecord(sleepRecord) }, callback)
    }

    fun deleteSleepRecord(sleepRecord: SleepRecord, callback: Runnable? = null) {
        run({ sleepRecordDao.deleteSleepRecord(sleepRecord) }, callback)
    }

    fun deleteSleepRecordById(sleepId: Int, callback: Runnable? = null) {
        run({ sleepRecordDao.deleteSleepRecordById(sleepId) }, callback)
    }

    fun getAllSleepRecords(babyId: Int): LiveData<List<SleepRecord>> {
        return MutableLiveData(sleepRecordDao.getAllSleepRecords(babyId))
    }

    fun getSleepRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<SleepRecord> {
        return sleepRecordDao.getSleepRecordsForDay(babyId, startOfDay, endOfDay)
    }

    fun getLastSleepRecord(babyId: Int): SleepRecord? {
        return sleepRecordDao.getLastSleepRecord(babyId)
    }

    fun getRecentSleeps(babyId: Int, limit: Int): List<SleepRecord> {
        return sleepRecordDao.getRecentSleeps(babyId, limit)
    }

    // ==================== ChildDailyRecord ====================

    fun insertChildDailyRecord(record: ChildDailyRecord, callback: Runnable? = null) {
        run({ childDailyRecordDao.insertChildDailyRecord(record) }, callback)
    }

    fun updateChildDailyRecord(record: ChildDailyRecord, callback: Runnable? = null) {
        run({ childDailyRecordDao.updateChildDailyRecord(record) }, callback)
    }

    fun deleteChildDailyRecord(record: ChildDailyRecord, callback: Runnable? = null) {
        run({ childDailyRecordDao.deleteChildDailyRecord(record) }, callback)
    }

    fun getChildDailyRecordByDate(babyId: Int, recordDate: Long): ChildDailyRecord? {
        return childDailyRecordDao.getRecordByDate(babyId, recordDate)
    }

    fun getAllChildDailyRecords(babyId: Int): LiveData<List<ChildDailyRecord>> {
        return childDailyRecordDao.getAllRecordsByBabyId(babyId)
    }

    fun updateOrInsertChildDailyRecord(
        babyId: Int,
        recordDate: Long,
        weight: Float? = null,
        height: Float? = null,
        headCircumference: Float? = null,
        pic: String? = null,
        extra: String? = null
    ) {
        BabyDatabase.getDatabaseWriteExecutor().execute {
            val existingRecord = childDailyRecordDao.getRecordByDate(babyId, recordDate)

            if (existingRecord != null) {
                weight?.let { childDailyRecordDao.updateWeight(babyId, recordDate, it) }
                height?.let { childDailyRecordDao.updateHeight(babyId, recordDate, it) }
                headCircumference?.let { childDailyRecordDao.updateHeadCircumference(babyId, recordDate, it) }
                pic?.let { childDailyRecordDao.updatePic(babyId, recordDate, it) }
                extra?.let { childDailyRecordDao.updateExtra(babyId, recordDate, it) }
            } else {
                val newRecord = ChildDailyRecord(
                    babyId = babyId,
                    recordDate = recordDate,
                    weight = weight ?: 0f,
                    height = height ?: 0f,
                    headCircumference = headCircumference ?: 0f,
                    pic = pic ?: "",
                    extra = extra ?: ""
                )
                childDailyRecordDao.insertChildDailyRecord(newRecord)
            }
        }
    }

    // ==================== EventRecord ====================

    fun insertEventRecord(record: EventRecord, callback: Runnable? = null) {
        run({ eventRecordDao.insertEventRecord(record) }, callback)
    }

    fun updateEventRecord(record: EventRecord, callback: Runnable? = null) {
        run({ eventRecordDao.updateEventRecord(record) }, callback)
    }

    fun deleteEventRecord(record: EventRecord, callback: Runnable? = null) {
        run({ eventRecordDao.deleteEventRecord(record) }, callback)
    }

    fun getAllEventRecords(babyId: Int): List<EventRecord> {
        return eventRecordDao.getAllEventRecord(babyId)
    }

    fun getEventRecordsByType(babyId: Int, type: Int): List<EventRecord> {
        return eventRecordDao.getAllEventRecordByType(babyId, type)
    }
}
