package com.zero.babycare.home.prediction

import java.util.Date

/**
 * 预测结果
 */
data class PredictionResult(
    /** 预测时间 */
    val predictedTime: Date,
    /** 置信度 (0.0 - 1.0) */
    val confidence: Float,
    /** 预测区间 - 最早时间 */
    val earliestTime: Date,
    /** 预测区间 - 最晚时间 */
    val latestTime: Date,
    /** 预测来源/方法 */
    val source: PredictionSource = PredictionSource.LOCAL_RULE
) {
    /**
     * 获取置信度等级描述
     */
    fun getConfidenceLevel(): ConfidenceLevel {
        return when {
            confidence >= 0.8f -> ConfidenceLevel.HIGH
            confidence >= 0.6f -> ConfidenceLevel.MEDIUM
            confidence >= 0.4f -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.VERY_LOW
        }
    }
    
    /**
     * 检查预测时间是否已过期
     */
    fun isExpired(): Boolean {
        // 以区间的最晚时间为准，避免中心点已过但预测区间仍有效
        return latestTime.time < System.currentTimeMillis()
    }
    
    /**
     * 获取距预测区间最晚时间的剩余毫秒数
     * 与过期判断保持一致，避免中心点已过但区间仍有效导致显示为 0
     */
    fun getRemainingMillis(): Long {
        return (latestTime.time - System.currentTimeMillis()).coerceAtLeast(0)
    }
}

/**
 * 置信度等级
 */
enum class ConfidenceLevel {
    HIGH,       // 高：规律明显，数据充足
    MEDIUM,     // 中：有一定规律
    LOW,        // 低：数据较少或规律不明显
    VERY_LOW    // 极低：几乎无法预测
}

/**
 * 预测来源
 */
enum class PredictionSource {
    /** 本地规则引擎 */
    LOCAL_RULE,
    /** 群体统计（冷启动） */
    POPULATION_STATS,
    /** 云端ML模型 */
    CLOUD_ML,
    /** 混合模式 */
    ENSEMBLE
}

