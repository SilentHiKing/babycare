package com.zero.babydata.room

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zero.babydata.entity.SleepRecord

@Dao
interface SleepRecordDao {

    @Query("SELECT COUNT(*) FROM Sleep_Records WHERE timerStatus = 0")
    suspend fun countForBackup(): Long

    @Insert
    suspend fun insertSleepRecord(sleepRecord: SleepRecord): Long

    @Update
    suspend fun updateSleepRecord(sleepRecord: SleepRecord)

    @Delete
    suspend fun deleteSleepRecord(sleepRecord: SleepRecord)

    @Query("SELECT * FROM Sleep_Records WHERE timerStatus = 0 AND babyId = :babyId")
    suspend fun getAllSleepRecords(babyId: Int): List<SleepRecord>

    @Query("DELETE FROM Sleep_Records WHERE sleepId = :sleepId")
    suspend fun deleteSleepRecordById(sleepId: Int): Int

    @Query("SELECT * FROM Sleep_Records WHERE timerStatus = 0 AND babyId = :babyId AND sleepStart BETWEEN :startOfDay AND :endOfDay ORDER BY sleepStart DESC")
    suspend fun getSleepRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<SleepRecord>

    /** 原日记录保留纠错入口；跨日只纳入已结束且有效的交叠区间，午夜结束不算次日。 */
    @Query("SELECT * FROM Sleep_Records WHERE timerStatus = 0 AND babyId = :babyId AND sleepStart <= :endOfDay AND (sleepStart >= :startOfDay OR (sleepStart > 0 AND sleepEnd > :startOfDay)) ORDER BY sleepStart DESC, sleepId DESC")
    suspend fun getSleepRecordsCoveringDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<SleepRecord>

    /** 日历投影只取时间端点，不加载整月记录正文。 */
    @Query("SELECT sleepStart AS startTime, sleepEnd AS endTime, timerData FROM Sleep_Records WHERE timerStatus = 0 AND babyId = :babyId AND sleepStart <= :endTime AND (sleepStart >= :startTime OR (sleepStart > 0 AND sleepEnd > :startTime))")
    suspend fun getSleepTimeSpans(babyId: Int, startTime: Long, endTime: Long): List<RecordTimeSpan>

    /**
     * 获取与当天有重叠的睡眠记录（用于跨天时长统计）
     */
    @Query("SELECT * FROM Sleep_Records WHERE timerStatus = 0 AND babyId = :babyId AND sleepStart <= :endOfDay AND sleepEnd > :startOfDay ORDER BY sleepStart DESC")
    suspend fun getSleepRecordsIntersectingDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<SleepRecord>

    /**
     * 获取与任意统计范围有重叠的睡眠记录。
     */
    @Query("SELECT * FROM Sleep_Records WHERE timerStatus = 0 AND babyId = :babyId AND sleepStart <= :endTime AND sleepEnd > :startTime ORDER BY sleepStart ASC")
    suspend fun getSleepRecordsIntersectingRange(babyId: Int, startTime: Long, endTime: Long): List<SleepRecord>

    @Query("SELECT * FROM Sleep_Records WHERE timerStatus = 0 AND babyId = :babyId AND sleepStart BETWEEN :startTime AND :endTime ORDER BY sleepStart ASC")
    suspend fun getSleepRecordsBetween(babyId: Int, startTime: Long, endTime: Long): List<SleepRecord>

    @Query("SELECT * FROM Sleep_Records WHERE timerStatus = 0 AND babyId = :babyId AND sleepStart > 0 AND sleepEnd > sleepStart ORDER BY sleepEnd DESC, sleepStart DESC, sleepId DESC LIMIT 1")
    suspend fun getLastSleepRecord(babyId: Int): SleepRecord?

    @Query("SELECT * FROM Sleep_Records WHERE timerStatus = 0 AND babyId = :babyId ORDER BY sleepStart DESC LIMIT :limit")
    suspend fun getRecentSleeps(babyId: Int, limit: Int): List<SleepRecord>

    /** 按完成时间选取样本，较早开始但较晚结束的长睡眠仍是最新依据。 */
    @Query("SELECT * FROM Sleep_Records WHERE timerStatus = 0 AND babyId = :babyId AND sleepStart > 0 AND sleepEnd > sleepStart ORDER BY sleepEnd DESC, sleepStart DESC, sleepId DESC LIMIT :limit")
    suspend fun getRecentCompletedSleeps(babyId: Int, limit: Int): List<SleepRecord>

    @Query("SELECT DISTINCT date(sleepStart / 1000, 'unixepoch', 'localtime') as recordDate FROM Sleep_Records WHERE timerStatus = 0 AND babyId = :babyId AND sleepStart BETWEEN :startTime AND :endTime")
    suspend fun getDatesWithSleeps(babyId: Int, startTime: Long, endTime: Long): List<String>

    @Query("SELECT * FROM Sleep_Records WHERE sleepId = :sleepId")
    suspend fun getSleepRecordById(sleepId: Int): SleepRecord?

    @Query("DELETE FROM Sleep_Records")
    suspend fun deleteAllSleepRecords(): Int
    /** 未完成记录单独观察，不能进入统计、预测或历史备份。 */
    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId AND timerStatus != 0 ORDER BY sleepId")
    fun observeUnfinished(babyId: Int): Flow<List<SleepRecord>>

    @Query("SELECT * FROM Sleep_Records WHERE babyId = :babyId AND timerStatus != 0 ORDER BY sleepId")
    suspend fun getUnfinished(babyId: Int): List<SleepRecord>

    @Query("SELECT * FROM Sleep_Records WHERE timerSessionId = :sessionId")
    suspend fun getByTimerSession(sessionId: String): SleepRecord?
}
