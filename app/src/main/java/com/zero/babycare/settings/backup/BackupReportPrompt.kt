package com.zero.babycare.settings.backup

import java.io.Serializable
import java.util.UUID

/** SavedState 只保存报告定位信息；详情在恢复后重读，不把 JSON 正文放入 Bundle。 */
data class BackupReportPrompt(
    val id: String = UUID.randomUUID().toString(),
    val action: Action,
    val fileName: String? = null,
    val filePath: String? = null,
    val lastModified: Long = 0L
) : Serializable {
    enum class Action { DETAIL, DELETE, CLEAR }

    fun item(): BackupReportItem? = if (fileName != null && filePath != null) {
        BackupReportItem(fileName, filePath, lastModified)
    } else null
}
