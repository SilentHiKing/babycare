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
    fun insertFeedingRecord(feedingRecord: FeedingRecord)

    @Update
    fun updateFeedingRecord(feedingRecord: FeedingRecord)

    @Delete
    fun deleteFeedingRecord(feedingRecord: FeedingRecord)

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId")
    fun getAllFeedingRecords(babyId: Int): List<FeedingRecord>

    @Query("DELETE FROM Feeding_Records WHERE feedingId = :feedingId")
    fun deleteFeedingRecordById(feedingId: Int)

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId AND feedingStart BETWEEN :startOfDay AND :endOfDay ORDER BY feedingStart DESC")
    fun getFeedingRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<FeedingRecord>

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId AND feedingStart BETWEEN :startTime AND :endTime ORDER BY feedingStart ASC")
    fun getFeedingRecordsBetween(babyId: Int, startTime: Long, endTime: Long): List<FeedingRecord>

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId ORDER BY feedingEnd DESC LIMIT 1")
    fun getLastFeedingRecord(babyId: Int): FeedingRecord?

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId ORDER BY feedingStart DESC LIMIT :limit")
    fun getRecentFeedings(babyId: Int, limit: Int): List<FeedingRecord>

    @Query("SELECT DISTINCT date(feedingStart / 1000, 'unixepoch', 'localtime') as recordDate FROM Feeding_Records WHERE babyId = :babyId AND feedingStart BETWEEN :startTime AND :endTime")
    fun getDatesWithFeedings(babyId: Int, startTime: Long, endTime: Long): List<String>

    @Query("SELECT * FROM Feeding_Records WHERE feedingId = :feedingId")
    fun getFeedingRecordById(feedingId: Int): FeedingRecord?

    @Query("DELETE FROM Feeding_Records")
    fun deleteAllFeedingRecords()
}
