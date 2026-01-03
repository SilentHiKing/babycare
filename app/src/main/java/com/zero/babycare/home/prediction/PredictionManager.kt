package com.zero.babycare.home.prediction

import com.blankj.utilcode.util.LogUtils
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord

/**
 * 预测管理器
 * 
 * 统一管理预测策略，支持策略切换和未来扩展
 */
object PredictionManager {
    
    // 当前使用的预测器
    private var currentPredictor: Predictor = LocalRulePredictor()
    
    // 可用的预测器列表（用于未来A/B测试或策略切换）
    private val predictors = mutableMapOf<String, Predictor>(
        "local_rule" to LocalRulePredictor()
    )
    
    /**
     * 设置当前预测器
     */
    fun setPredictor(name: String) {
        predictors[name]?.let {
            currentPredictor = it
            LogUtils.d("PredictionManager: Switched to ${it.getName()}")
        } ?: LogUtils.w("PredictionManager: Predictor '$name' not found")
    }
    
    /**
     * 注册新的预测器（用于扩展）
     */
    fun registerPredictor(name: String, predictor: Predictor) {
        predictors[name] = predictor
        LogUtils.d("PredictionManager: Registered ${predictor.getName()}")
    }
    
    /**
     * 预测下次喂奶时间
     */
    fun predictNextFeeding(
        babyAgeMonths: Int,
        feedingRecords: List<FeedingRecord>,
        sleepRecords: List<SleepRecord>? = null
    ): PredictionResult? {
        // 统一按开始时间升序，避免调用方传入 DESC 导致间隔计算异常
        val sortedFeedingRecords = feedingRecords.sortedBy { it.feedingStart }
        val sortedSleepRecords = sleepRecords?.sortedBy { it.sleepStart }
        return try {
            currentPredictor.predictNextFeeding(babyAgeMonths, sortedFeedingRecords, sortedSleepRecords)?.also {
                LogUtils.d("PredictionManager: Feeding prediction - " +
                    "time=${it.predictedTime}, confidence=${it.confidence}, source=${it.source}")
            }
        } catch (e: Exception) {
            LogUtils.e("PredictionManager: Feeding prediction failed", e)
            null
        }
    }
    
    /**
     * 预测下次睡眠时间
     */
    fun predictNextSleep(
        babyAgeMonths: Int,
        sleepRecords: List<SleepRecord>,
        feedingRecords: List<FeedingRecord>? = null
    ): PredictionResult? {
        // 统一按开始时间升序，避免调用方传入 DESC 导致间隔计算异常
        val sortedSleepRecords = sleepRecords.sortedBy { it.sleepStart }
        val sortedFeedingRecords = feedingRecords?.sortedBy { it.feedingStart }
        return try {
            currentPredictor.predictNextSleep(babyAgeMonths, sortedSleepRecords, sortedFeedingRecords)?.also {
                LogUtils.d("PredictionManager: Sleep prediction - " +
                    "time=${it.predictedTime}, confidence=${it.confidence}, source=${it.source}")
            }
        } catch (e: Exception) {
            LogUtils.e("PredictionManager: Sleep prediction failed", e)
            null
        }
    }
    
    /**
     * 获取当前预测器名称
     */
    fun getCurrentPredictorName(): String = currentPredictor.getName()
}

