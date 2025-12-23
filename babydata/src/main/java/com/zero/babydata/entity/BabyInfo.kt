package com.zero.babydata.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Baby_Info")
data class BabyInfo(
    @PrimaryKey(autoGenerate = true)
    var babyId: Int = 0,
    var name: String = "",
    var gender: String = "",
    var birthDate: Long = 0L,
    var birthWeight: Float = 0f,
    var birthHeight: Float = 0f,
    var bloodType: String = "",
    var extra: String = ""
)
