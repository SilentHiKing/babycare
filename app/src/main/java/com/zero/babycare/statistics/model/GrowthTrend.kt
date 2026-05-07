package com.zero.babycare.statistics.model

import androidx.annotation.StringRes

/**
 * 生长趋势数据
 * 由 ViewModel 统一换算到当前单位，方便 UI 直接展示。
 */
data class GrowthTrend(
    val weight: GrowthTrendItem,
    val height: GrowthTrendItem,
    val head: GrowthTrendItem
) {
    companion object {
        fun empty(): GrowthTrend {
            return GrowthTrend(
                weight = GrowthTrendItem(
                    latestValue = null,
                    diffValue = null,
                    unitLabelResId = com.zero.common.util.UnitConfig.getWeightUnitLabelResId()
                ),
                height = GrowthTrendItem(
                    latestValue = null,
                    diffValue = null,
                    unitLabelResId = com.zero.common.util.UnitConfig.getHeightUnitLabelResId()
                ),
                head = GrowthTrendItem(
                    latestValue = null,
                    diffValue = null,
                    unitLabelResId = com.zero.common.util.UnitConfig.getHeightUnitLabelResId()
                )
            )
        }
    }
}

/**
 * 单项生长趋势数据
 */
data class GrowthTrendItem(
    val latestValue: Double?,
    val diffValue: Double?,
    @StringRes val unitLabelResId: Int
)
