package com.zero.babycare.babyinfo

import androidx.annotation.StringRes
import com.blankj.utilcode.util.ThreadUtils
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.entity.BabyInfo
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import com.zero.components.base.vm.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


class UpdateInfoViewModel : BaseViewModel() {

    private val _unitState = MutableStateFlow(loadUnitState())
    val unitState: StateFlow<BabyInfoUnitState> = _unitState

    /**
     * 页面重新显示时刷新单位设置，保证设置页改动能同步影响宝宝信息表单。
     */
    fun refreshUnitState() {
        _unitState.value = loadUnitState()
    }

    /**
     * BabyInfo 的出生体重按克存储，出生身高按厘米存储；页面展示跟随设置单位。
     */
    private fun loadUnitState(): BabyInfoUnitState {
        return BabyInfoUnitState(
            weightUnit = UnitConfig.getWeightUnit(),
            weightUnitLabelResId = UnitConfig.getWeightUnitLabelResId(),
            heightUnit = UnitConfig.getHeightUnit(),
            heightUnitLabelResId = UnitConfig.getHeightUnitLabelResId()
        )
    }

    /**
     * 将数据库中的出生体重克值转换为当前设置单位的输入框文案。
     */
    fun formatBirthWeight(storageGrams: Float): String {
        val displayValue = UnitConverter.birthWeightToDisplay(
            storageGrams = storageGrams.toDouble(),
            targetUnit = _unitState.value.weightUnit
        )
        return UnitConverter.formatInputDecimal(displayValue)
    }

    /**
     * 将数据库中的出生身高厘米值转换为当前设置单位的输入框文案。
     */
    fun formatBirthHeight(storageCm: Float): String {
        val displayValue = UnitConverter.heightToDisplay(
            value = storageCm.toDouble(),
            fromUnit = UnitConfig.HEIGHT_UNIT_CM,
            targetUnit = _unitState.value.heightUnit
        )
        return UnitConverter.formatInputDecimal(displayValue)
    }

    /**
     * 将用户按当前体重单位输入的出生体重转换回克存储。
     */
    fun parseBirthWeightToStorage(input: String): Float? {
        val displayValue = input.toDoubleOrNull() ?: return null
        return UnitConverter.birthWeightToStorageGrams(displayValue, _unitState.value.weightUnit)
    }

    /**
     * 将用户按当前身高单位输入的出生身高转换回厘米存储。
     */
    fun parseBirthHeightToStorage(input: String): Float? {
        val displayValue = input.toDoubleOrNull() ?: return null
        return UnitConverter.heightToDisplay(
            value = displayValue,
            fromUnit = _unitState.value.heightUnit,
            targetUnit = UnitConfig.HEIGHT_UNIT_CM
        ).toFloat()
    }

    fun insert(babyInfo: BabyInfo, callback: Runnable) {
        safeLaunch {
            repository.insertBabyInfo(babyInfo) {
                ThreadUtils.runOnUiThread(callback)
            }
        }
    }

    fun update(babyInfo: BabyInfo, callback: Runnable) {
        safeLaunch {
            repository.updateBabyInfo(babyInfo) {
                ThreadUtils.runOnUiThread(callback)
            }
        }
    }
}

data class BabyInfoUnitState(
    val weightUnit: String,
    @param:StringRes val weightUnitLabelResId: Int,
    val heightUnit: String,
    @param:StringRes val heightUnitLabelResId: Int
)
