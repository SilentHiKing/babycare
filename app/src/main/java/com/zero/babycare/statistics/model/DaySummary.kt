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

    companion object {
        /**
         * 创建空的摘要
         */
        fun empty(date: LocalDate) = DaySummary(date = date)
    }
}

