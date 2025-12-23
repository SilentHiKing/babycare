package com.zero.babydata.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ChildDailyRecord",
    foreignKeys = [ForeignKey(
        entity = BabyInfo::class,
        parentColumns = ["babyId"],
        childColumns = ["babyId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["babyId"])]
)
data class ChildDailyRecord(
    @PrimaryKey(autoGenerate = true)
    var recordId: Int = 0,
    var babyId: Int = 0,
    var recordDate: Long = 0L,
    var weight: Float = 0f,
    var height: Float = 0f,
    var headCircumference: Float = 0f,
    var pic: String = "",
    var extra: String = ""
)
