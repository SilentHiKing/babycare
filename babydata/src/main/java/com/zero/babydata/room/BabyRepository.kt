package com.zero.babydata.room

import android.content.Context
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.domain.RecordDurationCalculator
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

/**
 * BabyCare 数据访问边界。
 *
 * 所有一次性 Room 操作都通过挂起函数暴露，让调用方获得真实的成功或失败结果；
 * 持续观察的数据使用 Flow，避免回调线程与生命周期脱节。
 */
class BabyRepository(context: Context) {

    private val database = BabyDatabase.getInstance(context)
    private val babyInfoDao = database.babyInfoDao()
    private val feedingRecordDao = database.feedingRecordDao()
    private val sleepRecordDao = database.sleepRecordDao()
    private val childDailyRecordDao = database.childDailyRecordDao()
    private val eventRecordDao = database.eventRecordDao()

    // ==================== BabyInfo ====================

    suspend fun insertBabyInfo(babyInfo: BabyInfo): Int {
        // 回填 Room 生成的主键，保证随后写入的记录不会继续引用 babyId=0。
        return babyInfoDao.insertBabyInfo(babyInfo).toInt().also { babyInfo.babyId = it }
    }

    suspend fun updateBabyInfo(babyInfo: BabyInfo) = babyInfoDao.updateBabyInfo(babyInfo)

    suspend fun deleteBabyInfo(babyInfo: BabyInfo) = babyInfoDao.deleteBabyInfo(babyInfo)

    suspend fun getBabyInfo(babyId: Int): BabyInfo? = babyInfoDao.getBabyInfo(babyId)

    suspend fun getAllBabyInfo(): List<BabyInfo> = babyInfoDao.getAllBabyInfo()

    fun observeAllBabyInfo(): Flow<List<BabyInfo>> = babyInfoDao.observeAllBabyInfo()

    // ==================== FeedingRecord ====================

    suspend fun insertFeedingRecord(feedingRecord: FeedingRecord) =
        feedingRecordDao.insertFeedingRecord(feedingRecord)

    suspend fun updateFeedingRecord(feedingRecord: FeedingRecord) =
        feedingRecordDao.updateFeedingRecord(feedingRecord)

    suspend fun deleteFeedingRecord(feedingRecord: FeedingRecord) =
        feedingRecordDao.deleteFeedingRecord(feedingRecord)

    suspend fun deleteFeedingRecordById(feedingId: Int) =
        feedingRecordDao.deleteFeedingRecordById(feedingId)

    suspend fun getAllFeedingRecords(babyId: Int): List<FeedingRecord> =
        feedingRecordDao.getAllFeedingRecords(babyId)

