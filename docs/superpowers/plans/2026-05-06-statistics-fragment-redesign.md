# StatisticsFragment Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild `StatisticsFragment` so the selected day is the primary page context, with date, baby age, day summary, and timeline shown as one section before period-based insights.

**Architecture:** Add focused UI models and mappers around the existing statistics feature, then make `StatisticsViewModel` publish a single page state driven by `selectedDate`. Keep Fragment as a renderer/event forwarder, and keep adapters limited to binding prepared UI models.

**Tech Stack:** Android View/XML, Kotlin, ViewBinding/DataBinding, `baby_recyclerview`/BRVAH adapters, AndroidX lifecycle `viewModelScope`, Room through `BabyRepository`, JUnit 4.

---

## Confirmed Spec

Read before implementation:

- `docs/superpowers/specs/2026-05-03-statistics-fragment-redesign.md`
- `DESIGN.md`
- `docs/ui-guidelines.md`
- `AGENTS.md`

The implementation must keep:

- `selectedDate` as the primary page state.
- Day section order: calendar, baby age, 2x2 summary, day timeline or empty state.
- Insight section order: trend, structure, growth, percentile, health.
- Growth trend and WHO percentile limited to records at or before the selected day end.
- No hardcoded user-visible strings.
- No page-level hardcoded colors.
- No ordinary View/card/list shadows.

## File Structure

Create:

- `app/src/main/java/com/zero/babycare/statistics/model/StatisticsUiState.kt`
  - Owns page-level UI state, section UI models, summary metric UI models, timeline UI models, and edit targets.
- `app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsDateRange.kt`
  - Pure date range helper for day end, selected week, selected month, and selected year.
- `app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsTimelineMapper.kt`
  - Maps repository timeline records into `TimelineUiItem`; moves title/detail/icon/color decisions out of `TimelineAdapter`.
- `app/src/test/java/com/zero/babycare/statistics/StatisticsDateRangeTest.kt`
  - Tests period ranges and selected-day end boundaries.
- `app/src/test/java/com/zero/babycare/statistics/StatisticsGrowthCutoffTest.kt`
  - Tests cutoff filtering for growth records.
- `app/src/test/java/com/zero/babycare/statistics/StatisticsTimelineMapperTest.kt`
  - Tests prepared timeline titles, details, colors, and edit targets for representative record types.

Modify:

- `app/src/main/java/com/zero/babycare/statistics/StatisticsViewModel.kt`
  - Build and expose `StatisticsUiState`; use date range helper; apply growth cutoff; expose event handlers.
- `app/src/main/java/com/zero/babycare/statistics/StatisticsFragment.kt`
  - Collect the new state, render sections, forward UI events, and keep navigation only in Fragment.
- `app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsSummaryAdapter.kt`
  - Bind `DayRecordSectionUiModel` or summary metric UI models rather than raw `DaySummary`.
- `app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsBabyAgeAdapter.kt`
  - Bind day-section age text and record count together.
- `app/src/main/java/com/zero/babycare/statistics/adapter/TimelineAdapter.kt`
  - Bind `TimelineUiItem` only; remove event parsing/detail business logic.
- `app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsTrendAdapter.kt`
  - Keep existing `TrendOverview` binding, add selected-date insight heading/range if the layout exposes it.
- `app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsGrowthAdapter.kt`
  - Bind cutoff-aware `GrowthTrend`.
- `app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsGrowthPercentileAdapter.kt`
  - Bind cutoff-aware `GrowthPercentileOverview`.
- `app/src/main/res/layout/item_statistics_summary.xml`
  - Convert summary from 4-column row to 2x2 indicators.
- `app/src/main/res/layout/item_statistics_baby_age.xml`
  - Add selected-day record count text and keep birth-date hint behavior.
- `app/src/main/res/layout/item_statistics_empty.xml`
  - Make empty state visually belong to the selected-day section.
- `app/src/main/res/layout/item_timeline_feeding.xml`
- `app/src/main/res/layout/item_timeline_sleep.xml`
- `app/src/main/res/layout/item_timeline_event.xml`
  - Keep timeline visuals but remove assumptions that require raw record-specific binding logic.
- `app/src/main/res/layout/item_statistics_trend.xml`
- `app/src/main/res/layout/item_statistics_structure.xml`
- `app/src/main/res/layout/item_statistics_growth.xml`
- `app/src/main/res/layout/item_statistics_growth_percentile.xml`
- `app/src/main/res/layout/item_statistics_health.xml`
  - Add or adjust range/context labels for insight cards.
- `common/src/main/res/values/strings.xml`
  - Add new strings used by day section and insight range labels.
- `common/src/main/res/values-en/strings.xml`
- `common/src/main/res/values-ja/strings.xml`
- `common/src/main/res/values-ko/strings.xml`
- `common/src/main/res/values-zh-rTW/strings.xml`
  - Add matching translations or clear fallback translations for new strings.

Do not modify:

- `.codex/skills/ui-ux-pro-max/SKILL.md` unless explicitly instructed. It is currently deleted in the working tree and unrelated to this plan.

## Task 1: Date Range Helper and Page UI Model

**Files:**

- Create: `app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsDateRange.kt`
- Create: `app/src/main/java/com/zero/babycare/statistics/model/StatisticsUiState.kt`
- Test: `app/src/test/java/com/zero/babycare/statistics/StatisticsDateRangeTest.kt`

- [ ] **Step 1: Write the failing date range tests**

Create `app/src/test/java/com/zero/babycare/statistics/StatisticsDateRangeTest.kt`:

```kotlin
package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.StatisticsDateRange
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

class StatisticsDateRangeTest {

    @Test
    fun `day range covers the selected local day`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val selectedDate = LocalDate.of(2026, 5, 2)

        val range = StatisticsDateRange.day(selectedDate, zone)

        assertEquals(
            selectedDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            range.startMillis
        )
        assertEquals(
            selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
            range.endMillis
        )
    }

    @Test
    fun `month range uses the selected date month`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val range = StatisticsDateRange.month(LocalDate.of(2026, 5, 2), zone)

        assertEquals(LocalDate.of(2026, 5, 1), range.startDate)
        assertEquals(LocalDate.of(2026, 5, 31), range.endDate)
    }

    @Test
    fun `week range follows the provided locale first day`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val weekFields = WeekFields.of(DayOfWeek.MONDAY, 4)
        val range = StatisticsDateRange.week(LocalDate.of(2026, 5, 2), zone, weekFields)

        assertEquals(LocalDate.of(2026, 4, 27), range.startDate)
        assertEquals(LocalDate.of(2026, 5, 3), range.endDate)
    }

    @Test
    fun `default week fields come from locale`() {
        val fields = StatisticsDateRange.weekFields(Locale.CHINA)

        assertEquals(WeekFields.of(Locale.CHINA).firstDayOfWeek, fields.firstDayOfWeek)
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.zero.babycare.statistics.StatisticsDateRangeTest"
```

