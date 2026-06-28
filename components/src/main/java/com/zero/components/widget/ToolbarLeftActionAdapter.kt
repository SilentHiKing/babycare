package com.zero.components.widget

import android.content.res.ColorStateList
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.common.ext.getThemeColor
import com.zero.components.databinding.ItemToolbarLeftActionBinding

/**
 * 工具栏左侧动作适配器
 */
class ToolbarLeftActionAdapter(
    private val onActionClick: (ToolbarAction) -> Unit
) : BaseQuickAdapter<ToolbarAction, ToolbarLeftActionAdapter.VH>() {

    inner class VH(
        parent: ViewGroup,
        val binding: ItemToolbarLeftActionBinding = ItemToolbarLeftActionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): VH {
        return VH(parent)
    }

    override fun onBindViewHolder(holder: VH, position: Int, item: ToolbarAction?) {
        if (item == null) return
        val binding = holder.binding
        // 左侧动作统一只显示图标，避免左右风格不一致
        if (item.iconRes != null) {
            binding.ivLeftActionIcon.setImageResource(item.iconRes)
            // 返回键是页面导航的低噪音辅助动作，视觉上弱于页面标题；菜单等入口仍保持主文字色。
            val iconColorAttr = if (item.iconRes == com.zero.common.R.drawable.ic_back) {
                com.zero.common.R.attr.colorTextSecondary
            } else {
                com.zero.common.R.attr.colorTextPrimary
            }
            binding.ivLeftActionIcon.imageTintList = ColorStateList.valueOf(
                context.getThemeColor(iconColorAttr)
            )
            binding.ivLeftActionIcon.visibility = android.view.View.VISIBLE
        } else {
            binding.ivLeftActionIcon.visibility = android.view.View.GONE
        }
        binding.root.contentDescription = item.contentDescription ?: item.text
        binding.root.setOnClickListener { onActionClick(item) }
    }
}
