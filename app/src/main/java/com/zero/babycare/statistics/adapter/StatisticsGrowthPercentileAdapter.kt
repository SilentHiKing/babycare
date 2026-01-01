package com.zero.babycare.statistics.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseSingleItemAdapter
import com.zero.babycare.databinding.ItemStatisticsGrowthPercentileBinding
import com.zero.babycare.databinding.ItemStatisticsGrowthPercentileItemBinding
import com.zero.babycare.statistics.model.GrowthPercentileOverview
import com.zero.babycare.statistics.model.GrowthPercentileSeries
import com.zero.common.util.UnitConverter

/**
 * 生长百分位适配器
 */
class StatisticsGrowthPercentileAdapter :
    BaseSingleItemAdapter<GrowthPercentileOverview, StatisticsGrowthPercentileAdapter.PercentileViewHolder>() {

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
        item = overview
    }

    inner class PercentileViewHolder(
        private val binding: ItemStatisticsGrowthPercentileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(overview: GrowthPercentileOverview?) {
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
                itemBinding.chartGrowthPercentile.setPercentiles(emptyList())
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

            itemBinding.chartGrowthPercentile.setPercentiles(series.points.map { it.percentile })
        }
    }
}
