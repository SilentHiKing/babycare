package com.zero.babycare

import android.view.ContextThemeWrapper
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.babycare.home.QuickRecordAdapter
import com.zero.babycare.home.QuickRecordItem
import com.zero.components.widget.GridSpacingItemDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** 测量真实两列网格，确保 RTL 的相邻触控区仍保留间隔，边缘开关也不反转。 */
@RunWith(AndroidJUnit4::class)
class GridSpacingItemDecorationTest {
    @Test
    fun twoColumnGridPreservesInteriorAndOuterSpacingInBothDirections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, com.zero.common.R.style.AppTheme_Girl)
            val density = context.resources.displayMetrics.density
            val spacing = (8 * density).roundToInt()
            val width = (240 * density).roundToInt()
            val height = (600 * density).roundToInt()
            listOf(false, true).forEach { includeEdge ->
                listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL).forEach { direction ->
                    val adapter = QuickRecordAdapter {}
                    val grid = RecyclerView(context).apply {
                        layoutDirection = direction
                        layoutManager = GridLayoutManager(context, 2)
                        this.adapter = adapter
                        addItemDecoration(GridSpacingItemDecoration(2, spacing, includeEdge))
                    }
                    try {
                        adapter.submitList(QuickRecordItem.getQuickRecordItems())
                        grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        grid.layout(0, 0, width, height)
                        assertEquals("四个真实快速记录项必须完成排版", 4, grid.childCount)
                        val first = requireNotNull(grid.findViewHolderForAdapterPosition(0)).itemView
                        val second = requireNotNull(grid.findViewHolderForAdapterPosition(1)).itemView
                        val row = listOf(first, second).sortedBy { it.left }
                        assertEquals("相邻热区必须保留 8dp 间隔", spacing, row[1].left - row[0].right)
                        val edge = if (includeEdge) spacing else 0
                        assertEquals("左侧外边距必须遵守配置", edge, row[0].left)
                        assertEquals("右侧外边距必须遵守配置", edge, grid.width - row[1].right)
                        assertTrue("网格必须真正按指定方向布局", if (direction == View.LAYOUT_DIRECTION_RTL)
                            first.left > second.left else first.left < second.left)
                    } finally {
                        grid.adapter = null
                    }
                }
            }
        }
    }
}
