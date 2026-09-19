package com.zero.babydata.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zero.babydata.entity.BabyCreationReceipt

@Dao
interface BabyCreationReceiptDao {
    @Query("SELECT * FROM Baby_Creation_Receipts WHERE requestId = :requestId LIMIT 1")
    suspend fun getReceipt(requestId: String): BabyCreationReceipt?

    @Insert
    suspend fun insertReceipt(receipt: BabyCreationReceipt)
}
