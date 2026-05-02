package com.zero.babycare.home

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.babycare.databinding.ItemQuickRecordBinding
import com.zero.babydata.entity.EventType
import com.zero.common.R

/**
 * 快速记录项数据
 */
data class QuickRecordItem(
    val categoryId: Int,
    @StringRes val nameResId: Int,
    @DrawableRes val iconResId: Int,
    @ColorRes val colorResId: Int,
    @ColorRes val lightColorResId: Int
) {
    companion object {
        /**
         * 获取 Dashboard 显示的快速记录列表
         */
        fun getQuickRecordItems(): List<QuickRecordItem> = listOf(
            QuickRecordItem(
                EventType.CATEGORY_DIAPER,
                R.string.event_category_diaper,
                R.drawable.ic_event_diaper,
                R.color.event_diaper,
                R.color.event_diaper_light
            ),
            QuickRecordItem(
                EventType.CATEGORY_GROWTH,
                R.string.event_category_growth,
                R.drawable.ic_event_growth,
                R.color.event_growth,
                R.color.event_growth_light
            ),
            QuickRecordItem(
                EventType.CATEGORY_HEALTH,
                R.string.event_category_health,
                R.drawable.ic_event_health,
                R.color.event_health,
                R.color.event_health_light
            ),
            QuickRecordItem(
                EventType.CATEGORY_MILESTONE,
                R.string.event_category_milestone,
                R.drawable.ic_event_milestone,
                R.color.event_milestone,
                R.color.event_milestone_light
            )
        )
    }
}

/**
 * 快速记录 RecyclerView 适配器
 */
class QuickRecordAdapter(
    private val onItemClick: (QuickRecordItem) -> Unit
) : BaseQuickAdapter<QuickRecordItem, QuickRecordAdapter.VH>() {

    inner class VH(
        parent: ViewGroup,
        val binding: ItemQuickRecordBinding = ItemQuickRecordBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): VH {
        return VH(parent)
    }

    override fun onBindViewHolder(holder: VH, position: Int, item: QuickRecordItem?) {
        if (item == null) return

        with(holder.binding) {
            val context = root.context
            val color = ContextCompat.getColor(context, item.colorResId)
            val lightColor = ContextCompat.getColor(context, item.lightColorResId)

            // 图标使用语义色，承托面使用同类别浅色，避免旧式高饱和彩色文字造成噪音。
            iconContainer.backgroundTintList = ColorStateList.valueOf(lightColor)
            ivIcon.setImageResource(item.iconResId)
            ivIcon.setColorFilter(color)

            tvName.setText(item.nameResId)

            root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}

