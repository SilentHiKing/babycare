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
    var createdAt: Long = 0L,
    
    // ==================== 扩展字段（用于预测优化） ====================
    /** 喂奶量（毫升），配方奶可记录，母乳可留空 */
    var feedingAmount: Int? = null,
    /** 宝宝状态：1=饿了哭闹, 2=主动/定时喂, 3=困了/安抚 */
    var babyMood: Int? = null,
    /** 喂奶场景：1=家中, 2=外出 */
    var feedingLocation: Int? = null
) {
    companion object {
        // 宝宝状态常量
        const val MOOD_HUNGRY_CRYING = 1
        const val MOOD_SCHEDULED = 2
        const val MOOD_SLEEPY_COMFORT = 3
        
        // 场景常量
        const val LOCATION_HOME = 1
        const val LOCATION_OUTSIDE = 2
    }
}
