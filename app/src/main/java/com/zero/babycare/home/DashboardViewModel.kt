package com.zero.babycare.home

import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.bean.DashboardData
import com.zero.babycare.home.prediction.PredictionManager
import com.zero.babycare.home.prediction.PredictionRecordSelector
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.domain.RecordDurationCalculator
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.SleepRecord
import com.zero.common.util.DateUtils
import com.zero.common.util.TimerClock
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
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class DashboardViewModel : BaseViewModel() {

    private val _dashboardUiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val dashboardUiState: StateFlow<DashboardUiState> = _dashboardUiState

    private var currentBaby: BabyInfo? = null
    private var loadJob: Job? = null
    private var loadedSnapshot: LoadedDashboard? = null

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
            loadedSnapshot = null
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
                val snapshot = buildDashboardData(baby)
                loadedSnapshot = snapshot
                _dashboardUiState.value = DashboardUiState.Content(
                    babyId = baby.babyId,
                    babyName = baby.name,
                    data = DashboardDurationMapper.update(
                        snapshot.data, snapshot.feedingSession, snapshot.sleepSession, snapshot.sampledAt,
                        clock = TimerClock.snapshot()
                    ),
                    nowMillis = snapshot.sampledAt,
                    timeZoneId = snapshot.timeZoneId
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                // 页面只暴露可恢复的通用错误态，避免把数据库或设备异常细节带到 UI 与日志。
                _dashboardUiState.value = DashboardUiState.Error
            }
        }
    }

    private suspend fun buildDashboardData(baby: BabyInfo): LoadedDashboard {
        return withContext(Dispatchers.IO) {
            val babyId = baby.babyId
            val babyAgeMonths = com.zero.babycare.home.prediction.PredictionAgePolicy.ageMonths(baby.birthDate)
            val now = Date()
            val timeZoneId = ZoneId.systemDefault().id
            val (startOfDay, endOfDay) = DateUtils.getDayRange(now)

            // 查询今日数据
            val feedings = repository.getFeedingRecordsForDay(babyId, startOfDay, endOfDay)
            val sleeps = repository.getSleepRecordsForDay(babyId, startOfDay, endOfDay)
            // 查询与当天有重叠的记录（用于跨天时长统计）
            val feedingOverlaps = repository.getFeedingRecordsIntersectingDay(babyId, startOfDay, endOfDay)
            val sleepOverlaps = repository.getSleepRecordsIntersectingDay(babyId, startOfDay, endOfDay)

            // 预测在数据库先筛选完成记录，再按完成时间限量，避免辅食占用奶类样本额度。
            val recentFeedings = repository.getRecentCompletedMilkFeedings(babyId, PREDICTION_RECORD_COUNT)
            val recentSleeps = repository.getRecentCompletedSleeps(babyId, PREDICTION_RECORD_COUNT)

            // 计算当前状态（使用 OngoingRecordManager）
            val sleepSession = OngoingRecordManager.getSleepSession(babyId)
            val feedingSession = OngoingRecordManager.getFeedingSession(babyId)

            // “距上次喂养”继续包含辅食；它与只预测下一次吃奶的样本范围不同。
            val lastFeedingEndTime = repository.getLastFeedingRecord(babyId)?.let(PredictionRecordSelector::feedingEnd)
                ?.takeIf { it > 0L }
            val lastSleepEndTime = repository.getLastSleepRecord(babyId)?.let(PredictionRecordSelector::sleepEnd)
                ?.takeIf { it > 0L }

            // 已完成记录与会话增量分别保留，分钟跳动只更新会话派生值。
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

            val data = DashboardData(
                lastFeedingEndTime = lastFeedingEndTime,
                feedingCount = feedingCount,
                totalFeedingMinutes = totalFeedingMinutes,
                lastSleepEndTime = lastSleepEndTime,
                sleepCount = sleepCount,
                totalSleepMinutes = totalSleepMinutes,
                feedingPrediction = feedingPrediction,
                sleepPrediction = sleepPrediction
            )
            LoadedDashboard(data, feedingSession, sleepSession, now.time, timeZoneId)
        }
    }

    private data class LoadedDashboard(
        val data: DashboardData,
        val feedingSession: OngoingRecordManager.OngoingSession?,
        val sleepSession: OngoingRecordManager.OngoingSession?,
        val sampledAt: Long,
        val timeZoneId: String
    )

    private fun observeRecordChanges() {
        viewModelScope.launch {
            BabyRecordChangeBus.changes.collect { changedBabyId ->
                if (changedBabyId == BabyRecordChangeBus.ALL_BABIES_CHANGED || changedBabyId == currentBaby?.babyId) {
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
     * 分钟级时钟更新相对时间与会话时长，自然日改变时重载今日数据。
     */
    private fun refreshClock() {
        val state = _dashboardUiState.value as? DashboardUiState.Content ?: return
        val now = System.currentTimeMillis()
        // 即使没有可用预测也必须在午夜重载，避免整晚停留首页后仍展示昨天的次数。
        // 过期预测由时间渲染隐藏即可；每分钟查询也无法凭空产生新样本。
        if (DashboardClockPolicy.needsDayReload(state.nowMillis, now, state.timeZoneId)) {
            refreshDashboard()
        } else {
            val snapshot = loadedSnapshot ?: return
            _dashboardUiState.value = state.copy(
                nowMillis = now,
                data = DashboardDurationMapper.update(
                    state.data, snapshot.feedingSession, snapshot.sleepSession, now,
                    clock = TimerClock.snapshot()
                )
            )
        }
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
            RecordDurationCalculator.feedingDuration(record, startOfDay, endOfDay) ?: 0L
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
            RecordDurationCalculator.sleepDuration(record, startOfDay, endOfDay) ?: 0L
        }
    }

}
