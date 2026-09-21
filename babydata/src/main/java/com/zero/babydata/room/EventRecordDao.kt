package com.zero.babydata.room

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zero.babydata.entity.EventRecord

@Dao
interface EventRecordDao {

    @Query("SELECT COUNT(*) FROM Event_Records WHERE timerStatus = 0")
    suspend fun countForBackup(): Long

    @Insert
    suspend fun insertEventRecord(record: EventRecord): Long

    @Update
    suspend fun updateEventRecord(record: EventRecord)

    @Delete
    suspend fun deleteEventRecord(record: EventRecord)

    @Query("SELECT * FROM Event_Records WHERE timerStatus = 0 AND babyId = :babyId")
    suspend fun getAllEventRecord(babyId: Int): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE timerStatus = 0 AND babyId = :babyId AND type = :type")
    suspend fun getAllEventRecordByType(babyId: Int, type: Int): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE timerStatus = 0 AND babyId = :babyId AND type = :type ORDER BY time DESC LIMIT :limit")
    suspend fun getLatestEventRecordsByType(babyId: Int, type: Int, limit: Int): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE timerStatus = 0 AND babyId = :babyId AND time BETWEEN :startOfDay AND :endOfDay ORDER BY time DESC")
    suspend fun getEventRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE timerStatus = 0 AND babyId = :babyId AND time BETWEEN :startTime AND :endTime ORDER BY time ASC")
    suspend fun getEventRecordsBetween(babyId: Int, startTime: Long, endTime: Long): List<EventRecord>

    // 只投影时间，由 Repository 按本次请求时区归日，避免 SQLite 的全局 localtime 混入另一时区。
    @Query("SELECT DISTINCT time FROM Event_Records WHERE timerStatus = 0 AND babyId = :babyId AND time BETWEEN :startTime AND :endTime")
    suspend fun getEventTimesBetween(babyId: Int, startTime: Long, endTime: Long): List<Long>

    @Query("SELECT time AS startTime, endTime, timerData FROM Event_Records WHERE timerStatus = 0 AND babyId = :babyId AND time <= :endTime AND (time >= :startTime OR endTime > :startTime)")
    suspend fun getEventTimeSpans(babyId: Int, startTime: Long, endTime: Long): List<RecordTimeSpan>

    @Query("SELECT * FROM Event_Records WHERE timerStatus = 0 AND babyId = :babyId AND time <= :endOfDay AND (time >= :startOfDay OR endTime > :startOfDay) ORDER BY time DESC, eventId DESC")
    suspend fun getEventRecordsCoveringDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<EventRecord>

    @Query("DELETE FROM Event_Records WHERE eventId = :eventId")
    suspend fun deleteEventRecordById(eventId: Int): Int

    @Query("SELECT * FROM Event_Records WHERE eventId = :eventId")
    suspend fun getEventRecordById(eventId: Int): EventRecord?

    @Query("DELETE FROM Event_Records")
    suspend fun deleteAllEventRecords(): Int
    /** 未完成记录单独观察，不能进入统计、预测或历史备份。 */
    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId AND timerStatus != 0 ORDER BY eventId")
    fun observeUnfinished(babyId: Int): Flow<List<EventRecord>>

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId AND timerStatus != 0 ORDER BY eventId")
    suspend fun getUnfinished(babyId: Int): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE timerSessionId = :sessionId")
    suspend fun getByTimerSession(sessionId: String): EventRecord?
}
