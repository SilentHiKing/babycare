package com.zero.babycare.home.record.event

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.babycare.databinding.ItemEventSubtypeBinding
import com.zero.common.ext.getThemeColor

/**
 * 事件子类型网格适配器
 */
class EventSubtypeAdapter(
    private val onSubtypeSelected: (EventSubtype) -> Unit
) : BaseQuickAdapter<EventSubtype, EventSubtypeAdapter.VH>() {

    private var selectedType: Int? = null
    private var isLocked = false
    private val lockedAlpha = 0.4f

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
            val categoryColor = ContextCompat.getColor(context, item.category.colorResId)
            ivIcon.setColorFilter(categoryColor)

            val brandColor = context.getThemeColor(com.zero.common.R.attr.colorBrand)
            cardRoot.setCardBackgroundColor(
                ContextCompat.getColor(context, com.zero.common.R.color.control_surface_default)
            )

            // 选中态统一使用品牌 tint；业务色只留给图标，避免同一控件出现两套强调色。
            if (isSelected) {
                cardRoot.strokeWidth = context.resources
                    .getDimensionPixelSize(com.zero.common.R.dimen.surface_stroke_width)
                    .coerceAtLeast(1)
                cardRoot.strokeColor = brandColor
            } else {
                cardRoot.strokeWidth = 0
            }
            tvName.setTextColor(
                if (isSelected) {
                    brandColor
                } else {
                    context.getThemeColor(com.zero.common.R.attr.colorTextPrimary)
                }
            )

            cardRoot.setOnClickListener {
                if (isLocked) {
                    return@setOnClickListener
                }
                if (item.type != selectedType) {
                    selectedType = item.type
                    notifyDataSetChanged()
                    onSubtypeSelected(item)
                }
            }
            cardRoot.isEnabled = !isLocked
            cardRoot.alpha = if (isLocked) lockedAlpha else 1f
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

    fun setLocked(locked: Boolean) {
        if (isLocked != locked) {
            isLocked = locked
            notifyDataSetChanged()
        }
    }
}