Expected: compile failure because `StatisticsDateRange` does not exist.

- [ ] **Step 3: Add the date range helper**

Create `app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsDateRange.kt`:

```kotlin
package com.zero.babycare.statistics.mapper

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 统计页统一时间范围计算，避免各模块对 selectedDate 的边界理解不一致。
 */
object StatisticsDateRange {

    data class Range(
        val startDate: LocalDate,
        val endDate: LocalDate,
        val startMillis: Long,
        val endMillis: Long
    )

    fun weekFields(locale: Locale = Locale.getDefault()): WeekFields {
        return WeekFields.of(locale)
    }

    fun day(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Range {
        return buildRange(date, date, zone)
    }

    fun week(
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        weekFields: WeekFields = weekFields()
    ): Range {
        val start = date.with(TemporalAdjusters.previousOrSame(weekFields.firstDayOfWeek))
        return buildRange(start, start.plusDays(6), zone)
    }

    fun month(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Range {
        val month = YearMonth.from(date)
        return buildRange(month.atDay(1), month.atEndOfMonth(), zone)
    }

    fun year(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Range {
        return buildRange(LocalDate.of(date.year, 1, 1), LocalDate.of(date.year, 12, 31), zone)
    }

    private fun buildRange(startDate: LocalDate, endDate: LocalDate, zone: ZoneId): Range {
        val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return Range(startDate, endDate, startMillis, endMillis)
    }
}
```

- [ ] **Step 4: Add the page UI model skeleton**

Create `app/src/main/java/com/zero/babycare/statistics/model/StatisticsUiState.kt`:

```kotlin
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
```

Modify `app/src/main/java/com/zero/babycare/statistics/model/GrowthTrend.kt` by adding a companion object:

```kotlin
companion object {
    fun empty(): GrowthTrend {
        return GrowthTrend(
            weight = GrowthTrendItem(
                latestValue = null,
                diffValue = null,
                unitLabelResId = com.zero.common.util.UnitConfig.getWeightUnitLabelResId()
            ),
            height = GrowthTrendItem(
                latestValue = null,
                diffValue = null,
                unitLabelResId = com.zero.common.util.UnitConfig.getHeightUnitLabelResId()
            ),
            head = GrowthTrendItem(
                latestValue = null,
                diffValue = null,
                unitLabelResId = com.zero.common.util.UnitConfig.getHeightUnitLabelResId()
            )
        )
    }
}
```

- [ ] **Step 5: Run the date range test**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.zero.babycare.statistics.StatisticsDateRangeTest"
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

Run:

```powershell
git add -- app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsDateRange.kt app/src/main/java/com/zero/babycare/statistics/model/StatisticsUiState.kt app/src/main/java/com/zero/babycare/statistics/model/GrowthTrend.kt app/src/test/java/com/zero/babycare/statistics/StatisticsDateRangeTest.kt
git commit -m "feat: add statistics page ui state"
```

## Task 2: Growth Cutoff Logic

**Files:**

- Modify: `app/src/main/java/com/zero/babycare/statistics/StatisticsViewModel.kt`
- Test: `app/src/test/java/com/zero/babycare/statistics/StatisticsGrowthCutoffTest.kt`

- [ ] **Step 1: Write the failing growth cutoff tests**

Create `app/src/test/java/com/zero/babycare/statistics/StatisticsGrowthCutoffTest.kt`:

```kotlin
package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.StatisticsDateRange
import com.zero.babycare.statistics.mapper.StatisticsGrowthCutoff
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatisticsGrowthCutoffTest {

    @Test
    fun `growth records after selected day are excluded`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val selectedDate = LocalDate.of(2026, 5, 2)
        val endOfSelectedDay = StatisticsDateRange.day(selectedDate, zone).endMillis
        val records = listOf(
            growthRecord(1, selectedDate.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()),
            growthRecord(2, endOfSelectedDay),
            growthRecord(3, selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        )

        val result = StatisticsGrowthCutoff.filterUntil(records, selectedDate, zone)

        assertEquals(listOf(1, 2), result.map { it.eventId })
    }

    @Test
    fun `latest two growth records are sorted newest first before cutoff`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val selectedDate = LocalDate.of(2026, 5, 2)
        val records = listOf(
            growthRecord(1, selectedDate.minusDays(5).atStartOfDay(zone).toInstant().toEpochMilli()),
            growthRecord(2, selectedDate.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()),
            growthRecord(3, selectedDate.minusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()),
            growthRecord(4, selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        )

        val result = StatisticsGrowthCutoff.latestUntil(records, selectedDate, limit = 2, zone = zone)

        assertEquals(listOf(2, 3), result.map { it.eventId })
    }

    private fun growthRecord(id: Int, time: Long): EventRecord {
        return EventRecord(
            eventId = id,
            babyId = 1,
            type = EventType.GROWTH_HEIGHT,
            time = time,
            extraData = """{"value":76.0,"unit":"cm"}"""
        )
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.zero.babycare.statistics.StatisticsGrowthCutoffTest"
```

Expected: compile failure because `StatisticsGrowthCutoff` does not exist.

- [ ] **Step 3: Add the cutoff helper**

Create `app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsGrowthCutoff.kt`:

```kotlin
package com.zero.babycare.statistics.mapper

import com.zero.babydata.entity.EventRecord
import java.time.LocalDate
import java.time.ZoneId

/**
 * 成长记录必须尊重 selectedDate，避免历史日期看到未来才录入的数据。
 */
object StatisticsGrowthCutoff {

    fun filterUntil(
        records: List<EventRecord>,
        selectedDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<EventRecord> {
        val endMillis = StatisticsDateRange.day(selectedDate, zone).endMillis
        return records.filter { it.time <= endMillis }
    }

    fun latestUntil(
        records: List<EventRecord>,
        selectedDate: LocalDate,
        limit: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<EventRecord> {
        return filterUntil(records, selectedDate, zone)
            .sortedByDescending { it.time }
            .take(limit)
    }
}
```

