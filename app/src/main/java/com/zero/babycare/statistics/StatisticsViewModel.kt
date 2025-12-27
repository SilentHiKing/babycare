package com.zero.babycare.statistics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zero.babycare.statistics.model.DaySummary
import com.zero.babycare.statistics.model.TimelineItem
import com.zero.babydata.room.BabyRepository
import com.zero.components.base.vm.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * 统计页面 ViewModel
 */
class StatisticsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BabyRepository(application)

    // ==================== 状态 ====================

    /** 当前宝宝ID */
    private val _currentBabyId = MutableStateFlow(0)
    val currentBabyId: StateFlow<Int> = _currentBabyId.asStateFlow()

    /** 选中的日期 */
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /** 当日统计摘要 */
    private val _daySummary = MutableStateFlow<UiState<DaySummary>>(UiState.Loading)
    val daySummary: StateFlow<UiState<DaySummary>> = _daySummary.asStateFlow()

    /** 时间轴记录列表 */
    private val _timelineItems = MutableStateFlow<UiState<List<TimelineItem>>>(UiState.Loading)
    val timelineItems: StateFlow<UiState<List<TimelineItem>>> = _timelineItems.asStateFlow()

    /** 有记录的日期集合（用于日历标记） */
    private val _datesWithRecords = MutableStateFlow<Set<LocalDate>>(emptySet())
    val datesWithRecords: StateFlow<Set<LocalDate>> = _datesWithRecords.asStateFlow()

    // ==================== 公开方法 ====================

    /**
     * 设置当前宝宝ID并加载数据
     */
    fun setBabyId(babyId: Int) {
        if (_currentBabyId.value != babyId) {
            _currentBabyId.value = babyId
            loadData()
        }
    }

    /**
     * 选择日期
     */
    fun selectDate(date: LocalDate) {
        if (_selectedDate.value != date) {
            _selectedDate.value = date
            loadDayData()
        }
    }

    /**
     * 跳转到今天
     */
    fun goToToday() {
        selectDate(LocalDate.now())
    }

    /**
     * 刷新数据
     */
    fun refreshData() {
        loadData()
    }

    // ==================== 私有方法 ====================

    /**
     * 加载所有数据
     */
    private fun loadData() {
        loadDayData()
        loadDatesWithRecords()
    }

    /**
     * 加载当日数据（摘要+时间轴）
     */
    private fun loadDayData() {
        val babyId = _currentBabyId.value
        val date = _selectedDate.value

        if (babyId <= 0) {
            _daySummary.value = UiState.Success(DaySummary.empty(date))
            _timelineItems.value = UiState.Success(emptyList())
            return
        }

        viewModelScope.launch {
            _daySummary.value = UiState.Loading
            _timelineItems.value = UiState.Loading

            try {
                val (summary, items) = withContext(Dispatchers.IO) {
                    val summaryData = repository.getDaySummary(babyId, date)
                    val timelineRecords = repository.getTimelineRecords(babyId, date)

                    // 转换为 UI 模型
                    val daySummary = DaySummary(
                        date = date,
                        feedingCount = summaryData.feedingCount,
                        feedingTotalMinutes = summaryData.feedingTotalMinutes,
                        feedingTotalMl = summaryData.feedingTotalMl,
                        sleepCount = summaryData.sleepCount,
                        sleepTotalMinutes = summaryData.sleepTotalMinutes,
                        diaperWetCount = summaryData.diaperWetCount,
                        diaperDirtyCount = summaryData.diaperDirtyCount,
                        diaperMixedCount = summaryData.diaperMixedCount,
                        diaperDryCount = summaryData.diaperDryCount,
                        otherEventCount = summaryData.otherEventCount
                    )

                    val timelineItems = timelineRecords.map { record ->
                        when (record) {
                            is BabyRepository.TimelineRecord.Feeding -> TimelineItem.Feeding(record.record)
                            is BabyRepository.TimelineRecord.Sleep -> TimelineItem.Sleep(record.record)
                            is BabyRepository.TimelineRecord.Event -> TimelineItem.Event(record.record)
                        }
                    }

                    Pair(daySummary, timelineItems)
                }

                _daySummary.value = UiState.Success(summary)
                _timelineItems.value = UiState.Success(items)

            } catch (e: Exception) {
                _daySummary.value = UiState.Error(e, e.message ?: "加载失败")
                _timelineItems.value = UiState.Error(e, e.message ?: "加载失败")
            }
        }
    }

    /**
     * 加载有记录的日期（用于日历标记）
     */
    private fun loadDatesWithRecords() {
        val babyId = _currentBabyId.value
        val yearMonth = YearMonth.from(_selectedDate.value)

        if (babyId <= 0) {
            _datesWithRecords.value = emptySet()
            return
        }

        viewModelScope.launch {
            try {
                val dates = withContext(Dispatchers.IO) {
                    repository.getDatesWithRecords(babyId, yearMonth)
                }
                _datesWithRecords.value = dates
            } catch (e: Exception) {
                _datesWithRecords.value = emptySet()
            }
        }
    }

    /**
     * 当月份变化时加载新月份的记录日期
     */
    fun onMonthChanged(yearMonth: YearMonth) {
        val babyId = _currentBabyId.value
        if (babyId <= 0) return

        viewModelScope.launch {
            try {
                val dates = withContext(Dispatchers.IO) {
                    repository.getDatesWithRecords(babyId, yearMonth)
                }
                _datesWithRecords.value = dates
            } catch (e: Exception) {
                // 忽略
            }
        }
    }
}

