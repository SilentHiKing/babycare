package com.zero.babycare.home.prediction

import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import java.util.Calendar
import java.util.Date
import kotlin.math.sqrt

/**
 * 本地规则引擎预测器
 * 
 * 基于统计规则和科学依据进行预测，无需网络，隐私友好
 */
class LocalRulePredictor : Predictor {

    companion object {
        // 最少需要的记录数
        private const val MIN_RECORDS_FOR_PREDICTION = 3
        
        // 最少需要的时段记录数（低于此值时合并白天/夜间数据）
        private const val MIN_PERIOD_RECORDS = 3
        
        // 最小区间范围（5分钟，避免标准差为0时区间退化为单点）
        private const val MIN_INTERVAL_RANGE_MS = 5 * 60 * 1000L
        
        // EWMA 平滑系数（0.3 表示最近记录权重更高，越接近1越敏感于新数据）
        private const val EWMA_ALPHA = 0.3
        
        // 白天时间段：6:00 - 20:00
        private const val DAY_START_HOUR = 6
        private const val DAY_END_HOUR = 20
        
        // 默认喂奶间隔（毫秒）- 按月龄
        private val DEFAULT_FEEDING_INTERVALS = mapOf(
            0 to 2.5 * 60 * 60 * 1000,   // 0-1个月: 2.5小时
            1 to 2.5 * 60 * 60 * 1000,
            2 to 3.0 * 60 * 60 * 1000,   // 2-3个月: 3小时
            3 to 3.0 * 60 * 60 * 1000,
            4 to 3.5 * 60 * 60 * 1000,   // 4-6个月: 3.5小时
            5 to 3.5 * 60 * 60 * 1000,
            6 to 4.0 * 60 * 60 * 1000,   // 6个月+: 4小时
        )
        
        // 默认睡眠间隔（清醒窗口，毫秒）- 按月龄
        private val DEFAULT_AWAKE_WINDOWS = mapOf(
            0 to 45.0 * 60 * 1000,       // 0-1个月: 45分钟
            1 to 60.0 * 60 * 1000,       // 1-2个月: 1小时
            2 to 75.0 * 60 * 1000,       // 2-3个月: 1.25小时
            3 to 90.0 * 60 * 1000,       // 3-4个月: 1.5小时
            4 to 120.0 * 60 * 1000,      // 4-6个月: 2小时
            5 to 120.0 * 60 * 1000,
            6 to 150.0 * 60 * 1000,      // 6个月+: 2.5小时
        )
    }

    override fun getName(): String = "LocalRulePredictor"

