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
    
    /** 
     * 喂养类型（使用 FeedingType 枚举的 type 值）
     * @see FeedingType
     */
    var feedingType: Int = FeedingType.BREAST.type,
    
    var feedingStart: Long = 0L,
    var feedingEnd: Long = 0L,
    var feedingDuration: Long = 0L,
    var feedingDurationBreastLeft: Long = 0L,
    var feedingDurationBreastRight: Long = 0L,
    var note: String = "",
    var createdAt: Long = 0L,
    
    // ==================== 扩展字段（用于预测优化） ====================
    /** 喂奶量（毫升/克），配方奶/辅食可记录，母乳可留空 */
    var feedingAmount: Int? = null,
    /** 宝宝状态：1=饿了哭闹, 2=主动/定时喂, 3=困了/安抚 */
    var babyMood: Int? = null,
    /** 喂奶场景：1=家中, 2=外出 */
    var feedingLocation: Int? = null,
    
    // ==================== 辅食扩展字段 ====================
    /**
     * 辅食类型（仅当 feedingType = SOLID_FOOD 时使用）
     * @see SolidFoodType
     */
    var solidFoodType: Int? = null,
    
    /** 具体食物名称（如"胡萝卜泥"、"苹果"） */
    var foodName: String? = null,
    
    /** 是否首次尝试此食物（用于过敏观察） */
    var isFirstTime: Boolean = false
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
    
    /**
     * 获取喂养类型枚举
     */
    fun getFeedingTypeEnum(): FeedingType = FeedingType.fromType(feedingType)
    
    /**
     * 是否是辅食
     */
    fun isSolidFood(): Boolean = feedingType == FeedingType.SOLID_FOOD.type
}
