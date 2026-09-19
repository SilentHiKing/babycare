package com.zero.babycare.reminder

import com.zero.babycare.home.prediction.PredictionResult
import java.util.concurrent.TimeUnit

/** 只使用已有预测区间：中心时间安排提醒，最晚时间之后不补发过时的照护提示。 */
internal object ReminderCandidatePolicy {
    private const val MIN_CONFIDENCE = 0.6f
    private val cooldownMillis = TimeUnit.HOURS.toMillis(2)
    private val maxPredictionHorizonMillis = TimeUnit.HOURS.toMillis(24)

    fun build(
        lastRecordEnd: Long,
        prediction: PredictionResult?,
        lastRemindedAt: Long,
        notifiedSourceRecordTime: Long,
        now: Long
    ): ReminderCandidate? {
        if (lastRecordEnd <= 0L || prediction == null ||
            !prediction.confidence.isFinite() || prediction.confidence < MIN_CONFIDENCE ||
            notifiedSourceRecordTime == lastRecordEnd || prediction.isExpired(now)
        ) return null
        val latest = prediction.latestTime.time
        if (latest <= lastRecordEnd || latest - lastRecordEnd > maxPredictionHorizonMillis) return null

        // 若仍将最晚时间作为触发点，WorkManager 的正常延迟就会令每次投递都已过期。
        // 冷却只能在原预测区间内推迟提醒，不能将旧预测延长为新的有效依据。
        val due = maxOf(prediction.predictedTime.time, lastRemindedAt + cooldownMillis)
        if (due > latest) return null
        return ReminderCandidate(due, lastRecordEnd, latest)
    }
}

internal data class ReminderCandidate(
    val dueTime: Long,
    val sourceRecordTime: Long,
    val expiresAt: Long
) {
    /** 与 PredictionResult 的展示规则相同：截止时刻本身有效，之后立即过期。 */
    fun isExpired(now: Long): Boolean = expiresAt < now
}
