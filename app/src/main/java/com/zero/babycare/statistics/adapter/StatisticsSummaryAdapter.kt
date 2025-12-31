package com.zero.babycare.statistics.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zero.babycare.databinding.ItemStatisticsSummaryBinding
import com.zero.babycare.statistics.model.DaySummary
import com.zero.common.R as CommonR
import java.time.LocalDate

class StatisticsSummaryAdapter : RecyclerView.Adapter<StatisticsSummaryAdapter.SummaryViewHolder>() {

    private var summary: DaySummary? = null
    private val fallbackSummary = DaySummary.empty(LocalDate.now())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SummaryViewHolder {
        val binding = ItemStatisticsSummaryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SummaryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SummaryViewHolder, position: Int) {
        holder.bind(summary)
    }

    override fun getItemCount(): Int = 1

    fun updateSummary(data: DaySummary?) {
        summary = data
        notifyItemChanged(0)
    }

    inner class SummaryViewHolder(
        private val binding: ItemStatisticsSummaryBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(summary: DaySummary?) {
            val data = summary ?: fallbackSummary
            val context = binding.root.context
            binding.tvFeedingCount.text = context.getString(
                CommonR.string.times_count_format,
                data.feedingCount
            )
            binding.tvSleepDuration.text = data.formatSleepDuration()
            binding.tvDiaperCount.text = context.getString(
                CommonR.string.times_count_format,
                data.totalDiaperCount
            )
            binding.tvOtherCount.text = context.getString(
                CommonR.string.times_count_format,
                data.otherEventCount
            )
        }
    }
}
