package com.zero.babycare.statistics.mapper

import com.zero.babycare.statistics.model.TimelineEditTarget
import com.zero.babycare.statistics.model.TimelineUiItem
import com.zero.babydata.entity.CustomEventData
import com.zero.babydata.entity.DiaperData
import com.zero.babydata.entity.EventExtraData
import com.zero.babydata.entity.EventRecord
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingRecord
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.GrowthData
import com.zero.babydata.entity.MedicineData
import com.zero.babydata.entity.MilestoneData
import com.zero.babydata.entity.SleepRecord
import com.zero.babydata.entity.SymptomData
import com.zero.babydata.entity.TemperatureData
import com.zero.babydata.entity.VaccineData
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import com.zero.common.R as CommonR

/**
 * 时间轴 UI 映射器。业务文案、语义颜色和编辑目标在这里一次性准备好，
 * Adapter 只负责绑定 TimelineUiItem，避免列表层继续解析原始业务记录。
 */
class StatisticsTimelineMapper(
    private val strings: Strings,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val units: Units = UnitConfigUnits
) {

    interface Strings {
        fun get(resId: Int): String
        fun format(resId: Int, vararg args: Any): String
    }

    interface Units {
        fun feedingUnit(): String
        fun feedingUnitLabelResId(): Int
        fun weightUnit(): String
        fun weightUnitLabelResId(): Int
        fun heightUnit(): String
        fun heightUnitLabelResId(): Int
    }

    /**
     * 默认从应用设置读取单位；测试可注入固定单位，避免 JVM 单测初始化 Android 存储。
     */
    private object UnitConfigUnits : Units {
        override fun feedingUnit(): String = UnitConfig.getFeedingUnit()
        override fun feedingUnitLabelResId(): Int = UnitConfig.getFeedingUnitLabelResId()
        override fun weightUnit(): String = UnitConfig.getWeightUnit()
        override fun weightUnitLabelResId(): Int = UnitConfig.getWeightUnitLabelResId()
        override fun heightUnit(): String = UnitConfig.getHeightUnit()
        override fun heightUnitLabelResId(): Int = UnitConfig.getHeightUnitLabelResId()
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }

    fun mapFeeding(record: FeedingRecord): TimelineUiItem {
        val type = record.getFeedingTypeEnum()
        val title = strings.format(
            CommonR.string.feeding_type_title_format,
            strings.get(feedingTypeTitle(type))
        )
        return TimelineUiItem(
            stableId = stableId("feeding", record.feedingId),
            timeText = timeFormat.format(Date(record.feedingStart)),
            titleText = title,
            detailText = buildFeedingDetail(record, type),
            noteText = record.note.takeIf { it.isNotBlank() },
            iconResId = feedingIcon(type),
            colorResId = feedingColor(type),
            surfaceColorResId = feedingSurface(type),
            editTarget = TimelineEditTarget.Feeding(record.feedingId)
        )
    }

    fun mapSleep(record: SleepRecord): TimelineUiItem {
        return TimelineUiItem(
            stableId = stableId("sleep", record.sleepId),
            timeText = timeFormat.format(Date(record.sleepStart)),
            titleText = strings.get(CommonR.string.sleeping),
            detailText = buildSleepDuration(record.sleepDuration),
            noteText = record.note.takeIf { it.isNotBlank() },
            iconResId = CommonR.drawable.ic_sleep,
            colorResId = CommonR.color.sleep_primary,
            surfaceColorResId = CommonR.color.sleep_bg,
            editTarget = TimelineEditTarget.Sleep(record.sleepId)
        )
    }

    fun mapEvent(record: EventRecord): TimelineUiItem {
        val info = getEventTypeResources(record.type)
        return TimelineUiItem(
            stableId = stableId("event", record.eventId),
            timeText = timeFormat.format(Date(record.time)),
            titleText = info.title,
            detailText = buildEventDetail(record),
            noteText = record.note.takeIf { it.isNotBlank() },
            iconResId = info.iconRes,
            colorResId = info.colorRes,
            surfaceColorResId = info.bgColorRes,
            editTarget = TimelineEditTarget.Event(record.eventId)
        )
    }

    private fun stableId(prefix: String, recordId: Int): Long {
        return "$prefix-$recordId".hashCode().toLong()
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

    private fun buildFeedingDetail(record: FeedingRecord, type: FeedingType): String {
        val parts = mutableListOf<String>()
        val separator = strings.get(CommonR.string.list_separator_dot)

        when (type) {
            FeedingType.BREAST -> {
                val leftMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDurationBreastLeft)
                val rightMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDurationBreastRight)
                if (leftMin > 0) {
                    val label = strings.get(CommonR.string.left_breast)
                    parts.add(strings.format(CommonR.string.feeding_breast_side_minutes_format, label, leftMin))
                }
                if (rightMin > 0) {
                    val label = strings.get(CommonR.string.right_breast)
                    parts.add(strings.format(CommonR.string.feeding_breast_side_minutes_format, label, rightMin))
                }
                val totalMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDuration)
                if (totalMin > 0) {
                    parts.add(strings.format(CommonR.string.feeding_total_minutes_format, totalMin))
                }
            }
            FeedingType.FORMULA, FeedingType.MIXED -> {
                record.feedingAmount?.let { amount ->
                    if (amount > 0) {
                        val unitValue = units.feedingUnit()
                        val unitLabel = strings.get(units.feedingUnitLabelResId())
                        val displayValue = UnitConverter.feedingToDisplay(amount, unitValue)
                        val displayText = UnitConverter.formatFeedingAmount(displayValue, unitValue)
                        parts.add(strings.format(CommonR.string.feeding_amount_unit_format, displayText, unitLabel))
                    }
                }
                val totalMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDuration)
                if (totalMin > 0) {
                    parts.add(strings.format(CommonR.string.min_format, totalMin))
                }
            }
            FeedingType.SOLID_FOOD -> {
                record.foodName?.let { if (it.isNotBlank()) parts.add(it) }
                record.feedingAmount?.let { amount ->
                    if (amount > 0) {
                        val unit = strings.get(CommonR.string.unit_g_abbr)
                        parts.add(strings.format(CommonR.string.feeding_amount_unit_format, amount, unit))
                    }
                }
            }
            FeedingType.OTHER -> {
                record.foodName?.let { if (it.isNotBlank()) parts.add(it) }
                val totalMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDuration)
                if (totalMin > 0) {
                    parts.add(strings.format(CommonR.string.min_format, totalMin))
                }
            }
        }

        return parts.joinToString(separator)
    }

    private fun buildSleepDuration(durationMillis: Long): String {
        val durationMin = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
        val hours = durationMin / 60
        val minutes = durationMin % 60
        return when {
            hours > 0 && minutes > 0 -> strings.format(CommonR.string.sleep_duration_format, hours, minutes)
            hours > 0 -> strings.format(CommonR.string.sleep_duration_hours, hours)
            minutes > 0 -> strings.format(CommonR.string.sleep_duration_minutes, minutes)
            else -> ""
        }
    }

    private fun getEventTypeResources(type: Int): EventTypeInfo {
        return when {
            type == EventType.DIAPER_WET -> EventTypeInfo(
                CommonR.drawable.ic_event_diaper_wet,
                CommonR.color.event_diaper,
                CommonR.color.event_diaper_light,
                strings.get(CommonR.string.event_diaper_wet_title)
            )
            type == EventType.DIAPER_DIRTY -> EventTypeInfo(
                CommonR.drawable.ic_event_diaper_dirty,
                CommonR.color.event_diaper,
                CommonR.color.event_diaper_light,
                strings.get(CommonR.string.event_diaper_dirty_title)
            )
            type == EventType.DIAPER_MIXED -> EventTypeInfo(
                CommonR.drawable.ic_event_diaper_mixed,
                CommonR.color.event_diaper,
                CommonR.color.event_diaper_light,
                strings.get(CommonR.string.event_diaper_mixed_title)
            )
            type == EventType.DIAPER_DRY -> EventTypeInfo(
                CommonR.drawable.ic_event_diaper_dry,
                CommonR.color.event_diaper,
                CommonR.color.event_diaper_light,
                strings.get(CommonR.string.event_diaper_dry_title)
            )
            type == EventType.HEALTH_TEMPERATURE -> EventTypeInfo(
                CommonR.drawable.ic_event_temperature,
                CommonR.color.event_health,
                CommonR.color.event_health_light,
                strings.format(
                    CommonR.string.event_measure_title_format,
                    strings.get(CommonR.string.event_health_temperature)
                )
            )
            type == EventType.HEALTH_MEDICINE -> EventTypeInfo(
                CommonR.drawable.ic_event_medicine,
                CommonR.color.event_health,
                CommonR.color.event_health_light,
                strings.get(CommonR.string.event_health_medicine)
            )
            type == EventType.HEALTH_VACCINE -> EventTypeInfo(
                CommonR.drawable.ic_event_vaccine,
                CommonR.color.event_health,
                CommonR.color.event_health_light,
                strings.get(CommonR.string.event_vaccine_title)
            )
            type == EventType.HEALTH_DOCTOR -> EventTypeInfo(
                CommonR.drawable.ic_event_doctor,
                CommonR.color.event_health,
                CommonR.color.event_health_light,
                strings.get(CommonR.string.event_doctor_title)
            )
            type == EventType.HEALTH_SYMPTOM -> EventTypeInfo(
                CommonR.drawable.ic_event_symptom,
                CommonR.color.event_health,
                CommonR.color.event_health_light,
                strings.format(
                    CommonR.string.event_record_title_format,
                    strings.get(CommonR.string.event_health_symptom)
                )
            )
            type == EventType.GROWTH_WEIGHT -> EventTypeInfo(
                CommonR.drawable.ic_event_weight,
                CommonR.color.event_growth,
                CommonR.color.event_growth_light,
                strings.format(
                    CommonR.string.event_measure_title_format,
                    strings.get(CommonR.string.event_growth_weight)
                )
            )
            type == EventType.GROWTH_HEIGHT -> EventTypeInfo(
                CommonR.drawable.ic_event_height,
                CommonR.color.event_growth,
                CommonR.color.event_growth_light,
                strings.format(
                    CommonR.string.event_measure_title_format,
                    strings.get(CommonR.string.event_growth_height)
                )
            )
            type == EventType.GROWTH_HEAD -> EventTypeInfo(
                CommonR.drawable.ic_event_head,
                CommonR.color.event_growth,
                CommonR.color.event_growth_light,
                strings.format(
                    CommonR.string.event_measure_title_format,
                    strings.get(CommonR.string.event_growth_head)
                )
            )
            EventType.isMilestone(type) -> EventTypeInfo(
                getMilestoneIcon(type),
                CommonR.color.event_milestone,
                CommonR.color.event_milestone_light,
                strings.format(CommonR.string.event_milestone_title_format, getMilestoneTitle(type))
            )
            type == EventType.CARE_BATH -> careInfo(CommonR.drawable.ic_event_bath, CommonR.string.event_care_bath)
            type == EventType.CARE_NAIL -> careInfo(CommonR.drawable.ic_event_nail, CommonR.string.event_care_nail)
            type == EventType.CARE_SKINCARE -> careInfo(
                CommonR.drawable.ic_event_skincare,
                CommonR.string.event_care_skincare
            )
            type == EventType.CARE_MASSAGE -> careInfo(CommonR.drawable.ic_event_massage, CommonR.string.event_care_massage)
            type == EventType.CARE_NOSE -> careInfo(CommonR.drawable.ic_event_nose, CommonR.string.event_care_nose)
            type == EventType.CARE_EAR -> careInfo(CommonR.drawable.ic_event_ear, CommonR.string.event_care_ear)
            type == EventType.ACTIVITY_OUTDOOR -> activityInfo(
                CommonR.drawable.ic_event_outdoor,
                CommonR.string.event_activity_outdoor
            )
            type == EventType.ACTIVITY_TUMMY_TIME -> activityInfo(
                CommonR.drawable.ic_event_tummy,
                CommonR.string.event_activity_tummy
            )
            type == EventType.ACTIVITY_SWIMMING -> activityInfo(
                CommonR.drawable.ic_event_swimming,
                CommonR.string.event_activity_swimming
            )
            type == EventType.ACTIVITY_PLAY -> activityInfo(CommonR.drawable.ic_event_play, CommonR.string.event_activity_play)
            type == EventType.OTHER_BURP -> otherInfo(CommonR.drawable.ic_event_burp, CommonR.string.event_other_burp)
            type == EventType.OTHER_CRY -> otherInfo(CommonR.drawable.ic_event_cry, CommonR.string.event_other_cry)
            type == EventType.OTHER_SPIT_UP -> otherInfo(
                CommonR.drawable.ic_event_spit_up,
                CommonR.string.event_other_spit_up
            )
            else -> otherInfo(CommonR.drawable.ic_event_custom, CommonR.string.event_other_title)
        }
    }

    private fun careInfo(iconRes: Int, titleRes: Int): EventTypeInfo {
        return EventTypeInfo(
            iconRes = iconRes,
            colorRes = CommonR.color.event_care,
            bgColorRes = CommonR.color.event_care_light,
            title = strings.get(titleRes)
        )
    }

    private fun activityInfo(iconRes: Int, titleRes: Int): EventTypeInfo {
        return EventTypeInfo(
            iconRes = iconRes,
            colorRes = CommonR.color.event_activity,
            bgColorRes = CommonR.color.event_activity_light,
            title = strings.get(titleRes)
        )
    }

    private fun otherInfo(iconRes: Int, titleRes: Int): EventTypeInfo {
        return EventTypeInfo(
            iconRes = iconRes,
            colorRes = CommonR.color.event_other,
            bgColorRes = CommonR.color.event_other_light,
            title = strings.get(titleRes)
        )
    }

    private fun getMilestoneIcon(type: Int): Int {
        return when (type) {
            EventType.MILESTONE_ROLL -> CommonR.drawable.ic_event_roll
            EventType.MILESTONE_SIT -> CommonR.drawable.ic_event_sit
            EventType.MILESTONE_CRAWL -> CommonR.drawable.ic_event_crawl
            EventType.MILESTONE_STAND -> CommonR.drawable.ic_event_stand
            EventType.MILESTONE_WALK -> CommonR.drawable.ic_event_walk
            EventType.MILESTONE_FIRST_WORD -> CommonR.drawable.ic_event_talk
            EventType.MILESTONE_FIRST_TOOTH -> CommonR.drawable.ic_event_tooth
            else -> CommonR.drawable.ic_event_milestone_custom
        }
    }

    private fun getMilestoneTitle(type: Int): String {
        return when (type) {
            EventType.MILESTONE_ROLL -> strings.get(CommonR.string.event_milestone_roll)
            EventType.MILESTONE_SIT -> strings.get(CommonR.string.event_milestone_sit)
            EventType.MILESTONE_CRAWL -> strings.get(CommonR.string.event_milestone_crawl)
            EventType.MILESTONE_STAND -> strings.get(CommonR.string.event_milestone_stand)
            EventType.MILESTONE_WALK -> strings.get(CommonR.string.event_milestone_walk)
            EventType.MILESTONE_FIRST_WORD -> strings.get(CommonR.string.event_milestone_first_word)
            EventType.MILESTONE_FIRST_TOOTH -> strings.get(CommonR.string.event_milestone_first_tooth)
            else -> strings.get(CommonR.string.event_milestone_custom)
        }
    }

    private fun buildEventDetail(event: EventRecord): String {
        val parts = mutableListOf<String>()
        val separator = strings.get(CommonR.string.list_separator_dot)
        val extraData = EventExtraData.parse(event.type, event.extraData)

        when (extraData) {
            is TemperatureData -> {
                val valueText = formatDecimal(extraData.value)
                val unit = strings.get(CommonR.string.temperature_unit)
                parts.add(valueText + unit)
                mapTemperatureLocation(extraData.location)?.let { parts.add(it) }
            }
            is GrowthData -> {
                val targetUnit = when (event.type) {
                    EventType.GROWTH_WEIGHT -> units.weightUnit()
                    EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD -> units.heightUnit()
                    else -> units.heightUnit()
                }
                val targetUnitLabel = when (event.type) {
                    EventType.GROWTH_WEIGHT -> strings.get(units.weightUnitLabelResId())
                    EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD -> {
                        strings.get(units.heightUnitLabelResId())
                    }
                    else -> ""
                }
                val displayValue = when (event.type) {
                    EventType.GROWTH_WEIGHT -> {
                        UnitConverter.weightToDisplay(extraData.value, extraData.unit, targetUnit)
                    }
                    EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD -> {
                        UnitConverter.heightToDisplay(extraData.value, extraData.unit, targetUnit)
                    }
                    else -> extraData.value
                }
                if (targetUnitLabel.isNotBlank()) {
                    parts.add(UnitConverter.formatDecimal(displayValue) + targetUnitLabel)
                } else {
                    parts.add(UnitConverter.formatDecimal(displayValue))
                }
            }
            is DiaperData -> {
                mapStoolColor(extraData.color)?.let { parts.add(it) }
                mapStoolConsistency(extraData.consistency)?.let { parts.add(it) }
                val urineAmountValue = mapUrineAmount(extraData.urineAmount)
                    ?: if (extraData.abnormal) {
                        strings.get(CommonR.string.urine_amount_much)
                    } else {
                        null
                    }
                urineAmountValue?.let { value ->
                    val urineAmountLabel = strings.get(CommonR.string.urine_amount)
                    parts.add(strings.format(CommonR.string.event_label_value_format, urineAmountLabel, value))
                }
            }
            is MedicineData -> {
                if (extraData.name.isNotBlank()) {
                    parts.add(extraData.name)
                }
                buildDosageText(extraData)?.let { parts.add(it) }
            }
            is SymptomData -> {
                extraData.description?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            }
            is VaccineData -> {
                if (extraData.name.isNotBlank()) {
                    parts.add(extraData.name)
                }
                extraData.dose?.takeIf { it > 0 }?.let { dose ->
                    parts.add(strings.format(CommonR.string.vaccine_dose_format, dose))
                }
                extraData.site?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                extraData.clinic?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                extraData.batchNumber?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            }
            is MilestoneData -> {
                extraData.name?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                extraData.description?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            }
            is CustomEventData -> {
                extraData.name?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                extraData.description?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            }
            else -> Unit
        }

        if (EventType.hasDuration(event.type) && event.endTime > event.time) {
            val durationMin = TimeUnit.MILLISECONDS.toMinutes(event.endTime - event.time)
            if (durationMin > 0) {
                parts.add(strings.format(CommonR.string.event_duration_minutes_format, durationMin))
            }
        }

        return parts.joinToString(separator)
    }

    private fun formatDecimal(value: Double): String {
        return DecimalFormat("0.##").format(value)
    }

    private fun mapStoolColor(color: String?): String? {
        return when (color) {
            DiaperData.COLOR_YELLOW -> strings.get(CommonR.string.stool_color_yellow)
            DiaperData.COLOR_GREEN -> strings.get(CommonR.string.stool_color_green)
            DiaperData.COLOR_BROWN -> strings.get(CommonR.string.stool_color_brown)
            DiaperData.COLOR_BLACK -> strings.get(CommonR.string.stool_color_black)
            DiaperData.COLOR_RED -> strings.get(CommonR.string.stool_color_red)
            else -> null
        }
    }

    private fun mapStoolConsistency(consistency: String?): String? {
        return when (consistency) {
            DiaperData.CONSISTENCY_WATERY,
            DiaperData.CONSISTENCY_LOOSE -> strings.get(CommonR.string.stool_consistency_watery)
            DiaperData.CONSISTENCY_NORMAL -> strings.get(CommonR.string.stool_consistency_soft)
            DiaperData.CONSISTENCY_HARD -> strings.get(CommonR.string.stool_consistency_hard)
            else -> null
        }
    }

    private fun mapUrineAmount(amount: String?): String? {
        return when (amount) {
            DiaperData.URINE_AMOUNT_LITTLE -> strings.get(CommonR.string.urine_amount_little)
            DiaperData.URINE_AMOUNT_NORMAL -> strings.get(CommonR.string.urine_amount_normal)
            DiaperData.URINE_AMOUNT_MUCH -> strings.get(CommonR.string.urine_amount_much)
            else -> null
        }
    }

    private fun mapTemperatureLocation(location: String?): String? {
        return when (location) {
            TemperatureData.LOCATION_EAR -> strings.get(CommonR.string.temperature_location_ear)
            TemperatureData.LOCATION_FOREHEAD -> strings.get(CommonR.string.temperature_location_forehead)
            TemperatureData.LOCATION_ARMPIT -> strings.get(CommonR.string.temperature_location_armpit)
            TemperatureData.LOCATION_ORAL -> strings.get(CommonR.string.temperature_location_oral)
            TemperatureData.LOCATION_RECTAL -> strings.get(CommonR.string.temperature_location_rectal)
            else -> null
        }
    }

    private fun buildDosageText(extraData: MedicineData): String? {
        val dosage = extraData.dosage?.trim().orEmpty()
        if (dosage.isBlank()) {
            return null
        }
        val unit = extraData.unit?.trim().orEmpty()
        return if (unit.isBlank()) {
            dosage
        } else {
            dosage + unit
        }
    }

    private data class EventTypeInfo(
        val iconRes: Int,
        val colorRes: Int,
        val bgColorRes: Int,
        val title: String
    )
}
