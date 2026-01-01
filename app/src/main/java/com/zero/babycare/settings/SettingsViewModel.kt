package com.zero.babycare.settings

import androidx.annotation.StringRes
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore
import com.zero.components.base.vm.BaseViewModel
import com.zero.babycare.reminder.ReminderScheduler
import com.blankj.utilcode.util.Utils
import com.zero.common.util.LanguageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置页 ViewModel
 * 负责读取与持久化轻量设置项，避免 View 层直接操作存储。
 */
class SettingsViewModel : BaseViewModel() {

    private val _settingsState = MutableStateFlow(loadState())
    val settingsState: StateFlow<SettingsUiState> = _settingsState

    /**
     * 读取本地缓存并构建 UI 状态
     */
    private fun loadState(): SettingsUiState {
        val reminderEnabled = MMKVStore.getBoolean(MMKVKeys.SETTINGS_REMINDER_ENABLED, true)
        val feedingUnit = FeedingUnit.fromValue(MMKVStore.getString(MMKVKeys.SETTINGS_FEEDING_UNIT, null))
        val weightUnit = WeightUnit.fromValue(MMKVStore.getString(MMKVKeys.SETTINGS_WEIGHT_UNIT, null))
        val heightUnit = HeightUnit.fromValue(MMKVStore.getString(MMKVKeys.SETTINGS_HEIGHT_UNIT, null))
        val languageOption = LanguageOption.fromValue(LanguageManager.getSavedLanguage())
        return SettingsUiState(
            reminderEnabled = reminderEnabled,
            feedingUnit = feedingUnit,
            weightUnit = weightUnit,
            heightUnit = heightUnit,
            languageOption = languageOption
        )
    }

    /**
     * 更新提醒开关
     */
    fun setReminderEnabled(enabled: Boolean) {
        MMKVStore.put(MMKVKeys.SETTINGS_REMINDER_ENABLED, enabled)
        // 只触发一次调度更新，避免 View 层直接操作系统服务
        ReminderScheduler.ensureScheduled(Utils.getApp())
        _settingsState.value = _settingsState.value.copy(reminderEnabled = enabled)
    }

    /**
     * 更新喂养单位
     */
    fun setFeedingUnit(unit: FeedingUnit) {
        MMKVStore.put(MMKVKeys.SETTINGS_FEEDING_UNIT, unit.storeValue)
        _settingsState.value = _settingsState.value.copy(feedingUnit = unit)
    }

    /**
     * 更新体重单位
     */
    fun setWeightUnit(unit: WeightUnit) {
        MMKVStore.put(MMKVKeys.SETTINGS_WEIGHT_UNIT, unit.storeValue)
        _settingsState.value = _settingsState.value.copy(weightUnit = unit)
    }

    /**
     * 更新身高单位
     */
    fun setHeightUnit(unit: HeightUnit) {
        MMKVStore.put(MMKVKeys.SETTINGS_HEIGHT_UNIT, unit.storeValue)
        _settingsState.value = _settingsState.value.copy(heightUnit = unit)
    }

    /**
     * 更新语言设置
     */
    fun setLanguage(option: LanguageOption) {
        LanguageManager.updateLanguage(option.storeValue)
        _settingsState.value = _settingsState.value.copy(languageOption = option)
    }
}

/**
 * 设置页 UI 状态
 */
data class SettingsUiState(
    val reminderEnabled: Boolean,
    val feedingUnit: FeedingUnit,
    val weightUnit: WeightUnit,
    val heightUnit: HeightUnit,
    val languageOption: LanguageOption
)

/**
 * 语言选项
 */
enum class LanguageOption(@StringRes val labelResId: Int, val storeValue: String) {
    SYSTEM(com.zero.common.R.string.settings_language_system, LanguageManager.LANGUAGE_SYSTEM),
    ZH(com.zero.common.R.string.settings_language_zh, LanguageManager.LANGUAGE_ZH),
    EN(com.zero.common.R.string.settings_language_en, LanguageManager.LANGUAGE_EN);

    companion object {
        fun fromValue(value: String?): LanguageOption {
            return values().firstOrNull { it.storeValue == value } ?: SYSTEM
        }
    }
}

/**
 * 喂养单位枚举
 */
enum class FeedingUnit(@StringRes val labelResId: Int, val storeValue: String) {
    ML(com.zero.common.R.string.unit_ml_abbr, "ml"),
    OZ(com.zero.common.R.string.unit_oz_abbr, "oz");

    companion object {
        fun fromValue(value: String?): FeedingUnit {
            return values().firstOrNull { it.storeValue == value } ?: ML
        }
    }
}

/**
 * 体重单位枚举
 */
enum class WeightUnit(@StringRes val labelResId: Int, val storeValue: String) {
    KG(com.zero.common.R.string.weight_unit, "kg"),
    LB(com.zero.common.R.string.unit_lb_abbr, "lb");

    companion object {
        fun fromValue(value: String?): WeightUnit {
            return values().firstOrNull { it.storeValue == value } ?: KG
        }
    }
}

/**
 * 身高单位枚举
 */
enum class HeightUnit(@StringRes val labelResId: Int, val storeValue: String) {
    CM(com.zero.common.R.string.height_unit, "cm"),
    IN(com.zero.common.R.string.unit_in_abbr, "in");

    companion object {
        fun fromValue(value: String?): HeightUnit {
            return values().firstOrNull { it.storeValue == value } ?: CM
        }
    }
}