- [ ] **Step 4: Use cutoff helper in growth builders**

Modify `app/src/main/java/com/zero/babycare/statistics/StatisticsViewModel.kt`:

```kotlin
private fun loadGrowthTrend() {
    val babyId = _currentBabyId.value
    val date = _selectedDate.value
    if (babyId <= 0) {
        _growthTrend.value = UiState.Success(GrowthTrend.empty())
        return
    }

    viewModelScope.launch {
        _growthTrend.value = UiState.Loading
        try {
            val trend = withContext(Dispatchers.IO) {
                buildGrowthTrend(babyId, date)
            }
            _growthTrend.value = UiState.Success(trend)
        } catch (e: Exception) {
            _growthTrend.value = UiState.Error(e, e.message ?: getLoadFailedText())
        }
    }
}

private fun buildGrowthTrend(babyId: Int, selectedDate: LocalDate): GrowthTrend {
    val weightUnit = UnitConfig.getWeightUnit()
    val heightUnit = UnitConfig.getHeightUnit()

    return GrowthTrend(
        weight = buildGrowthItem(
            babyId = babyId,
            type = EventType.GROWTH_WEIGHT,
            unitLabelResId = UnitConfig.getWeightUnitLabelResId(),
            targetUnit = weightUnit,
            selectedDate = selectedDate,
            converter = UnitConverter::weightToDisplay
        ),
        height = buildGrowthItem(
            babyId = babyId,
            type = EventType.GROWTH_HEIGHT,
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            selectedDate = selectedDate,
            converter = UnitConverter::heightToDisplay
        ),
        head = buildGrowthItem(
            babyId = babyId,
            type = EventType.GROWTH_HEAD,
            unitLabelResId = UnitConfig.getHeightUnitLabelResId(),
            targetUnit = heightUnit,
            selectedDate = selectedDate,
            converter = UnitConverter::heightToDisplay
        )
    )
}
```

Replace `buildGrowthItem` with:

```kotlin
private fun buildGrowthItem(
    babyId: Int,
    type: Int,
    unitLabelResId: Int,
    targetUnit: String,
    selectedDate: LocalDate,
    converter: (Double, String, String) -> Double
): GrowthTrendItem {
    val records = StatisticsGrowthCutoff.latestUntil(
        records = repository.getEventRecordsByType(babyId, type),
        selectedDate = selectedDate,
        limit = 2
    )
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
```

Modify `buildPercentileSeries` records line:

```kotlin
val records = StatisticsGrowthCutoff
    .filterUntil(repository.getEventRecordsByType(babyId, type), _selectedDate.value)
    .sortedBy { it.time }
```

Also update `loadGrowthPercentile()` to call `buildGrowthPercentileOverview(babyId, _selectedDate.value)`, and change the signature to:

```kotlin
private fun buildGrowthPercentileOverview(babyId: Int, selectedDate: LocalDate): GrowthPercentileOverview
```

Pass `selectedDate` into each `buildPercentileSeries(...)`, and add `selectedDate: LocalDate` to the function parameters.

- [ ] **Step 5: Run the growth cutoff test**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.zero.babycare.statistics.StatisticsGrowthCutoffTest"
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

Run:

```powershell
git add -- app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsGrowthCutoff.kt app/src/main/java/com/zero/babycare/statistics/StatisticsViewModel.kt app/src/test/java/com/zero/babycare/statistics/StatisticsGrowthCutoffTest.kt
git commit -m "fix: limit statistics growth data by selected date"
```

## Task 3: Timeline UI Mapper

**Files:**

- Create: `app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsTimelineMapper.kt`
- Modify: `app/src/main/java/com/zero/babycare/statistics/adapter/TimelineAdapter.kt`
- Test: `app/src/test/java/com/zero/babycare/statistics/StatisticsTimelineMapperTest.kt`

- [ ] **Step 1: Write mapper tests**

Create `app/src/test/java/com/zero/babycare/statistics/StatisticsTimelineMapperTest.kt`:

```kotlin
package com.zero.babycare.statistics

import com.zero.babycare.statistics.mapper.StatisticsTimelineMapper
import com.zero.babycare.statistics.model.TimelineEditTarget
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import com.zero.common.R as CommonR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StatisticsTimelineMapperTest {

    private val mapper = StatisticsTimelineMapper(
        strings = FakeStrings(),
        zone = ZoneId.of("Asia/Shanghai")
    )

    @Test
    fun `feeding item exposes edit target and semantic feeding color`() {
        val item = mapper.mapFeeding(
            FeedingRecord(
                feedingId = 9,
                babyId = 1,
                feedingType = FeedingType.BREAST.type,
                feedingStart = Instant.parse("2026-05-02T15:25:00Z").toEpochMilli(),
                feedingDuration = 60_000L
            )
        )

        assertEquals(TimelineEditTarget.Feeding(9), item.editTarget)
        assertEquals(CommonR.color.feeding_primary, item.colorResId)
        assertEquals(CommonR.color.feeding_bg, item.surfaceColorResId)
        assertTrue(item.titleText.contains("母乳"))
        assertTrue(item.detailText.contains("1分钟"))
    }

    @Test
    fun `sleep item exposes sleep target and duration detail`() {
        val item = mapper.mapSleep(
            SleepRecord(
                sleepId = 7,
                babyId = 1,
                sleepStart = Instant.parse("2026-05-02T14:30:00Z").toEpochMilli(),
                sleepEnd = Instant.parse("2026-05-02T15:00:00Z").toEpochMilli(),
                sleepDuration = 30 * 60_000L
            )
        )

        assertEquals(TimelineEditTarget.Sleep(7), item.editTarget)
        assertEquals(CommonR.color.sleep_primary, item.colorResId)
        assertTrue(item.detailText.contains("30分钟"))
    }

    @Test
    fun `diaper event item exposes event target and diaper color`() {
        val item = mapper.mapEvent(
            EventRecord(
                eventId = 5,
                babyId = 1,
                type = EventType.DIAPER_WET,
                time = Instant.parse("2026-05-02T13:00:00Z").toEpochMilli()
            )
        )

        assertEquals(TimelineEditTarget.Event(5), item.editTarget)
        assertEquals(CommonR.color.event_diaper, item.colorResId)
        assertEquals(CommonR.color.event_diaper_light, item.surfaceColorResId)
        assertTrue(item.titleText.contains("尿"))
    }

    private class FakeStrings : StatisticsTimelineMapper.Strings {
        override fun get(resId: Int): String {
            return when (resId) {
                CommonR.string.feeding_type_breast -> "母乳"
                CommonR.string.feeding_type_formula -> "奶粉"
                CommonR.string.feeding_type_mixed -> "混合喂养"
                CommonR.string.feeding_type_solid -> "辅食"
                CommonR.string.feeding_type_other -> "其他喂养"
                CommonR.string.feeding_type_title_format -> "%1\$s喂养"
                CommonR.string.feeding_total_minutes_format -> "共%1\$d分钟"
                CommonR.string.sleeping -> "睡眠"
                CommonR.string.sleep_duration_minutes -> "%1\$d分钟"
                CommonR.string.event_diaper_wet_title -> "尿布湿"
                CommonR.string.event_diaper_dirty_title -> "尿布脏"
                CommonR.string.event_diaper_mixed_title -> "尿布混合"
                CommonR.string.event_diaper_dry_title -> "尿布干净"
                else -> "res-$resId"
            }
        }

        override fun format(resId: Int, vararg args: Any): String {
            return get(resId).format(*args)
        }
    }
}
```

