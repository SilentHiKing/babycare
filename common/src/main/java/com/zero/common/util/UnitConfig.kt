package com.zero.common.util

import androidx.annotation.StringRes
import com.zero.common.R
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore

/**
 * 单位配置读取
 * 统一从本地存储获取当前单位设置，并提供默认值兜底。
 */
object UnitConfig {

    const val FEEDING_UNIT_ML = "ml"
    const val FEEDING_UNIT_OZ = "oz"
    const val WEIGHT_UNIT_KG = "kg"
    const val WEIGHT_UNIT_LB = "lb"
    const val HEIGHT_UNIT_CM = "cm"
    const val HEIGHT_UNIT_IN = "in"

    /**
     * 获取喂养单位（默认 ml）
     */
    fun getFeedingUnit(): String {
        return MMKVStore.getString(MMKVKeys.SETTINGS_FEEDING_UNIT, FEEDING_UNIT_ML) ?: FEEDING_UNIT_ML
    }

    /**
     * 获取体重单位（默认 kg）
     */
    fun getWeightUnit(): String {
        return MMKVStore.getString(MMKVKeys.SETTINGS_WEIGHT_UNIT, WEIGHT_UNIT_KG) ?: WEIGHT_UNIT_KG
    }

    /**
     * 获取身高单位（默认 cm）
     */
    fun getHeightUnit(): String {
        return MMKVStore.getString(MMKVKeys.SETTINGS_HEIGHT_UNIT, HEIGHT_UNIT_CM) ?: HEIGHT_UNIT_CM
    }

    /**
     * 获取喂养单位文案资源
     */
    @StringRes
    fun getFeedingUnitLabelResId(): Int {
        return if (getFeedingUnit() == FEEDING_UNIT_OZ) {
            R.string.unit_oz_abbr
        } else {
            R.string.unit_ml_abbr
        }
    }

    /**
     * 获取体重单位文案资源
     */
    @StringRes
    fun getWeightUnitLabelResId(): Int {
        return if (getWeightUnit() == WEIGHT_UNIT_LB) {
            R.string.unit_lb_abbr
        } else {
            R.string.weight_unit
        }
    }

    /**
     * 获取身高单位文案资源
     */
    @StringRes
    fun getHeightUnitLabelResId(): Int {
        return if (getHeightUnit() == HEIGHT_UNIT_IN) {
            R.string.unit_in_abbr
        } else {
            R.string.height_unit
        }
    }
}
