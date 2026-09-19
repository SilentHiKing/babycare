package com.zero.babydata.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zero.babydata.entity.RecordWriteReceipt

@Dao
interface RecordWriteReceiptDao {
    @Query("SELECT * FROM Record_Write_Receipts WHERE requestId = :requestId LIMIT 1")
    suspend fun getReceipt(requestId: String): RecordWriteReceipt?

    @Insert
    suspend fun insertReceipt(receipt: RecordWriteReceipt)
}
