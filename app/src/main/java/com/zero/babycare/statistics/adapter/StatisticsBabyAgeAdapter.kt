package com.zero.babycare.statistics.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zero.babycare.databinding.ItemStatisticsBabyAgeBinding

class StatisticsBabyAgeAdapter :
    RecyclerView.Adapter<StatisticsBabyAgeAdapter.BabyAgeViewHolder>() {

    private var babyDaysText: String? = null
    private var binding: ItemStatisticsBabyAgeBinding? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BabyAgeViewHolder {
        val binding = ItemStatisticsBabyAgeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BabyAgeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BabyAgeViewHolder, position: Int) {
        binding = holder.binding
        applyText(holder.binding, babyDaysText)
    }

    override fun getItemCount(): Int = 1

    fun updateBabyDaysText(text: String?) {
        babyDaysText = text
        binding?.let { applyText(it, text) }
    }

    private fun applyText(binding: ItemStatisticsBabyAgeBinding, text: String?) {
        if (text.isNullOrBlank()) {
            binding.flBabyAge.visibility = View.GONE
        } else {
            binding.flBabyAge.visibility = View.VISIBLE
            binding.tvBabyDays.text = text
        }
    }

    class BabyAgeViewHolder(val binding: ItemStatisticsBabyAgeBinding) :
        RecyclerView.ViewHolder(binding.root)
}
