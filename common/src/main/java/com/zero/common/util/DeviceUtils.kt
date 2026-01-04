package com.zero.common.util

import android.content.Context
import android.content.res.Configuration
import kotlin.math.max

/**
 * 设备类型检测工具类
 * 用于屏幕适配判断
 */
object DeviceUtils {
    
    /** 判断是否为平板 (sw >= 600dp) */
    fun isTablet(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= 600
    
    /** 判断是否为横屏 */
    fun isLandscape(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    
    /**
     * 获取适配的设计稿宽度
     * - 手机: 375dp
     * - 平板竖屏: 720dp (覆盖8-10寸主流平板)
     * - 平板横屏: 1080dp (保持元素比例合理)
     */
    fun getDesignWidthInDp(context: Context): Int = when {
        isTablet(context) && isLandscape(context) -> 1080
        isTablet(context) -> 720
        else -> 375
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
        val displayMetrics = context.resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        val availableWidthDp = screenWidthDp - horizontalPaddingDp
        val columns = (availableWidthDp / itemMinWidthDp).toInt()
        return columns.coerceIn(minColumns, maxColumns)
    }
    
    /**
     * 快速操作按钮的列数 (底部操作栏)
     * 手机4列，平板根据宽度动态计算
     */
    fun getQuickActionColumns(context: Context): Int {
        return calculateGridColumns(
            context = context,
            itemMinWidthDp = 80f,   // 每个按钮至少80dp宽
            horizontalPaddingDp = 0f,
            minColumns = 4,
            maxColumns = 10
        )
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
