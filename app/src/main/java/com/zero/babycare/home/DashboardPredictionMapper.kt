package com.zero.babycare.home

import com.zero.babycare.home.bean.DashboardData
import com.zero.babycare.home.prediction.PredictionResult

/** 首页预测只消费同一次状态快照，不改变预测区间或以顶部单一状态代替两类会话。 */
internal object DashboardPredictionMapper {
    data class Rows(val feeding: Row, val sleep: Row)

    sealed interface Row {
        data object Missing : Row
        data object InProgress : Row
        data object Expired : Row
        data class Ready(val prediction: PredictionResult, val remainingMillis: Long) : Row
    }

    fun map(data: DashboardData, nowMillis: Long): Rows = Rows(
        feeding = row(data.feedingPrediction, data.ongoingFeedingDurationMillis != null, nowMillis),
        sleep = row(data.sleepPrediction, data.ongoingSleepDurationMillis != null, nowMillis)
    )

    private fun row(prediction: PredictionResult?, hasSession: Boolean, nowMillis: Long): Row = when {
        // 新建的零时长、暂停但未保存的会话都尚未结束，不能重新展示此前的预测。
        hasSession -> Row.InProgress
        prediction == null -> Row.Missing
        prediction.isExpired(nowMillis) -> Row.Expired
        else -> Row.Ready(prediction, prediction.getRemainingMillis(nowMillis))
    }
}
