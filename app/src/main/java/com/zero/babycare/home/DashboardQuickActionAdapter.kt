package com.zero.babycare.home

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.zero.babycare.databinding.ItemDashboardQuickActionBinding
import com.zero.babycare.navigation.NavTarget
import com.zero.common.ext.getThemeColor

data class DashboardQuickAction(
    @DrawableRes val iconResId: Int,
    @StringRes val labelResId: Int,
    val color: DashboardQuickActionColor,
    val target: NavTarget
)

sealed class DashboardQuickActionColor {
    data class Res(@ColorRes val resId: Int) : DashboardQuickActionColor()
    data class Attr(@AttrRes val attrId: Int) : DashboardQuickActionColor()
}

class DashboardQuickActionAdapter(
    private val onItemClick: (DashboardQuickAction) -> Unit
) : BaseQuickAdapter<DashboardQuickAction, DashboardQuickActionAdapter.VH>() {

    inner class VH(
        parent: ViewGroup,
        val binding: ItemDashboardQuickActionBinding = ItemDashboardQuickActionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(context: Context, parent: ViewGroup, viewType: Int): VH {
        return VH(parent)
    }

    override fun onBindViewHolder(holder: VH, position: Int, item: DashboardQuickAction?) {
        if (item == null) return

        val color = resolveColor(holder.itemView.context, item.color)
        with(holder.binding) {
            ivActionIcon.setImageResource(item.iconResId)
            ivActionIcon.setColorFilter(color)
            tvActionLabel.setText(item.labelResId)
            tvActionLabel.setTextColor(color)
            root.setOnClickListener { onItemClick(item) }
        }
    }

    @ColorInt
    private fun resolveColor(context: Context, color: DashboardQuickActionColor): Int {
        return when (color) {
            is DashboardQuickActionColor.Res -> ContextCompat.getColor(context, color.resId)
            is DashboardQuickActionColor.Attr -> context.getThemeColor(color.attrId)
        }
    }
}
