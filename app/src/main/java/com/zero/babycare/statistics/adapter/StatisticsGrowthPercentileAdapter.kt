package com.zero.babycare.statistics.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseSingleItemAdapter
import com.zero.babycare.databinding.ItemStatisticsGrowthPercentileBinding
import com.zero.babycare.databinding.ItemStatisticsGrowthPercentileItemBinding
import com.zero.babycare.statistics.model.GrowthPercentileOverview
import com.zero.babycare.statistics.model.GrowthPercentileSeries
import com.zero.common.util.UnitConverter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * 生长百分位适配器
 */
class StatisticsGrowthPercentileAdapter :
    BaseSingleItemAdapter<GrowthPercentileOverview, StatisticsGrowthPercentileAdapter.PercentileViewHolder>() {

    private var selectedDate: LocalDate = LocalDate.now()

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): PercentileViewHolder {
        val binding = ItemStatisticsGrowthPercentileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PercentileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PercentileViewHolder, item: GrowthPercentileOverview?) {
        holder.bind(item)
    }

    fun updatePercentile(overview: GrowthPercentileOverview?) {
        updatePercentile(overview, selectedDate)
    }

    fun updatePercentile(overview: GrowthPercentileOverview?, selectedDate: LocalDate) {
        this.selectedDate = selectedDate
        item = overview
    }

    inner class PercentileViewHolder(
        private val binding: ItemStatisticsGrowthPercentileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val formatter = DateTimeFormatter.ofPattern("MM.dd")

        fun bind(overview: GrowthPercentileOverview?) {
            binding.tvGrowthPercentileContext.text = binding.root.context.getString(
                com.zero.common.R.string.statistics_until_selected_date_format,
                selectedDate.format(formatter)
            )
            if (overview == null) {
                bindEmpty()
                return
            }
            binding.tvGrowthPercentileNote.setText(overview.noteResId)
            bindSeries(binding.itemGrowthPercentileWeight, overview.weight)
            bindSeries(binding.itemGrowthPercentileHeight, overview.height)
            bindSeries(binding.itemGrowthPercentileHead, overview.head)
        }

        private fun bindEmpty() {
            binding.tvGrowthPercentileNote.setText(
                com.zero.common.R.string.statistics_growth_percentile_note_missing_info
            )
            bindSeries(binding.itemGrowthPercentileWeight, null)
            bindSeries(binding.itemGrowthPercentileHeight, null)
            bindSeries(binding.itemGrowthPercentileHead, null)
        }

        private fun bindSeries(
            itemBinding: ItemStatisticsGrowthPercentileItemBinding,
            series: GrowthPercentileSeries?
        ) {
            val context = itemBinding.root.context
            if (series == null) {
                itemBinding.tvGrowthPercentileLabel.text =
                    context.getString(com.zero.common.R.string.statistics_growth_placeholder)
                itemBinding.tvGrowthPercentileValue.text =
                    context.getString(com.zero.common.R.string.statistics_growth_placeholder)
                itemBinding.tvGrowthPercentileRank.text =
                    context.getString(com.zero.common.R.string.statistics_growth_percentile_empty)
                bindChart(itemBinding, null)
                return
            }

            itemBinding.tvGrowthPercentileLabel.text = context.getString(series.labelResId)

            if (series.latestValue == null) {
                itemBinding.tvGrowthPercentileValue.text =
                    context.getString(com.zero.common.R.string.statistics_growth_no_record)
                itemBinding.tvGrowthPercentileRank.text =
                    context.getString(com.zero.common.R.string.statistics_growth_percentile_empty)
            } else {
                val valueText = context.getString(
                    com.zero.common.R.string.statistics_value_with_unit_format,
                    UnitConverter.formatDecimal(series.latestValue),
                    context.getString(series.unitLabelResId)
                )
                itemBinding.tvGrowthPercentileValue.text = valueText
                itemBinding.tvGrowthPercentileRank.text = series.latestPercentile?.let { percentile ->
                    context.getString(com.zero.common.R.string.statistics_growth_percentile_rank_format, percentile)
                } ?: context.getString(com.zero.common.R.string.statistics_growth_percentile_empty)
            }

            bindChart(itemBinding, series)
        }

        /** 日期说明与读屏均来自同一份有效测量；绘图仍保留原索引、原数值和采样规则。 */
        private fun bindChart(
            itemBinding: ItemStatisticsGrowthPercentileItemBinding,
            series: GrowthPercentileSeries?
        ) {
            val chart = itemBinding.chartGrowthPercentile
            val points = series?.points.orEmpty()
            chart.setPercentiles(points.map { it.percentile })
            if (points.isEmpty()) {
                // 列表重用后不能留下上一宝宝或上一日期的范围、曲线与读屏描述。
                itemBinding.tvGrowthPercentilePeriod.text = null
                itemBinding.tvGrowthPercentilePeriod.visibility = View.GONE
                chart.contentDescription = null
                chart.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                chart.visibility = View.GONE
                return
            }

            val context = itemBinding.root.context
            val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(context.resources.configuration.locales[0])
            val period = context.getString(
                com.zero.common.R.string.statistics_growth_percentile_period_format,
                points.size, points.first().date.format(dateFormatter), points.last().date.format(dateFormatter)
            )
            itemBinding.tvGrowthPercentilePeriod.text = period
            itemBinding.tvGrowthPercentilePeriod.visibility = View.VISIBLE
            // 状态层只提供最近 12 次有效百分位，逐项表达日期与 P 值，不让读屏只剩最新数字。
            val measurements = points.joinToString("\n") { point ->
                context.getString(com.zero.common.R.string.statistics_growth_percentile_point_accessibility_format,
                    point.date.format(dateFormatter), point.percentile)
            }
            chart.contentDescription = context.getString(
                com.zero.common.R.string.statistics_growth_percentile_chart_accessibility_format,
                itemBinding.tvGrowthPercentileLabel.text, period, measurements
            )
            chart.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            chart.visibility = View.VISIBLE
        }
    }
}
