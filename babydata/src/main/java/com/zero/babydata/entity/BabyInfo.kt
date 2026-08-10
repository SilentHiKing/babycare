package com.zero.babydata.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "Baby_Info",
    indices = [Index(value = ["stableId"], unique = true)]
)
data class BabyInfo(
    @PrimaryKey(autoGenerate = true)
    var babyId: Int = 0,
    /** 跨备份和本地重建保持不变的业务身份，不使用可变姓名或生日进行匹配。 */
    var stableId: String = UUID.randomUUID().toString(),
    var name: String = "",
    var gender: String = "",
    var birthDate: Long = 0L,
    var birthWeight: Float = 0f,
    var birthHeight: Float = 0f,
    var bloodType: String = "",
    var extra: String = ""
)
