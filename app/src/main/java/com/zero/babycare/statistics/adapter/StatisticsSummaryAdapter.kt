package com.zero.babycare.statistics.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zero.babycare.databinding.ItemStatisticsSummaryBinding
import com.zero.babycare.statistics.model.SummaryMetricType
import com.zero.babycare.statistics.model.SummaryMetricUiModel
import com.zero.common.R as CommonR

class StatisticsSummaryAdapter : RecyclerView.Adapter<StatisticsSummaryAdapter.SummaryViewHolder>() {

    private var metrics: List<SummaryMetricUiModel> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SummaryViewHolder {
        val binding = ItemStatisticsSummaryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SummaryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SummaryViewHolder, position: Int) {
        holder.bind(metrics)
    }

    override fun getItemCount(): Int = 1

    fun updateSummary(items: List<SummaryMetricUiModel>) {
        metrics = items
        notifyItemChanged(0)
    }

    inner class SummaryViewHolder(
        private val binding: ItemStatisticsSummaryBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(metrics: List<SummaryMetricUiModel>) {
            val context = binding.root.context
            val placeholder = context.getString(CommonR.string.statistics_growth_placeholder)

            binding.tvFeedingCount.text = metrics.primaryText(SummaryMetricType.FEEDING, placeholder)
            binding.tvSleepDuration.text = metrics.primaryText(SummaryMetricType.SLEEP, placeholder)
            binding.tvDiaperCount.text = metrics.primaryText(SummaryMetricType.DIAPER, placeholder)
            binding.tvOtherCount.text = metrics.primaryText(SummaryMetricType.OTHER, placeholder)
            binding.tvFeedingDetail.text = metrics.secondaryText(SummaryMetricType.FEEDING, placeholder)
            binding.tvSleepDetail.text = metrics.secondaryText(SummaryMetricType.SLEEP, placeholder)
            binding.tvDiaperDetail.text = metrics.secondaryText(SummaryMetricType.DIAPER, placeholder)
            binding.tvOtherDetail.text = metrics.secondaryText(SummaryMetricType.OTHER, placeholder)
        }
    }

    private fun List<SummaryMetricUiModel>.primaryText(
        type: SummaryMetricType,
        fallback: String
    ): String {
        return firstOrNull { it.type == type }?.primaryText ?: fallback
    }

    private fun List<SummaryMetricUiModel>.secondaryText(
        type: SummaryMetricType,
        fallback: String
    ): String {
        return firstOrNull { it.type == type }?.secondaryText ?: fallback
    }
}
