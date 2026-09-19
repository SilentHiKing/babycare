package com.zero.babycare.statistics

import com.zero.babycare.statistics.widget.PercentileChartSampler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 渲染采样不能丢失首尾、局部尖峰或原始顺序，也不能把成本继续绑定到记录总数。 */
class PercentileChartSamplerTest {
    @Test
    fun emptyNarrowAndSparseInputsKeepTheirDefinedBoundaries() {
        assertArrayEquals(intArrayOf(), sample(intArrayOf()))
        assertArrayEquals(intArrayOf(0), sample(intArrayOf(42)))
        assertArrayEquals(intArrayOf(0, 1), sample(intArrayOf(10, 90)))
        assertArrayEquals(intArrayOf(0, 1, 2), sample(intArrayOf(10, 90, 20)))
        assertArrayEquals(intArrayOf(), sample(intArrayOf(10), width = 0f))
        assertArrayEquals(intArrayOf(), sample(intArrayOf(10), width = Float.NaN))
        assertArrayEquals(intArrayOf(0, 1, 3, 4), sample(intArrayOf(40, 90, 50, 10, 60), width = 1f))
    }

    @Test
    fun eachVisualBucketKeepsBothExtremesInOriginalOrder() {
        // 两桶分别先峰后谷、先谷后峰；中间平点不能挤掉尖峰，横轴仍用原始索引。
        val values = intArrayOf(50, 90, 10, 50, 60, 20, 80, 40, 30)
        assertArrayEquals(intArrayOf(0, 1, 2, 5, 6, 8), sample(values, width = 8f))
    }

    @Test
    fun denseDataHasBoundedOrderedUniqueIndicesAndRetainsBucketExtrema() {
        val values = IntArray(5000) { (it * 37) % 101 }
        val original = values.copyOf()
        val indices = sample(values, width = 320f)
        assertTrue(indices.size <= 2 * 80 + 2)
        assertEquals(0, indices.first())
        assertEquals(values.lastIndex, indices.last())
        assertTrue(indices.asList().zipWithNext().all { (left, right) -> left < right })
        for (bucket in 0 until 80) {
            val members = (1 until values.lastIndex).filter { it.toLong() * 80 / values.lastIndex == bucket.toLong() }
            if (members.isNotEmpty()) {
                val selected = indices.filter { it in members }.map { values[it] }
                assertTrue(selected.contains(members.minOf { values[it] }))
                assertTrue(selected.contains(members.maxOf { values[it] }))
            }
        }
        assertArrayEquals(original, values)
    }

    @Test
    fun flatAndAlternatingSeriesRetainEndsWithoutDuplicatingIndices() {
        for (values in listOf(IntArray(1000) { 50 }, IntArray(1000) { if (it % 2 == 0) 0 else 100 })) {
            val indices = sample(values, width = 12f)
            assertEquals(0, indices.first())
            assertEquals(values.lastIndex, indices.last())
            assertTrue(indices.size <= 8)
            assertEquals(indices.size, indices.toSet().size)
        }
    }

    private fun sample(values: IntArray, width: Float = 100f) =
        PercentileChartSampler.sampleIndices(values, width, 4f)
}
