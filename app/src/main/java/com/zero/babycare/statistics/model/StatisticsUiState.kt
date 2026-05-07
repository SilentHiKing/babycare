package com.zero.babycare.statistics.model

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import java.time.LocalDate
import java.time.YearMonth

/**
 * 统计页整页状态。selectedDate 是唯一主上下文，日记录区和洞察区都从它派生。
 */
data class StatisticsUiState(
    val babyId: Int = 0,
    val selectedDate: LocalDate = LocalDate.now(),
    val datesWithRecords: Set<LocalDate> = emptySet(),
    val dayRecord: DayRecordSectionUiModel = DayRecordSectionUiModel.empty(LocalDate.now()),
    val insights: InsightSectionUiModel = InsightSectionUiModel.empty(LocalDate.now()),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * 用户动作入口，Fragment 只转发事件，不承载业务判断。
 */
sealed interface StatisticsUiEvent {
    data class SelectDate(val date: LocalDate) : StatisticsUiEvent
    data class ChangeMonth(val yearMonth: YearMonth) : StatisticsUiEvent
    data object GoToday : StatisticsUiEvent
    data object Refresh : StatisticsUiEvent
    data class OpenTimelineItem(val target: TimelineEditTarget) : StatisticsUiEvent
}

/**
 * 选中日记录区：日期上下文、摘要指标、时间轴和空态必须连续展示。
 */
data class DayRecordSectionUiModel(
    val date: LocalDate,
    val babyAgeText: String?,
    val showBirthHint: Boolean,
    val recordCountText: String,
    val summaryMetrics: List<SummaryMetricUiModel>,
    val timelineItems: List<TimelineUiItem>,
    val isEmpty: Boolean
) {
    companion object {
        fun empty(date: LocalDate): DayRecordSectionUiModel {
            return DayRecordSectionUiModel(
                date = date,
                babyAgeText = null,
                showBirthHint = true,
                recordCountText = "",
                summaryMetrics = emptyList(),
                timelineItems = emptyList(),
                isEmpty = true
            )
        }
    }
}

data class SummaryMetricUiModel(
    val type: SummaryMetricType,
    @StringRes val titleResId: Int,
    val primaryText: String,
    val secondaryText: String,
    @DrawableRes val iconResId: Int,
    @ColorRes val colorResId: Int,
    @ColorRes val surfaceColorResId: Int
)

enum class SummaryMetricType {
    FEEDING,
    SLEEP,
    DIAPER,
    OTHER
}

data class TimelineUiItem(
    val stableId: Long,
    val timeText: String,
    val titleText: String,
    val detailText: String,
    val endTimeText: String? = null,
    val noteText: String?,
    @DrawableRes val iconResId: Int,
    @ColorRes val colorResId: Int,
    @ColorRes val surfaceColorResId: Int,
    val editTarget: TimelineEditTarget
)

sealed interface TimelineEditTarget {
    data class Feeding(val recordId: Int) : TimelineEditTarget
    data class Sleep(val recordId: Int) : TimelineEditTarget
    data class Event(val recordId: Int) : TimelineEditTarget
}

/**
 * 洞察区保留现有统计模型，但明确它们都由 selectedDate 派生。
 */
data class InsightSectionUiModel(
    val selectedDate: LocalDate,
    val trend: TrendOverview,
    val structure: StructureOverview,
    val growth: GrowthTrend,
    val percentile: GrowthPercentileOverview?,
    val health: HealthStats
) {
    companion object {
        fun empty(date: LocalDate): InsightSectionUiModel {
            return InsightSectionUiModel(
                selectedDate = date,
                trend = TrendOverview(emptyList()),
                structure = StructureOverview(emptyList()),
                growth = GrowthTrend.empty(),
                percentile = null,
                health = HealthStats(
                    startDate = date,
                    endDate = date,
                    temperatureCount = 0,
                    medicineCount = 0,
                    doctorCount = 0,
                    symptomCount = 0,
                    vaccineCount = 0,
                    lastVaccineTime = null
                )
            )
        }
    }
}
