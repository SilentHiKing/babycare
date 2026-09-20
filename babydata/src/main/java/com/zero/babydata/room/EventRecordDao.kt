package com.zero.babydata.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zero.babydata.entity.EventRecord

@Dao
interface EventRecordDao {

    @Query("SELECT COUNT(*) FROM Event_Records")
    suspend fun countForBackup(): Long

    @Insert
    suspend fun insertEventRecord(record: EventRecord): Long

    @Update
    suspend fun updateEventRecord(record: EventRecord)

    @Delete
    suspend fun deleteEventRecord(record: EventRecord)

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId")
    suspend fun getAllEventRecord(babyId: Int): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId AND type = :type")
    suspend fun getAllEventRecordByType(babyId: Int, type: Int): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId AND type = :type ORDER BY time DESC LIMIT :limit")
    suspend fun getLatestEventRecordsByType(babyId: Int, type: Int, limit: Int): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId AND time BETWEEN :startOfDay AND :endOfDay ORDER BY time DESC")
    suspend fun getEventRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId AND time BETWEEN :startTime AND :endTime ORDER BY time ASC")
    suspend fun getEventRecordsBetween(babyId: Int, startTime: Long, endTime: Long): List<EventRecord>

    // 只投影时间，由 Repository 按本次请求时区归日，避免 SQLite 的全局 localtime 混入另一时区。
    @Query("SELECT DISTINCT time FROM Event_Records WHERE babyId = :babyId AND time BETWEEN :startTime AND :endTime")
    suspend fun getEventTimesBetween(babyId: Int, startTime: Long, endTime: Long): List<Long>

    @Query("DELETE FROM Event_Records WHERE eventId = :eventId")
    suspend fun deleteEventRecordById(eventId: Int): Int

    @Query("SELECT * FROM Event_Records WHERE eventId = :eventId")
    suspend fun getEventRecordById(eventId: Int): EventRecord?

    @Query("DELETE FROM Event_Records")
    suspend fun deleteAllEventRecords(): Int
}
