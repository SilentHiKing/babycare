package com.zero.common.util

import java.text.DecimalFormat
import kotlin.math.roundToInt

/**
 * 单位换算工具
 * 将存储值与展示值做统一转换，避免单位不一致导致的理解偏差。
 */
object UnitConverter {

    private const val OZ_TO_ML = 29.5735
    private const val LB_TO_KG = 0.45359237
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
        // 成长历史与备份可使用 g/oz，统一先换算成 kg，避免将克数当千克显示。
        // 体重 oz 是质量盎司，与喂养量的液体盎司使用不同换算关系。
        val valueInKg = when (fromUnit) {
            UnitConfig.WEIGHT_UNIT_G -> value / 1000.0
            UnitConfig.WEIGHT_UNIT_LB -> value * LB_TO_KG
            UnitConfig.WEIGHT_UNIT_OZ -> value * LB_TO_KG / 16.0
            else -> value
        }
        return when (targetUnit) {
            UnitConfig.WEIGHT_UNIT_G -> valueInKg * 1000.0
            UnitConfig.WEIGHT_UNIT_LB -> valueInKg / LB_TO_KG
            UnitConfig.WEIGHT_UNIT_OZ -> valueInKg / LB_TO_KG * 16.0
            else -> valueInKg
        }
    }

    /**
     * 出生体重历史上以克存储，展示时跟随设置页的 kg/lb，避免直接改数据库语义导致旧数据错读。
     */
    fun birthWeightToDisplay(storageGrams: Double, targetUnit: String): Double {
        val valueInKg = storageGrams / 1000.0
        return weightToDisplay(valueInKg, UnitConfig.WEIGHT_UNIT_KG, targetUnit)
    }

    /**
     * 出生体重保存时统一回写为克，保证既有 BabyInfo 数据和备份结构保持兼容。
     */
    fun birthWeightToStorageGrams(displayValue: Double, displayUnit: String): Float {
        val valueInKg = weightToDisplay(displayValue, displayUnit, UnitConfig.WEIGHT_UNIT_KG)
        return (valueInKg * 1000.0).toFloat()
    }

    /**
     * 通用表单数值格式化（最多 2 位小数），用于 kg/lb/in 这类需要比趋势卡更高精度的输入回填。
     */
    fun formatInputDecimal(value: Double): String {
        return DecimalFormat("0.##").format(value)
    }

    /**
     * 身高/头围：任意单位转为目标单位
     */
    fun heightToDisplay(value: Double, fromUnit: String, targetUnit: String): Double {
        // 毫米历史数据与英寸输入均转换到 cm，再供趋势、时间轴和 WHO 标准使用。
        val valueInCm = when (fromUnit) {
            UnitConfig.HEIGHT_UNIT_MM -> value / 10.0
            UnitConfig.HEIGHT_UNIT_IN -> value * IN_TO_CM
            else -> value
        }
        return when (targetUnit) {
            UnitConfig.HEIGHT_UNIT_MM -> valueInCm * 10.0
            UnitConfig.HEIGHT_UNIT_IN -> valueInCm / IN_TO_CM
            else -> valueInCm
        }
    }

    /**
     * 通用数值格式化（最多 1 位小数）
     */
    fun formatDecimal(value: Double): String {
        return decimalFormat.format(value)
    }
}
