package com.zero.babydata.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zero.babydata.entity.ChildDailyRecord

@Dao
interface ChildDailyRecordDao {

    @Insert
    fun insertChildDailyRecord(record: ChildDailyRecord)

    @Update
    fun updateChildDailyRecord(record: ChildDailyRecord)

    @Delete
    fun deleteChildDailyRecord(record: ChildDailyRecord)

    @Query("SELECT * FROM ChildDailyRecord WHERE babyId = :babyId AND recordDate = :recordDate LIMIT 1")
    fun getRecordByDate(babyId: Int, recordDate: Long): ChildDailyRecord?

    @Query("UPDATE ChildDailyRecord SET weight = :weight WHERE babyId = :babyId AND recordDate = :recordDate")
    fun updateWeight(babyId: Int, recordDate: Long, weight: Float)

    @Query("UPDATE ChildDailyRecord SET height = :height WHERE babyId = :babyId AND recordDate = :recordDate")
    fun updateHeight(babyId: Int, recordDate: Long, height: Float)

    @Query("UPDATE ChildDailyRecord SET headCircumference = :headCircumference WHERE babyId = :babyId AND recordDate = :recordDate")
    fun updateHeadCircumference(babyId: Int, recordDate: Long, headCircumference: Float)

    @Query("UPDATE ChildDailyRecord SET pic = :pic WHERE babyId = :babyId AND recordDate = :recordDate")
    fun updatePic(babyId: Int, recordDate: Long, pic: String?)

    @Query("UPDATE ChildDailyRecord SET extra = :extra WHERE babyId = :babyId AND recordDate = :recordDate")
    fun updateExtra(babyId: Int, recordDate: Long, extra: String?)

    @Query("SELECT * FROM ChildDailyRecord WHERE babyId = :babyId ORDER BY recordDate DESC")
    fun getAllRecordsByBabyId(babyId: Int): LiveData<List<ChildDailyRecord>>

    /**
     * 同步查询成长记录（备份使用）
     */
    @Query("SELECT * FROM ChildDailyRecord WHERE babyId = :babyId ORDER BY recordDate DESC")
    fun getAllRecordsByBabyIdSync(babyId: Int): List<ChildDailyRecord>

    @Query("DELETE FROM ChildDailyRecord")
    fun deleteAllChildDailyRecords()
}
