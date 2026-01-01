package com.zero.babycare.statistics.model

import java.time.LocalDate

/**
 * 生长百分位总览
 */
data class GrowthPercentileOverview(
    val weight: GrowthPercentileSeries,
    val height: GrowthPercentileSeries,
    val head: GrowthPercentileSeries,
    val noteResId: Int
)

/**
 * 单项生长百分位序列
 */
data class GrowthPercentileSeries(
    val labelResId: Int,
    val unitLabelResId: Int,
    val latestValue: Double?,
    val latestPercentile: Int?,
    val points: List<GrowthPercentilePoint>
)

/**
 * 百分位曲线上的一个点
 */
data class GrowthPercentilePoint(
    val date: LocalDate,
    val percentile: Int
)
