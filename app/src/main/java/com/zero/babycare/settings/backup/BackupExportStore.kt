package com.zero.babycare.settings.backup

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Serializable
import java.util.UUID

/** SavedState 只保存小型描述，备份正文保留在应用私有暂存目录，避免 Bundle 过大。 */
data class BackupExportRequest(
    val token: String,
    val fileName: String,
    val isReport: Boolean
) : Serializable

enum class BackupExportOutcome { SUCCEEDED, FAILED, CANCELLED }

data class BackupDocumentExportResult(
    val fileName: String,
    val isReport: Boolean,
    val outcome: BackupExportOutcome
) : Serializable

sealed interface BackupDocumentExportState {
    data object Idle : BackupDocumentExportState
    data object Writing : BackupDocumentExportState
    data class Finished(val result: BackupDocumentExportResult) : BackupDocumentExportState
}

/** 文件操作由 ViewModel 的 IO 调度器调用，不持有页面或 Activity。 */
internal class BackupExportStore(private val directory: File) {
    fun stage(payload: BackupPayload, isReport: Boolean, protectedToken: String? = null): BackupExportRequest {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建导出暂存目录" }
        // 异常终止可能留下未被 SavedState 引用的文件；保留本次仍在等待选择的文件。
        val expiration = System.currentTimeMillis() - RETENTION_MILLIS
        directory.listFiles()?.filter {
            it.name.endsWith(".json") && it.name != "$protectedToken.json" && it.lastModified() < expiration
        }?.forEach { it.delete() }
        val request = BackupExportRequest(UUID.randomUUID().toString(), payload.fileName, isReport)
        val target = fileFor(request)
        try {
            FileOutputStream(target).use { stream ->
                // 固定缓冲区避免整包 UTF-8/UTF-16 临时数组；同一编码器保留跨块代理对。
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    val buffer = CharArray(8 * 1024)
                    var offset = 0
                    while (offset < payload.json.length) {
                        val count = minOf(buffer.size, payload.json.length - offset)
                        payload.json.toCharArray(buffer, 0, offset, offset + count)
                        writer.write(buffer, 0, count)
                        offset += count
                    }
                    writer.flush()
                    stream.fd.sync()
                }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        return request
    }

    /** 先确认暂存可读再打开目标，失败时不消费正文，供结果确认前的状态恢复使用。 */
    fun writeTo(request: BackupExportRequest, openDestination: () -> OutputStream) {
        fileFor(request).inputStream().use { source ->
            openDestination().use { destination -> source.copyTo(destination) }
        }
    }

    fun discard(request: BackupExportRequest) {
        fileFor(request).delete()
    }

    private fun fileFor(request: BackupExportRequest): File {
        // 暂存路径完全由内部随机标识构造，恢复的文件名不参与路径拼接。
        require(TOKEN_PATTERN.matches(request.token)) { "导出暂存标识无效" }
        return File(directory, "${request.token}.json")
    }

    private companion object {
        val TOKEN_PATTERN = Regex("[0-9a-fA-F-]{36}")
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
