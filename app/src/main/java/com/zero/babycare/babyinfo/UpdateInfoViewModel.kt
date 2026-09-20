package com.zero.babycare.babyinfo

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.babycare.navigation.NavTarget
import com.zero.babydata.domain.BabyDataHelper.repository
import com.zero.babydata.domain.BabyWriteException
import com.zero.babydata.domain.BabyWriteFailure
import com.zero.babydata.entity.BabyInfo
import com.zero.common.util.UnitConfig
import com.zero.common.util.UnitConverter
import com.zero.common.util.CompatDateUtils
import com.zero.common.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.util.UUID


class UpdateInfoViewModel @JvmOverloads constructor(
    private val savedStateHandle: SavedStateHandle,
    private val injectedOperations: BabyInfoSaveOperations? = null
) : ViewModel() {

    private val operations by lazy { injectedOperations ?: RepositoryBabyInfoSaveOperations() }
    private val _saveState = MutableStateFlow<BabyInfoSaveState>(BabyInfoSaveState.Idle)
    val saveState = _saveState.asStateFlow()
    val isSavePending: Boolean get() = _saveState.value != BabyInfoSaveState.Idle

    /** 草稿跟随具体编辑目标保存；配置重建或系统恢复不应抹掉用户尚未提交的输入。 */
    fun getDraft(targetKey: String, stableId: String? = null): BabyInfoDraft? =
        savedStateHandle.get<BabyInfoDraft>(DRAFT_KEY)
            ?.takeIf { it.targetKey == targetKey && (stableId == null || it.stableId == stableId) }

    fun saveDraft(targetKey: String, form: BabyInfoFormSnapshot, initial: BabyInfoFormSnapshot, stableId: String?) {
        val previous = getDraft(targetKey, stableId)
        // 创建身份在首次输入时持久化，提交后丢失进程仍用同一个身份确认结果。
        savedStateHandle[DRAFT_KEY] = BabyInfoDraft(
            targetKey, form, initial,
            draftId = previous?.draftId ?: UUID.randomUUID().toString(),
            stableId = previous?.stableId ?: stableId ?: UUID.randomUUID().toString(),
            weightUnit = previous?.weightUnit ?: _unitState.value.weightUnit,
            heightUnit = previous?.heightUnit ?: _unitState.value.heightUnit
        )
    }

    fun clearDraft() {
        savedStateHandle.remove<BabyInfoDraft>(DRAFT_KEY)
    }

    /** 新草稿使用选择时的瞬间；旧草稿未改生日保留原精度，其余旧文本只能按恢复时区兼容解析。 */
    internal fun resolveBirthdayTimestamp(
        form: BabyInfoFormSnapshot,
        initial: BabyInfoFormSnapshot,
        originalTimestamp: Long?
    ): Long? {
        if (form.birthday.isBlank()) return null
        return form.birthdayTimestamp
            ?: originalTimestamp?.takeIf { it > 0L && form.birthday == initial.birthday }
            ?: CompatDateUtils.stringToTimestamp(form.birthday)?.takeIf { it > 0L }
    }

    private val _unitState = MutableStateFlow(loadUnitState())
    val unitState: StateFlow<BabyInfoUnitState> = _unitState

    /**
     * 页面重新显示时刷新单位设置，保证设置页改动能同步影响宝宝信息表单。
     */
    fun refreshUnitState(draft: BabyInfoDraft? = null) {
        // 恢复的数字始终与输入时的单位一起恢复，不能按后来改变的设置重新解释旧数字。
        _unitState.value = draft?.let { unitState(it.weightUnit, it.heightUnit) } ?: loadUnitState()
    }

    /**
     * BabyInfo 的出生体重按克存储，出生身高按厘米存储；页面展示跟随设置单位。
     */
    private fun loadUnitState(): BabyInfoUnitState {
        return unitState(UnitConfig.getWeightUnit(), UnitConfig.getHeightUnit())
    }

    private fun unitState(weight: String, height: String) = BabyInfoUnitState(
        weightUnit = weight,
        weightUnitLabelResId = when (weight) {
            UnitConfig.WEIGHT_UNIT_LB -> R.string.unit_lb_abbr
            UnitConfig.WEIGHT_UNIT_G -> R.string.unit_g_abbr
            UnitConfig.WEIGHT_UNIT_OZ -> R.string.unit_oz_abbr
            else -> R.string.weight_unit
        },
        heightUnit = height,
        heightUnitLabelResId = if (height == UnitConfig.HEIGHT_UNIT_IN) R.string.unit_in_abbr else R.string.height_unit
    )

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

    /** 写入和完成状态均绑定发起草稿，离开页面或换目标不会清理后来新建的表单。 */
    fun save(babyInfo: BabyInfo, target: NavTarget.BabyInfo) {
        val draft = getDraft(target.key(), if (target.mode == NavTarget.BabyInfo.Mode.EDIT) babyInfo.stableId else null) ?: return
        val submitted = babyInfo.copy(stableId = draft.stableId)
        val saving = BabyInfoSaveState.Saving(draft.draftId, target)
        if (!_saveState.compareAndSet(BabyInfoSaveState.Idle, saving)) return
        viewModelScope.launch {
            try {
                val created = target.mode == NavTarget.BabyInfo.Mode.CREATE
                withContext(Dispatchers.IO) {
                    if (created) submitted.babyId = operations.create(submitted)
                    else if (target.babyId != submitted.babyId || !operations.update(submitted)) {
                        throw BabyWriteException(BabyWriteFailure.BABY_DELETED)
                    }
                }
                _saveState.value = BabyInfoSaveState.Finished(draft.draftId, target,
                    BabyInfoSaveResult(submitted.babyId, submitted.stableId))
            } catch (cancelled: CancellationException) {
                _saveState.compareAndSet(saving, BabyInfoSaveState.Idle)
                throw cancelled
            } catch (failure: Exception) {
                val message = when ((failure as? BabyWriteException)?.reason) {
                    BabyWriteFailure.CONTENT_CONFLICT -> R.string.baby_profile_save_conflict
                    BabyWriteFailure.BABY_DELETED -> R.string.baby_profile_deleted
                    else -> R.string.save_failed
                }
                _saveState.value = BabyInfoSaveState.Finished(draft.draftId, target, errorRes = message)
            }
        }
    }

    fun consumeSaveResult(result: BabyInfoSaveState.Finished): Boolean {
        if (!_saveState.compareAndSet(result, BabyInfoSaveState.Idle)) return false
        if (result.errorRes == null && getDraft(result.target.key())?.draftId == result.draftId) clearDraft()
        return true
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

/** 只返回提交身份；当前资料由 Room 更新，重试结果不能携带旧快照覆盖后来编辑。 */
data class BabyInfoSaveResult(val babyId: Int, val stableId: String)

sealed interface BabyInfoSaveState {
    data object Idle : BabyInfoSaveState
    data class Saving(val draftId: String, val target: NavTarget.BabyInfo) : BabyInfoSaveState
    data class Finished(
        val draftId: String,
        val target: NavTarget.BabyInfo,
        val result: BabyInfoSaveResult? = null,
        @param:StringRes val errorRes: Int? = null
    ) : BabyInfoSaveState {
        fun belongsTo(draft: BabyInfoDraft?, currentTarget: NavTarget): Boolean =
            draft?.draftId == draftId && currentTarget == target
    }
}

/** 可注入的持久化边界，生产实现仍只委托 Repository，不持有页面或 Context。 */
interface BabyInfoSaveOperations {
    suspend fun create(baby: BabyInfo): Int
    suspend fun update(baby: BabyInfo): Boolean
}

private class RepositoryBabyInfoSaveOperations : BabyInfoSaveOperations {
    override suspend fun create(baby: BabyInfo): Int = repository.insertBabyInfoOnce(baby)
    override suspend fun update(baby: BabyInfo): Boolean = repository.updateBabyInfoIfPresent(baby)
}

internal fun NavTarget.BabyInfo.key(): String = "${mode}:${babyId}"

data class BabyInfoDraft(
    val targetKey: String,
    val form: BabyInfoFormSnapshot,
    val initial: BabyInfoFormSnapshot,
    val draftId: String,
    val stableId: String,
    val weightUnit: String,
    val heightUnit: String
) : Serializable

data class BabyInfoFormSnapshot(
    val name: String = "",
    val birthday: String = "",
    val genderCode: String = "",
    val weight: String = "",
    val height: String = "",
    val bloodType: String = "",
    val birthdayTimestamp: Long? = null
) : Serializable {
    companion object {
        // 固定新增字段前的默认 UID；旧序列化流没有时间戳时读为 null，继续走兼容分支。
        private const val serialVersionUID = 1898364289836678547L
    }
}

data class BabyInfoUnitState(
    val weightUnit: String,
    @param:StringRes val weightUnitLabelResId: Int,
    val heightUnit: String,
    @param:StringRes val heightUnitLabelResId: Int
)
