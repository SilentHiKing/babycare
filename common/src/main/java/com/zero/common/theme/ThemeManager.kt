package com.zero.common.theme

import android.app.Activity
import com.zero.common.R
import com.zero.common.mmkv.MMKVStore

/**
 * 主题管理器
 * 根据宝宝性别切换不同的主题色
 */
object ThemeManager {

    /**
     * 宝宝主题枚举
     */
    enum class BabyTheme(val themeResId: Int) {
        /** 默认主题 - 使用 AppTheme (蓝色 #047AFF) */
        DEFAULT(R.style.AppTheme),
        /** 男孩主题 - 天蓝色系 */
        BOY(R.style.AppTheme_Boy),
        /** 女孩主题 - 樱花粉系 */
        GIRL(R.style.AppTheme_Girl),
        /** 中性主题 - 薰衣草紫 */
        NEUTRAL(R.style.AppTheme_Neutral)
    }

    private const val KEY_THEME = "baby_theme"

    /**
     * 根据性别字符串获取对应主题
     * @param gender 性别字符串，如 "男"、"女"、"boy"、"girl" 等
     * @return 对应的主题枚举
     */
    fun getThemeByGender(gender: String?): BabyTheme {
        return when {
            gender.isNullOrEmpty() -> BabyTheme.DEFAULT
            gender.contains("男") || gender.lowercase().contains("boy") -> BabyTheme.BOY
            gender.contains("女") || gender.lowercase().contains("girl") -> BabyTheme.GIRL
            else -> BabyTheme.DEFAULT
        }
    }

    /**
     * 保存当前主题
     */
    fun saveTheme(theme: BabyTheme) {
        MMKVStore.put(KEY_THEME, theme.name)
    }

    /**
     * 获取已保存的主题
     */
    fun getSavedTheme(): BabyTheme {
        return try {
            val name = MMKVStore.getString(KEY_THEME, BabyTheme.DEFAULT.name) ?: BabyTheme.DEFAULT.name
            BabyTheme.valueOf(name)
        } catch (e: Exception) {
            BabyTheme.DEFAULT
        }
    }

    /**
     * 应用主题到 Activity
     * ⚠️ 必须在 super.onCreate() 之前调用
     *
     * @param activity 目标 Activity
     * @param theme 要应用的主题，默认使用已保存的主题
     */
    fun applyTheme(activity: Activity, theme: BabyTheme = getSavedTheme()) {
        activity.setTheme(theme.themeResId)
    }

    /**
     * 切换宝宝并更新主题
     * @param gender 新宝宝的性别
     * @return true 如果主题发生变化需要 recreate Activity
     */
    fun switchBabyTheme(gender: String?): Boolean {
        val newTheme = getThemeByGender(gender)
        val oldTheme = getSavedTheme()

        if (newTheme != oldTheme) {
            saveTheme(newTheme)
            return true
        }
        return false
    }

    /**
     * 判断是否需要切换主题（不执行保存）
     * @param gender 新宝宝的性别
     * @return true 如果主题会发生变化
     */
    fun needsThemeChange(gender: String?): Boolean {
        val newTheme = getThemeByGender(gender)
        val oldTheme = getSavedTheme()
        return newTheme != oldTheme
    }

    /**
     * 重置为默认主题
     */
    fun resetToDefault() {
        saveTheme(BabyTheme.DEFAULT)
    }
}

