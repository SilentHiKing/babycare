package com.zero.babycare.statistics.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseSingleItemAdapter
import com.zero.babycare.databinding.ItemStatisticsGrowthBinding
import com.zero.babycare.statistics.model.GrowthTrend
import com.zero.babycare.statistics.model.GrowthTrendItem
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import com.zero.common.R as CommonR
import kotlin.math.abs

class StatisticsGrowthAdapter :
    BaseSingleItemAdapter<GrowthTrend, StatisticsGrowthAdapter.GrowthViewHolder>() {

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): GrowthViewHolder {
        val binding = ItemStatisticsGrowthBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return GrowthViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GrowthViewHolder, item: GrowthTrend?) {
        holder.bind(item)
    }

    fun updateTrend(data: GrowthTrend?) {
        item = data
    }

    inner class GrowthViewHolder(
        private val binding: ItemStatisticsGrowthBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(trend: GrowthTrend?) {
            val data = trend ?: buildFallback()
            bindItem(binding.tvWeightValue, binding.tvWeightDiff, data.weight)
            bindItem(binding.tvHeightValue, binding.tvHeightDiff, data.height)
            bindItem(binding.tvHeadValue, binding.tvHeadDiff, data.head)
        }

        private fun buildFallback(): GrowthTrend {
            return GrowthTrend(
                weight = GrowthTrendItem(null, null, UnitConfig.getWeightUnitLabelResId()),
                height = GrowthTrendItem(null, null, UnitConfig.getHeightUnitLabelResId()),
                head = GrowthTrendItem(null, null, UnitConfig.getHeightUnitLabelResId())
            )
        }

        private fun bindItem(
            valueView: android.widget.TextView,
            diffView: android.widget.TextView,
            item: GrowthTrendItem
        ) {
            val context = valueView.context
            val unitLabel = context.getString(item.unitLabelResId)

            if (item.latestValue == null) {
                valueView.text = context.getString(CommonR.string.statistics_growth_placeholder)
                diffView.text = context.getString(CommonR.string.statistics_growth_no_record)
                return
            }

            // 使用统一格式化，确保数值与单位展示一致
            val valueText = context.getString(
                CommonR.string.statistics_value_with_unit_format,
                UnitConverter.formatDecimal(item.latestValue),
                unitLabel
            )
            valueView.text = valueText
            diffView.text = formatDiffText(item.diffValue, unitLabel)
        }

        /**
         * 差值文案生成：无数据/无变化/正负变化
         */
        private fun formatDiffText(diffValue: Double?, unitLabel: String): String {
            val context = binding.root.context
            if (diffValue == null) {
                return context.getString(CommonR.string.statistics_growth_no_record)
            }
            if (abs(diffValue) < 0.01) {
                return context.getString(CommonR.string.statistics_growth_no_change)
            }
            val sign = if (diffValue > 0) "+" else "-"
            val diffText = context.getString(
                CommonR.string.statistics_value_with_unit_format,
                "$sign${UnitConverter.formatDecimal(abs(diffValue))}",
                unitLabel
            )
            return context.getString(CommonR.string.statistics_growth_diff_format, diffText)
        }
    }
}
