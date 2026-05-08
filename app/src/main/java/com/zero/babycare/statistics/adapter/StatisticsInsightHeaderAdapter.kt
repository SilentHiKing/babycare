package com.zero.babycare.statistics.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zero.babycare.databinding.ItemStatisticsInsightHeaderBinding

/**
 * 规律洞察区标题。它只负责建立“今日记录”和“后续洞察”的视觉分段，
 * 不承载任何业务状态，避免 Fragment 之外出现页面流程判断。
 */
class StatisticsInsightHeaderAdapter :
    RecyclerView.Adapter<StatisticsInsightHeaderAdapter.InsightHeaderViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InsightHeaderViewHolder {
        val binding = ItemStatisticsInsightHeaderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return InsightHeaderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: InsightHeaderViewHolder, position: Int) = Unit

    override fun getItemCount(): Int = 1

    class InsightHeaderViewHolder(
        binding: ItemStatisticsInsightHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root)
}
