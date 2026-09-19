package com.zero.babydata.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 本机保存回执；与宝宝一起清理，但删除记录时保留，阻止旧草稿重试后复活已删记录。 */
@Entity(
    tableName = "Record_Write_Receipts",
    foreignKeys = [ForeignKey(
        entity = BabyInfo::class,
        parentColumns = ["babyId"],
        childColumns = ["babyId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["babyId"])]
)
data class RecordWriteReceipt(
    @PrimaryKey val requestId: String,
    val babyId: Int,
    val recordType: Int,
    val recordId: Int,
    val payloadHash: String
)
