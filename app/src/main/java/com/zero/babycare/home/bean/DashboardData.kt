package com.zero.babycare.home.bean

import com.zero.babycare.home.prediction.PredictionResult
import java.util.Date

/**
 * Dashboard 首页数据模型
 */
data class DashboardData(
    // ==================== 实时状态 ====================
    /** 当前宝宝状态 */
    val currentStatus: BabyStatus = BabyStatus.AWAKE,
    /** 正在进行的睡眠开始时间（如果正在睡觉） */
    val ongoingSleepStart: Long? = null,
    /** 正在进行的喂奶开始时间（如果正在喂奶） */
    val ongoingFeedingStart: Long? = null,

    // ==================== 喂养数据 ====================
    /** 上次喂奶结束时间（时间戳，用于实时计算距上次时间） */
    val lastFeedingEndTime: Long? = null,
    /** 今日喂奶次数 */
    val feedingCount: Int = 0,
    /** 今日喂奶总时长（分钟） */
    val totalFeedingMinutes: Long = 0,

    // ==================== 睡眠数据 ====================
    /** 上次睡眠结束时间（时间戳，用于实时计算距上次时间） */
    val lastSleepEndTime: Long? = null,
    /** 今日睡眠次数 */
    val sleepCount: Int = 0,
    /** 今日睡眠总时长（分钟） */
    val totalSleepMinutes: Long = 0,

    // ==================== 预测数据 ====================
    /** 预测下次喂奶结果（包含时间、置信度、区间） */
    val feedingPrediction: PredictionResult? = null,
    /** 预测下次睡觉结果（包含时间、置信度、区间） */
    val sleepPrediction: PredictionResult? = null
) {
    // 兼容旧API
    val predictNextFeedingTime: Date? get() = feedingPrediction?.predictedTime
    val predictNextSleepTime: Date? get() = sleepPrediction?.predictedTime
    /**
     * 宝宝当前状态
     */
    enum class BabyStatus {
        /** 醒着 */
        AWAKE,
        /** 睡觉中 */
        SLEEPING,
        /** 喂奶中 */
        FEEDING
    }
}

