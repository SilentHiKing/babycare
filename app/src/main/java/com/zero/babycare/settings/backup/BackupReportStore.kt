package com.zero.babycare.settings.backup

import androidx.core.util.AtomicFile
import com.google.gson.Gson
import com.zero.babydata.backup.BackupImportReport
import com.zero.babydata.backup.CommittedBackupImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 报告文件属于本机归档；与备份协议、业务数据库分开，接口便于注入磁盘失败。 */
internal interface BackupReportStore {
    suspend fun archive(commit: CommittedBackupImport): BackupReportItem
    suspend fun list(): List<BackupReportItem>
    suspend fun read(item: BackupReportItem): BackupReportItem
    suspend fun export(item: BackupReportItem): BackupPayload
    suspend fun delete(item: BackupReportItem)
    suspend fun clear(): Int
}

internal class FileBackupReportStore(
    private val directory: File,
    private val displayName: (File) -> String = { it.name }
) : BackupReportStore {
    private val gson = Gson()

    /** 同目录的不同 VM 共用锁，避免保存、删除和导出读取互相穿插。 */
    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.IO) {
        locks.getOrPut(directory.canonicalPath) { Mutex() }.withLock { block() }
    }

    override suspend fun archive(commit: CommittedBackupImport): BackupReportItem = access {
        require(UUID.fromString(commit.commitId).toString() == commit.commitId) { "报告提交标识无效" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建报告目录" }
        val file = File(directory, "${commit.commitId}.json")
        val atomic = AtomicFile(file)
        if (file.exists() || File(file.path + ".bak").exists()) {
            check(readReport(file) == commit.report) { "报告提交内容不一致" }
        } else {
            // 稳定提交 ID 防同秒覆盖；AtomicFile 保证读者只能看到完整的旧版或新版。
            val stream = atomic.startWrite()
            try {
                stream.write(gson.toJson(commit.report).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
                atomic.finishWrite(stream)
                if (readReport(file) != commit.report) throw IOException("报告原子写入未完成")
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        }
        toItem(file).copy(report = commit.report)
    }

    override suspend fun list(): List<BackupReportItem> = access {
        reportFiles().sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name }).map(::toItem)
    }

    override suspend fun read(item: BackupReportItem): BackupReportItem = access {
        val file = checkedFile(item)
        // canonical 路径只做边界校验；Android 数据目录别名不能改变 UI 正在等待的请求身份。
        item.copy(fileName = displayName(file), lastModified = file.lastModified(), report = readReport(file))
    }

    override suspend fun export(item: BackupReportItem): BackupPayload = access {
        val file = checkedFile(item)
        BackupPayload(toItem(file).fileName, AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    }

    override suspend fun delete(item: BackupReportItem) = access {
        val file = checkedFile(item)
        deleteFile(file)
        Unit
    }

    override suspend fun clear(): Int = access {
        var deleted = 0
        var failed = false
        reportFiles().forEach { file ->
            try { deleteFile(file); deleted++ } catch (_: IOException) { failed = true }
        }
        // 部分失败不能宣称清理完成；调用者无论成功失败都重新读取剩余列表。
        if (failed) throw IOException("部分报告未能删除")
        deleted
    }

    private fun reportFiles(): List<File> {
        if (!directory.exists()) return emptyList()
        // AndroidX 统一使用 .new；兼容旧平台留下的 .bak，先恢复再列出，避免遗漏报告。
        (directory.listFiles() ?: throw IOException("无法读取报告目录"))
            .filter { it.isFile && it.name.endsWith(".json.bak") }
            .forEach { AtomicFile(File(it.path.removeSuffix(".bak"))).openRead().close() }
        return (directory.listFiles() ?: throw IOException("无法读取报告目录"))
            .filter { it.isFile && it.name.endsWith(".json") }
    }

    private fun deleteFile(file: File) {
        AtomicFile(file).delete()
        if (listOf(file, File(file.path + ".bak"), File(file.path + ".new")).any { it.exists() }) {
            throw IOException("报告删除失败")
        }
    }

    private fun checkedFile(item: BackupReportItem): File = File(item.filePath).canonicalFile.also {
        require(it.parentFile == directory.canonicalFile && it.name.endsWith(".json")) { "报告路径无效" }
    }

    private fun toItem(file: File) = BackupReportItem(displayName(file), file.absolutePath, file.lastModified())

    private fun readReport(file: File): BackupImportReport {
        val report = AtomicFile(file).openRead().bufferedReader().use {
            gson.fromJson(it, BackupImportReport::class.java)
        } ?: throw IOException("报告内容为空")
        // 旧秒级文件名继续可读；不完整 JSON 不能进入依赖非空策略的 UI。
        @Suppress("SENSELESS_COMPARISON")
        if (report.strategy == null) throw IOException("报告类型无效")
        return report
    }

    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
