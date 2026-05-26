package com.zero.babycare.home

import com.blankj.utilcode.util.LogUtils
import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.bean.DashboardData
import com.zero.babycare.home.prediction.PredictionManager
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.common.util.DateUtils
import com.zero.components.base.vm.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.TimeUnit

class DashboardViewModel : BaseViewModel() {

    private val _dashboardUiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val dashboardUiState: StateFlow<DashboardUiState> = _dashboardUiState

    private var currentBaby: BabyInfo? = null
    private var loadJob: Job? = null

    companion object {
        /** 预测使用的最近记录数 */
        private const val PREDICTION_RECORD_COUNT = 15
        private const val CLOCK_TICK_MS = 60_000L
    }

    init {
        observeRecordChanges()
        startClockTicker()
    }

    /**
     * 设置 Dashboard 当前宝宝。
     *
     * 当前宝宝是首页查询的根输入；宝宝切换或宝宝信息编辑后必须重新生成完整状态，
     * 避免 Fragment 通过同步查询自行判断刷新时机。
     */
    fun setCurrentBaby(baby: BabyInfo?) {
        val previousBaby = currentBaby
        currentBaby = baby

        if (baby == null) {
            loadJob?.cancel()
            _dashboardUiState.value = DashboardUiState.NoBaby
            return
        }

        if (previousBaby != baby || _dashboardUiState.value !is DashboardUiState.Content) {
            refreshDashboard()
        }
    }

    /**
     * 页面重新可见时请求一次刷新。
     *
     * 该入口由 ViewModel 统一去重和取消旧查询，Fragment 不再维护 Handler 防抖。
     */
    fun onVisible() {
        refreshDashboard()
    }
    
    /**
     * 加载 Dashboard 数据。
     */
    private fun refreshDashboard() {
        val baby = currentBaby ?: run {
            _dashboardUiState.value = DashboardUiState.NoBaby
            return
        }

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (_dashboardUiState.value !is DashboardUiState.Content) {
                _dashboardUiState.value = DashboardUiState.Loading
            }

            try {
                val data = buildDashboardData(baby)
                _dashboardUiState.value = DashboardUiState.Content(
                    babyId = baby.babyId,
                    babyName = baby.name,
                    data = data,
                    nowMillis = System.currentTimeMillis()
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                LogUtils.d(throwable.stackTraceToString())
                _dashboardUiState.value = DashboardUiState.Error(throwable.message.orEmpty())
            }
        }
    }