- [ ] **Step 2: Run the failing mapper tests**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.zero.babycare.statistics.StatisticsTimelineMapperTest"
```

Expected: compile failure because `StatisticsTimelineMapper` does not exist.

- [ ] **Step 3: Add the mapper**

Create `app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsTimelineMapper.kt`:

```kotlin
package com.zero.babycare.statistics.mapper

import com.zero.babycare.statistics.model.TimelineEditTarget
import com.zero.babycare.statistics.model.TimelineUiItem
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.SleepRecord
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.zero.common.R as CommonR

/**
 * 时间轴 UI 映射器。Adapter 只消费 TimelineUiItem，业务文案与语义资源在这里集中维护。
 */
class StatisticsTimelineMapper(
    private val strings: Strings,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    interface Strings {
        fun get(resId: Int): String
        fun format(resId: Int, vararg args: Any): String
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getTimeZone(zone)
    }

    fun mapFeeding(record: FeedingRecord): TimelineUiItem {
        val type = FeedingType.fromType(record.feedingType)
        val title = strings.format(
            CommonR.string.feeding_type_title_format,
            strings.get(feedingTypeTitle(type))
        )
        return TimelineUiItem(
            stableId = "feeding-${record.feedingId}".hashCode().toLong(),
            timeText = timeFormat.format(Date(record.feedingStart)),
            titleText = title,
            detailText = feedingDetail(record, type),
            noteText = record.note.takeIf { it.isNotBlank() },
            iconResId = feedingIcon(type),
            colorResId = feedingColor(type),
            surfaceColorResId = feedingSurface(type),
            editTarget = TimelineEditTarget.Feeding(record.feedingId)
        )
    }

    fun mapSleep(record: SleepRecord): TimelineUiItem {
        return TimelineUiItem(
            stableId = "sleep-${record.sleepId}".hashCode().toLong(),
            timeText = timeFormat.format(Date(record.sleepStart)),
            titleText = strings.get(CommonR.string.sleeping),
            detailText = formatMinutes(TimeUnit.MILLISECONDS.toMinutes(record.sleepDuration).toInt()),
            noteText = record.note.takeIf { it.isNotBlank() },
            iconResId = CommonR.drawable.ic_sleep,
            colorResId = CommonR.color.sleep_primary,
            surfaceColorResId = CommonR.color.sleep_bg,
            editTarget = TimelineEditTarget.Sleep(record.sleepId)
        )
    }

    fun mapEvent(record: EventRecord): TimelineUiItem {
        val info = eventInfo(record.type)
        return TimelineUiItem(
            stableId = "event-${record.eventId}".hashCode().toLong(),
            timeText = timeFormat.format(Date(record.time)),
            titleText = strings.get(info.titleResId),
            detailText = "",
            noteText = record.note.takeIf { it.isNotBlank() },
            iconResId = info.iconResId,
            colorResId = info.colorResId,
            surfaceColorResId = info.surfaceColorResId,
            editTarget = TimelineEditTarget.Event(record.eventId)
        )
    }

    private fun feedingDetail(record: FeedingRecord, type: FeedingType): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(record.feedingDuration).toInt()
        return when {
            totalMinutes > 0 -> strings.format(CommonR.string.feeding_total_minutes_format, totalMinutes)
            type == FeedingType.SOLID_FOOD && !record.foodName.isNullOrBlank() -> record.foodName.orEmpty()
            else -> ""
        }
    }

    private fun formatMinutes(minutes: Int): String {
        return strings.format(CommonR.string.sleep_duration_minutes, minutes)
    }

    private fun feedingTypeTitle(type: FeedingType): Int {
        return when (type) {
            FeedingType.BREAST -> CommonR.string.feeding_type_breast
            FeedingType.FORMULA -> CommonR.string.feeding_type_formula
            FeedingType.MIXED -> CommonR.string.feeding_type_mixed
            FeedingType.SOLID_FOOD -> CommonR.string.feeding_type_solid
            FeedingType.OTHER -> CommonR.string.feeding_type_other
        }
    }

    private fun feedingIcon(type: FeedingType): Int {
        return when (type) {
            FeedingType.SOLID_FOOD -> CommonR.drawable.ic_event_growth
            else -> CommonR.drawable.ic_feeding
        }
    }

    private fun feedingColor(type: FeedingType): Int {
        return when (type) {
            FeedingType.SOLID_FOOD -> CommonR.color.event_growth
            FeedingType.OTHER -> CommonR.color.event_other
            else -> CommonR.color.feeding_primary
        }
    }

    private fun feedingSurface(type: FeedingType): Int {
        return when (type) {
            FeedingType.SOLID_FOOD -> CommonR.color.event_growth_light
            FeedingType.OTHER -> CommonR.color.event_other_light
            else -> CommonR.color.feeding_bg
        }
    }

    private data class EventInfo(
        val titleResId: Int,
        val iconResId: Int,
        val colorResId: Int,
        val surfaceColorResId: Int
    )

    private fun eventInfo(type: Int): EventInfo {
        return when {
            type == EventType.DIAPER_WET -> EventInfo(
                CommonR.string.event_diaper_wet_title,
                CommonR.drawable.ic_event_diaper_wet,
                CommonR.color.event_diaper,
                CommonR.color.event_diaper_light
            )
            type == EventType.DIAPER_DIRTY -> EventInfo(
                CommonR.string.event_diaper_dirty_title,
                CommonR.drawable.ic_event_diaper_dirty,
                CommonR.color.event_diaper,
                CommonR.color.event_diaper_light
            )
            type == EventType.DIAPER_MIXED -> EventInfo(
                CommonR.string.event_diaper_mixed_title,
                CommonR.drawable.ic_event_diaper_mixed,
                CommonR.color.event_diaper,
                CommonR.color.event_diaper_light
            )
            type == EventType.DIAPER_DRY -> EventInfo(
                CommonR.string.event_diaper_dry_title,
                CommonR.drawable.ic_event_diaper_dry,
                CommonR.color.event_diaper,
                CommonR.color.event_diaper_light
            )
            EventType.isHealth(type) -> EventInfo(
                CommonR.string.statistics_health_title,
                CommonR.drawable.ic_event_temperature,
                CommonR.color.event_health,
                CommonR.color.event_health_light
            )
            EventType.isGrowth(type) -> EventInfo(
                CommonR.string.statistics_growth_title,
                CommonR.drawable.ic_event_height,
                CommonR.color.event_growth,
                CommonR.color.event_growth_light
            )
            else -> EventInfo(
                CommonR.string.event_other_title,
                CommonR.drawable.ic_event_custom,
                CommonR.color.event_other,
                CommonR.color.event_other_light
            )
        }
    }
}
```

- [ ] **Step 4: Refine mapper coverage while porting existing details**

Port the remaining event-specific title/detail logic from the current `TimelineAdapter` into `StatisticsTimelineMapper` before modifying the adapter. Preserve:

- feeding amount and breast side details
- sleep end time when present
- temperature value/location
- growth value/unit conversion
- diaper stool/urine details
- medicine dosage
- vaccine dose/site/clinic/batch
- milestone/custom names and descriptions
- duration for `EventType.hasDuration`

Use the existing private helper method names where practical so review is mechanical. Add one test case for each category that has non-empty details.

- [ ] **Step 5: Run mapper tests**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.zero.babycare.statistics.StatisticsTimelineMapperTest"
```