    suspend fun getFeedingRecordsForDay(
        babyId: Int,
        startOfDay: Long,
        endOfDay: Long
    ): List<FeedingRecord> = feedingRecordDao.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)

    suspend fun getFeedingRecordsIntersectingDay(
        babyId: Int,
        startOfDay: Long,
        endOfDay: Long
    ): List<FeedingRecord> = feedingRecordDao.getFeedingRecordsIntersectingDay(babyId, startOfDay, endOfDay)

    suspend fun getFeedingRecordsIntersectingRange(
        babyId: Int,
        startTime: Long,
        endTime: Long
    ): List<FeedingRecord> = feedingRecordDao.getFeedingRecordsIntersectingRange(babyId, startTime, endTime)

    suspend fun getFeedingRecordsBetween(
        babyId: Int,
        startTime: Long,
        endTime: Long
    ): List<FeedingRecord> = feedingRecordDao.getFeedingRecordsBetween(babyId, startTime, endTime)

    suspend fun getLastFeedingRecord(babyId: Int): FeedingRecord? =
        feedingRecordDao.getLastFeedingRecord(babyId)

    suspend fun getRecentFeedings(babyId: Int, limit: Int): List<FeedingRecord> =
        feedingRecordDao.getRecentFeedings(babyId, limit)

    suspend fun getFeedingRecordById(feedingId: Int): FeedingRecord? =
        feedingRecordDao.getFeedingRecordById(feedingId)

    // ==================== SleepRecord ====================

    suspend fun insertSleepRecord(sleepRecord: SleepRecord) =
        sleepRecordDao.insertSleepRecord(sleepRecord)

    suspend fun updateSleepRecord(sleepRecord: SleepRecord) =
        sleepRecordDao.updateSleepRecord(sleepRecord)

    suspend fun deleteSleepRecord(sleepRecord: SleepRecord) =
        sleepRecordDao.deleteSleepRecord(sleepRecord)

    suspend fun deleteSleepRecordById(sleepId: Int) =
        sleepRecordDao.deleteSleepRecordById(sleepId)

    suspend fun getAllSleepRecords(babyId: Int): List<SleepRecord> =
        sleepRecordDao.getAllSleepRecords(babyId)

    suspend fun getSleepRecordsForDay(
        babyId: Int,
        startOfDay: Long,
        endOfDay: Long
    ): List<SleepRecord> = sleepRecordDao.getSleepRecordsForDay(babyId, startOfDay, endOfDay)

    suspend fun getSleepRecordsIntersectingDay(
        babyId: Int,
        startOfDay: Long,
        endOfDay: Long
    ): List<SleepRecord> = sleepRecordDao.getSleepRecordsIntersectingDay(babyId, startOfDay, endOfDay)

    suspend fun getSleepRecordsIntersectingRange(
        babyId: Int,
        startTime: Long,
        endTime: Long
    ): List<SleepRecord> = sleepRecordDao.getSleepRecordsIntersectingRange(babyId, startTime, endTime)

    suspend fun getSleepRecordsBetween(
        babyId: Int,
        startTime: Long,
        endTime: Long
    ): List<SleepRecord> = sleepRecordDao.getSleepRecordsBetween(babyId, startTime, endTime)

    suspend fun getLastSleepRecord(babyId: Int): SleepRecord? =
        sleepRecordDao.getLastSleepRecord(babyId)

    suspend fun getRecentSleeps(babyId: Int, limit: Int): List<SleepRecord> =
        sleepRecordDao.getRecentSleeps(babyId, limit)

    suspend fun getSleepRecordById(sleepId: Int): SleepRecord? =
        sleepRecordDao.getSleepRecordById(sleepId)

    // ==================== ChildDailyRecord ====================

    suspend fun insertChildDailyRecord(record: ChildDailyRecord) =
        childDailyRecordDao.insertChildDailyRecord(record)

    suspend fun updateChildDailyRecord(record: ChildDailyRecord) =
        childDailyRecordDao.updateChildDailyRecord(record)

    suspend fun deleteChildDailyRecord(record: ChildDailyRecord) =
        childDailyRecordDao.deleteChildDailyRecord(record)

    suspend fun getChildDailyRecordByDate(babyId: Int, recordDate: Long): ChildDailyRecord? =
        childDailyRecordDao.getRecordByDate(babyId, recordDate)

    suspend fun getAllChildDailyRecords(babyId: Int): List<ChildDailyRecord> =
        childDailyRecordDao.getAllRecordsByBabyId(babyId)

    fun observeAllChildDailyRecords(babyId: Int): Flow<List<ChildDailyRecord>> =
        childDailyRecordDao.observeAllRecordsByBabyId(babyId)

    suspend fun updateOrInsertChildDailyRecord(
        babyId: Int,
        recordDate: Long,
        weight: Float? = null,
        height: Float? = null,
        headCircumference: Float? = null,
        pic: String? = null,
        extra: String? = null
    ) = childDailyRecordDao.updateOrInsert(
        babyId = babyId,
        recordDate = recordDate,
        weight = weight,
        height = height,
        headCircumference = headCircumference,
        pic = pic,
        extra = extra
    )

    // ==================== EventRecord ====================

    suspend fun insertEventRecord(record: EventRecord) = eventRecordDao.insertEventRecord(record)

    suspend fun updateEventRecord(record: EventRecord) = eventRecordDao.updateEventRecord(record)

    suspend fun deleteEventRecord(record: EventRecord) = eventRecordDao.deleteEventRecord(record)

    suspend fun getAllEventRecords(babyId: Int): List<EventRecord> =
        eventRecordDao.getAllEventRecord(babyId)

    suspend fun getEventRecordsByType(babyId: Int, type: Int): List<EventRecord> =
        eventRecordDao.getAllEventRecordByType(babyId, type)

    suspend fun getLatestEventRecordsByType(
        babyId: Int,
        type: Int,
        limit: Int
    ): List<EventRecord> = eventRecordDao.getLatestEventRecordsByType(babyId, type, limit)

    suspend fun getEventRecordsForDay(
        babyId: Int,
        startOfDay: Long,
        endOfDay: Long
    ): List<EventRecord> = eventRecordDao.getEventRecordsForDay(babyId, startOfDay, endOfDay)

    suspend fun getEventRecordsBetween(
        babyId: Int,
        startTime: Long,
        endTime: Long
    ): List<EventRecord> = eventRecordDao.getEventRecordsBetween(babyId, startTime, endTime)

    suspend fun deleteEventRecordById(eventId: Int) =
        eventRecordDao.deleteEventRecordById(eventId)

    suspend fun getEventRecordById(eventId: Int): EventRecord? =
        eventRecordDao.getEventRecordById(eventId)

    // ==================== 统计页面相关查询 ====================

    /** 获取指定月份存在任意生活记录的日期集合。 */
    suspend fun getDatesWithRecords(babyId: Int, yearMonth: YearMonth): Set<LocalDate> {
        val startOfMonth = yearMonth.atDay(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val endOfMonth = yearMonth.atEndOfMonth()
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli() - 1

        val feedingDates = feedingRecordDao.getDatesWithFeedings(babyId, startOfMonth, endOfMonth)
        val sleepDates = sleepRecordDao.getDatesWithSleeps(babyId, startOfMonth, endOfMonth)
        val eventDates = eventRecordDao.getDatesWithEvents(babyId, startOfMonth, endOfMonth)

        return buildSet {
            (feedingDates + sleepDates + eventDates).forEach { dateText ->
                runCatching { LocalDate.parse(dateText) }.getOrNull()?.let(::add)
            }
        }
    }

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

    /** 获取当日喂养、睡眠、排泄与其他事件摘要。 */
    suspend fun getDaySummary(babyId: Int, date: LocalDate): DaySummaryData {
        val startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = date.plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli() - 1

        val feedings = feedingRecordDao.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)
        val sleeps = sleepRecordDao.getSleepRecordsForDay(babyId, startOfDay, endOfDay)
        // 次数按开始日计入，时长按当天重叠区间分摊，避免跨夜睡眠全部归到前一天。
        val overlappingFeedings = feedingRecordDao.getFeedingRecordsIntersectingDay(babyId, startOfDay, endOfDay)
        val overlappingSleeps = sleepRecordDao.getSleepRecordsIntersectingDay(babyId, startOfDay, endOfDay)
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
            feedingCount = feedings.size,
            feedingTotalMinutes = TimeUnit.MILLISECONDS.toMinutes(overlappingFeedings.sumOf {
                RecordDurationCalculator.overlappedDuration(
                    it.feedingStart, it.feedingEnd, it.feedingDuration, startOfDay, endOfDay
                )
            }).toInt(),
            feedingTotalMl = feedings
                .filter {
                    it.feedingType == FeedingType.FORMULA.type ||
                        it.feedingType == FeedingType.MIXED.type
                }
                .sumOf { it.feedingAmount ?: 0 },
            sleepCount = sleeps.size,
            sleepTotalMinutes = TimeUnit.MILLISECONDS.toMinutes(overlappingSleeps.sumOf {
                RecordDurationCalculator.overlappedDuration(
                    it.sleepStart, it.sleepEnd, it.sleepDuration, startOfDay, endOfDay
                )
            }).toInt(),
            diaperWetCount = diaperWetCount,
            diaperDirtyCount = diaperDirtyCount,
            diaperMixedCount = diaperMixedCount,
            diaperDryCount = diaperDryCount,
            otherEventCount = otherEventCount
        )
    }

    sealed class TimelineRecord(open val time: Long) {
        data class Feeding(val record: FeedingRecord) : TimelineRecord(record.feedingStart)
        data class Sleep(val record: SleepRecord) : TimelineRecord(record.sleepStart)
        data class Event(val record: EventRecord) : TimelineRecord(record.time)
    }

    /** 获取当日时间轴记录，并按发生时间倒序返回。 */
    suspend fun getTimelineRecords(babyId: Int, date: LocalDate): List<TimelineRecord> {
        val startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = date.plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli() - 1

        val feedings = feedingRecordDao.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)
            .map { TimelineRecord.Feeding(it) }
        val sleeps = sleepRecordDao.getSleepRecordsForDay(babyId, startOfDay, endOfDay)
            .map { TimelineRecord.Sleep(it) }
        val events = eventRecordDao.getEventRecordsForDay(babyId, startOfDay, endOfDay)
            .map { TimelineRecord.Event(it) }

        return (feedings + sleeps + events).sortedByDescending { it.time }
    }
}
