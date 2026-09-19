package com.zero.babydata.backup

import com.google.gson.Gson
import com.zero.babydata.entity.BackupImportCommit
import com.zero.babydata.room.BabyDatabase

/** 导入提交和界面展示分开持久化；取消或重启只能补善后，永远不会重放导入事务。 */
class BackupImportJournal(private val database: BabyDatabase) {
    suspend fun get(commitId: String): CommittedBackupImport? = database.backupImportCommitDao().getCommit(commitId)?.decode()
    suspend fun pendingCleanup(): List<CommittedBackupImport> = database.backupImportCommitDao().pendingCleanup().map { it.decode() }
    suspend fun oldestUnconsumed(): CommittedBackupImport? = database.backupImportCommitDao().oldestUnconsumed()?.decode()
    suspend fun markCleanupComplete(commitId: String) = database.backupImportCommitDao().markCleanupComplete(commitId)
    suspend fun consumeReport(commitId: String) = database.backupImportCommitDao().consumeReport(commitId)

    /** 只能在与业务导入相同的 Room 事务中调用，保证这条记录存在就代表数据已提交。 */
    internal suspend fun recordCommit(commitId: String, report: BackupImportReport, replacedBabyIds: List<Int>) {
        database.backupImportCommitDao().insertCommit(BackupImportCommit(
            commitId, gson.toJson(report), gson.toJson(replacedBabyIds), System.currentTimeMillis()
        ))
    }

    private fun BackupImportCommit.decode() = CommittedBackupImport(
        commitId, gson.fromJson(reportJson, BackupImportReport::class.java),
        gson.fromJson(replacedBabyIdsJson, IntArray::class.java).toList()
    )

    private companion object { val gson = Gson() }
}

data class CommittedBackupImport(val commitId: String, val report: BackupImportReport, val replacedBabyIds: List<Int>)
