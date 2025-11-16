package com.zero.babycare.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

import com.chad.library.adapter4.BaseMultiItemAdapter
import com.zero.babycare.databinding.ItemInfoViewBinding
import com.zero.babycare.databinding.ItemTitleViewBinding
import com.zero.babycare.home.bean.DashboardEntity


class DashboardAdapter(data: List<DashboardEntity>) : BaseMultiItemAdapter<DashboardEntity>(data) {

    class ItemVH(val viewBinding: ItemInfoViewBinding) : RecyclerView.ViewHolder(viewBinding.root)

    class HeaderVH(val viewBinding: ItemTitleViewBinding) : RecyclerView.ViewHolder(viewBinding.root)

    init {
        addItemType(
            DashboardEntity.TYPE_INFO,
            object : OnMultiItemAdapterListener<DashboardEntity, ItemVH> {
                override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): ItemVH {
                    val viewBinding =
                        ItemInfoViewBinding.inflate(LayoutInflater.from(context), parent, false)
                    return ItemVH(viewBinding)
                }

                override fun onBind(holder: ItemVH, position: Int, item: DashboardEntity?) {
                    if (item == null) return
                    holder.viewBinding.tvTitle.text = item.title
                    holder.viewBinding.tvContent.text = item.content
                    holder.viewBinding.tvDesc.text = item.desc
                }
            })
        addItemType(
            DashboardEntity.TYPE_NEXT,
            object : OnMultiItemAdapterListener<DashboardEntity, ItemVH> {
                override fun onCreate(context: Context, parent: ViewGroup, viewType: Int): ItemVH {
                    val viewBinding =
                        ItemInfoViewBinding.inflate(LayoutInflater.from(context), parent, false)
                    return ItemVH(viewBinding)
                }

                override fun onBind(holder: ItemVH, position: Int, item: DashboardEntity?) {
                    if (item == null) return
                    holder.viewBinding.tvTitle.text = item.title
                    holder.viewBinding.tvContent.text = item.content
                    holder.viewBinding.tvDesc.text = item.desc
                }
            })
            .addItemType(
                DashboardEntity.TYPE_TITLE,
                object : OnMultiItemAdapterListener<DashboardEntity, HeaderVH> {
                    override fun onCreate(
                        context: Context,
                        parent: ViewGroup,
                        viewType: Int
                    ): HeaderVH {
                        val viewBinding =
                            ItemTitleViewBinding.inflate(
                                LayoutInflater.from(context),
                                parent,
                                false
                            )
                        return HeaderVH(viewBinding)
                    }

                    override fun onBind(holder: HeaderVH, position: Int, item: DashboardEntity?) {
                        if (item == null) return
                        holder.viewBinding.header.text = item.title
                    }

                    override fun isFullSpanItem(itemType: Int): Boolean {
                        return true
                    }

                })
            .onItemViewType { position, list ->
                list[position].type
            }
    }


}
