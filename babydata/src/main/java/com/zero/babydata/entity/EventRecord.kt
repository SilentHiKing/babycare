package com.zero.babydata.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通用事件记录
 * 
 * 设计理念：类型码 + JSON扩展字段
 * - type: 事件大类（100位）+ 子类（个位）
 * - extraData: JSON格式存储不同类型的扩展数据
 * 
 * 示例：
 * - 换尿布（湿）: type=101, extraData={"color":"yellow"}
 * - 体重记录: type=301, extraData={"value":5.5,"unit":"kg"}
 * - 辅食: type=201, extraData={"name":"米糊","amount":30,"unit":"g"}
 */
@Entity(
    tableName = "Event_Records",
    foreignKeys = [ForeignKey(
        entity = BabyInfo::class,
        parentColumns = ["babyId"],
        childColumns = ["babyId"],
        onDelete = ForeignKey.CASCADE
    )],
    // 日历/时间轴和指定类型的最新测量分别使用复合索引，不改写记录内容或业务 SQL。
    indices = [
        Index(value = ["babyId"]), Index(value = ["type"]),
        Index(value = ["babyId", "time"]), Index(value = ["babyId", "type", "time"]),
        Index(value = ["timerSessionId"], unique = true),
        Index(value = ["babyId", "timerSlot"], unique = true)
    ]
)
data class EventRecord(
    @PrimaryKey(autoGenerate = true)
    var eventId: Int = 0,
    
    /** 宝宝ID */
    var babyId: Int = 0,
    
    /** 
     * 事件类型（使用 EventType 中的常量）
     * 编码规则：大类(百位) + 子类(十位和个位)
     */
    var type: Int = 0,
    
    /** 事件发生时间 */
    var time: Long = 0L,
    
    /** 
     * 结束时间（可选，用于有时长的事件如户外活动）
     * 为0表示瞬时事件
     */
    var endTime: Long = 0L,
    
    /**
     * 扩展数据（JSON格式）
     * 不同事件类型存储不同的数据结构
     * @see EventExtraData
     */
    var extraData: String = "",
    
    /** 备注 */
    var note: String = "",
    
    /** 创建时间 */
    var createdAt: Long = 0L,
    // 活动有效时长允许未知；各具体活动用 type 作为未完成槽位，避免互相覆盖。
    var activeDurationMs: Long? = null,
    var timerStatus: Int = 0,
    var timerSessionId: String? = null,
    var timerRevision: Long = 0L,
    var timerData: String? = null,
    var timerSlot: Int? = null
)
