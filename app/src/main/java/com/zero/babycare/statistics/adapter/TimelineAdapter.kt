package com.zero.babycare.statistics.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseMultiItemAdapter
import com.chad.library.adapter4.viewholder.QuickViewHolder
import com.zero.babycare.databinding.ItemTimelineEventBinding
import com.zero.babycare.databinding.ItemTimelineFeedingBinding
import com.zero.babycare.databinding.ItemTimelineSleepBinding
import com.zero.babycare.statistics.model.TimelineItem
import com.zero.babydata.entity.EventType
import com.zero.babydata.entity.FeedingType
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
                // 时间
                tvTime.text = timeFormat.format(Date(feeding.feedingStart))
                
                // 类型和图标
                val feedingType = feeding.getFeedingTypeEnum()
                tvTitle.text = getFeedingTypeName(feedingType)
                
                // 设置图标和颜色
                val (iconRes, colorRes, bgColorRes) = getFeedingTypeResources(feedingType)
                ivIcon.setImageResource(iconRes)
                ivIcon.setColorFilter(ContextCompat.getColor(ctx, colorRes))
                ivIcon.backgroundTintList = ContextCompat.getColorStateList(ctx, bgColorRes)
                vTimelineDot.backgroundTintList = ContextCompat.getColorStateList(ctx, colorRes)
                
                // 详情
                tvDetail.text = buildFeedingDetail(feeding, feedingType)
                
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

        private fun getFeedingTypeName(type: FeedingType): String {
            return when (type) {
                FeedingType.BREAST -> "母乳喂养"
                FeedingType.FORMULA -> "配方奶"
                FeedingType.MIXED -> "混合喂养"
                FeedingType.SOLID_FOOD -> "辅食"
                FeedingType.OTHER -> "其他"
            }
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

        private fun buildFeedingDetail(record: com.zero.babydata.entity.FeedingRecord, type: FeedingType): String {
            val parts = mutableListOf<String>()
            
            when (type) {
                FeedingType.BREAST -> {
                    val leftMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDurationBreastLeft)
                    val rightMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDurationBreastRight)
                    if (leftMin > 0) parts.add("左侧${leftMin}分")
                    if (rightMin > 0) parts.add("右侧${rightMin}分")
                    val totalMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDuration)
                    if (totalMin > 0) parts.add("共${totalMin}分钟")
                }
                FeedingType.FORMULA, FeedingType.MIXED -> {
                    record.feedingAmount?.let { if (it > 0) parts.add("${it}ml") }
                    val totalMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDuration)
                    if (totalMin > 0) parts.add("${totalMin}分钟")
                }
                FeedingType.SOLID_FOOD -> {
                    record.foodName?.let { if (it.isNotBlank()) parts.add(it) }
                    record.feedingAmount?.let { if (it > 0) parts.add("${it}g") }
                }
                FeedingType.OTHER -> {
                    record.foodName?.let { if (it.isNotBlank()) parts.add(it) }
                    val totalMin = TimeUnit.MILLISECONDS.toMinutes(record.feedingDuration)
                    if (totalMin > 0) parts.add("${totalMin}分钟")
                }
            }
            
            return parts.joinToString(" · ")
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
                // 开始时间
                tvStartTime.text = timeFormat.format(Date(sleep.sleepStart))
                
                // 结束时间
                if (sleep.sleepEnd > 0) {
                    tvEndTime.visibility = View.VISIBLE
                    tvEndTime.text = "- ${timeFormat.format(Date(sleep.sleepEnd))}"
                } else {
                    tvEndTime.visibility = View.GONE
                }
                
                // 时长
                val durationMin = TimeUnit.MILLISECONDS.toMinutes(sleep.sleepDuration)
                val hours = durationMin / 60
                val minutes = durationMin % 60
                tvDuration.text = when {
                    hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
                    hours > 0 -> "${hours}h"
                    minutes > 0 -> "${minutes}m"
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
                // 时间
                tvTime.text = timeFormat.format(Date(event.time))
                
                // 类型和图标
                val (iconRes, colorRes, bgColorRes, title) = getEventTypeResources(event.type)
                tvTitle.text = title
                ivIcon.setImageResource(iconRes)
                ivIcon.setColorFilter(ContextCompat.getColor(ctx, colorRes))
                ivIcon.backgroundTintList = ContextCompat.getColorStateList(ctx, bgColorRes)
                vTimelineDot.backgroundTintList = ContextCompat.getColorStateList(ctx, colorRes)
                
                // 详情（根据事件类型显示不同内容）
                val detail = buildEventDetail(event)
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

        private fun getEventTypeResources(type: Int): EventTypeInfo {
            return when {
                // 排泄类
                type == EventType.DIAPER_WET -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_diaper_wet,
                    com.zero.common.R.color.event_diaper,
                    com.zero.common.R.color.event_diaper_light,
                    "换尿布（小便）"
                )
                type == EventType.DIAPER_DIRTY -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_diaper_dirty,
                    com.zero.common.R.color.event_diaper,
                    com.zero.common.R.color.event_diaper_light,
                    "换尿布（大便）"
                )
                type == EventType.DIAPER_MIXED -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_diaper_mixed,
                    com.zero.common.R.color.event_diaper,
                    com.zero.common.R.color.event_diaper_light,
                    "换尿布（混合）"
                )
                type == EventType.DIAPER_DRY -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_diaper_dry,
                    com.zero.common.R.color.event_diaper,
                    com.zero.common.R.color.event_diaper_light,
                    "换尿布（干净）"
                )
                // 健康类
                type == EventType.HEALTH_TEMPERATURE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_temperature,
                    com.zero.common.R.color.event_health,
                    com.zero.common.R.color.event_health_light,
                    "测量体温"
                )
                type == EventType.HEALTH_MEDICINE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_medicine,
                    com.zero.common.R.color.event_health,
                    com.zero.common.R.color.event_health_light,
                    "用药"
                )
                type == EventType.HEALTH_VACCINE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_vaccine,
                    com.zero.common.R.color.event_health,
                    com.zero.common.R.color.event_health_light,
                    "疫苗接种"
                )
                type == EventType.HEALTH_DOCTOR -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_doctor,
                    com.zero.common.R.color.event_health,
                    com.zero.common.R.color.event_health_light,
                    "就医检查"
                )
                type == EventType.HEALTH_SYMPTOM -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_symptom,
                    com.zero.common.R.color.event_health,
                    com.zero.common.R.color.event_health_light,
                    "症状记录"
                )
                // 生长类
                type == EventType.GROWTH_WEIGHT -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_weight,
                    com.zero.common.R.color.event_growth,
                    com.zero.common.R.color.event_growth_light,
                    "测量体重"
                )
                type == EventType.GROWTH_HEIGHT -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_height,
                    com.zero.common.R.color.event_growth,
                    com.zero.common.R.color.event_growth_light,
                    "测量身高"
                )
                type == EventType.GROWTH_HEAD -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_head,
                    com.zero.common.R.color.event_growth,
                    com.zero.common.R.color.event_growth_light,
                    "测量头围"
                )
                // 里程碑类
                EventType.isMilestone(type) -> EventTypeInfo(
                    getMilestoneIcon(type),
                    com.zero.common.R.color.event_milestone,
                    com.zero.common.R.color.event_milestone_light,
                    getMilestoneTitle(type)
                )
                // 护理类
                type == EventType.CARE_BATH -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_bath,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    "洗澡"
                )
                type == EventType.CARE_NAIL -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_nail,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    "剪指甲"
                )
                type == EventType.CARE_SKINCARE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_skincare,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    "护肤"
                )
                type == EventType.CARE_MASSAGE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_massage,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    "抚触按摩"
                )
                type == EventType.CARE_NOSE -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_nose,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    "清洁鼻腔"
                )
                type == EventType.CARE_EAR -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_ear,
                    com.zero.common.R.color.event_care,
                    com.zero.common.R.color.event_care_light,
                    "清洁耳朵"
                )
                // 活动类
                type == EventType.ACTIVITY_OUTDOOR -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_outdoor,
                    com.zero.common.R.color.event_activity,
                    com.zero.common.R.color.event_activity_light,
                    "户外活动"
                )
                type == EventType.ACTIVITY_TUMMY_TIME -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_tummy,
                    com.zero.common.R.color.event_activity,
                    com.zero.common.R.color.event_activity_light,
                    "趴趴时间"
                )
                type == EventType.ACTIVITY_SWIMMING -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_swimming,
                    com.zero.common.R.color.event_activity,
                    com.zero.common.R.color.event_activity_light,
                    "游泳"
                )
                type == EventType.ACTIVITY_PLAY -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_play,
                    com.zero.common.R.color.event_activity,
                    com.zero.common.R.color.event_activity_light,
                    "亲子游戏"
                )
                // 其他类
                type == EventType.OTHER_BURP -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_burp,
                    com.zero.common.R.color.event_other,
                    com.zero.common.R.color.event_other_light,
                    "拍嗝"
                )
                type == EventType.OTHER_CRY -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_cry,
                    com.zero.common.R.color.event_other,
                    com.zero.common.R.color.event_other_light,
                    "哭闹"
                )
                type == EventType.OTHER_SPIT_UP -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_spit_up,
                    com.zero.common.R.color.event_other,
                    com.zero.common.R.color.event_other_light,
                    "吐奶"
                )
                else -> EventTypeInfo(
                    com.zero.common.R.drawable.ic_event_custom,
                    com.zero.common.R.color.event_other,
                    com.zero.common.R.color.event_other_light,
                    "其他事件"
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

        private fun getMilestoneTitle(type: Int): String {
            return when (type) {
                EventType.MILESTONE_ROLL -> "里程碑：翻身"
                EventType.MILESTONE_SIT -> "里程碑：独坐"
                EventType.MILESTONE_CRAWL -> "里程碑：爬行"
                EventType.MILESTONE_STAND -> "里程碑：站立"
                EventType.MILESTONE_WALK -> "里程碑：行走"
                EventType.MILESTONE_FIRST_WORD -> "里程碑：第一个词"
                EventType.MILESTONE_FIRST_TOOTH -> "里程碑：第一颗牙"
                else -> "里程碑"
            }
        }

        private fun buildEventDetail(event: com.zero.babydata.entity.EventRecord): String {
            // 可以根据 extraData 解析更多详情
            return when {
                EventType.hasDuration(event.type) && event.endTime > 0 -> {
                    val durationMin = TimeUnit.MILLISECONDS.toMinutes(event.endTime - event.time)
                    "时长 ${durationMin}分钟"
                }
                else -> ""
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
}

