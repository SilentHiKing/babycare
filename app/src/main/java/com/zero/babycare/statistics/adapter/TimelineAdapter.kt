package com.zero.babycare.statistics.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.chad.library.adapter4.BaseMultiItemAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.zero.babycare.databinding.ItemTimelineEventBinding
import com.zero.babycare.databinding.ItemTimelineFeedingBinding
import com.zero.babycare.databinding.ItemTimelineSleepBinding
import com.zero.babycare.statistics.model.TimelineItem
import com.zero.babydata.entity.CustomEventData
import com.zero.babydata.entity.DiaperData
import com.zero.babydata.entity.EventExtraData
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingType
import com.zero.babydata.entity.GrowthData
import com.zero.babydata.entity.MedicineData
import com.zero.babydata.entity.MilestoneData
import com.zero.babydata.entity.SymptomData
import com.zero.babydata.entity.TemperatureData
import com.zero.babydata.entity.VaccineData
import com.zero.common.ext.getThemeColor
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import com.zero.common.R as CommonR
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 时间轴多类型适配器
 * 基于 baby_recyclerview 的 BaseMultiItemAdapter
 */
class TimelineAdapter(
    private val onItemClick: (TimelineItem) -> Unit
) : BaseMultiItemAdapter<TimelineItem>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var roundBottom = true

    init {
        // 喂养类型
        addItemType(TimelineItem.VIEW_TYPE_FEEDING, FeedingItemType())
        // 睡眠类型
        addItemType(TimelineItem.VIEW_TYPE_SLEEP, SleepItemType())
        // 事件类型
        addItemType(TimelineItem.VIEW_TYPE_EVENT, EventItemType())

        // 设置 ViewType 判断逻辑
        onItemViewType { position, list ->
            list[position].getViewType()
        }
    }

    /**
     * 喂养类型 ViewHolder
     */
    inner class FeedingItemType : OnMultiItem<TimelineItem, QuickViewHolder>() {
        override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
            val binding = ItemTimelineFeedingBinding.inflate(
                LayoutInflater.from(context), parent, false
            )
            return QuickViewHolder(binding.root)
        }

        override fun onBind(holder: QuickViewHolder, position: Int, item: TimelineItem?) {
            val feeding = (item as? TimelineItem.Feeding)?.record ?: return
            val binding = ItemTimelineFeedingBinding.bind(holder.itemView)
            val ctx = holder.itemView.context

            with(binding) {
                applyTimelineBackground(root, position)
                updateTimelineLineState(vTimelineLineTop, vTimelineLineBottom, position)

                // 时间
                tvTime.text = timeFormat.format(Date(feeding.feedingStart))
                
                // 类型和图标
                val feedingType = feeding.getFeedingTypeEnum()
                tvTitle.text = getFeedingTypeName(ctx, feedingType)
                
                // 设置图标和颜色
                val (iconRes, colorRes, bgColorRes) = getFeedingTypeResources(feedingType)
                ivIcon.setImageResource(iconRes)
                ivIcon.setColorFilter(ContextCompat.getColor(ctx, colorRes))
                ivIcon.backgroundTintList = ContextCompat.getColorStateList(ctx, bgColorRes)
                vTimelineDot.backgroundTintList = ContextCompat.getColorStateList(ctx, colorRes)
                
                // 详情
                tvDetail.text = buildFeedingDetail(ctx, feeding, feedingType)
                
                // 备注
                if (feeding.note.isNotBlank()) {
                    tvNote.visibility = View.VISIBLE
                    tvNote.text = feeding.note
                } else {
                    tvNote.visibility = View.GONE
                }

                root.setOnClickListener { onItemClick(item) }
            }
        }

        private fun getFeedingTypeName(context: Context, type: FeedingType): String {
            val typeName = context.getString(
                when (type) {
                    FeedingType.BREAST -> CommonR.string.feeding_type_breast
                    FeedingType.FORMULA -> CommonR.string.feeding_type_formula
                    FeedingType.MIXED -> CommonR.string.feeding_type_mixed
                    FeedingType.SOLID_FOOD -> CommonR.string.feeding_type_solid
                    FeedingType.OTHER -> CommonR.string.feeding_type_other
                }
            )
            return context.getString(CommonR.string.feeding_type_title_format, typeName)
        }

        private fun getFeedingTypeResources(type: FeedingType): Triple<Int, Int, Int> {
            return when (type) {
                FeedingType.BREAST -> Triple(
                    com.zero.common.R.drawable.ic_feeding,
                    com.zero.common.R.color.feeding_primary,
                    com.zero.common.R.color.feeding_bg
                )
                FeedingType.FORMULA -> Triple(
                    com.zero.common.R.drawable.ic_feeding,
                    com.zero.common.R.color.ice_blue90,
                    com.zero.common.R.color.ice_blue00
                )
                FeedingType.MIXED -> Triple(
                    com.zero.common.R.drawable.ic_feeding,
                    com.zero.common.R.color.purple100,
                    com.zero.common.R.color.purple00
                )
                FeedingType.SOLID_FOOD -> Triple(
                    com.zero.common.R.drawable.ic_event_growth,
                    com.zero.common.R.color.event_growth,
                    com.zero.common.R.color.event_growth_light
                )
                FeedingType.OTHER -> Triple(
                    com.zero.common.R.drawable.ic_feeding,
                    com.zero.common.R.color.event_other,
                    com.zero.common.R.color.event_other_light
                )
            }
        }

        private fun buildFeedingDetail(
            context: Context,
            record: com.zero.babydata.entity.FeedingRecord,
            type: FeedingType
        ): String {
            val parts = mutableListOf<String>()
            val separator = context.getString(CommonR.string.list_separator_dot)
            
            when (type) {
                FeedingType.BREAST -> {
                    val leftMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDurationBreastLeft)
                    val rightMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDurationBreastRight)
                    if (leftMin > 0) {
                        val label = context.getString(CommonR.string.left_breast)
                        parts.add(context.getString(CommonR.string.feeding_breast_side_minutes_format, label, leftMin))
                    }
                    if (rightMin > 0) {
                        val label = context.getString(CommonR.string.right_breast)
                        parts.add(context.getString(CommonR.string.feeding_breast_side_minutes_format, label, rightMin))
                    }
                    val totalMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDuration)
                    if (totalMin > 0) {
                        parts.add(context.getString(CommonR.string.feeding_total_minutes_format, totalMin))
                    }
                }
                FeedingType.FORMULA, FeedingType.MIXED -> {
                    record.feedingAmount?.let { amount ->
                        if (amount > 0) {
                            val unitValue = UnitConfig.getFeedingUnit()
                            val unitLabel = context.getString(UnitConfig.getFeedingUnitLabelResId())
                            val displayValue = UnitConverter.feedingToDisplay(amount, unitValue)
                            val displayText = UnitConverter.formatFeedingAmount(displayValue, unitValue)
                            parts.add(context.getString(CommonR.string.feeding_amount_unit_format, displayText, unitLabel))
                        }
                    }
                    val totalMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDuration)
                    if (totalMin > 0) {
                        parts.add(context.getString(CommonR.string.min_format, totalMin))
                    }
                }
                FeedingType.SOLID_FOOD -> {
                    record.foodName?.let { if (it.isNotBlank()) parts.add(it) }
                    record.feedingAmount?.let { amount ->
                        if (amount > 0) {
                            val unit = context.getString(CommonR.string.unit_g_abbr)
                            parts.add(context.getString(CommonR.string.feeding_amount_unit_format, amount, unit))
                        }
                    }
                }
                FeedingType.OTHER -> {
                    record.foodName?.let { if (it.isNotBlank()) parts.add(it) }
                    val totalMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDuration)
                    if (totalMin > 0) {
                        parts.add(context.getString(CommonR.string.min_format, totalMin))
                    }
                }
            }
            
            return parts.joinToString(separator)
        }
    }

    /**
     * 睡眠类型 ViewHolder
     */
    inner class SleepItemType : OnMultiItem<TimelineItem, QuickViewHolder>() {
        override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
            val binding = ItemTimelineSleepBinding.inflate(
                LayoutInflater.from(context), parent, false
            )
            return QuickViewHolder(binding.root)
        }

        override fun onBind(holder: QuickViewHolder, position: Int, item: TimelineItem?) {
            val sleep = (item as? TimelineItem.Sleep)?.record ?: return
            val binding = ItemTimelineSleepBinding.bind(holder.itemView)

            with(binding) {
                applyTimelineBackground(root, position)
                updateTimelineLineState(vTimelineLineTop, vTimelineLineBottom, position)

                // 开始时间
                tvStartTime.text = timeFormat.format(Date(sleep.sleepStart))
                
                // 结束时间
                if (sleep.sleepEnd > 0) {
                    tvEndTime.visibility = View.VISIBLE
                    tvEndTime.text = holder.itemView.context.getString(
                        CommonR.string.timeline_end_time_format,
                        timeFormat.format(Date(sleep.sleepEnd))
                    )
                } else {
                    tvEndTime.visibility = View.GONE
                }
                
                // 时长
                val durationMin = TimeUnit.MILLISECONDS.toMinutes(sleep.sleepDuration)
                val hours = durationMin / 60
                val minutes = durationMin % 60
                val ctx = holder.itemView.context
                tvDuration.text = when {
                    hours > 0 && minutes > 0 -> ctx.getString(
                        CommonR.string.sleep_duration_format,
                        hours,
                        minutes
                    )
                    hours > 0 -> ctx.getString(CommonR.string.sleep_duration_hours, hours)
                    minutes > 0 -> ctx.getString(CommonR.string.sleep_duration_minutes, minutes)
                    else -> ""
                }
                
                // 备注
                if (sleep.note.isNotBlank()) {
                    tvNote.visibility = View.VISIBLE
                    tvNote.text = sleep.note
                } else {
                    tvNote.visibility = View.GONE
                }

                root.setOnClickListener { onItemClick(item) }
            }
        }
    }

    /**
     * 事件类型 ViewHolder
     */
    inner class EventItemType : OnMultiItem<TimelineItem, QuickViewHolder>() {
        override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
            val binding = ItemTimelineEventBinding.inflate(
                LayoutInflater.from(context), parent, false
            )
            return QuickViewHolder(binding.root)
        }

        override fun onBind(holder: QuickViewHolder, position: Int, item: TimelineItem?) {
            val event = (item as? TimelineItem.Event)?.record ?: return
            val binding = ItemTimelineEventBinding.bind(holder.itemView)
            val ctx = holder.itemView.context

            with(binding) {
                applyTimelineBackground(root, position)
                updateTimelineLineState(vTimelineLineTop, vTimelineLineBottom, position)

                // 时间
                tvTime.text = timeFormat.format(Date(event.time))
                
                // 类型和图标
                val (iconRes, colorRes, bgColorRes, title) = getEventTypeResources(ctx, event.type)
                tvTitle.text = title
                ivIcon.setImageResource(iconRes)
                ivIcon.setColorFilter(ContextCompat.getColor(ctx, colorRes))
                ivIcon.backgroundTintList = ContextCompat.getColorStateList(ctx, bgColorRes)
                vTimelineDot.backgroundTintList = ContextCompat.getColorStateList(ctx, colorRes)
                
                // 详情（根据事件类型显示不同内容）
                val detail = buildEventDetail(ctx, event)
                if (detail.isNotBlank()) {
                    tvDetail.visibility = View.VISIBLE
                    tvDetail.text = detail
                } else {
                    tvDetail.visibility = View.GONE
                }
                
                // 备注
                if (event.note.isNotBlank()) {
                    tvNote.visibility = View.VISIBLE
                    tvNote.text = event.note
                } else {
                    tvNote.visibility = View.GONE
                }

                root.setOnClickListener { onItemClick(item) }
            }
        }

        private fun getEventTypeResources(context: Context, type: Int): EventTypeInfo {
            return when {
                // 排泄类
                type == EventType.DIAPER_WET -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_diaper_wet,
                    com.zero.common.R.color.event_diaper,
                    com.zero.common.R.color.event_diaper_light,
                    context.getString(CommonR.string.event_diaper_wet_title)
                )
                type == EventType.DIAPER_DIRTY -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_diaper_dirty,
                    com.zero.common.R.color.event_diaper,
                    com.zero.common.R.color.event_diaper_light,
                    context.getString(CommonR.string.event_diaper_dirty_title)
                )
                type == EventType.DIAPER_MIXED -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_diaper_mixed,
                    com.zero.common.R.color.event_diaper,
                    com.zero.common.R.color.event_diaper_light,
                    context.getString(CommonR.string.event_diaper_mixed_title)
                )
                type == EventType.DIAPER_DRY -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_diaper_dry,
                    com.zero.common.R.color.event_diaper,
                    com.zero.common.R.color.event_diaper_light,
                    context.getString(CommonR.string.event_diaper_dry_title)
                )
                // 健康类
                type == EventType.HEALTH_TEMPERATURE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_temperature,
                    com.zero.common.R.color.event_health,
                    com.zero.common.R.color.event_health_light,
                    context.getString(
                        CommonR.string.event_measure_title_format,
                        context.getString(CommonR.string.event_health_temperature)
                    )
                )
                type == EventType.HEALTH_MEDICINE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_medicine,
                    com.zero.common.R.color.event_health,
                    com.zero.common.R.color.event_health_light,
                    context.getString(CommonR.string.event_health_medicine)
                )
                type == EventType.HEALTH_VACCINE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_vaccine,
                    com.zero.common.R.color.event_health,
                    com.zero.common.R.color.event_health_light,
                    context.getString(CommonR.string.event_vaccine_title)
                )
                type == EventType.HEALTH_DOCTOR -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_doctor,
                    com.zero.common.R.color.event_health,
                    com.zero.common.R.color.event_health_light,
                    context.getString(CommonR.string.event_doctor_title)
                )
                type == EventType.HEALTH_SYMPTOM -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_symptom,
                    com.zero.common.R.color.event_health,
                    com.zero.common.R.color.event_health_light,
                    context.getString(
                        CommonR.string.event_record_title_format,
                        context.getString(CommonR.string.event_health_symptom)
                    )
                )
                // 生长类
                type == EventType.GROWTH_WEIGHT -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_weight,
                    com.zero.common.R.color.event_growth,
                    com.zero.common.R.color.event_growth_light,
                    context.getString(
                        CommonR.string.event_measure_title_format,
                        context.getString(CommonR.string.event_growth_weight)
                    )
                )
                type == EventType.GROWTH_HEIGHT -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_height,
                    com.zero.common.R.color.event_growth,
                    com.zero.common.R.color.event_growth_light,
                    context.getString(
                        CommonR.string.event_measure_title_format,
                        context.getString(CommonR.string.event_growth_height)
                    )
                )
                type == EventType.GROWTH_HEAD -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_head,
                    com.zero.common.R.color.event_growth,
                    com.zero.common.R.color.event_growth_light,
                    context.getString(
                        CommonR.string.event_measure_title_format,
                        context.getString(CommonR.string.event_growth_head)
                    )
                )
                // 里程碑类
                EventType.isMilestone(type) -> EventTypeInfo(
                    getMilestoneIcon(type),
                    com.zero.common.R.color.event_milestone,
                    com.zero.common.R.color.event_milestone_light,
                    context.getString(
                        CommonR.string.event_milestone_title_format,
                        getMilestoneTitle(context, type)
                    )
                )
                // 护理类
                type == EventType.CARE_BATH -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_bath,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    context.getString(CommonR.string.event_care_bath)
                )
                type == EventType.CARE_NAIL -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_nail,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    context.getString(CommonR.string.event_care_nail)
                )
                type == EventType.CARE_SKINCARE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_skincare,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    context.getString(CommonR.string.event_care_skincare)
                )
                type == EventType.CARE_MASSAGE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_massage,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    context.getString(CommonR.string.event_care_massage)
                )
                type == EventType.CARE_NOSE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_nose,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    context.getString(CommonR.string.event_care_nose)
                )
                type == EventType.CARE_EAR -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_ear,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    context.getString(CommonR.string.event_care_ear)
                )
                // 活动类
                type == EventType.ACTIVITY_OUTDOOR -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_outdoor,
                    com.zero.common.R.color.event_activity,
                    com.zero.common.R.color.event_activity_light,
                    context.getString(CommonR.string.event_activity_outdoor)
                )
                type == EventType.ACTIVITY_TUMMY_TIME -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_tummy,
                    com.zero.common.R.color.event_activity,
                    com.zero.common.R.color.event_activity_light,
                    context.getString(CommonR.string.event_activity_tummy)
                )
                type == EventType.ACTIVITY_SWIMMING -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_swimming,
                    com.zero.common.R.color.event_activity,
                    com.zero.common.R.color.event_activity_light,
                    context.getString(CommonR.string.event_activity_swimming)
                )
                type == EventType.ACTIVITY_PLAY -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_play,
                    com.zero.common.R.color.event_activity,
                    com.zero.common.R.color.event_activity_light,
                    context.getString(CommonR.string.event_activity_play)
                )
                // 其他类
                type == EventType.OTHER_BURP -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_burp,
                    com.zero.common.R.color.event_other,
                    com.zero.common.R.color.event_other_light,
                    context.getString(CommonR.string.event_other_burp)
                )
                type == EventType.OTHER_CRY -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_cry,
                    com.zero.common.R.color.event_other,
                    com.zero.common.R.color.event_other_light,
                    context.getString(CommonR.string.event_other_cry)
                )
                type == EventType.OTHER_SPIT_UP -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_spit_up,
                    com.zero.common.R.color.event_other,
                    com.zero.common.R.color.event_other_light,
                    context.getString(CommonR.string.event_other_spit_up)
                )
                else -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_custom,
                    com.zero.common.R.color.event_other,
                    com.zero.common.R.color.event_other_light,
                    context.getString(CommonR.string.event_other_title)
                )
            }
        }

        private fun getMilestoneIcon(type: Int): Int {
            return when (type) {
                EventType.MILESTONE_ROLL -> com.zero.common.R.drawable.ic_event_roll
                EventType.MILESTONE_SIT -> com.zero.common.R.drawable.ic_event_sit
                EventType.MILESTONE_CRAWL -> com.zero.common.R.drawable.ic_event_crawl
                EventType.MILESTONE_STAND -> com.zero.common.R.drawable.ic_event_stand
                EventType.MILESTONE_WALK -> com.zero.common.R.drawable.ic_event_walk
                EventType.MILESTONE_FIRST_WORD -> com.zero.common.R.drawable.ic_event_talk
                EventType.MILESTONE_FIRST_TOOTH -> com.zero.common.R.drawable.ic_event_tooth
                else -> com.zero.common.R.drawable.ic_event_milestone_custom
            }
        }

        private fun getMilestoneTitle(context: Context, type: Int): String {
            return when (type) {
                EventType.MILESTONE_ROLL -> context.getString(CommonR.string.event_milestone_roll)
                EventType.MILESTONE_SIT -> context.getString(CommonR.string.event_milestone_sit)
                EventType.MILESTONE_CRAWL -> context.getString(CommonR.string.event_milestone_crawl)
                EventType.MILESTONE_STAND -> context.getString(CommonR.string.event_milestone_stand)
                EventType.MILESTONE_WALK -> context.getString(CommonR.string.event_milestone_walk)
                EventType.MILESTONE_FIRST_WORD -> context.getString(CommonR.string.event_milestone_first_word)
                EventType.MILESTONE_FIRST_TOOTH -> context.getString(CommonR.string.event_milestone_first_tooth)
                else -> context.getString(CommonR.string.event_milestone_custom)
            }
        }

        private fun buildEventDetail(context: Context, event: com.zero.babydata.entity.EventRecord): String {
            val parts = mutableListOf<String>()
            val separator = context.getString(CommonR.string.list_separator_dot)
            val extraData = EventExtraData.parse(event.type, event.extraData)

            when (extraData) {
                is TemperatureData -> {
                    val valueText = formatDecimal(extraData.value)
                    val unit = context.getString(CommonR.string.temperature_unit)
                    parts.add(valueText + unit)
                    mapTemperatureLocation(context, extraData.location)?.let { parts.add(it) }
                }
                is GrowthData -> {
                    val targetUnit = when (event.type) {
                        EventType.GROWTH_WEIGHT -> UnitConfig.getWeightUnit()
                        EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD -> UnitConfig.getHeightUnit()
                        else -> UnitConfig.getHeightUnit()
                    }
                    val targetUnitLabel = when (event.type) {
                        EventType.GROWTH_WEIGHT -> context.getString(UnitConfig.getWeightUnitLabelResId())
                        EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD -> {
                            context.getString(UnitConfig.getHeightUnitLabelResId())
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
                    mapStoolColor(context, extraData.color)?.let { parts.add(it) }
                    mapStoolConsistency(context, extraData.consistency)?.let { parts.add(it) }
                    val urineAmountValue = mapUrineAmount(context, extraData.urineAmount)
                        ?: if (extraData.abnormal) {
                            context.getString(CommonR.string.urine_amount_much)
                        } else {
                            null
                        }
                    urineAmountValue?.let { value ->
                        val urineAmountLabel = context.getString(CommonR.string.urine_amount)
                        parts.add(context.getString(CommonR.string.event_label_value_format, urineAmountLabel, value))
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
                        parts.add(context.getString(CommonR.string.vaccine_dose_format, dose))
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
                    parts.add(context.getString(CommonR.string.event_duration_minutes_format, durationMin))
                }
            }

            return parts.joinToString(separator)
        }

        private fun formatDecimal(value: Double): String {
            return DecimalFormat("0.##").format(value)
        }

        private fun getGrowthUnit(context: Context, type: Int): String {
            return when (type) {
                EventType.GROWTH_WEIGHT -> context.getString(CommonR.string.weight_unit)
                EventType.GROWTH_HEIGHT, EventType.GROWTH_HEAD -> context.getString(CommonR.string.height_unit)
                else -> ""
            }
        }

        private fun mapStoolColor(context: Context, color: String?): String? {
            return when (color) {
                DiaperData.COLOR_YELLOW -> context.getString(CommonR.string.stool_color_yellow)
                DiaperData.COLOR_GREEN -> context.getString(CommonR.string.stool_color_green)
                DiaperData.COLOR_BROWN -> context.getString(CommonR.string.stool_color_brown)
                DiaperData.COLOR_BLACK -> context.getString(CommonR.string.stool_color_black)
                DiaperData.COLOR_RED -> context.getString(CommonR.string.stool_color_red)
                else -> null
            }
        }

        private fun mapStoolConsistency(context: Context, consistency: String?): String? {
            return when (consistency) {
                DiaperData.CONSISTENCY_WATERY, DiaperData.CONSISTENCY_LOOSE -> {
                    context.getString(CommonR.string.stool_consistency_watery)
                }
                DiaperData.CONSISTENCY_NORMAL -> {
                    context.getString(CommonR.string.stool_consistency_soft)
                }
                DiaperData.CONSISTENCY_HARD -> {
                    context.getString(CommonR.string.stool_consistency_hard)
                }
                else -> null
            }
        }

        private fun mapUrineAmount(context: Context, amount: String?): String? {
            return when (amount) {
                DiaperData.URINE_AMOUNT_LITTLE -> context.getString(CommonR.string.urine_amount_little)
                DiaperData.URINE_AMOUNT_NORMAL -> context.getString(CommonR.string.urine_amount_normal)
                DiaperData.URINE_AMOUNT_MUCH -> context.getString(CommonR.string.urine_amount_much)
                else -> null
            }
        }

        private fun mapTemperatureLocation(context: Context, location: String?): String? {
            return when (location) {
                TemperatureData.LOCATION_EAR -> context.getString(CommonR.string.temperature_location_ear)
                TemperatureData.LOCATION_FOREHEAD -> context.getString(CommonR.string.temperature_location_forehead)
                TemperatureData.LOCATION_ARMPIT -> context.getString(CommonR.string.temperature_location_armpit)
                TemperatureData.LOCATION_ORAL -> context.getString(CommonR.string.temperature_location_oral)
                TemperatureData.LOCATION_RECTAL -> context.getString(CommonR.string.temperature_location_rectal)
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
    }

    /**
     * 事件类型信息
     */
    data class EventTypeInfo(
        val iconRes: Int,
        val colorRes: Int,
        val bgColorRes: Int,
        val title: String
    )

    private fun updateTimelineLineState(lineTop: View, lineBottom: View, position: Int) {
        val isFirst = position == 0
        val isLast = position == itemCount - 1
        lineTop.visibility = if (isFirst) View.INVISIBLE else View.VISIBLE
        lineBottom.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
    }

    private fun applyTimelineBackground(view: View, position: Int) {
        val isLast = position == itemCount - 1
        if (roundBottom && isLast) {
            view.setBackgroundResource(CommonR.drawable.bg_r12_bottom_secondary_bg_default)
        } else {
            view.setBackgroundColor(view.getThemeColor(CommonR.attr.secondary_bg_default))
        }
    }

    fun setRoundBottom(enabled: Boolean) {
        if (roundBottom == enabled) {
            return
        }
        roundBottom = enabled
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }
}