    override fun predictNextFeeding(
        babyAgeMonths: Int,
        feedingRecords: List<FeedingRecord>,
        sleepRecords: List<SleepRecord>?
    ): PredictionResult? {
        // 过滤掉进行中/无效记录（feedingEnd 必须大于 feedingStart）
        val completedRecords = feedingRecords.filter { it.feedingEnd > it.feedingStart }
        
        // 数据不足时使用默认值
        if (completedRecords.size < MIN_RECORDS_FOR_PREDICTION) {
            return predictWithDefault(babyAgeMonths, completedRecords.lastOrNull()?.feedingEnd, isFeeding = true)
        }
        
        // 按时段分析间隔
        val intervals = calculateIntervals(completedRecords.map { it.feedingStart to it.feedingEnd })
        val dayIntervals = intervals.filter { it.second }.map { it.first }
        val nightIntervals = intervals.filter { !it.second }.map { it.first }
        
        // 根据当前时间选择使用哪个时段的数据
        val isDay = isCurrentlyDay()
        var relevantIntervals = if (isDay) {
            dayIntervals.ifEmpty { nightIntervals }
        } else {
            nightIntervals.ifEmpty { dayIntervals }
        }
        
        // 时段数据不足时，合并使用所有数据
        if (relevantIntervals.size < MIN_PERIOD_RECORDS) {
            val allIntervals = dayIntervals + nightIntervals
            if (allIntervals.size >= MIN_PERIOD_RECORDS) {
                relevantIntervals = allIntervals
            } else if (allIntervals.isEmpty()) {
                return predictWithDefault(babyAgeMonths, completedRecords.last().feedingEnd, isFeeding = true)
            }
            // allIntervals 不为空但少于 MIN_PERIOD_RECORDS，仍使用这些数据但置信度会较低
            if (relevantIntervals.isEmpty()) {
                relevantIntervals = allIntervals
            }
        }
        
        // 计算统计值（使用 EWMA 时间衰减）
        val stats = calculateStats(relevantIntervals)
        
        // 月龄修正因子
        val ageFactor = getAgeFactor(babyAgeMonths, isFeeding = true)
        
        // 上次喂奶时长修正
        val lastRecord = completedRecords.last()
        val durationFactor = calculateDurationFactor(lastRecord, completedRecords)
        
        // 计算预测间隔（使用 EWMA 替代简单中位数，更关注近期规律）
        val predictedInterval = (stats.ewma * ageFactor * durationFactor).toLong()
        val predictedTime = lastRecord.feedingEnd + predictedInterval
        
        // 计算置信度
        val confidence = calculateConfidence(
            recordCount = completedRecords.size,
            coefficientOfVariation = stats.cv,
            hasEnoughData = relevantIntervals.size >= 5
        )
        
        // 计算区间（基于标准差，设置最小值避免区间为0）
        val intervalRange = maxOf((stats.stdDev * 0.8).toLong(), MIN_INTERVAL_RANGE_MS)
        val earliestTime = predictedTime - intervalRange
        val latestTime = predictedTime + intervalRange
        
        return PredictionResult(
            predictedTime = Date(predictedTime),
            confidence = confidence,
            earliestTime = Date(earliestTime),
            latestTime = Date(latestTime),
            source = PredictionSource.LOCAL_RULE
        )
    }

    override fun predictNextSleep(
        babyAgeMonths: Int,
        sleepRecords: List<SleepRecord>,
        feedingRecords: List<FeedingRecord>?
    ): PredictionResult? {
        // 过滤掉进行中/无效记录（sleepEnd 必须大于 sleepStart）
        val completedRecords = sleepRecords.filter { it.sleepEnd > it.sleepStart }
        
        // 数据不足时使用默认清醒窗口
        if (completedRecords.size < MIN_RECORDS_FOR_PREDICTION) {
            return predictWithDefault(babyAgeMonths, completedRecords.lastOrNull()?.sleepEnd, isFeeding = false)
        }
        
        // 按时段分析间隔（上次睡醒到下次入睡的时间）
        val awakeIntervals = mutableListOf<Pair<Long, Boolean>>()  // (interval, isDay)
        for (i in 0 until completedRecords.size - 1) {
            val curr = completedRecords[i]
            val next = completedRecords[i + 1]
            val interval = next.sleepStart - curr.sleepEnd
            if (interval > 0) {
                val isDay = isTimeInDay(curr.sleepEnd)
                awakeIntervals.add(interval to isDay)
            }
        }
        
        val dayIntervals = awakeIntervals.filter { it.second }.map { it.first }
        val nightIntervals = awakeIntervals.filter { !it.second }.map { it.first }
        
        val isDay = isCurrentlyDay()
        var relevantIntervals = if (isDay) {
            dayIntervals.ifEmpty { nightIntervals }
        } else {
            nightIntervals.ifEmpty { dayIntervals }
        }
        
        // 时段数据不足时，合并使用所有数据
        if (relevantIntervals.size < MIN_PERIOD_RECORDS) {
            val allIntervals = dayIntervals + nightIntervals
            if (allIntervals.size >= MIN_PERIOD_RECORDS) {
                relevantIntervals = allIntervals
            } else if (allIntervals.isEmpty()) {
                return predictWithDefault(babyAgeMonths, completedRecords.last().sleepEnd, isFeeding = false)
            }
            if (relevantIntervals.isEmpty()) {
                relevantIntervals = allIntervals
            }
        }
        
        val stats = calculateStats(relevantIntervals)
        val ageFactor = getAgeFactor(babyAgeMonths, isFeeding = false)
        
        // 上次睡眠时长修正（睡得短→清醒窗口短，睡得长→清醒窗口长）
        val lastRecord = completedRecords.last()
        val durationFactor = calculateSleepDurationFactor(lastRecord, completedRecords)
        
        val predictedInterval = (stats.ewma * ageFactor * durationFactor).toLong()
        val lastSleepEnd = lastRecord.sleepEnd
        val predictedTime = lastSleepEnd + predictedInterval
        
        val confidence = calculateConfidence(
            recordCount = completedRecords.size,
            coefficientOfVariation = stats.cv,
            hasEnoughData = relevantIntervals.size >= 5
        )
        
        // 设置最小区间，避免标准差为0时区间退化为单点
        val intervalRange = maxOf((stats.stdDev * 0.8).toLong(), MIN_INTERVAL_RANGE_MS)
        
        return PredictionResult(
            predictedTime = Date(predictedTime),
            confidence = confidence,
            earliestTime = Date(predictedTime - intervalRange),
            latestTime = Date(predictedTime + intervalRange),
            source = PredictionSource.LOCAL_RULE
        )
    }