    private suspend fun buildDashboardData(baby: BabyInfo): DashboardData {
        return withContext(Dispatchers.IO) {
            val babyId = baby.babyId
            val babyAgeMonths = calculateBabyAgeMonths(baby.birthDate)
            val now = Date()
            val (startOfDay, endOfDay) = DateUtils.getDayRange(now)

            // 查询今日数据
            val feedings = repository.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)
            val sleeps = repository.getSleepRecordsForDay(babyId, startOfDay, endOfDay)
            // 查询与当天有重叠的记录（用于跨天时长统计）
            val feedingOverlaps = repository.getFeedingRecordsIntersectingDay(babyId, startOfDay, endOfDay)
            val sleepOverlaps = repository.getSleepRecordsIntersectingDay(babyId, startOfDay, endOfDay)

            // 获取最近的记录（用于预测）
            val recentFeedings = repository.getRecentFeedings(babyId, PREDICTION_RECORD_COUNT)
            val recentSleeps = repository.getRecentSleeps(babyId, PREDICTION_RECORD_COUNT)

            // 计算当前状态（使用 OngoingRecordManager）
            val currentStatus = determineCurrentStatus(babyId)
            val ongoingSleepStart = if (currentStatus == DashboardData.BabyStatus.SLEEPING) {
                OngoingRecordManager.getOngoingSleepStart(babyId)
            } else null
            val ongoingFeedingStart = if (currentStatus == DashboardData.BabyStatus.FEEDING) {
                OngoingRecordManager.getOngoingFeedingStart(babyId)
            } else null

            // 上次结束时间（用于"距上次"显示）
            // 1. 过滤进行中记录（feedingEnd <= feedingStart 表示未完成）
            // 2. 按结束时间排序，取最新的
            val lastFeedingEndTime = recentFeedings
                .filter { it.feedingEnd > it.feedingStart }
                .maxByOrNull { it.feedingEnd }
                ?.feedingEnd
            val lastSleepEndTime = recentSleeps
                .filter { it.sleepEnd > it.sleepStart }
                .maxByOrNull { it.sleepEnd }
                ?.sleepEnd

            // 今日统计（只包含已完成的记录，进行中的由 UI 层动态计算）
            val feedingCount = feedings.size
            val totalFeedingMs = calculateOverlappedFeedingDuration(
                records = feedingOverlaps,
                startOfDay = startOfDay,
                endOfDay = endOfDay
            )
            val totalFeedingMinutes = TimeUnit.MILLISECONDS.toMinutes(totalFeedingMs)

            val sleepCount = sleeps.size
            val totalSleepMs = calculateOverlappedSleepDuration(
                records = sleepOverlaps,
                startOfDay = startOfDay,
                endOfDay = endOfDay
            )
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

            DashboardData(
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

    private fun observeRecordChanges() {
        viewModelScope.launch {
            BabyRecordChangeBus.changes.collect { changedBabyId ->
                if (changedBabyId == currentBaby?.babyId) {
                    refreshDashboard()
                }
            }
        }
    }

    private fun startClockTicker() {
        viewModelScope.launch {
            while (isActive) {
                delay(CLOCK_TICK_MS)
                refreshClock()
            }
        }
    }

    /**
     * 分钟级时钟只更新相对时间展示；预测过期时再重新查询，避免 Fragment 自己调度刷新。
     */
    private fun refreshClock() {
        val state = _dashboardUiState.value as? DashboardUiState.Content ?: return
        val shouldReloadPrediction =
            state.data.feedingPrediction?.isExpired() == true ||
                state.data.sleepPrediction?.isExpired() == true

        if (shouldReloadPrediction) {
            refreshDashboard()
        } else {
            _dashboardUiState.value = state.copy(nowMillis = System.currentTimeMillis())
        }
    }

    private fun calculateBabyAgeMonths(birthDay: Long): Int {
        if (birthDay <= 0) return 0
        val diffMs = System.currentTimeMillis() - birthDay
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        return (days / 30).toInt().coerceAtLeast(0)
    }

    /**
     * 计算喂养记录与当天的重叠时长（毫秒）
     * 仅对跨天记录做截断，避免“今日时长”被放大或漏算
     */
    private fun calculateOverlappedFeedingDuration(
        records: List<FeedingRecord>,
        startOfDay: Long,
        endOfDay: Long
    ): Long {
        return records.sumOf { record ->
            calculateOverlappedDuration(
                start = record.feedingStart,
                end = record.feedingEnd,
                duration = record.feedingDuration,
                startOfDay = startOfDay,
                endOfDay = endOfDay
            )
        }
    }

    /**
     * 计算睡眠记录与当天的重叠时长（毫秒）
     * 仅对跨天记录做截断，避免“今日时长”被放大或漏算
     */
    private fun calculateOverlappedSleepDuration(
        records: List<SleepRecord>,
        startOfDay: Long,
        endOfDay: Long
    ): Long {
        return records.sumOf { record ->
            calculateOverlappedDuration(
                start = record.sleepStart,
                end = record.sleepEnd,
                duration = record.sleepDuration,
                startOfDay = startOfDay,
                endOfDay = endOfDay
            )
        }
    }

    /**
     * 计算记录与当天的重叠时长，必要时使用记录内的实际时长（完全落在当天时）
     */
    private fun calculateOverlappedDuration(
        start: Long,
        end: Long,
        duration: Long,
        startOfDay: Long,
        endOfDay: Long
    ): Long {
        if (start <= 0L || end <= start) return 0L

        val overlapStart = maxOf(start, startOfDay)
        val overlapEnd = minOf(end, endOfDay)
        val overlap = (overlapEnd - overlapStart).coerceAtLeast(0L)
        if (overlap <= 0L) return 0L

        val isFullyWithinDay = start >= startOfDay && end <= endOfDay
        return if (isFullyWithinDay && duration > 0L) {
            // 记录完全在当天内时，优先使用记录的真实时长
            minOf(duration, overlap)
        } else {
            overlap
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
