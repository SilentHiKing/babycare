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
import com.zero.babycare.statistics.model.TimelineEditTarget
import com.zero.babycare.statistics.model.TimelineUiItem
import com.zero.common.R as CommonR

/**
 * 时间轴多类型适配器。业务解析已前移到 StatisticsTimelineMapper，
 * 这里只根据 TimelineUiItem 绑定视图和转发点击事件。
 */
class TimelineAdapter(
    private val onItemClick: (TimelineUiItem) -> Unit
) : BaseMultiItemAdapter<TimelineUiItem>() {

    private var roundBottom = true

    init {
        addItemType(VIEW_TYPE_FEEDING, FeedingItemType())
        addItemType(VIEW_TYPE_SLEEP, SleepItemType())
        addItemType(VIEW_TYPE_EVENT, EventItemType())

        onItemViewType { position, list ->
            when (list[position].editTarget) {
                is TimelineEditTarget.Feeding -> VIEW_TYPE_FEEDING
                is TimelineEditTarget.Sleep -> VIEW_TYPE_SLEEP
                is TimelineEditTarget.Event -> VIEW_TYPE_EVENT
            }
        }
    }

    inner class FeedingItemType : OnMultiItem<TimelineUiItem, QuickViewHolder>() {
        override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
            val binding = ItemTimelineFeedingBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            return QuickViewHolder(binding.root)
        }

        override fun onBind(holder: QuickViewHolder, position: Int, item: TimelineUiItem?) {
            item ?: return
            val binding = ItemTimelineFeedingBinding.bind(holder.itemView)
            val ctx = holder.itemView.context

            with(binding) {
                applyTimelineBackground(root, position)
                updateTimelineLineState(vTimelineLineTop, vTimelineLineBottom, position)
                bindCommonEventChrome(
                    context = ctx,
                    icon = ivIcon,
                    dot = vTimelineDot,
                    item = item
                )
                tvTime.text = item.timeText
                tvTitle.text = item.titleText
                bindTextOrGone(tvDetail, item.detailText)
                bindTextOrGone(tvNote, item.noteText)
                root.setOnClickListener { onItemClick(item) }
            }
        }
    }

    inner class SleepItemType : OnMultiItem<TimelineUiItem, QuickViewHolder>() {
        override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
            val binding = ItemTimelineSleepBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            return QuickViewHolder(binding.root)
        }

        override fun onBind(holder: QuickViewHolder, position: Int, item: TimelineUiItem?) {
            item ?: return
            val binding = ItemTimelineSleepBinding.bind(holder.itemView)
            val ctx = holder.itemView.context

            with(binding) {
                applyTimelineBackground(root, position)
                updateTimelineLineState(vTimelineLineTop, vTimelineLineBottom, position)
                bindCommonEventChrome(
                    context = ctx,
                    icon = ivIcon,
                    dot = vTimelineDot,
                    item = item
                )
                tvStartTime.text = item.timeText
                tvTitle.text = item.titleText
                tvDuration.text = item.detailText
                tvDuration.setTextColor(ContextCompat.getColor(ctx, item.colorResId))
                bindTextOrGone(tvEndTime, item.endTimeText)
                bindTextOrGone(tvNote, item.noteText)
                root.setOnClickListener { onItemClick(item) }
            }
        }
    }

    inner class EventItemType : OnMultiItem<TimelineUiItem, QuickViewHolder>() {
        override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): QuickViewHolder {
            val binding = ItemTimelineEventBinding.inflate(
                LayoutInflater.from(context),
                parent,
                false
            )
            return QuickViewHolder(binding.root)
        }

        override fun onBind(holder: QuickViewHolder, position: Int, item: TimelineUiItem?) {
            item ?: return
            val binding = ItemTimelineEventBinding.bind(holder.itemView)
            val ctx = holder.itemView.context

            with(binding) {
                applyTimelineBackground(root, position)
                updateTimelineLineState(vTimelineLineTop, vTimelineLineBottom, position)
                bindCommonEventChrome(
                    context = ctx,
                    icon = ivIcon,
                    dot = vTimelineDot,
                    item = item
                )
                tvTime.text = item.timeText
                tvTitle.text = item.titleText
                bindTextOrGone(tvDetail, item.detailText)
                bindTextOrGone(tvNote, item.noteText)
                root.setOnClickListener { onItemClick(item) }
            }
        }
    }

    private fun bindCommonEventChrome(
        context: Context,
        icon: android.widget.ImageView,
        dot: View,
        item: TimelineUiItem
    ) {
        icon.setImageResource(item.iconResId)
        icon.setColorFilter(ContextCompat.getColor(context, item.colorResId))
        icon.backgroundTintList = ContextCompat.getColorStateList(context, item.surfaceColorResId)
        dot.backgroundTintList = ContextCompat.getColorStateList(context, item.colorResId)
    }

    private fun bindTextOrGone(textView: android.widget.TextView, text: String?) {
        if (text.isNullOrBlank()) {
            textView.visibility = View.GONE
        } else {
            textView.visibility = View.VISIBLE
            textView.text = text
        }
    }

    private fun updateTimelineLineState(lineTop: View, lineBottom: View, position: Int) {
        val isFirst = position == 0
        val isLast = position == itemCount - 1
        lineTop.visibility = if (isFirst) View.INVISIBLE else View.VISIBLE
        lineBottom.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
    }

    private fun applyTimelineBackground(view: View, position: Int) {
        val isLast = position == itemCount - 1
        if (roundBottom && isLast) {
            view.setBackgroundResource(CommonR.drawable.bg_r16_bottom_surface_group_control_border)
        } else {
            view.setBackgroundResource(CommonR.drawable.bg_surface_group_sides_control_border)
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

    private companion object {
        const val VIEW_TYPE_FEEDING = 1
        const val VIEW_TYPE_SLEEP = 2
        const val VIEW_TYPE_EVENT = 3
    }
}
