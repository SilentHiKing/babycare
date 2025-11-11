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

    // --- 业务数据 ---
    const val BABY_INFO = "BABY_INFO"
    const val DRAFT_BACKUP = "draft_backup"

    // --- 调试/环境 ---
    const val DEBUG_MODE = "debug_mode"
    const val API_ENV = "api_env"
}
