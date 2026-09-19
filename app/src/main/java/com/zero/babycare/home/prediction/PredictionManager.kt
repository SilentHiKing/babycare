package com.zero.babycare.home.prediction

import com.blankj.utilcode.util.LogUtils
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord

/**
 * 预测入口只持有一个明确的本地策略，避免未使用的全局可变注册表造成运行时策略漂移。
 */
object PredictionManager {

    private val predictor: Predictor = LocalRulePredictor()

    fun predictNextFeeding(
        babyAgeMonths: Int?,
        feedingRecords: List<FeedingRecord>,
        sleepRecords: List<SleepRecord>? = null
    ): PredictionResult? {
        return runCatching {
            predictor.predictNextFeeding(
                babyAgeMonths,
                feedingRecords.sortedBy { it.feedingStart },
                sleepRecords?.sortedBy { it.sleepStart }
            )
        }.onFailure {
            // 日志只保留故障类别，不记录宝宝时间线或预测时间。
            LogUtils.e("喂养预测失败，原因：${it.javaClass.simpleName}")
        }.getOrNull()
    }

    fun predictNextSleep(
        babyAgeMonths: Int?,
        sleepRecords: List<SleepRecord>,
        feedingRecords: List<FeedingRecord>? = null
    ): PredictionResult? {
        return runCatching {
            predictor.predictNextSleep(
                babyAgeMonths,
                sleepRecords.sortedBy { it.sleepStart },
                feedingRecords?.sortedBy { it.feedingStart }
            )
        }.onFailure {
            LogUtils.e("睡眠预测失败，原因：${it.javaClass.simpleName}")
        }.getOrNull()
    }
}
