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
    indices = [Index(value = ["babyId"])]
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
