package com.zero.babydata.room;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.zero.babydata.entity.ChildDailyRecord;

import java.util.List;

@Dao
public interface ChildDailyRecordDao {

    @Insert
    void insertChildDailyRecord(ChildDailyRecord record);


    @Update
    void updateChildDailyRecord(ChildDailyRecord record);

    @Query("SELECT * FROM ChildDailyRecord WHERE babyId = :babyId AND recordDate = :recordDate LIMIT 1")
    ChildDailyRecord getRecordByDate(int babyId, Long recordDate);  // 查询某天记录

    @Query("UPDATE ChildDailyRecord SET weight = :weight WHERE babyId = :babyId AND recordDate = :recordDate")
    void updateWeight(int babyId, Long recordDate, float weight);  // 仅更新体重

    @Query("UPDATE ChildDailyRecord SET height = :height WHERE babyId = :babyId AND recordDate = :recordDate")
    void updateHeight(int babyId, Long recordDate, float height);  // 仅更新身高

    @Query("UPDATE ChildDailyRecord SET pic = :pic WHERE babyId = :babyId AND recordDate = :recordDate")
    void updatePic(int babyId, Long recordDate, String pic);  // 仅更新 pic

    @Query("UPDATE ChildDailyRecord SET extra = :extra WHERE babyId = :babyId AND recordDate = :recordDate")
    void updateExtra(int babyId, Long recordDate, String extra);  // 仅更新 extra

    @Query("SELECT * FROM ChildDailyRecord WHERE babyId = :babyId ORDER BY recordDate DESC")
    LiveData<List<ChildDailyRecord>> getAllRecordsByBabyId(int babyId);
}
