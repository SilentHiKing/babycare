package com.zero.babycare.statistics.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseSingleItemAdapter
import com.zero.babycare.databinding.ItemStatisticsHealthBinding
import com.zero.babycare.statistics.model.HealthStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 健康/疫苗统计适配器
 */
class StatisticsHealthAdapter :
    BaseSingleItemAdapter<HealthStats, StatisticsHealthAdapter.HealthViewHolder>() {

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): HealthViewHolder {
        val binding = ItemStatisticsHealthBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HealthViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HealthViewHolder, item: HealthStats?) {
        holder.bind(item)
    }

    fun updateHealth(stats: HealthStats?) {
        item = stats
    }

    inner class HealthViewHolder(
        private val binding: ItemStatisticsHealthBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val formatter = DateTimeFormatter.ofPattern("MM.dd")

        fun bind(stats: HealthStats?) {
            val context = binding.root.context
            if (stats == null) {
                bindEmpty(context)
                return
            }

            val range = context.getString(
                com.zero.common.R.string.statistics_trend_range_format,
                stats.startDate.format(formatter),
                stats.endDate.format(formatter)
            )
            binding.tvHealthRange.text = context.getString(
                com.zero.common.R.string.statistics_health_range_format,
                range
            )
            binding.tvHealthTotal.text = context.getString(
                com.zero.common.R.string.statistics_health_total_format,
                stats.totalHealthCount
            )
            binding.tvHealthTemperature.text = context.getString(
                com.zero.common.R.string.statistics_health_temperature_format,
                stats.temperatureCount
            )
            binding.tvHealthMedicine.text = context.getString(
                com.zero.common.R.string.statistics_health_medicine_format,
                stats.medicineCount
            )
            binding.tvHealthDoctor.text = context.getString(
                com.zero.common.R.string.statistics_health_doctor_format,
                stats.doctorCount
            )
            binding.tvHealthSymptom.text = context.getString(
                com.zero.common.R.string.statistics_health_symptom_format,
                stats.symptomCount
            )
            binding.tvHealthVaccine.text = context.getString(
                com.zero.common.R.string.statistics_health_vaccine_format,
                stats.vaccineCount
            )
            binding.tvHealthLastVaccine.text = stats.lastVaccineTime?.let { time ->
                val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
                context.getString(
                    com.zero.common.R.string.statistics_health_last_vaccine_format,
                    date.format(formatter)
                )
            } ?: context.getString(com.zero.common.R.string.statistics_health_last_vaccine_empty)
        }

        private fun bindEmpty(context: Context) {
            val today = LocalDate.now()
            val range = context.getString(
                com.zero.common.R.string.statistics_trend_range_format,
                today.format(formatter),
                today.format(formatter)
            )
            binding.tvHealthRange.text = context.getString(
                com.zero.common.R.string.statistics_health_range_format,
                range
            )
            binding.tvHealthTotal.text = context.getString(
                com.zero.common.R.string.statistics_health_total_format,
                0
            )
            binding.tvHealthTemperature.text = context.getString(
                com.zero.common.R.string.statistics_health_temperature_format,
                0
            )
            binding.tvHealthMedicine.text = context.getString(
                com.zero.common.R.string.statistics_health_medicine_format,
                0
            )
            binding.tvHealthDoctor.text = context.getString(
                com.zero.common.R.string.statistics_health_doctor_format,
                0
            )
            binding.tvHealthSymptom.text = context.getString(
                com.zero.common.R.string.statistics_health_symptom_format,
                0
            )
            binding.tvHealthVaccine.text = context.getString(
                com.zero.common.R.string.statistics_health_vaccine_format,
                0
            )
            binding.tvHealthLastVaccine.text =
                context.getString(com.zero.common.R.string.statistics_health_last_vaccine_empty)
        }
    }
}
