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
}
