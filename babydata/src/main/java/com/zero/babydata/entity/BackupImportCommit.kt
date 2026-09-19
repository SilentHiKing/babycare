package com.zero.babydata.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 只保存计数报告与被替换的本机 ID；不含备份正文、姓名，也不随宝宝级联删除。 */
@Entity(tableName = "Backup_Import_Commits")
data class BackupImportCommit(
    @PrimaryKey val commitId: String,
    val reportJson: String,
    val replacedBabyIdsJson: String,
    val committedAt: Long,
    val cleanupComplete: Boolean = false,
    val reportConsumed: Boolean = false
)
