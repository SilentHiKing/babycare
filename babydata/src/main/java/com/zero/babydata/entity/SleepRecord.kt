package com.zero.babydata.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "Sleep_Records",
    foreignKeys = [ForeignKey(
        entity = BabyInfo::class,
        parentColumns = ["babyId"],
        childColumns = ["babyId"],
        onDelete = ForeignKey.CASCADE
    )],
    // 开始索引同时携带结束时间，减少跨日查询历史记录的回表；仍保持时间顺序。
    indices = [
        Index(value = ["babyId"]),
        Index(value = ["babyId", "sleepStart", "sleepEnd"]),
        Index(value = ["babyId", "sleepEnd"])
    ]
)
data class SleepRecord(
    @PrimaryKey(autoGenerate = true)
    var sleepId: Int = 0,
    var babyId: Int = 0,
    var sleepStart: Long = 0L,
    var sleepEnd: Long = 0L,
    var sleepDuration: Long = 0L,
    var note: String = "",
    var createdAt: Long = 0L
)
