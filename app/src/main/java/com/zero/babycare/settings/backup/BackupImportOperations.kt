package com.zero.babycare.settings.backup

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.zero.babycare.home.BabyRecordChangeBus
import com.zero.babycare.home.OngoingRecordManager
import com.zero.babycare.reminder.ReminderScheduler
import com.zero.babydata.backup.BackupImportError
import com.zero.babydata.backup.BackupImportException
import com.zero.babydata.backup.BackupImportReport
import com.zero.babydata.backup.BackupImportStrategy
import com.zero.babydata.backup.BabyBackupEnvelope
import com.zero.babydata.backup.BabyBackupImporter
import com.zero.babydata.backup.BabyBackupParser
import com.zero.babydata.domain.BabyDataHelper
import com.zero.common.mmkv.MMKVKeys
import com.zero.common.mmkv.MMKVStore

/** 文件读取和实际导入分开，恢复预览永远不能隐式执行数据库写入。调用方必须切到 IO。 */
internal interface BackupImportOperations {
    suspend fun read(uri: Uri): BabyBackupEnvelope
    suspend fun execute(envelope: BabyBackupEnvelope, mode: BackupImportMode, dedupMinutes: Int): BackupImportReport
    fun release(uri: Uri)
}

/** 只持有 Application；授权和派生状态更新不依赖发起选择器的页面。 */
internal class ApplicationBackupImportOperations(private val app: Application) : BackupImportOperations {
    private val importer by lazy { BabyBackupImporter(app) }

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
    ): BackupImportReport {
        val report = when (mode) {
            BackupImportMode.OVERWRITE -> importer.importOverwrite(envelope)
            BackupImportMode.MERGE -> importer.importMerge(envelope, dedupMinutes)
        }
        // 数据提交后刷新当前宝宝、计时和提醒的派生缓存，避免旧状态继续作用于新数据库。
        if (report.strategy == BackupImportStrategy.OVERWRITE) {
            MMKVStore.remove(MMKVKeys.CURRENT_BABY_ID)
            OngoingRecordManager.clearAll()
        }
        MMKVStore.removeByPrefix(MMKVKeys.SETTINGS_FEEDING_REMINDER_LAST_PREFIX)
        MMKVStore.removeByPrefix(MMKVKeys.SETTINGS_SLEEP_REMINDER_LAST_PREFIX)
        MMKVStore.removeByPrefix(MMKVKeys.SETTINGS_FEEDING_REMINDER_SOURCE_PREFIX)
        MMKVStore.removeByPrefix(MMKVKeys.SETTINGS_SLEEP_REMINDER_SOURCE_PREFIX)
        ReminderScheduler.ensureScheduled(app)
        BabyDataHelper.repository.getAllBabyInfo().forEach { BabyRecordChangeBus.notifyChanged(it.babyId) }
        return report
    }

    override fun release(uri: Uri) {
        runCatching {
            app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
