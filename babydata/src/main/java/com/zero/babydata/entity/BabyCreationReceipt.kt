package com.zero.babydata.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 创建身份不随宝宝删除而删除，旧草稿不能在删除或覆盖导入后悄悄重建宝宝。 */
@Entity(tableName = "Baby_Creation_Receipts")
data class BabyCreationReceipt(
    @PrimaryKey val requestId: String,
    val babyId: Int,
    val payloadHash: String
)
