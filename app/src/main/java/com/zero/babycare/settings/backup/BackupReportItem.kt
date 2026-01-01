package com.zero.babycare.settings.backup

import com.zero.babydata.backup.BackupImportReport

/**
 * 导入报告列表项
 */
data class BackupReportItem(
    val fileName: String,
    val filePath: String,
    val lastModified: Long,
    val report: BackupImportReport? = null
)

/**
 * 报告导出载体
 */
data class BackupReportExportPayload(
    val fileName: String,
    val json: String
)
