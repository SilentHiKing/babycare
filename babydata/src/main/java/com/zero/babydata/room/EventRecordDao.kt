package com.zero.babydata.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zero.babydata.entity.EventRecord

@Dao
interface EventRecordDao {

    @Insert
    fun insertEventRecord(record: EventRecord)

    @Update
    fun updateEventRecord(record: EventRecord)

    @Delete
    fun deleteEventRecord(record: EventRecord)

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId")
    fun getAllEventRecord(babyId: Int): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId AND type = :type")
    fun getAllEventRecordByType(babyId: Int, type: Int): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId AND type = :type ORDER BY time DESC LIMIT :limit")
    fun getLatestEventRecordsByType(babyId: Int, type: Int, limit: Int): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId AND time BETWEEN :startOfDay AND :endOfDay ORDER BY time DESC")
    fun getEventRecordsForDay(babyId: Int, startOfDay: Long, endOfDay: Long): List<EventRecord>

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId AND time BETWEEN :startTime AND :endTime ORDER BY time ASC")
    fun getEventRecordsBetween(babyId: Int, startTime: Long, endTime: Long): List<EventRecord>

    @Query("SELECT DISTINCT date(time / 1000, 'unixepoch', 'localtime') as recordDate FROM Event_Records WHERE babyId = :babyId AND time BETWEEN :startTime AND :endTime")
    fun getDatesWithEvents(babyId: Int, startTime: Long, endTime: Long): List<String>

    @Query("DELETE FROM Event_Records WHERE eventId = :eventId")
    fun deleteEventRecordById(eventId: Int)

    @Query("SELECT * FROM Event_Records WHERE eventId = :eventId")
    fun getEventRecordById(eventId: Int): EventRecord?

    @Query("DELETE FROM Event_Records")
    fun deleteAllEventRecords()
}
