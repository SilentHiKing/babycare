package com.zero.babycare.statistics.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zero.babycare.databinding.ItemStatisticsBabyAgeBinding
import com.zero.babycare.statistics.model.DayRecordSectionUiModel

class StatisticsBabyAgeAdapter :
    RecyclerView.Adapter<StatisticsBabyAgeAdapter.BabyAgeViewHolder>() {

    private var babyDaysText: String? = null
    private var recordCountText: String = ""
    private var showBirthHint: Boolean = true
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
        applyState(holder.binding)
    }

    override fun getItemCount(): Int = 1

    fun bindDayRecord(dayRecord: DayRecordSectionUiModel) {
        babyDaysText = dayRecord.babyAgeText
        recordCountText = dayRecord.recordCountText
        showBirthHint = dayRecord.showBirthHint
        binding?.let { applyState(it) }
    }

    fun updateBabyDaysText(text: String?) {
        babyDaysText = text
        showBirthHint = text.isNullOrBlank()
        binding?.let { applyState(it) }
    }

    private fun applyState(binding: ItemStatisticsBabyAgeBinding) {
        binding.flBabyAge.visibility = View.VISIBLE
        binding.llBirthHint.visibility = if (showBirthHint) View.VISIBLE else View.GONE
        binding.llBabyAge.visibility = if (babyDaysText.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.tvBabyDays.text = babyDaysText.orEmpty()
    }

    class BabyAgeViewHolder(val binding: ItemStatisticsBabyAgeBinding) :
        RecyclerView.ViewHolder(binding.root)
}
