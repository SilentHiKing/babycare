package com.zero.babycare.settings.backup

/** 确认步骤在 VM 内可重放；不保存备份正文，也不把覆盖授权跨进程恢复。 */
data class BackupImportPrompt(
    val id: String,
    val preview: BackupImportPreview,
    val mode: BackupImportMode = BackupImportMode.MERGE,
    val step: Step = Step.SELECT_MODE
) {
    enum class Step { SELECT_MODE, CONFIRM }
}
