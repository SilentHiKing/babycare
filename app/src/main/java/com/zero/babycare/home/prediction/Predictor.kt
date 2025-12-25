package com.zero.babycare.home.prediction

import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord

/**
 * 预测器接口
 * 定义预测行为的抽象，支持不同实现策略
 */
interface Predictor {
    
    /**
     * 预测下次喂奶时间
     * @param babyAgeMonths 宝宝月龄
     * @param feedingRecords 历史喂奶记录（按时间升序）
     * @param sleepRecords 历史睡眠记录（可选，用于关联分析）
     * @return 预测结果，无法预测时返回 null
     */
    fun predictNextFeeding(
        babyAgeMonths: Int,
        feedingRecords: List<FeedingRecord>,
        sleepRecords: List<SleepRecord>? = null
    ): PredictionResult?
    
    /**
     * 预测下次睡眠时间
     * @param babyAgeMonths 宝宝月龄
     * @param sleepRecords 历史睡眠记录（按时间升序）
     * @param feedingRecords 历史喂奶记录（可选，用于关联分析）
     * @return 预测结果，无法预测时返回 null
     */
    fun predictNextSleep(
        babyAgeMonths: Int,
        sleepRecords: List<SleepRecord>,
        feedingRecords: List<FeedingRecord>? = null
    ): PredictionResult?
    
    /**
     * 获取预测器名称（用于日志和调试）
     */
    fun getName(): String
}

