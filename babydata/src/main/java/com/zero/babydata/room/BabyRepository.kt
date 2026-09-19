package com.zero.babydata.room

import android.content.Context
import androidx.room.withTransaction
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.ChildDailyRecord
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.domain.RecordDurationCalculator
import com.zero.babydata.domain.RecordWriteException
import com.zero.babydata.domain.RecordWriteFailure
import com.zero.babydata.domain.RecordWritePayload
import com.zero.babydata.entity.RecordWriteReceipt
import com.zero.babydata.entity.BabyCreationReceipt
import com.zero.babydata.domain.BabyWriteException
import com.zero.babydata.domain.BabyWriteFailure
import java.util.UUID
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
class BabyRepository(private val database: BabyDatabase) {
    constructor(context: Context) : this(BabyDatabase.getInstance(context))

    private val babyInfoDao = database.babyInfoDao()
    private val feedingRecordDao = database.feedingRecordDao()
    private val sleepRecordDao = database.sleepRecordDao()
    private val childDailyRecordDao = database.childDailyRecordDao()
    private val eventRecordDao = database.eventRecordDao()
    private val recordWriteReceiptDao = database.recordWriteReceiptDao()

    // ==================== BabyInfo ====================

    suspend fun insertBabyInfo(babyInfo: BabyInfo): Int {
        // 回填 Room 生成的主键，保证随后写入的记录不会继续引用 babyId=0。
        return babyInfoDao.insertBabyInfo(babyInfo).toInt().also { babyInfo.babyId = it }
    }

    /** stableId 随新建草稿保存；回执与宝宝同事务提交，重试保留后来编辑，删除后不复活。 */
    suspend fun insertBabyInfoOnce(babyInfo: BabyInfo): Int {
        val snapshot = babyInfo.copy(babyId = 0)
        val normalizedId = runCatching { UUID.fromString(snapshot.stableId).toString() }.getOrNull()
        if (normalizedId == null || normalizedId != snapshot.stableId ||
            !snapshot.birthWeight.isFinite() || !snapshot.birthHeight.isFinite()) {
            throw BabyWriteException(BabyWriteFailure.INVALID_REQUEST)
        }
        // 业务摘要刻意不含身份；历史 UUID 的大小写兼容不能改变既有回执摘要协议。
        val payloadHash = RecordWritePayload.baby(snapshot)
        return database.withTransaction {
            val receipts = database.babyCreationReceiptDao()
            val receipt = receipts.getReceipt(normalizedId)
            if (receipt != null) {
                if (receipt.payloadHash != payloadHash) throw BabyWriteException(BabyWriteFailure.CONTENT_CONFLICT)
                val saved = babyInfoDao.getBabyInfo(receipt.babyId)
                // 只承认回执原本地 ID 下的完整 UUID；删除或覆盖后的同 UUID 新行不能重绑。
                if (saved == null || !saved.stableId.equals(normalizedId, ignoreCase = true)) {
                    throw BabyWriteException(BabyWriteFailure.BABY_DELETED)
                }
                return@withTransaction receipt.babyId
            }
            // 已通过备份恢复的相同身份也不能重复创建；首次绑定时只接受完全相同的业务内容。
            val existing = babyInfoDao.findBabyByStableId(normalizedId) ?: run {
                // 历史备份允许大写 UUID；没有精确目标时只绑定唯一候选，不改写其原始身份。
                val candidates = babyInfoDao.findBabiesByStableIdIgnoringCase(normalizedId)
                if (candidates.size > 1) throw BabyWriteException(BabyWriteFailure.CONTENT_CONFLICT)
                candidates.singleOrNull()
            }
            if (existing != null && RecordWritePayload.baby(existing) != payloadHash) {
                throw BabyWriteException(BabyWriteFailure.CONTENT_CONFLICT)
            }
            val id = existing?.babyId ?: babyInfoDao.insertBabyInfo(snapshot).toInt()
            receipts.insertReceipt(BabyCreationReceipt(normalizedId, id, payloadHash))
            id
        }
    }

    /** 身份核验与更新同事务，删除/覆盖期间的旧编辑不能将零行更新当作成功。 */
    suspend fun updateBabyInfoIfPresent(babyInfo: BabyInfo): Boolean {
        val snapshot = babyInfo.copy()
        return database.withTransaction {
            if (babyInfoDao.getBabyInfo(snapshot.babyId)?.stableId != snapshot.stableId) return@withTransaction false
            babyInfoDao.updateBabyInfo(snapshot)
            true
        }
    }

    suspend fun updateBabyInfo(babyInfo: BabyInfo) = babyInfoDao.updateBabyInfo(babyInfo)

    suspend fun deleteBabyInfo(babyInfo: BabyInfo) = babyInfoDao.deleteBabyInfo(babyInfo)

    suspend fun getBabyInfo(babyId: Int): BabyInfo? = babyInfoDao.getBabyInfo(babyId)

    suspend fun getAllBabyInfo(): List<BabyInfo> = babyInfoDao.getAllBabyInfo()

    fun observeAllBabyInfo(): Flow<List<BabyInfo>> = babyInfoDao.observeAllBabyInfo()

    // ==================== FeedingRecord ====================

    suspend fun insertFeedingRecord(feedingRecord: FeedingRecord) {
        feedingRecordDao.insertFeedingRecord(feedingRecord)
    }