Expected: PASS.

- [ ] **Step 6: Refactor TimelineAdapter to bind TimelineUiItem**

Modify `app/src/main/java/com/zero/babycare/statistics/adapter/TimelineAdapter.kt`:

- Change class type from `BaseMultiItemAdapter<TimelineItem>()` to `BaseMultiItemAdapter<TimelineUiItem>()`.
- Add a `viewType` field to `TimelineUiItem` or infer view type from `editTarget`.
- In each bind branch, set:
  - time from `item.timeText`
  - title from `item.titleText`
  - detail from `item.detailText`
  - note from `item.noteText`
  - icon from `item.iconResId`
  - icon/dot tint from `item.colorResId`
  - icon background from `item.surfaceColorResId`
  - click callback with `item`
- Delete adapter-level parsing helpers after mapper tests pass.

The click callback signature becomes:

```kotlin
class TimelineAdapter(
    private val onItemClick: (TimelineUiItem) -> Unit
) : BaseMultiItemAdapter<TimelineUiItem>()
```

- [ ] **Step 7: Commit Task 3**

Run:

```powershell
git add -- app/src/main/java/com/zero/babycare/statistics/mapper/StatisticsTimelineMapper.kt app/src/main/java/com/zero/babycare/statistics/adapter/TimelineAdapter.kt app/src/test/java/com/zero/babycare/statistics/StatisticsTimelineMapperTest.kt
git commit -m "refactor: map statistics timeline ui items"
```

## Task 4: ViewModel Page State

**Files:**

- Modify: `app/src/main/java/com/zero/babycare/statistics/StatisticsViewModel.kt`
- Modify: `app/src/main/java/com/zero/babycare/statistics/StatisticsFragment.kt`
- Modify: `app/src/main/java/com/zero/babycare/statistics/model/StatisticsUiState.kt`

- [ ] **Step 1: Add state and event entry points**

In `StatisticsViewModel`, add:

```kotlin
private val _uiState = MutableStateFlow(StatisticsUiState())
val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

fun onEvent(event: StatisticsUiEvent) {
    when (event) {
        is StatisticsUiEvent.SelectDate -> selectDate(event.date)
        is StatisticsUiEvent.ChangeMonth -> onMonthChanged(event.yearMonth)
        StatisticsUiEvent.GoToday -> goToToday()
        StatisticsUiEvent.Refresh -> refreshData()
        is StatisticsUiEvent.OpenTimelineItem -> Unit
    }
}
```

Keep the existing section flows during the transition so the app still compiles. Remove them only after Fragment uses `uiState`.

- [ ] **Step 2: Build day section state**

Add this helper in `StatisticsViewModel`:

```kotlin
private fun buildDayRecordSection(
    date: LocalDate,
    babyInfo: BabyInfo?,
    summary: DaySummary,
    timelineItems: List<TimelineUiItem>
): DayRecordSectionUiModel {
    val babyAgeText = babyInfo?.let { buildBabyAgeText(it, date) }
    return DayRecordSectionUiModel(
        date = date,
        babyAgeText = babyAgeText,
        showBirthHint = babyAgeText.isNullOrBlank(),
        recordCountText = getApplication<Application>().getString(
            com.zero.common.R.string.statistics_day_record_count_format,
            timelineItems.size
        ),
        summaryMetrics = buildSummaryMetrics(summary),
        timelineItems = timelineItems,
        isEmpty = timelineItems.isEmpty()
    )
}
```

Add:

```kotlin
private fun buildBabyAgeText(babyInfo: BabyInfo, selectedDate: LocalDate): String? {
    if (babyInfo.birthDate <= 0) return null
    val birthDate = Instant.ofEpochMilli(babyInfo.birthDate)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val days = ChronoUnit.DAYS.between(birthDate, selectedDate)
    return if (days < 0) {
        getApplication<Application>().getString(com.zero.common.R.string.baby_not_born_yet_playful)
    } else {
        getApplication<Application>().getString(com.zero.common.R.string.days_born, days.toInt())
    }
}
```

Add:

