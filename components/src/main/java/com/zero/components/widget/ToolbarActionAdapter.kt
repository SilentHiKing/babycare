package com.zero.components.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.components.databinding.ItemToolbarActionBinding

/**
 * 工具栏右侧多动作适配器
 */
class ToolbarActionAdapter(
    private val onActionClick: (ToolbarAction) -> Unit
) : BaseQuickAdapter<ToolbarAction, ToolbarActionAdapter.VH>() {

    inner class VH(
        parent: ViewGroup,
        val binding: ItemToolbarActionBinding = ItemToolbarActionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): VH {
        return VH(parent)
    }

    override fun onBindViewHolder(holder: VH, position: Int, item: ToolbarAction?) {
        if (item == null) return
        val binding = holder.binding
        val hasIcon = item.iconRes != null
        val hasText = !item.text.isNullOrBlank()

        // 根据数据动态显示图标和文字，满足“图标/文字/图标+文字”的组合
        binding.ivActionIcon.visibility = if (hasIcon) View.VISIBLE else View.GONE
        binding.tvActionText.visibility = if (hasText) View.VISIBLE else View.GONE
        if (hasIcon) {
            binding.ivActionIcon.setImageResource(item.iconRes!!)
        }
        if (hasText) {
            binding.tvActionText.text = item.text
        }

        // 优先使用显式无障碍文案，否则回退到文字
        binding.root.contentDescription = item.contentDescription ?: item.text
        binding.root.setOnClickListener { onActionClick(item) }
    }
}
