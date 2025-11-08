package com.zero.babydata.room;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import com.zero.babydata.entity.EventRecord;
import com.zero.babydata.entity.SleepRecord;

import java.util.List;

@Dao
public interface EventRecordDao {

    @Insert
    void insertEventRecord(EventRecord record);

    @Delete
    void deleteEventRecord(EventRecord record);

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId")
    List<EventRecord> getAllEventRecord(int babyId);

    @Query("SELECT * FROM Event_Records WHERE babyId = :babyId AND type = :type")
    List<EventRecord> getAllEventRecord(int babyId,int type);


}
