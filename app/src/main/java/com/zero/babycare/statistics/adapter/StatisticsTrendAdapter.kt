package com.zero.babycare.statistics.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseSingleItemAdapter
import com.zero.babycare.databinding.ItemStatisticsTrendBinding
import com.zero.babycare.databinding.ItemStatisticsTrendCardBinding
import com.zero.babycare.statistics.model.TrendOverview
import com.zero.babycare.statistics.model.TrendPeriod
import com.zero.babycare.statistics.model.TrendPeriodSummary
import java.time.format.DateTimeFormatter

/**
 * 周/月/年趋势适配器
 */
class StatisticsTrendAdapter :
    BaseSingleItemAdapter<TrendOverview, StatisticsTrendAdapter.TrendViewHolder>() {

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): TrendViewHolder {
        val binding = ItemStatisticsTrendBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TrendViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrendViewHolder, item: TrendOverview?) {
        holder.bind(item)
    }

    fun updateTrend(overview: TrendOverview?) {
        item = overview
    }

    inner class TrendViewHolder(
        private val binding: ItemStatisticsTrendBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val formatter = DateTimeFormatter.ofPattern("MM.dd")

        fun bind(overview: TrendOverview?) {
            val list = overview?.summaries.orEmpty()
            bindCard(binding.cardTrendWeek, list.firstOrNull { it.period == TrendPeriod.WEEK })
            bindCard(binding.cardTrendMonth, list.firstOrNull { it.period == TrendPeriod.MONTH })
            bindCard(binding.cardTrendYear, list.firstOrNull { it.period == TrendPeriod.YEAR })
        }

        private fun bindCard(card: ItemStatisticsTrendCardBinding, summary: TrendPeriodSummary?) {
            val context = card.root.context
            if (summary == null) {
                bindEmpty(card, context)
                return
            }

            card.tvTrendPeriod.text = context.getString(
                when (summary.period) {
                    TrendPeriod.WEEK -> com.zero.common.R.string.statistics_trend_week
                    TrendPeriod.MONTH -> com.zero.common.R.string.statistics_trend_month
                    TrendPeriod.YEAR -> com.zero.common.R.string.statistics_trend_year
                }
            )

            val rangeText = context.getString(
                com.zero.common.R.string.statistics_trend_range_format,
                summary.startDate.format(formatter),
                summary.endDate.format(formatter)
            )
            card.tvTrendRange.text = rangeText

            val feedingDuration = formatMinutes(context, summary.feedingTotalMinutes)
            val sleepDuration = formatMinutes(context, summary.sleepTotalMinutes)

            card.tvTrendFeedingValue.text = context.getString(
                com.zero.common.R.string.statistics_trend_feeding_value_format,
                summary.feedingCount,
                feedingDuration
            )
            card.tvTrendSleepValue.text = context.getString(
                com.zero.common.R.string.statistics_trend_sleep_value_format,
                summary.sleepCount,
                sleepDuration
            )
            card.tvTrendDiaperValue.text = context.getString(
                com.zero.common.R.string.statistics_trend_count_format,
                summary.diaperCount
            )
            card.tvTrendEventValue.text = context.getString(
                com.zero.common.R.string.statistics_trend_count_format,
                summary.otherEventCount
            )
        }

        private fun bindEmpty(card: ItemStatisticsTrendCardBinding, context: Context) {
            card.tvTrendPeriod.text = context.getString(com.zero.common.R.string.statistics_trend_placeholder)
            card.tvTrendRange.text = ""
            card.tvTrendFeedingValue.text = context.getString(com.zero.common.R.string.statistics_growth_placeholder)
            card.tvTrendSleepValue.text = context.getString(com.zero.common.R.string.statistics_growth_placeholder)
            card.tvTrendDiaperValue.text = context.getString(com.zero.common.R.string.statistics_growth_placeholder)
            card.tvTrendEventValue.text = context.getString(com.zero.common.R.string.statistics_growth_placeholder)
        }

        /**
         * 将分钟转换为展示文本（避免硬编码文案）
         */
        private fun formatMinutes(context: Context, minutes: Int): String {
            val hours = minutes / 60
            val rest = minutes % 60
            return when {
                hours > 0 && rest > 0 -> context.getString(
                    com.zero.common.R.string.sleep_duration_format,
                    hours,
                    rest
                )
                hours > 0 -> context.getString(com.zero.common.R.string.sleep_duration_hours, hours)
                else -> context.getString(com.zero.common.R.string.sleep_duration_minutes, rest)
            }
        }
    }
}
