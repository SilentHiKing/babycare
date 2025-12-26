package com.zero.components.widget

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView Grid 布局的间隔装饰器
 *
 * @param spanCount Grid 的列数
 * @param spacing 间隔大小（像素）
 * @param includeEdge 是否包含边缘间隔
 */
class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacing: Int,
    private val includeEdge: Boolean = true
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return

        // 支持 SpanSizeLookup 的情况
        val layoutManager = parent.layoutManager
        val actualSpanCount = if (layoutManager is GridLayoutManager) {
            layoutManager.spanCount
        } else {
            spanCount
        }

        val column = position % actualSpanCount

        if (includeEdge) {
            // 包含边缘间隔
            // 左边距：spacing - column * spacing / spanCount
            outRect.left = spacing - column * spacing / actualSpanCount
            // 右边距：(column + 1) * spacing / spanCount
            outRect.right = (column + 1) * spacing / actualSpanCount
            // 第一行有顶部间隔
            if (position < actualSpanCount) {
                outRect.top = spacing
            }
            outRect.bottom = spacing
        } else {
            // 不包含边缘间隔
            // 左边距：column * spacing / spanCount
            outRect.left = column * spacing / actualSpanCount
            // 右边距：spacing - (column + 1) * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / actualSpanCount
            // 非第一行有顶部间隔
            if (position >= actualSpanCount) {
                outRect.top = spacing
            }
        }
    }
}

