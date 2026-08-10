package com.zero.common.mmkv

/**
 * 所有存储Key集中管理
 * 防止字符串硬编码 & 保证一致性
 */
object MMKVKeys {

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
    const val SETTINGS_FEEDING_REMINDER_SOURCE_PREFIX = "settings_feeding_reminder_source_"
    const val SETTINGS_SLEEP_REMINDER_SOURCE_PREFIX = "settings_sleep_reminder_source_"

    // 只缓存当前宝宝的数据库 ID，姓名、生日和健康数据始终从 Room 读取。
    const val CURRENT_BABY_ID = "current_baby_id"

    // --- 调试/环境 ---
    const val DEBUG_MODE = "debug_mode"
    const val API_ENV = "api_env"
}
