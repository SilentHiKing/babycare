package com.zero.common.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
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
     * Android 12 及以前 AppCompat 只更新 Activity 的语言，后台通知和 ViewModel
     * 仍拿到系统语言的 Application。每次读取当前配置，不缓存页面或旧语言 Context。
     */
    fun localizedContext(context: Context): Context {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return app
        val requested = AppCompatDelegate.getApplicationLocales()
        if (requested.isEmpty) return app
        val configuration = Configuration(app.resources.configuration)
        // 保留系统语言作为资源缺失时的回退，与 AppCompat 页面语言叠加规则一致。
        val locales = (0 until requested.size()).mapNotNull { requested[it] } +
            (0 until configuration.locales.size()).map { configuration.locales[it] }
        configuration.setLocales(LocaleList(*locales.distinct().toTypedArray()))
        return app.createConfigurationContext(configuration)
    }

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
