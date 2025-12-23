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
}
