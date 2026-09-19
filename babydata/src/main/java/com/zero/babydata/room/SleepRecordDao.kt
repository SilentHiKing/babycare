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
    suspend fun insertSleepRecord(sleepRecord: SleepRecord): Long

    @Update
    suspend fun updateSleepRecord(sleepRecord: SleepRecord)

    @Delete
    suspend fun deleteSleepRecord(sleepRecord: SleepRecord)

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId")
    suspend fun getAllSleepRecords(babyId: Int): List<SleepRecord>

    @Query("DELETE FROM Sleep_Records WHERE sleepId = :sleepId")
    suspend fun deleteSleepRecordById(sleepId: Int): Int

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId AND sleepStart BETWEEN :startOfDay AND :endOfDay ORDER BY sleepStart DESC")
    suspend fun getSleepRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<SleepRecord>

    /**
     * 获取与当天有重叠的睡眠记录（用于跨天时长统计）
     */
    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId AND sleepStart <= :endOfDay AND sleepEnd > :startOfDay ORDER BY sleepStart DESC")
    suspend fun getSleepRecordsIntersectingDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<SleepRecord>

    /**
     * 获取与任意统计范围有重叠的睡眠记录。
     */
    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId AND sleepStart <= :endTime AND sleepEnd > :startTime ORDER BY sleepStart ASC")
    suspend fun getSleepRecordsIntersectingRange(babyId: Int, startTime: Long, endTime: Long): List<SleepRecord>

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId AND sleepStart BETWEEN :startTime AND :endTime ORDER BY sleepStart ASC")
    suspend fun getSleepRecordsBetween(babyId: Int, startTime: Long, endTime: Long): List<SleepRecord>

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId ORDER BY sleepEnd DESC LIMIT 1")
    suspend fun getLastSleepRecord(babyId: Int): SleepRecord?

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId ORDER BY sleepStart DESC LIMIT :limit")
    suspend fun getRecentSleeps(babyId: Int, limit: Int): List<SleepRecord>

    @Query("SELECT DISTINCT date(sleepStart / 1000, 'unixepoch', 'localtime') as recordDate FROM Sleep_Records WHERE babyId = :babyId AND sleepStart BETWEEN :startTime AND :endTime")
    suspend fun getDatesWithSleeps(babyId: Int, startTime: Long, endTime: Long): List<String>

    @Query("SELECT * FROM Sleep_Records WHERE sleepId = :sleepId")
    suspend fun getSleepRecordById(sleepId: Int): SleepRecord?

    @Query("DELETE FROM Sleep_Records")
    suspend fun deleteAllSleepRecords(): Int
}