    /** 同一草稿的跨进程重试只提交一次；自增 ID 和创建时间不参与业务内容比较。 */
    suspend fun insertFeedingRecordOnce(record: FeedingRecord, requestId: String): Int {
        val snapshot = record.copy(feedingId = 0)
        return insertRecordOnce(
            requestId, snapshot.babyId, RecordWritePayload.FEEDING, RecordWritePayload.feeding(snapshot),
            insert = { feedingRecordDao.insertFeedingRecord(snapshot) },
            recordOwner = { feedingRecordDao.getFeedingRecordById(it)?.babyId }
        )
    }

    suspend fun updateFeedingRecord(feedingRecord: FeedingRecord) {
        val snapshot = feedingRecord.copy()
        database.withTransaction {
            // 覆盖/删除可能先于排队的编辑提交；零行更新不能伪报保存成功，也不能迁移宝宝归属。
            if (feedingRecordDao.getFeedingRecordById(snapshot.feedingId)?.babyId != snapshot.babyId) {
                throw RecordWriteException(RecordWriteFailure.RECORD_DELETED)
            }
            feedingRecordDao.updateFeedingRecord(snapshot)
        }
    }

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

    /** 预测只使用最近已完成的奶类记录，限制在数据库筛选之后生效。 */
    suspend fun getRecentCompletedMilkFeedings(babyId: Int, limit: Int): List<FeedingRecord> =
        feedingRecordDao.getRecentCompletedMilkFeedings(
            babyId, limit, listOf(FeedingType.BREAST.type, FeedingType.FORMULA.type, FeedingType.MIXED.type)
        )

    suspend fun getFeedingRecordById(feedingId: Int): FeedingRecord? =
        feedingRecordDao.getFeedingRecordById(feedingId)

    // ==================== SleepRecord ====================

    suspend fun insertSleepRecord(sleepRecord: SleepRecord) {
        sleepRecordDao.insertSleepRecord(sleepRecord)
    }

    suspend fun insertSleepRecordOnce(record: SleepRecord, requestId: String): Int {
        val snapshot = record.copy(sleepId = 0)
        return insertRecordOnce(
            requestId, snapshot.babyId, RecordWritePayload.SLEEP, RecordWritePayload.sleep(snapshot),
            insert = { sleepRecordDao.insertSleepRecord(snapshot) },
            recordOwner = { sleepRecordDao.getSleepRecordById(it)?.babyId }
        )
    }

    suspend fun updateSleepRecord(sleepRecord: SleepRecord) {
        val snapshot = sleepRecord.copy()
        database.withTransaction {
            if (sleepRecordDao.getSleepRecordById(snapshot.sleepId)?.babyId != snapshot.babyId) {
                throw RecordWriteException(RecordWriteFailure.RECORD_DELETED)
            }
            sleepRecordDao.updateSleepRecord(snapshot)
        }
    }

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

    suspend fun getRecentCompletedSleeps(babyId: Int, limit: Int): List<SleepRecord> =
        sleepRecordDao.getRecentCompletedSleeps(babyId, limit)

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

    suspend fun insertEventRecord(record: EventRecord) {
        eventRecordDao.insertEventRecord(record)
    }

    suspend fun insertEventRecordOnce(record: EventRecord, requestId: String): Int {
        val snapshot = record.copy(eventId = 0)
        return insertRecordOnce(
            requestId, snapshot.babyId, RecordWritePayload.EVENT, RecordWritePayload.event(snapshot),
            insert = { eventRecordDao.insertEventRecord(snapshot) },
            recordOwner = { eventRecordDao.getEventRecordById(it)?.babyId }
        )
    }

    /** 记录和回执一起提交或回滚；只保存摘要，避免重复保存敏感正文。 */
    private suspend fun insertRecordOnce(
        requestId: String,
        babyId: Int,
        recordType: Int,
        payloadHash: String,
        insert: suspend () -> Long,
        recordOwner: suspend (Int) -> Int?
    ): Int {
        val normalizedRequestId = RecordWritePayload.requestId(requestId, babyId)
        return database.withTransaction {
            // 覆盖导入/删除宝宝会使旧草稿的本机 ID 失效，不按姓名或备份身份转移写入目标。
            if (babyInfoDao.getBabyInfo(babyId) == null) {
                throw RecordWriteException(RecordWriteFailure.BABY_NOT_FOUND)
            }
            val receipt = recordWriteReceiptDao.getReceipt(normalizedRequestId)
            if (receipt != null) {
                if (receipt.babyId != babyId || receipt.recordType != recordType) {
                    throw RecordWriteException(RecordWriteFailure.CONTENT_CONFLICT)
                }
                val owner = recordOwner(receipt.recordId)
                    ?: throw RecordWriteException(RecordWriteFailure.RECORD_DELETED)
                if (owner != babyId || receipt.payloadHash != payloadHash) {
                    throw RecordWriteException(RecordWriteFailure.CONTENT_CONFLICT)
                }
                // 原记录可能后来已被编辑；重放旧请求只能确认成功，不能覆盖更新后的内容。
                return@withTransaction receipt.recordId
            }
            val recordId = insert().toInt()
            recordWriteReceiptDao.insertReceipt(RecordWriteReceipt(
                requestId = normalizedRequestId,
                babyId = babyId,
                recordType = recordType,
                recordId = recordId,
                payloadHash = payloadHash
            ))
            recordId
        }
    }

    suspend fun updateEventRecord(record: EventRecord) {
        val snapshot = record.copy()
        database.withTransaction {
            if (eventRecordDao.getEventRecordById(snapshot.eventId)?.babyId != snapshot.babyId) {
                throw RecordWriteException(RecordWriteFailure.RECORD_DELETED)
            }
            eventRecordDao.updateEventRecord(snapshot)
        }
    }

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
