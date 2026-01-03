package com.zero.components.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
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
            binding.ivLeftActionIcon.visibility = android.view.View.VISIBLE
        } else {
            binding.ivLeftActionIcon.visibility = android.view.View.GONE
        }
        binding.root.contentDescription = item.contentDescription ?: item.text
        binding.root.setOnClickListener { onActionClick(item) }
    }
}
