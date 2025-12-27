package com.zero.babycare.statistics.model

import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord

/**
 * 时间轴记录条目 - 密封类
 * 用于 RecyclerView 多类型展示
 */
sealed class TimelineItem(
    open val id: Long,
    open val time: Long,
    open val babyId: Int
) : Comparable<TimelineItem> {

    /**
     * 喂养记录
     */
    data class Feeding(
        val record: FeedingRecord
    ) : TimelineItem(
        id = record.feedingId.toLong(),
        time = record.feedingStart,
        babyId = record.babyId
    )

    /**
     * 睡眠记录（开始+结束合并显示）
     */
    data class Sleep(
        val record: SleepRecord
    ) : TimelineItem(
        id = record.sleepId.toLong(),
        time = record.sleepStart,
        babyId = record.babyId
    )

    /**
     * 事件记录
     */
    data class Event(
        val record: EventRecord
    ) : TimelineItem(
        id = record.eventId.toLong(),
        time = record.time,
        babyId = record.babyId
    )

    /**
     * 获取 ViewType
     */
    fun getViewType(): Int = when (this) {
        is Feeding -> VIEW_TYPE_FEEDING
        is Sleep -> VIEW_TYPE_SLEEP
        is Event -> VIEW_TYPE_EVENT
    }

    /**
     * 按时间排序（倒序，最新的在前面）
     */
    override fun compareTo(other: TimelineItem): Int {
        return other.time.compareTo(this.time)
    }

    companion object {
        const val VIEW_TYPE_FEEDING = 1
        const val VIEW_TYPE_SLEEP = 2
        const val VIEW_TYPE_EVENT = 3
    }
}

