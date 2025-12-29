package com.zero.babydata.room

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit

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

    fun getEventRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<EventRecord> {
        return eventRecordDao.getEventRecordsForDay(babyId, startOfDay, endOfDay)
    }

    fun deleteEventRecordById(eventId: Int, callback: Runnable? = null) {
        run({ eventRecordDao.deleteEventRecordById(eventId) }, callback)
    }

    fun getEventRecordById(eventId: Int): EventRecord? {
        return eventRecordDao.getEventRecordById(eventId)
    }

    fun getFeedingRecordById(feedingId: Int): FeedingRecord? {
        return feedingRecordDao.getFeedingRecordById(feedingId)
    }

    fun getSleepRecordById(sleepId: Int): SleepRecord? {
        return sleepRecordDao.getSleepRecordById(sleepId)
    }

    // ==================== 统计页面相关查询 ====================

    /**
     * 获取指定日期有记录的日期集合
     * @param babyId 宝宝ID
     * @param yearMonth 年月
     * @return 有记录的日期集合
     */
    fun getDatesWithRecords(babyId: Int, yearMonth: YearMonth): Set<LocalDate> {
        val startOfMonth = yearMonth.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfMonth = yearMonth.atEndOfMonth().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

        val feedingDates = feedingRecordDao.getDatesWithFeedings(babyId, startOfMonth, endOfMonth)
        val sleepDates = sleepRecordDao.getDatesWithSleeps(babyId, startOfMonth, endOfMonth)
        val eventDates = eventRecordDao.getDatesWithEvents(babyId, startOfMonth, endOfMonth)

        val allDates = mutableSetOf<LocalDate>()
        (feedingDates + sleepDates + eventDates).forEach { dateStr ->
            try {
                allDates.add(LocalDate.parse(dateStr))
            } catch (_: Exception) {
                // 忽略解析错误
            }
        }
        return allDates
    }

    /**
     * 数据类：当日统计摘要
     */
    data class DaySummaryData(
        val feedingCount: Int = 0,
        val feedingTotalMinutes: Int = 0,
        val feedingTotalMl: Int = 0,
        val sleepCount: Int = 0,
        val sleepTotalMinutes: Int = 0,
        val diaperWetCount: Int = 0,
        val diaperDirtyCount: Int = 0,
        val diaperMixedCount: Int = 0,
        val diaperDryCount: Int = 0,
        val otherEventCount: Int = 0
    )

    /**
     * 获取当日统计摘要
     */
    fun getDaySummary(babyId: Int, date: LocalDate): DaySummaryData {
        val startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

        // 喂养统计
        val feedings = feedingRecordDao.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)
        val feedingCount = feedings.size
        val feedingTotalMinutes = feedings.sumOf { 
            TimeUnit.MILLISECONDS.toMinutes(it.feedingDuration).toInt() 
        }
        val feedingTotalMl = feedings
            .filter {
                it.feedingType == FeedingType.FORMULA.type ||
                    it.feedingType == FeedingType.MIXED.type
            }
            .sumOf { it.feedingAmount ?: 0 }

        // 睡眠统计
        val sleeps = sleepRecordDao.getSleepRecordsForDay(babyId, startOfDay, endOfDay)
        val sleepCount = sleeps.size
        val sleepTotalMinutes = sleeps.sumOf { 
            TimeUnit.MILLISECONDS.toMinutes(it.sleepDuration).toInt() 
        }

        // 事件统计
        val events = eventRecordDao.getEventRecordsForDay(babyId, startOfDay, endOfDay)
        var diaperWetCount = 0
        var diaperDirtyCount = 0
        var diaperMixedCount = 0
        var diaperDryCount = 0
        var otherEventCount = 0

        events.forEach { event ->
            when (event.type) {
                EventType.DIAPER_WET -> diaperWetCount++
                EventType.DIAPER_DIRTY -> diaperDirtyCount++
                EventType.DIAPER_MIXED -> diaperMixedCount++
                EventType.DIAPER_DRY -> diaperDryCount++
                else -> otherEventCount++
            }
        }

        return DaySummaryData(
            feedingCount = feedingCount,
            feedingTotalMinutes = feedingTotalMinutes,
            feedingTotalMl = feedingTotalMl,
            sleepCount = sleepCount,
            sleepTotalMinutes = sleepTotalMinutes,
            diaperWetCount = diaperWetCount,
            diaperDirtyCount = diaperDirtyCount,
            diaperMixedCount = diaperMixedCount,
            diaperDryCount = diaperDryCount,
            otherEventCount = otherEventCount
        )
    }

    /**
     * 数据类：时间轴条目
     */
    sealed class TimelineRecord(open val time: Long) {
        data class Feeding(val record: FeedingRecord) : TimelineRecord(record.feedingStart)
        data class Sleep(val record: SleepRecord) : TimelineRecord(record.sleepStart)
        data class Event(val record: EventRecord) : TimelineRecord(record.time)
    }

    /**
     * 获取当日时间轴记录（按时间倒序）
     */
    fun getTimelineRecords(babyId: Int, date: LocalDate): List<TimelineRecord> {
        val startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

        val feedings = feedingRecordDao.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)
            .map { TimelineRecord.Feeding(it) }
        val sleeps = sleepRecordDao.getSleepRecordsForDay(babyId, startOfDay, endOfDay)
            .map { TimelineRecord.Sleep(it) }
        val events = eventRecordDao.getEventRecordsForDay(babyId, startOfDay, endOfDay)
            .map { TimelineRecord.Event(it) }

        return (feedings + sleeps + events).sortedByDescending { it.time }
    }
}
