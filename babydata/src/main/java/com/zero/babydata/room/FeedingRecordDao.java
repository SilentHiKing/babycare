package com.zero.babydata.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Delete;
import androidx.room.Query;

import com.zero.babydata.entity.FeedingRecord;

import java.util.List;

@Dao
public interface FeedingRecordDao {

    @Insert
    void insertFeedingRecord(FeedingRecord feedingRecord);

    @Delete
    void deleteFeedingRecord(FeedingRecord feedingRecord);

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId")
    List<FeedingRecord> getAllFeedingRecords(int babyId);

    @Query("DELETE FROM Feeding_Records WHERE feedingId = :feedingId")
    void deleteFeedingRecordById(int feedingId);

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId AND feedingStart BETWEEN :startOfDay AND :endOfDay ORDER BY feedingStart DESC")
    List<FeedingRecord> getFeedingRecordsForDay(int babyId, long startOfDay, long endOfDay);

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId ORDER BY feedingEnd DESC LIMIT 1")
    FeedingRecord getLastFeedingRecord(int babyId);

    @Query("SELECT * FROM Feeding_Records WHERE babyId = :babyId ORDER BY feedingStart DESC LIMIT :limit")
    List<FeedingRecord> getRecentFeedings(int babyId, int limit);
}