```kotlin
private fun buildSummaryMetrics(summary: DaySummary): List<SummaryMetricUiModel> {
    val app = getApplication<Application>()
    return listOf(
        SummaryMetricUiModel(
            type = SummaryMetricType.FEEDING,
            titleResId = com.zero.common.R.string.feeding,
            primaryText = app.getString(com.zero.common.R.string.times_count_format, summary.feedingCount),
            secondaryText = summary.formatFeedingDuration(),
            iconResId = com.zero.common.R.drawable.ic_feeding,
            colorResId = com.zero.common.R.color.feeding_primary,
            surfaceColorResId = com.zero.common.R.color.feeding_bg
        ),
        SummaryMetricUiModel(
            type = SummaryMetricType.SLEEP,
            titleResId = com.zero.common.R.string.sleeping,
            primaryText = app.getString(com.zero.common.R.string.times_count_format, summary.sleepCount),
            secondaryText = summary.formatSleepDuration(),
            iconResId = com.zero.common.R.drawable.ic_sleep,
            colorResId = com.zero.common.R.color.sleep_primary,
            surfaceColorResId = com.zero.common.R.color.sleep_bg
        ),
        SummaryMetricUiModel(
            type = SummaryMetricType.DIAPER,
            titleResId = com.zero.common.R.string.diaper,
            primaryText = app.getString(com.zero.common.R.string.times_count_format, summary.totalDiaperCount),
            secondaryText = summary.formatDiaperDetail().ifBlank {
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
```

- [ ] **Step 3: Update day data loading to publish uiState**

Inside `loadDayData()`, after building `summary` and raw `timelineRecords`, map timeline records:

```kotlin
val mapper = StatisticsTimelineMapper(
    strings = object : StatisticsTimelineMapper.Strings {
        override fun get(resId: Int): String = getApplication<Application>().getString(resId)
        override fun format(resId: Int, vararg args: Any): String {
            return getApplication<Application>().getString(resId, *args)
        }
    }
)

val timelineUiItems = timelineRecords.map { record ->
    when (record) {
        is BabyRepository.TimelineRecord.Feeding -> mapper.mapFeeding(record.record)
        is BabyRepository.TimelineRecord.Sleep -> mapper.mapSleep(record.record)
        is BabyRepository.TimelineRecord.Event -> mapper.mapEvent(record.record)
    }
}
```

Then publish:

```kotlin
_uiState.value = _uiState.value.copy(
    babyId = babyId,
    selectedDate = date,
    dayRecord = buildDayRecordSection(
        date = date,
        babyInfo = repository.getBabyInfoSync(babyId),
        summary = summary,
        timelineItems = timelineUiItems
    ),
    isLoading = false,
    errorMessage = null
)
```

- [ ] **Step 4: Publish insight state**

After each insight load succeeds, update `_uiState.value.insights` with a copied `InsightSectionUiModel`. Use a helper:

```kotlin
private fun updateInsights(transform: (InsightSectionUiModel) -> InsightSectionUiModel) {
    _uiState.value = _uiState.value.copy(
        insights = transform(_uiState.value.insights)
    )
}
```

Examples:

```kotlin
updateInsights { it.copy(selectedDate = date, trend = overview) }
updateInsights { it.copy(selectedDate = date, structure = overview) }
updateInsights { it.copy(selectedDate = date, growth = trend) }
updateInsights { it.copy(selectedDate = date, percentile = overview) }
updateInsights { it.copy(selectedDate = date, health = stats) }
```

- [ ] **Step 5: Refresh growth when date changes**

Modify `selectDate(date)` to call:

```kotlin
loadDayData()
loadTrendOverview()
loadStructureOverview()
loadGrowthTrend()
loadGrowthPercentile()
loadHealthStats()
```

This is required because growth and percentile now depend on `selectedDate`.

- [ ] **Step 6: Compile**

Run:

```powershell
.\gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 7: Commit Task 4**

Run:

```powershell
git add -- app/src/main/java/com/zero/babycare/statistics/StatisticsViewModel.kt app/src/main/java/com/zero/babycare/statistics/StatisticsFragment.kt app/src/main/java/com/zero/babycare/statistics/model/StatisticsUiState.kt
git commit -m "refactor: publish statistics page state"
```

## Task 5: Day Section Rendering and Adapter Order

**Files:**

- Modify: `app/src/main/java/com/zero/babycare/statistics/StatisticsFragment.kt`
- Modify: `app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsBabyAgeAdapter.kt`
- Modify: `app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsSummaryAdapter.kt`
- Modify: `app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsEmptyAdapter.kt`
- Modify: `app/src/main/java/com/zero/babycare/statistics/adapter/TimelineAdapter.kt`

- [ ] **Step 1: Reorder ConcatAdapter**

In `StatisticsFragment.setupContentList()`, set adapter order:

```kotlin
concatAdapter = ConcatAdapter(
    calendarAdapter,
    babyAgeAdapter,
    summaryAdapter,
    timelineAdapter,
    trendAdapter,
    structureAdapter,
    growthAdapter,
    growthPercentileAdapter,
    healthAdapter
)
```

Empty adapter must be inserted immediately after `summaryAdapter` when the day timeline is empty:

```kotlin
private fun updateTimelineUI(items: List<TimelineUiItem>) {
    timelineAdapter.submitList(items)
    val hasEmptyAdapter = concatAdapter.adapters.contains(emptyAdapter)
    val timelineIndex = concatAdapter.adapters.indexOf(timelineAdapter)

    if (items.isEmpty()) {
        if (!hasEmptyAdapter) {
            concatAdapter.addAdapter(timelineIndex, emptyAdapter)
        }
        timelineAdapter.setRoundBottom(false)
    } else {
        if (hasEmptyAdapter) {
            concatAdapter.removeAdapter(emptyAdapter)
        }
        timelineAdapter.setRoundBottom(true)
    }
}
```

- [ ] **Step 2: Render uiState in Fragment**

Add collection:

```kotlin
launchInLifecycle {
    vm.uiState.collect { state ->
        calendarAdapter.setDatesWithRecords(state.datesWithRecords)
        calendarAdapter.syncSelectedDate(state.selectedDate)
        babyAgeAdapter.bindDayRecord(state.dayRecord)
        summaryAdapter.updateSummary(state.dayRecord.summaryMetrics)
        updateTimelineUI(state.dayRecord.timelineItems)
        trendAdapter.updateTrend(state.insights.trend)
        structureAdapter.updateStructure(state.insights.structure)
        growthAdapter.updateTrend(state.insights.growth)
        growthPercentileAdapter.updatePercentile(state.insights.percentile)
        healthAdapter.updateHealth(state.insights.health)
    }
}
```

After this compiles, remove old collectors for `daySummary`, `timelineItems`, `growthTrend`, `trendOverview`, `structureOverview`, `growthPercentile`, `healthStats`, `datesWithRecords`, and `selectedDate`.

- [ ] **Step 3: Update click navigation**

Change timeline click handling:

```kotlin
private fun handleTimelineItemClick(item: TimelineUiItem) {
    when (val target = item.editTarget) {
        is TimelineEditTarget.Feeding -> mainVm.navigateTo(
            NavTarget.FeedingRecord(
                editRecordId = target.recordId,
                returnTarget = NavTarget.Statistics(getReturnTarget())
            )
        )
        is TimelineEditTarget.Sleep -> mainVm.navigateTo(
            NavTarget.SleepRecord(
                editRecordId = target.recordId,
                returnTarget = NavTarget.Statistics(getReturnTarget())
            )
        )
        is TimelineEditTarget.Event -> mainVm.navigateTo(
            NavTarget.EventRecord(
                editRecordId = target.recordId,
                returnTarget = NavTarget.Statistics(getReturnTarget())
            )
        )
    }
}
```

- [ ] **Step 4: Update BabyAgeAdapter**

Replace `updateBabyDaysText` with:

```kotlin
fun bindDayRecord(dayRecord: DayRecordSectionUiModel) {
    babyDaysText = dayRecord.babyAgeText
    recordCountText = dayRecord.recordCountText
    showBirthHint = dayRecord.showBirthHint
    binding?.let { applyState(it) }
}
```

Add fields:

```kotlin
private var recordCountText: String = ""
private var showBirthHint: Boolean = true
```

Update binding:

```kotlin
private fun applyState(binding: ItemStatisticsBabyAgeBinding) {
    binding.flBabyAge.visibility = View.VISIBLE
    binding.llBirthHint.visibility = if (showBirthHint) View.VISIBLE else View.GONE
    binding.llBabyAge.visibility = if (babyDaysText.isNullOrBlank()) View.GONE else View.VISIBLE
    binding.tvBabyDays.text = babyDaysText.orEmpty()
    binding.tvRecordCount.text = recordCountText
}
```

- [ ] **Step 5: Update SummaryAdapter**

Change adapter storage to:

```kotlin
private var metrics: List<SummaryMetricUiModel> = emptyList()

