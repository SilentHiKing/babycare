package com.zero.babycare.statistics

import android.app.Application
import android.content.Context
import com.zero.common.util.LanguageManager
import com.zero.common.ext.getAppString
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babycare.statistics.mapper.StatisticsDateRange
import com.zero.babycare.statistics.mapper.StatisticsDurationCalculator
import com.zero.babycare.statistics.mapper.StatisticsGrowthHistory
import com.zero.babycare.statistics.mapper.StatisticsTimelineMapper
import com.zero.babycare.statistics.mapper.WhoSexParser
import com.zero.babycare.statistics.model.DayRecordSectionUiModel
import com.zero.babycare.statistics.model.CalendarBrowseState
import com.zero.babycare.statistics.model.DaySummary
import com.zero.babycare.statistics.model.GrowthPercentileOverview
import com.zero.babycare.statistics.model.GrowthPercentileSeries
import com.zero.babycare.statistics.model.GrowthTrend
import com.zero.babycare.statistics.model.GrowthTrendItem
import com.zero.babycare.statistics.model.HealthStats
import com.zero.babycare.statistics.model.InsightSectionUiModel
import com.zero.babycare.statistics.model.StatisticsUiEvent
import com.zero.babycare.statistics.model.StatisticsUiState
import com.zero.babycare.statistics.model.StatisticsClockContext
import com.zero.babycare.statistics.model.StructureItem
import com.zero.babycare.statistics.model.StructureOverview
import com.zero.babycare.statistics.model.StructureSection
import com.zero.babycare.statistics.model.SummaryMetricType
import com.zero.babycare.statistics.model.SummaryMetricUiModel
import com.zero.babycare.statistics.model.TimelineUiItem
import com.zero.babycare.statistics.model.TrendOverview
import com.zero.babycare.statistics.model.TrendPeriod
import com.zero.babycare.statistics.model.TrendPeriodSummary
import com.zero.babycare.statistics.standard.WhoGrowthStandardProvider
import com.zero.babycare.statistics.standard.WhoIndicator
import com.zero.babycare.statistics.standard.WhoSex
import com.zero.babydata.entity.BabyInfo
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.room.BabyRepository
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 统计页面 ViewModel
 */
