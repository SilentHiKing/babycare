package com.zero.babycare.home.record.event

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.babycare.databinding.ItemEventCategoryBinding

/**
 * 事件大类 Tab 适配器
 */
class EventCategoryAdapter(
    private val onCategorySelected: (EventCategory) -> Unit
) : BaseQuickAdapter<EventCategory, EventCategoryAdapter.VH>() {

    private var selectedCategory: EventCategory? = null

    init {
        // 初始化数据
        submitList(EventCategory.entries.toList())
    }

    inner class VH(
        parent: ViewGroup,
        val binding: ItemEventCategoryBinding = ItemEventCategoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): VH {
        return VH(parent)
    }

    override fun onBindViewHolder(holder: VH, position: Int, item: EventCategory?) {
        if (item == null) return

        val isSelected = item == selectedCategory
        val context = holder.binding.root.context

        with(holder.binding) {
            ivIcon.setImageResource(item.iconResId)
            tvName.setText(item.nameResId)
            rootLayout.isSelected = isSelected

            // 根据选中状态设置图标颜色
            val iconTint = if (isSelected) {
                ContextCompat.getColor(context, com.zero.common.R.color.White100)
            } else {
                ContextCompat.getColor(context, item.colorResId)
            }
            ivIcon.setColorFilter(iconTint)

            root.setOnClickListener {
                if (item != selectedCategory) {
                    val oldPosition = items.indexOf(selectedCategory)
                    selectedCategory = item
                    if (oldPosition >= 0) notifyItemChanged(oldPosition)
                    notifyItemChanged(position)
                    onCategorySelected(item)
                }
            }
        }
    }

    /**
     * 设置选中的分类
     */
    fun setSelectedCategory(category: EventCategory?) {
        if (category != selectedCategory) {
            val oldPosition = items.indexOf(selectedCategory)
            val newPosition = items.indexOf(category)
            selectedCategory = category
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
            if (newPosition >= 0) notifyItemChanged(newPosition)
        }
    }
}
