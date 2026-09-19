package com.zero.babycare.statistics.widget

import kotlin.math.floor

/** 仅降低曲线的绘制密度；返回原始索引，避免采样后改变时间顺序和横轴间距。 */
internal object PercentileChartSampler {
    fun sampleIndices(values: IntArray, plotWidthPx: Float, bucketWidthPx: Float): IntArray {
        if (values.isEmpty() || !plotWidthPx.isFinite() || plotWidthPx <= 0f) return intArrayOf()
        if (values.size <= 2) return IntArray(values.size) { it }

        val safeBucketWidth = bucketWidthPx.takeIf { it.isFinite() && it > 0f } ?: 1f
        val bucketCount = floor(plotWidthPx / safeBucketWidth).toInt()
            .coerceIn(1, values.size - 2)
        if (values.size.toLong() <= bucketCount * 2L + 2L) return IntArray(values.size) { it }

        val sampled = IntArray(bucketCount * 2 + 2)
        var count = 0
        sampled[count++] = 0
        var activeBucket = -1
        var minimumIndex = 0
        var maximumIndex = 0

        fun appendExtremes() {
            // 按原始先后顺序连接极值，不能总先 min 后 max 而制造不存在的折返。
            sampled[count++] = minOf(minimumIndex, maximumIndex)
            if (minimumIndex != maximumIndex) sampled[count++] = maxOf(minimumIndex, maximumIndex)
        }

        for (index in 1 until values.lastIndex) {
            val bucket = (index.toLong() * bucketCount / values.lastIndex).toInt()
                .coerceAtMost(bucketCount - 1)
            if (bucket != activeBucket) {
                if (activeBucket >= 0) appendExtremes()
                activeBucket = bucket
                minimumIndex = index
                maximumIndex = index
            } else {
                if (values[index] < values[minimumIndex]) minimumIndex = index
                if (values[index] > values[maximumIndex]) maximumIndex = index
            }
        }
        appendExtremes()
        sampled[count++] = values.lastIndex
        return sampled.copyOf(count)
    }
}
