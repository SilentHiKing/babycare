package com.zero.common.mmkv

/**
 * 所有存储Key集中管理
 * 防止字符串硬编码 & 保证一致性
 */
object MMKVKeys {

    // --- 用户信息 ---
    const val USER_INFO = "user_info"
    const val USER_TOKEN = "user_token"
    const val USER_LAST_LOGIN = "user_last_login"

    // --- 应用配置 ---
    const val APP_THEME = "app_theme"
    const val LANGUAGE = "language"
    const val GUIDE_SHOWN = "guide_shown"
    const val SETTINGS_REMINDER_ENABLED = "settings_reminder_enabled"
    const val SETTINGS_FEEDING_UNIT = "settings_feeding_unit"
    const val SETTINGS_WEIGHT_UNIT = "settings_weight_unit"
    const val SETTINGS_HEIGHT_UNIT = "settings_height_unit"
    const val SETTINGS_BACKUP_DEDUP_MINUTES = "settings_backup_dedup_minutes"
    const val SETTINGS_FEEDING_REMINDER_LAST_PREFIX = "settings_feeding_reminder_last_"
    const val SETTINGS_SLEEP_REMINDER_LAST_PREFIX = "settings_sleep_reminder_last_"

    // --- 业务数据 ---
    const val BABY_INFO = "BABY_INFO"
    const val DRAFT_BACKUP = "draft_backup"

    // --- 调试/环境 ---
    const val DEBUG_MODE = "debug_mode"
    const val API_ENV = "api_env"
}
