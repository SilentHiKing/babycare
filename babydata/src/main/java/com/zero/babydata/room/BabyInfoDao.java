package com.zero.babydata.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Delete;
import androidx.room.Query;

import com.zero.babydata.entity.BabyInfo;

@Dao
public interface BabyInfoDao {

    @Insert
    void insertBabyInfo(BabyInfo babyInfo);

    @Query("SELECT * FROM Baby_Info WHERE babyId = :babyId LIMIT 1")
    BabyInfo getBabyInfo(int babyId);

    @Delete
    void deleteBabyInfo(BabyInfo babyInfo);

    @Query("UPDATE Baby_Info SET bloodType = :bloodType WHERE babyId = :babyId")
    void updateBloodType(int babyId, String bloodType);  // 更新血型
}