fun updateSummary(items: List<SummaryMetricUiModel>) {
    metrics = items
    notifyItemChanged(0)
}
```

Bind each of the four metric views by index. If the list is empty, show placeholders using `statistics_growth_placeholder` and existing titles.

- [ ] **Step 6: Compile**

Run:

```powershell
.\gradlew :app:compileDebugKotlin
```

Expected: PASS.

- [ ] **Step 7: Commit Task 5**

Run:

```powershell
git add -- app/src/main/java/com/zero/babycare/statistics/StatisticsFragment.kt app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsBabyAgeAdapter.kt app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsSummaryAdapter.kt app/src/main/java/com/zero/babycare/statistics/adapter/StatisticsEmptyAdapter.kt app/src/main/java/com/zero/babycare/statistics/adapter/TimelineAdapter.kt
git commit -m "refactor: render statistics selected day section"
```

## Task 6: XML Layout and String Resources

**Files:**

- Modify: `app/src/main/res/layout/item_statistics_summary.xml`
- Modify: `app/src/main/res/layout/item_statistics_baby_age.xml`
- Modify: `app/src/main/res/layout/item_statistics_empty.xml`
- Modify: `app/src/main/res/layout/item_statistics_trend.xml`
- Modify: `app/src/main/res/layout/item_statistics_structure.xml`
- Modify: `app/src/main/res/layout/item_statistics_growth.xml`
- Modify: `app/src/main/res/layout/item_statistics_growth_percentile.xml`
- Modify: `app/src/main/res/layout/item_statistics_health.xml`
- Modify: `common/src/main/res/values/strings.xml`
- Modify: localized `strings.xml` files under `values-en`, `values-ja`, `values-ko`, `values-zh-rTW`

- [ ] **Step 1: Add string resources**

Add to `common/src/main/res/values/strings.xml`:

```xml
<string name="statistics_day_record_count_format">当天 %1$d 条记录</string>
<string name="statistics_summary_other_scope">护理/活动/健康</string>
<string name="statistics_insight_title">规律洞察</string>
<string name="statistics_insight_based_on_format">基于 %1$s</string>
<string name="statistics_until_selected_date_format">截至 %1$s</string>
```

Add matching entries:

`common/src/main/res/values-en/strings.xml`

```xml
<string name="statistics_day_record_count_format">%1$d records that day</string>
<string name="statistics_summary_other_scope">Care/activity/health</string>
<string name="statistics_insight_title">Routine insights</string>
<string name="statistics_insight_based_on_format">Based on %1$s</string>
<string name="statistics_until_selected_date_format">Until %1$s</string>
```

`common/src/main/res/values-zh-rTW/strings.xml`

```xml
<string name="statistics_day_record_count_format">當天 %1$d 條記錄</string>
<string name="statistics_summary_other_scope">護理/活動/健康</string>
<string name="statistics_insight_title">規律洞察</string>
<string name="statistics_insight_based_on_format">基於 %1$s</string>
<string name="statistics_until_selected_date_format">截至 %1$s</string>
```

`common/src/main/res/values-ja/strings.xml`

```xml
<string name="statistics_day_record_count_format">当日 %1$d 件の記録</string>
<string name="statistics_summary_other_scope">ケア/活動/健康</string>
<string name="statistics_insight_title">リズムの洞察</string>
<string name="statistics_insight_based_on_format">%1$s に基づく</string>
<string name="statistics_until_selected_date_format">%1$s まで</string>
```

`common/src/main/res/values-ko/strings.xml`

```xml
<string name="statistics_day_record_count_format">해당 날짜 기록 %1$d개</string>
<string name="statistics_summary_other_scope">케어/활동/건강</string>
<string name="statistics_insight_title">패턴 인사이트</string>
<string name="statistics_insight_based_on_format">%1$s 기준</string>
<string name="statistics_until_selected_date_format">%1$s까지</string>
```

- [ ] **Step 2: Convert summary layout to 2x2**

In `item_statistics_summary.xml`, replace the four-column horizontal container with a vertical container containing two horizontal rows. Keep IDs:

- `tvFeedingCount`
- `tvSleepDuration`
- `tvDiaperCount`
- `tvOtherCount`

Add secondary text IDs:

- `tvFeedingDetail`
- `tvSleepDetail`
- `tvDiaperDetail`
- `tvOtherDetail`

Each metric cell must use:

```xml
android:background="@drawable/bg_r8_block_gridview"
android:minHeight="72dp"
android:paddingHorizontal="12dp"
android:paddingVertical="10dp"
```

Do not add `android:elevation`, `android:translationZ`, `cardElevation`, `android:textSize`, or hardcoded `#RRGGBB`.

