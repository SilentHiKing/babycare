package com.zero.babydata.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zero.babydata.entity.BabyInfo
import kotlinx.coroutines.flow.Flow

@Dao
interface BabyInfoDao {

    @Query("SELECT COUNT(*) FROM Baby_Info")
    suspend fun countForBackup(): Long

    @Insert
    suspend fun insertBabyInfo(babyInfo: BabyInfo): Long

    @Update
    suspend fun updateBabyInfo(babyInfo: BabyInfo)

    @Delete
    suspend fun deleteBabyInfo(babyInfo: BabyInfo)

    @Query("SELECT * FROM Baby_Info WHERE babyId = :babyId LIMIT 1")
    suspend fun getBabyInfo(babyId: Int): BabyInfo?

    @Query("SELECT * FROM Baby_Info WHERE stableId = :stableId LIMIT 1")
    suspend fun findBabyByStableId(stableId: String): BabyInfo?

    // 仅供合法 UUID 的精确匹配失败后回退；最多两行即可识别歧义，禁止猜测合并历史双行。
    @Query("SELECT * FROM Baby_Info WHERE stableId = :stableId COLLATE NOCASE LIMIT 2")
    suspend fun findBabiesByStableIdIgnoringCase(stableId: String): List<BabyInfo>

    @Query("SELECT * FROM Baby_Info ORDER BY babyId DESC")
    suspend fun getAllBabyInfo(): List<BabyInfo>

    @Query("SELECT * FROM Baby_Info ORDER BY babyId DESC")
    fun observeAllBabyInfo(): Flow<List<BabyInfo>>

    @Query("UPDATE Baby_Info SET bloodType = :bloodType WHERE babyId = :babyId")
    suspend fun updateBloodType(babyId: Int, bloodType: String?): Int

    @Query("DELETE FROM Baby_Info")
    suspend fun deleteAllBabyInfo(): Int
}
