package com.zero.babycare.statistics.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseSingleItemAdapter
import com.zero.babycare.databinding.ItemStatisticsStructureBinding
import com.zero.babycare.databinding.ItemStatisticsStructureLegendBinding
import com.zero.babycare.databinding.ItemStatisticsStructureSectionBinding
import com.zero.babycare.statistics.model.StructureItem
import com.zero.babycare.statistics.model.StructureOverview
import com.zero.babycare.statistics.model.StructureSection

/**
 * 结构图适配器
 */
class StatisticsStructureAdapter :
    BaseSingleItemAdapter<StructureOverview, StatisticsStructureAdapter.StructureViewHolder>() {

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): StructureViewHolder {
        val binding = ItemStatisticsStructureBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StructureViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StructureViewHolder, item: StructureOverview?) {
        holder.bind(item)
    }

    fun updateStructure(overview: StructureOverview?) {
        item = overview
    }

    inner class StructureViewHolder(
        private val binding: ItemStatisticsStructureBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(overview: StructureOverview?) {
            val sections = overview?.sections.orEmpty()
            binding.llStructureContainer.removeAllViews()

            if (sections.isEmpty()) {
                binding.tvStructureEmpty.visibility = View.VISIBLE
                return
            }
            binding.tvStructureEmpty.visibility = View.GONE

            val inflater = LayoutInflater.from(binding.root.context)
            sections.forEachIndexed { index, section ->
                val sectionBinding = ItemStatisticsStructureSectionBinding.inflate(
                    inflater,
                    binding.llStructureContainer,
                    false
                )
                bindSection(sectionBinding, section)
                if (index > 0) {
                    val params = (sectionBinding.root.layoutParams as? LinearLayout.LayoutParams)
                        ?: LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    params.topMargin = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 8f, binding.root.resources.displayMetrics).toInt()
                    sectionBinding.root.layoutParams = params
                }
                binding.llStructureContainer.addView(sectionBinding.root)
            }
        }

        private fun bindSection(
            sectionBinding: ItemStatisticsStructureSectionBinding,
            section: StructureSection
        ) {
            val context = sectionBinding.root.context
            sectionBinding.tvSectionTitle.text = context.getString(section.titleResId)
            sectionBinding.llSectionBar.removeAllViews()
            sectionBinding.llSectionLegend.removeAllViews()

            val total = section.items.sumOf { it.count }
            if (total <= 0) {
                addEmptyLegend(sectionBinding, context)
                return
            }

            section.items.filter { it.count > 0 }.forEach { item ->
                addBarSegment(sectionBinding.llSectionBar, item)
                addLegend(sectionBinding.llSectionLegend, item, total)
            }
        }

        /**
         * 绘制结构条的颜色段
         */
        private fun addBarSegment(container: LinearLayout, item: StructureItem) {
            val context = container.context
            val view = View(context)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
            params.weight = item.count.toFloat()
            view.layoutParams = params
            view.setBackgroundColor(ContextCompat.getColor(context, item.colorResId))
            container.addView(view)
        }

        /**
         * 绘制结构图图例
         */
        private fun addLegend(
            container: LinearLayout,
            item: StructureItem,
            total: Int
        ) {
            val context = container.context
            val legendBinding = ItemStatisticsStructureLegendBinding.inflate(
                LayoutInflater.from(context),
                container,
                false
            )
            val color = ContextCompat.getColor(context, item.colorResId)
            legendBinding.viewLegendColor.backgroundTintList = ColorStateList.valueOf(color)
            legendBinding.tvLegendLabel.text = context.getString(item.labelResId)
            val percent = if (total == 0) 0 else (item.count * 100f / total).toInt()
            legendBinding.tvLegendValue.text = context.getString(
                com.zero.common.R.string.statistics_structure_legend_format,
                item.count,
                percent
            )
            container.addView(legendBinding.root)
        }

        /**
         * 空态占位
         */
        private fun addEmptyLegend(
            sectionBinding: ItemStatisticsStructureSectionBinding,
            context: Context
        ) {
            val legendBinding = ItemStatisticsStructureLegendBinding.inflate(
                LayoutInflater.from(context),
                sectionBinding.llSectionLegend,
                false
            )
            legendBinding.viewLegendColor.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, com.zero.common.R.color.colorE3))
            legendBinding.tvLegendLabel.text = context.getString(com.zero.common.R.string.statistics_growth_no_record)
            legendBinding.tvLegendValue.text = ""
            sectionBinding.llSectionLegend.addView(legendBinding.root)
        }
    }
}
