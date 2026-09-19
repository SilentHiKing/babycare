package com.zero.babycare.babyinfo

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.entity.BabyInfo
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import com.zero.components.base.vm.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import com.zero.components.base.vm.UiState
import java.io.Serializable


class UpdateInfoViewModel(private val savedStateHandle: SavedStateHandle) : BaseViewModel() {

    private val saveResults = Channel<BabyInfoSaveResult>(Channel.BUFFERED)
    val saveEffects = saveResults.receiveAsFlow()

    /** 草稿跟随具体编辑目标保存；配置重建或系统恢复不应抹掉用户尚未提交的输入。 */
    fun getDraft(targetKey: String): BabyInfoDraft? = savedStateHandle.get<BabyInfoDraft>(DRAFT_KEY)
        ?.takeIf { it.targetKey == targetKey }

    fun saveDraft(draft: BabyInfoDraft) {
        savedStateHandle[DRAFT_KEY] = draft
    }

    fun clearDraft() {
        savedStateHandle.remove<BabyInfoDraft>(DRAFT_KEY)
    }

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
        return parsePositiveMeasurement(input) { displayValue ->
            UnitConverter.birthWeightToStorageGrams(displayValue, _unitState.value.weightUnit)
        }
    }

    /**
     * 将用户按当前身高单位输入的出生身高转换回厘米存储。
     */
    fun parseBirthHeightToStorage(input: String): Float? {
        return parsePositiveMeasurement(input) { displayValue ->
            UnitConverter.heightToDisplay(
                value = displayValue,
                fromUnit = _unitState.value.heightUnit,
                targetUnit = UnitConfig.HEIGHT_UNIT_CM
            ).toFloat()
        }
    }

    fun insert(babyInfo: BabyInfo, selectAsCurrent: Boolean) {
        if (uiState.value is UiState.Loading) return
        safeLaunch {
            repository.insertBabyInfo(babyInfo)
            clearDraft()
            saveResults.send(BabyInfoSaveResult(babyInfo, created = true, selectAsCurrent = selectAsCurrent))
        }
    }

    fun update(babyInfo: BabyInfo) {
        if (uiState.value is UiState.Loading) return
        safeLaunch {
            repository.updateBabyInfo(babyInfo)
            clearDraft()
            saveResults.send(BabyInfoSaveResult(babyInfo, created = false, selectAsCurrent = false))
        }
    }

    private companion object {
        const val DRAFT_KEY = "baby_info_draft"
    }
}

/** 校验转换前后的数值，避免非法输入被静默清零或溢出为不可备份的 Infinity。 */
internal fun parsePositiveMeasurement(input: String, convert: (Double) -> Float): Float? {
    val value = input.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    return convert(value).takeIf { it.isFinite() && it > 0f }
}

data class BabyInfoSaveResult(val baby: BabyInfo, val created: Boolean, val selectAsCurrent: Boolean)

data class BabyInfoDraft(
    val targetKey: String,
    val form: BabyInfoFormSnapshot,
    val initial: BabyInfoFormSnapshot
) : Serializable

data class BabyInfoFormSnapshot(
    val name: String = "",
    val birthday: String = "",
    val genderCode: String = "",
    val weight: String = "",
    val height: String = "",
    val bloodType: String = ""
) : Serializable

data class BabyInfoUnitState(
    val weightUnit: String,
    @param:StringRes val weightUnitLabelResId: Int,
    val heightUnit: String,
    @param:StringRes val heightUnitLabelResId: Int
)
