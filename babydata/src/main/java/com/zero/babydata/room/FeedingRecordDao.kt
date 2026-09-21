package com.zero.babydata.room

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zero.babydata.entity.FeedingRecord

@Dao
interface FeedingRecordDao {

    @Query("SELECT COUNT(*) FROM Feeding_Records WHERE timerStatus = 0")
    suspend fun countForBackup(): Long

    @Insert
    suspend fun insertFeedingRecord(feedingRecord: FeedingRecord): Long

    @Update
    suspend fun updateFeedingRecord(feedingRecord: FeedingRecord)

    @Delete
    suspend fun deleteFeedingRecord(feedingRecord: FeedingRecord)

    @Query("SELECT * FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId")
    suspend fun getAllFeedingRecords(babyId: Int): List<FeedingRecord>

    @Query("DELETE FROM Feeding_Records WHERE feedingId = :feedingId")
    suspend fun deleteFeedingRecordById(feedingId: Int): Int

    @Query("SELECT * FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId AND feedingStart BETWEEN :startOfDay AND :endOfDay ORDER BY feedingStart DESC")
    suspend fun getFeedingRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<FeedingRecord>

    /** 原日记录保留纠错入口；跨日只纳入已结束且有效的交叠区间，午夜结束不算次日。 */
    @Query("SELECT * FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId AND feedingStart <= :endOfDay AND (feedingStart >= :startOfDay OR (feedingStart > 0 AND feedingEnd > :startOfDay)) ORDER BY feedingStart DESC, feedingId DESC")
    suspend fun getFeedingRecordsCoveringDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<FeedingRecord>

    /** 与时间轴相同的覆盖范围，区间投影可直接使用现有开始/结束复合索引。 */
    @Query("SELECT feedingStart AS startTime, feedingEnd AS endTime, timerData FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId AND feedingStart <= :endTime AND (feedingStart >= :startTime OR (feedingStart > 0 AND feedingEnd > :startTime))")
    suspend fun getFeedingTimeSpans(babyId: Int, startTime: Long, endTime: Long): List<RecordTimeSpan>

    /**
     * 获取与当天有重叠的喂养记录（用于跨天时长统计）
     */
    @Query("SELECT * FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId AND feedingStart <= :endOfDay AND feedingEnd > :startOfDay ORDER BY feedingStart DESC")
    suspend fun getFeedingRecordsIntersectingDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<FeedingRecord>

    /**
     * 获取与任意统计范围有重叠的喂养记录。
     */
    @Query("SELECT * FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId AND feedingStart <= :endTime AND feedingEnd > :startTime ORDER BY feedingStart ASC")
    suspend fun getFeedingRecordsIntersectingRange(babyId: Int, startTime: Long, endTime: Long): List<FeedingRecord>

    @Query("SELECT * FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId AND feedingStart BETWEEN :startTime AND :endTime ORDER BY feedingStart ASC")
    suspend fun getFeedingRecordsBetween(babyId: Int, startTime: Long, endTime: Long): List<FeedingRecord>

    @Query("SELECT * FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId AND feedingStart > 0 AND feedingEnd > feedingStart ORDER BY feedingEnd DESC, feedingStart DESC, feedingId DESC LIMIT 1")
    suspend fun getLastFeedingRecord(babyId: Int): FeedingRecord?

    @Query("SELECT * FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId ORDER BY feedingStart DESC LIMIT :limit")
    suspend fun getRecentFeedings(babyId: Int, limit: Int): List<FeedingRecord>

    /** 先筛有效奶类再限量，辅食及重叠短记录不能挤掉最近完成的预测依据。 */
    @Query("SELECT * FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId AND feedingStart > 0 AND feedingEnd > feedingStart AND feedingType IN (:milkTypes) ORDER BY feedingEnd DESC, feedingStart DESC, feedingId DESC LIMIT :limit")
    suspend fun getRecentCompletedMilkFeedings(babyId: Int, limit: Int, milkTypes: List<Int>): List<FeedingRecord>

    /** 未计时样本从结束时间为零的独立索引范围获取，不能用 CASE 排序拖慢全部历史。 */
    @Query("SELECT * FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId AND feedingEnd = 0 AND feedingStart > 0 AND feedingType IN (:types) AND timerData LIKE '%\"basis\":\"UNMEASURED\"%' ORDER BY feedingStart DESC, feedingId DESC LIMIT :limit")
    suspend fun getRecentUnmeasuredFeedings(babyId: Int, limit: Int, types: List<Int>): List<FeedingRecord>

    @Query("SELECT DISTINCT date(feedingStart / 1000, 'unixepoch', 'localtime') as recordDate FROM Feeding_Records WHERE timerStatus = 0 AND babyId = :babyId AND feedingStart BETWEEN :startTime AND :endTime")
    suspend fun getDatesWithFeedings(babyId: Int, startTime: Long, endTime: Long): List<String>

    @Query("SELECT * FROM Feeding_Records WHERE feedingId = :feedingId")
    suspend fun getFeedingRecordById(feedingId: Int): FeedingRecord?

    @Query("DELETE FROM Feeding_Records")
    suspend fun deleteAllFeedingRecords(): Int
    /** 未完成记录单独观察，不能进入统计、预测或历史备份。 */
    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId AND timerStatus != 0 ORDER BY feedingId")
    fun observeUnfinished(babyId: Int): Flow<List<FeedingRecord>>

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId AND timerStatus != 0 ORDER BY feedingId")
    suspend fun getUnfinished(babyId: Int): List<FeedingRecord>

    @Query("SELECT * FROM Feeding_Records WHERE timerSessionId = :sessionId")
    suspend fun getByTimerSession(sessionId: String): FeedingRecord?
}
