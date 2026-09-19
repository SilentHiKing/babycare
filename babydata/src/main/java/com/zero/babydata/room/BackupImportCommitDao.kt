package com.zero.babydata.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zero.babydata.entity.BackupImportCommit

@Dao
interface BackupImportCommitDao {
    @Insert
    suspend fun insertCommit(commit: BackupImportCommit)

    @Query("SELECT * FROM Backup_Import_Commits WHERE commitId = :commitId LIMIT 1")
    suspend fun getCommit(commitId: String): BackupImportCommit?

    @Query("SELECT * FROM Backup_Import_Commits WHERE cleanupComplete = 0 ORDER BY committedAt, commitId")
    suspend fun pendingCleanup(): List<BackupImportCommit>

    @Query("SELECT * FROM Backup_Import_Commits WHERE reportConsumed = 0 ORDER BY committedAt, commitId LIMIT 1")
    suspend fun oldestUnconsumed(): BackupImportCommit?

    @Query("UPDATE Backup_Import_Commits SET cleanupComplete = 1 WHERE commitId = :commitId")
    suspend fun markCleanupComplete(commitId: String)

    @Query("UPDATE Backup_Import_Commits SET reportConsumed = 1 WHERE commitId = :commitId")
    suspend fun consumeReport(commitId: String)
}
