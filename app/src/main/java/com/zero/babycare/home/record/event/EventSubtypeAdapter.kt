package com.zero.babycare.home.record.event

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.babycare.databinding.ItemEventSubtypeBinding

/**
 * 事件子类型网格适配器
 */
class EventSubtypeAdapter(
    private val onSubtypeSelected: (EventSubtype) -> Unit
) : BaseQuickAdapter<EventSubtype, EventSubtypeAdapter.VH>() {

    private var selectedType: Int? = null

    inner class VH(
        parent: ViewGroup,
        val binding: ItemEventSubtypeBinding = ItemEventSubtypeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): VH {
        return VH(parent)
    }

    override fun onBindViewHolder(holder: VH, position: Int, item: EventSubtype?) {
        if (item == null) return

        val isSelected = item.type == selectedType
        val context = holder.binding.root.context

        with(holder.binding) {
            ivIcon.setImageResource(item.iconResId)
            tvName.setText(item.nameResId)

            // 设置图标背景颜色（使用分类对应的浅色）
            val lightColorResId = when (item.category) {
                EventCategory.DIAPER -> com.zero.common.R.color.event_diaper_light
                EventCategory.HEALTH -> com.zero.common.R.color.event_health_light
                EventCategory.GROWTH -> com.zero.common.R.color.event_growth_light
                EventCategory.MILESTONE -> com.zero.common.R.color.event_milestone_light
                EventCategory.CARE -> com.zero.common.R.color.event_care_light
                EventCategory.ACTIVITY -> com.zero.common.R.color.event_activity_light
                EventCategory.OTHER -> com.zero.common.R.color.event_other_light
            }

            val bgDrawable = iconContainer.background as? GradientDrawable
                ?: GradientDrawable().also { iconContainer.background = it }
            bgDrawable.setColor(ContextCompat.getColor(context, lightColorResId))
            bgDrawable.shape = GradientDrawable.OVAL

            // 设置图标颜色
            ivIcon.setColorFilter(
                ContextCompat.getColor(context, item.category.colorResId)
            )

            // 选中指示器
            selectedIndicator.visibility = if (isSelected) View.VISIBLE else View.GONE

            // 卡片边框（选中时）
            if (isSelected) {
                cardRoot.strokeWidth = context.resources.getDimensionPixelSize(
                    com.zero.common.R.dimen.dp_2
                )
                cardRoot.strokeColor = ContextCompat.getColor(
                    context,
                    com.zero.common.R.color.darkblue
                )
            } else {
                cardRoot.strokeWidth = 0
            }

            cardRoot.setOnClickListener {
                if (item.type != selectedType) {
                    selectedType = item.type
                    notifyDataSetChanged()
                    onSubtypeSelected(item)
                }
            }
        }
    }

    /**
     * 设置选中的子类型
     */
    fun setSelectedType(type: Int?) {
        if (type != selectedType) {
            selectedType = type
            notifyDataSetChanged()
        }
    }

    /**
     * 获取选中的子类型
     */
    fun getSelectedType(): Int? = selectedType
}
