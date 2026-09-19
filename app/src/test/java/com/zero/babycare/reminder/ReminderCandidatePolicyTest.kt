package com.zero.babycare.reminder

import com.zero.babycare.home.prediction.LocalRulePredictor
import com.zero.babycare.home.prediction.PredictionResult
import com.zero.babydata.entity.FeedingRecord
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class ReminderCandidatePolicyTest {
    private val hour = 3_600_000L
    private val sourceTime = 1_767_225_600_000L
    private val prediction = PredictionResult(
        predictedTime = Date(sourceTime + 2 * hour), confidence = 0.8f,
        earliestTime = Date(sourceTime + hour), latestTime = Date(sourceTime + 3 * hour)
    )

    @Test fun `schedule uses center and accepts delayed delivery only through existing window`() {
        val scheduled = build(sourceTime)!!
        assertEquals(prediction.predictedTime.time, scheduled.dueTime)
        assertEquals(prediction.latestTime.time, scheduled.expiresAt)
        assertNotNull(build(prediction.predictedTime.time + 1))
        assertNotNull(build(prediction.latestTime.time))
        assertFalse(scheduled.isExpired(prediction.latestTime.time))
        assertTrue(scheduled.isExpired(prediction.latestTime.time + 1))
        assertNull(build(prediction.latestTime.time + 1))
    }

    @Test fun `cooldown cannot extend prediction validity and notified sources stay suppressed`() {
        assertEquals(sourceTime + 5 * hour / 2, build(sourceTime, sourceTime + hour / 2)!!.dueTime)
        assertNull(build(sourceTime, sourceTime + 2 * hour))
        assertNull(ReminderCandidatePolicy.build(sourceTime, prediction, 0L, sourceTime, sourceTime))
    }

    @Test fun `enabling reminders with old regular history does not revive expired prediction`() {
        val records = (0..9).map { index ->
            val start = sourceTime + index * 3 * hour
            FeedingRecord(feedingStart = start, feedingEnd = start + hour / 4, feedingDuration = hour / 4)
        }
        val result = LocalRulePredictor().predictNextFeeding(4, records, null)!!
        // 规则历史依旧有足够置信度，抑制必须来自时间边界，不能碰巧依赖低置信度。
        assertTrue(result.confidence >= 0.6f)
        val now = sourceTime + 90 * 24 * hour
        assertTrue(result.isExpired(now))
        assertNull(ReminderCandidatePolicy.build(records.last().feedingEnd, result, 0L, 0L, now))
    }

    private fun build(now: Long, lastRemindedAt: Long = 0L) =
        ReminderCandidatePolicy.build(sourceTime, prediction, lastRemindedAt, 0L, now)
}
