package com.zero.common.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore

/**
 * 语言配置管理
 * 统一读写存储与应用 Locale，避免多处散落处理。
 */
object LanguageManager {

    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_ZH = "zh"
    const val LANGUAGE_EN = "en"

    /**
     * 读取已保存的语言设置（默认跟随系统）
     */
    fun getSavedLanguage(): String {
        return MMKVStore.getString(MMKVKeys.LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
    }

    /**
     * 保存并应用语言设置
     */
    fun updateLanguage(language: String) {
        MMKVStore.put(MMKVKeys.LANGUAGE, language)
        applyLanguage(language)
    }

    /**
     * 应用已保存的语言设置
     */
    fun applySavedLanguage() {
        applyLanguage(getSavedLanguage())
    }

    /**
     * 根据语言码切换应用语言
     */
    private fun applyLanguage(language: String) {
        val locales = when (language) {
            LANGUAGE_ZH -> LocaleListCompat.forLanguageTags("zh-Hans")
            LANGUAGE_EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