class StatisticsViewModel @JvmOverloads constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    injectedRepository: BabyRepository? = null
) : AndroidViewModel(application) {

    // 默认仍使用应用数据库；隔离回归可通过同一查询实现验证提交后的派生状态。
    private val repository = injectedRepository ?: BabyRepository(application)
    private val whoStandardProvider by lazy { WhoGrowthStandardProvider(getApplication()) }

    // ==================== 状态 ====================

    /** 当前宝宝ID */
    private val _currentBabyId = MutableStateFlow(0)
    val currentBabyId: StateFlow<Int> = _currentBabyId.asStateFlow()

    /** 选中的日期只作为 ViewModel 内部驱动源，对外统一通过 uiState 暴露。 */
    private val _selectedDate = MutableStateFlow(readSavedDate("statistics_selected_date") ?: LocalDate.now())

    /** 统计页统一状态，后续 Fragment 只按它渲染整页。 */
    private val _uiState = MutableStateFlow(StatisticsUiState(
        selectedDate = _selectedDate.value,
        calendarBrowse = CalendarBrowseState.forDate(
            readSavedDate("statistics_calendar_anchor") ?: _selectedDate.value,
            savedStateHandle["statistics_calendar_month"] ?: false
        ),
        dayRecord = DayRecordSectionUiModel.empty(_selectedDate.value),
        insights = InsightSectionUiModel.empty(_selectedDate.value)
    ))
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    private var contentLoadJob: Job? = null
    private var calendarLoadJob: Job? = null
    private var requestedClockContext = _uiState.value.clockContext

    init {
        // 保存允许离页；返回统计后的首次查询可能早于提交完成，必须继续接收提交后的失效信号。
        viewModelScope.launch {
            BabyRecordChangeBus.changes.collect { changedBabyId ->
                val currentBabyId = _currentBabyId.value
                if (currentBabyId > 0 && (changedBabyId == currentBabyId ||
                        changedBabyId == BabyRecordChangeBus.ALL_BABIES_CHANGED)) {
                    loadData()
                }
            }
        }
    }

    // ==================== 公开方法 ====================

    /**
     * 设置当前宝宝ID并加载数据
     */
    fun setBabyId(babyId: Int) {
        if (_currentBabyId.value != babyId) {
            _currentBabyId.value = babyId
            _uiState.value = _uiState.value.copy(
                babyId = babyId,
                datesWithRecords = emptySet()
            )
            loadData()
        }
    }

    fun onEvent(event: StatisticsUiEvent) {
        when (event) {
            is StatisticsUiEvent.SelectDate -> selectDate(event.date)
            is StatisticsUiEvent.ChangeMonth -> onMonthChanged(event.yearMonth)
            StatisticsUiEvent.GoToday -> goToToday()
            StatisticsUiEvent.Refresh -> refreshData()
            is StatisticsUiEvent.OpenTimelineItem -> Unit
        }
    }

    /**
     * 选择日期
     */
    fun selectDate(date: LocalDate) {
        val browse = CalendarBrowseState.forDate(date, _uiState.value.calendarBrowse.monthMode)
        if (_selectedDate.value != date || _uiState.value.calendarBrowse != browse) {
            _selectedDate.value = date
            savedStateHandle["statistics_selected_date"] = date.toString()
            saveCalendarBrowse(browse)
            _uiState.value = _uiState.value.copy(
                selectedDate = date,
                calendarBrowse = browse,
                dayRecord = _uiState.value.dayRecord.copy(date = date),
                insights = _uiState.value.insights.copy(selectedDate = date)
            )
            loadData()
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

    /** 返回前台核对时区/日期；系统校时广播可强制刷新，但保留选中日和独立浏览位置。 */
    fun onClockChanged(force: Boolean = false) {
        if (force || requestedClockContext != StatisticsClockContext.now()) loadData()
    }

    /** 查询期间切换时区时丢弃旧结果，并由最新上下文接管整页加载。 */
    private fun acceptsClock(clock: StatisticsClockContext): Boolean {
        if (clock == StatisticsClockContext.now()) return true
        onClockChanged()
        return false
    }

    // ==================== 私有方法 ====================

    /**
     * 以宝宝和选中日期为一个输入快照加载整页。新输入会取消旧请求，避免快速切换后
     * 旧协程覆盖新页面，也让 Loading/Error 明确代表整组核心内容。
     */
    private fun loadData() {
        val babyId = _currentBabyId.value
        val date = _selectedDate.value
        val clock = StatisticsClockContext.now()
        val zone = clock.zone
        requestedClockContext = clock
        contentLoadJob?.cancel()

        if (babyId <= 0) {
            _uiState.value = _uiState.value.copy(
                babyId = babyId,
                selectedDate = date,
                dayRecord = DayRecordSectionUiModel.empty(date),
                insights = InsightSectionUiModel.empty(date),
                datesWithRecords = emptySet(),
                isLoading = false,
                errorMessage = null,
                clockContext = clock
            )
            calendarLoadJob?.cancel()
            return
        }

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            errorMessage = null,
            datesWithRecords = if (_uiState.value.clockContext == clock) _uiState.value.datesWithRecords else emptySet()
        )
        contentLoadJob = viewModelScope.launch {
            try {
                val content = coroutineScope {
                    // Deferred 仅属于本次请求；各区块共享输入，切换宝宝/日期时一并取消，不复用过期数据。
                    val monthRange = StatisticsDateRange.month(date, zone)
                    val input = StatisticsLoadInputs(
                        babyInfo = async(Dispatchers.IO) { repository.getBabyInfo(babyId) },
                        growth = listOf(EventType.GROWTH_WEIGHT, EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD)
                            .associateWith { type ->
                                async(Dispatchers.IO) {
                                    StatisticsGrowthHistory.create(repository.getEventRecordsByType(babyId, type), date, zone)
                                }
                            },
                        monthFeedings = async(Dispatchers.IO) {
                            repository.getFeedingRecordsBetween(babyId, monthRange.startMillis, monthRange.endMillis)
                        },
                        monthEvents = async(Dispatchers.IO) {
                            repository.getEventRecordsBetween(babyId, monthRange.startMillis, monthRange.endMillis)
                        }
                    )
                    val dayRecord = async(Dispatchers.IO) { buildDayRecordSection(babyId, date, input.babyInfo.await(), zone) }
                    val growth = async(Dispatchers.IO) { buildGrowthTrend(input) }
                    val trend = async(Dispatchers.IO) { buildTrendOverview(babyId, date, input, zone) }
                    val structure = async(Dispatchers.IO) {
                        buildStructureOverview(input.monthFeedings.await(), input.monthEvents.await())
                    }
                    val percentile = async(Dispatchers.IO) { buildGrowthPercentileOverview(input) }
                    val health = async(Dispatchers.IO) { buildHealthStats(date, input.monthEvents.await(), zone) }

                    LoadedStatisticsContent(
                        dayRecord = dayRecord.await(),
                        insights = InsightSectionUiModel(
                            selectedDate = date,
                            trend = trend.await(),
                            structure = structure.await(),
                            growth = growth.await(),
                            percentile = percentile.await(),
                            health = health.await()
                        )
                    )
                }

                currentCoroutineContext().ensureActive()
                if (_selectedDate.value == date && _currentBabyId.value == babyId && acceptsClock(clock)) {
                    _uiState.value = _uiState.value.copy(
                        babyId = babyId,
                        selectedDate = date,
                        dayRecord = content.dayRecord,
                        insights = content.insights,
                        isLoading = false,
                        errorMessage = null,
                        clockContext = clock
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (_selectedDate.value == date && _currentBabyId.value == babyId && acceptsClock(clock)) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = getLoadFailedText()
                    )
                }
            }
        }
        loadDatesWithRecords(_uiState.value.calendarBrowse, clock)
    }

    /**
     * 月份标记独立于选中日内容；同样只允许最新月份请求写回。
     */
    private fun loadDatesWithRecords(browse: CalendarBrowseState, clock: StatisticsClockContext = requestedClockContext) {
        val babyId = _currentBabyId.value
        val zone = clock.zone
        calendarLoadJob?.cancel()

        if (babyId <= 0) {
            _uiState.value = _uiState.value.copy(datesWithRecords = emptySet())
            return
        }

        calendarLoadJob = viewModelScope.launch {
            try {
                // 月视图也补齐首尾周；灰色日期仍可点击，记录点必须覆盖整个可见日期范围。
                val month = YearMonth.from(browse.anchorDate)
                val firstDate = if (browse.monthMode) StatisticsDateRange.week(month.atDay(1), zone).startDate
                    else browse.anchorDate
                val lastDate = if (browse.monthMode) StatisticsDateRange.week(month.atEndOfMonth(), zone).endDate
                    else browse.anchorDate.plusDays(6)
                val dates = withContext(Dispatchers.IO) {
                    buildSet {
                        var queryMonth = YearMonth.from(firstDate)
                        val lastMonth = YearMonth.from(lastDate)
                        while (queryMonth <= lastMonth) {
                            addAll(repository.getDatesWithRecords(babyId, queryMonth, zone).filter { it in firstDate..lastDate })
                            queryMonth = queryMonth.plusMonths(1)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (_currentBabyId.value == babyId && _uiState.value.calendarBrowse == browse && acceptsClock(clock)) {
                    _uiState.value = _uiState.value.copy(datesWithRecords = dates)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (_currentBabyId.value == babyId && _uiState.value.calendarBrowse == browse && acceptsClock(clock)) {
                    _uiState.value = _uiState.value.copy(datesWithRecords = emptySet())
                }
            }
        }
    }

    /**
     * 当月份变化时加载新月份的记录日期
     */
    fun onMonthChanged(yearMonth: YearMonth) {
        onCalendarBrowsed(CalendarBrowseState(monthMode = true, anchorDate = yearMonth.atDay(1)))
    }

    /** 浏览状态只改变日历，统计内容继续归属 selectedDate；进程恢复也保留两者的区别。 */
    fun onCalendarBrowsed(browse: CalendarBrowseState) {
        if (_uiState.value.calendarBrowse == browse) return
        saveCalendarBrowse(browse)
        _uiState.value = _uiState.value.copy(calendarBrowse = browse, datesWithRecords = emptySet())
        loadDatesWithRecords(browse)
    }

    private fun saveCalendarBrowse(browse: CalendarBrowseState) {
        savedStateHandle["statistics_calendar_anchor"] = browse.anchorDate.toString()
        savedStateHandle["statistics_calendar_month"] = browse.monthMode
    }

    private fun readSavedDate(key: String): LocalDate? = savedStateHandle.get<String>(key)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }

    private suspend fun buildDayRecordSection(
        babyId: Int,
        date: LocalDate,
        babyInfo: BabyInfo?,
        zone: ZoneId
    ): DayRecordSectionUiModel {
        val summaryData = repository.getDaySummary(babyId, date, zone)
        val timelineRecords = repository.getTimelineRecords(babyId, date, zone)
        val summary = DaySummary(
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
        val mapper = buildTimelineMapper(zone)
        val context = currentCoroutineContext()
        val timelineItems = timelineRecords.map { record ->
            context.ensureActive()
            when (record) {
                is BabyRepository.TimelineRecord.Feeding -> mapper.mapFeeding(record.record)
                is BabyRepository.TimelineRecord.Sleep -> mapper.mapSleep(record.record)
                is BabyRepository.TimelineRecord.Event -> mapper.mapEvent(record.record)
            }
        }
        return buildDayRecordSection(
            date = date,
            babyInfo = babyInfo,
            summary = summary,
            timelineItems = timelineItems,
            zone = zone
        )
    }

    private data class LoadedStatisticsContent(
        val dayRecord: DayRecordSectionUiModel,
        val insights: InsightSectionUiModel
    )

    private data class StatisticsLoadInputs(
        val babyInfo: Deferred<BabyInfo?>,
        val growth: Map<Int, Deferred<StatisticsGrowthHistory>>,
        val monthFeedings: Deferred<List<FeedingRecord>>,
        val monthEvents: Deferred<List<EventRecord>>
    )

    /**
     * 查询并计算生长趋势
     */
    private suspend fun buildGrowthTrend(input: StatisticsLoadInputs): GrowthTrend {
        val weightUnit = UnitConfig.getWeightUnit()
        val heightUnit = UnitConfig.getHeightUnit()

        val weightItem = buildGrowthItem(
            history = input.growth.getValue(EventType.GROWTH_WEIGHT).await(),
            unitLabelResId = UnitConfig.getWeightUnitLabelResId(),
            targetUnit = weightUnit,
            converter = UnitConverter::weightToDisplay
        )

        val heightItem = buildGrowthItem(
            history = input.growth.getValue(EventType.GROWTH_HEIGHT).await(),
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            converter = UnitConverter::heightToDisplay
        )

        val headItem = buildGrowthItem(
            history = input.growth.getValue(EventType.GROWTH_HEAD).await(),
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
    private suspend fun buildTrendOverview(babyId: Int, date: LocalDate, input: StatisticsLoadInputs, zone: ZoneId): TrendOverview {
        val weekRange = StatisticsDateRange.week(date, zone)
        val monthRange = StatisticsDateRange.month(date, zone)
        val yearRange = StatisticsDateRange.year(date, zone)

        val summaries = listOf(
            buildPeriodSummary(babyId, TrendPeriod.WEEK, weekRange),
            buildPeriodSummary(babyId, TrendPeriod.MONTH, monthRange, input),
            buildPeriodSummary(babyId, TrendPeriod.YEAR, yearRange)
        )
        return TrendOverview(summaries)
    }

    /**
     * 统计指定时间范围内的趋势数据
     */
    private suspend fun buildPeriodSummary(
        babyId: Int,
        period: TrendPeriod,
        range: StatisticsDateRange.Range,
        sharedMonth: StatisticsLoadInputs? = null
    ): TrendPeriodSummary {
        val feedings = sharedMonth?.monthFeedings?.await()
            ?: repository.getFeedingRecordsBetween(babyId, range.startMillis, range.endMillis)
        val sleeps = repository.getSleepRecordsBetween(babyId, range.startMillis, range.endMillis)
        val feedingOverlaps = repository.getFeedingRecordsIntersectingRange(babyId, range.startMillis, range.endMillis)
        val sleepOverlaps = repository.getSleepRecordsIntersectingRange(babyId, range.startMillis, range.endMillis)
        val events = sharedMonth?.monthEvents?.await()
            ?: repository.getEventRecordsBetween(babyId, range.startMillis, range.endMillis)
        val context = currentCoroutineContext()

        // 次数仍按记录开始时间归属周期；时长按重叠部分归属周期，避免跨边界记录重复放大。
        val feedingTotalMinutes = StatisticsDurationCalculator.toWholeMinutes(
            StatisticsDurationCalculator.feedingDurationMillis(
                records = feedingOverlaps,
                rangeStart = range.startMillis,
                rangeEnd = range.endMillis,
                checkCancellation = { context.ensureActive() }
            )
        )
        val sleepTotalMinutes = StatisticsDurationCalculator.toWholeMinutes(
            StatisticsDurationCalculator.sleepDurationMillis(
                records = sleepOverlaps,
                rangeStart = range.startMillis,
                rangeEnd = range.endMillis,
                checkCancellation = { context.ensureActive() }
            )
        )

        var diaperCount = 0
        var otherEventCount = 0
        events.forEach { event ->
            context.ensureActive()
            if (EventType.isDiaper(event.type)) {
                diaperCount++
            } else {
                otherEventCount++
            }
        }

        return TrendPeriodSummary(
            period = period,
            startDate = range.startDate,
            endDate = range.endDate,
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
    private suspend fun buildStructureOverview(feedings: List<FeedingRecord>, events: List<EventRecord>): StructureOverview {
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
    private suspend fun buildFeedingStructureItems(records: List<FeedingRecord>): List<StructureItem> {
        val counts = mutableMapOf<FeedingType, Int>()
        val context = currentCoroutineContext()
        records.forEach { record ->
            context.ensureActive()
            val type = FeedingType.fromType(record.feedingType)
            counts[type] = (counts[type] ?: 0) + 1
        }
        // 结构图使用业务语义色，避免装饰色导致不同统计模块的含义漂移。
        return listOf(
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_breast,
                count = counts[FeedingType.BREAST] ?: 0,
                colorResId = com.zero.common.R.color.feeding_primary
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_formula,
                count = counts[FeedingType.FORMULA] ?: 0,
                colorResId = com.zero.common.R.color.event_care
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_mixed,
                count = counts[FeedingType.MIXED] ?: 0,
                colorResId = com.zero.common.R.color.sleep_primary
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_solid,
                count = counts[FeedingType.SOLID_FOOD] ?: 0,
                colorResId = com.zero.common.R.color.event_growth
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.feeding_type_other,
                count = counts[FeedingType.OTHER] ?: 0,
                colorResId = com.zero.common.R.color.event_other
            )
        ).filter { it.count > 0 }
    }

    /**
     * 排泄结构拆解
     */
    private suspend fun buildDiaperStructureItems(records: List<EventRecord>): List<StructureItem> {
        val context = currentCoroutineContext()
        val wet = records.count { context.ensureActive(); it.type == EventType.DIAPER_WET }
        val dirty = records.count { context.ensureActive(); it.type == EventType.DIAPER_DIRTY }
        val mixed = records.count { context.ensureActive(); it.type == EventType.DIAPER_MIXED }
        val dry = records.count { context.ensureActive(); it.type == EventType.DIAPER_DRY }

        // 排泄结构保留可区分颜色，但仍从业务语义色中选取，保证图例含义稳定。
        return listOf(
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_wet,
                count = wet,
                colorResId = com.zero.common.R.color.event_care
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_dirty,
                count = dirty,
                colorResId = com.zero.common.R.color.event_diaper
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_mixed,
                count = mixed,
                colorResId = com.zero.common.R.color.event_milestone
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_diaper_dry,
                count = dry,
                colorResId = com.zero.common.R.color.event_other
            )
        ).filter { it.count > 0 }
    }

    /**
     * 健康结构拆解
     */
    private suspend fun buildHealthStructureItems(records: List<EventRecord>): List<StructureItem> {
        val context = currentCoroutineContext()
        val temperature = records.count { context.ensureActive(); it.type == EventType.HEALTH_TEMPERATURE }
        val medicine = records.count { context.ensureActive(); it.type == EventType.HEALTH_MEDICINE }
        val doctor = records.count { context.ensureActive(); it.type == EventType.HEALTH_DOCTOR }
        val symptom = records.count { context.ensureActive(); it.type == EventType.HEALTH_SYMPTOM }
        val vaccine = records.count { context.ensureActive(); it.type == EventType.HEALTH_VACCINE }

        // 健康结构使用健康、照护、成长等语义色，便于与其他统计卡片形成一致认知。
        return listOf(
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_temperature,
                count = temperature,
                colorResId = com.zero.common.R.color.event_health
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_medicine,
                count = medicine,
                colorResId = com.zero.common.R.color.sleep_primary
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_doctor,
                count = doctor,
                colorResId = com.zero.common.R.color.event_care
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_symptom,
                count = symptom,
                colorResId = com.zero.common.R.color.event_milestone
            ),
            StructureItem(
                labelResId = com.zero.common.R.string.event_health_vaccine,
                count = vaccine,
                colorResId = com.zero.common.R.color.event_growth
            )
        ).filter { it.count > 0 }
    }

    /**
     * 构建生长百分位数据（基于 WHO 标准）
     */
    private suspend fun buildGrowthPercentileOverview(input: StatisticsLoadInputs): GrowthPercentileOverview {
        val baby = input.babyInfo.await()
        val birthDate = baby?.birthDate ?: 0L
        val sex = WhoSexParser.parse(baby?.gender)

        if (birthDate <= 0L || sex == WhoSex.UNKNOWN) {
            return buildEmptyGrowthPercentile(
                com.zero.common.R.string.statistics_growth_percentile_note_missing_info
            )
        }

        val weightUnit = UnitConfig.getWeightUnit()
        val heightUnit = UnitConfig.getHeightUnit()
        val weight = buildPercentileSeries(
            history = input.growth.getValue(EventType.GROWTH_WEIGHT).await(),
            labelResId = com.zero.common.R.string.statistics_growth_weight,
            unitLabelResId = UnitConfig.getWeightUnitLabelResId(),
            targetUnit = weightUnit,
            indicator = WhoIndicator.WEIGHT,
            sex = sex,
            birthDate = birthDate
        )
        val height = buildPercentileSeries(
            history = input.growth.getValue(EventType.GROWTH_HEIGHT).await(),
            labelResId = com.zero.common.R.string.statistics_growth_height,
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            indicator = WhoIndicator.LENGTH_HEIGHT,
            sex = sex,
            birthDate = birthDate
        )
        val head = buildPercentileSeries(
            history = input.growth.getValue(EventType.GROWTH_HEAD).await(),
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
    private suspend fun buildPercentileSeries(
        history: StatisticsGrowthHistory,
        labelResId: Int,
        unitLabelResId: Int,
        targetUnit: String,
        indicator: WhoIndicator,
        sex: WhoSex,
        birthDate: Long
    ): GrowthPercentileSeries {
        val recent = history.recentPercentiles(
            birthDateMillis = birthDate,
            standardValue = { data ->
                when (indicator) {
                    WhoIndicator.WEIGHT -> UnitConverter.weightToDisplay(
                        data.value, data.unit, UnitConfig.WEIGHT_UNIT_KG
                    )
                    WhoIndicator.LENGTH_HEIGHT, WhoIndicator.HEAD -> UnitConverter.heightToDisplay(
                        data.value, data.unit, UnitConfig.HEIGHT_UNIT_CM
                    )
                }
            },
            percentile = { ageDays, value ->
                whoStandardProvider.computePercentile(
                    indicator = indicator,
                    sex = sex,
                    ageDays = ageDays,
                    ageMonths = ageDays / 30.4375,
                    value = value
                )
            },
            displayValue = { toDisplayValue(it, indicator, targetUnit) }
        )

        return GrowthPercentileSeries(
            labelResId = labelResId,
            unitLabelResId = unitLabelResId,
            latestValue = recent.latestValue,
            latestPercentile = recent.latestPercentile,
            points = recent.points
        )
    }

    /**
     * 构建健康/疫苗统计（按选中月份）
     */
    private suspend fun buildHealthStats(date: LocalDate, events: List<EventRecord>, zone: ZoneId): HealthStats {
        val range = StatisticsDateRange.month(date, zone)

        var temperatureCount = 0
        var medicineCount = 0
        var doctorCount = 0
        var symptomCount = 0
        var vaccineCount = 0
        var lastVaccineTime: Long? = null
        val context = currentCoroutineContext()

        events.forEach { event ->
            context.ensureActive()
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
            startDate = range.startDate,
            endDate = range.endDate,
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

    private fun getLoadFailedText(): String {
        return getApplication<Application>().getAppString(com.zero.common.R.string.data_load_failed)
    }

    private fun buildDayRecordSection(
        date: LocalDate,
        babyInfo: BabyInfo?,
        summary: DaySummary,
        timelineItems: List<TimelineUiItem>,
        zone: ZoneId
    ): DayRecordSectionUiModel {
        val babyAgeText = babyInfo?.let { buildBabyAgeText(it, date, zone) }
        return DayRecordSectionUiModel(
            date = date,
            babyAgeText = babyAgeText,
            showBirthHint = babyAgeText.isNullOrBlank(),
            recordCountText = getApplication<Application>().getAppString(
                com.zero.common.R.string.statistics_day_record_count_format,
                timelineItems.size
            ),
            summaryMetrics = buildSummaryMetrics(summary),
            timelineItems = timelineItems,
            isEmpty = timelineItems.isEmpty()
        )
    }

    private fun buildBabyAgeText(babyInfo: BabyInfo, selectedDate: LocalDate, zone: ZoneId): String? {
        if (babyInfo.birthDate <= 0) {
            return null
        }
        val birthDate = Instant.ofEpochMilli(babyInfo.birthDate)
            .atZone(zone)
            .toLocalDate()
        val days = ChronoUnit.DAYS.between(birthDate, selectedDate)
        return if (days < 0) {
            getApplication<Application>().getAppString(com.zero.common.R.string.baby_not_born_yet_playful)
        } else {
            getApplication<Application>().getAppString(com.zero.common.R.string.days_born, days.toInt())
        }
    }

    private fun buildSummaryMetrics(summary: DaySummary): List<SummaryMetricUiModel> {
        val app = LanguageManager.localizedContext(getApplication<Application>())
        return listOf(
            SummaryMetricUiModel(
                type = SummaryMetricType.FEEDING,
                titleResId = com.zero.common.R.string.feeding,
                primaryText = app.getString(com.zero.common.R.string.times_count_format, summary.feedingCount),
                secondaryText = formatSummaryDuration(app, summary.feedingTotalMinutes),
                iconResId = com.zero.common.R.drawable.ic_feeding,
                colorResId = com.zero.common.R.color.feeding_primary,
                surfaceColorResId = com.zero.common.R.color.feeding_bg
            ),
            SummaryMetricUiModel(
                type = SummaryMetricType.SLEEP,
                titleResId = com.zero.common.R.string.sleeping,
                primaryText = app.getString(com.zero.common.R.string.times_count_format, summary.sleepCount),
                secondaryText = formatSummaryDuration(app, summary.sleepTotalMinutes),
                iconResId = com.zero.common.R.drawable.ic_sleep,
                colorResId = com.zero.common.R.color.sleep_primary,
                surfaceColorResId = com.zero.common.R.color.sleep_bg
            ),
            SummaryMetricUiModel(
                type = SummaryMetricType.DIAPER,
                titleResId = com.zero.common.R.string.diaper,
                primaryText = app.getString(com.zero.common.R.string.times_count_format, summary.totalDiaperCount),
                secondaryText = formatDiaperDetail(app, summary).ifBlank {
                    app.getString(com.zero.common.R.string.statistics_growth_no_record)
                },
                iconResId = com.zero.common.R.drawable.ic_event_diaper,
                colorResId = com.zero.common.R.color.event_diaper,
                surfaceColorResId = com.zero.common.R.color.event_diaper_light
            ),
            SummaryMetricUiModel(
                type = SummaryMetricType.OTHER,
                titleResId = com.zero.common.R.string.other,
                primaryText = app.getString(com.zero.common.R.string.times_count_format, summary.otherEventCount),
                secondaryText = app.getString(com.zero.common.R.string.statistics_summary_other_scope),
                iconResId = com.zero.common.R.drawable.ic_event_other,
                colorResId = com.zero.common.R.color.event_other,
                surfaceColorResId = com.zero.common.R.color.event_other_light
            )
        )
    }

    /**
     * 统计摘要属于 UI 文案，放在 ViewModel 统一组装，避免数据模型直接拼接中文。
     */
    private fun formatSummaryDuration(app: Context, totalMinutes: Int): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> app.getString(
                com.zero.common.R.string.summary_duration_hours_minutes_compact,
                hours,
                minutes
            )
            hours > 0 -> app.getString(com.zero.common.R.string.summary_duration_hours_compact, hours)
            minutes > 0 -> app.getString(com.zero.common.R.string.summary_duration_minutes_compact, minutes)
            else -> app.getString(com.zero.common.R.string.summary_duration_zero_compact)
        }
    }

    /**
     * 尿布详情按存在的分类组合，分类名称来自资源，连接符只承担结构表达。
     */
    private fun formatDiaperDetail(app: Context, summary: DaySummary): String {
        val parts = mutableListOf<String>()
        if (summary.diaperWetCount > 0) {
            parts.add(app.getString(com.zero.common.R.string.summary_diaper_wet_count_short, summary.diaperWetCount))
        }
        if (summary.diaperDirtyCount > 0) {
            parts.add(app.getString(com.zero.common.R.string.summary_diaper_dirty_count_short, summary.diaperDirtyCount))
        }
        if (summary.diaperMixedCount > 0) {
            parts.add(app.getString(com.zero.common.R.string.summary_diaper_mixed_count_short, summary.diaperMixedCount))
        }
        return if (parts.isEmpty()) "" else "(${parts.joinToString("/")})"
    }

    private fun buildTimelineMapper(zone: ZoneId): StatisticsTimelineMapper {
        val app = LanguageManager.localizedContext(getApplication<Application>())
        return StatisticsTimelineMapper(
            strings = object : StatisticsTimelineMapper.Strings {
                override fun get(resId: Int): String {
                    return app.getString(resId)
                }

                override fun format(resId: Int, vararg args: Any): String {
                    return app.getString(resId, *args)
                }
            },
            zone = zone
        )
    }

    /**
     * 读取最近两条记录，换算成当前单位后计算差值
     */
    private suspend fun buildGrowthItem(
        history: StatisticsGrowthHistory,
        unitLabelResId: Int,
        targetUnit: String,
        converter: (Double, String, String) -> Double
    ): GrowthTrendItem {
        val dataList = history.latestValid().map { it.data }
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
