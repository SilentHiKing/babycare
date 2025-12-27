package com.zero.babydata.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zero.babydata.entity.SleepRecord

@Dao
interface SleepRecordDao {

    @Insert
    fun insertSleepRecord(sleepRecord: SleepRecord)

    @Update
    fun updateSleepRecord(sleepRecord: SleepRecord)

    @Delete
    fun deleteSleepRecord(sleepRecord: SleepRecord)

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId")
    fun getAllSleepRecords(babyId: Int): List<SleepRecord>

    @Query("DELETE FROM Sleep_Records WHERE sleepId = :sleepId")
    fun deleteSleepRecordById(sleepId: Int)

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId AND sleepStart BETWEEN :startOfDay AND :endOfDay ORDER BY sleepStart DESC")
    fun getSleepRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<SleepRecord>

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId ORDER BY sleepEnd DESC LIMIT 1")
    fun getLastSleepRecord(babyId: Int): SleepRecord?

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId ORDER BY sleepStart DESC LIMIT :limit")
    fun getRecentSleeps(babyId: Int, limit: Int): List<SleepRecord>

    @Query("SELECT DISTINCT date(sleepStart / 1000, 'unixepoch', 'localtime') as recordDate FROM Sleep_Records WHERE babyId = :babyId AND sleepStart BETWEEN :startTime AND :endTime")
    fun getDatesWithSleeps(babyId: Int, startTime: Long, endTime: Long): List<String>

    @Query("SELECT * FROM Sleep_Records WHERE sleepId = :sleepId")
    fun getSleepRecordById(sleepId: Int): SleepRecord?
}