- [ ] **Step 3: Add record count to baby age layout**

In `item_statistics_baby_age.xml`, add:

```xml
<TextView
    android:id="@+id/tvRecordCount"
    style="@style/TextAppearance.BabyCare.Label"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="center_vertical|end"
    android:textColor="?attr/colorTextHint"
    tools:text="当天 5 条记录" />
```

If `FrameLayout` cannot align both left chip and right count cleanly, change the root child to a horizontal `LinearLayout` with `gravity="center_vertical"` and a spacer `View` with `layout_weight="1"`.

- [ ] **Step 4: Update empty state visual attachment**

In `item_statistics_empty.xml`:

- reduce `android:paddingTop` from `60dp` to `24dp`
- keep bottom rounded background
- keep Add Record action
- do not add shadow

- [ ] **Step 5: Add insight context labels**

For insight layouts, add or keep a small range label near each title:

- `item_statistics_trend.xml`: show `statistics_insight_based_on_format`
- `item_statistics_structure.xml`: show selected month range through existing adapter data
- `item_statistics_growth.xml`: show `statistics_until_selected_date_format`
- `item_statistics_growth_percentile.xml`: show `statistics_until_selected_date_format`
- `item_statistics_health.xml`: keep `statistics_health_range_format`

Use `TextAppearance.BabyCare.Label` and `?attr/colorTextHint`.

- [ ] **Step 6: Run resource policy tests**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.zero.babycare.ui.UiResourcePolicyTest"
```

Expected: PASS.

- [ ] **Step 7: Commit Task 6**

Run:

```powershell
git add -- app/src/main/res/layout/item_statistics_summary.xml app/src/main/res/layout/item_statistics_baby_age.xml app/src/main/res/layout/item_statistics_empty.xml app/src/main/res/layout/item_statistics_trend.xml app/src/main/res/layout/item_statistics_structure.xml app/src/main/res/layout/item_statistics_growth.xml app/src/main/res/layout/item_statistics_growth_percentile.xml app/src/main/res/layout/item_statistics_health.xml common/src/main/res/values/strings.xml common/src/main/res/values-en/strings.xml common/src/main/res/values-ja/strings.xml common/src/main/res/values-ko/strings.xml common/src/main/res/values-zh-rTW/strings.xml
git commit -m "style: group statistics day and insight sections"
```

## Task 7: Final Cleanup and Verification

**Files:**

- Modify only files touched by Tasks 1-6 if compile/test failures require fixes.

- [ ] **Step 1: Remove obsolete state collectors and imports**

In `StatisticsFragment.kt`, remove unused imports for raw section models if no longer needed:

```kotlin
import com.zero.babycare.statistics.model.DaySummary
import com.zero.babycare.statistics.model.GrowthTrend
import com.zero.babycare.statistics.model.StructureOverview
import com.zero.babycare.statistics.model.TimelineItem
import com.zero.babycare.statistics.model.TrendOverview
import com.zero.components.base.vm.UiState
```

Keep only imports used by `StatisticsUiState`, `TimelineUiItem`, and `TimelineEditTarget`.

- [ ] **Step 2: Remove obsolete ViewModel section flows if all rendering uses uiState**

After compile confirms Fragment no longer uses old flows, delete public flows that are not needed outside `StatisticsViewModel`:

- `daySummary`
- `timelineItems`
- `growthTrend`
- `trendOverview`
- `structureOverview`
- `growthPercentile`
- `healthStats`

If another file still references one, keep it and add a comment explaining why it remains:

```kotlin
// 仍保留给现有日历/统计适配过渡使用；完成页面状态迁移后可删除。
```

- [ ] **Step 3: Run targeted statistics tests**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.zero.babycare.statistics.*"
```

Expected: PASS.

- [ ] **Step 4: Run app unit tests**

Run:

```powershell
.\gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Build debug APK**

Run:

```powershell
.\gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Manual UI QA**

Install or run the app and verify:

- Boy theme light mode: selected-day card reads as one continuous section.
- Girl theme light mode: brand accent changes, semantic event colors stay unchanged.
- Dark mode: all text and icons in summary/timeline/insight cards are readable.
- Select a previous date with no records: empty state appears directly below summary.
- Select a previous date before a later growth measurement: growth/percentile do not show that future measurement.
- Expand/collapse calendar: day section remains visually attached and timeline stays below summary.
- Tap feeding, sleep, and event timeline rows: each opens the correct existing edit screen and returns to Statistics.

- [ ] **Step 7: Commit final cleanup**

Run:

```powershell
git status --short
git add -- app/src/main/java/com/zero/babycare/statistics app/src/main/res/layout common/src/main/res/values common/src/main/res/values-en common/src/main/res/values-ja common/src/main/res/values-ko common/src/main/res/values-zh-rTW app/src/test/java/com/zero/babycare/statistics
git commit -m "chore: verify statistics fragment redesign"
```

## Self-Review

Spec coverage:

- Selected date as primary state: Tasks 1, 4, 5.
- Day section grouping: Tasks 5, 6.
- Insight section labels and order: Tasks 5, 6.
- Growth cutoff by selected date: Task 2.
- Timeline business logic moved out of Adapter: Task 3.
- MVVM Fragment boundary: Tasks 4, 5, 7.
- Soft Utility UI constraints: Task 6 plus `UiResourcePolicyTest`.
- Verification commands: Task 7.

Placeholder scan:

- This plan avoids unresolved markers and incomplete sections.
- Every code-changing task includes exact file paths, snippets, commands, and expected results.

Type consistency:

- `StatisticsUiState`, `DayRecordSectionUiModel`, `SummaryMetricUiModel`, `TimelineUiItem`, `TimelineEditTarget`, and `InsightSectionUiModel` are defined in Task 1 and reused by later tasks.
- `StatisticsDateRange` is defined in Task 1 and reused by Task 2.
- `StatisticsGrowthCutoff` is defined in Task 2 and reused by `StatisticsViewModel`.
- `StatisticsTimelineMapper` is defined in Task 3 and reused by `StatisticsViewModel`.
