package com.zero.common.util

import java.text.DecimalFormat
import kotlin.math.roundToInt

/**
 * 单位换算工具
 * 将存储值与展示值做统一转换，避免单位不一致导致的理解偏差。
 */
object UnitConverter {

    private const val OZ_TO_ML = 29.5735
    private const val KG_TO_LB = 2.20462
    private const val IN_TO_CM = 2.54
    private val decimalFormat = DecimalFormat("0.#")

    /**
     * 喂养量：存储值（ml）转为展示值
     */
    fun feedingToDisplay(ml: Int, targetUnit: String): Double {
        return if (targetUnit == UnitConfig.FEEDING_UNIT_OZ) {
            ml / OZ_TO_ML
        } else {
            ml.toDouble()
        }
    }

    /**
     * 喂养量：展示值转为存储值（ml）
     */
    fun feedingToStorage(displayValue: Double, displayUnit: String): Int {
        return if (displayUnit == UnitConfig.FEEDING_UNIT_OZ) {
            (displayValue * OZ_TO_ML).roundToInt()
        } else {
            displayValue.roundToInt()
        }
    }

    /**
     * 喂养量展示文案
     */
    fun formatFeedingAmount(value: Double, unit: String): String {
        return if (unit == UnitConfig.FEEDING_UNIT_OZ) {
            // oz 允许小数，避免换算后被截断影响精度感知
            decimalFormat.format(value)
        } else {
            value.roundToInt().toString()
        }
    }

    /**
     * 体重：任意单位转为目标单位
     */
    fun weightToDisplay(value: Double, fromUnit: String, targetUnit: String): Double {
        val valueInKg = if (fromUnit == UnitConfig.WEIGHT_UNIT_LB) {
            value / KG_TO_LB
        } else {
            value
        }
        return if (targetUnit == UnitConfig.WEIGHT_UNIT_LB) {
            valueInKg * KG_TO_LB
        } else {
            valueInKg
        }
    }

    /**
     * 身高/头围：任意单位转为目标单位
     */
    fun heightToDisplay(value: Double, fromUnit: String, targetUnit: String): Double {
        val valueInCm = if (fromUnit == UnitConfig.HEIGHT_UNIT_IN) {
            value * IN_TO_CM
        } else {
            value
        }
        return if (targetUnit == UnitConfig.HEIGHT_UNIT_IN) {
            valueInCm / IN_TO_CM
        } else {
            valueInCm
        }
    }

    /**
     * 通用数值格式化（最多 1 位小数）
     */
    fun formatDecimal(value: Double): String {
        return decimalFormat.format(value)
    }
}
