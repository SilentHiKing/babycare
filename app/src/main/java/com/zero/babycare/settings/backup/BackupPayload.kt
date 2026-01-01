package com.zero.babycare.settings.backup

import androidx.annotation.StringRes

/**
 * 导出结果载体
 */
data class BackupPayload(
    val fileName: String,
    val json: String
)

/**
 * 导入预览信息
 */
data class BackupImportPreview(
    val babyCount: Int,
    val feedingCount: Int,
    val sleepCount: Int,
    val eventCount: Int,
    val childDailyCount: Int
)

/**
 * 导入模式
 */
enum class BackupImportMode(@StringRes val labelResId: Int) {
    OVERWRITE(com.zero.common.R.string.backup_import_mode_overwrite),
    MERGE(com.zero.common.R.string.backup_import_mode_merge)
}
