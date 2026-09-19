package com.zero.babycare.settings.backup

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.zero.babydata.backup.BackupImportError
import com.zero.babydata.backup.BackupImportException
import com.zero.babydata.backup.BackupImportJournal
import com.zero.babydata.backup.CommittedBackupImport
import com.zero.babydata.backup.BabyBackupEnvelope
import com.zero.babydata.backup.BabyBackupImporter
import com.zero.babydata.backup.BabyBackupParser
import com.zero.babydata.room.BabyDatabase
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** 文件读取和实际导入分开，恢复预览永远不能隐式执行数据库写入。调用方必须切到 IO。 */
internal interface BackupImportOperations {
    suspend fun read(uri: Uri): BabyBackupEnvelope
    suspend fun execute(envelope: BabyBackupEnvelope, mode: BackupImportMode, dedupMinutes: Int): CommittedBackupImport
    suspend fun recoverReport(): CommittedBackupImport? = null
    suspend fun consumeReport(commitId: String) = Unit
    fun release(uri: Uri)
}

/** 只持有 Application；授权和派生状态更新不依赖发起选择器的页面。 */
internal class ApplicationBackupImportOperations(
    private val app: Application,
    database: BabyDatabase? = null,
    effects: BackupImportSideEffects? = null
) : BackupImportOperations {
    private val database by lazy { database ?: BabyDatabase.getInstance(app) }
    private val importer by lazy { BabyBackupImporter(this.database) }
    private val journal by lazy { BackupImportJournal(this.database) }
    private val coordinator by lazy { BackupImportCommitCoordinator(journal, effects ?: ApplicationBackupImportSideEffects(app)) }

    override suspend fun read(uri: Uri): BabyBackupEnvelope {
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // 提供方只授予临时访问时本次仍可预览，恢复时无法读取则提示重新选择。
        } catch (_: IllegalArgumentException) {
            // file Uri 没有持久授权机制。
        }
        return app.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().use(BabyBackupParser::parse)
        } ?: throw BackupImportException(BackupImportError.INVALID_JSON)
    }

    override suspend fun execute(
        envelope: BabyBackupEnvelope,
        mode: BackupImportMode,
        dedupMinutes: Int
    ): CommittedBackupImport {
        val commitId = UUID.randomUUID().toString()
        val committed = try {
            val report = when (mode) {
                BackupImportMode.OVERWRITE -> importer.importOverwrite(envelope, commitId)
                BackupImportMode.MERGE -> importer.importMerge(envelope, dedupMinutes, commitId)
            }
            CommittedBackupImport(commitId, report, emptyList())
        } catch (cancelled: CancellationException) {
            // 提交前取消由 Room 回滚；提交后取消留下同事务收据，恢复只补善后/报告。
            throw cancelled
        } catch (error: Exception) {
            // 若数据库已提交，回程异常也不能把它改写为“导入失败”。
            journal.get(commitId) ?: throw error
        }
        coordinator.recoverPending()
        return committed
    }

    override suspend fun recoverReport(): CommittedBackupImport? {
        coordinator.recoverPending()
        return journal.oldestUnconsumed()
    }

    override suspend fun consumeReport(commitId: String) = journal.consumeReport(commitId)

    override fun release(uri: Uri) {
        runCatching {
            app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
