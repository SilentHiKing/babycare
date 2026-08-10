package com.zero.common.util

import android.content.Context
import android.content.res.Configuration

/**
 * 当前窗口适配工具。窗口宽度而非物理设备类型决定布局，确保分屏、折叠展开和横竖屏
 * 都能按实际可用空间重排。
*/
object DeviceUtils {
    enum class WindowWidthSizeClass {
        COMPACT,
        MEDIUM,
        EXPANDED
    }

    fun getWindowWidthInDp(context: Context): Int {
        val configurationWidth = context.resources.configuration.screenWidthDp
        if (configurationWidth != Configuration.SCREEN_WIDTH_DP_UNDEFINED && configurationWidth > 0) {
            return configurationWidth
        }
        val metrics = context.resources.displayMetrics
        return (metrics.widthPixels / metrics.density).toInt().coerceAtLeast(1)
    }

    fun getWindowWidthSizeClass(context: Context): WindowWidthSizeClass =
        when (getWindowWidthInDp(context)) {
            in 0 until 600 -> WindowWidthSizeClass.COMPACT
            in 600 until 840 -> WindowWidthSizeClass.MEDIUM
            else -> WindowWidthSizeClass.EXPANDED
        }

    /**
     * 紧凑窗口继续沿用 375dp 手机设计基线；中大窗口使用实际宽度，避免 AutoSize
     * 把手机信息架构等比放大。内容宽度由各页面的 maxWidth 约束控制。
     */
    fun getDesignWidthInDp(context: Context): Int =
        when (getWindowWidthSizeClass(context)) {
            WindowWidthSizeClass.COMPACT -> 375
            WindowWidthSizeClass.MEDIUM,
            WindowWidthSizeClass.EXPANDED -> getWindowWidthInDp(context)
        }
    
    // ==================== 动态网格列数计算 ====================
    
    /**
     * 根据屏幕宽度和单个item最小宽度计算列数
     * @param context Context
     * @param itemMinWidthDp 单个item的最小宽度 (dp)
     * @param horizontalPaddingDp 页面水平边距总和 (dp)
     * @param minColumns 最小列数，默认1
     * @param maxColumns 最大列数，默认不限制
     * @return 计算出的列数
     */
    fun calculateGridColumns(
        context: Context,
        itemMinWidthDp: Float,
        horizontalPaddingDp: Float = 32f,
        minColumns: Int = 1,
        maxColumns: Int = Int.MAX_VALUE
    ): Int {
        val availableWidthDp = getWindowWidthInDp(context) - horizontalPaddingDp
        val columns = (availableWidthDp / itemMinWidthDp).toInt()
        return columns.coerceIn(minColumns, maxColumns)
    }
    
    /**
     * 快速记录网格的列数
     * 每个item至少需要60dp
     */
    fun getQuickRecordColumns(context: Context): Int {
        return calculateGridColumns(
            context = context,
            itemMinWidthDp = 70f,
            horizontalPaddingDp = 32f,
            minColumns = 4,
            maxColumns = 8
        )
    }
}
