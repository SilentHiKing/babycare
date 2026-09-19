package com.zero.babycare.statistics.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseSingleItemAdapter
import com.zero.babycare.databinding.ItemStatisticsBabyAgeBinding
import com.zero.babycare.statistics.model.DayRecordSectionUiModel

/** 年龄区只渲染统计状态并转发补充生日操作，导航由页面处理。 */
class StatisticsBabyAgeAdapter(private val onAddBirthdate: () -> Unit) :
    BaseSingleItemAdapter<DayRecordSectionUiModel, StatisticsBabyAgeAdapter.BabyAgeViewHolder>() {

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): BabyAgeViewHolder {
        val binding = ItemStatisticsBabyAgeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        binding.btnAddBirthdate.setOnClickListener { onAddBirthdate() }
        return BabyAgeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BabyAgeViewHolder, item: DayRecordSectionUiModel?) {
        with(holder.binding) {
            val canAddBirthdate = item?.showBirthHint == true
            btnAddBirthdate.visibility = if (canAddBirthdate) View.VISIBLE else View.GONE
            btnAddBirthdate.isEnabled = canAddBirthdate
            llBabyAge.visibility = if (item?.babyAgeText.isNullOrBlank()) View.GONE else View.VISIBLE
            tvBabyDays.text = item?.babyAgeText.orEmpty()
            tvRecordCount.text = item?.recordCountText.orEmpty()
        }
    }

    fun bindDayRecord(dayRecord: DayRecordSectionUiModel) {
        if (item != dayRecord) item = dayRecord
    }

    class BabyAgeViewHolder(val binding: ItemStatisticsBabyAgeBinding) :
        RecyclerView.ViewHolder(binding.root)
}
