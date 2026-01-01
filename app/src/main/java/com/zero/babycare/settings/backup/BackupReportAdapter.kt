package com.zero.babycare.settings.backup

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.babycare.databinding.ItemBackupReportBinding
import com.zero.common.util.DateUtils

/**
 * 导入报告列表适配器
 */
class BackupReportAdapter(
    private val onItemClick: (BackupReportItem) -> Unit,
    private val onItemLongClick: (BackupReportItem) -> Unit
) : BaseQuickAdapter<BackupReportItem, BackupReportAdapter.VH>() {

    inner class VH(
        parent: ViewGroup,
        val binding: ItemBackupReportBinding = ItemBackupReportBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): VH {
        return VH(parent)
    }

    override fun onBindViewHolder(holder: VH, position: Int, item: BackupReportItem?) {
        if (item == null) return
        val context = holder.binding.root.context
        holder.binding.tvReportName.text = item.fileName
        holder.binding.tvReportTime.text = DateUtils.timestampToMMddHHmm(item.lastModified)
        holder.binding.root.setOnClickListener { onItemClick(item) }
        holder.binding.root.setOnLongClickListener {
            onItemLongClick(item)
            true
        }
    }
}
