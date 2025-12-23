package com.zero.babydata.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "Event_Records",
    foreignKeys = [ForeignKey(
        entity = BabyInfo::class,
        parentColumns = ["babyId"],
        childColumns = ["babyId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["babyId"])]
)
data class EventRecord(
    @PrimaryKey(autoGenerate = true)
    var eventId: Int = 0,
    var type: Int = 0,
    var babyId: Int = 0,
    var time: Long = 0L,
    var note: String = "",
    var createdAt: Long = 0L
)
