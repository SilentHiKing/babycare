package com.zero.babydata.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Delete;
import androidx.room.Query;

import com.zero.babydata.entity.SleepRecord;

import java.util.List;

@Dao
public interface SleepRecordDao {

    @Insert
    void insertSleepRecord(SleepRecord sleepRecord);

    @Delete
    void deleteSleepRecord(SleepRecord sleepRecord);

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId")
    List<SleepRecord> getAllSleepRecords(int babyId);

    @Query("DELETE FROM Sleep_Records WHERE sleepId = :sleepId")
    void deleteSleepRecordById(int sleepId);

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId AND sleepStart BETWEEN :startOfDay AND :endOfDay ORDER BY sleepStart DESC")
    List<SleepRecord> getSleepRecordsForDay(int babyId, long startOfDay, long endOfDay);

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId ORDER BY sleepEnd DESC LIMIT 1")
    SleepRecord getLastSleepRecord(int babyId);

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId ORDER BY sleepStart DESC LIMIT :limit")
    List<SleepRecord> getRecentSleeps(int babyId, int limit);
}
