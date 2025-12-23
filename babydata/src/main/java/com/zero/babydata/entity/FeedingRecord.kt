package com.zero.babydata.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "Feeding_Records",
    foreignKeys = [ForeignKey(
        entity = BabyInfo::class,
        parentColumns = ["babyId"],
        childColumns = ["babyId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["babyId"])]
)
data class FeedingRecord(
    @PrimaryKey(autoGenerate = true)
    var feedingId: Int = 0,
    var babyId: Int = 0,
    var feedingType: Int = 0,
    var feedingStart: Long = 0L,
    var feedingEnd: Long = 0L,
    var feedingDuration: Long = 0L,
    var feedingDurationBreastLeft: Long = 0L,
    var feedingDurationBreastRight: Long = 0L,
    var note: String = "",
    var createdAt: Long = 0L
)
