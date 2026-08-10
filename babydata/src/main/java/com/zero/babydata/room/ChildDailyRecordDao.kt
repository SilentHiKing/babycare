package com.zero.babydata.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.zero.babydata.entity.ChildDailyRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface ChildDailyRecordDao {

    @Insert
    suspend fun insertChildDailyRecord(record: ChildDailyRecord)

    @Update
    suspend fun updateChildDailyRecord(record: ChildDailyRecord)

    @Delete
    suspend fun deleteChildDailyRecord(record: ChildDailyRecord)

    @Query("SELECT * FROM ChildDailyRecord WHERE babyId = :babyId AND recordDate = :recordDate LIMIT 1")
    suspend fun getRecordByDate(babyId: Int, recordDate: Long): ChildDailyRecord?

    @Query("SELECT * FROM ChildDailyRecord WHERE babyId = :babyId ORDER BY recordDate DESC")
    fun observeAllRecordsByBabyId(babyId: Int): Flow<List<ChildDailyRecord>>

    /**
     * 一次性查询成长记录（备份使用）。
     */
    @Query("SELECT * FROM ChildDailyRecord WHERE babyId = :babyId ORDER BY recordDate DESC")
    suspend fun getAllRecordsByBabyId(babyId: Int): List<ChildDailyRecord>

    @Query("DELETE FROM ChildDailyRecord")
    suspend fun deleteAllChildDailyRecords(): Int

    /**
     * 以宝宝与日期为业务唯一键合并成长数据，确保并发写入不会产生同日重复记录。
     * null 表示保留已有值；首次创建时使用实体默认值。
     */
    @Transaction
    suspend fun updateOrInsert(
        babyId: Int,
        recordDate: Long,
        weight: Float? = null,
        height: Float? = null,
        headCircumference: Float? = null,
        pic: String? = null,
        extra: String? = null
    ) {
        val existing = getRecordByDate(babyId, recordDate)
        if (existing == null) {
            insertChildDailyRecord(
                ChildDailyRecord(
                    babyId = babyId,
                    recordDate = recordDate,
                    weight = weight ?: 0f,
                    height = height ?: 0f,
                    headCircumference = headCircumference ?: 0f,
                    pic = pic.orEmpty(),
                    extra = extra.orEmpty()
                )
            )
            return
        }

        updateChildDailyRecord(
            existing.copy(
                weight = weight ?: existing.weight,
                height = height ?: existing.height,
                headCircumference = headCircumference ?: existing.headCircumference,
                pic = pic ?: existing.pic,
                extra = extra ?: existing.extra
            )
        )
    }
}
