package com.zero.babycare.home

import com.zero.babycare.home.DashboardPredictionMapper.Row
import com.zero.babycare.home.bean.DashboardData
import com.zero.babycare.home.prediction.PredictionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Date

class DashboardPredictionMapperTest {
    private val deadline = 1_800_000L
    private val prediction = PredictionResult(Date(deadline - 300_000L), 0.8f,
        Date(deadline - 600_000L), Date(deadline), evidenceCount = 8)

    @Test
    fun `both unfinished sessions suppress their own prediction regardless of headline status`() {
        DashboardData.BabyStatus.entries.forEach { status ->
            val data = DashboardData(currentStatus = status, feedingPrediction = prediction, sleepPrediction = prediction,
                ongoingFeedingDurationMillis = 0L, ongoingSleepDurationMillis = 60_000L)
            val result = DashboardPredictionMapper.map(data, deadline - 1L)
            assertEquals(Row.InProgress, result.feeding)
            assertEquals(Row.InProgress, result.sleep)
            // 清除一类会话不能顺带阻止另一类预测，也不依赖顶部状态枚举恰好同步。
            assertEquals(Row.Ready(prediction, 1L), DashboardPredictionMapper.map(
                data.copy(ongoingFeedingDurationMillis = null), deadline - 1L).feeding)
            assertEquals(Row.Ready(prediction, 1L), DashboardPredictionMapper.map(
                data.copy(ongoingSleepDurationMillis = null), deadline - 1L).sleep)
        }
    }

    @Test
    fun `supplied clock controls inclusive deadline and remaining amount without changing evidence`() {
        val data = DashboardData(feedingPrediction = prediction, sleepPrediction = prediction)
        listOf(deadline - 60_000L, deadline - 1L, deadline).forEach { now ->
            val result = DashboardPredictionMapper.map(data, now)
            val row = result.feeding as Row.Ready
            assertEquals(deadline - now, row.remainingMillis)
            assertSame("呈现不能重新生成或移动预测区间与依据", prediction, row.prediction)
            assertEquals(row, result.sleep)
        }
        assertEquals(DashboardPredictionMapper.Rows(Row.Expired, Row.Expired),
            DashboardPredictionMapper.map(data, deadline + 1L))
        assertEquals(0L, prediction.getRemainingMillis(deadline + 1L))
    }

    @Test
    fun `expired and missing remain distinct while unfinished session takes precedence`() {
        val data = DashboardData(feedingPrediction = prediction)
        assertEquals(DashboardPredictionMapper.Rows(Row.Expired, Row.Missing),
            DashboardPredictionMapper.map(data, deadline + 1L))
        assertEquals(DashboardPredictionMapper.Rows(Row.InProgress, Row.InProgress),
            DashboardPredictionMapper.map(data.copy(ongoingFeedingDurationMillis = 0L,
                ongoingSleepDurationMillis = 0L), deadline + 1L))
    }
}
