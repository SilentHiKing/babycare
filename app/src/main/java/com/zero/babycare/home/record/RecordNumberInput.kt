package com.zero.babycare.home.record

import com.zero.babydata.entity.GrowthData
import com.zero.common.util.DateUtils
import com.zero.common.util.UnitConverter

/**
 * 输入单位和回填基线随草稿保存。显示文字可能经过舍入或长度限制，未改动时必须保留原数值。
 * 原值使用十进制字符串保存，避免把毫秒 Long 经 Double 中转；页面只采集实际输入文字。
 */
data class RecordNumberInput(
    val unit: String,
    val originalText: String? = null,
    val originalValue: String? = null,
    val originalUnit: String = unit
) {
    private fun unchanged(text: String) = originalText != null && text == originalText

    fun number(text: String): Double? =
        (if (unchanged(text)) originalValue else text)?.toDoubleOrNull()?.takeIf { it.isFinite() }

    fun growth(text: String): GrowthData? = number(text)?.let {
        GrowthData(it, if (unchanged(text)) originalUnit else unit)
    }

    fun feedingAmount(text: String): Int? = if (unchanged(text)) {
        originalValue?.toIntOrNull()
    } else {
        number(text)?.let { UnitConverter.feedingToStorage(it, unit) }
    }

    fun durationMillis(text: String): Long = if (unchanged(text)) {
        originalValue?.toLongOrNull() ?: 0L
    } else {
        text.toLongOrNull()?.let(DateUtils::minutesToMilliseconds) ?: 0L
    }

    fun withOriginal(text: String, value: Number?, storedUnit: String = unit) =
        copy(originalText = text, originalValue = value?.toString(), originalUnit = storedUnit)
}