    /**
     * 使用默认值预测（冷启动/数据不足）
     */
    private fun predictWithDefault(
        babyAgeMonths: Int,
        lastEndTime: Long?,
        isFeeding: Boolean
    ): PredictionResult? {
        val baseTime = lastEndTime ?: return null
        
        val defaultInterval = if (isFeeding) {
            DEFAULT_FEEDING_INTERVALS[babyAgeMonths.coerceIn(0, 6)]
                ?: DEFAULT_FEEDING_INTERVALS[6]!!
        } else {
            DEFAULT_AWAKE_WINDOWS[babyAgeMonths.coerceIn(0, 6)]
                ?: DEFAULT_AWAKE_WINDOWS[6]!!
        }
        
        val predictedTime = baseTime + defaultInterval.toLong()
        val intervalRange = (defaultInterval * 0.2).toLong()  // 20%区间
        
        return PredictionResult(
            predictedTime = Date(predictedTime),
            confidence = 0.35f,  // 默认值置信度较低
            earliestTime = Date(predictedTime - intervalRange),
            latestTime = Date(predictedTime + intervalRange),
            source = PredictionSource.POPULATION_STATS  // 标记为群体统计
        )
    }

    /**
     * 计算两次活动之间的间隔
     * @return List of (interval, isDay)
     */
    private fun calculateIntervals(records: List<Pair<Long, Long>>): List<Pair<Long, Boolean>> {
        val result = mutableListOf<Pair<Long, Boolean>>()
        for (i in 0 until records.size - 1) {
            val currEnd = records[i].second
            val nextStart = records[i + 1].first
            val interval = nextStart - currEnd
            if (interval > 0) {
                val isDay = isTimeInDay(currEnd)
                result.add(interval to isDay)
            }
        }
        return result
    }

