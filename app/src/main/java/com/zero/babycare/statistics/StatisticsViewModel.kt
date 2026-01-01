package com.zero.babycare.statistics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zero.babycare.statistics.model.DaySummary
import com.zero.babycare.statistics.model.GrowthPercentileOverview
import com.zero.babycare.statistics.model.GrowthPercentilePoint
import com.zero.babycare.statistics.model.GrowthPercentileSeries
import com.zero.babycare.statistics.model.GrowthTrend
import com.zero.babycare.statistics.model.GrowthTrendItem
import com.zero.babycare.statistics.model.HealthStats
import com.zero.babycare.statistics.model.StructureItem
import com.zero.babycare.statistics.model.StructureOverview
import com.zero.babycare.statistics.model.StructureSection
import com.zero.babycare.statistics.model.TimelineItem
import com.zero.babycare.statistics.model.TrendOverview
import com.zero.babycare.statistics.model.TrendPeriod
import com.zero.babycare.statistics.model.TrendPeriodSummary
import com.zero.babycare.statistics.standard.WhoGrowthStandardProvider
import com.zero.babycare.statistics.standard.WhoIndicator
import com.zero.babycare.statistics.standard.WhoSex
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.GrowthData
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.room.BabyRepository
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import com.zero.components.base.vm.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 统计页面 ViewModel
 */
class StatisticsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BabyRepository(application)
    private val whoStandardProvider by lazy { WhoGrowthStandardProvider(getApplication()) }

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

    /** 生长趋势对比 */
    private val _growthTrend = MutableStateFlow<UiState<GrowthTrend>>(UiState.Loading)
    val growthTrend: StateFlow<UiState<GrowthTrend>> = _growthTrend.asStateFlow()

    /** 周/月/年趋势概览 */
    private val _trendOverview = MutableStateFlow<UiState<TrendOverview>>(UiState.Loading)
    val trendOverview: StateFlow<UiState<TrendOverview>> = _trendOverview.asStateFlow()

    /** 结构图数据 */
    private val _structureOverview = MutableStateFlow<UiState<StructureOverview>>(UiState.Loading)
    val structureOverview: StateFlow<UiState<StructureOverview>> = _structureOverview.asStateFlow()

    /** 生长百分位数据 */
    private val _growthPercentile = MutableStateFlow<UiState<GrowthPercentileOverview>>(UiState.Loading)
    val growthPercentile: StateFlow<UiState<GrowthPercentileOverview>> = _growthPercentile.asStateFlow()

    /** 健康与疫苗统计 */
    private val _healthStats = MutableStateFlow<UiState<HealthStats>>(UiState.Loading)
    val healthStats: StateFlow<UiState<HealthStats>> = _healthStats.asStateFlow()

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
            loadTrendOverview()
            loadStructureOverview()
            loadHealthStats()
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
        loadGrowthTrend()
        loadTrendOverview()
        loadStructureOverview()
        loadGrowthPercentile()
        loadHealthStats()
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
     * 加载生长趋势数据（体重/身高/头围最近两次对比）
     */
    private fun loadGrowthTrend() {
        val babyId = _currentBabyId.value
        if (babyId <= 0) {
            _growthTrend.value = UiState.Success(buildEmptyGrowthTrend())
            return
        }

        viewModelScope.launch {
            _growthTrend.value = UiState.Loading
            try {
                val trend = withContext(Dispatchers.IO) {
                    buildGrowthTrend(babyId)
                }
                _growthTrend.value = UiState.Success(trend)
            } catch (e: Exception) {
                _growthTrend.value = UiState.Error(e, e.message ?: "加载失败")
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
     * 加载周/月/年趋势概览
     */
    private fun loadTrendOverview() {
        val babyId = _currentBabyId.value
        val date = _selectedDate.value
        if (babyId <= 0) {
            _trendOverview.value = UiState.Success(TrendOverview(emptyList()))
            return
        }

        viewModelScope.launch {
            _trendOverview.value = UiState.Loading
            try {
                val overview = withContext(Dispatchers.IO) {
                    buildTrendOverview(babyId, date)
                }
                _trendOverview.value = UiState.Success(overview)
            } catch (e: Exception) {
                _trendOverview.value = UiState.Error(e, getLoadFailedText())
            }
        }
    }

    /**
     * 加载结构图数据（按选中月份统计）
     */
    private fun loadStructureOverview() {
        val babyId = _currentBabyId.value
        val date = _selectedDate.value
        if (babyId <= 0) {
            _structureOverview.value = UiState.Success(StructureOverview(emptyList()))
            return
        }

        viewModelScope.launch {
            _structureOverview.value = UiState.Loading
            try {
                val overview = withContext(Dispatchers.IO) {
                    buildStructureOverview(babyId, date)
                }
                _structureOverview.value = UiState.Success(overview)
            } catch (e: Exception) {
                _structureOverview.value = UiState.Error(e, getLoadFailedText())
            }
        }
    }

    /**
     * 加载生长百分位数据
     */
    private fun loadGrowthPercentile() {
        val babyId = _currentBabyId.value
        if (babyId <= 0) {
            _growthPercentile.value = UiState.Success(
                buildEmptyGrowthPercentile(
                    com.zero.common.R.string.statistics_growth_percentile_note_missing_info
                )
            )
            return
        }

        viewModelScope.launch {
            _growthPercentile.value = UiState.Loading
            try {
                val overview = withContext(Dispatchers.IO) {
                    buildGrowthPercentileOverview(babyId)
                }
                _growthPercentile.value = UiState.Success(overview)
            } catch (e: Exception) {
                _growthPercentile.value = UiState.Error(e, getLoadFailedText())
            }
        }
    }

    /**
     * 加载健康/疫苗统计
     */
    private fun loadHealthStats() {
        val babyId = _currentBabyId.value
        val date = _selectedDate.value
        if (babyId <= 0) {
            val empty = HealthStats(
                startDate = date,
                endDate = date,
                temperatureCount = 0,
                medicineCount = 0,
                doctorCount = 0,
                symptomCount = 0,
                vaccineCount = 0,
                lastVaccineTime = null
            )
            _healthStats.value = UiState.Success(empty)
            return
        }

        viewModelScope.launch {
            _healthStats.value = UiState.Loading
            try {
                val stats = withContext(Dispatchers.IO) {
                    buildHealthStats(babyId, date)
                }
                _healthStats.value = UiState.Success(stats)
            } catch (e: Exception) {
                _healthStats.value = UiState.Error(e, getLoadFailedText())
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

    /**
     * 构建空数据占位，保证单位随设置同步
     */
    private fun buildEmptyGrowthTrend(): GrowthTrend {
        return GrowthTrend(
            weight = GrowthTrendItem(
                latestValue = null,
                diffValue = null,
                unitLabelResId = UnitConfig.getWeightUnitLabelResId()
            ),
            height = GrowthTrendItem(
                latestValue = null,
                diffValue = null,
                unitLabelResId = UnitConfig.getHeightUnitLabelResId()
            ),
            head = GrowthTrendItem(
                latestValue = null,
                diffValue = null,
                unitLabelResId = UnitConfig.getHeightUnitLabelResId()
            )
        )
    }

    /**
     * 查询并计算生长趋势
     */
    private fun buildGrowthTrend(babyId: Int): GrowthTrend {
        val weightUnit = UnitConfig.getWeightUnit()
        val heightUnit = UnitConfig.getHeightUnit()

        val weightItem = buildGrowthItem(
            babyId = babyId,
            type = EventType.GROWTH_WEIGHT,
            unitLabelResId = UnitConfig.getWeightUnitLabelResId(),
            targetUnit = weightUnit,
            converter = UnitConverter::weightToDisplay
        )

        val heightItem = buildGrowthItem(
            babyId = babyId,
            type = EventType.GROWTH_HEIGHT,
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            converter = UnitConverter::heightToDisplay
        )

        val headItem = buildGrowthItem(
            babyId = babyId,
            type = EventType.GROWTH_HEAD,
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            converter = UnitConverter::heightToDisplay
        )

        return GrowthTrend(
            weight = weightItem,
            height = heightItem,
            head = headItem
        )
    }

    /**
     * 生成周/月/年趋势数据
     */
    private fun buildTrendOverview(babyId: Int, date: LocalDate): TrendOverview {
        val weekFields = WeekFields.of(Locale.getDefault())
        val weekStart = date.with(TemporalAdjusters.previousOrSame(weekFields.firstDayOfWeek))
        val weekEnd = weekStart.plusDays(6)

        val month = YearMonth.from(date)
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()

        val yearStart = LocalDate.of(date.year, 1, 1)
        val yearEnd = LocalDate.of(date.year, 12, 31)

        val summaries = listOf(
            buildPeriodSummary(babyId, TrendPeriod.WEEK, weekStart, weekEnd),
            buildPeriodSummary(babyId, TrendPeriod.MONTH, monthStart, monthEnd),
            buildPeriodSummary(babyId, TrendPeriod.YEAR, yearStart, yearEnd)
        )
        return TrendOverview(summaries)
    }

    /**
     * 统计指定时间范围内的趋势数据
     */
    private fun buildPeriodSummary(
        babyId: Int,
        period: TrendPeriod,
        startDate: LocalDate,
        endDate: LocalDate
    ): TrendPeriodSummary {
        val (startTime, endTime) = buildRangeMillis(startDate, endDate)
        val feedings = repository.getFeedingRecordsBetween(babyId, startTime, endTime)
        val sleeps = repository.getSleepRecordsBetween(babyId, startTime, endTime)
        val events = repository.getEventRecordsBetween(babyId, startTime, endTime)

        val feedingTotalMinutes = feedings.sumOf {
            TimeUnit.MILLISECONDS.toMinutes(it.feedingDuration).toInt()
        }
        val sleepTotalMinutes = sleeps.sumOf {
            TimeUnit.MILLISECONDS.toMinutes(it.sleepDuration).toInt()
        }

        var diaperCount = 0
        var otherEventCount = 0
        events.forEach { event ->
            if (EventType.isDiaper(event.type)) {
                diaperCount++
            } else {
                otherEventCount++
            }
        }

        return TrendPeriodSummary(
            period = period,
            startDate = startDate,
            endDate = endDate,
            feedingCount = feedings.size,
            feedingTotalMinutes = feedingTotalMinutes,
            sleepCount = sleeps.size,
            sleepTotalMinutes = sleepTotalMinutes,
            diaperCount = diaperCount,
            otherEventCount = otherEventCount
        )
    }

    /**
     * 构建结构图数据（默认按月）
     */
    private fun buildStructureOverview(babyId: Int, date: LocalDate): StructureOverview {
        val month = YearMonth.from(date)
        val startDate = month.atDay(1)
        val endDate = month.atEndOfMonth()
        val (startTime, endTime) = buildRangeMillis(startDate, endDate)
        val feedings = repository.getFeedingRecordsBetween(babyId, startTime, endTime)
        val events = repository.getEventRecordsBetween(babyId, startTime, endTime)

        val feedingSection = StructureSection(
            titleResId = com.zero.common.R.string.statistics_structure_feeding_title,
            items = buildFeedingStructureItems(feedings)
        )
        val diaperSection = StructureSection(
            titleResId = com.zero.common.R.string.statistics_structure_diaper_title,
            items = buildDiaperStructureItems(events)
        )
        val healthSection = StructureSection(
            titleResId = com.zero.common.R.string.statistics_structure_health_title,
            items = buildHealthStructureItems(events)
        )

        val sections = listOf(feedingSection, diaperSection, healthSection)
            .filter { it.items.isNotEmpty() }
        return StructureOverview(sections)
    }

    /**
     * 喂养结构拆解
     */
    private fun buildFeedingStructureItems(records: List<FeedingRecord>): List<StructureItem> {
        val counts = mutableMapOf<FeedingType, Int>()
        records.forEach { record ->
            val type = FeedingType.fromType(record.feedingType)
            counts[type] = (counts[type] ?: 0) + 1
        }
        return listOf(
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_breast,
                count = counts[FeedingType.BREAST] ?: 0,
                colorResId = com.zero.common.R.color.color_cover_1
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_formula,
                count = counts[FeedingType.FORMULA] ?: 0,
                colorResId = com.zero.common.R.color.color_cover_2
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_mixed,
                count = counts[FeedingType.MIXED] ?: 0,
                colorResId = com.zero.common.R.color.color_cover_3
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_solid,
                count = counts[FeedingType.SOLID_FOOD] ?: 0,
                colorResId = com.zero.common.R.color.color_cover_4
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_other,
                count = counts[FeedingType.OTHER] ?: 0,
                colorResId = com.zero.common.R.color.color_cover_5
            )
        ).filter { it.count > 0 }
    }

    /**
     * 排泄结构拆解
     */
    private fun buildDiaperStructureItems(records: List<EventRecord>): List<StructureItem> {
        val wet = records.count { it.type == EventType.DIAPER_WET }
        val dirty = records.count { it.type == EventType.DIAPER_DIRTY }
        val mixed = records.count { it.type == EventType.DIAPER_MIXED }
        val dry = records.count { it.type == EventType.DIAPER_DRY }

        return listOf(
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_wet,
                count = wet,
                colorResId = com.zero.common.R.color.color_cover_2
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_dirty,
                count = dirty,
                colorResId = com.zero.common.R.color.color_cover_3
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_mixed,
                count = mixed,
                colorResId = com.zero.common.R.color.color_cover_4
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_dry,
                count = dry,
                colorResId = com.zero.common.R.color.color_cover_5
            )
        ).filter { it.count > 0 }
    }

    /**
     * 健康结构拆解
     */
    private fun buildHealthStructureItems(records: List<EventRecord>): List<StructureItem> {
        val temperature = records.count { it.type == EventType.HEALTH_TEMPERATURE }
        val medicine = records.count { it.type == EventType.HEALTH_MEDICINE }
        val doctor = records.count { it.type == EventType.HEALTH_DOCTOR }
        val symptom = records.count { it.type == EventType.HEALTH_SYMPTOM }
        val vaccine = records.count { it.type == EventType.HEALTH_VACCINE }

        return listOf(
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_temperature,
                count = temperature,
                colorResId = com.zero.common.R.color.color_cover_1
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_medicine,
                count = medicine,
                colorResId = com.zero.common.R.color.color_cover_2
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_doctor,
                count = doctor,
                colorResId = com.zero.common.R.color.color_cover_3
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_symptom,
                count = symptom,
                colorResId = com.zero.common.R.color.color_cover_4
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_vaccine,
                count = vaccine,
                colorResId = com.zero.common.R.color.color_cover_5
            )
        ).filter { it.count > 0 }
    }

    /**
     * 构建生长百分位数据（基于 WHO 标准）
     */
    private fun buildGrowthPercentileOverview(babyId: Int): GrowthPercentileOverview {
        val baby = repository.getBabyInfoSync(babyId)
        val birthDate = baby?.birthDate ?: 0L
        val sex = parseWhoSex(baby?.gender)

        if (birthDate <= 0L || sex == WhoSex.UNKNOWN) {
            return buildEmptyGrowthPercentile(
                com.zero.common.R.string.statistics_growth_percentile_note_missing_info
            )
        }

        val weightUnit = UnitConfig.getWeightUnit()
        val heightUnit = UnitConfig.getHeightUnit()
        val weight = buildPercentileSeries(
            babyId = babyId,
            type = EventType.GROWTH_WEIGHT,
            labelResId = com.zero.common.R.string.statistics_growth_weight,
            unitLabelResId = UnitConfig.getWeightUnitLabelResId(),
            targetUnit = weightUnit,
            indicator = WhoIndicator.WEIGHT,
            sex = sex,
            birthDate = birthDate
        )
        val height = buildPercentileSeries(
            babyId = babyId,
            type = EventType.GROWTH_HEIGHT,
            labelResId = com.zero.common.R.string.statistics_growth_height,
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            indicator = WhoIndicator.LENGTH_HEIGHT,
            sex = sex,
            birthDate = birthDate
        )
        val head = buildPercentileSeries(
            babyId = babyId,
            type = EventType.GROWTH_HEAD,
            labelResId = com.zero.common.R.string.statistics_growth_head,
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            indicator = WhoIndicator.HEAD,
            sex = sex,
            birthDate = birthDate
        )

        return GrowthPercentileOverview(
            weight = weight,
            height = height,
            head = head,
            noteResId = com.zero.common.R.string.statistics_growth_percentile_note_who
        )
    }

    /**
     * 生成单项百分位序列
     */
    private fun buildPercentileSeries(
        babyId: Int,
        type: Int,
        labelResId: Int,
        unitLabelResId: Int,
        targetUnit: String,
        indicator: WhoIndicator,
        sex: WhoSex,
        birthDate: Long
    ): GrowthPercentileSeries {
        val records = repository.getEventRecordsByType(babyId, type).sortedBy { it.time }
        if (records.isEmpty()) {
            return GrowthPercentileSeries(
                labelResId = labelResId,
                unitLabelResId = unitLabelResId,
                latestValue = null,
                latestPercentile = null,
                points = emptyList()
            )
        }

        val points = mutableListOf<GrowthPercentilePoint>()
        var latestValue: Double? = null
        var latestPercentile: Int? = null

        records.forEachIndexed { index, record ->
            val data = GrowthData.fromJson(record.extraData) ?: return@forEachIndexed
            val ageDays = buildAgeDays(birthDate, record.time) ?: return@forEachIndexed
            // 以平均月天数换算，便于对接 WHO 月龄表
            val ageMonths = ageDays / 30.4375
            val standardValue = when (indicator) {
                WhoIndicator.WEIGHT -> convertWeightToKg(data.value, data.unit)
                WhoIndicator.LENGTH_HEIGHT -> convertLengthToCm(data.value, data.unit)
                WhoIndicator.HEAD -> convertLengthToCm(data.value, data.unit)
            }
            val percentile = whoStandardProvider.computePercentile(
                indicator = indicator,
                sex = sex,
                ageDays = ageDays,
                ageMonths = ageMonths,
                value = standardValue
            )
            val recordDate = java.time.Instant.ofEpochMilli(record.time)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            if (percentile != null) {
                points.add(GrowthPercentilePoint(recordDate, percentile))
            }
            if (index == records.lastIndex) {
                latestValue = toDisplayValue(standardValue, indicator, targetUnit)
                latestPercentile = percentile
            }
        }

        return GrowthPercentileSeries(
            labelResId = labelResId,
            unitLabelResId = unitLabelResId,
            latestValue = latestValue,
            latestPercentile = latestPercentile,
            points = points.takeLast(12)
        )
    }

    /**
     * 构建健康/疫苗统计（按选中月份）
     */
    private fun buildHealthStats(babyId: Int, date: LocalDate): HealthStats {
        val month = YearMonth.from(date)
        val startDate = month.atDay(1)
        val endDate = month.atEndOfMonth()
        val (startTime, endTime) = buildRangeMillis(startDate, endDate)
        val events = repository.getEventRecordsBetween(babyId, startTime, endTime)

        var temperatureCount = 0
        var medicineCount = 0
        var doctorCount = 0
        var symptomCount = 0
        var vaccineCount = 0
        var lastVaccineTime: Long? = null

        events.forEach { event ->
            when (event.type) {
                EventType.HEALTH_TEMPERATURE -> temperatureCount++
                EventType.HEALTH_MEDICINE -> medicineCount++
                EventType.HEALTH_DOCTOR -> doctorCount++
                EventType.HEALTH_SYMPTOM -> symptomCount++
                EventType.HEALTH_VACCINE -> {
                    vaccineCount++
                    val current = lastVaccineTime ?: 0L
                    if (event.time > current) {
                        lastVaccineTime = event.time
                    }
                }
            }
        }

        return HealthStats(
            startDate = startDate,
            endDate = endDate,
            temperatureCount = temperatureCount,
            medicineCount = medicineCount,
            doctorCount = doctorCount,
            symptomCount = symptomCount,
            vaccineCount = vaccineCount,
            lastVaccineTime = lastVaccineTime
        )
    }

    /**
     * 构建空的百分位数据
     */
    private fun buildEmptyGrowthPercentile(noteResId: Int): GrowthPercentileOverview {
        return GrowthPercentileOverview(
            weight = GrowthPercentileSeries(
                labelResId = com.zero.common.R.string.statistics_growth_weight,
                unitLabelResId = UnitConfig.getWeightUnitLabelResId(),
                latestValue = null,
                latestPercentile = null,
                points = emptyList()
            ),
            height = GrowthPercentileSeries(
                labelResId = com.zero.common.R.string.statistics_growth_height,
                unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
                latestValue = null,
                latestPercentile = null,
                points = emptyList()
            ),
            head = GrowthPercentileSeries(
                labelResId = com.zero.common.R.string.statistics_growth_head,
                unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
                latestValue = null,
                latestPercentile = null,
                points = emptyList()
            ),
            noteResId = noteResId
        )
    }

    /**
     * 解析性别字段（用于 WHO 标准）
     */
    private fun parseWhoSex(gender: String?): WhoSex {
        val value = gender?.trim()?.lowercase(Locale.getDefault()) ?: return WhoSex.UNKNOWN
        return when {
            value.contains("男") || value.contains("boy") || value.contains("male") -> WhoSex.BOY
            value.contains("女") || value.contains("girl") || value.contains("female") -> WhoSex.GIRL
            else -> WhoSex.UNKNOWN
        }
    }

    /**
     * 计算出生到记录的天数
     */
    private fun buildAgeDays(birthDateMillis: Long, recordTimeMillis: Long): Int? {
        if (birthDateMillis <= 0L) return null
        val birthDate = java.time.Instant.ofEpochMilli(birthDateMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val recordDate = java.time.Instant.ofEpochMilli(recordTimeMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(birthDate, recordDate)
        return if (days < 0) null else days.toInt()
    }

    /**
     * 体重单位换算为 kg
     */
    private fun convertWeightToKg(value: Double, unit: String): Double {
        return when (unit) {
            GrowthData.UNIT_G -> value / 1000.0
            GrowthData.UNIT_KG -> value
            else -> value
        }
    }

    /**
     * 长度/头围单位换算为 cm
     */
    private fun convertLengthToCm(value: Double, unit: String): Double {
        return when (unit) {
            GrowthData.UNIT_MM -> value / 10.0
            GrowthData.UNIT_CM -> value
            else -> value
        }
    }

    /**
     * 将标准单位转为当前展示单位
     */
    private fun toDisplayValue(value: Double, indicator: WhoIndicator, targetUnit: String): Double {
        return when (indicator) {
            WhoIndicator.WEIGHT -> UnitConverter.weightToDisplay(
                value,
                UnitConfig.WEIGHT_UNIT_KG,
                targetUnit
            )
            WhoIndicator.LENGTH_HEIGHT, WhoIndicator.HEAD -> UnitConverter.heightToDisplay(
                value,
                UnitConfig.HEIGHT_UNIT_CM,
                targetUnit
            )
        }
    }

    /**
     * 将日期范围转换为毫秒时间戳
     */
    private fun buildRangeMillis(startDate: LocalDate, endDate: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val startTime = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTime = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return startTime to endTime
    }

    private fun getLoadFailedText(): String {
        return getApplication<Application>().getString(com.zero.common.R.string.statistics_load_failed)
    }

    /**
     * 读取最近两条记录，换算成当前单位后计算差值
     */
    private fun buildGrowthItem(
        babyId: Int,
        type: Int,
        unitLabelResId: Int,
        targetUnit: String,
        converter: (Double, String, String) -> Double
    ): GrowthTrendItem {
        val records = repository.getLatestEventRecordsByType(babyId, type, 2)
        val dataList = records.mapNotNull { GrowthData.fromJson(it.extraData) }
        val latest = dataList.getOrNull(0)
        if (latest == null) {
            return GrowthTrendItem(latestValue = null, diffValue = null, unitLabelResId = unitLabelResId)
        }

        val latestValue = converter(latest.value, latest.unit, targetUnit)
        val previousValue = dataList.getOrNull(1)?.let {
            converter(it.value, it.unit, targetUnit)
        }
        val diffValue = previousValue?.let { latestValue - it }

        return GrowthTrendItem(
            latestValue = latestValue,
            diffValue = diffValue,
            unitLabelResId = unitLabelResId
        )
    }
}
