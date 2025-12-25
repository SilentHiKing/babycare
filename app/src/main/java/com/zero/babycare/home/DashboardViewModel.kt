package com.zero.babycare.home

import com.zero.babycare.home.bean.DashboardData
import com.zero.babycare.home.prediction.PredictionManager
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.common.util.DateUtils
import com.zero.components.base.vm.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Date
import java.util.concurrent.TimeUnit

class DashboardViewModel : BaseViewModel() {

    private val _dashboardData = MutableStateFlow<DashboardData?>(null)
    val dashboardData: StateFlow<DashboardData?> = _dashboardData

    companion object {
        /** 预测使用的最近记录数 */
        private const val PREDICTION_RECORD_COUNT = 15
    }
    
    /**
     * 加载 Dashboard 数据
     */
    fun loadDashboardData(babyId: Int, babyAgeMonths: Int = 0) {
        safeLaunch {
            val now = Date()
            val (startOfDay, endOfDay) = DateUtils.getDayRange(now)

            // 查询今日数据
            val feedings = repository.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)
            val sleeps = repository.getSleepRecordsForDay(babyId, startOfDay, endOfDay)

            // 获取最近的记录（用于预测）
            val recentFeedings = repository.getRecentFeedings(babyId, PREDICTION_RECORD_COUNT)
            val recentSleeps = repository.getRecentSleeps(babyId, PREDICTION_RECORD_COUNT)
            
            val lastFeeding = recentFeedings.lastOrNull()
            val lastSleep = recentSleeps.lastOrNull()

            // 计算当前状态（使用 OngoingRecordManager）
            val currentStatus = determineCurrentStatus(babyId)
            val ongoingSleepStart = if (currentStatus == DashboardData.BabyStatus.SLEEPING) {
                OngoingRecordManager.getOngoingSleepStart()
            } else null
            val ongoingFeedingStart = if (currentStatus == DashboardData.BabyStatus.FEEDING) {
                OngoingRecordManager.getOngoingFeedingStart()
            } else null

            // 上次结束时间（时间戳，用于实时计算）
            val lastFeedingEndTime = lastFeeding?.feedingEnd
            val lastSleepEndTime = lastSleep?.sleepEnd

            // 今日统计
            val feedingCount = feedings.size
            val totalFeedingMs = feedings.sumOf { it.feedingEnd - it.feedingStart }
            val totalFeedingMinutes = TimeUnit.MILLISECONDS.toMinutes(totalFeedingMs)

            val sleepCount = sleeps.size
            val totalSleepMs = sleeps.sumOf { it.sleepEnd - it.sleepStart }
            val totalSleepMinutes = TimeUnit.MILLISECONDS.toMinutes(totalSleepMs)

            // 使用 PredictionManager 预测
            val feedingPrediction = PredictionManager.predictNextFeeding(
                babyAgeMonths = babyAgeMonths,
                feedingRecords = recentFeedings,
                sleepRecords = recentSleeps
            )
            val sleepPrediction = PredictionManager.predictNextSleep(
                babyAgeMonths = babyAgeMonths,
                sleepRecords = recentSleeps,
                feedingRecords = recentFeedings
            )

            _dashboardData.value = DashboardData(
                currentStatus = currentStatus,
                ongoingSleepStart = ongoingSleepStart,
                ongoingFeedingStart = ongoingFeedingStart,
                lastFeedingEndTime = lastFeedingEndTime,
                feedingCount = feedingCount,
                totalFeedingMinutes = totalFeedingMinutes,
                lastSleepEndTime = lastSleepEndTime,
                sleepCount = sleepCount,
                totalSleepMinutes = totalSleepMinutes,
                feedingPrediction = feedingPrediction,
                sleepPrediction = sleepPrediction
            )
        }
    }

    /**
     * 判断当前宝宝状态
     * 通过 OngoingRecordManager 检查是否有进行中的记录
     */
    private fun determineCurrentStatus(babyId: Int): DashboardData.BabyStatus {
        return when (OngoingRecordManager.getCurrentStatus(babyId)) {
            OngoingRecordManager.OngoingStatus.SLEEPING -> DashboardData.BabyStatus.SLEEPING
            OngoingRecordManager.OngoingStatus.FEEDING -> DashboardData.BabyStatus.FEEDING
            OngoingRecordManager.OngoingStatus.IDLE -> DashboardData.BabyStatus.AWAKE
        }
    }

}