    /**
     * 计算统计值（包含 EWMA 时间衰减）
     */
    private fun calculateStats(intervals: List<Long>): IntervalStats {
        if (intervals.isEmpty()) {
            return IntervalStats(0.0, 0.0, 0.0, 0.0, 1.0)
        }
        
        val sorted = intervals.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2].toDouble()
        }
        
        val mean = intervals.average()
        val variance = intervals.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        // 变异系数：mean <= 0 视为数据异常，返回高CV降低置信度
        val cv = if (mean > 0) stdDev / mean else Double.MAX_VALUE
        
        // EWMA 指数加权移动平均（最近记录权重更高）
        // 按时间顺序，最后一个是最新的
        val ewma = calculateEwma(intervals)
        
        return IntervalStats(median, mean, stdDev, ewma, cv)
    }
    
    /**
     * 计算指数加权移动平均（EWMA）
     * 最近的数据权重更高，更能反映当前规律
     * @param values 按时间顺序排列的值列表（最后一个是最新的）
     */
    private fun calculateEwma(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        if (values.size == 1) return values[0].toDouble()
        
        // EWMA: S_t = α * X_t + (1-α) * S_{t-1}
        var ewma = values[0].toDouble()
        for (i in 1 until values.size) {
            ewma = EWMA_ALPHA * values[i] + (1 - EWMA_ALPHA) * ewma
        }
        return ewma
    }

    /**
     * 月龄修正因子
     */
    private fun getAgeFactor(ageMonths: Int, isFeeding: Boolean): Double {
        return if (isFeeding) {
            when (ageMonths) {
                in 0..1 -> 0.9    // 新生儿更频繁
                in 2..3 -> 0.95
                in 4..5 -> 1.0
                in 6..8 -> 1.05
                else -> 1.1      // 大宝宝间隔更长
            }
        } else {
            // 睡眠的清醒窗口随月龄增长
            when (ageMonths) {
                in 0..1 -> 0.85
                in 2..3 -> 0.95
                in 4..5 -> 1.0
                else -> 1.1
            }
        }
    }

    /**
     * 根据上次喂奶时长调整（喂得久→间隔长）
     */
    private fun calculateDurationFactor(
        lastRecord: FeedingRecord,
        allRecords: List<FeedingRecord>
    ): Double {
        if (allRecords.size < 3) return 1.0
        
        // 确保时长为非负值，避免异常数据影响计算
        val avgDuration = allRecords.takeLast(10)
            .map { (it.feedingEnd - it.feedingStart).coerceAtLeast(0L) }
            .average()
        val lastDuration = (lastRecord.feedingEnd - lastRecord.feedingStart).coerceAtLeast(0L)
        
        return when {
            lastDuration > avgDuration * 1.3 -> 1.08   // 喂得久，间隔延长
            lastDuration < avgDuration * 0.7 -> 0.92   // 喂得短，间隔缩短
            else -> 1.0
        }
    }
    
    /**
     * 根据上次睡眠时长调整清醒窗口（睡得短→清醒窗口短，睡得长→清醒窗口长）
     */
    private fun calculateSleepDurationFactor(
        lastRecord: SleepRecord,
        allRecords: List<SleepRecord>
    ): Double {
        if (allRecords.size < 3) return 1.0
        
        // 确保时长为非负值
        val avgDuration = allRecords.takeLast(10)
            .map { (it.sleepEnd - it.sleepStart).coerceAtLeast(0L) }
            .average()
        val lastDuration = (lastRecord.sleepEnd - lastRecord.sleepStart).coerceAtLeast(0L)
        
        return when {
            lastDuration > avgDuration * 1.3 -> 1.10   // 睡得久，清醒窗口延长
            lastDuration < avgDuration * 0.7 -> 0.88   // 睡得短，清醒窗口缩短
            else -> 1.0
        }
    }

    /**
     * 计算置信度
     */
    private fun calculateConfidence(
        recordCount: Int,
        coefficientOfVariation: Double,
        hasEnoughData: Boolean
    ): Float {
        var confidence = 0.45f  // 基础置信度
        
        // 数据量加成（最多+25%）
        confidence += minOf(recordCount * 0.025f, 0.25f)
        
        // 规律性加成（变异系数小=规律性强）
        confidence += when {
            coefficientOfVariation < 0.15 -> 0.2f   // 非常规律
            coefficientOfVariation < 0.25 -> 0.1f   // 较规律
            coefficientOfVariation < 0.35 -> 0.0f   // 一般
            else -> -0.1f                           // 不规律
        }
        
        // 数据充足加成
        if (hasEnoughData) confidence += 0.05f
        
        return confidence.coerceIn(0.2f, 0.9f)
    }

    /**
     * 判断当前是否是白天
     */
    private fun isCurrentlyDay(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour in DAY_START_HOUR until DAY_END_HOUR
    }

    /**
     * 判断指定时间戳是否是白天
     */
    private fun isTimeInDay(timestamp: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return hour in DAY_START_HOUR until DAY_END_HOUR
    }

    /**
     * 间隔统计数据
     */
    private data class IntervalStats(
        val median: Double,    // 中位数
        val mean: Double,      // 平均值
        val stdDev: Double,    // 标准差
        val ewma: Double,      // 指数加权移动平均（时间衰减）
        val cv: Double         // 变异系数
    )
}

