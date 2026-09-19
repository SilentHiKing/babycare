package com.zero.babydata.backup

/** 不附带记录正文，页面可按原因给出明确反馈而不泄露备份内容。 */
class BackupExportException(val error: BackupExportError) : RuntimeException()

enum class BackupExportError { LIMIT_EXCEEDED, INVALID_DATA }
