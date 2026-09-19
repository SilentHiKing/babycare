package com.zero.babycare.home.prediction

import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

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
    fun `unknown age must not produce population estimates from sparse records`() {
        val now = System.currentTimeMillis()
        assertNull(predictor.predictNextFeeding(null,
            listOf(createFeedingRecord(now - HOUR, now - 30 * MINUTE)), null))
        assertNull(predictor.predictNextSleep(null,
            listOf(createSleepRecord(now - HOUR, now - 30 * MINUTE)), null))
    }

    @Test
    fun `unknown age still allows history based prediction without age correction`() {
        val times = mixedPeriodTimes()
        val feeding = times.map { createFeedingRecord(it, it + 10 * MINUTE) }
        val sleep = times.map { createSleepRecord(it, it + 10 * MINUTE) }
        val feedingResult = predictor.predictNextFeeding(null, feeding, null)
        val sleepResult = predictor.predictNextSleep(null, sleep, null)
        assertEquals(PredictionSource.LOCAL_RULE, feedingResult!!.source)
        assertEquals(PredictionSource.LOCAL_RULE, sleepResult!!.source)
        assertEquals(feeding.last().feedingEnd + (125.6 * MINUTE).toLong(), feedingResult.predictedTime.time)
        assertEquals(sleep.last().sleepEnd + (125.6 * MINUTE).toLong(), sleepResult.predictedTime.time)
    }

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

    @Test
    fun `predictNextFeeding ignores solid food records when predicting next milk`() {
        val now = System.currentTimeMillis()
        val milkRecords = listOf(
            createFeedingRecord(now - 7 * HOUR, now - 7 * HOUR + 10 * MINUTE),
            createFeedingRecord(now - 4 * HOUR, now - 4 * HOUR + 10 * MINUTE),
            createFeedingRecord(now - 30 * MINUTE, now - 20 * MINUTE)
        )
        val solidFoodRecord = createFeedingRecord(
            start = now - 10 * MINUTE,
            end = now - 5 * MINUTE,
            feedingType = FeedingType.SOLID_FOOD
        )

        val milkOnlyResult = predictor.predictNextFeeding(
            babyAgeMonths = 3,
            feedingRecords = milkRecords,
            sleepRecords = null
        )
        val resultWithSolid = predictor.predictNextFeeding(
            babyAgeMonths = 3,
            feedingRecords = milkRecords + solidFoodRecord,
            sleepRecords = null
        )

        assertNotNull(milkOnlyResult)
        assertNotNull(resultWithSolid)
        assertTrue(
            "辅食记录不应影响下次喂奶预测时间",
            abs(resultWithSolid!!.predictedTime.time - milkOnlyResult!!.predictedTime.time) <= 2 * MINUTE
        )
    }

    @Test
    fun `predictNextFeeding extends interval after a larger bottle amount`() {
        val now = System.currentTimeMillis()
        val normalAmountRecords = listOf(
            createFeedingRecord(now - 10 * HOUR, now - 10 * HOUR + 15 * MINUTE, feedingType = FeedingType.FORMULA, feedingAmount = 120),
            createFeedingRecord(now - 7 * HOUR, now - 7 * HOUR + 15 * MINUTE, feedingType = FeedingType.FORMULA, feedingAmount = 120),
            createFeedingRecord(now - 4 * HOUR, now - 4 * HOUR + 15 * MINUTE, feedingType = FeedingType.FORMULA, feedingAmount = 120),
            createFeedingRecord(now - 30 * MINUTE, now - 15 * MINUTE, feedingType = FeedingType.FORMULA, feedingAmount = 120)
        )
        val largerAmountRecords = normalAmountRecords.dropLast(1) + createFeedingRecord(
            start = now - 30 * MINUTE,
            end = now - 15 * MINUTE,
            feedingType = FeedingType.FORMULA,
            feedingAmount = 240
        )

        val normalResult = predictor.predictNextFeeding(3, normalAmountRecords, null)
        val largerResult = predictor.predictNextFeeding(3, largerAmountRecords, null)

        assertNotNull(normalResult)
        assertNotNull(largerResult)
        assertTrue(
            "明显更大的奶量应适度延后下次喂奶预测",
            largerResult!!.predictedTime.time - normalResult!!.predictedTime.time >= 10 * MINUTE
        )
    }

    @Test
    fun `predictNextFeeding keeps interval range stable when history contains an extreme gap`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            createFeedingRecord(now - 42 * HOUR, now - 42 * HOUR + 10 * MINUTE),
            createFeedingRecord(now - 18 * HOUR, now - 18 * HOUR + 10 * MINUTE),
            createFeedingRecord(now - 7 * HOUR, now - 7 * HOUR + 10 * MINUTE),
            createFeedingRecord(now - 4 * HOUR, now - 4 * HOUR + 10 * MINUTE),
            createFeedingRecord(now - 30 * MINUTE, now - 20 * MINUTE)
        )

        val result = predictor.predictNextFeeding(
            babyAgeMonths = 3,
            feedingRecords = records,
            sleepRecords = null
        )

        assertNotNull(result)
        val fullRange = result!!.latestTime.time - result.earliestTime.time
        assertTrue(
            "漏记造成的极端间隔不应让预测区间超过2小时",
            fullRange <= 2 * HOUR
        )
    }

    // ==================== 辅助方法 ====================

    @Test
    fun `mixed day and night samples keep chronological weights for feeding`() {
        val times = mixedPeriodTimes()
        val records = times.map { createFeedingRecord(it, it + 10 * MINUTE) }
        val result = predictor.predictNextFeeding(4, records, null)
        // 夜间 140 分钟、白天 100/120 分钟按发生顺序做 EWMA，结果为 125.6 分钟。
        val expected = records.last().feedingEnd + (125.6 * MINUTE).toLong()
        assertEquals(expected, result!!.predictedTime.time)
    }

    @Test
    fun `mixed day and night samples keep chronological weights for sleep`() {
        val times = mixedPeriodTimes()
        val records = times.map { createSleepRecord(it, it + 10 * MINUTE) }
        val result = predictor.predictNextSleep(4, records, null)
        val expected = records.last().sleepEnd + (125.6 * MINUTE).toLong()
        assertEquals(expected, result!!.predictedTime.time)
    }

    @Test
    fun `milk reminder source excludes newer solid food and incomplete records`() {
        val milk = createFeedingRecord(1_000, 2_000)
        val solid = createFeedingRecord(3_000, 4_000, FeedingType.SOLID_FOOD)
        val unfinished = createFeedingRecord(5_000, 0)
        val selected = PredictionRecordSelector.completedMilkFeedings(listOf(solid, unfinished, milk))
        assertEquals(listOf(milk), selected)
        assertEquals(2_000L, selected.maxOf { it.feedingEnd })
    }

    private fun mixedPeriodTimes(): List<Long> {
        val day = LocalDate.now().minusDays(2)
        val zone = ZoneId.systemDefault()
        return listOf(4 to 0, 6 to 30, 8 to 20, 10 to 30).map { (hour, minute) ->
            day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        }
    }

    @Test
    fun `sparse overlapping records anchor defaults to latest completion`() {
        val start = LocalDate.now().atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val feeding = listOf(createFeedingRecord(start, start + 5 * HOUR),
            createFeedingRecord(start + 2 * HOUR, start + 2 * HOUR + 15 * MINUTE))
        val sleep = feeding.map { createSleepRecord(it.feedingStart, it.feedingEnd) }
        assertEquals(start + 5 * HOUR + (3.5 * HOUR).toLong(), predictor.predictNextFeeding(4, feeding, null)!!.predictedTime.time)
        assertEquals(start + 7 * HOUR, predictor.predictNextSleep(4, sleep, null)!!.predictedTime.time)
    }

    @Test
    fun `nested records cannot create intervals inside covered care time`() {
        val start = LocalDate.now().atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val feeding = listOf(
            createFeedingRecord(start, start + 4 * HOUR).apply { feedingDuration = 15 * MINUTE },
            createFeedingRecord(start + HOUR, start + HOUR + 15 * MINUTE),
            createFeedingRecord(start + 3 * HOUR, start + 3 * HOUR + 15 * MINUTE),
            createFeedingRecord(start + 5 * HOUR, start + 5 * HOUR + 15 * MINUTE)
        )
        val sleep = feeding.map { createSleepRecord(it.feedingStart, it.feedingEnd) }
        val milkResult = predictor.predictNextFeeding(null, feeding, null)!!
        val sleepResult = predictor.predictNextSleep(null, sleep, null)!!
        assertEquals("只有12点至13点是真正未被记录覆盖的间隔", 1, milkResult.evidenceCount)
        assertEquals(1, sleepResult.evidenceCount)
        assertEquals(feeding.last().feedingEnd + HOUR, milkResult.predictedTime.time)
        assertEquals(sleep.last().sleepEnd + (HOUR * 0.88).toLong(), sleepResult.predictedTime.time)
    }

    @Test
    fun `latest overlapping completion anchors history based feeding and sleep predictions`() {
        val start = LocalDate.now().minusDays(1).atTime(6, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val feeding = (0..8).map { createFeedingRecord(start + it * 2 * HOUR, start + it * 2 * HOUR + 15 * MINUTE) } +
            listOf(createFeedingRecord(start + 26 * HOUR, start + 31 * HOUR).apply { feedingDuration = 15 * MINUTE },
                createFeedingRecord(start + 28 * HOUR, start + 28 * HOUR + 15 * MINUTE))
        val sleep = feeding.map { createSleepRecord(it.feedingStart, it.feedingEnd) }
        assertTrue(predictor.predictNextFeeding(4, feeding, null)!!.earliestTime.time >= start + 31 * HOUR)
        assertTrue(predictor.predictNextSleep(4, sleep, null)!!.earliestTime.time >= start + 31 * HOUR)
    }

    @Test
    fun `feeding duration factor uses active duration and retains legacy zero fallback`() {
        val start = LocalDate.now().minusDays(1).atTime(6, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val records = (0..7).map { createFeedingRecord(start + it * 3 * HOUR, start + it * 3 * HOUR + 15 * MINUTE) }
        val regular = predictor.predictNextFeeding(4, records, null)!!
        val anchor = records.last().feedingEnd
        assertEquals(anchor + 165 * MINUTE, regular.predictedTime.time)
        records.last().feedingDuration = MINUTE
        assertEquals(anchor + (165 * MINUTE * 0.92).toLong(), predictor.predictNextFeeding(4, records, null)!!.predictedTime.time)
        records.last().feedingDuration = 0L
        assertEquals(regular.predictedTime, predictor.predictNextFeeding(4, records, null)!!.predictedTime)
    }

    private fun createFeedingRecord(
        start: Long,
        end: Long,
        feedingType: FeedingType = FeedingType.BREAST,
        feedingAmount: Int? = null
    ): FeedingRecord {
        return FeedingRecord().apply {
            this.feedingType = feedingType.type
            feedingStart = start
            feedingEnd = end
            feedingDuration = end - start
            this.feedingAmount = feedingAmount
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

