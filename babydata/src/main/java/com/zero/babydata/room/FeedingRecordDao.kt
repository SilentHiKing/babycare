package com.zero.babydata.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zero.babydata.entity.FeedingRecord

@Dao
interface FeedingRecordDao {

    @Insert
    suspend fun insertFeedingRecord(feedingRecord: FeedingRecord): Long

    @Update
    suspend fun updateFeedingRecord(feedingRecord: FeedingRecord)

    @Delete
    suspend fun deleteFeedingRecord(feedingRecord: FeedingRecord)

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId")
    suspend fun getAllFeedingRecords(babyId: Int): List<FeedingRecord>

    @Query("DELETE FROM Feeding_Records WHERE feedingId = :feedingId")
    suspend fun deleteFeedingRecordById(feedingId: Int): Int

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId AND feedingStart BETWEEN :startOfDay AND :endOfDay ORDER BY feedingStart DESC")
    suspend fun getFeedingRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<FeedingRecord>

    /**
     * 获取与当天有重叠的喂养记录（用于跨天时长统计）
     */
    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId AND feedingStart <= :endOfDay AND feedingEnd > :startOfDay ORDER BY feedingStart DESC")
    suspend fun getFeedingRecordsIntersectingDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<FeedingRecord>

    /**
     * 获取与任意统计范围有重叠的喂养记录。
     */
    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId AND feedingStart <= :endTime AND feedingEnd > :startTime ORDER BY feedingStart ASC")
    suspend fun getFeedingRecordsIntersectingRange(babyId: Int, startTime: Long, endTime: Long): List<FeedingRecord>

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId AND feedingStart BETWEEN :startTime AND :endTime ORDER BY feedingStart ASC")
    suspend fun getFeedingRecordsBetween(babyId: Int, startTime: Long, endTime: Long): List<FeedingRecord>

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId ORDER BY feedingEnd DESC LIMIT 1")
    suspend fun getLastFeedingRecord(babyId: Int): FeedingRecord?

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId ORDER BY feedingStart DESC LIMIT :limit")
    suspend fun getRecentFeedings(babyId: Int, limit: Int): List<FeedingRecord>

    @Query("SELECT DISTINCT date(feedingStart / 1000, 'unixepoch', 'localtime') as recordDate FROM Feeding_Records WHERE babyId = :babyId AND feedingStart BETWEEN :startTime AND :endTime")
    suspend fun getDatesWithFeedings(babyId: Int, startTime: Long, endTime: Long): List<String>

    @Query("SELECT * FROM Feeding_Records WHERE feedingId = :feedingId")
    suspend fun getFeedingRecordById(feedingId: Int): FeedingRecord?

    @Query("DELETE FROM Feeding_Records")
    suspend fun deleteAllFeedingRecords(): Int
}
