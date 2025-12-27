package com.zero.babycare.statistics.model

import java.time.LocalDate

/**
 * 当日统计摘要
 */
data class DaySummary(
    val date: LocalDate,
    /** 喂养次数 */
    val feedingCount: Int = 0,
    /** 喂养总时长（分钟） */
    val feedingTotalMinutes: Int = 0,
    /** 喂养总量（毫升，仅配方奶/辅食） */
    val feedingTotalMl: Int = 0,
    /** 睡眠次数 */
    val sleepCount: Int = 0,
    /** 睡眠总时长（分钟） */
    val sleepTotalMinutes: Int = 0,
    /** 尿布-湿次数 */
    val diaperWetCount: Int = 0,
    /** 尿布-脏次数 */
    val diaperDirtyCount: Int = 0,
    /** 尿布-混合次数 */
    val diaperMixedCount: Int = 0,
    /** 尿布-干净次数 */
    val diaperDryCount: Int = 0,
    /** 其他事件次数 */
    val otherEventCount: Int = 0
) {
    /**
     * 尿布总次数
     */
    val totalDiaperCount: Int
        get() = diaperWetCount + diaperDirtyCount + diaperMixedCount + diaperDryCount

    /**
     * 格式化喂养时长
     */
    fun formatFeedingDuration(): String {
        val hours = feedingTotalMinutes / 60
        val minutes = feedingTotalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}小时${minutes}分"
            hours > 0 -> "${hours}小时"
            minutes > 0 -> "${minutes}分钟"
            else -> "0分钟"
        }
    }

    /**
     * 格式化睡眠时长
     */
    fun formatSleepDuration(): String {
        val hours = sleepTotalMinutes / 60
        val minutes = sleepTotalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "0m"
        }
    }

    /**
     * 格式化尿布详情
     */
    fun formatDiaperDetail(): String {
        val parts = mutableListOf<String>()
        if (diaperWetCount > 0) parts.add("湿$diaperWetCount")
        if (diaperDirtyCount > 0) parts.add("脏$diaperDirtyCount")
        if (diaperMixedCount > 0) parts.add("混合$diaperMixedCount")
        return if (parts.isEmpty()) "" else "(${parts.joinToString("/")})"
    }

    companion object {
        /**
         * 创建空的摘要
         */
        fun empty(date: LocalDate) = DaySummary(date = date)
    }
}

