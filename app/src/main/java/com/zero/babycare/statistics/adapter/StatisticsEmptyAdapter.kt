package com.zero.babycare.statistics.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zero.babycare.databinding.ItemStatisticsEmptyBinding

class StatisticsEmptyAdapter(
    private val onAddRecord: () -> Unit
) : RecyclerView.Adapter<StatisticsEmptyAdapter.EmptyViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmptyViewHolder {
        val binding = ItemStatisticsEmptyBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EmptyViewHolder(binding, onAddRecord)
    }

    override fun onBindViewHolder(holder: EmptyViewHolder, position: Int) = Unit

    override fun getItemCount(): Int = 1

    class EmptyViewHolder(
        binding: ItemStatisticsEmptyBinding,
        onAddRecord: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.tvAddRecord.setOnClickListener { onAddRecord() }
        }
    }
}
