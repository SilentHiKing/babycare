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
    /** 会话时长由 ViewModel 统一计算，暂停时不再继续增长。 */
    val ongoingSleepDurationMillis: Long? = null,
    val ongoingFeedingDurationMillis: Long? = null,
    /** 含未保存会话的今日展示值，与已完成记录总量分开，避免分钟刷新重复叠加。 */
    val displayFeedingMinutes: Long = 0,
    val displaySleepMinutes: Long = 0,

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

