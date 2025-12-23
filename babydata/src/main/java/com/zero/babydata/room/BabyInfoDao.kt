package com.zero.babydata.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zero.babydata.entity.BabyInfo

@Dao
interface BabyInfoDao {

    @Insert
    fun insertBabyInfo(babyInfo: BabyInfo)

    @Update
    fun updateBabyInfo(babyInfo: BabyInfo)

    @Delete
    fun deleteBabyInfo(babyInfo: BabyInfo)

    @Query("SELECT * FROM Baby_Info WHERE babyId = :babyId LIMIT 1")
    fun getBabyInfo(babyId: Int): BabyInfo?

    @Query("SELECT * FROM Baby_Info ORDER BY babyId DESC")
    fun getAllBabyInfo(): List<BabyInfo>

    @Query("UPDATE Baby_Info SET bloodType = :bloodType WHERE babyId = :babyId")
    fun updateBloodType(babyId: Int, bloodType: String?)
}
