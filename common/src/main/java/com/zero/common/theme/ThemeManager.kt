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
     * 宝宝主题枚举。
     *
     * 产品只暴露男孩 / 女孩两套视觉主题，基础 AppTheme 仅作为 Android 样式父类存在。
     */
    enum class BabyTheme(val themeResId: Int) {
        /** 男孩主题 - 天蓝色系 */
        BOY(R.style.AppTheme_Boy),
        /** 女孩主题 - 樱花粉系 */
        GIRL(R.style.AppTheme_Girl)
    }

    private const val KEY_THEME = "baby_theme"

    /**
     * 根据性别字符串获取对应主题
     * @param gender 性别字符串，如 "男"、"女"、"boy"、"girl" 等
     * @return 对应的主题枚举
     */
    fun getThemeByGender(gender: String?): BabyTheme {
        return when {
            gender.isNullOrEmpty() -> BabyTheme.BOY
            gender.contains("男") || gender.lowercase().contains("boy") -> BabyTheme.BOY
            gender.contains("女") || gender.lowercase().contains("girl") -> BabyTheme.GIRL
            // 未识别性别不再进入中性主题，统一回退到男孩主题以保持主题集合只有两种。
            else -> BabyTheme.BOY
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
            val name = MMKVStore.getString(KEY_THEME, BabyTheme.BOY.name) ?: BabyTheme.BOY.name
            BabyTheme.valueOf(name)
        } catch (e: Exception) {
            // 兼容历史版本中已保存的旧主题值，读取失败时回退男孩主题。
            BabyTheme.BOY
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
     * 重置为兜底主题。
     *
     * 兜底值使用男孩主题，避免恢复出产品不再设计的中性主题。
     */
    fun resetToDefaultTheme() {
        saveTheme(BabyTheme.BOY)
    }
}

