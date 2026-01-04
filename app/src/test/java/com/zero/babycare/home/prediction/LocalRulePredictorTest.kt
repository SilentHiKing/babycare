package com.zero.babycare.home.prediction

import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * LocalRulePredictor 单元测试
 * 覆盖主要场景和边界情况
 */
class LocalRulePredictorTest {

    private lateinit var predictor: LocalRulePredictor

    @Before
    fun setup() {
        predictor = LocalRulePredictor()
    }

    // ==================== 基本预测测试 ====================

    @Test
    fun `predictNextFeeding returns null when no records`() {
        val result = predictor.predictNextFeeding(
            babyAgeMonths = 3,
            feedingRecords = emptyList(),
            sleepRecords = null
        )
        assertNull("空记录应返回null", result)
    }

    @Test
    fun `predictNextFeeding uses default when less than 3 records`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            createFeedingRecord(now - 3 * HOUR, now - 2 * HOUR - 30 * MINUTE),
            createFeedingRecord(now - 1 * HOUR, now - 30 * MINUTE)
        )
        
        val result = predictor.predictNextFeeding(
            babyAgeMonths = 3,
            feedingRecords = records,
            sleepRecords = null
        )
        
        assertNotNull("2条记录应使用默认值预测", result)
        assertEquals("来源应为群体统计", PredictionSource.POPULATION_STATS, result?.source)
        assertTrue("置信度应较低", result?.confidence ?: 0f <= 0.4f)
    }

    @Test
    fun `predictNextFeeding with sufficient records returns local rule`() {
        val now = System.currentTimeMillis()
        // 使用更近期的记录，确保预测时间在未来
        val records = listOf(
            createFeedingRecord(now - 6 * HOUR, now - 5 * HOUR - 30 * MINUTE),
            createFeedingRecord(now - 3 * HOUR, now - 2 * HOUR - 30 * MINUTE),
            createFeedingRecord(now - 30 * MINUTE, now - 10 * MINUTE)  // 刚结束10分钟
        )
        
        val result = predictor.predictNextFeeding(
            babyAgeMonths = 3,
            feedingRecords = records,
            sleepRecords = null
        )
        
        assertNotNull("3条记录应正常预测", result)
        assertEquals("来源应为本地规则", PredictionSource.LOCAL_RULE, result?.source)
        // 间隔约2.5-3小时，从10分钟前开始算，预测时间应在2小时后（即未来）
        assertTrue("预测时间应在未来", result!!.predictedTime.time > now)
    }

    // ==================== 进行中记录过滤测试 ====================

    @Test
    fun `predictNextFeeding filters out ongoing records`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            createFeedingRecord(now - 9 * HOUR, now - 8 * HOUR - 30 * MINUTE),
            createFeedingRecord(now - 6 * HOUR, now - 5 * HOUR - 30 * MINUTE),
            createFeedingRecord(now - 3 * HOUR, now - 2 * HOUR - 30 * MINUTE),
            // 进行中记录: feedingEnd = 0
            FeedingRecord().apply {
                feedingStart = now - 10 * MINUTE
                feedingEnd = 0
            }
        )
        
        val result = predictor.predictNextFeeding(
            babyAgeMonths = 3,
            feedingRecords = records,
            sleepRecords = null
        )
        
        assertNotNull("应过滤进行中记录后正常预测", result)
        // 预测应基于前3条完成的记录，而非进行中的第4条
        assertTrue("预测时间应合理", result!!.predictedTime.time > now - 2 * HOUR - 30 * MINUTE)
    }

    @Test
    fun `predictNextFeeding filters invalid records where end less than start`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            createFeedingRecord(now - 9 * HOUR, now - 8 * HOUR - 30 * MINUTE),
            createFeedingRecord(now - 6 * HOUR, now - 5 * HOUR - 30 * MINUTE),
            createFeedingRecord(now - 3 * HOUR, now - 2 * HOUR - 30 * MINUTE),
            // 异常记录: feedingEnd < feedingStart
            FeedingRecord().apply {
                feedingStart = now - 1 * HOUR
                feedingEnd = now - 2 * HOUR
            }
        )
        
        val result = predictor.predictNextFeeding(
            babyAgeMonths = 3,
            feedingRecords = records,
            sleepRecords = null
        )
        
        assertNotNull("应过滤异常记录后正常预测", result)
    }

    // ==================== 区间范围测试 ====================

    @Test
    fun `prediction interval has minimum range when stdDev is zero`() {
        val now = System.currentTimeMillis()
        // 所有间隔完全相同（3小时）
        val records = listOf(
            createFeedingRecord(now - 9 * HOUR, now - 9 * HOUR + 30 * MINUTE),
            createFeedingRecord(now - 6 * HOUR, now - 6 * HOUR + 30 * MINUTE),
            createFeedingRecord(now - 3 * HOUR, now - 3 * HOUR + 30 * MINUTE)
        )
        
        val result = predictor.predictNextFeeding(
            babyAgeMonths = 3,
            feedingRecords = records,
            sleepRecords = null
        )
        
        assertNotNull(result)
        // 区间不应为0（最小5分钟）
        val range = result!!.latestTime.time - result.earliestTime.time
        assertTrue("区间范围应至少5分钟", range >= 5 * MINUTE)
    }

    // ==================== 睡眠预测测试 ====================

    @Test
    fun `predictNextSleep returns null when no records`() {
        val result = predictor.predictNextSleep(
            babyAgeMonths = 3,
            sleepRecords = emptyList(),
            feedingRecords = null
        )
        assertNull(result)
    }

    @Test
    fun `predictNextSleep filters ongoing sleep records`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            createSleepRecord(now - 8 * HOUR, now - 7 * HOUR),
            createSleepRecord(now - 5 * HOUR, now - 4 * HOUR),
            createSleepRecord(now - 2 * HOUR, now - 1 * HOUR),
            // 进行中记录
            SleepRecord().apply {
                sleepStart = now - 10 * MINUTE
                sleepEnd = 0
            }
        )
        
        val result = predictor.predictNextSleep(
            babyAgeMonths = 3,
            sleepRecords = records,
            feedingRecords = null
        )
        
        assertNotNull("应过滤进行中记录后正常预测", result)
    }

    // ==================== 置信度测试 ====================

    @Test
    fun `confidence increases with more records`() {
        val now = System.currentTimeMillis()
        
        // 3条记录
        val records3 = (0..2).map { i ->
            createFeedingRecord(now - (9 - i * 3) * HOUR, now - (9 - i * 3) * HOUR + 30 * MINUTE)
        }
        val result3 = predictor.predictNextFeeding(3, records3, null)
        
        // 10条记录（间隔一致）
        val records10 = (0..9).map { i ->
            createFeedingRecord(now - (30 - i * 3) * HOUR, now - (30 - i * 3) * HOUR + 30 * MINUTE)
        }
        val result10 = predictor.predictNextFeeding(3, records10, null)
        
        assertTrue(
            "更多记录应有更高置信度",
            result10!!.confidence >= result3!!.confidence
        )
    }

    // ==================== 辅助方法 ====================

    private fun createFeedingRecord(start: Long, end: Long): FeedingRecord {
        return FeedingRecord().apply {
            feedingStart = start
            feedingEnd = end
            feedingDuration = end - start
        }
    }

    private fun createSleepRecord(start: Long, end: Long): SleepRecord {
        return SleepRecord().apply {
            sleepStart = start
            sleepEnd = end
            sleepDuration = end - start
        }
    }

    companion object {
        private const val MINUTE = 60 * 1000L
        private const val HOUR = 60 * MINUTE
    }
}

